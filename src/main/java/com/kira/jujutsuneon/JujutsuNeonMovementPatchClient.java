package com.kira.jujutsuneon;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ClipContext;
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

/**
 * Client side of the strict blindfold movement contract.
 *
 * This class owns the Q decision and dash steering only. It must never delete world
 * blocks locally. Ground/air/water locomotion remains in ClientForgeEvents and flight
 * remains in JujutsuNeonFlightClient, with mutually exclusive state ownership.
 */
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
    private static JujutsuNeonMovementPatch.DashKind activePatchedDash = null;
    private static long lastDashStartTick = -1000L;
    private static long lastSuperRunClearRequestTick = -1000L;
    private static int pendingDashPresses = 0;
    private static boolean dashKeyHeldLastTick = false;
    private static boolean dashHoldStartedWithItem = false;

    static {
        try {
            CLIENT_EVENTS_CLASS = Class.forName("com.kira.jujutsuneon.JujutsuNeonMod$ClientForgeEvents");
            MOVEMENT_ACTION_CLASS = Class.forName("com.kira.jujutsuneon.JujutsuNeonMod$MovementAction");

            START_CLIENT_DASH = CLIENT_EVENTS_CLASS.getDeclaredMethod(
                    "startClientDash", Minecraft.class, MOVEMENT_ACTION_CLASS
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

    private JujutsuNeonMovementPatchClient() {}

    private static boolean hasBlindfold(Minecraft mc) {
        return mc.player != null &&
                mc.player.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get());
    }

    private static boolean handsEmpty(Minecraft mc) {
        return mc.player != null && mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty();
    }

    private static boolean techniqueLocksMovement() {
        try {
            return HUD_MAX_BLUE_ACTIVE.getBoolean(null) || HUD_PURPLE_CASTING.getBoolean(null)
                    || MaximumPurpleClient.isLocalActive()
                    || DomainExpansionClient.locksLocalPlayer() || LapseBlueClient.locksLocalPlayer() || RedTechniqueClient.locksLocalPlayer();
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
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
        ));
        return hit.getType() != HitResult.Type.MISS;
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
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();
        return switch (kind) {
            case LEFT -> right.scale(-1.0);
            case RIGHT -> right;
            case BACK -> forward.scale(-1.0);
            case FRONT -> forward;
        };
    }

    private static Object movementAction(String name) {
        for (Object constant : MOVEMENT_ACTION_CLASS.getEnumConstants()) {
            if (constant instanceof Enum<?> value && value.name().equals(name)) return constant;
        }
        throw new IllegalArgumentException("Unknown movement action: " + name);
    }

    private static void startLegacyAnimation(JujutsuNeonMovementPatch.DashKind kind) {
        String animation = switch (kind) {
            case BACK -> "BACK_DASH";
            case LEFT -> "SIDE_DASH_LEFT";
            case RIGHT -> "SIDE_DASH_RIGHT";
            case FRONT -> "FRONT_DASH";
        };
        int ticks = switch (kind) {
            case LEFT, RIGHT -> 8;
            case FRONT, BACK -> 14;
        };

        try {
            START_ANIM.invoke(null, animation, ticks);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean startPatchedDash(Minecraft mc, JujutsuNeonMovementPatch.DashKind kind) {
        if (mc.player == null || mc.level == null) return false;
        if (!hasGroundSupport(mc, 0.36)) return false;

        long now = mc.level.getGameTime();
        if (now - lastDashStartTick < 10L) return false;

        String legacyAction = switch (kind) {
            case LEFT -> "LEFT_DASH";
            case RIGHT -> "RIGHT_DASH";
            case BACK -> "BACK_DASH";
            case FRONT -> "FRONT_DASH";
        };

        try {
            START_CLIENT_DASH.invoke(null, mc, movementAction(legacyAction));
        } catch (ReflectiveOperationException exception) {
            return false;
        }

        if (legacyDashMode() == 0) return false;

        desiredDashDirection = correctedDashDirection(mc, kind);
        activePatchedDash = kind;
        setLegacyDashDirection(desiredDashDirection);
        JujutsuNeonMovementPatch.sendDash(kind);
        startLegacyAnimation(kind);
        lastDashStartTick = now;
        return true;
    }

    private static boolean canStepUp(Minecraft mc, Vec3 direction, double probe) {
        if (mc.player == null || mc.level == null) return false;
        AABB box = mc.player.getBoundingBox();
        Vec3 horizontal = direction.scale(probe);
        for (double lift = 0.125; lift <= 2.0001; lift += 0.125) {
            if (mc.level.noCollision(mc.player, box.move(horizontal.x, lift, horizontal.z))) return true;
        }
        return false;
    }

    private static Vec3 wallSlideDirection(Minecraft mc, Vec3 desired, int dashMode) {
        if (mc.player == null || mc.level == null || desired.lengthSqr() < 1.0E-8) return desired;

        Vec3 direction = new Vec3(desired.x, 0.0, desired.z).normalize();
        AABB box = mc.player.getBoundingBox();
        double probe = 0.18;
        Vec3 fullStep = direction.scale(probe);

        if (mc.level.noCollision(mc.player, box.move(fullStep))) return direction;
        if (canStepUp(mc, direction, probe)) return direction;

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

    private static Vec3 steerTowards(Vec3 current, Vec3 target, double maxRadians) {
        Vec3 from = new Vec3(current.x, 0.0, current.z);
        Vec3 to = new Vec3(target.x, 0.0, target.z);
        if (to.lengthSqr() < 1.0E-8) return from;
        to = to.normalize();
        if (from.lengthSqr() < 1.0E-8) return to;
        from = from.normalize();

        double dot = Math.max(-1.0, Math.min(1.0, from.dot(to)));
        double crossY = from.x * to.z - from.z * to.x;
        double angle = Math.atan2(crossY, dot);
        double turn = Math.max(-maxRadians, Math.min(maxRadians, angle));
        double c = Math.cos(turn);
        double sn = Math.sin(turn);
        Vec3 rotated = new Vec3(
                from.x * c - from.z * sn,
                0.0,
                from.x * sn + from.z * c
        );
        return rotated.lengthSqr() > 1.0E-8 ? rotated.normalize() : to;
    }

    private static void adjustDashForWallSlide(Minecraft mc) {
        int mode = legacyDashMode();
        if (mode == 0 || desiredDashDirection.lengthSqr() < 1.0E-8) return;

        boolean longitudinal = mode == 1 &&
                (activePatchedDash == JujutsuNeonMovementPatch.DashKind.FRONT ||
                 activePatchedDash == JujutsuNeonMovementPatch.DashKind.BACK);

        if (longitudinal) {
            Vec3 target = horizontalForward(mc);
            if (activePatchedDash == JujutsuNeonMovementPatch.DashKind.BACK) target = target.scale(-1.0);
            desiredDashDirection = steerTowards(desiredDashDirection, target, Math.toRadians(34.0));
        }

        Vec3 actual = wallSlideDirection(mc, desiredDashDirection, mode);
        setLegacyDashDirection(actual);
        if (longitudinal) JujutsuNeonMovementPatch.sendFrontSteer(actual);
    }

    private static Vec3 customMovementDirection(Minecraft mc) {
        if (mc.player == null) return Vec3.ZERO;

        double forwardInput = (mc.options.keyUp.isDown() ? 1.0 : 0.0) -
                (mc.options.keyDown.isDown() ? 1.0 : 0.0);
        double strafeInput = (mc.options.keyRight.isDown() ? 1.0 : 0.0) -
                (mc.options.keyLeft.isDown() ? 1.0 : 0.0);

        if (Math.abs(forwardInput) < 1.0E-4 && Math.abs(strafeInput) < 1.0E-4) return Vec3.ZERO;

        Vec3 forward = horizontalForward(mc);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        Vec3 result = forward.scale(forwardInput).add(right.scale(strafeInput));
        return result.lengthSqr() > 1.0E-6 ? result.normalize() : Vec3.ZERO;
    }

    /**
     * Server-authoritative vegetation clearing. The client only asks when its next
     * swept step is physically blocked; it never replaces a local block with AIR.
     */
    private static void requestSuperRunClear(Minecraft mc, Vec3 direction) {
        if (mc.player == null || mc.level == null || direction.lengthSqr() < 1.0E-8) return;

        Vec3 step = direction.normalize().scale(0.95);
        if (mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(step))) return;

        long now = mc.level.getGameTime();
        if (now - lastSuperRunClearRequestTick < 2L) return;
        lastSuperRunClearRequestTick = now;
        JujutsuNeonMovementPatch.sendSuperRunClear(direction);
    }

    private static void spawnWaterRunPredictionVfx(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.18, mc.player.getZ());
        var fluid = mc.level.getFluidState(below);
        if (!fluid.is(FluidTags.WATER)) return;

        double surfaceY = below.getY() + fluid.getHeight(mc.level, below);
        if (mc.player.getY() < surfaceY - 0.48 || mc.player.getY() > surfaceY + 0.65) return;
        if ((mc.level.getGameTime() & 1L) != 0L) return;

        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2.0 * i / 6.0;
            double radius = 0.28 + (i % 2) * 0.10;
            mc.level.addParticle(
                    i % 2 == 0 ? ParticleTypes.SPLASH : ParticleTypes.ELECTRIC_SPARK,
                    mc.player.getX() + Math.cos(angle) * radius,
                    surfaceY + 0.06,
                    mc.player.getZ() + Math.sin(angle) * radius,
                    Math.cos(angle) * 0.07,
                    0.08,
                    Math.sin(angle) * 0.07
            );
        }
    }

    /**
     * Q решаем в НАЧАЛЕ тика, до того как Minecraft обработает ванильный выброс предмета.
     * Раньше проверка рук шла в конце тика: предмет уже был выброшен, рука пустела,
     * и вместе с выбросом срабатывал рывок.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getInstance();
        KeyMapping dashKey = JujutsuNeonMod.ClientModEvents.DASH_KEY;
        boolean held = dashKey.isDown();
        int clicks = 0;
        while (dashKey.consumeClick()) clicks++;

        if (mc.player == null || mc.level == null) {
            pendingDashPresses = 0;
        } else {
            boolean empty = handsEmpty(mc);
            // Новое нажатие: запоминаем, было ли что-то в руках. Если Q зажат и выбрасывает
            // стопку по одному, рывка не будет и после того, как стопка закончится.
            if (clicks > 0 && !dashKeyHeldLastTick) dashHoldStartedWithItem = !empty;
            if (clicks > 0 && empty && !dashHoldStartedWithItem) pendingDashPresses += clicks;
        }
        dashKeyHeldLastTick = held;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTickHigh(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!hasBlindfold(mc) || mc.screen != null || mc.player.getAbilities().flying) {
            pendingDashPresses = 0;
            desiredDashDirection = Vec3.ZERO;
            activePatchedDash = null;
            return;
        }

        // Нажатия Q собраны в onClientTickStart (до ванильного выброса предмета).
        // С предметом в руках они туда не попадают: Q остаётся обычным выбросом.
        int presses = pendingDashPresses;
        pendingDashPresses = 0;
        for (int press = 0; press < presses; press++) {
            if (!handsEmpty(mc)) continue;
            if (techniqueLocksMovement()) continue;
            if (legacyDashMode() != 0) continue;
            if (!hasGroundSupport(mc, 0.36)) continue; // Air Dash does not exist.

            boolean left = mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown();
            boolean right = mc.options.keyRight.isDown() && !mc.options.keyLeft.isDown();
            boolean back = !left && !right && mc.options.keyDown.isDown() && !mc.options.keyUp.isDown();

            JujutsuNeonMovementPatch.DashKind kind;
            if (left) kind = JujutsuNeonMovementPatch.DashKind.LEFT;
            else if (right) kind = JujutsuNeonMovementPatch.DashKind.RIGHT;
            else if (back) kind = JujutsuNeonMovementPatch.DashKind.BACK;
            else kind = JujutsuNeonMovementPatch.DashKind.FRONT;

            startPatchedDash(mc, kind);
        }

        adjustDashForWallSlide(mc);

        if (JujutsuNeonMod.ClientModEvents.SUPER_SPEED_KEY.isDown() &&
                legacyDashMode() == 0 && !techniqueLocksMovement()) {
            Vec3 direction = customMovementDirection(mc);
            if (direction.lengthSqr() > 1.0E-8) {
                requestSuperRunClear(mc, direction);
                spawnWaterRunPredictionVfx(mc);
            }
        }

        if (legacyDashMode() == 0) {
            desiredDashDirection = Vec3.ZERO;
            activePatchedDash = null;
        }
    }
}
