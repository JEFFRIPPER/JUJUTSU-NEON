package com.kira.jujutsuneon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Красный и Максимальный Красный (клавиша X): общий таймлайн и сеть.
 *
 * Управление:
 *  - нажал и отпустил раньше 2 секунд — обычный Красный (в момент отпускания);
 *  - держишь 2 секунды — Максимальный Красный начинается сам, отпускать не нужно.
 *
 * Обычный Красный (по референсу, ~0,8 с): рука к лицу, над головой загорается искра, рука к груди —
 * искра в ладони растёт в маленький шарик, бросок вперёд на 13-м тике. Шарик летит со скоростью
 * 3,75 блока/тик до 150 блоков; при попадании — взрыв (вдвое мощнее прежнего).
 *
 * Максимальный Красный (по референсу, ~1,7 с): разворот боком, рука к голове (печать), огромное
 * красное кольцо и белые ленты энергии сходятся в шар у груди, ударная волна, три кадра негатива,
 * огромный красный луч-конус вперёд с обломками, отдача назад.
 *
 * Логика на сервере — в JujutsuNeonMod (tickRedState), картинка — RedTechniqueClient.
 */
public final class RedTechnique {

    // ---- обычный Красный (тики от отпускания X) ----
    static final int CHARGE_MAX_TICKS = 40;
    static final int R_SPARK = 4;
    static final int R_CHEST = 9;
    static final int R_FIRE = 13;
    static final int R_END = 18;

    // ---- Максимальный Красный (тики от начала) ----
    static final int M_AURA = 5;
    static final int M_CONDENSE = 15;
    static final int M_ORB = 18;
    static final int M_SHOCK = 22;
    static final int M_FLASH = 23;
    static final int M_FIRE = 26;
    static final int M_END = 36;

    static final double RED_SPEED = 3.75;
    static final double RED_MAX_DISTANCE = 150.0;
    /** Радиус попадания (сам шарик меньше — 0,16). */
    static final double RED_HIT_RADIUS = 0.22;
    static final float RED_ORB_RADIUS = 0.16f;

    static final double BEAM_SPEED = 8.0;
    static final double BEAM_LENGTH = 50.0;
    static final double BEAM_MAX_RADIUS = 7.0;

    /** Радиус луча Максимального Красного на расстоянии d от глаз: конус от руки, потом труба, к концу сужается. */
    static double beamRadius(double d) {
        if (d < 1.0) return 0.0;
        if (d < 12.0) {
            double k = (d - 1.0) / 11.0;
            k = k * k * (3.0 - 2.0 * k);
            return Mth.lerp(k, 0.9, BEAM_MAX_RADIUS);
        }
        if (d <= 40.0) return BEAM_MAX_RADIUS;
        if (d >= BEAM_LENGTH) return 2.0;
        return Mth.lerp((d - 40.0) / (BEAM_LENGTH - 40.0), BEAM_MAX_RADIUS, 2.0);
    }

    // ---- сеть ----
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "red_technique"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, ControlPacket.class, ControlPacket::encode, ControlPacket::decode, ControlPacket::handle);
        CHANNEL.registerMessage(id++, ChargePacket.class, ChargePacket::encode, ChargePacket::decode, ChargePacket::handle);
        CHANNEL.registerMessage(id++, CastPacket.class, CastPacket::encode, CastPacket::decode, CastPacket::handle);
        CHANNEL.registerMessage(id++, ShotPacket.class, ShotPacket::encode, ShotPacket::decode, ShotPacket::handle);
        CHANNEL.registerMessage(id++, ShotEndPacket.class, ShotEndPacket::encode, ShotEndPacket::decode, ShotEndPacket::handle);
        CHANNEL.registerMessage(id++, BeamPacket.class, BeamPacket::encode, BeamPacket::decode, BeamPacket::handle);
    }

    private RedTechnique() {
    }

    /** Подгрузить класс (каналы регистрируются в статическом блоке). */
    static void init() {
    }

    static final int ACTION_START = 0;
    static final int ACTION_RELEASE = 1;
    static final int ACTION_CANCEL = 2;

    /** C2S: X нажата / отпущена. */
    public record ControlPacket(int action) {
        static void encode(ControlPacket msg, FriendlyByteBuf buf) {
            buf.writeByte(msg.action);
        }

        static ControlPacket decode(FriendlyByteBuf buf) {
            return new ControlPacket(buf.readByte());
        }

        static void handle(ControlPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender != null) JujutsuNeonMod.redControl(sender, msg.action);
            });
            context.setPacketHandled(true);
        }
    }

    /** S2C: заряд в руке (виден всем рядом). */
    public record ChargePacket(UUID caster, int entityId, boolean on) {
        static void encode(ChargePacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.caster);
            buf.writeVarInt(msg.entityId);
            buf.writeBoolean(msg.on);
        }

        static ChargePacket decode(FriendlyByteBuf buf) {
            return new ChargePacket(buf.readUUID(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(ChargePacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    RedTechniqueClient.onCharge(msg.caster, msg.entityId, msg.on)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: каст начался (анимация тела, эффекты у руки). */
    public record CastPacket(UUID caster, int entityId, boolean max, float yaw) {
        static void encode(CastPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.caster);
            buf.writeVarInt(msg.entityId);
            buf.writeBoolean(msg.max);
            buf.writeFloat(msg.yaw);
        }

        static CastPacket decode(FriendlyByteBuf buf) {
            return new CastPacket(buf.readUUID(), buf.readVarInt(), buf.readBoolean(), buf.readFloat());
        }

        static void handle(CastPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    RedTechniqueClient.onCast(msg.caster, msg.entityId, msg.max, msg.yaw)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: шарик вылетел (летит по прямой — клиент ведёт его сам). */
    public record ShotPacket(int shotId, int casterEntity, double x, double y, double z, double vx, double vy, double vz) {
        static void encode(ShotPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.shotId);
            buf.writeVarInt(msg.casterEntity);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeDouble(msg.vx);
            buf.writeDouble(msg.vy);
            buf.writeDouble(msg.vz);
        }

        static ShotPacket decode(FriendlyByteBuf buf) {
            return new ShotPacket(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        static void handle(ShotPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    RedTechniqueClient.onShot(msg.shotId, msg.casterEntity, new Vec3(msg.x, msg.y, msg.z),
                            new Vec3(msg.vx, msg.vy, msg.vz))));
            context.setPacketHandled(true);
        }
    }

    /** S2C: шарик взорвался (exploded) или растаял в конце пути. */
    public record ShotEndPacket(int shotId, double x, double y, double z, boolean exploded) {
        static void encode(ShotEndPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.shotId);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeBoolean(msg.exploded);
        }

        static ShotEndPacket decode(FriendlyByteBuf buf) {
            return new ShotEndPacket(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean());
        }

        static void handle(ShotEndPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    RedTechniqueClient.onShotEnd(msg.shotId, new Vec3(msg.x, msg.y, msg.z), msg.exploded)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: луч Максимального Красного. */
    public record BeamPacket(UUID caster, int casterEntity, double x, double y, double z, double dx, double dy, double dz) {
        static void encode(BeamPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.caster);
            buf.writeVarInt(msg.casterEntity);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeDouble(msg.dx);
            buf.writeDouble(msg.dy);
            buf.writeDouble(msg.dz);
        }

        static BeamPacket decode(FriendlyByteBuf buf) {
            return new BeamPacket(buf.readUUID(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        static void handle(BeamPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    RedTechniqueClient.onBeam(msg.caster, msg.casterEntity, new Vec3(msg.x, msg.y, msg.z),
                            new Vec3(msg.dx, msg.dy, msg.dz))));
            context.setPacketHandled(true);
        }
    }

    // ---- отправка ----

    static void request(int action) {
        CHANNEL.sendToServer(new ControlPacket(action));
    }

    private static PacketDistributor.PacketTarget around(ServerPlayer player) {
        return PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player);
    }

    private static PacketDistributor.PacketTarget near(ServerLevel level, Vec3 p, double radius) {
        return PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(p.x, p.y, p.z, radius, level.dimension()));
    }

    static void sendCharge(ServerPlayer player, boolean on) {
        CHANNEL.send(around(player), new ChargePacket(player.getUUID(), player.getId(), on));
    }

    static void sendCast(ServerPlayer player, boolean max) {
        CHANNEL.send(around(player), new CastPacket(player.getUUID(), player.getId(), max, player.getYRot()));
    }

    static void sendShot(ServerPlayer player, int shotId, Vec3 start, Vec3 velocity) {
        CHANNEL.send(near(player.serverLevel(), start, 220.0), new ShotPacket(shotId, player.getId(),
                start.x, start.y, start.z, velocity.x, velocity.y, velocity.z));
    }

    static void sendShotEnd(ServerLevel level, int shotId, Vec3 pos, boolean exploded) {
        CHANNEL.send(near(level, pos, 260.0), new ShotEndPacket(shotId, pos.x, pos.y, pos.z, exploded));
    }

    static void sendBeam(ServerPlayer player, Vec3 origin, Vec3 dir) {
        CHANNEL.send(near(player.serverLevel(), origin, 200.0), new BeamPacket(player.getUUID(), player.getId(),
                origin.x, origin.y, origin.z, dir.x, dir.y, dir.z));
    }
}
