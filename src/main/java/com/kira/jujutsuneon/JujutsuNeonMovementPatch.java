package com.kira.jujutsuneon;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server authority for dash direction, Super Run path clearing and Red owner safety. */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class JujutsuNeonMovementPatch {

    private static final String PATCH_PROTOCOL = "2";
    private static final SimpleChannel PATCH_NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "movement_fix"),
            () -> PATCH_PROTOCOL, PATCH_PROTOCOL::equals, PATCH_PROTOCOL::equals
    );

    static {
        PATCH_NETWORK.registerMessage(0, DashPacket.class, DashPacket::encode, DashPacket::decode, DashPacket::handle);
        PATCH_NETWORK.registerMessage(1, FrontSteerPacket.class, FrontSteerPacket::encode, FrontSteerPacket::decode, FrontSteerPacket::handle);
        PATCH_NETWORK.registerMessage(2, SuperRunClearPacket.class, SuperRunClearPacket::encode, SuperRunClearPacket::decode, SuperRunClearPacket::handle);
    }

    private JujutsuNeonMovementPatch() {}

    public enum DashKind { FRONT, BACK, LEFT, RIGHT }

    public static void sendDash(DashKind kind) {
        PATCH_NETWORK.sendToServer(new DashPacket(kind));
    }

    public static void sendFrontSteer(Vec3 direction) {
        Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
        if (horizontal.lengthSqr() < 1.0E-8) return;
        horizontal = horizontal.normalize();
        PATCH_NETWORK.sendToServer(new FrontSteerPacket(horizontal.x, horizontal.z));
    }

    public static void sendSuperRunClear(Vec3 direction) {
        Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
        if (horizontal.lengthSqr() < 1.0E-8) return;
        horizontal = horizontal.normalize();
        PATCH_NETWORK.sendToServer(new SuperRunClearPacket(horizontal.x, horizontal.z));
    }

    private record DashPacket(DashKind kind) {
        static void encode(DashPacket msg, FriendlyByteBuf buf) { buf.writeEnum(msg.kind); }
        static DashPacket decode(FriendlyByteBuf buf) { return new DashPacket(buf.readEnum(DashKind.class)); }
        static void handle(DashPacket msg, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && !DomainExpansion.blocksActions(player) && !DomainExpansion.isStunned(player)) handlePatchedDash(player, msg.kind);
            });
            context.setPacketHandled(true);
        }
    }

    private record FrontSteerPacket(double x, double z) {
        static void encode(FrontSteerPacket msg, FriendlyByteBuf buf) {
            buf.writeDouble(msg.x); buf.writeDouble(msg.z);
        }
        static FrontSteerPacket decode(FriendlyByteBuf buf) {
            return new FrontSteerPacket(buf.readDouble(), buf.readDouble());
        }
        static void handle(FrontSteerPacket msg, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !player.isAlive() || player.isSpectator()) return;
                if (!hasBlindfold(player) || !handsEmpty(player)) return;
                if (player.getPersistentData().getInt("jn_dash_mode") != 1) return;

                Vec3 direction = new Vec3(msg.x, 0.0, msg.z);
                if (!Double.isFinite(direction.x) || !Double.isFinite(direction.z) || direction.lengthSqr() < 1.0E-8) return;
                direction = direction.normalize();
                player.getPersistentData().putDouble("jn_dash_dx", direction.x);
                player.getPersistentData().putDouble("jn_dash_dy", 0.0);
                player.getPersistentData().putDouble("jn_dash_dz", direction.z);
            });
            context.setPacketHandled(true);
        }
    }

    private record SuperRunClearPacket(double x, double z) {
        static void encode(SuperRunClearPacket msg, FriendlyByteBuf buf) {
            buf.writeDouble(msg.x); buf.writeDouble(msg.z);
        }
        static SuperRunClearPacket decode(FriendlyByteBuf buf) {
            return new SuperRunClearPacket(buf.readDouble(), buf.readDouble());
        }
        static void handle(SuperRunClearPacket msg, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !player.isAlive() || player.isSpectator()) return;
                if (!hasBlindfold(player) || player.getAbilities().flying) return;
                if (!player.getPersistentData().getBoolean("jn_super_speed")) return;
                if (player.getPersistentData().getInt("jn_dash_mode") != 0) return;

                Vec3 direction = new Vec3(msg.x, 0.0, msg.z);
                if (!Double.isFinite(direction.x) || !Double.isFinite(direction.z) || direction.lengthSqr() < 1.0E-8) return;
                clearSuperRunPath(player, direction.normalize());
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

    private static Vec3 horizontalForward(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-6) {
            double yaw = Math.toRadians(player.getYRot());
            horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        return horizontal.normalize();
    }

    private static Vec3 correctedDirection(ServerPlayer player, DashKind kind) {
        Vec3 forward = horizontalForward(player);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();
        return switch (kind) {
            case LEFT -> right.scale(-1.0);
            case RIGHT -> right;
            case BACK -> forward.scale(-1.0);
            case FRONT -> forward;
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
        if (!hasGroundSupport(player, 0.36)) return;

        int side = switch (kind) {
            case LEFT -> -1;
            case RIGHT -> 1;
            case BACK -> 2;
            case FRONT -> 0;
        };

        invokeOriginalStartDash(player, side);
        if (player.getPersistentData().getInt("jn_dash_mode") == 0) return;

        Vec3 direction = correctedDirection(player, kind);
        player.getPersistentData().putDouble("jn_dash_dx", direction.x);
        player.getPersistentData().putDouble("jn_dash_dy", 0.0);
        player.getPersistentData().putDouble("jn_dash_dz", direction.z);
    }

    private static boolean naturalGround(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) ||
                state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) ||
                state.is(Blocks.MUD) || state.is(Blocks.MYCELIUM);
    }

    /** Bounded natural-tree heuristic: no trunk/canopy BFS in a movement tick. */
    private static boolean isNaturalTreeLog(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(BlockTags.LOGS)) return false;
        if (naturalGround(level.getBlockState(pos.below()))) return true;

        for (int dy = 0; dy <= 6; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 5) continue;
                    if (level.getBlockState(pos.offset(dx, dy, dz)).is(BlockTags.LEAVES)) return true;
                }
            }
        }
        return false;
    }

    private static boolean superRunBreakable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)) return true;
        if (state.is(BlockTags.LOGS)) return isNaturalTreeLog(level, pos);
        return state.is(Blocks.GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN) ||
                state.is(Blocks.LARGE_FERN) || state.is(Blocks.VINE) || state.is(Blocks.DEAD_BUSH) ||
                state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.AZALEA) ||
                state.is(Blocks.FLOWERING_AZALEA) || state.is(Blocks.BAMBOO) || state.is(Blocks.SUGAR_CANE);
    }

    private static void clearSuperRunPath(ServerPlayer player, Vec3 direction) {
        ServerLevel level = player.serverLevel();
        AABB sweep = player.getBoundingBox()
                .expandTowards(direction.scale(1.35))
                .inflate(0.16, 1.05, 0.16);

        List<BlockPos> remove = new ArrayList<>();
        for (int x = (int)Math.floor(sweep.minX); x <= (int)Math.floor(sweep.maxX) && remove.size() < 32; x++) {
            for (int y = (int)Math.floor(sweep.minY); y <= (int)Math.floor(sweep.maxY) && remove.size() < 32; y++) {
                for (int z = (int)Math.floor(sweep.minZ); z <= (int)Math.floor(sweep.maxZ) && remove.size() < 32; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (superRunBreakable(level, pos, state)) remove.add(pos.immutable());
                }
            }
        }

        // Classify first, mutate second: removing the base log must not change the
        // classification of the next trunk block inside this same authoritative pass.
        for (BlockPos pos : remove) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRedExplosionDetonate(ExplosionEvent.Detonate event) {
        event.getAffectedEntities().removeIf(entity -> entity instanceof ServerPlayer player &&
                player.getPersistentData().getBoolean("jn_red_explosion_blocks_only"));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRedOwnerAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player &&
                player.getPersistentData().getBoolean("jn_red_explosion_blocks_only")) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRedOwnerHurt(LivingHurtEvent event) {
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
