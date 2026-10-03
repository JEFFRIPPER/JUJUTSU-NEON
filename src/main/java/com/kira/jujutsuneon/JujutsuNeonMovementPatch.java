package com.kira.jujutsuneon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Compatibility/fix layer for the movement build that shipped in JujutsuNeon-TEST.jar.
 * It keeps the original ability implementation and patches only movement and Red safety.
 */
@Mod.EventBusSubscriber(
        modid = JujutsuNeonMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class JujutsuNeonMovementPatch {

    private static final String PATCH_PROTOCOL = "1";
    private static final SimpleChannel PATCH_NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "movement_fix"),
            () -> PATCH_PROTOCOL,
            PATCH_PROTOCOL::equals,
            PATCH_PROTOCOL::equals
    );

    static {
        PATCH_NETWORK.registerMessage(
                0,
                DashPacket.class,
                DashPacket::encode,
                DashPacket::decode,
                DashPacket::handle
        );
        PATCH_NETWORK.registerMessage(
                1,
                FrontSteerPacket.class,
                FrontSteerPacket::encode,
                FrontSteerPacket::decode,
                FrontSteerPacket::handle
        );
    }

    private JujutsuNeonMovementPatch() {}

    public enum DashKind {
        FRONT,
        BACK,
        LEFT,
        RIGHT,
        AIR
    }

    public static void sendDash(DashKind kind) {
        PATCH_NETWORK.sendToServer(new DashPacket(kind));
    }

    /** Keep server hit detection aligned with the camera-steered front dash. */
    public static void sendFrontSteer(Vec3 direction) {
        Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
        if (horizontal.lengthSqr() < 1.0E-8) return;
        horizontal = horizontal.normalize();
        PATCH_NETWORK.sendToServer(new FrontSteerPacket(horizontal.x, horizontal.z));
    }

    private record DashPacket(DashKind kind) {
        static void encode(DashPacket msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.kind);
        }

        static DashPacket decode(FriendlyByteBuf buf) {
            return new DashPacket(buf.readEnum(DashKind.class));
        }

        static void handle(DashPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) handlePatchedDash(player, msg.kind);
            });
            context.setPacketHandled(true);
        }
    }

    private record FrontSteerPacket(double x, double z) {
        static void encode(FrontSteerPacket msg, FriendlyByteBuf buf) {
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.z);
        }

        static FrontSteerPacket decode(FriendlyByteBuf buf) {
            return new FrontSteerPacket(buf.readDouble(), buf.readDouble());
        }

        static void handle(FrontSteerPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !player.isAlive() || player.isSpectator()) return;
                if (!hasBlindfold(player) || !handsEmpty(player)) return;
                if (player.getPersistentData().getInt("jn_dash_mode") != 1) return;

                Vec3 direction = new Vec3(msg.x, 0.0, msg.z);
                if (!Double.isFinite(direction.x) || !Double.isFinite(direction.z) ||
                        direction.lengthSqr() < 1.0E-8) return;

                direction = direction.normalize();
                player.getPersistentData().putDouble("jn_dash_dx", direction.x);
                player.getPersistentData().putDouble("jn_dash_dy", 0.0);
                player.getPersistentData().putDouble("jn_dash_dz", direction.z);
            });
            context.setPacketHandled(true);
        }
    }

    private static boolean hasBlindfold(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get());
    }

    private static boolean handsEmpty(ServerPlayer player) {
        return player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
    }

    private static boolean hasGroundSupport(ServerPlayer player, double distance) {
        if (player.onGround()) return true;
        Vec3 start = player.position().add(0.0, 0.08, 0.0);
        Vec3 end = start.add(0.0, -Math.max(0.10, distance), 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        return hit.getType() != HitResult.Type.MISS;
    }

    private static boolean atLeastFourBlocksAboveGround(ServerPlayer player) {
        Vec3 start = player.position().add(0.0, 0.05, 0.0);
        Vec3 end = start.add(0.0, -4.15, 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        if (hit.getType() == HitResult.Type.MISS) return true;
        return start.y - hit.getLocation().y >= 4.0 - 1.0E-3;
    }

    private static Vec3 horizontalForward(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-6) {
            double yaw = Math.toRadians(player.getYRot());
            horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        return horizontal.normalize();
    }

    /** Single coordinate contract: A=-right, D=+right, S=-forward. */
    private static Vec3 correctedDirection(ServerPlayer player, DashKind kind) {
        Vec3 forward = horizontalForward(player);
        Vec3 rightAxis = new Vec3(-forward.z, 0.0, forward.x).normalize();
        return switch (kind) {
            case LEFT -> rightAxis.scale(-1.0);
            case RIGHT -> rightAxis;
            case BACK -> forward.scale(-1.0);
            case FRONT, AIR -> forward;
        };
    }

    private static void invokeOriginalStartDash(ServerPlayer player, int side) {
        try {
            Method method = JujutsuNeonMod.class.getDeclaredMethod("startDash", ServerPlayer.class, int.class);
            method.setAccessible(true);
            method.invoke(null, player, side);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke Jujutsu Neon dash controller", exception);
        }
    }

    private static void handlePatchedDash(ServerPlayer player, DashKind kind) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (!hasBlindfold(player) || !handsEmpty(player) || player.getAbilities().flying) return;

        boolean grounded = hasGroundSupport(player, 0.36);
        if (kind == DashKind.AIR) {
            if (grounded || !atLeastFourBlocksAboveGround(player)) return;
        } else if (!grounded) {
            return;
        }

        int side = switch (kind) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case BACK -> 2;
            case FRONT, AIR -> 0;
        };

        // Reuse original CE, cooldown, sound, hit registration and VFX.
        invokeOriginalStartDash(player, side);
        int mode = player.getPersistentData().getInt("jn_dash_mode");
        if (mode == 0) return;

        Vec3 direction = correctedDirection(player, kind);
        player.getPersistentData().putDouble("jn_dash_dx", direction.x);
        player.getPersistentData().putDouble("jn_dash_dy", 0.0);
        player.getPersistentData().putDouble("jn_dash_dz", direction.z);
        if (kind == DashKind.AIR) player.getPersistentData().putInt("jn_dash_mode", 3);
    }

    /** Owner never participates in the vanilla part of their own Red explosion. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRedExplosionDetonate(ExplosionEvent.Detonate event) {
        event.getAffectedEntities().removeIf(entity ->
                entity instanceof ServerPlayer player &&
                        player.getPersistentData().getBoolean("jn_red_explosion_blocks_only")
        );
    }

    /** Defensive fallbacks for damage/knockback paths fired by other Forge hooks. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRedOwnerAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player &&
                player.getPersistentData().getBoolean("jn_red_explosion_blocks_only")) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRedOwnerKnockback(LivingKnockBackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player &&
                player.getPersistentData().getBoolean("jn_red_explosion_blocks_only")) {
            event.setCanceled(true);
        }
    }

    /**
     * Ordinary Red is the only destructive technique in the current build that uses a
     * vanilla Explosion. Reject its ItemEntity/XP spawns while the synchronous Red flag
     * is active. Maximum Blue, Maximum Red, Purple and super-run already set blocks to
     * AIR directly and therefore produce no drops.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTechniqueDropSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ItemEntity) && !(event.getEntity() instanceof ExperienceOrb)) return;

        for (ServerPlayer player : level.players()) {
            if (!player.getPersistentData().getBoolean("jn_red_explosion_blocks_only")) continue;
            if (player.distanceToSqr(event.getEntity()) > 144.0) continue;
            event.setCanceled(true);
            return;
        }
    }
}
