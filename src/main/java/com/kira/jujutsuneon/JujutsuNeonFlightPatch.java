package com.kira.jujutsuneon;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Server authority for the charged-jump -> hover flight system.
 *
 * The jump itself is deliberately NOT changed here. The client arms flight by observing
 * the already existing charged-jump impulse and requests START only at the apex.
 */
@Mod.EventBusSubscriber(
        modid = JujutsuNeonMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class JujutsuNeonFlightPatch {

    private static final String PROTOCOL = "1";
    private static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "flight_patch"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static final String TAG_ACTIVE = "jn_custom_flight";
    private static final String TAG_BOOST = "jn_custom_flight_boost";
    private static final String TAG_ORIG_MAYFLY = "jn_custom_flight_orig_mayfly";
    private static final String TAG_ORIG_FLYING = "jn_custom_flight_orig_flying";
    private static final String TAG_ORIG_SPEED = "jn_custom_flight_orig_speed";

    private static final float NORMAL_FLY_SPEED = 0.05F;
    private static final float BOOST_FLY_SPEED = 0.10F;
    private static final double START_MIN_HEIGHT = 4.0; // 4+ блока до земли или воды
    private static final double IMPACT_VALIDATE_HEIGHT = 1.45;
    private static final double IMPACT_RADIUS = 3.25;
    private static final float IMPACT_DAMAGE = 40.0F; // 20 hearts

    static {
        NETWORK.registerMessage(
                0,
                FlightActionPacket.class,
                FlightActionPacket::encode,
                FlightActionPacket::decode,
                FlightActionPacket::handle
        );
    }

    private JujutsuNeonFlightPatch() {
    }

    public enum Action {
        START,
        STOP,
        BOOST_ON,
        BOOST_OFF,
        IMPACT
    }

    public static void send(Action action) {
        NETWORK.sendToServer(new FlightActionPacket(action));
    }

    private record FlightActionPacket(Action action) {
        static void encode(FlightActionPacket msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
        }

        static FlightActionPacket decode(FriendlyByteBuf buf) {
            return new FlightActionPacket(buf.readEnum(Action.class));
        }

        static void handle(FlightActionPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) handleAction(player, msg.action);
            });
            context.setPacketHandled(true);
        }
    }

    private static boolean hasBlindfold(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get());
    }

    private static double groundDistance(ServerPlayer player, double maxDistance) {
        return groundDistance(player, maxDistance, ClipContext.Fluid.NONE);
    }

    /** Высота над землёй; с Fluid.ANY поверхность воды тоже считается землёй. */
    private static double groundDistance(ServerPlayer player, double maxDistance, ClipContext.Fluid fluid) {
        Vec3 start = player.position().add(0.0, 0.08, 0.0);
        Vec3 end = start.add(0.0, -Math.max(0.25, maxDistance), 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                fluid,
                player
        ));

        if (hit.getType() == HitResult.Type.MISS) return maxDistance + 1.0;
        return Math.max(0.0, start.y - hit.getLocation().y);
    }

    private static void handleAction(ServerPlayer player, Action action) {
        switch (action) {
            case START -> startFlight(player);
            case STOP -> stopFlight(player);
            case BOOST_ON -> setBoost(player, true);
            case BOOST_OFF -> setBoost(player, false);
            case IMPACT -> impact(player);
        }
    }

    private static void startFlight(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (!hasBlindfold(player)) return;
        if (player.onGround()) return;
        if (groundDistance(player, START_MIN_HEIGHT + 0.35, ClipContext.Fluid.ANY) + 1.0E-4 < START_MIN_HEIGHT) return;

        var data = player.getPersistentData();

        if (!data.getBoolean(TAG_ACTIVE)) {
            data.putBoolean(TAG_ORIG_MAYFLY, player.getAbilities().mayfly);
            data.putBoolean(TAG_ORIG_FLYING, player.getAbilities().flying);
            data.putFloat(TAG_ORIG_SPEED, player.getAbilities().getFlyingSpeed());
        }

        data.putBoolean(TAG_ACTIVE, true);
        data.putBoolean(TAG_BOOST, false);

        // Ctrl belongs to flight while this state is active; do not leave Super Run armed.
        data.putBoolean("jn_super_speed", false);
        data.remove("jn_speed_next_cost");
        data.remove("jn_speed_last_x");
        data.remove("jn_speed_last_y");
        data.remove("jn_speed_last_z");

        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.getAbilities().setFlyingSpeed(NORMAL_FLY_SPEED);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.onUpdateAbilities();
    }

    private static void setBoost(ServerPlayer player, boolean boost) {
        var data = player.getPersistentData();
        if (!data.getBoolean(TAG_ACTIVE)) return;

        data.putBoolean(TAG_BOOST, boost);
        data.putBoolean("jn_super_speed", false);
        data.remove("jn_speed_next_cost");

        player.getAbilities().setFlyingSpeed(boost ? BOOST_FLY_SPEED : NORMAL_FLY_SPEED);
        player.onUpdateAbilities();
    }

    private static void stopFlight(ServerPlayer player) {
        var data = player.getPersistentData();
        if (!data.getBoolean(TAG_ACTIVE)) return;

        boolean originalMayfly = data.getBoolean(TAG_ORIG_MAYFLY);
        boolean originalFlying = data.getBoolean(TAG_ORIG_FLYING);
        float originalSpeed = data.contains(TAG_ORIG_SPEED)
                ? data.getFloat(TAG_ORIG_SPEED)
                : NORMAL_FLY_SPEED;

        data.remove(TAG_ACTIVE);
        data.remove(TAG_BOOST);
        data.remove(TAG_ORIG_MAYFLY);
        data.remove(TAG_ORIG_FLYING);
        data.remove(TAG_ORIG_SPEED);

        player.getAbilities().mayfly = originalMayfly;
        player.getAbilities().flying = originalMayfly && originalFlying;
        player.getAbilities().setFlyingSpeed(originalSpeed);
        player.fallDistance = 0.0F;
        player.onUpdateAbilities();
    }

    private static void impact(ServerPlayer player) {
        var data = player.getPersistentData();
        if (!data.getBoolean(TAG_ACTIVE) || !data.getBoolean(TAG_BOOST)) return;

        // Do not accept a remote "impact" while still clearly high in the air.
        if (!player.onGround() && groundDistance(player, IMPACT_VALIDATE_HEIGHT + 0.20) > IMPACT_VALIDATE_HEIGHT) {
            return;
        }

        ServerLevel level = player.serverLevel();
        Vec3 center = player.position().add(0.0, 0.15, 0.0);

        // Creeper-like impact presentation, intentionally without Explosion/block destruction.
        level.sendParticles(
                ParticleTypes.EXPLOSION_EMITTER,
                center.x, center.y, center.z,
                1,
                0.0, 0.0, 0.0,
                0.0
        );
        level.sendParticles(
                ParticleTypes.CLOUD,
                center.x, center.y + 0.10, center.z,
                28,
                1.15, 0.35, 1.15,
                0.14
        );
        level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                center.x, center.y + 0.35, center.z,
                34,
                1.30, 0.55, 1.30,
                0.28
        );
        level.playSound(
                null,
                center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS,
                1.55F,
                0.82F
        );

        AABB hitBox = new AABB(
                center.x - IMPACT_RADIUS,
                center.y - 1.25,
                center.z - IMPACT_RADIUS,
                center.x + IMPACT_RADIUS,
                center.y + IMPACT_RADIUS,
                center.z + IMPACT_RADIUS
        );

        for (LivingEntity target : level.getEntitiesOfClass(
                LivingEntity.class,
                hitBox,
                entity -> entity != player && entity.isAlive()
        )) {
            double distance = Math.sqrt(target.distanceToSqr(center));
            if (distance > IMPACT_RADIUS) continue;

            // User requested a fixed 20-heart crash hit. No block damage/drop path exists here.
            target.hurt(player.damageSources().playerAttack(player), IMPACT_DAMAGE);

            Vec3 away = target.position().subtract(center);
            Vec3 push = away.lengthSqr() > 1.0E-6
                    ? away.normalize().scale(0.85).add(0.0, 0.35, 0.0)
                    : new Vec3(0.0, 0.45, 0.0);
            target.push(push.x, push.y, push.z);
        }

        stopFlight(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        var data = player.getPersistentData();
        if (!data.getBoolean(TAG_ACTIVE)) return;

        if (!player.isAlive() || player.isSpectator() || !hasBlindfold(player) || player.isInWaterOrBubble()) {
            stopFlight(player);
            return;
        }

        // Never let the old ground Super Run controller own Ctrl while custom flight is active.
        data.putBoolean("jn_super_speed", false);
        data.remove("jn_speed_next_cost");
        data.remove("jn_speed_last_x");
        data.remove("jn_speed_last_y");
        data.remove("jn_speed_last_z");

        boolean boost = data.getBoolean(TAG_BOOST);

        if (!player.getAbilities().mayfly || !player.getAbilities().flying) {
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }

        float expectedSpeed = boost ? BOOST_FLY_SPEED : NORMAL_FLY_SPEED;
        if (Math.abs(player.getAbilities().getFlyingSpeed() - expectedSpeed) > 1.0E-4F) {
            player.getAbilities().setFlyingSpeed(expectedSpeed);
            player.onUpdateAbilities();
        }

        player.fallDistance = 0.0F;

        // A normal landing should end the temporary flight entitlement immediately.
        if (player.onGround() && !boost) {
            stopFlight(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopFlight(player);
        }
    }
}
