package com.kira.jujutsuneon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * M1-комбо (как в батлграундах): в повязке и с пустой рукой ЛКМ — серия из четырёх ударов.
 * Три быстрых удара руками по очереди (П-Л-П или Л-П-Л, серии чередуются) и мощный четвёртый правой:
 * отбрасывает цель, она падает и лежит в стане 0,5 с. Удары 1–3 дают короткий стан, чтобы цель
 * не вырвалась посреди серии. Пауза больше ~1,2 с — серия начинается заново. Блоки кулаком не ломаются.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class M1Combo {

    /** Минимум между ударами 1–3. */
    static final int JAB_INTERVAL = 6;
    /** Перед четвёртым — чуть дольше (замах). */
    static final int FINAL_INTERVAL = 7;
    /** Замах четвёртого: удар приходится на этот тик анимации. */
    static final int FINAL_HIT_DELAY = 4;
    /** Без клика дольше — серия заново. */
    static final int COMBO_WINDOW = 24;
    /** После четвёртого — пауза до новой серии. */
    static final int FINAL_RECOVERY = 20;
    static final double REACH = 3.4;

    static final float JAB_DAMAGE = 3.0F;   // 1,5 сердца
    static final float FINAL_DAMAGE = 6.0F; // 3 сердца
    static final int JAB_STUN = 12;
    /** Полёт после финала + 0,5 с на земле. */
    static final int KNOCKDOWN_TICKS = 24;

    private static final class State {
        int step;          // сколько ударов серии уже было (0..3)
        long lastSwing = Long.MIN_VALUE / 4;
        long readyAt;
        boolean leftFirst; // серия начинается левой
        long finalAt = Long.MIN_VALUE;
    }

    private static final class StunInfo {
        final ServerLevel level;
        long until;
        boolean knockdown;
        boolean prevNoAi;
        boolean noAiSet;

        StunInfo(ServerLevel level) {
            this.level = level;
        }
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, StunInfo> STUNNED = new HashMap<>();
    private static final String TAG_PREV_NOAI = "jn_m1_prev_noai";

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "m1_combo"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, SwingRequest.class, SwingRequest::encode, SwingRequest::decode, SwingRequest::handle);
        CHANNEL.registerMessage(id++, SwingFx.class, SwingFx::encode, SwingFx::decode, SwingFx::handle);
        CHANNEL.registerMessage(id++, HitFx.class, HitFx::encode, HitFx::decode, HitFx::handle);
        CHANNEL.registerMessage(id++, StunFx.class, StunFx::encode, StunFx::decode, StunFx::handle);
    }

    private M1Combo() {
    }

    static void init() {
    }

    /** Игрок оглушён ударом M1 (нельзя двигаться и применять техники). */
    static boolean isStunned(Player player) {
        return STUNNED.containsKey(player.getUUID());
    }

    static void request() {
        CHANNEL.sendToServer(new SwingRequest());
    }

    // ------------------------------------------------------------------ сеть

    public record SwingRequest() {
        static void encode(SwingRequest msg, FriendlyByteBuf buf) {
        }

        static SwingRequest decode(FriendlyByteBuf buf) {
            return new SwingRequest();
        }

        static void handle(SwingRequest msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender != null) swing(sender);
            });
            context.setPacketHandled(true);
        }
    }

    /** Чужим: какой удар серии показать (step 0..3, left — какой рукой). */
    public record SwingFx(int entityId, int step, boolean left) {
        static void encode(SwingFx msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeByte(msg.step);
            buf.writeBoolean(msg.left);
        }

        static SwingFx decode(FriendlyByteBuf buf) {
            return new SwingFx(buf.readVarInt(), buf.readByte(), buf.readBoolean());
        }

        static void handle(SwingFx msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    M1ComboClient.onRemoteSwing(msg.entityId, msg.step, msg.left)));
            context.setPacketHandled(true);
        }
    }

    /** Попадание: kind 0 — по существу, 1 — финал по существу, 2 — по блоку (blockState), 3 — промах. */
    public record HitFx(int attackerId, int kind, double x, double y, double z, float dx, float dy, float dz, int blockState) {
        static void encode(HitFx msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.attackerId);
            buf.writeByte(msg.kind);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.dx);
            buf.writeFloat(msg.dy);
            buf.writeFloat(msg.dz);
            buf.writeVarInt(msg.blockState);
        }

        static HitFx decode(FriendlyByteBuf buf) {
            return new HitFx(buf.readVarInt(), buf.readByte(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt());
        }

        static void handle(HitFx msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    M1ComboClient.onHit(msg.attackerId, msg.kind, new Vec3(msg.x, msg.y, msg.z),
                            new Vec3(msg.dx, msg.dy, msg.dz), msg.blockState)));
            context.setPacketHandled(true);
        }
    }

    /** Оглушение: entityId, ticks; knockdown — сбит с ног (лежит). Своему игроку ещё и блок управления. */
    public record StunFx(int entityId, int ticks, boolean knockdown) {
        static void encode(StunFx msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeVarInt(msg.ticks);
            buf.writeBoolean(msg.knockdown);
        }

        static StunFx decode(FriendlyByteBuf buf) {
            return new StunFx(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(StunFx msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    M1ComboClient.onStun(msg.entityId, msg.ticks, msg.knockdown)));
            context.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ удар

    private static boolean canSwing(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator()) return false;
        if (!JujutsuNeonMod.hasGojoBlindfold(p) || !p.getMainHandItem().isEmpty()) return false;
        if (isStunned(p) || DomainExpansion.blocksActions(p)) return false;
        return !MaximumPurple.isActive(p) && !JujutsuNeonMod.isHollowPurpleCasting(p) && !JujutsuNeonMod.isMaxRedCasting(p);
    }

    private static void swing(ServerPlayer p) {
        if (!canSwing(p)) return;
        State s = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        long now = p.level().getGameTime();
        // +1 тик допуска: клиент считает готовность по своим тикам.
        if (now + 1 < s.readyAt || s.finalAt != Long.MIN_VALUE) return;
        if (now - s.lastSwing > COMBO_WINDOW && s.step != 0) {
            s.step = 0;
            s.leftFirst = !s.leftFirst;
        }
        int step = s.step;
        boolean left = step < 3 && ((step % 2 == 0) == s.leftFirst);
        s.lastSwing = now;
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> p), new SwingFx(p.getId(), step, left));

        if (step < 3) {
            s.step++;
            s.readyAt = now + (s.step == 3 ? FINAL_INTERVAL : JAB_INTERVAL);
            strike(p, false);
        } else {
            // Четвёртый: замах, удар на FINAL_HIT_DELAY-м тике.
            s.finalAt = now + FINAL_HIT_DELAY;
            s.step = 0;
            s.leftFirst = !s.leftFirst;
            s.readyAt = now + FINAL_HIT_DELAY + FINAL_RECOVERY;
        }
    }

    private static void strike(ServerPlayer p, boolean fin) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle().normalize();
        LivingEntity target = findTarget(p, eye, look);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        flat = flat.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : flat.normalize();

        if (target != null) {
            Vec3 at = target.getBoundingBox().getCenter();
            Vec3 contact = at.add(eye.subtract(at).normalize().scale(Math.min(target.getBbWidth() * 0.55, 0.5)));
            p.getPersistentData().putBoolean("jn_m1_damage", true);
            target.invulnerableTime = 0;
            boolean hurt;
            try {
                hurt = target.hurt(level.damageSources().playerAttack(p), fin ? FINAL_DAMAGE : JAB_DAMAGE);
            } finally {
                p.getPersistentData().putBoolean("jn_m1_damage", false);
            }
            sendHit(p, fin ? 1 : 0, contact, look, 0);
            if (!hurt) return; // удар поглощён (Бесконечность, щит простой территории)
            if (fin) {
                Vec3 push = flat.scale(1.35).add(0.0, 0.42, 0.0);
                target.setDeltaMovement(push);
                target.hurtMarked = true;
                stun(target, KNOCKDOWN_TICKS, true);
            } else {
                // Чуть подтолкнуть вперёд, чтобы серия «вела» цель.
                Vec3 v = target.getDeltaMovement();
                target.setDeltaMovement(v.x * 0.3 + flat.x * 0.12, Math.min(v.y, 0.1), v.z * 0.3 + flat.z * 0.12);
                target.hurtMarked = true;
                stun(target, JAB_STUN, false);
            }
            return;
        }

        BlockHitResult bh = level.clip(new ClipContext(eye, eye.add(look.scale(REACH)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, p));
        if (bh.getType() == HitResult.Type.BLOCK) {
            BlockState state = level.getBlockState(bh.getBlockPos());
            Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
            sendHit(p, 2, bh.getLocation(), n, Block.getId(state));
        } else {
            sendHit(p, 3, eye.add(look.scale(2.0)), look, 0);
        }
    }

    private static void sendHit(ServerPlayer p, int kind, Vec3 at, Vec3 dir, int blockState) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> p),
                new HitFx(p.getId(), kind, at.x, at.y, at.z, (float) dir.x, (float) dir.y, (float) dir.z, blockState));
    }

    /** Ближайшая живая цель в «капсуле» удара перед игроком. */
    private static LivingEntity findTarget(ServerPlayer p, Vec3 eye, Vec3 look) {
        Vec3 end = eye.add(look.scale(REACH));
        AABB box = new AABB(eye, end).inflate(1.0);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != p && e.isAlive() && !e.isSpectator() && e.isPickable())) {
            AABB bb = e.getBoundingBox().inflate(0.45);
            Vec3 c = bb.getCenter();
            double along = c.subtract(eye).dot(look);
            if (along < -0.2 || along > REACH + 0.6) continue;
            if (!bb.contains(eye) && bb.clip(eye, end).isEmpty()) {
                // не на линии взгляда — берём, если почти перед игроком (лёгкий автоприцел)
                Vec3 to = c.subtract(eye);
                if (to.length() > REACH + 0.5 || to.normalize().dot(look) < 0.82) continue;
            }
            if (DomainExpansion.separated(p.level(), eye, c)) continue;
            double d = c.distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        // Сквозь стену не бьём.
        if (best != null) {
            BlockHitResult wall = p.level().clip(new ClipContext(eye, best.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, p));
            if (wall.getType() == HitResult.Type.BLOCK
                    && wall.getLocation().distanceToSqr(eye) + 0.25 < best.getBoundingBox().getCenter().distanceToSqr(eye)) {
                return null;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ оглушение

    private static void stun(LivingEntity e, int ticks, boolean knockdown) {
        ServerLevel level = (ServerLevel) e.level();
        StunInfo st = STUNNED.get(e.getUUID());
        if (st == null) {
            st = new StunInfo(level);
            STUNNED.put(e.getUUID(), st);
        }
        st.until = Math.max(st.until, level.getGameTime() + ticks);
        if (knockdown) st.until = level.getGameTime() + ticks;
        st.knockdown = knockdown;
        if (e instanceof Mob mob && !DomainExpansion.isFrozenMob(mob)) {
            if (!st.noAiSet) {
                st.prevNoAi = mob.isNoAi();
                st.noAiSet = true;
                mob.getPersistentData().putBoolean(TAG_PREV_NOAI, st.prevNoAi);
            }
            mob.setNoAi(true);
            mob.getNavigation().stop();
            mob.setTarget(null);
        }
        if (e instanceof ServerPlayer sp) JujutsuNeonMod.interruptForStun(sp);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> e), new StunFx(e.getId(), ticks, knockdown));
    }

    private static void release(LivingEntity e, StunInfo st) {
        if (e instanceof Mob mob && st.noAiSet) {
            // Если моба тем временем обездвижила территория — её статую не трогаем.
            if (!DomainExpansion.isFrozenMob(mob)) mob.setNoAi(st.prevNoAi);
            else DomainExpansion.setFrozenPrevNoAi(mob, st.prevNoAi);
            mob.getPersistentData().remove(TAG_PREV_NOAI);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // Отложенные финальные удары.
        if (!STATES.isEmpty()) {
            List<UUID> due = new ArrayList<>();
            for (Map.Entry<UUID, State> en : STATES.entrySet()) {
                if (en.getValue().finalAt != Long.MIN_VALUE) due.add(en.getKey());
            }
            for (UUID id : due) {
                ServerPlayer p = event.getServer().getPlayerList().getPlayer(id);
                State s = STATES.get(id);
                if (p == null) {
                    s.finalAt = Long.MIN_VALUE;
                    continue;
                }
                if (p.level().getGameTime() >= s.finalAt) {
                    s.finalAt = Long.MIN_VALUE;
                    if (canSwing(p)) strike(p, true);
                }
            }
        }
        if (STUNNED.isEmpty()) return;
        Iterator<Map.Entry<UUID, StunInfo>> it = STUNNED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, StunInfo> en = it.next();
            StunInfo st = en.getValue();
            var entity = st.level.getEntity(en.getKey());
            long now = st.level.getGameTime();
            if (!(entity instanceof LivingEntity e) || !e.isAlive() || now >= st.until) {
                if (entity instanceof LivingEntity le) release(le, st);
                it.remove();
                continue;
            }
            e.fallDistance = 0.0f;
            if (e instanceof Mob mob) mob.getNavigation().stop();
        }
    }

    /** Оглушённый не бьёт сам. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        if (STUNNED.isEmpty() || event.getEntity().level().isClientSide) return;
        if (event.getSource().getEntity() instanceof LivingEntity src && event.getSource().getDirectEntity() == src
                && STUNNED.containsKey(src.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
        STUNNED.remove(event.getEntity().getUUID());
    }

    /** Блок по клику кулаком не ломается (ломать — инструментом или блоком в руке). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && JujutsuNeonMod.hasGojoBlindfold(p)
                && p.getMainHandItem().isEmpty()) {
            event.setCanceled(true);
        }
    }
}
