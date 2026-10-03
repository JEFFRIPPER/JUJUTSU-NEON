package com.kira.jujutsuneon;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Reference-driven Hollow Purple renderer.
 *
 * Design goal: reproduce the supplied 8.68 second reference as closely as the
 * Minecraft 1.20.1 fixed-function style renderer allows without using a flat
 * billboard as the body of the technique. Every important shape here is actual
 * 3D procedural geometry: distorted spheres, volumetric shells, broken spatial
 * rings, energy ribbons and a persistent annihilation wake.
 */
@Mod.EventBusSubscriber(
        modid = HollowPurpleOverhaul.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class HollowPurpleReferenceClient {

    private static final int MODE_NONE = 0;
    private static final int MODE_CASTING = 1;
    private static final int MODE_PROJECTILE = 2;

    private static final int TRAIL_LIFETIME = 92;
    private static final int MAX_TRAIL_POINTS = 112;
    private static final Map<UUID, VisualState> VISUALS = new HashMap<>();

    private static final Rgb RED_DARK = new Rgb(0.34f, 0.005f, 0.018f);
    private static final Rgb RED = new Rgb(1.00f, 0.015f, 0.055f);
    private static final Rgb RED_HOT = new Rgb(1.00f, 0.40f, 0.50f);
    private static final Rgb BLUE_DARK = new Rgb(0.005f, 0.08f, 0.32f);
    private static final Rgb BLUE = new Rgb(0.015f, 0.30f, 1.00f);
    private static final Rgb BLUE_HOT = new Rgb(0.35f, 0.76f, 1.00f);
    private static final Rgb VOID = new Rgb(0.018f, 0.001f, 0.035f);
    private static final Rgb PURPLE_DARK = new Rgb(0.15f, 0.004f, 0.30f);
    private static final Rgb PURPLE = new Rgb(0.56f, 0.012f, 1.00f);
    private static final Rgb MAGENTA = new Rgb(1.00f, 0.018f, 0.55f);
    private static final Rgb LAVENDER = new Rgb(0.78f, 0.48f, 1.00f);
    private static final Rgb WHITE = new Rgb(1.00f, 1.00f, 1.00f);

    private HollowPurpleReferenceClient() {
    }

    private record Rgb(float r, float g, float b) {
    }

    private static final class TrailPoint {
        final Vec3 position;
        final float radius;
        final long bornTick;

        TrailPoint(Vec3 position, float radius, long bornTick) {
            this.position = position;
            this.radius = radius;
            this.bornTick = bornTick;
        }
    }

    private static final class VisualState {
        final UUID ownerId;

        Vec3 previousPos;
        Vec3 currentPos;
        Vec3 targetPos;

        Vec3 previousDirection;
        Vec3 currentDirection;
        Vec3 targetDirection;

        float previousRadius;
        float currentRadius;
        float targetRadius;

        float previousProgress;
        float currentProgress;
        float targetProgress;

        float previousAlpha;
        float currentAlpha;
        float targetAlpha;

        int mode = MODE_NONE;
        int staleTicks;
        int releaseAge = 999;
        double distance;

        Vec3 releaseOrigin;
        Vec3 releaseDirection = new Vec3(0.0, 0.0, 1.0);
        Vec3 lastTrailPosition;
        final ArrayDeque<TrailPoint> trail = new ArrayDeque<>();

        VisualState(UUID ownerId, Vec3 pos, Vec3 direction) {
            this.ownerId = ownerId;
            previousPos = pos;
            currentPos = pos;
            targetPos = pos;
            previousDirection = direction;
            currentDirection = direction;
            targetDirection = direction;
            previousAlpha = 0.0f;
            currentAlpha = 0.0f;
            targetAlpha = 1.0f;
        }
    }

    public static void accept(HollowPurpleOverhaul.PurpleVisualPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 pos = new Vec3(msg.x(), msg.y(), msg.z());
        Vec3 direction = safeDirection(new Vec3(msg.dirX(), msg.dirY(), msg.dirZ()));

        VisualState visual = VISUALS.computeIfAbsent(
                msg.ownerId(),
                ignored -> new VisualState(msg.ownerId(), pos, direction)
        );

        if (!msg.active()) {
            visual.targetAlpha = 0.0f;
            visual.targetRadius = 0.0f;
            visual.staleTicks = 0;
            visual.mode = MODE_NONE;
            return;
        }

        if (visual.mode != MODE_PROJECTILE && msg.mode() == MODE_PROJECTILE) {
            visual.releaseAge = 0;
            visual.releaseOrigin = pos;
            visual.releaseDirection = direction;
            visual.lastTrailPosition = null;
        }

        visual.mode = msg.mode();
        visual.targetPos = pos;
        visual.targetDirection = direction;
        visual.targetRadius = Math.max(0.0f, msg.radius());
        visual.targetProgress = Mth.clamp(msg.castProgress(), 0.0f, 1.0f);
        visual.targetAlpha = 1.0f;
        visual.distance = msg.distance();
        visual.staleTicks = 0;

        if (msg.mode() == MODE_PROJECTILE && mc.level != null && msg.radius() > 0.04f) {
            if (visual.lastTrailPosition == null || visual.lastTrailPosition.distanceToSqr(pos) >= 0.64) {
                visual.trail.addLast(new TrailPoint(pos, msg.radius(), mc.level.getGameTime()));
                visual.lastTrailPosition = pos;
                while (visual.trail.size() > MAX_TRAIL_POINTS) visual.trail.removeFirst();
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            VISUALS.clear();
            return;
        }

        long now = mc.level.getGameTime();
        Iterator<Map.Entry<UUID, VisualState>> iterator = VISUALS.entrySet().iterator();

        while (iterator.hasNext()) {
            VisualState visual = iterator.next().getValue();

            visual.previousPos = visual.currentPos;
            visual.previousDirection = visual.currentDirection;
            visual.previousRadius = visual.currentRadius;
            visual.previousProgress = visual.currentProgress;
            visual.previousAlpha = visual.currentAlpha;

            visual.currentPos = visual.currentPos.lerp(visual.targetPos, 0.72);
            visual.currentDirection = safeDirection(visual.currentDirection.lerp(visual.targetDirection, 0.62));
            visual.currentRadius += (visual.targetRadius - visual.currentRadius) * 0.66f;
            visual.currentProgress += (visual.targetProgress - visual.currentProgress) * 0.64f;
            visual.currentAlpha += (visual.targetAlpha - visual.currentAlpha) * 0.42f;
            visual.staleTicks++;

            if (visual.releaseAge < 999) visual.releaseAge++;

            while (!visual.trail.isEmpty() && now - visual.trail.peekFirst().bornTick >= TRAIL_LIFETIME) {
                visual.trail.removeFirst();
            }

            if (visual.staleTicks > 16) {
                visual.targetAlpha = 0.0f;
                visual.targetRadius = 0.0f;
            }

            if (visual.targetAlpha <= 0.001f && visual.currentAlpha <= 0.012f && visual.trail.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /**
     * Reference behavior: the environment loses exposure during fusion/charge,
     * stays almost black at the final compression, then snaps back over a few
     * frames after release. HUD is rendered after this overlay and stays readable.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || VISUALS.isEmpty()) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float darkness = 0.0f;
        float flash = 0.0f;

        for (VisualState visual : VISUALS.values()) {
            float proximity = (float) (1.0 - Mth.clamp(camera.distanceTo(visual.currentPos) / 96.0, 0.0, 1.0));
            if (proximity <= 0.0f) continue;

            if (visual.mode == MODE_CASTING) {
                float p = Mth.clamp(visual.currentProgress, 0.0f, 1.0f);
                float fusion = (float) smooth((p - 0.46f) / 0.26f);
                float finalCharge = (float) smooth((p - 0.72f) / 0.28f);
                float value = (0.08f * fusion + 0.66f * finalCharge) * proximity * visual.currentAlpha;
                darkness = Math.max(darkness, value);
            } else if (visual.mode == MODE_PROJECTILE && visual.releaseAge < 16) {
                float life = 1.0f - visual.releaseAge / 16.0f;
                darkness = Math.max(darkness, 0.70f * life * proximity);

                if (visual.releaseAge < 5) {
                    float f = 1.0f - visual.releaseAge / 5.0f;
                    flash = Math.max(flash, f * proximity * 0.22f);
                }
            }
        }

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        if (darkness > 0.008f) {
            int alpha = Mth.clamp((int) (darkness * 255.0f), 0, 205);
            event.getGuiGraphics().fill(0, 0, width, height, alpha << 24);
        }

        if (flash > 0.005f) {
            int alpha = Mth.clamp((int) (flash * 255.0f), 0, 62);
            int color = (alpha << 24) | 0x00E9C8FF;
            event.getGuiGraphics().fill(0, 0, width, height, color);
        }
    }

    /** Reference camera impulse: light pressure while charging, violent release kick. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || VISUALS.isEmpty()) return;

        Vec3 camera = event.getCamera().getPosition();
        double time = mc.level.getGameTime() + mc.getFrameTime();
        float amplitude = 0.0f;

        for (VisualState visual : VISUALS.values()) {
            boolean owner = visual.ownerId.equals(mc.player.getUUID());
            double distance = camera.distanceTo(visual.currentPos);
            float proximity = owner ? 1.0f : (float) (1.0 - Mth.clamp(distance / 42.0, 0.0, 1.0));
            if (proximity <= 0.0f) continue;

            if (visual.mode == MODE_CASTING) {
                float p = visual.currentProgress;
                float pressure = (float) smooth((p - 0.70f) / 0.30f);
                amplitude = Math.max(amplitude, pressure * (owner ? 0.72f : 0.34f) * proximity);
            }

            if (visual.mode == MODE_PROJECTILE && visual.releaseAge < 18) {
                float life = 1.0f - visual.releaseAge / 18.0f;
                float kick = life * life * (owner ? 3.10f : 1.45f) * proximity;
                amplitude = Math.max(amplitude, kick);
            }
        }

        if (amplitude <= 0.001f) return;

        float yaw = (float) (Math.sin(time * 4.93) * amplitude * 0.72 + Math.sin(time * 9.11) * amplitude * 0.22);
        float pitch = (float) (Math.sin(time * 5.77 + 1.2) * amplitude * 0.55 + Math.cos(time * 10.7) * amplitude * 0.18);
        float roll = (float) (Math.sin(time * 6.81 + 2.0) * amplitude * 0.48);

        event.setYaw(event.getYaw() + yaw);
        event.setPitch(event.getPitch() + pitch);
        event.setRoll(event.getRoll() + roll);
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (VISUALS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        PoseStack pose = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = event.getPartialTick();
        double time = mc.level.getGameTime() + partialTick;
        long now = mc.level.getGameTime();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (VisualState visual : VISUALS.values()) {
            Vec3 pos = visual.previousPos.lerp(visual.currentPos, partialTick);
            Vec3 direction = safeDirection(visual.previousDirection.lerp(visual.currentDirection, partialTick));
            float radius = Mth.lerp(partialTick, visual.previousRadius, visual.currentRadius);
            float progress = Mth.lerp(partialTick, visual.previousProgress, visual.currentProgress);
            float alpha = Mth.lerp(partialTick, visual.previousAlpha, visual.currentAlpha);

            renderResidualWake(pose, camera, visual, direction, now, partialTick, time);
            if (alpha <= 0.01f) continue;

            if (visual.mode == MODE_CASTING) {
                renderCast(pose, camera, pos, direction, progress, alpha, time);
            } else if (visual.mode == MODE_PROJECTILE) {
                renderProjectile(pose, camera, visual, pos, direction, Math.max(0.05f, radius), alpha, time);
            }
        }

        alphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderCast(
            PoseStack pose,
            Vec3 camera,
            Vec3 eye,
            Vec3 direction,
            float progress,
            float alpha,
            double time
    ) {
        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        Vec3 backCenter = eye.subtract(direction.scale(1.30)).add(0.0, -0.18, 0.0);
        // Video reference: Blue is on the caster's left, Red on the right.
        Vec3 blueStart = backCenter.add(right.scale(-2.10));
        Vec3 redStart = backCenter.add(right.scale(2.10));
        Vec3 mergePoint = eye.add(direction.scale(2.35)).add(0.0, -0.12, 0.0);

        float p = Mth.clamp(progress, 0.0f, 1.0f);

        // 0.00 - 0.32: Red/Blue materialize as true 3D Limitless orbs.
        if (p < 0.32f) {
            float grow = (float) smooth(p / 0.32f);
            float radius = 0.16f + grow * 1.10f;
            renderLimitlessModel(pose, camera, redStart, direction, radius, alpha, time, false);
            renderLimitlessModel(pose, camera, blueStart, direction, radius, alpha, time, true);
            renderEnergyBridge(pose, camera, redStart, blueStart, alpha * grow * 0.28f, time, 11.0);
            return;
        }

        // 0.32 - 0.58: visible convergence. The two colored bodies remain separate
        // until the very end, matching the reference instead of cross-fading sprites.
        if (p < 0.58f) {
            float merge = (float) smooth((p - 0.32f) / 0.26f);
            Vec3 red = redStart.lerp(mergePoint, merge);
            Vec3 blue = blueStart.lerp(mergePoint, merge);
            float radius = 1.26f - merge * 0.56f;

            renderLimitlessModel(pose, camera, red, direction, radius, alpha * (1.0f - merge * 0.18f), time, false);
            renderLimitlessModel(pose, camera, blue, direction, radius, alpha * (1.0f - merge * 0.18f), time, true);
            renderEnergyBridge(pose, camera, red, blue, alpha * (0.42f + merge * 0.35f), time, 19.0);

            float seed = (float) smooth((merge - 0.38f) / 0.62f);
            if (seed > 0.001f) {
                Vec3 midpoint = red.lerp(blue, 0.5);
                renderPurpleModel(pose, camera, midpoint, direction,
                        0.14f + seed * 0.72f,
                        alpha * seed,
                        time,
                        0.62f + seed * 0.22f,
                        seed * 0.35f);
            }
            return;
        }

        // 0.58 - 0.74: the two Limitless poles collapse into a compact Purple seed.
        if (p < 0.74f) {
            float fusion = (float) smooth((p - 0.58f) / 0.16f);
            float radius = 0.72f + fusion * 0.54f;
            renderPurpleModel(pose, camera, mergePoint, direction, radius, alpha, time,
                    0.86f + fusion * 0.08f, 0.18f + fusion * 0.22f);

            additiveBlend();
            for (int i = 0; i < 3; i++) {
                Vec3 normal = safeDirection(direction
                        .add(right.scale((i - 1) * 0.34))
                        .add(up.scale((1 - i) * 0.23)));
                drawFracturedRingAt(pose, camera, mergePoint, normal,
                        radius * (1.42f + i * 0.17f),
                        radius * 0.040f,
                        time * (2.6 + i * 0.44),
                        i == 1 ? MAGENTA : PURPLE,
                        LAVENDER,
                        alpha * (0.52f - i * 0.06f),
                        0.08f,
                        17.0 + i * 13.0,
                        false);
            }
            return;
        }

        // 0.74 - 1.00: charge, exposure drop, white-hot core and pre-release
        // spatial rupture. The last 14% intentionally fills a large part of the view.
        float charge = (float) smooth((p - 0.74f) / 0.26f);
        float pulse = 1.0f + (float) Math.sin(time * 0.84) * (0.025f + charge * 0.020f);
        float radius = (1.20f + charge * 0.84f) * pulse;
        float stress = (float) smooth((p - 0.84f) / 0.16f);

        renderPurpleModel(pose, camera, mergePoint, direction, radius, alpha, time,
                0.94f + charge * 0.06f, 0.28f + stress * 0.58f);

        renderCompressionCage(pose, camera, mergePoint, direction, right, up,
                radius, stress, alpha, time);

        if (stress > 0.001f) {
            renderPreReleaseRupture(pose, camera, mergePoint, direction, right, up,
                    radius, stress, alpha, time);
        }
    }

    private static void renderLimitlessModel(
            PoseStack pose,
            Vec3 camera,
            Vec3 position,
            Vec3 direction,
            float radius,
            float alpha,
            double time,
            boolean blue
    ) {
        Rgb dark = blue ? BLUE_DARK : RED_DARK;
        Rgb main = blue ? BLUE : RED;
        Rgb hot = blue ? BLUE_HOT : RED_HOT;
        double seed = blue ? 23.0 : 47.0;

        alphaBlend();
        drawPlasmaSphereAt(pose, camera, position, radius * 1.03f, 16, 24,
                dark, main, alpha * 0.72f, 0.055f, time * 0.74, seed);
        drawPlasmaSphereAt(pose, camera, position, radius * 0.78f, 15, 22,
                dark, main, alpha * 0.82f, 0.035f, -time * 0.90, seed + 7.0);

        additiveBlend();
        drawPlasmaSphereAt(pose, camera, position, radius * 1.17f, 14, 22,
                main, hot, alpha * 0.18f, 0.10f, time, seed + 11.0);
        drawPlasmaSphereAt(pose, camera, position, radius * 0.40f, 13, 20,
                hot, WHITE, alpha * 0.72f, 0.028f, -time * 1.3, seed + 19.0);

        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();
        for (int i = 0; i < 3; i++) {
            Vec3 normal = safeDirection(direction
                    .add(right.scale((i - 1) * 0.45))
                    .add(up.scale((i == 1 ? 0.52 : -0.18) * (blue ? 1.0 : -1.0))));
            drawFracturedRingAt(pose, camera, position, normal,
                    radius * (1.23f + i * 0.10f),
                    Math.max(0.018f, radius * 0.036f),
                    (blue ? -1.0 : 1.0) * time * (2.7 + i * 0.42),
                    main, hot,
                    alpha * (0.55f - i * 0.08f),
                    0.065f,
                    seed + i * 9.0,
                    i == 2);
        }
    }

    private static void renderPurpleModel(
            PoseStack pose,
            Vec3 camera,
            Vec3 position,
            Vec3 direction,
            float radius,
            float alpha,
            double time,
            float intensity,
            float stress
    ) {
        if (radius <= 0.02f || alpha <= 0.01f) return;
        float a = alpha * intensity;

        // Dark volumetric void first. It is what makes the object read as a sphere
        // even before additive glow is applied.
        alphaBlend();
        drawPlasmaSphereAt(pose, camera, position, radius * 1.02f, 20, 30,
                PURPLE_DARK, PURPLE, a * 0.58f, 0.050f + stress * 0.045f,
                time * 0.72, 71.0);
        drawPlasmaSphereAt(pose, camera, position, radius * 0.78f, 18, 28,
                VOID, PURPLE_DARK, a * 0.92f, 0.036f + stress * 0.025f,
                -time * 0.88, 83.0);

        // Emissive plasma layers. Their radius modulation is independent, so the
        // silhouette never looks like a single transparent PNG plane.
        additiveBlend();
        drawPlasmaSphereAt(pose, camera, position, radius * 1.24f, 18, 28,
                PURPLE_DARK, PURPLE, a * 0.18f, 0.12f + stress * 0.07f,
                time * 1.05, 97.0);
        drawPlasmaSphereAt(pose, camera, position, radius * 1.00f, 22, 34,
                PURPLE, MAGENTA, a * 0.34f, 0.070f + stress * 0.055f,
                time * 1.22, 109.0);
        drawPlasmaSphereAt(pose, camera, position, radius * 0.68f, 18, 28,
                MAGENTA, LAVENDER, a * 0.26f, 0.050f,
                -time * 1.46, 127.0);
        drawPlasmaSphereAt(pose, camera, position, radius * 0.36f, 16, 24,
                LAVENDER, WHITE, a * 0.92f, 0.030f,
                time * 1.65, 139.0);
        drawPlasmaSphereAt(pose, camera, position, radius * 0.17f, 14, 22,
                WHITE, WHITE, a, 0.012f,
                -time * 2.0, 151.0);

        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        // Surface filaments hug the model in three different 3D planes.
        for (int i = 0; i < 4; i++) {
            double side = switch (i) {
                case 0 -> 0.62;
                case 1 -> -0.46;
                case 2 -> 0.18;
                default -> -0.12;
            };
            double lift = switch (i) {
                case 0 -> 0.18;
                case 1 -> 0.58;
                case 2 -> -0.70;
                default -> -0.25;
            };
            Vec3 normal = safeDirection(direction.add(right.scale(side)).add(up.scale(lift)));
            drawFracturedRingAt(pose, camera, position, normal,
                    radius * (1.13f + i * 0.09f),
                    Math.max(0.018f, radius * (0.030f - i * 0.003f)),
                    (i % 2 == 0 ? 1.0 : -1.0) * time * (3.0 + i * 0.37),
                    i % 2 == 0 ? PURPLE : MAGENTA,
                    i % 2 == 0 ? LAVENDER : WHITE,
                    a * (0.68f - i * 0.09f),
                    0.075f + stress * 0.05f,
                    173.0 + i * 23.0,
                    false);
        }

        // Stress arcs crawl over the shell as release approaches.
        if (stress > 0.15f) {
            for (int i = 0; i < 3; i++) {
                double angle = time * (0.42 + i * 0.13) + i * 2.1;
                Vec3 aPos = position
                        .add(right.scale(Math.cos(angle) * radius * 1.08))
                        .add(up.scale(Math.sin(angle) * radius * 1.08));
                Vec3 bPos = position
                        .add(right.scale(Math.cos(angle + 2.2) * radius * 1.18))
                        .add(up.scale(Math.sin(angle + 2.2) * radius * 1.18))
                        .add(direction.scale(Math.sin(angle * 1.7) * radius * 0.34));
                drawLightningArc(pose, camera, aPos, bPos,
                        Math.max(0.018f, radius * 0.018f),
                        7, time, 211.0 + i * 17.0,
                        i == 1 ? MAGENTA : LAVENDER,
                        a * stress * 0.68f,
                        radius * 0.18f);
            }
        }
    }

    private static void renderCompressionCage(
            PoseStack pose,
            Vec3 camera,
            Vec3 center,
            Vec3 direction,
            Vec3 right,
            Vec3 up,
            float radius,
            float stress,
            float alpha,
            double time
    ) {
        additiveBlend();
        for (int i = 0; i < 4; i++) {
            Vec3 normal = safeDirection(direction
                    .add(right.scale((i - 1.5) * 0.17))
                    .add(up.scale(Math.sin(i * 1.7) * 0.22)));
            float cageRadius = radius * (1.38f + i * 0.17f - stress * 0.08f);
            drawFracturedRingAt(pose, camera, center, normal,
                    cageRadius,
                    Math.max(0.025f, radius * 0.032f),
                    (i % 2 == 0 ? 1 : -1) * time * (2.7 + i * 0.28),
                    i == 2 ? MAGENTA : PURPLE,
                    LAVENDER,
                    alpha * (0.42f + stress * 0.18f - i * 0.045f),
                    0.09f,
                    251.0 + i * 31.0,
                    false);
        }
    }

    private static void renderPreReleaseRupture(
            PoseStack pose,
            Vec3 camera,
            Vec3 center,
            Vec3 direction,
            Vec3 right,
            Vec3 up,
            float bodyRadius,
            float stress,
            float alpha,
            double time
    ) {
        additiveBlend();
        float expansion = (float) smooth(stress);

        // The reference shows a huge tunnel-like ring structure immediately before
        // launch. Multiple offset, broken rings create depth instead of one flat disc.
        for (int i = 0; i < 6; i++) {
            float ringRadius = bodyRadius * (1.42f + i * 0.55f) + expansion * (0.30f + i * 0.44f);
            Vec3 ringCenter = center.add(direction.scale(-0.22 + i * 0.34));
            Vec3 normal = safeDirection(direction
                    .add(right.scale(Math.sin(i * 1.31) * 0.07))
                    .add(up.scale(Math.cos(i * 1.67) * 0.07)));

            drawFracturedRingAt(pose, camera, ringCenter, normal,
                    ringRadius,
                    0.070f + i * 0.018f,
                    (i % 2 == 0 ? 1 : -1) * time * (2.1 + i * 0.24) + i * 37.0,
                    i % 3 == 1 ? MAGENTA : PURPLE,
                    i == 0 ? WHITE : LAVENDER,
                    alpha * stress * (0.88f - i * 0.085f),
                    0.13f + i * 0.010f,
                    307.0 + i * 41.0,
                    true);
        }

        // Radial tearing streaks connect the expanding space rings with the core.
        int streaks = 10;
        for (int i = 0; i < streaks; i++) {
            double a = Math.PI * 2.0 * i / streaks + time * 0.10;
            double reach = bodyRadius * (2.6 + (i % 3) * 0.62) * stress;
            Vec3 target = center
                    .add(right.scale(Math.cos(a) * reach))
                    .add(up.scale(Math.sin(a) * reach))
                    .add(direction.scale((i % 2 == 0 ? 1 : -1) * bodyRadius * 0.38));
            drawLightningArc(pose, camera, center, target,
                    0.025f + stress * 0.025f,
                    6,
                    time,
                    401.0 + i * 19.0,
                    i % 3 == 0 ? WHITE : (i % 2 == 0 ? MAGENTA : PURPLE),
                    alpha * stress * 0.62f,
                    bodyRadius * 0.17f);
        }
    }

    private static void renderProjectile(
            PoseStack pose,
            Vec3 camera,
            VisualState visual,
            Vec3 position,
            Vec3 direction,
            float radius,
            float alpha,
            double time
    ) {
        float travelStress = 0.72f + (float) (Math.sin(time * 0.95) * 0.08);
        renderPurpleModel(pose, camera, position, direction, radius, alpha, time,
                1.0f, travelStress);

        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        additiveBlend();
        for (int i = 0; i < 4; i++) {
            Vec3 ringCenter = position.subtract(direction.scale(0.34 + i * 0.42));
            float ringRadius = radius * (1.34f + i * 0.18f);
            drawFracturedRingAt(pose, camera, ringCenter, direction,
                    ringRadius,
                    Math.max(0.026f, radius * (0.038f - i * 0.004f)),
                    time * (3.7 - i * 0.36) + i * 29.0,
                    i == 1 ? MAGENTA : PURPLE,
                    LAVENDER,
                    alpha * (0.70f - i * 0.10f),
                    0.09f,
                    503.0 + i * 13.0,
                    i >= 2);
        }

        // Long crooked lightning whips like the reference launch frame.
        for (int i = 0; i < 3; i++) {
            double angle = time * (0.44 + i * 0.08) + i * 2.35;
            Vec3 start = position
                    .add(right.scale(Math.cos(angle) * radius * 0.72))
                    .add(up.scale(Math.sin(angle) * radius * 0.72));
            Vec3 end = position.subtract(direction.scale(radius * (2.2 + i * 0.65)))
                    .add(right.scale(Math.cos(angle + 1.4) * radius * (1.4 + i * 0.25)))
                    .add(up.scale(Math.sin(angle + 1.4) * radius * (1.0 + i * 0.20)));
            drawLightningArc(pose, camera, start, end,
                    0.035f + i * 0.008f,
                    9,
                    time,
                    557.0 + i * 43.0,
                    i == 0 ? WHITE : (i == 1 ? MAGENTA : PURPLE),
                    alpha * (0.84f - i * 0.16f),
                    radius * (0.28f + i * 0.08f));
        }

        renderReleaseBurst(pose, camera, visual, alpha, time);
    }

    private static void renderReleaseBurst(
            PoseStack pose,
            Vec3 camera,
            VisualState visual,
            float alpha,
            double time
    ) {
        if (visual.releaseOrigin == null || visual.releaseAge >= 22) return;

        float life = 1.0f - visual.releaseAge / 22.0f;
        float expansion = (float) smooth(1.0f - life);
        Vec3 direction = safeDirection(visual.releaseDirection);
        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        additiveBlend();
        for (int i = 0; i < 7; i++) {
            Vec3 center = visual.releaseOrigin.add(direction.scale(i * 0.72 + expansion * i * 0.34));
            float ringRadius = 1.70f + i * 0.82f + expansion * (2.2f + i * 0.40f);
            Vec3 normal = safeDirection(direction
                    .add(right.scale(Math.sin(i * 1.3) * 0.06))
                    .add(up.scale(Math.cos(i * 1.7) * 0.06)));
            drawFracturedRingAt(pose, camera, center, normal,
                    ringRadius,
                    0.085f + i * 0.016f,
                    (i % 2 == 0 ? 1 : -1) * time * (2.7 + i * 0.17) + i * 31.0,
                    i % 3 == 1 ? MAGENTA : PURPLE,
                    i == 0 ? WHITE : LAVENDER,
                    alpha * life * (0.95f - i * 0.075f),
                    0.16f,
                    601.0 + i * 47.0,
                    true);
        }

        // Central white punch and a dark spatial hole behind it.
        alphaBlend();
        drawPlasmaSphereAt(pose, camera, visual.releaseOrigin, 1.10f + expansion * 1.45f,
                16, 24, VOID, PURPLE_DARK, alpha * life * 0.62f,
                0.16f, time * 0.8, 701.0);
        additiveBlend();
        drawPlasmaSphereAt(pose, camera, visual.releaseOrigin.add(direction.scale(0.25)),
                0.48f + expansion * 0.72f,
                14, 22, LAVENDER, WHITE, alpha * life * 0.92f,
                0.08f, -time * 1.4, 719.0);
    }

    private static void renderResidualWake(
            PoseStack pose,
            Vec3 camera,
            VisualState visual,
            Vec3 direction,
            long now,
            float partialTick,
            double time
    ) {
        if (visual.trail.isEmpty()) return;

        int index = 0;
        for (TrailPoint node : visual.trail) {
            long ageTicks = now - node.bornTick;
            if (ageTicks < 0 || ageTicks >= TRAIL_LIFETIME) {
                index++;
                continue;
            }

            float age = Mth.clamp((ageTicks + partialTick) / (float) TRAIL_LIFETIME, 0.0f, 1.0f);
            float fade = 1.0f - age;
            float radius = Math.max(0.16f, node.radius * (0.95f + age * 0.10f));

            // Dense near the projectile, sparse farther back.
            if (index % 2 == 0) {
                alphaBlend();
                drawPlasmaSphereAt(pose, camera, node.position,
                        radius * (0.86f + age * 0.18f),
                        10, 16,
                        VOID, PURPLE_DARK,
                        fade * 0.34f,
                        0.13f,
                        time * 0.32,
                        811.0 + index * 3.0);

                additiveBlend();
                drawFracturedRingAt(pose, camera, node.position, direction,
                        radius * (1.05f + age * 0.18f),
                        Math.max(0.015f, radius * 0.020f),
                        time * 1.55 + index * 19.0,
                        PURPLE, LAVENDER,
                        fade * 0.36f,
                        0.10f,
                        907.0 + index * 5.0,
                        true);
            }

            if (index % 5 == 0 && fade > 0.35f) {
                Vec3 right = rightFor(direction);
                double angle = index * 1.71 + time * 0.18;
                Vec3 end = node.position
                        .add(right.scale(Math.cos(angle) * radius * 1.5))
                        .add(direction.scale(-radius * 0.75));
                drawLightningArc(pose, camera, node.position, end,
                        0.018f,
                        5,
                        time,
                        1009.0 + index,
                        index % 10 == 0 ? MAGENTA : PURPLE,
                        fade * 0.32f,
                        radius * 0.16f);
            }
            index++;
        }
    }

    private static void renderEnergyBridge(
            PoseStack pose,
            Vec3 camera,
            Vec3 from,
            Vec3 to,
            float alpha,
            double time,
            double seed
    ) {
        if (alpha <= 0.001f) return;
        additiveBlend();
        drawLightningArc(pose, camera, from, to,
                0.026f, 10, time, seed,
                PURPLE, alpha, 0.22f);
        drawLightningArc(pose, camera, from, to,
                0.012f, 12, -time, seed + 31.0,
                LAVENDER, alpha * 0.72f, 0.14f);
    }

    /**
     * Real plasma sphere mesh. Radius is deformed independently at each lat/lon
     * vertex; color also changes per vertex. This is the key difference from the
     * previous translucent concentric spheres and from any billboard particle.
     */
    private static void drawPlasmaSphereAt(
            PoseStack pose,
            Vec3 camera,
            Vec3 worldPosition,
            float radius,
            int latitudeSegments,
            int longitudeSegments,
            Rgb colorA,
            Rgb colorB,
            float alpha,
            float distortion,
            double time,
            double seed
    ) {
        if (radius <= 0.0f || alpha <= 0.0f) return;

        pose.pushPose();
        pose.translate(worldPosition.x - camera.x, worldPosition.y - camera.y, worldPosition.z - camera.z);

        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int lat = 0; lat < latitudeSegments; lat++) {
            double v0 = lat / (double) latitudeSegments;
            double v1 = (lat + 1) / (double) latitudeSegments;
            double phi0 = -Math.PI / 2.0 + Math.PI * v0;
            double phi1 = -Math.PI / 2.0 + Math.PI * v1;

            for (int lon = 0; lon < longitudeSegments; lon++) {
                double u0 = lon / (double) longitudeSegments;
                double u1 = (lon + 1) / (double) longitudeSegments;
                double theta0 = Math.PI * 2.0 * u0;
                double theta1 = Math.PI * 2.0 * u1;

                emitSphereVertex(buffer, matrix, phi0, theta0, radius, distortion, time, seed, colorA, colorB, alpha);
                emitSphereVertex(buffer, matrix, phi1, theta0, radius, distortion, time, seed, colorA, colorB, alpha);
                emitSphereVertex(buffer, matrix, phi1, theta1, radius, distortion, time, seed, colorA, colorB, alpha);

                emitSphereVertex(buffer, matrix, phi0, theta0, radius, distortion, time, seed, colorA, colorB, alpha);
                emitSphereVertex(buffer, matrix, phi1, theta1, radius, distortion, time, seed, colorA, colorB, alpha);
                emitSphereVertex(buffer, matrix, phi0, theta1, radius, distortion, time, seed, colorA, colorB, alpha);
            }
        }

        BufferUploader.drawWithShader(buffer.end());
        pose.popPose();
    }

    private static void emitSphereVertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            double phi,
            double theta,
            float radius,
            float distortion,
            double time,
            double seed,
            Rgb colorA,
            Rgb colorB,
            float alpha
    ) {
        double wave =
                Math.sin(theta * 3.0 + time * 0.18 + seed) * 0.46 +
                Math.sin(phi * 5.0 - time * 0.15 + seed * 0.37) * 0.31 +
                Math.cos((theta + phi) * 4.0 + time * 0.11 - seed * 0.21) * 0.23;

        double localRadius = radius * (1.0 + distortion * wave);
        double cosPhi = Math.cos(phi);
        float x = (float) (Math.cos(theta) * cosPhi * localRadius);
        float y = (float) (Math.sin(phi) * localRadius);
        float z = (float) (Math.sin(theta) * cosPhi * localRadius);

        float mix = (float) (0.5 + 0.5 * Math.sin(theta * 2.0 + phi * 3.0 + time * 0.13 + seed));
        float light = (float) (0.72 + 0.28 * (0.5 + 0.5 * Math.cos(theta - 0.72) * cosPhi));

        float r = Mth.lerp(mix, colorA.r, colorB.r) * light;
        float g = Mth.lerp(mix, colorA.g, colorB.g) * light;
        float b = Mth.lerp(mix, colorA.b, colorB.b) * light;

        vertex(buffer, matrix, x, y, z,
                Mth.clamp(r, 0.0f, 1.0f),
                Mth.clamp(g, 0.0f, 1.0f),
                Mth.clamp(b, 0.0f, 1.0f),
                alpha);
    }

    /** Irregular 3D annulus with optional broken segments, used for spatial tears. */
    private static void drawFracturedRingAt(
            PoseStack pose,
            Vec3 camera,
            Vec3 worldPosition,
            Vec3 normal,
            float radius,
            float thickness,
            double rotation,
            Rgb colorA,
            Rgb colorB,
            float alpha,
            float radialNoise,
            double seed,
            boolean broken
    ) {
        if (radius <= 0.0f || alpha <= 0.0f) return;

        Vec3 n = safeDirection(normal);
        Vec3 right = rightFor(n);
        Vec3 up = right.cross(n).normalize();

        pose.pushPose();
        pose.translate(worldPosition.x - camera.x, worldPosition.y - camera.y, worldPosition.z - camera.z);
        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        int segments = 84;
        double baseRotation = Math.toRadians(rotation);
        float half = Math.max(0.006f, thickness);

        for (int i = 0; i < segments; i++) {
            double gate = Math.sin(i * 1.73 + seed) + Math.sin(i * 0.47 - seed * 0.31);
            if (broken && gate > 1.12) continue;

            double a0 = Math.PI * 2.0 * i / segments + baseRotation;
            double a1 = Math.PI * 2.0 * (i + 1) / segments + baseRotation;
            double wobble0 = 1.0 + radialNoise * (
                    Math.sin(a0 * 5.0 + seed) * 0.55 +
                    Math.sin(a0 * 11.0 - seed * 0.7) * 0.28 +
                    Math.cos(a0 * 3.0 + seed * 0.2) * 0.17);
            double wobble1 = 1.0 + radialNoise * (
                    Math.sin(a1 * 5.0 + seed) * 0.55 +
                    Math.sin(a1 * 11.0 - seed * 0.7) * 0.28 +
                    Math.cos(a1 * 3.0 + seed * 0.2) * 0.17);

            double r0 = radius * wobble0;
            double r1 = radius * wobble1;
            Vec3 q0 = ringPoint(right, up, a0, r0 + half);
            Vec3 q1 = ringPoint(right, up, a1, r1 + half);
            Vec3 p0 = ringPoint(right, up, a0, Math.max(0.001, r0 - half));
            Vec3 p1 = ringPoint(right, up, a1, Math.max(0.001, r1 - half));

            float mix0 = (float) (0.5 + 0.5 * Math.sin(a0 * 2.0 + seed));
            float mix1 = (float) (0.5 + 0.5 * Math.sin(a1 * 2.0 + seed));
            Rgb c0 = mix(colorA, colorB, mix0);
            Rgb c1 = mix(colorA, colorB, mix1);
            float segmentAlpha = alpha * (float) (0.78 + 0.22 * Math.sin(a0 * 7.0 + seed));

            emitQuad(buffer, matrix, q0, q1, p1, p0, c0, c1, segmentAlpha);
        }

        BufferUploader.drawWithShader(buffer.end());
        pose.popPose();
    }

    /** Jagged luminous 3D ribbon. Secondary effect only; the body remains a mesh. */
    private static void drawLightningArc(
            PoseStack pose,
            Vec3 camera,
            Vec3 start,
            Vec3 end,
            float thickness,
            int segments,
            double time,
            double seed,
            Rgb color,
            float alpha,
            float wobble
    ) {
        if (alpha <= 0.0f || thickness <= 0.0f) return;
        Vec3 axis = end.subtract(start);
        if (axis.lengthSqr() < 1.0E-8) return;

        Vec3 forward = axis.normalize();
        Vec3 right = rightFor(forward);
        Vec3 up = right.cross(forward).normalize();

        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        Vec3 previous = start;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            double envelope = Math.sin(Math.PI * t);
            double phase = seed + i * 2.173 + time * 0.31;
            Vec3 current = start.lerp(end, t)
                    .add(right.scale(Math.sin(phase * 1.7) * wobble * envelope))
                    .add(up.scale(Math.cos(phase * 2.3) * wobble * 0.72 * envelope));

            emitBeamSegment(buffer, matrix, camera, previous, current, right, up,
                    thickness * (0.80f + 0.20f * (float) Math.sin(i * 1.9 + seed)),
                    color, alpha);
            previous = current;
        }

        BufferUploader.drawWithShader(buffer.end());
    }

    private static void emitBeamSegment(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3 camera,
            Vec3 a,
            Vec3 b,
            Vec3 referenceRight,
            Vec3 referenceUp,
            float thickness,
            Rgb color,
            float alpha
    ) {
        Vec3 ar = a.subtract(camera);
        Vec3 br = b.subtract(camera);
        Vec3 r = referenceRight.scale(thickness);
        Vec3 u = referenceUp.scale(thickness);

        emitQuad(buffer, matrix,
                ar.add(r), br.add(r), br.subtract(r), ar.subtract(r),
                color, WHITE, alpha);
        emitQuad(buffer, matrix,
                ar.add(u), br.add(u), br.subtract(u), ar.subtract(u),
                color, WHITE, alpha * 0.82f);
    }

    private static void emitQuad(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3 a,
            Vec3 b,
            Vec3 c,
            Vec3 d,
            Rgb colorA,
            Rgb colorB,
            float alpha
    ) {
        vertex(buffer, matrix, a, colorA.r, colorA.g, colorA.b, alpha);
        vertex(buffer, matrix, b, colorB.r, colorB.g, colorB.b, alpha);
        vertex(buffer, matrix, c, colorB.r, colorB.g, colorB.b, alpha);

        vertex(buffer, matrix, a, colorA.r, colorA.g, colorA.b, alpha);
        vertex(buffer, matrix, c, colorB.r, colorB.g, colorB.b, alpha);
        vertex(buffer, matrix, d, colorA.r, colorA.g, colorA.b, alpha);
    }

    private static Vec3 ringPoint(Vec3 right, Vec3 up, double angle, double radius) {
        return right.scale(Math.cos(angle) * radius).add(up.scale(Math.sin(angle) * radius));
    }

    private static Rgb mix(Rgb a, Rgb b, float t) {
        float x = Mth.clamp(t, 0.0f, 1.0f);
        return new Rgb(
                Mth.lerp(x, a.r, b.r),
                Mth.lerp(x, a.g, b.g),
                Mth.lerp(x, a.b, b.b)
        );
    }

    private static Vec3 rightFor(Vec3 direction) {
        Vec3 n = safeDirection(direction);
        Vec3 reference = Math.abs(n.y) > 0.92
                ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = n.cross(reference);
        if (right.lengthSqr() < 1.0E-8) right = new Vec3(1.0, 0.0, 0.0);
        return right.normalize();
    }

    private static Vec3 safeDirection(Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 1.0E-8) {
            return new Vec3(0.0, 0.0, 1.0);
        }
        return direction.normalize();
    }

    private static double smooth(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static void alphaBlend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static void additiveBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE
        );
    }

    private static void vertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3 point,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        vertex(buffer, matrix,
                (float) point.x, (float) point.y, (float) point.z,
                red, green, blue, alpha);
    }

    private static void vertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            float x,
            float y,
            float z,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        buffer.vertex(matrix, x, y, z)
                .color(red, green, blue, Mth.clamp(alpha, 0.0f, 1.0f))
                .endVertex();
    }
}
