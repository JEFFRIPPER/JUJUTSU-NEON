package com.kira.jujutsuneon;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reference-driven Maximum Blue renderer.
 *
 * Hard rule: the technique body is procedural 3D geometry. No billboard/PNG is
 * used as the core or shell. The reference's orange character outline is
 * intentionally NOT reproduced because it belongs to that game's UI/combat
 * highlighting, not to Maximum Blue itself.
 */
@Mod.EventBusSubscriber(
        modid = JujutsuNeonMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class MaximumBlueReferenceClient {
    private static final int PHASE_NONE = 0;
    private static final int PHASE_FORMING = 1;
    private static final int PHASE_ACTIVE = 2;
    private static final int PHASE_FADING = 3;

    private static final int FORM_TICKS = 40;
    private static final int MAX_VISUALS = 8;
    private static final int MAX_DEBRIS = 1400;

    private static final Map<UUID, BlueVisual> VISUALS = new LinkedHashMap<>();
    private static final ArrayDeque<DebrisVisual> DEBRIS = new ArrayDeque<>();

    private MaximumBlueReferenceClient() {}

    private static final class BlueVisual {
        final UUID ownerId;
        Vec3 previousPos;
        Vec3 currentPos;
        Vec3 targetPos;
        float previousRadius;
        float currentRadius;
        float targetRadius;
        float previousAlpha;
        float currentAlpha;
        float targetAlpha;
        int phase;
        int previousPhase;
        int phaseAge;
        int staleTicks;

        BlueVisual(UUID ownerId, Vec3 pos, float radius, int phase) {
            this.ownerId = ownerId;
            this.previousPos = pos;
            this.currentPos = pos;
            this.targetPos = pos;
            this.previousRadius = radius;
            this.currentRadius = radius;
            this.targetRadius = radius;
            this.previousAlpha = 0.0f;
            this.currentAlpha = 0.0f;
            this.targetAlpha = 1.0f;
            this.phase = phase;
            this.previousPhase = phase;
        }
    }

    private static final class DebrisVisual {
        final UUID ownerId;
        final Vec3 start;
        final Vec3 fallbackTarget;
        final BlockState state;
        final double seed;
        final int lifetime;
        int age;

        DebrisVisual(UUID ownerId, Vec3 start, Vec3 fallbackTarget, BlockState state, double seed, int lifetime) {
            this.ownerId = ownerId;
            this.start = start;
            this.fallbackTarget = fallbackTarget;
            this.state = state;
            this.seed = seed;
            this.lifetime = lifetime;
        }
    }

    public static void accept(
            UUID ownerId,
            boolean active,
            double x,
            double y,
            double z,
            float radius,
            int phase
    ) {
        Vec3 pos = new Vec3(x, y, z);
        BlueVisual visual = VISUALS.get(ownerId);

        if (!active) {
            if (visual != null) {
                visual.targetPos = pos;
                visual.targetRadius = 0.0f;
                visual.targetAlpha = 0.0f;
                visual.previousPhase = visual.phase;
                visual.phase = PHASE_FADING;
                visual.phaseAge = 0;
                visual.staleTicks = 0;
            }
            return;
        }

        if (visual == null) {
            if (VISUALS.size() >= MAX_VISUALS) {
                UUID first = VISUALS.keySet().iterator().next();
                VISUALS.remove(first);
            }
            visual = new BlueVisual(ownerId, pos, radius, phase);
            VISUALS.put(ownerId, visual);
        }

        if (visual.phase != phase) {
            visual.previousPhase = visual.phase;
            visual.phase = phase;
            visual.phaseAge = 0;
        }

        visual.targetPos = pos;
        visual.targetRadius = Math.max(0.0f, radius);
        visual.targetAlpha = 1.0f;
        visual.staleTicks = 0;
    }

    public static void acceptDebrisBatch(
            UUID ownerId,
            double targetX,
            double targetY,
            double targetZ,
            List<Long> packedPositions,
            List<Integer> stateIds
    ) {
        Vec3 fallbackTarget = new Vec3(targetX, targetY, targetZ);
        int count = Math.min(packedPositions.size(), stateIds.size());

        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(packedPositions.get(i));
            BlockState state = Block.stateById(stateIds.get(i));
            if (state == null || state.isAir()) continue;

            long packed = packedPositions.get(i);
            double seed = hashUnit(packed ^ ownerId.getLeastSignificantBits());
            int lifetime = 8 + (int) Math.floor(seed * 8.0);
            DEBRIS.addLast(new DebrisVisual(
                    ownerId,
                    Vec3.atCenterOf(pos),
                    fallbackTarget,
                    state,
                    seed,
                    lifetime
            ));
        }

        while (DEBRIS.size() > MAX_DEBRIS) DEBRIS.removeFirst();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            VISUALS.clear();
            DEBRIS.clear();
            return;
        }

        Iterator<Map.Entry<UUID, BlueVisual>> iterator = VISUALS.entrySet().iterator();
        while (iterator.hasNext()) {
            BlueVisual visual = iterator.next().getValue();

            visual.previousPos = visual.currentPos;
            visual.previousRadius = visual.currentRadius;
            visual.previousAlpha = visual.currentAlpha;

            visual.currentPos = visual.currentPos.lerp(visual.targetPos, 0.82);
            visual.currentRadius += (visual.targetRadius - visual.currentRadius) * 0.72f;
            visual.currentAlpha += (visual.targetAlpha - visual.currentAlpha) * 0.42f;
            visual.phaseAge++;
            visual.staleTicks++;

            if (visual.staleTicks > 14) {
                visual.targetAlpha = 0.0f;
                visual.targetRadius = 0.0f;
            }

            if (visual.targetAlpha <= 0.001f && visual.currentAlpha <= 0.012f) {
                iterator.remove();
            }
        }

        Iterator<DebrisVisual> debrisIterator = DEBRIS.iterator();
        while (debrisIterator.hasNext()) {
            DebrisVisual debris = debrisIterator.next();
            debris.age++;
            if (debris.age > debris.lifetime) debrisIterator.remove();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || VISUALS.isEmpty()) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float dark = 0.0f;
        float blueWash = 0.0f;
        float flash = 0.0f;

        for (BlueVisual visual : VISUALS.values()) {
            float dist = (float) camera.distanceTo(visual.currentPos);
            float proximity = 1.0f - Mth.clamp(dist / 72.0f, 0.0f, 1.0f);
            if (proximity <= 0.0f) continue;

            float formation = visual.phase == PHASE_FORMING
                    ? Mth.clamp(visual.phaseAge / (float) FORM_TICKS, 0.0f, 1.0f)
                    : 1.0f;
            float burst = (float) smooth((formation - 0.22f) / 0.22f);
            float settled = (float) smooth((formation - 0.46f) / 0.40f);

            dark = Math.max(dark, (0.06f * burst + 0.27f * settled) * proximity * visual.currentAlpha);
            blueWash = Math.max(blueWash, (0.04f + 0.10f * settled) * proximity * visual.currentAlpha);

            if (visual.phase == PHASE_FORMING && visual.phaseAge >= 8 && visual.phaseAge <= 13) {
                float local = 1.0f - Math.abs(10.5f - visual.phaseAge) / 3.0f;
                flash = Math.max(flash, Math.max(0.0f, local) * 0.22f * proximity);
            }
        }

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        if (dark > 0.004f) {
            int a = Mth.clamp((int) (dark * 255.0f), 0, 82);
            event.getGuiGraphics().fill(0, 0, w, h, a << 24);
        }
        if (blueWash > 0.004f) {
            int a = Mth.clamp((int) (blueWash * 255.0f), 0, 40);
            event.getGuiGraphics().fill(0, 0, w, h, (a << 24) | 0x00105CFF);
        }
        if (flash > 0.004f) {
            int a = Mth.clamp((int) (flash * 255.0f), 0, 56);
            event.getGuiGraphics().fill(0, 0, w, h, (a << 24) | 0x00C8F7FF);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || VISUALS.isEmpty()) return;

        Vec3 camera = event.getCamera().getPosition();
        double time = mc.level.getGameTime() + mc.getFrameTime();
        float amplitude = 0.0f;

        for (BlueVisual visual : VISUALS.values()) {
            float proximity = 1.0f - Mth.clamp((float) camera.distanceTo(visual.currentPos) / 34.0f, 0.0f, 1.0f);
            if (proximity <= 0.0f) continue;

            float a = 0.0f;
            if (visual.phase == PHASE_FORMING) {
                float p = Mth.clamp(visual.phaseAge / (float) FORM_TICKS, 0.0f, 1.0f);
                float burst = (float) smooth((p - 0.20f) / 0.25f);
                a = burst * 0.32f;
            } else if (visual.phase == PHASE_ACTIVE) {
                a = 0.12f;
            }
            amplitude = Math.max(amplitude, a * proximity * visual.currentAlpha);
        }

        if (amplitude <= 0.001f) return;
        event.setYaw(event.getYaw() + (float) Math.sin(time * 6.71) * amplitude * 0.34f);
        event.setPitch(event.getPitch() + (float) Math.sin(time * 8.93 + 1.2) * amplitude * 0.28f);
        event.setRoll(event.getRoll() + (float) Math.cos(time * 7.47 + 0.7) * amplitude * 0.22f);
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (VISUALS.isEmpty() && DEBRIS.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float partial = event.getPartialTick();
        double time = mc.level.getGameTime() + partial;

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (BlueVisual visual : VISUALS.values()) {
            renderBlue(pose, camera, visual, partial, time);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        renderDebris(mc, pose, camera, partial, time);
    }

    private static void renderBlue(PoseStack pose, Vec3 camera, BlueVisual visual, float partial, double time) {
        float alpha = Mth.lerp(partial, visual.previousAlpha, visual.currentAlpha);
        float radius = Mth.lerp(partial, visual.previousRadius, visual.currentRadius);
        Vec3 pos = visual.previousPos.lerp(visual.currentPos, partial);
        if (alpha <= 0.01f || radius <= 0.025f) return;

        float formation = visual.phase == PHASE_FORMING
                ? Mth.clamp((visual.phaseAge + partial) / (float) FORM_TICKS, 0.0f, 1.0f)
                : 1.0f;

        float seedStage = (float) smooth(formation / 0.18f);
        float eruption = (float) smooth((formation - 0.18f) / 0.28f);
        float bodyStage = (float) smooth((formation - 0.32f) / 0.48f);
        float chaos = 0.55f + 0.45f * bodyStage;
        float pulse = 1.0f + 0.035f * (float) Math.sin(time * 0.42) + 0.016f * (float) Math.sin(time * 1.17);

        pose.pushPose();
        pose.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);

        alphaBlend();
        drawDeformedSphere(pose, radius * 0.34f * pulse, 18, 28, time, 0.035f,
                0.93f, 1.00f, 1.00f, Math.min(1.0f, alpha * (0.92f + seedStage * 0.08f)), 2.1);
        drawDeformedSphere(pose, radius * 0.49f * pulse, 20, 30, time, 0.075f,
                0.30f, 0.82f, 1.00f, alpha * 0.82f * Math.max(0.28f, seedStage), 2.8);

        drawDeformedSphere(pose, radius * 0.72f * pulse, 22, 34, time, 0.12f * chaos,
                0.015f, 0.19f, 0.82f, alpha * 0.78f * Math.max(0.20f, eruption), 3.6);

        additiveBlend();
        drawPatchyShell(pose, radius * 0.91f * pulse, 24, 38, time, 0.16f,
                0.02f, 0.40f, 1.00f, alpha * 0.92f * eruption, 0.15f, 4.7);
        drawPatchyShell(pose, radius * 1.03f * pulse, 22, 36, time + 7.3, 0.22f,
                0.05f, 0.79f, 1.00f, alpha * 0.64f * bodyStage, 0.28f, 5.6);
        drawPatchyShell(pose, radius * 1.12f * pulse, 18, 32, time + 13.9, 0.27f,
                0.18f, 0.92f, 1.00f, alpha * 0.34f * bodyStage, 0.48f, 6.9);

        if (eruption > 0.02f) {
            int ribbons = bodyStage > 0.55f ? 7 : 4;
            for (int i = 0; i < ribbons; i++) {
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees((float) (i * 53.0 + time * (2.3 + i * 0.17))));
                pose.mulPose(Axis.XP.rotationDegrees((float) (18.0 + i * 21.0 + Math.sin(time * 0.11 + i) * 17.0)));
                pose.mulPose(Axis.ZP.rotationDegrees((float) (i * 31.0 - time * 1.37)));
                drawBrokenRibbon(pose,
                        radius * (1.08f + 0.055f * (i % 3)),
                        radius * (0.055f + 0.012f * (i % 2)),
                        time + i * 9.7,
                        i,
                        0.04f + 0.04f * (i % 2),
                        0.45f + 0.08f * (i % 3),
                        1.00f,
                        alpha * eruption * (0.62f - i * 0.035f));
                pose.popPose();
            }
        }

        if (visual.phase == PHASE_FORMING && formation > 0.16f && formation < 0.56f) {
            float birth = (float) Math.sin(Mth.clamp((formation - 0.16f) / 0.40f, 0.0f, 1.0f) * Math.PI);
            drawRadialBurst(pose, radius * (1.15f + birth * 0.70f), time, alpha * birth);
        }

        if (bodyStage > 0.10f) {
            int arcs = bodyStage > 0.70f ? 11 : 6;
            for (int i = 0; i < arcs; i++) {
                Vec3 a = pointOnSphere(radius * (0.68 + 0.08 * (i % 3)), i * 1.91 + time * 0.055, i * 0.77 + 0.8);
                Vec3 b = pointOnSphere(radius * (1.18 + 0.11 * ((i + 1) % 3)), i * 2.37 - time * 0.073, i * 1.13 + 1.4);
                drawLightning(pose, a, b, radius * 0.018f, time, i,
                        0.20f, 0.86f, 1.00f, alpha * bodyStage * 0.72f);
            }
        }

        pose.popPose();
        alphaBlend();
    }

    private static void renderDebris(Minecraft mc, PoseStack pose, Vec3 camera, float partial, double time) {
        if (DEBRIS.isEmpty()) return;

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int rendered = 0;
        for (DebrisVisual debris : DEBRIS) {
            if (rendered >= 420) break;

            BlueVisual owner = VISUALS.get(debris.ownerId);
            Vec3 target = owner != null ? owner.currentPos : debris.fallbackTarget;
            float p = Mth.clamp((debris.age + partial) / (float) Math.max(1, debris.lifetime), 0.0f, 1.0f);
            float e = p * p * (3.0f - 2.0f * p);

            Vec3 line = target.subtract(debris.start);
            Vec3 dir = line.lengthSqr() > 1.0e-6 ? line.normalize() : new Vec3(0.0, 1.0, 0.0);
            Vec3 right = rightFor(dir);
            Vec3 up = right.cross(dir).normalize();

            double initialOrbit = 0.25 + debris.seed * 1.25;
            double orbit = initialOrbit * (1.0 - e) * (1.0 - e);
            double angle = debris.seed * Math.PI * 2.0 + p * Math.PI * (3.0 + debris.seed * 3.0) + time * 0.025;
            Vec3 offset = right.scale(Math.cos(angle) * orbit)
                    .add(up.scale(Math.sin(angle) * orbit * 0.78));
            Vec3 at = debris.start.lerp(target, e).add(offset);

            float shrink = 1.0f - (float) smooth((p - 0.64f) / 0.36f) * 0.88f;
            float scale = Mth.clamp(0.68f * shrink, 0.07f, 0.68f);

            pose.pushPose();
            pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            pose.mulPose(Axis.YP.rotationDegrees((float) (debris.seed * 360.0 + (debris.age + partial) * (16.0 + debris.seed * 22.0))));
            pose.mulPose(Axis.XP.rotationDegrees((float) ((debris.age + partial) * (11.0 + debris.seed * 17.0))));
            pose.scale(scale, scale, scale);
            pose.translate(-0.5, -0.5, -0.5);
            mc.getBlockRenderer().renderSingleBlock(
                    debris.state,
                    pose,
                    buffers,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY
            );
            pose.popPose();
            rendered++;
        }
        buffers.endBatch();
    }

    private static void drawDeformedSphere(
            PoseStack pose,
            float radius,
            int latSegments,
            int lonSegments,
            double time,
            float deformation,
            float red, float green, float blue, float alpha,
            double frequency
    ) {
        if (radius <= 0.0f || alpha <= 0.0f) return;
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

                Vec3 p00 = spherePoint(radius, phi0, theta0, deformedScale(phi0, theta0, time, deformation, frequency));
                Vec3 p10 = spherePoint(radius, phi1, theta0, deformedScale(phi1, theta0, time, deformation, frequency));
                Vec3 p11 = spherePoint(radius, phi1, theta1, deformedScale(phi1, theta1, time, deformation, frequency));
                Vec3 p01 = spherePoint(radius, phi0, theta1, deformedScale(phi0, theta1, time, deformation, frequency));

                vertex(buffer, matrix, p00, red, green, blue, alpha);
                vertex(buffer, matrix, p10, red, green, blue, alpha);
                vertex(buffer, matrix, p11, red, green, blue, alpha);
                vertex(buffer, matrix, p00, red, green, blue, alpha);
                vertex(buffer, matrix, p11, red, green, blue, alpha);
                vertex(buffer, matrix, p01, red, green, blue, alpha);
            }
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void drawPatchyShell(
            PoseStack pose,
            float radius,
            int latSegments,
            int lonSegments,
            double time,
            float deformation,
            float red, float green, float blue, float alpha,
            float threshold,
            double frequency
    ) {
        if (radius <= 0.0f || alpha <= 0.0f) return;
        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int lat = 0; lat < latSegments; lat++) {
            double phi0 = -Math.PI * 0.5 + Math.PI * lat / latSegments;
            double phi1 = -Math.PI * 0.5 + Math.PI * (lat + 1) / latSegments;
            for (int lon = 0; lon < lonSegments; lon++) {
                double theta0 = Math.PI * 2.0 * lon / lonSegments;
                double theta1 = Math.PI * 2.0 * (lon + 1) / lonSegments;
                double samplePhi = (phi0 + phi1) * 0.5;
                double sampleTheta = (theta0 + theta1) * 0.5;
                double patch = plasmaNoise(samplePhi, sampleTheta, time, frequency);
                if (patch < threshold) continue;

                float localAlpha = alpha * (float) Mth.clamp((patch - threshold) / Math.max(0.08, 1.0 - threshold) + 0.28, 0.20, 1.0);
                Vec3 p00 = spherePoint(radius, phi0, theta0, deformedScale(phi0, theta0, time, deformation, frequency));
                Vec3 p10 = spherePoint(radius, phi1, theta0, deformedScale(phi1, theta0, time, deformation, frequency));
                Vec3 p11 = spherePoint(radius, phi1, theta1, deformedScale(phi1, theta1, time, deformation, frequency));
                Vec3 p01 = spherePoint(radius, phi0, theta1, deformedScale(phi0, theta1, time, deformation, frequency));

                vertex(buffer, matrix, p00, red, green, blue, localAlpha);
                vertex(buffer, matrix, p10, red, green, blue, localAlpha);
                vertex(buffer, matrix, p11, red, green, blue, localAlpha);
                vertex(buffer, matrix, p00, red, green, blue, localAlpha);
                vertex(buffer, matrix, p11, red, green, blue, localAlpha);
                vertex(buffer, matrix, p01, red, green, blue, localAlpha);
            }
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void drawBrokenRibbon(
            PoseStack pose,
            float radius,
            float halfWidth,
            double time,
            int index,
            float red, float green, float blue, float alpha
    ) {
        if (alpha <= 0.0f) return;
        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int segments = 88;

        for (int i = 0; i < segments; i++) {
            double t0 = i / (double) segments;
            double t1 = (i + 1) / (double) segments;
            double a0 = t0 * Math.PI * 2.0;
            double a1 = t1 * Math.PI * 2.0;
            double gate = Math.sin(a0 * (2.0 + index % 3) + time * 0.21 + index * 1.7)
                    + 0.55 * Math.sin(a0 * 7.0 - time * 0.13 + index);
            if (gate < -0.62) continue;

            double wobble0 = 1.0 + 0.10 * Math.sin(a0 * 5.0 + time * 0.16 + index)
                    + 0.045 * Math.sin(a0 * 11.0 - time * 0.29);
            double wobble1 = 1.0 + 0.10 * Math.sin(a1 * 5.0 + time * 0.16 + index)
                    + 0.045 * Math.sin(a1 * 11.0 - time * 0.29);

            Vec3 c0 = new Vec3(Math.cos(a0) * radius * wobble0,
                    Math.sin(a0 * 2.0 + index) * radius * 0.12,
                    Math.sin(a0) * radius * wobble0);
            Vec3 c1 = new Vec3(Math.cos(a1) * radius * wobble1,
                    Math.sin(a1 * 2.0 + index) * radius * 0.12,
                    Math.sin(a1) * radius * wobble1);

            Vec3 n0 = new Vec3(c0.x, 0.0, c0.z).normalize().scale(halfWidth * (0.65 + 0.35 * Math.sin(a0 * 3.0 + time * 0.2) * 0.5 + 0.5));
            Vec3 n1 = new Vec3(c1.x, 0.0, c1.z).normalize().scale(halfWidth * (0.65 + 0.35 * Math.sin(a1 * 3.0 + time * 0.2) * 0.5 + 0.5));

            Vec3 q0 = c0.add(n0);
            Vec3 r0 = c0.subtract(n0);
            Vec3 q1 = c1.add(n1);
            Vec3 r1 = c1.subtract(n1);

            vertex(buffer, matrix, q0, red, green, blue, alpha);
            vertex(buffer, matrix, q1, red, green, blue, alpha);
            vertex(buffer, matrix, r1, red, green, blue, alpha);
            vertex(buffer, matrix, q0, red, green, blue, alpha);
            vertex(buffer, matrix, r1, red, green, blue, alpha);
            vertex(buffer, matrix, r0, red, green, blue, alpha);
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void drawLightning(
            PoseStack pose,
            Vec3 start,
            Vec3 end,
            float width,
            double time,
            int seed,
            float red, float green, float blue, float alpha
    ) {
        int segments = 9;
        Vec3 previous = start;
        for (int i = 1; i <= segments; i++) {
            double p = i / (double) segments;
            Vec3 base = start.lerp(end, p);
            double envelope = Math.sin(Math.PI * p);
            double j = width * 7.0 * envelope;
            Vec3 jitter = new Vec3(
                    Math.sin(time * 0.91 + seed * 7.1 + i * 2.17) * j,
                    Math.cos(time * 1.07 + seed * 3.7 + i * 1.73) * j,
                    Math.sin(time * 1.31 + seed * 5.9 + i * 2.83) * j
            );
            Vec3 next = i == segments ? end : base.add(jitter);
            drawTubeSegment(pose, previous, next, width * (1.0f - (float) p * 0.38f), 5, red, green, blue, alpha);
            previous = next;
        }
    }

    private static void drawTubeSegment(
            PoseStack pose,
            Vec3 a,
            Vec3 b,
            float radius,
            int sides,
            float red, float green, float blue, float alpha
    ) {
        Vec3 axis = b.subtract(a);
        if (axis.lengthSqr() < 1.0e-8) return;
        Vec3 dir = axis.normalize();
        Vec3 u = rightFor(dir);
        Vec3 v = dir.cross(u).normalize();

        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < sides; i++) {
            double a0 = Math.PI * 2.0 * i / sides;
            double a1 = Math.PI * 2.0 * (i + 1) / sides;
            Vec3 o0 = u.scale(Math.cos(a0) * radius).add(v.scale(Math.sin(a0) * radius));
            Vec3 o1 = u.scale(Math.cos(a1) * radius).add(v.scale(Math.sin(a1) * radius));
            Vec3 p0 = a.add(o0);
            Vec3 p1 = a.add(o1);
            Vec3 q0 = b.add(o0);
            Vec3 q1 = b.add(o1);
            vertex(buffer, matrix, p0, red, green, blue, alpha);
            vertex(buffer, matrix, q0, red, green, blue, alpha);
            vertex(buffer, matrix, q1, red, green, blue, alpha);
            vertex(buffer, matrix, p0, red, green, blue, alpha);
            vertex(buffer, matrix, q1, red, green, blue, alpha);
            vertex(buffer, matrix, p1, red, green, blue, alpha);
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void drawRadialBurst(PoseStack pose, float radius, double time, float alpha) {
        int rays = 18;
        for (int i = 0; i < rays; i++) {
            Vec3 dir = pointOnSphere(1.0, i * 2.399 + time * 0.03, i * 1.173 + 0.5).normalize();
            Vec3 a = dir.scale(radius * 0.42);
            Vec3 b = dir.scale(radius * (0.88 + 0.16 * Math.sin(time * 0.4 + i)));
            drawTubeSegment(pose, a, b, radius * 0.008f, 4,
                    0.44f, 0.95f, 1.00f, alpha * 0.58f);
        }
    }

    private static Vec3 spherePoint(float radius, double phi, double theta, double scale) {
        double c = Math.cos(phi);
        return new Vec3(
                Math.cos(theta) * c * radius * scale,
                Math.sin(phi) * radius * scale,
                Math.sin(theta) * c * radius * scale
        );
    }

    private static double deformedScale(double phi, double theta, double time, float amount, double frequency) {
        if (amount <= 0.0f) return 1.0;
        double n = Math.sin(theta * frequency + time * 0.17 + Math.sin(phi * 3.0) * 1.7)
                * 0.55
                + Math.sin(phi * (frequency + 1.7) - time * 0.23 + theta * 1.9) * 0.30
                + Math.cos((theta + phi) * (frequency * 0.63) + time * 0.31) * 0.15;
        return 1.0 + n * amount;
    }

    private static double plasmaNoise(double phi, double theta, double time, double frequency) {
        return Math.sin(theta * frequency + time * 0.19 + Math.sin(phi * 4.0) * 2.0) * 0.52
                + Math.sin(phi * (frequency * 0.82) - time * 0.27 + theta * 2.1) * 0.31
                + Math.cos((theta - phi) * 3.7 + time * 0.41) * 0.17;
    }

    private static Vec3 pointOnSphere(double radius, double a, double b) {
        double y = Math.sin(b) * radius;
        double r = Math.cos(b) * radius;
        return new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    private static Vec3 rightFor(Vec3 direction) {
        Vec3 d = direction.lengthSqr() > 1.0e-8 ? direction.normalize() : new Vec3(0.0, 0.0, 1.0);
        Vec3 up = Math.abs(d.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = d.cross(up);
        if (right.lengthSqr() < 1.0e-8) right = new Vec3(1.0, 0.0, 0.0);
        return right.normalize();
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, Vec3 p,
                               float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color(red, green, blue, alpha)
                .endVertex();
    }

    private static double smooth(double x) {
        double t = Mth.clamp(x, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double hashUnit(long value) {
        long x = value;
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return (x & 0x1fffffffffffffL) / (double) 0x1fffffffffffffL;
    }

    private static void alphaBlend() {
        RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
    }

    private static void additiveBlend() {
        RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE
        );
    }
}
