package com.kira.jujutsuneon;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reference-driven Hollow Purple renderer.
 *
 * The body of the technique is 100% procedural 3D geometry. No billboard PNG is
 * used as the Purple body. The supplied video is treated as the visual timeline:
 * Blue + Red manifest -> converge -> white-hot Purple core -> world exposure drop ->
 * unstable spatial rings -> violent release -> lightning/tunnel residue.
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

    private static final int TRAIL_LIFETIME_TICKS = 100;
    private static final int RELEASE_BURST_TICKS = 30;

    private static final Map<UUID, VisualState> VISUALS = new HashMap<>();

    private HollowPurpleReferenceClient() {
    }

    private static final class TrailNode {
        final Vec3 position;
        final Vec3 direction;
        final float radius;
        final long born;

        TrailNode(Vec3 position, Vec3 direction, float radius, long born) {
            this.position = position;
            this.direction = direction;
            this.radius = radius;
            this.born = born;
        }
    }

    private static final class VisualState {
        Vec3 previousPos;
        Vec3 currentPos;
        Vec3 targetPos;

        Vec3 previousDirection;
        Vec3 currentDirection;
        Vec3 targetDirection;

        float previousProgress;
        float currentProgress;
        float targetProgress;

        float previousRadius;
        float currentRadius;
        float targetRadius;

        float previousAlpha;
        float currentAlpha;
        float targetAlpha;

        int mode = MODE_NONE;
        int staleTicks;
        int releaseAge = 999;
        Vec3 releaseOrigin;
        Vec3 releaseDirection = new Vec3(0.0, 0.0, 1.0);
        Vec3 lastTrailPosition;
        double distance;

        final ArrayDeque<TrailNode> trail = new ArrayDeque<>();

        VisualState(Vec3 position, Vec3 direction) {
            previousPos = currentPos = targetPos = position;
            previousDirection = currentDirection = targetDirection = safeDirection(direction);
            previousAlpha = currentAlpha = 0.0f;
            targetAlpha = 1.0f;
        }
    }

    public static void accept(HollowPurpleOverhaul.PurpleVisualPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 pos = new Vec3(msg.x(), msg.y(), msg.z());
        Vec3 direction = safeDirection(new Vec3(msg.dirX(), msg.dirY(), msg.dirZ()));

        VisualState visual = VISUALS.computeIfAbsent(
                msg.ownerId(),
                ignored -> new VisualState(pos, direction)
        );

        if (!msg.active()) {
            visual.targetAlpha = 0.0f;
            visual.targetRadius = 0.0f;
            visual.mode = MODE_NONE;
            visual.staleTicks = 0;
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
        visual.targetProgress = Mth.clamp(msg.castProgress(), 0.0f, 1.0f);
        visual.targetRadius = Math.max(0.0f, msg.radius());
        visual.targetAlpha = 1.0f;
        visual.distance = msg.distance();
        visual.staleTicks = 0;

        if (msg.mode() == MODE_PROJECTILE && mc.level != null && msg.radius() > 0.03f) {
            if (visual.lastTrailPosition == null || visual.lastTrailPosition.distanceToSqr(pos) >= 1.40) {
                visual.trail.addLast(new TrailNode(
                        pos,
                        direction,
                        msg.radius(),
                        mc.level.getGameTime()
                ));
                visual.lastTrailPosition = pos;
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
            visual.previousProgress = visual.currentProgress;
            visual.previousRadius = visual.currentRadius;
            visual.previousAlpha = visual.currentAlpha;

            visual.currentPos = visual.currentPos.lerp(visual.targetPos, 0.72);
            visual.currentDirection = safeDirection(visual.currentDirection.lerp(visual.targetDirection, 0.64));
            visual.currentProgress += (visual.targetProgress - visual.currentProgress) * 0.64f;
            visual.currentRadius += (visual.targetRadius - visual.currentRadius) * 0.68f;
            visual.currentAlpha += (visual.targetAlpha - visual.currentAlpha) * 0.44f;
            visual.staleTicks++;

            if (visual.releaseAge < 999) visual.releaseAge++;

            while (!visual.trail.isEmpty() && now - visual.trail.peekFirst().born >= TRAIL_LIFETIME_TICKS) {
                visual.trail.removeFirst();
            }

            if (visual.staleTicks > 14) {
                visual.targetAlpha = 0.0f;
                visual.targetRadius = 0.0f;
            }

            if (visual.targetAlpha <= 0.001f && visual.currentAlpha <= 0.012f && visual.trail.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** Darkens the world while keeping vanilla HUD readable, then adds a short release flash. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || VISUALS.isEmpty()) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float darkness = 0.0f;
        float flash = 0.0f;

        for (VisualState visual : VISUALS.values()) {
            double dist = camera.distanceTo(visual.currentPos);
            float proximity = (float) (1.0 - Mth.clamp(dist / 96.0, 0.0, 1.0));

            if (visual.mode == MODE_CASTING && visual.currentAlpha > 0.01f) {
                float p = Mth.clamp(visual.currentProgress, 0.0f, 1.0f);
                float ramp = (float) smooth((p - 0.52f) / 0.48f);
                float pulse = 0.92f + 0.08f * (float) Math.sin((mc.level.getGameTime() + p * 7.0) * 0.74);
                darkness = Math.max(darkness, (0.12f + 0.66f * ramp) * pulse * proximity * visual.currentAlpha);
            }

            if (visual.releaseAge < 10 && visual.releaseOrigin != null) {
                double releaseDist = camera.distanceTo(visual.releaseOrigin);
                float releaseProximity = (float) (1.0 - Mth.clamp(releaseDist / 80.0, 0.0, 1.0));
                float life = 1.0f - visual.releaseAge / 10.0f;
                flash = Math.max(flash, life * life * releaseProximity);
            }
        }

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        if (darkness > 0.01f) {
            int a = Mth.clamp((int) (darkness * 255.0f), 0, 205);
            event.getGuiGraphics().fill(0, 0, width, height, a << 24);
        }

        if (flash > 0.01f) {
            int a = Mth.clamp((int) (flash * 98.0f), 0, 98);
            int color = (a << 24) | 0x00F4D9FF;
            event.getGuiGraphics().fill(0, 0, width, height, color);
        }
    }

    /** Reference-like camera pressure during final charge and a violent release kick. */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || VISUALS.isEmpty()) return;

        Vec3 camera = event.getCamera().getPosition();
        double time = mc.level.getGameTime() + event.getPartialTick();
        float amplitude = 0.0f;

        for (VisualState visual : VISUALS.values()) {
            if (visual.currentAlpha <= 0.01f) continue;

            if (visual.mode == MODE_CASTING) {
                float p = Mth.clamp(visual.currentProgress, 0.0f, 1.0f);
                float ramp = (float) smooth((p - 0.72f) / 0.28f);
                float proximity = (float) (1.0 - Mth.clamp(camera.distanceTo(visual.currentPos) / 56.0, 0.0, 1.0));
                amplitude = Math.max(amplitude, 0.36f * ramp * proximity);
            }

            if (visual.releaseAge < 22 && visual.releaseOrigin != null) {
                float life = 1.0f - visual.releaseAge / 22.0f;
                float proximity = (float) (1.0 - Mth.clamp(camera.distanceTo(visual.releaseOrigin) / 72.0, 0.0, 1.0));
                amplitude = Math.max(amplitude, 2.45f * life * life * proximity);
            }
        }

        if (amplitude <= 0.001f) return;

        float yawShake = (float) (Math.sin(time * 5.7) * 0.60 + Math.sin(time * 11.3 + 1.2) * 0.40);
        float pitchShake = (float) (Math.sin(time * 7.1 + 2.0) * 0.65 + Math.sin(time * 13.9) * 0.35);
        float rollShake = (float) Math.sin(time * 8.8 + 0.7);

        event.setYaw(event.getYaw() + yawShake * amplitude);
        event.setPitch(event.getPitch() + pitchShake * amplitude * 0.82f);
        event.setRoll(event.getRoll() + rollShake * amplitude * 0.46f);
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (VISUALS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick();
        double time = mc.level.getGameTime() + partial;
        long now = mc.level.getGameTime();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (VisualState visual : VISUALS.values()) {
            Vec3 pos = visual.previousPos.lerp(visual.currentPos, partial);
            Vec3 dir = safeDirection(visual.previousDirection.lerp(visual.currentDirection, partial));
            float progress = Mth.lerp(partial, visual.previousProgress, visual.currentProgress);
            float radius = Mth.lerp(partial, visual.previousRadius, visual.currentRadius);
            float alpha = Mth.lerp(partial, visual.previousAlpha, visual.currentAlpha);

            renderResidualTunnel(pose, camera, visual, now, partial, time);

            if (alpha <= 0.01f) continue;

            if (visual.mode == MODE_CASTING) {
                renderCastingSequence(pose, camera, pos, dir, progress, alpha, time);
            } else if (visual.mode == MODE_PROJECTILE) {
                renderProjectile(pose, camera, visual, pos, dir, Math.max(0.04f, radius), alpha, time);
            }
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderCastingSequence(
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

        Vec3 anchor = eye.add(direction.scale(1.50)).add(0.0, -0.32, 0.0);
        Vec3 blueStart = anchor.add(right.scale(-1.08));
        Vec3 redStart = anchor.add(right.scale(1.08));
        Vec3 mergePoint = eye.add(direction.scale(2.22)).add(0.0, -0.15, 0.0);

        if (progress < 0.30f) {
            float t = (float) smooth(progress / 0.30f);
            float radius = 0.12f + 0.92f * t;
            float orbit = 0.08f * (1.0f - t);

            renderLimitlessSphere(pose, camera, blueStart.add(up.scale(Math.sin(time * 0.18) * orbit)), radius,
                    0.02f, 0.28f, 1.0f, alpha * t, time, -1.0);
            renderLimitlessSphere(pose, camera, redStart.add(up.scale(-Math.sin(time * 0.18) * orbit)), radius,
                    1.0f, 0.02f, 0.06f, alpha * t, time, 1.0);
            renderEnergyBridge(pose, camera, blueStart, redStart, right, up, alpha * t * 0.34f, time);
            return;
        }

        if (progress < 0.60f) {
            float t = (float) smooth((progress - 0.30f) / 0.30f);
            Vec3 blue = blueStart.lerp(mergePoint, t);
            Vec3 red = redStart.lerp(mergePoint, t);
            float sideRadius = 1.04f - 0.48f * t;

            renderLimitlessSphere(pose, camera, blue, sideRadius,
                    0.02f, 0.30f, 1.0f, alpha * (1.0f - t * 0.15f), time, -1.0);
            renderLimitlessSphere(pose, camera, red, sideRadius,
                    1.0f, 0.02f, 0.07f, alpha * (1.0f - t * 0.15f), time, 1.0);

            Vec3 midpoint = blue.lerp(red, 0.5);
            float purpleRadius = 0.10f + 0.68f * t;
            renderPurpleCore(pose, camera, midpoint, direction, purpleRadius, alpha * t, time, 0.60f + 0.40f * t);
            renderEnergyBridge(pose, camera, blue, red, right, up, alpha * (0.35f + 0.45f * t), time);
            return;
        }

        if (progress < 0.84f) {
            float t = (float) smooth((progress - 0.60f) / 0.24f);
            float radius = 0.74f + 0.76f * t;
            renderPurpleCore(pose, camera, mergePoint, direction, radius, alpha, time, 0.86f + 0.14f * t);

            for (int i = 0; i < 3; i++) {
                Vec3 n = safeDirection(direction
                        .add(right.scale((i - 1) * 0.30))
                        .add(up.scale((1 - i) * 0.22)));
                drawWarpedRingAt(pose, camera, mergePoint, n,
                        radius * (1.28f + i * 0.20f),
                        radius * (0.030f + i * 0.006f),
                        time * (2.1 + i * 0.34) + i * 27.0,
                        0.11f + i * 0.025f,
                        i == 1 ? 1.0f : 0.58f,
                        0.025f,
                        i == 1 ? 0.66f : 1.0f,
                        alpha * (0.48f + t * 0.14f));
            }
            return;
        }

        float t = (float) smooth((progress - 0.84f) / 0.16f);
        float radius = 1.50f + 0.50f * t;
        renderPurpleCore(pose, camera, mergePoint, direction, radius, alpha, time, 1.0f);
        renderChargeVortex(pose, camera, mergePoint, direction, right, up, radius, t, alpha, time);
        renderChargeLightning(pose, camera, mergePoint, direction, right, up, radius, t, alpha, time);
    }

    private static void renderLimitlessSphere(
            PoseStack pose,
            Vec3 camera,
            Vec3 position,
            float radius,
            float r,
            float g,
            float b,
            float alpha,
            double time,
            double spinSign
    ) {
        drawWarpedSphereAt(pose, camera, position, radius * 1.14f, 18, 28,
                r * 0.38f, g * 0.38f, b * 0.38f, alpha * 0.20f, time, 0.065f, 1.0);
        drawWarpedSphereAt(pose, camera, position, radius, 20, 30,
                r, g, b, alpha * 0.58f, time * 1.20, 0.050f, spinSign);
        drawWarpedSphereAt(pose, camera, position, radius * 0.50f, 16, 24,
                Math.min(1.0f, r + 0.42f), Math.min(1.0f, g + 0.42f), Math.min(1.0f, b + 0.42f),
                alpha * 0.82f, time * 1.45, 0.026f, -spinSign);

        Vec3 normal = safeDirection(new Vec3(0.25 * spinSign, 0.72, 0.64));
        drawWarpedRingAt(pose, camera, position, normal,
                radius * 1.24f, Math.max(0.025f, radius * 0.038f),
                time * 3.0 * spinSign, 0.060f,
                r, g, b, alpha * 0.60f);
    }

    private static void renderEnergyBridge(
            PoseStack pose,
            Vec3 camera,
            Vec3 from,
            Vec3 to,
            Vec3 right,
            Vec3 up,
            float alpha,
            double time
    ) {
        if (alpha <= 0.01f) return;
        for (int arc = 0; arc < 3; arc++) {
            List<Vec3> points = new ArrayList<>();
            int segments = 12;
            for (int i = 0; i <= segments; i++) {
                double t = i / (double) segments;
                double envelope = Math.sin(Math.PI * t);
                double wobbleA = Math.sin(t * Math.PI * 5.0 + time * 0.90 + arc * 2.2) * 0.18 * envelope;
                double wobbleB = Math.sin(t * Math.PI * 7.0 - time * 1.15 + arc * 1.4) * 0.11 * envelope;
                points.add(from.lerp(to, t).add(up.scale(wobbleA)).add(right.scale(wobbleB)));
            }
            drawRibbonPolyline(pose, camera, points, 0.030f,
                    arc == 1 ? 1.0f : 0.62f,
                    0.03f,
                    arc == 1 ? 0.66f : 1.0f,
                    alpha * (0.62f - arc * 0.10f));
        }
    }

    private static void renderPurpleCore(
            PoseStack pose,
            Vec3 camera,
            Vec3 position,
            Vec3 direction,
            float radius,
            float alpha,
            double time,
            float intensity
    ) {
        if (radius <= 0.02f || alpha <= 0.01f) return;
        float a = alpha * intensity;

        // Outer refractive-looking haze.
        drawWarpedSphereAt(pose, camera, position, radius * 1.18f, 22, 34,
                0.21f, 0.006f, 0.48f, a * 0.18f, time * 0.75, 0.105f, 1.0);
        // Blue-violet plasma membrane.
        drawWarpedSphereAt(pose, camera, position, radius * 1.03f, 24, 36,
                0.43f, 0.012f, 1.0f, a * 0.35f, time * 1.12, 0.080f, -1.0);
        // Magenta counter-rotating membrane.
        drawWarpedSphereAt(pose, camera, position, radius * 0.94f, 22, 34,
                1.0f, 0.018f, 0.53f, a * 0.24f, time * 1.36, 0.075f, 1.0);
        // Dark void gives the body actual depth instead of a flat glow sprite.
        drawWarpedSphereAt(pose, camera, position, radius * 0.76f, 20, 32,
                0.022f, 0.001f, 0.050f, a * 0.88f, time * 0.52, 0.035f, -1.0);
        // White-hot center seen in the reference.
        drawWarpedSphereAt(pose, camera, position, radius * 0.42f, 18, 28,
                1.0f, 0.72f, 1.0f, a * 0.94f, time * 1.80, 0.045f, 1.0);
        drawWarpedSphereAt(pose, camera, position, radius * 0.21f, 16, 24,
                1.0f, 1.0f, 1.0f, a, time * 2.05, 0.020f, -1.0);

        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();
        Vec3 n1 = safeDirection(direction.add(right.scale(0.58)).add(up.scale(0.22)));
        Vec3 n2 = safeDirection(direction.add(right.scale(-0.30)).add(up.scale(0.62)));
        Vec3 n3 = safeDirection(direction.add(right.scale(0.12)).add(up.scale(-0.74)));

        drawWarpedRingAt(pose, camera, position, n1, radius * 1.27f, radius * 0.030f,
                time * 3.7, 0.085f, 0.58f, 0.02f, 1.0f, a * 0.66f);
        drawWarpedRingAt(pose, camera, position, n2, radius * 1.39f, radius * 0.027f,
                -time * 3.1, 0.100f, 1.0f, 0.02f, 0.58f, a * 0.58f);
        drawWarpedRingAt(pose, camera, position, n3, radius * 1.49f, radius * 0.024f,
                time * 2.6 + 71.0, 0.115f, 0.80f, 0.22f, 1.0f, a * 0.48f);
    }

    private static void renderChargeVortex(
            PoseStack pose,
            Vec3 camera,
            Vec3 center,
            Vec3 direction,
            Vec3 right,
            Vec3 up,
            float radius,
            float charge,
            float alpha,
            double time
    ) {
        for (int i = 0; i < 7; i++) {
            float depth = (i - 3) * 0.30f;
            Vec3 ringCenter = center.add(direction.scale(depth));
            float ringRadius = radius * (1.36f + i * 0.16f + charge * (0.05f + i * 0.018f));
            Vec3 normal = safeDirection(direction
                    .add(right.scale(Math.sin(i * 1.4) * 0.13))
                    .add(up.scale(Math.cos(i * 1.7) * 0.13)));

            drawWarpedRingAt(pose, camera, ringCenter, normal,
                    ringRadius,
                    Math.max(0.045f, radius * (0.032f + i * 0.003f)),
                    time * (2.6 + i * 0.22) + i * 37.0,
                    0.13f + i * 0.012f,
                    i % 3 == 1 ? 1.0f : 0.55f,
                    0.025f,
                    i % 3 == 1 ? 0.68f : 1.0f,
                    alpha * charge * (0.76f - i * 0.055f));
        }

        // Thin nested white ring: the reference has a near-white spatial rim before firing.
        drawWarpedRingAt(pose, camera, center.add(direction.scale(0.10)), direction,
                radius * (1.12f + 0.08f * charge), radius * 0.026f,
                -time * 5.2, 0.075f,
                1.0f, 0.86f, 1.0f, alpha * charge * 0.86f);
    }

    private static void renderChargeLightning(
            PoseStack pose,
            Vec3 camera,
            Vec3 center,
            Vec3 direction,
            Vec3 right,
            Vec3 up,
            float radius,
            float charge,
            float alpha,
            double time
    ) {
        for (int arc = 0; arc < 5; arc++) {
            double phase = time * (0.55 + arc * 0.05) + arc * 1.7;
            Vec3 start = center
                    .add(right.scale(Math.cos(phase) * radius * 0.62))
                    .add(up.scale(Math.sin(phase) * radius * 0.62));
            Vec3 end = center
                    .add(direction.scale(radius * (1.60 + arc * 0.18)))
                    .add(right.scale(Math.cos(phase + 1.5) * radius * (1.55 + arc * 0.18)))
                    .add(up.scale(Math.sin(phase + 1.5) * radius * (1.05 + arc * 0.14)));

            drawLightningArc(pose, camera, start, end, right, up,
                    10, 0.18f + arc * 0.025f, time + arc * 3.2,
                    0.032f,
                    arc % 2 == 0 ? 0.78f : 1.0f,
                    0.04f,
                    arc % 2 == 0 ? 1.0f : 0.62f,
                    alpha * charge * (0.72f - arc * 0.08f));
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
        renderPurpleCore(pose, camera, position, direction, radius, alpha, time, 1.0f);

        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        // Tight traveling spacetime rings.
        for (int i = 0; i < 4; i++) {
            Vec3 ringCenter = position.subtract(direction.scale(0.24 + i * 0.36));
            drawWarpedRingAt(pose, camera, ringCenter, direction,
                    radius * (1.24f + i * 0.18f),
                    Math.max(0.030f, radius * (0.035f - i * 0.003f)),
                    time * (4.2 - i * 0.38) + i * 33.0,
                    0.10f + i * 0.018f,
                    i == 1 ? 1.0f : 0.57f,
                    0.025f,
                    i == 1 ? 0.64f : 1.0f,
                    alpha * (0.72f - i * 0.10f));
        }

        // Lightning lashes trail behind and sideways exactly at launch/flight.
        for (int arc = 0; arc < 4; arc++) {
            double phase = time * 0.75 + arc * 1.9;
            Vec3 start = position.subtract(direction.scale(radius * 0.25));
            Vec3 end = position
                    .subtract(direction.scale(radius * (2.3 + arc * 0.52)))
                    .add(right.scale(Math.cos(phase) * radius * (1.1 + arc * 0.20)))
                    .add(up.scale(Math.sin(phase) * radius * (0.8 + arc * 0.18)));
            drawLightningArc(pose, camera, start, end, right, up,
                    9, 0.20f, time + arc * 5.0, 0.040f,
                    arc % 2 == 0 ? 1.0f : 0.62f,
                    0.03f,
                    arc % 2 == 0 ? 0.62f : 1.0f,
                    alpha * (0.66f - arc * 0.09f));
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
        if (visual.releaseOrigin == null || visual.releaseAge >= RELEASE_BURST_TICKS) return;

        float age = visual.releaseAge;
        float life = 1.0f - age / RELEASE_BURST_TICKS;
        float expand = (float) smooth(age / RELEASE_BURST_TICKS);
        Vec3 direction = safeDirection(visual.releaseDirection);
        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        // Massive irregular tunnel-mouth rings from the reference launch frame.
        for (int i = 0; i < 8; i++) {
            Vec3 center = visual.releaseOrigin.add(direction.scale(i * 0.72 + expand * i * 0.16));
            float ringRadius = 1.50f + i * 0.72f + expand * (3.0f + i * 0.48f);
            Vec3 normal = safeDirection(direction
                    .add(right.scale(Math.sin(i * 1.23) * 0.10))
                    .add(up.scale(Math.cos(i * 1.41) * 0.10)));

            drawWarpedRingAt(pose, camera, center, normal,
                    ringRadius,
                    0.085f + i * 0.013f,
                    time * (2.9 + i * 0.18) + i * 31.0,
                    0.15f + i * 0.009f,
                    i % 3 == 1 ? 1.0f : 0.56f,
                    0.025f,
                    i % 3 == 1 ? 0.72f : 1.0f,
                    alpha * life * (0.92f - i * 0.075f));
        }

        drawWarpedRingAt(pose, camera, visual.releaseOrigin.add(direction.scale(0.25)), direction,
                1.0f + expand * 2.9f, 0.075f,
                -time * 6.0, 0.080f,
                1.0f, 0.92f, 1.0f, alpha * life * 0.95f);

        // Radial lightning tears across the spatial aperture.
        for (int arc = 0; arc < 8; arc++) {
            double a = Math.PI * 2.0 * arc / 8.0 + time * 0.10;
            Vec3 start = visual.releaseOrigin.add(direction.scale(0.20));
            Vec3 end = visual.releaseOrigin
                    .add(direction.scale(1.0 + expand * 2.2))
                    .add(right.scale(Math.cos(a) * (4.0 + expand * 4.2)))
                    .add(up.scale(Math.sin(a) * (3.2 + expand * 3.7)));
            drawLightningArc(pose, camera, start, end, right, up,
                    12, 0.32f, time + arc * 4.1,
                    0.045f,
                    arc % 3 == 0 ? 1.0f : 0.67f,
                    0.04f,
                    arc % 3 == 0 ? 0.68f : 1.0f,
                    alpha * life * (0.72f - arc * 0.035f));
        }
    }

    private static void renderResidualTunnel(
            PoseStack pose,
            Vec3 camera,
            VisualState visual,
            long now,
            float partial,
            double time
    ) {
        if (visual.trail.isEmpty()) return;

        int index = 0;
        for (TrailNode node : visual.trail) {
            long ageTicks = now - node.born;
            if (ageTicks < 0 || ageTicks >= TRAIL_LIFETIME_TICKS) {
                index++;
                continue;
            }

            // Sparse geometry keeps a five-second residual wound without flooding draw calls.
            if ((index & 1) != 0) {
                index++;
                continue;
            }

            float age = Mth.clamp((ageTicks + partial) / (float) TRAIL_LIFETIME_TICKS, 0.0f, 1.0f);
            float fade = 1.0f - age;
            float radius = Math.max(0.16f, node.radius * (1.08f + age * 0.12f));

            drawWarpedRingAt(pose, camera, node.position, node.direction,
                    radius * (1.05f + age * 0.18f),
                    Math.max(0.018f, radius * 0.026f),
                    time * 1.5 + index * 18.0,
                    0.12f,
                    0.48f, 0.012f, 1.0f,
                    fade * 0.34f);

            if ((index % 4) == 0) {
                drawWarpedSphereAt(pose, camera, node.position,
                        radius * 0.72f, 12, 18,
                        0.035f, 0.001f, 0.070f,
                        fade * 0.18f,
                        time * 0.45 + index,
                        0.11f,
                        index % 3 == 0 ? 1.0 : -1.0);
            }
            index++;
        }
    }

    private static void drawLightningArc(
            PoseStack pose,
            Vec3 camera,
            Vec3 start,
            Vec3 end,
            Vec3 right,
            Vec3 up,
            int segments,
            float jitter,
            double seed,
            float width,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (alpha <= 0.01f) return;

        List<Vec3> points = new ArrayList<>();
        Vec3 delta = end.subtract(start);

        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            double envelope = Math.sin(Math.PI * t);
            double n1 = Math.sin(seed * 1.71 + i * 2.73) * jitter * envelope;
            double n2 = Math.sin(seed * 2.31 + i * 4.17 + 1.2) * jitter * envelope;
            points.add(start.add(delta.scale(t)).add(right.scale(n1)).add(up.scale(n2)));
        }

        drawRibbonPolyline(pose, camera, points, width, red, green, blue, alpha);
        drawRibbonPolyline(pose, camera, points, width * 0.36f, 1.0f, 0.82f, 1.0f, alpha * 0.90f);
    }

    private static void drawRibbonPolyline(
            PoseStack pose,
            Vec3 camera,
            List<Vec3> points,
            float width,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (points.size() < 2 || alpha <= 0.0f) return;

        pose.pushPose();
        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(i + 1);
            Vec3 segment = b.subtract(a);
            if (segment.lengthSqr() < 1.0E-8) continue;

            Vec3 mid = a.lerp(b, 0.5);
            Vec3 view = camera.subtract(mid);
            Vec3 side = segment.cross(view);
            if (side.lengthSqr() < 1.0E-8) side = rightFor(segment);
            side = side.normalize().scale(width);

            Vec3 a0 = a.add(side).subtract(camera);
            Vec3 a1 = a.subtract(side).subtract(camera);
            Vec3 b0 = b.add(side).subtract(camera);
            Vec3 b1 = b.subtract(side).subtract(camera);

            vertex(buffer, matrix, a0, red, green, blue, alpha);
            vertex(buffer, matrix, b0, red, green, blue, alpha);
            vertex(buffer, matrix, b1, red, green, blue, alpha);

            vertex(buffer, matrix, a0, red, green, blue, alpha);
            vertex(buffer, matrix, b1, red, green, blue, alpha);
            vertex(buffer, matrix, a1, red, green, blue, alpha);
        }

        BufferUploader.drawWithShader(buffer.end());
        pose.popPose();
    }

    private static void drawWarpedSphereAt(
            PoseStack pose,
            Vec3 camera,
            Vec3 worldPosition,
            float radius,
            int latSegments,
            int lonSegments,
            float red,
            float green,
            float blue,
            float alpha,
            double time,
            float warp,
            double spinSign
    ) {
        if (radius <= 0.0f || alpha <= 0.0f) return;

        pose.pushPose();
        pose.translate(worldPosition.x - camera.x, worldPosition.y - camera.y, worldPosition.z - camera.z);

        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int lat = 0; lat < latSegments; lat++) {
            double v0 = lat / (double) latSegments;
            double v1 = (lat + 1) / (double) latSegments;
            double phi0 = -Math.PI * 0.5 + Math.PI * v0;
            double phi1 = -Math.PI * 0.5 + Math.PI * v1;

            for (int lon = 0; lon < lonSegments; lon++) {
                double u0 = lon / (double) lonSegments;
                double u1 = (lon + 1) / (double) lonSegments;
                double theta0 = Math.PI * 2.0 * u0;
                double theta1 = Math.PI * 2.0 * u1;

                Vec3 p00 = warpedSpherePoint(radius, phi0, theta0, time, warp, spinSign);
                Vec3 p01 = warpedSpherePoint(radius, phi0, theta1, time, warp, spinSign);
                Vec3 p10 = warpedSpherePoint(radius, phi1, theta0, time, warp, spinSign);
                Vec3 p11 = warpedSpherePoint(radius, phi1, theta1, time, warp, spinSign);

                float c00 = surfaceBrightness(phi0, theta0, time, spinSign);
                float c01 = surfaceBrightness(phi0, theta1, time, spinSign);
                float c10 = surfaceBrightness(phi1, theta0, time, spinSign);
                float c11 = surfaceBrightness(phi1, theta1, time, spinSign);

                vertexLit(buffer, matrix, p00, red, green, blue, alpha, c00);
                vertexLit(buffer, matrix, p10, red, green, blue, alpha, c10);
                vertexLit(buffer, matrix, p11, red, green, blue, alpha, c11);

                vertexLit(buffer, matrix, p00, red, green, blue, alpha, c00);
                vertexLit(buffer, matrix, p11, red, green, blue, alpha, c11);
                vertexLit(buffer, matrix, p01, red, green, blue, alpha, c01);
            }
        }

        BufferUploader.drawWithShader(buffer.end());
        pose.popPose();
    }

    private static Vec3 warpedSpherePoint(
            float baseRadius,
            double phi,
            double theta,
            double time,
            float warp,
            double spinSign
    ) {
        double wave =
                Math.sin(theta * 5.0 + time * 0.72 * spinSign + phi * 2.0) * 0.46 +
                Math.sin(theta * 9.0 - time * 0.49 * spinSign - phi * 5.0) * 0.31 +
                Math.sin(phi * 11.0 + time * 0.61) * 0.23;
        double r = baseRadius * (1.0 + warp * wave);
        double cp = Math.cos(phi);
        return new Vec3(
                Math.cos(theta) * cp * r,
                Math.sin(phi) * r,
                Math.sin(theta) * cp * r
        );
    }

    private static float surfaceBrightness(double phi, double theta, double time, double spinSign) {
        double v = 0.82 +
                0.13 * Math.sin(theta * 4.0 + time * 0.42 * spinSign) +
                0.05 * Math.sin(phi * 8.0 - time * 0.55);
        return (float) Mth.clamp(v, 0.62, 1.0);
    }

    private static void drawWarpedRingAt(
            PoseStack pose,
            Vec3 camera,
            Vec3 worldPosition,
            Vec3 normal,
            float radius,
            float thickness,
            double rotationDegrees,
            float irregularity,
            float red,
            float green,
            float blue,
            float alpha
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

        int segments = 72;
        double rotation = Math.toRadians(rotationDegrees);
        float half = Math.max(0.006f, thickness);

        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments + rotation;
            double a1 = Math.PI * 2.0 * (i + 1) / segments + rotation;

            double wobble0 = 1.0 + irregularity * (
                    Math.sin(a0 * 5.0 + rotation * 0.30) * 0.62 +
                    Math.sin(a0 * 11.0 - rotation * 0.17) * 0.38);
            double wobble1 = 1.0 + irregularity * (
                    Math.sin(a1 * 5.0 + rotation * 0.30) * 0.62 +
                    Math.sin(a1 * 11.0 - rotation * 0.17) * 0.38);

            Vec3 q0 = ringPoint(right, up, a0, radius * wobble0 + half);
            Vec3 q1 = ringPoint(right, up, a1, radius * wobble1 + half);
            Vec3 r0 = ringPoint(right, up, a0, Math.max(0.0, radius * wobble0 - half));
            Vec3 r1 = ringPoint(right, up, a1, Math.max(0.0, radius * wobble1 - half));

            vertex(buffer, matrix, q0, red, green, blue, alpha);
            vertex(buffer, matrix, q1, red, green, blue, alpha);
            vertex(buffer, matrix, r1, red, green, blue, alpha);

            vertex(buffer, matrix, q0, red, green, blue, alpha);
            vertex(buffer, matrix, r1, red, green, blue, alpha);
            vertex(buffer, matrix, r0, red, green, blue, alpha);
        }

        BufferUploader.drawWithShader(buffer.end());
        pose.popPose();
    }

    private static Vec3 ringPoint(Vec3 right, Vec3 up, double angle, double radius) {
        return right.scale(Math.cos(angle) * radius).add(up.scale(Math.sin(angle) * radius));
    }

    private static Vec3 rightFor(Vec3 direction) {
        Vec3 n = safeDirection(direction);
        Vec3 reference = Math.abs(n.y) > 0.92
                ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = reference.cross(n);
        if (right.lengthSqr() < 1.0E-8) right = new Vec3(1.0, 0.0, 0.0);
        return right.normalize();
    }

    private static Vec3 safeDirection(Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 1.0E-8) return new Vec3(0.0, 0.0, 1.0);
        return direction.normalize();
    }

    private static double smooth(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static void vertexLit(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3 point,
            float red,
            float green,
            float blue,
            float alpha,
            float brightness
    ) {
        vertex(buffer, matrix, point,
                Mth.clamp(red * brightness, 0.0f, 1.0f),
                Mth.clamp(green * brightness, 0.0f, 1.0f),
                Mth.clamp(blue * brightness, 0.0f, 1.0f),
                alpha);
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
        buffer.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .color(red, green, blue, alpha)
                .endVertex();
    }
}
