package com.kira.jujutsuneon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Обычный Синий (Z) по референсу.
 *
 * Есть живая цель под прицелом — комбо (тики от нажатия, 20 в секунду):
 *   0–4    правая рука к цели, вокруг цели дуга-молния, в ладони синий шар
 *   7–15   синий взрыв на цели, чёрно-белый полумесяц; 10–18 цель притягивается вплотную
 *   15–25  призмы-искры и вихрь; рука к груди
 *   24–35  разворот влево, присед, низкая подсечка
 *   36     удар ногой вверх — цель улетает вверх (урона нет)
 *   44     телепорт на цель; дальше замедление и камера спереди (только у кастующего)
 *   58     правая стопа касается модели цели — круг удара; дальше стопа прижата к цели
 *   70     цель впечатана в землю: воронка, 20 сердец урона
 *   77     камера снова от первого лица; 80–89 отпрыгивает назад; 96 — конец
 * Замедление — сам сценарий (медленнее для всех), поэтому всё синхронно.
 *
 * Нет живой цели — захват 5 блоков: та же рука с синим, блоки притягиваются (10–20 тиков),
 * синий гаснет, дальше блоки плавно идут за прицелом; ЛКМ — бросок.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LapseBlue {

    // ---- таймлайн комбо ----
    static final int T_ARC = 1;
    static final int T_BURST = 7;
    static final int T_PULL_START = 10;
    static final int T_CRESCENT = 12;
    static final int T_PRISM = 15;
    static final int T_PULL_END = 18;
    static final int T_TURN = 24;
    static final int T_KICK_UP = 36;
    static final int T_TP = 44;
    static final int T_APEX = 46;
    static final int T_CONTACT = 58;
    static final int T_IMPACT = 70;
    static final int T_SINK_END = 73;
    static final int T_SLOW_END = 77;
    static final int T_LEAP = 82;
    static final int T_LAND = 89;
    static final int T_END = 96;

    // ---- захват блоков ----
    static final int G_PULL_START = 10;
    static final int G_PULL_END = 20;
    static final int G_FX_END = 24;

    static final double LAUNCH_HEIGHT = 6.0;
    static final double LEAP_BACK = 3.5;
    static final double LEAP_HEIGHT = 1.3;
    static final double CRATER_R = 2.6;
    static final double CRATER_D = 1.6;
    static final float DAMAGE = 40.0f;
    static final double MAX_TARGET_WIDTH = 2.5;
    static final double MAX_TARGET_HEIGHT = 3.6;

    // ---- поза касания (те же числа в tools/gen_lapse_blue_anim.py) ----
    static final double MODEL_SCALE = 0.9375;
    static final double KICK_LEG_PITCH = -10.0;
    static final double TUCK_LEG_PITCH = -70.0;
    static final double TUCK_LEG_BEND = 100.0;
    /** Насколько стопа выше верха модели цели в момент касания (почти вплотную). */
    static final double CONTACT_MARGIN = 0.01;
    /** Сдвиг ступни от центра игрока вбок (правая нога — вправо, левая — влево). */
    static final double FOOT_LAT = 1.9 / 16.0 * MODEL_SCALE;
    static final double FOOT_RIGHT_FWD;
    /** Нижняя точка правой стопы (с ботинком) относительно ног игрока: < 0 — ниже. */
    static final double FOOT_RIGHT_LOW;
    static final double FOOT_LEFT_FWD;
    static final double FOOT_LEFT_LOW;

    static {
        double[] r = foot(KICK_LEG_PITCH, 0.0);
        double[] l = foot(TUCK_LEG_PITCH, TUCK_LEG_BEND);
        FOOT_RIGHT_FWD = r[0];
        FOOT_RIGHT_LOW = r[1];
        FOOT_LEFT_FWD = l[0];
        FOOT_LEFT_LOW = l[1];
    }

    /** {вперёд, низ} стопы относительно ног игрока для бедра thigh° и колена bend° (модель игрока). */
    private static double[] foot(double thighPitch, double bend) {
        double a = Math.toRadians(-thighPitch);
        double s = Math.toRadians(-thighPitch - bend);
        double kneeF = 0.375 * Math.sin(a), kneeY = 0.751 - 0.375 * Math.cos(a);
        double footF = kneeF + 0.375 * Math.sin(s), footY = kneeY - 0.375 * Math.cos(s);
        double low = footY - 0.125 * Math.abs(Math.sin(s)) - 0.0625;
        return new double[]{footF * MODEL_SCALE, low * MODEL_SCALE};
    }

    private LapseBlue() {
    }

    // ================================================================== сценарий (общий для сервера и клиента)

    static final class Script {
        final Vec3 start;
        final float yaw;
        final Vec3 t0;
        final Vec3 kick;
        final double apexY;
        final double groundY;
        final double endY;
        final boolean crater;
        final Vec3 landing;
        final float targetWidth;
        final float targetHeight;
        final Vec3 forward;
        final Vec3 right;

        Script(Vec3 start, float yaw, Vec3 t0, Vec3 kick, double apexY, double groundY, double endY, boolean crater,
               Vec3 landing, float targetWidth, float targetHeight) {
            this.start = start;
            this.yaw = yaw;
            this.t0 = t0;
            this.kick = kick;
            this.apexY = apexY;
            this.groundY = groundY;
            this.endY = endY;
            this.crater = crater;
            this.landing = landing;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            double r = Math.toRadians(yaw);
            this.forward = new Vec3(-Math.sin(r), 0.0, Math.cos(r));
            this.right = new Vec3(-forward.z, 0.0, forward.x);
        }

        void write(FriendlyByteBuf b) {
            writeVec(b, start);
            b.writeFloat(yaw);
            writeVec(b, t0);
            writeVec(b, kick);
            b.writeDouble(apexY);
            b.writeDouble(groundY);
            b.writeDouble(endY);
            b.writeBoolean(crater);
            writeVec(b, landing);
            b.writeFloat(targetWidth);
            b.writeFloat(targetHeight);
        }

        static Script read(FriendlyByteBuf b) {
            Vec3 start = readVec(b);
            float yaw = b.readFloat();
            Vec3 t0 = readVec(b);
            Vec3 kick = readVec(b);
            double apexY = b.readDouble(), groundY = b.readDouble(), endY = b.readDouble();
            boolean crater = b.readBoolean();
            Vec3 landing = readVec(b);
            float w = b.readFloat(), h = b.readFloat();
            return new Script(start, yaw, t0, kick, apexY, groundY, endY, crater, landing, w, h);
        }

        /** Ноги цели в момент t. */
        Vec3 targetAt(double t) {
            if (t < T_PULL_START) return t0;
            if (t < T_PULL_END) {
                double k = smooth((t - T_PULL_START) / (T_PULL_END - T_PULL_START));
                Vec3 p = t0.add(kick.subtract(t0).scale(k));
                return p.add(0.0, 0.25 * Math.sin(Math.PI * k), 0.0);
            }
            if (t < T_KICK_UP) return kick;
            if (t < T_APEX) {
                double k = (t - T_KICK_UP) / (T_APEX - T_KICK_UP);
                k = 1.0 - Math.pow(1.0 - k, 3.0);
                return new Vec3(kick.x, kick.y + (apexY - kick.y) * k, kick.z);
            }
            if (t < T_CONTACT) return new Vec3(kick.x, apexY, kick.z);
            if (t < T_IMPACT) {
                double k = (t - T_CONTACT) / (T_IMPACT - T_CONTACT);
                double f = k * (0.3 + 0.7 * k);
                return new Vec3(kick.x, apexY + (groundY - apexY) * f, kick.z);
            }
            if (t < T_SINK_END) {
                double k = smooth((t - T_IMPACT) / (T_SINK_END - T_IMPACT));
                return new Vec3(kick.x, groundY + (endY - groundY) * k, kick.z);
            }
            return new Vec3(kick.x, endY, kick.z);
        }

        /** Точка над целью: правая стопа ровно над её центром. */
        Vec3 airXZ() {
            return new Vec3(kick.x - right.x * FOOT_LAT - forward.x * FOOT_RIGHT_FWD, 0.0,
                    kick.z - right.z * FOOT_LAT - forward.z * FOOT_RIGHT_FWD);
        }

        /** Ноги игрока в момент t; lift — высота ног игрока над ногами цели, пока он на ней. */
        Vec3 casterAt(double t, double lift) {
            if (t < T_TP) return start;
            Vec3 air = airXZ();
            if (t < T_LEAP) {
                double base = t < T_CONTACT ? apexY : targetAt(t).y;
                return new Vec3(air.x, base + lift, air.z);
            }
            if (t >= T_LAND) return landing;
            Vec3 from = new Vec3(air.x, endY + lift, air.z);
            double k = (t - T_LEAP) / (T_LAND - T_LEAP);
            return new Vec3(
                    from.x + (landing.x - from.x) * k,
                    from.y + (landing.y - from.y) * k + LEAP_HEIGHT * 4.0 * k * (1.0 - k),
                    from.z + (landing.z - from.z) * k);
        }

        /** Оценка lift по хитбоксу (сервер); клиент считает по самой модели цели. */
        double boxLift() {
            return targetHeight + CONTACT_MARGIN - FOOT_RIGHT_LOW;
        }

        Vec3 rightFoot(Vec3 casterFeet) {
            return casterFeet.add(right.scale(FOOT_LAT)).add(forward.scale(FOOT_RIGHT_FWD));
        }

        Vec3 leftFoot(Vec3 casterFeet) {
            return casterFeet.add(right.scale(-FOOT_LAT)).add(forward.scale(FOOT_LEFT_FWD));
        }
    }

    static double smooth(double v) {
        double t = Mth.clamp(v, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static void writeVec(FriendlyByteBuf b, Vec3 v) {
        b.writeDouble(v.x);
        b.writeDouble(v.y);
        b.writeDouble(v.z);
    }

    private static Vec3 readVec(FriendlyByteBuf b) {
        return new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
    }

    // ================================================================== захват блоков (общая математика)

    static final double[][] GRAB_OFFSETS = {
            {0.00, 0.00},
            {0.34, 0.05},
            {-0.34, 0.05},
            {0.08, 0.34},
            {-0.08, -0.34}
    };

    /** Куда встаёт i-й удерживаемый блок (низ блока по центру), если игрок смотрит look из eye. */
    static Vec3 holdPos(Vec3 eye, Vec3 look, int i) {
        Vec3 forward = look.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : look.normalize();
        Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0E-4) right = new Vec3(1.0, 0.0, 0.0);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 center = eye.add(forward.scale(2.75)).add(0.0, -0.30, 0.0);
        double[] o = GRAB_OFFSETS[Math.min(i, GRAB_OFFSETS.length - 1)];
        return center.add(right.scale(o[0])).add(up.scale(o[1]));
    }

    /** Блок по ходу притягивания: стоит на месте, потом по дуге летит к руке. */
    static Vec3 grabPos(Vec3 origin, Vec3 hold, double age) {
        if (age <= G_PULL_START) return origin;
        if (age >= G_PULL_END) return hold;
        double k = smooth((age - G_PULL_START) / (G_PULL_END - G_PULL_START));
        Vec3 p = origin.add(hold.subtract(origin).scale(k));
        return p.add(0.0, 0.6 * Math.sin(Math.PI * k), 0.0);
    }

    // ================================================================== сеть

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "lapse_blue"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, ComboPacket.class, ComboPacket::encode, ComboPacket::decode, ComboPacket::handle);
        CHANNEL.registerMessage(id++, ComboEndPacket.class, ComboEndPacket::encode, ComboEndPacket::decode, ComboEndPacket::handle);
        CHANNEL.registerMessage(id++, GrabPacket.class, GrabPacket::encode, GrabPacket::decode, GrabPacket::handle);
        CHANNEL.registerMessage(id++, GrabEndPacket.class, GrabEndPacket::encode, GrabEndPacket::decode, GrabEndPacket::handle);
    }

    /** S2C: комбо началось. */
    public record ComboPacket(UUID caster, int casterId, int targetId, Script script) {
        static void encode(ComboPacket m, FriendlyByteBuf b) {
            b.writeUUID(m.caster);
            b.writeVarInt(m.casterId);
            b.writeVarInt(m.targetId);
            m.script.write(b);
        }

        static ComboPacket decode(FriendlyByteBuf b) {
            return new ComboPacket(b.readUUID(), b.readVarInt(), b.readVarInt(), Script.read(b));
        }

        static void handle(ComboPacket m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    LapseBlueClient.onCombo(m.caster, m.casterId, m.targetId, m.script)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: комбо закончилось (aborted — оборвано раньше времени). */
    public record ComboEndPacket(UUID caster, boolean aborted) {
        static void encode(ComboEndPacket m, FriendlyByteBuf b) {
            b.writeUUID(m.caster);
            b.writeBoolean(m.aborted);
        }

        static ComboEndPacket decode(FriendlyByteBuf b) {
            return new ComboEndPacket(b.readUUID(), b.readBoolean());
        }

        static void handle(ComboEndPacket m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    LapseBlueClient.onComboEnd(m.caster, m.aborted)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: захват блоков начался. variant: 0 — смотрит вниз, 1 — прямо, 2 — вверх. */
    public record GrabPacket(UUID caster, int casterId, int[] ids, List<Vec3> origins, int variant) {
        static void encode(GrabPacket m, FriendlyByteBuf b) {
            b.writeUUID(m.caster);
            b.writeVarInt(m.casterId);
            b.writeVarIntArray(m.ids);
            b.writeVarInt(m.origins.size());
            for (Vec3 v : m.origins) writeVec(b, v);
            b.writeVarInt(m.variant);
        }

        static GrabPacket decode(FriendlyByteBuf b) {
            UUID caster = b.readUUID();
            int casterId = b.readVarInt();
            int[] ids = b.readVarIntArray(16);
            int n = Math.min(b.readVarInt(), 16);
            List<Vec3> origins = new ArrayList<>(n);
            for (int i = 0; i < n; i++) origins.add(readVec(b));
            return new GrabPacket(caster, casterId, ids, origins, b.readVarInt());
        }

        static void handle(GrabPacket m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    LapseBlueClient.onGrab(m.caster, m.casterId, m.ids, m.origins, m.variant)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: блоки отпущены или брошены — клиент перестаёт вести их сам. */
    public record GrabEndPacket(UUID caster) {
        static void encode(GrabEndPacket m, FriendlyByteBuf b) {
            b.writeUUID(m.caster);
        }

        static GrabEndPacket decode(FriendlyByteBuf b) {
            return new GrabEndPacket(b.readUUID());
        }

        static void handle(GrabEndPacket m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    LapseBlueClient.onGrabEnd(m.caster)));
            ctx.get().setPacketHandled(true);
        }
    }

    // ================================================================== состояние

    private static final class Combo {
        final UUID casterId;
        final ServerLevel level;
        final Script s;
        final int targetId;
        final UUID targetUuid;
        final boolean origMayfly;
        final boolean origFlying;
        int age;
        boolean targetReleased;

        Combo(ServerPlayer caster, LivingEntity target, Script s) {
            this.casterId = caster.getUUID();
            this.level = caster.serverLevel();
            this.s = s;
            this.targetId = target.getId();
            this.targetUuid = target.getUUID();
            this.origMayfly = caster.getAbilities().mayfly;
            this.origFlying = caster.getAbilities().flying;
        }
    }

    private static final class Grab {
        final List<Vec3> origins;
        final long start;

        Grab(List<Vec3> origins, long start) {
            this.origins = origins;
            this.start = start;
        }
    }

    private static final Map<UUID, Combo> COMBOS = new HashMap<>();
    /** Цель → кастующий. */
    private static final Map<UUID, UUID> TARGETS = new HashMap<>();
    private static final Map<UUID, Grab> GRABS = new HashMap<>();
    /** Без урона от падения ещё немного после комбо (цель падает в воронку). */
    private static final Map<UUID, Long> NO_FALL_UNTIL = new HashMap<>();

    /** Игрок сейчас в комбо Синего — как кастующий или как цель. */
    static boolean isBusy(Player player) {
        UUID id = player.getUUID();
        return COMBOS.containsKey(id) || TARGETS.containsKey(id);
    }

    /** Сущность ведёт сценарий Синего (не трогать её физику). */
    static boolean isControlled(Entity entity) {
        return TARGETS.containsKey(entity.getUUID());
    }

    /** Территория поймала игрока — его комбо (как кастующего или цели) обрывается. */
    static void interrupt(ServerPlayer player) {
        Combo own = COMBOS.get(player.getUUID());
        if (own != null) abort(own);
        UUID caster = TARGETS.get(player.getUUID());
        if (caster != null) {
            Combo c = COMBOS.get(caster);
            if (c != null) abort(c);
        }
    }

    // ================================================================== старт комбо

    /** true — комбо началось (цель подходит). */
    static boolean tryStart(ServerPlayer caster, LivingEntity target) {
        if (target == null || !target.isAlive() || target == caster || target.isSpectator()) return false;
        if (isBusy(caster) || TARGETS.containsKey(target.getUUID()) || COMBOS.containsKey(target.getUUID())) return false;
        if (target.getBbWidth() > MAX_TARGET_WIDTH || target.getBbHeight() > MAX_TARGET_HEIGHT) return false;
        if (target.isPassengerOfSameVehicle(caster) || caster.isPassenger() || target.hasPassenger(caster)) return false;
        ServerLevel level = caster.serverLevel();
        Vec3 casterMid = caster.position().add(0.0, 1.0, 0.0);
        if (DomainExpansion.separated(level, casterMid, target.position().add(0.0, target.getBbHeight() * 0.5, 0.0))) {
            return false;
        }

        Script s = buildScript(caster, target);

        if (target.isPassenger()) target.stopRiding();
        target.ejectPassengers();
        if (target instanceof ServerPlayer tp) JujutsuNeonMod.interruptForStun(tp);

        Combo c = new Combo(caster, target, s);
        COMBOS.put(c.casterId, c);
        TARGETS.put(c.targetUuid, c.casterId);

        JujutsuNeonFlightPatch.stopForTechnique(caster);
        caster.getAbilities().mayfly = true;
        caster.onUpdateAbilities();
        caster.setSprinting(false);
        caster.fallDistance = 0.0f;

        ComboPacket packet = new ComboPacket(c.casterId, caster.getId(), target.getId(), s);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> caster), packet);
        if (target instanceof ServerPlayer tp && !tp.equals(caster)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> tp), packet);
        }

        play(level, caster.position(), JujutsuNeonMod.SFX_LAPSE_CAST, 1.0f, 1.0f);
        return true;
    }

    private static Script buildScript(ServerPlayer caster, LivingEntity target) {
        ServerLevel level = caster.serverLevel();
        Vec3 start = caster.position();
        Vec3 t0 = target.position();
        double dx = t0.x - start.x, dz = t0.z - start.z;
        float yaw = dx * dx + dz * dz < 1.0E-4 ? caster.getYRot() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        double r = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(r), 0.0, Math.cos(r));
        float w = target.getBbWidth(), h = target.getBbHeight();

        // Точка пинка: перед игроком, так чтобы вытянутая нога доставала до передней стороны цели.
        double want = 0.72 + w * 0.5;
        Vec3 eyeLow = start.add(0.0, 0.5, 0.0);
        BlockHitResult wall = level.clip(new ClipContext(eyeLow, eyeLow.add(forward.scale(want + w * 0.5 + 0.1)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        double dist = want;
        if (wall.getType() != HitResult.Type.MISS) {
            double free = wall.getLocation().distanceTo(eyeLow) - w * 0.5 - 0.05;
            dist = Mth.clamp(free, 0.35 + w * 0.5, want);
        }
        Vec3 kick = start.add(forward.scale(dist));
        AABB box = boxAt(kick, w, h);
        for (int i = 0; i < 4 && !level.noCollision(box); i++) {
            kick = kick.add(0.0, 0.5, 0.0);
            box = boxAt(kick, w, h);
        }

        // Высота взлёта: над целью нужно место ещё и для игрока.
        double need = h + 2.1;
        double rise = 0.0;
        for (double up = 0.25; up <= LAUNCH_HEIGHT + 1.0E-6; up += 0.25) {
            AABB column = new AABB(kick.x - Math.max(w, 0.7) * 0.5, kick.y + up, kick.z - Math.max(w, 0.7) * 0.5,
                    kick.x + Math.max(w, 0.7) * 0.5, kick.y + up + need, kick.z + Math.max(w, 0.7) * 0.5);
            if (!level.noCollision(column)) break;
            rise = up;
        }
        double apexY = kick.y + rise;

        // Земля под целью.
        BlockHitResult down = level.clip(new ClipContext(kick.add(0.0, 0.4, 0.0), kick.add(0.0, -48.0, 0.0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        boolean ground = down.getType() != HitResult.Type.MISS;
        double groundY = ground ? down.getLocation().y : apexY - 14.0;
        boolean crater = ground && craterAllowed(level, caster, BlockPos.containing(kick.x, groundY - 0.5, kick.z));
        double endY = crater ? craterFloor(level, caster, kick, groundY) : groundY;

        // Куда отпрыгнуть: назад от точки удара, на свободную землю.
        Vec3 airXZ = new Vec3(kick.x, 0.0, kick.z);
        Vec3 landing = null;
        for (double back : new double[]{LEAP_BACK, 3.0, 2.5, 2.0, 1.5}) {
            Vec3 p = new Vec3(airXZ.x - forward.x * back, 0.0, airXZ.z - forward.z * back);
            double top = Math.max(groundY, endY) + 2.5;
            BlockHitResult hit = level.clip(new ClipContext(new Vec3(p.x, top, p.z), new Vec3(p.x, top - 10.0, p.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
            if (hit.getType() == HitResult.Type.MISS) continue;
            Vec3 feet = new Vec3(p.x, hit.getLocation().y, p.z);
            if (level.noCollision(caster, caster.getDimensions(caster.getPose()).makeBoundingBox(feet))) {
                landing = feet;
                break;
            }
        }
        if (landing == null) landing = start;

        return new Script(start, yaw, t0, kick, apexY, groundY, endY, crater, landing, w, h);
    }

    private static AABB boxAt(Vec3 feet, double w, double h) {
        return new AABB(feet.x - w * 0.5, feet.y, feet.z - w * 0.5, feet.x + w * 0.5, feet.y + h, feet.z + w * 0.5);
    }

    private static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        if (state.getDestroySpeed(level, pos) < 0.0f) return false;
        return !state.is(Blocks.END_PORTAL_FRAME) && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL);
    }

    private static boolean craterAllowed(ServerLevel level, ServerPlayer caster, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return breakable(level, pos, state) && !DomainExpansion.denyTechniqueEdit(level, pos, caster);
    }

    /** Блок воронки: центр блока выше дна чаши. */
    private static boolean inBowl(Vec3 center, double groundY, BlockPos pos) {
        double hx = pos.getX() + 0.5 - center.x, hz = pos.getZ() + 0.5 - center.z;
        double r2 = (hx * hx + hz * hz) / (CRATER_R * CRATER_R);
        if (r2 >= 1.0) return false;
        double depth = CRATER_D * (1.0 - r2);
        double cy = pos.getY() + 0.5;
        return cy < groundY + 1.0 && cy > groundY - depth;
    }

    /** Дно воронки под центром (куда цель уйдёт после удара). */
    private static double craterFloor(ServerLevel level, ServerPlayer caster, Vec3 kick, double groundY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = Mth.floor(groundY - 0.01);
        double floor = groundY;
        for (int y = top; y >= top - 3; y--) {
            pos.set(Mth.floor(kick.x), y, Mth.floor(kick.z));
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                floor = y;
                continue;
            }
            if (inBowl(kick, groundY, pos) && craterAllowed(level, caster, pos)) {
                floor = y;
                continue;
            }
            return y + 1.0;
        }
        return floor;
    }

    // ================================================================== тик

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!COMBOS.isEmpty()) {
            Iterator<Map.Entry<UUID, Combo>> it = COMBOS.entrySet().iterator();
            List<Combo> aborted = new ArrayList<>();
            while (it.hasNext()) {
                Combo c = it.next().getValue();
                int r = tickCombo(c);
                if (r == 1) {
                    finish(c, false);
                    it.remove();
                } else if (r == 2) {
                    aborted.add(c);
                }
            }
            for (Combo c : aborted) abort(c);
        }
        if (!NO_FALL_UNTIL.isEmpty()) {
            long now = ServerLifecycleHooks.getCurrentServer().overworld().getGameTime();
            NO_FALL_UNTIL.values().removeIf(until -> until < now);
        }
    }

    /** 0 — дальше, 1 — закончилось, 2 — оборвать. */
    private static int tickCombo(Combo c) {
        ServerPlayer caster = c.level.getServer().getPlayerList().getPlayer(c.casterId);
        if (caster == null || !caster.isAlive() || caster.level() != c.level) return 2;
        Entity raw = c.level.getEntity(c.targetId);
        LivingEntity target = raw instanceof LivingEntity le ? le : null;
        boolean targetAlive = target != null && target.isAlive();
        if (!targetAlive && c.age < T_IMPACT) return 2;

        int t = c.age;
        Script s = c.s;
        caster.fallDistance = 0.0f;
        caster.setSprinting(false);

        // Грубая страховка: клиент сам ведёт игрока по сценарию; если он «потерялся» — возвращаем.
        Vec3 expected = s.casterAt(t, s.boxLift());
        if (caster.position().distanceToSqr(expected) > 12.0 * 12.0) {
            caster.connection.teleport(expected.x, expected.y, expected.z, caster.getYRot(), caster.getXRot());
        }

        if (targetAlive && !c.targetReleased) {
            Vec3 p = s.targetAt(t);
            if (target instanceof ServerPlayer tp) {
                tp.fallDistance = 0.0f;
                if (tp.position().distanceToSqr(p) > 4.0 * 4.0) {
                    tp.connection.teleport(p.x, p.y, p.z, tp.getYRot(), tp.getXRot());
                }
            } else {
                target.setPos(p.x, p.y, p.z);
                target.setDeltaMovement(Vec3.ZERO);
                target.fallDistance = 0.0f;
                if (target instanceof Mob mob) {
                    mob.getNavigation().stop();
                    mob.setTarget(null);
                    mob.setJumping(false);
                }
            }
        }

        Vec3 tCenter = s.targetAt(t).add(0.0, s.targetHeight * 0.5, 0.0);
        switch (t) {
            case T_BURST -> {
                play(c.level, tCenter, JujutsuNeonMod.SFX_LAPSE_BURST, 1.1f, 1.0f);
                c.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, tCenter.x, tCenter.y, tCenter.z, 24, 0.5, 0.6, 0.5, 0.25);
            }
            case T_PULL_START -> play(c.level, tCenter, JujutsuNeonMod.SFX_LAPSE_PULL, 0.9f, 1.0f);
            case T_KICK_UP -> {
                play(c.level, tCenter, JujutsuNeonMod.SFX_LAPSE_KICK, 1.1f, 1.0f);
                c.level.sendParticles(ParticleTypes.CLOUD, s.kick.x, s.kick.y + 0.1, s.kick.z, 14, 0.6, 0.05, 0.6, 0.08);
            }
            case T_TP -> {
                // Телепорт делает сервер: иначе на сервере «moved too quickly» при высоком подъёме.
                Vec3 air = s.casterAt(T_TP, s.boxLift());
                caster.connection.teleport(air.x, air.y, air.z, s.yaw, caster.getXRot());
                play(c.level, s.start.add(0.0, 1.0, 0.0), JujutsuNeonMod.SFX_LAPSE_BLINK, 0.9f, 1.0f);
                play(c.level, tCenter, JujutsuNeonMod.SFX_LAPSE_SLOWMO, 1.2f, 1.0f);
            }
            case T_CONTACT -> play(c.level, tCenter.add(0.0, s.targetHeight * 0.5, 0.0), JujutsuNeonMod.SFX_LAPSE_CONTACT, 1.3f, 1.0f);
            case T_IMPACT -> impact(c, caster, targetAlive ? target : null);
            case T_LAND -> play(c.level, s.landing, JujutsuNeonMod.SFX_LAPSE_LAND, 0.8f, 1.0f);
            default -> {
            }
        }

        if (t == T_LEAP && !c.targetReleased) releaseTarget(c, target);

        c.age++;
        return c.age > T_END ? 1 : 0;
    }

    private static void impact(Combo c, ServerPlayer caster, LivingEntity target) {
        Script s = c.s;
        Vec3 ground = new Vec3(s.kick.x, s.groundY, s.kick.z);
        play(c.level, ground, JujutsuNeonMod.SFX_LAPSE_SLAM, 1.6f, 1.0f);
        if (s.crater) carveCrater(c.level, caster, ground);
        c.level.sendParticles(ParticleTypes.EXPLOSION, ground.x, ground.y + 0.4, ground.z, 2, 0.4, 0.1, 0.4, 0.0);
        c.level.sendParticles(ParticleTypes.CLOUD, ground.x, ground.y + 0.2, ground.z, 30, 1.6, 0.1, 1.6, 0.12);

        if (target != null && target.isAlive()) {
            caster.getPersistentData().putBoolean("jn_blue_custom_damage", true);
            try {
                target.invulnerableTime = 0;
                target.hurt(c.level.damageSources().playerAttack(caster), DAMAGE);
            } finally {
                caster.getPersistentData().putBoolean("jn_blue_custom_damage", false);
            }
            target.setDeltaMovement(Vec3.ZERO);
            target.hurtMarked = true;
        }
    }

    private static void carveCrater(ServerLevel level, ServerPlayer caster, Vec3 center) {
        int r = (int) Math.ceil(CRATER_R);
        int top = Mth.floor(center.y - 0.01) + 1;
        int bottom = Mth.floor(center.y - CRATER_D) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int shown = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int y = top; y >= bottom; y--) {
                    pos.set(Mth.floor(center.x) + dx, y, Mth.floor(center.z) + dz);
                    if (!inBowl(center, center.y, pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    // Над поверхностью убираем только траву, цветы и т.п.
                    if (y >= top && !state.canBeReplaced()) continue;
                    if (!breakable(level, pos, state)) continue;
                    if (DomainExpansion.denyTechniqueEdit(level, pos, caster)) continue;
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    if (shown < 14 && (dx + dz + y) % 2 == 0) {
                        level.levelEvent(2001, pos, Block.getId(state));
                        shown++;
                    }
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                            pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 4, 0.25, 0.2, 0.25, 0.35);
                }
            }
        }
    }

    private static void releaseTarget(Combo c, LivingEntity target) {
        c.targetReleased = true;
        TARGETS.remove(c.targetUuid);
        if (target != null) {
            target.setDeltaMovement(Vec3.ZERO);
            target.fallDistance = 0.0f;
            NO_FALL_UNTIL.put(c.targetUuid, c.level.getGameTime() + 60);
        }
    }

    private static void finish(Combo c, boolean aborted) {
        ServerPlayer caster = c.level.getServer().getPlayerList().getPlayer(c.casterId);
        Entity raw = c.level.getEntity(c.targetId);
        if (!c.targetReleased) releaseTarget(c, raw instanceof LivingEntity le ? le : null);
        TARGETS.remove(c.targetUuid);
        if (caster != null) {
            caster.getAbilities().mayfly = c.origMayfly;
            caster.getAbilities().flying = c.origMayfly && c.origFlying;
            caster.onUpdateAbilities();
            caster.fallDistance = 0.0f;
            NO_FALL_UNTIL.put(c.casterId, c.level.getGameTime() + 60);
        }
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> c.level.dimension()), new ComboEndPacket(c.casterId, aborted));
        if (raw instanceof ServerPlayer tp && tp.level() != c.level) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> tp), new ComboEndPacket(c.casterId, aborted));
        }
    }

    private static void abort(Combo c) {
        if (COMBOS.remove(c.casterId) == null) return;
        finish(c, true);
    }

    private static void play(ServerLevel level, Vec3 at, RegistryObject<SoundEvent> sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound.get(), SoundSource.PLAYERS, volume, pitch);
    }

    // ================================================================== захват блоков (сервер)

    /** Блоки захвачены: клиенты сами плавно ведут их (без рывков синхронизации). */
    static void startGrab(ServerPlayer player, int[] ids, List<Vec3> origins) {
        GRABS.put(player.getUUID(), new Grab(origins, player.level().getGameTime()));
        float pitch = player.getXRot();
        int variant = pitch > 28.0f ? 0 : (pitch < -28.0f ? 2 : 1);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new GrabPacket(player.getUUID(), player.getId(), ids, origins, variant));
        play(player.serverLevel(), player.position(), JujutsuNeonMod.SFX_LAPSE_CAST, 0.9f, 1.1f);
    }

    /** Сколько тиков идёт захват (или -1). */
    static long grabAge(ServerPlayer player) {
        Grab g = GRABS.get(player.getUUID());
        return g == null ? -1L : player.level().getGameTime() - g.start;
    }

    /** Где сейчас i-й блок захвата. */
    static Vec3 grabTarget(ServerPlayer player, int i) {
        Grab g = GRABS.get(player.getUUID());
        Vec3 hold = holdPos(player.getEyePosition(), player.getLookAngle(), i);
        if (g == null || i >= g.origins.size()) return hold;
        long age = player.level().getGameTime() - g.start;
        if (age == G_PULL_START && i == 0) {
            play(player.serverLevel(), g.origins.get(0), JujutsuNeonMod.SFX_LAPSE_PULL, 0.9f, 1.1f);
        }
        return grabPos(g.origins.get(i), hold, age);
    }

    static void endGrab(ServerPlayer player) {
        if (GRABS.remove(player.getUUID()) == null) return;
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), new GrabEndPacket(player.getUUID()));
    }

    // ================================================================== события

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity().level().isClientSide) return;
        UUID id = event.getEntity().getUUID();
        if (COMBOS.containsKey(id) || TARGETS.containsKey(id) || NO_FALL_UNTIL.containsKey(id)) {
            event.setCanceled(true);
            event.getEntity().fallDistance = 0.0f;
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Combo own = COMBOS.get(p.getUUID());
        if (own != null) abort(own);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        interrupt(p);
        GRABS.remove(p.getUUID());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) interrupt(p);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Combo c : new ArrayList<>(COMBOS.values())) {
            ServerPlayer caster = event.getServer().getPlayerList().getPlayer(c.casterId);
            if (caster != null) {
                caster.getAbilities().mayfly = c.origMayfly;
                caster.getAbilities().flying = c.origMayfly && c.origFlying;
                caster.onUpdateAbilities();
            }
        }
        COMBOS.clear();
        TARGETS.clear();
        GRABS.clear();
        NO_FALL_UNTIL.clear();
    }
}
