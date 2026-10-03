package com.kira.jujutsuneon;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Client half of the movement compatibility layer. */
@Mod.EventBusSubscriber(
        modid = JujutsuNeonMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class JujutsuNeonMovementPatchClient {

    private static final Class<?> CLIENT_EVENTS_CLASS;
    private static final Class<?> MOVEMENT_ACTION_CLASS;
    private static final Method START_CLIENT_DASH;
    private static final Method START_ANIM;
    private static final Field CLIENT_DASH_MODE;
    private static final Field CLIENT_DASH_DIRECTION;
    private static final Field HUD_MAX_BLUE_ACTIVE;
    private static final Field HUD_PURPLE_CASTING;

    private static Vec3 desiredDashDirection = Vec3.ZERO;
    private static boolean previousRawJumpHeld = false;
    private static boolean blockedAirJumpUntilRelease = false;
    private static boolean restoreJumpKey = false;
    private static boolean rawJumpHeldThisTick = false;

    static {
        try {
            CLIENT_EVENTS_CLASS = Class.forName("com.kira.jujutsuneon.JujutsuNeonMod$ClientForgeEvents");
            MOVEMENT_ACTION_CLASS = Class.forName("com.kira.jujutsuneon.JujutsuNeonMod$MovementAction");

            START_CLIENT_DASH = CLIENT_EVENTS_CLASS.getDeclaredMethod(
                    "startClientDash",
                    Minecraft.class,
                    MOVEMENT_ACTION_CLASS
            );
            START_CLIENT_DASH.setAccessible(true);

            START_ANIM = CLIENT_EVENTS_CLASS.getDeclaredMethod("startAnim", String.class, int.class);
            START_ANIM.setAccessible(true);

            CLIENT_DASH_MODE = CLIENT_EVENTS_CLASS.getDeclaredField("clientDashMode");
            CLIENT_DASH_MODE.setAccessible(true);

            CLIENT_DASH_DIRECTION = CLIENT_EVENTS_CLASS.getDeclaredField("clientDashDirection");
            CLIENT_DASH_DIRECTION.setAccessible(true);

            HUD_MAX_BLUE_ACTIVE = CLIENT_EVENTS_CLASS.getDeclaredField("hudMaxBlueActive");
            HUD_MAX_BLUE_ACTIVE.setAccessible(true);

            HUD_PURPLE_CASTING = CLIENT_EVENTS_CLASS.getDeclaredField("hudPurpleCasting");
            HUD_PURPLE_CASTING.setAccessible(true);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private JujutsuNeonMovementPatchClient() {
    }

    private static boolean hasBlindfold(Minecraft mc) {
        return mc.player != null &&
                mc.player.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get());
    }

    private static boolean handsEmpty(Minecraft mc) {
        return mc.player != null &&
                mc.player.getMainHandItem().isEmpty() &&
                mc.player.getOffhandItem().isEmpty();
    }

    private static boolean legacyTechniqueLocksMovement() {
        try {
            return HUD_MAX_BLUE_ACTIVE.getBoolean(null) || HUD_PURPLE_CASTING.getBoolean(null);
        } catch (IllegalAccessException exception) {
            return true;
        }
    }

    private static int legacyDashMode() {
        try {
            return CLIENT_DASH_MODE.getInt(null);
        } catch (IllegalAccessException exception) {
            return 0;
        }
    }

    private static void setLegacyDashDirection(Vec3 direction) {
        try {
            CLIENT_DASH_DIRECTION.set(null, direction);
        } catch (IllegalAccessException ignored) {
        }
    }

    private static boolean hasGroundSupport(Minecraft mc, double distance) {
        if (mc.player == null || mc.level == null) return false;
        if (mc.player.onGround()) return true;

        Vec3 start = mc.player.position().add(0.0, 0.08, 0.0);
        Vec3 end = start.add(0.0, -Math.max(0.10, distance), 0.0);
        BlockHitResult hit = mc.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player
        ));
        return hit.getType() != HitResult.Type.MISS;
    }

    private static boolean atLeastFourBlocksAboveGround(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;

        Vec3 start = mc.player.position().add(0.0, 0.05, 0.0);
        Vec3 end = start.add(0.0, -4.15, 0.0);
        BlockHitResult hit = mc.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player
        ));

        if (hit.getType() == HitResult.Type.MISS) return true;
        return start.y - hit.getLocation().y >= 4.0 - 1.0E-3;
    }

    private static Vec3 horizontalForward(Minecraft mc) {
        Vec3 look = mc.player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-6) {
            double yaw = Math.toRadians(mc.player.getYRot());
            horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        return horizontal.normalize();
    }

    private static Vec3 correctedDashDirection(Minecraft mc, JujutsuNeonMovementPatch.DashKind kind) {
        Vec3 forward = horizontalForward(mc);
        Vec3 oldRightAxis = new Vec3(-forward.z, 0.0, forward.x).normalize();

        return switch (kind) {
            // Intentionally opposite to the previous build: A/D were reversed in-game.
            case LEFT -> oldRightAxis;
            case RIGHT -> oldRightAxis.scale(-1.0);
            case FRONT, AIR -> forward;
        };
    }

    private static Object movementAction(String name) {
        for (Object constant : MOVEMENT_ACTION_CLASS.getEnumConstants()) {
            if (constant instanceof Enum<?> value && value.name().equals(name)) return constant;
        }
        throw new IllegalArgumentException("Unknown movement action: " + name);
    }

    private static void startLegacyAnimation(JujutsuNeonMovementPatch.DashKind kind) {
        String animation;
        int ticks;

        switch (kind) {
            case AIR -> {
                animation = "AIR_DASH";
                ticks = 5;
            }
            case LEFT -> {
                animation = "SIDE_DASH_LEFT";
                ticks = 6;
            }
            case RIGHT -> {
                animation = "SIDE_DASH_RIGHT";
                ticks = 6;
            }
            default -> {
                animation = "FRONT_DASH";
                ticks = 14;
            }
        }

        try {
            START_ANIM.invoke(null, animation, ticks);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean startPatchedDash(Minecraft mc, JujutsuNeonMovementPatch.DashKind kind) {
        if (mc.player == null || mc.level == null) return false;

        String legacyAction = switch (kind) {
            case LEFT -> "LEFT_DASH";
            case RIGHT -> "RIGHT_DASH";
            case FRONT, AIR -> "FRONT_DASH";
        };

        try {
            START_CLIENT_DASH.invoke(null, mc, movementAction(legacyAction));
        } catch (ReflectiveOperationException exception) {
            return false;
        }

        if (legacyDashMode() == 0) return false;

        desiredDashDirection = correctedDashDirection(mc, kind);
        setLegacyDashDirection(desiredDashDirection);
        JujutsuNeonMovementPatch.sendDash(kind);
        startLegacyAnimation(kind);
        return true;
    }

    private static boolean canStepUp(Minecraft mc, Vec3 direction, double probe) {
        if (mc.player == null || mc.level == null) return false;
        AABB box = mc.player.getBoundingBox();
        Vec3 horizontal = direction.scale(probe);

        for (double lift = 0.125; lift <= 2.0001; lift += 0.125) {
            Vec3 candidate = new Vec3(horizontal.x, lift, horizontal.z);
            if (mc.level.noCollision(mc.player, box.move(candidate))) return true;
        }
        return false;
    }

    /**
     * Projects a blocked dash onto the free horizontal axis. This is the missing
     * wall-slide step in the old forced-movement solver.
     */
    private static Vec3 wallSlideDirection(Minecraft mc, Vec3 desired, int dashMode) {
        if (mc.player == null || mc.level == null || desired.lengthSqr() < 1.0E-8) return desired;

        Vec3 direction = new Vec3(desired.x, 0.0, desired.z).normalize();
        AABB box = mc.player.getBoundingBox();
        double probe = 0.18;
        Vec3 fullStep = direction.scale(probe);

        if (mc.level.noCollision(mc.player, box.move(fullStep))) return direction;

        // Ground/front/side dash keeps the existing automatic 1-2 block climb.
        if (dashMode != 3 && canStepUp(mc, direction, probe)) return direction;

        boolean xFree = Math.abs(direction.x) > 1.0E-5 &&
                mc.level.noCollision(mc.player, box.move(direction.x * probe, 0.0, 0.0));
        boolean zFree = Math.abs(direction.z) > 1.0E-5 &&
                mc.level.noCollision(mc.player, box.move(0.0, 0.0, direction.z * probe));

        if (xFree && zFree) {
            return Math.abs(direction.x) >= Math.abs(direction.z)
                    ? new Vec3(Math.signum(direction.x), 0.0, 0.0)
                    : new Vec3(0.0, 0.0, Math.signum(direction.z));
        }
        if (xFree) return new Vec3(Math.signum(direction.x), 0.0, 0.0);
        if (zFree) return new Vec3(0.0, 0.0, Math.signum(direction.z));

        return direction;
    }

    private static void adjustDashForWallSlide(Minecraft mc) {
        int mode = legacyDashMode();
        if (mode == 0 || desiredDashDirection.lengthSqr() < 1.0E-8) return;
        setLegacyDashDirection(wallSlideDirection(mc, desiredDashDirection, mode));
    }

    private static Vec3 customMovementDirection(Minecraft mc) {
        if (mc.player == null) return Vec3.ZERO;

        double forwardInput =
                (mc.options.keyUp.isDown() ? 1.0 : 0.0) -
                (mc.options.keyDown.isDown() ? 1.0 : 0.0);
        double strafeInput =
                (mc.options.keyRight.isDown() ? 1.0 : 0.0) -
                (mc.options.keyLeft.isDown() ? 1.0 : 0.0);

        if (Math.abs(forwardInput) < 1.0E-4 && Math.abs(strafeInput) < 1.0E-4) {
            return Vec3.ZERO;
        }

        double yaw = Math.toRadians(mc.player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vec3 right = new Vec3(forward.z, 0.0, -forward.x);
        Vec3 result = forward.scale(forwardInput).add(right.scale(strafeInput));
        return result.lengthSqr() > 1.0E-6 ? result.normalize() : Vec3.ZERO;
    }

    private static boolean isNaturalTreeLog(Level level, BlockPos start) {
        if (!level.getBlockState(start).is(BlockTags.LOGS)) return false;

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start.immutable());

        boolean grounded = false;
        int leavesNearby = 0;
        int minY = start.getY();
        int maxY = start.getY();

        while (!queue.isEmpty() && visited.size() < 40) {
            BlockPos pos = queue.removeFirst();
            if (!visited.add(pos)) continue;

            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.LOGS)) continue;

            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());

            BlockState below = level.getBlockState(pos.below());
            if (below.is(Blocks.DIRT) ||
                    below.is(Blocks.GRASS_BLOCK) ||
                    below.is(Blocks.PODZOL) ||
                    below.is(Blocks.COARSE_DIRT) ||
                    below.is(Blocks.ROOTED_DIRT) ||
                    below.is(Blocks.MUD) ||
                    below.is(Blocks.MYCELIUM)) {
                grounded = true;
            }

            for (int dx = -2; dx <= 2 && leavesNearby < 4; dx++) {
                for (int dy = -2; dy <= 3 && leavesNearby < 4; dy++) {
                    for (int dz = -2; dz <= 2 && leavesNearby < 4; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) > 3) continue;
                        if (level.getBlockState(pos.offset(dx, dy, dz)).is(BlockTags.LEAVES)) {
                            leavesNearby++;
                        }
                    }
                }
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!visited.contains(next) && level.getBlockState(next).is(BlockTags.LOGS)) {
                    queue.addLast(next.immutable());
                }
            }
        }

        return grounded && leavesNearby >= 4 && (maxY - minY >= 2);
    }

    private static boolean isSuperRunBreakable(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;

        if (state.is(BlockTags.LEAVES) ||
                state.is(BlockTags.FLOWERS) ||
                state.is(BlockTags.SAPLINGS)) {
            return true;
        }

        if (state.is(BlockTags.LOGS)) return isNaturalTreeLog(level, pos);

        return state.is(Blocks.GRASS) ||
                state.is(Blocks.TALL_GRASS) ||
                state.is(Blocks.FERN) ||
                state.is(Blocks.LARGE_FERN) ||
                state.is(Blocks.VINE) ||
                state.is(Blocks.DEAD_BUSH) ||
                state.is(Blocks.SWEET_BERRY_BUSH) ||
                state.is(Blocks.AZALEA) ||
                state.is(Blocks.FLOWERING_AZALEA) ||
                state.is(Blocks.BAMBOO) ||
                state.is(Blocks.SUGAR_CANE);
    }

    /**
     * Client-side prediction: remove only blocks the server is already allowed to
     * vaporize during super-run, before the old forced-move collision test runs.
     * The server remains authoritative and performs the real no-drop removal.
     */
    private static void preclearSuperRunPath(Minecraft mc, Vec3 direction) {
        if (mc.player == null || mc.level == null || direction.lengthSqr() < 1.0E-8) return;

        AABB sweep = mc.player.getBoundingBox()
                .expandTowards(direction.normalize().scale(0.95))
                .inflate(0.10, 0.05, 0.10);

        int minX = (int) Math.floor(sweep.minX);
        int minY = (int) Math.floor(sweep.minY);
        int minZ = (int) Math.floor(sweep.minZ);
        int maxX = (int) Math.floor(sweep.maxX);
        int maxY = (int) Math.floor(sweep.maxY);
        int maxZ = (int) Math.floor(sweep.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (!isSuperRunBreakable(mc.level, pos, state)) continue;

                    // Prediction only: no drops are created client-side.
                    mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
                }
            }
        }
    }

    private static void spawnWaterRunPredictionVfx(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.18, mc.player.getZ());
        var fluid = mc.level.getFluidState(below);
        if (!fluid.is(FluidTags.WATER)) return;

        double surfaceY = below.getY() + fluid.getHeight(mc.level, below);
        if (mc.player.getY() < surfaceY - 0.48 || mc.player.getY() > surfaceY + 0.65) return;
        if ((mc.level.getGameTime() & 1L) != 0L) return;

        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0 * i / 8.0;
            double radius = 0.28 + (i % 3) * 0.10;
            mc.level.addParticle(
                    i % 2 == 0 ? ParticleTypes.SPLASH : ParticleTypes.ELECTRIC_SPARK,
                    mc.player.getX() + Math.cos(angle) * radius,
                    surfaceY + 0.06,
                    mc.player.getZ() + Math.sin(angle) * radius,
                    Math.cos(angle) * 0.08,
                    0.10,
                    Math.sin(angle) * 0.08
            );
        }
    }

    private static void gateLowAltitudeAirJump(Minecraft mc, boolean rawJumpHeld) {
        restoreJumpKey = false;

        if (!rawJumpHeld) {
            blockedAirJumpUntilRelease = false;
            return;
        }

        boolean newPress = !previousRawJumpHeld;
        boolean airborne = !hasGroundSupport(mc, 0.34);
        boolean highEnough = atLeastFourBlocksAboveGround(mc);

        if (airborne && !highEnough && newPress) {
            blockedAirJumpUntilRelease = true;
        }

        if (blockedAirJumpUntilRelease) {
            mc.options.keyJump.setDown(false);
            restoreJumpKey = true;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTickHigh(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        rawJumpHeldThisTick = mc.options.keyJump.isDown();
        restoreJumpKey = false;

        boolean blindfold = hasBlindfold(mc);
        if (!blindfold || mc.screen != null || mc.player.getAbilities().flying) {
            desiredDashDirection = Vec3.ZERO;
            blockedAirJumpUntilRelease = false;
            previousRawJumpHeld = rawJumpHeldThisTick;
            return;
        }

        gateLowAltitudeAirJump(mc, rawJumpHeldThisTick);

        // Consume the custom Q binding first. Vanilla Drop Item remains a separate
        // key mapping; when either hand contains an item we deliberately do nothing.
        while (JujutsuNeonMod.ClientModEvents.DASH_KEY.consumeClick()) {
            if (!handsEmpty(mc)) continue;
            if (legacyTechniqueLocksMovement()) continue;
            if (legacyDashMode() != 0) continue;

            boolean grounded = hasGroundSupport(mc, 0.36);
            JujutsuNeonMovementPatch.DashKind kind;

            if (!grounded) {
                // Air dash is explicit: >=4 blocks AND Space held. 0-3 blocks => no dash.
                if (!atLeastFourBlocksAboveGround(mc) || !rawJumpHeldThisTick) continue;
                kind = JujutsuNeonMovementPatch.DashKind.AIR;
            } else {
                boolean left = mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown();
                boolean right = mc.options.keyRight.isDown() && !mc.options.keyLeft.isDown();

                if (left) kind = JujutsuNeonMovementPatch.DashKind.LEFT;
                else if (right) kind = JujutsuNeonMovementPatch.DashKind.RIGHT;
                else kind = JujutsuNeonMovementPatch.DashKind.FRONT;
            }

            startPatchedDash(mc, kind);
        }

        adjustDashForWallSlide(mc);

        // The old movement controller already has the requested ~3x walk and ~0.8
        // blocks/tick run speed. We only remove its collision race with vegetation.
        if (JujutsuNeonMod.ClientModEvents.SUPER_SPEED_KEY.isDown() &&
                legacyDashMode() == 0 &&
                !legacyTechniqueLocksMovement()) {
            Vec3 direction = customMovementDirection(mc);
            if (direction.lengthSqr() > 1.0E-8) {
                preclearSuperRunPath(mc, direction);
                spawnWaterRunPredictionVfx(mc);
            }
        }

        previousRawJumpHeld = rawJumpHeldThisTick;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTickLow(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // The original jump state machine saw a temporary 'not pressed' state.
        // Restore the physical held state after its NORMAL-priority handler finishes.
        if (restoreJumpKey) {
            mc.options.keyJump.setDown(rawJumpHeldThisTick);
            restoreJumpKey = false;
        }

        if (legacyDashMode() == 0) {
            desiredDashDirection = Vec3.ZERO;
        }
    }
}
