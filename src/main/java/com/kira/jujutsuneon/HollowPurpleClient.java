package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
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
 * Client-side Hollow Purple renderer based on the supplied reference.
 *
 * Purple is never a camera-facing billboard here. The entire visible body is
 * procedural 3D geometry: Red/Blue precursor spheres, merge core, layered Purple,
 * release rings, projectile shell and a five-second residual spatial trail.
 */
@Mod.EventBusSubscriber(
        modid = HollowPurpleOverhaul.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class HollowPurpleClient {

    private static final int MODE_NONE = 0;
    private static final int MODE_CASTING = 1;
    private static final int MODE_PROJECTILE = 2;

    private static final int TRAIL_LIFETIME = 100; // 5 секунд при 20 TPS
    private static final Map<UUID, PurpleVisualState> VISUALS = new HashMap<>();

    private HollowPurpleClient() {
    }

    /**
     * The original vfx_hollow_purple particle is intentionally suppressed.
     * It used a 4K billboard as the body of Purple and caused the flicker/rotation
     * problem from the old implementation. Secondary Dust/Electric particles remain.
     */
    @Mod.EventBusSubscriber(
            modid = HollowPurpleOverhaul.MODID,
            bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT
    )
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void registerPurpleProvider(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(
                    JujutsuNeonMod.VFX_PURPLE.get(),
                    sprites -> new ParticleProvider<SimpleParticleType>() {
                        @Override
                        public Particle createParticle(
                                SimpleParticleType type,
                                ClientLevel level,
                                double x,
                                double y,
                                double z,
                                double xd,
                                double yd,
                                double zd
                        ) {
                            return null;
                        }
                    }
            );
        }
    }

    private static final class PurpleTrailPoint {
        final Vec3 position;
        final float radius;
        final long bornTick;

        PurpleTrailPoint(Vec3 position, float radius, long bornTick) {
            this.position = position;
            this.radius = radius;
            this.bornTick = bornTick;
        }
    }

    private static final class PurpleVisualState {
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
        final ArrayDeque<PurpleTrailPoint> trail = new ArrayDeque<>();

        PurpleVisualState(Vec3 pos, Vec3 direction) {
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

        PurpleVisualState visual = VISUALS.computeIfAbsent(
                msg.ownerId(),
                ignored -> new PurpleVisualState(pos, direction)
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
            if (visual.lastTrailPosition == null ||
                    visual.lastTrailPosition.distanceToSqr(pos) >= 2.25) {
                visual.trail.addLast(new PurpleTrailPoint(
                        pos,
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

        Iterator<Map.Entry<UUID, PurpleVisualState>> iterator = VISUALS.entrySet().iterator();
        while (iterator.hasNext()) {
            PurpleVisualState visual = iterator.next().getValue();

            visual.previousPos = visual.currentPos;
            visual.previousDirection = visual.currentDirection;
            visual.previousRadius = visual.currentRadius;
            visual.previousProgress = visual.currentProgress;
            visual.previousAlpha = visual.currentAlpha;

            visual.currentPos = visual.currentPos.lerp(visual.targetPos, 0.68);
            visual.currentDirection = safeDirection(
                    visual.currentDirection.lerp(visual.targetDirection, 0.55)
            );
            visual.currentRadius += (visual.targetRadius - visual.currentRadius) * 0.62f;
            visual.currentProgress += (visual.targetProgress - visual.currentProgress) * 0.56f;
            visual.currentAlpha += (visual.targetAlpha - visual.currentAlpha) * 0.38f;
            visual.staleTicks++;

            if (visual.releaseAge < 999) visual.releaseAge++;

            while (!visual.trail.isEmpty() &&
                    now - visual.trail.peekFirst().bornTick >= TRAIL_LIFETIME) {
                visual.trail.removeFirst();
            }

            if (visual.staleTicks > 14) {
                visual.targetAlpha = 0.0f;
                visual.targetRadius = 0.0f;
            }

            if (visual.targetAlpha <= 0.001f &&
                    visual.currentAlpha <= 0.012f &&
                    visual.trail.isEmpty()) {
                iterator.remove();
            }
        }
    }

    /**
     * Reference feature: the world exposure drops while Purple is being formed.
     * The overlay is drawn before the normal HUD, so bindings and vanilla UI stay readable.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        float strongest = 0.0f;

        for (PurpleVisualState visual : VISUALS.values()) {
            if (visual.mode != MODE_CASTING || visual.currentAlpha <= 0.01f) continue;

            double distance = camera.distanceTo(visual.currentPos);
            float proximity = (float) (1.0 - Mth.clamp(distance / 64.0, 0.0, 1.0));
            float progress = Mth.clamp(visual.currentProgress, 0.0f, 1.0f);
            float ramp = (float) smooth(Math.max(0.0, (progress - 0.12f) / 0.88f));
            float darkness = (0.10f + 0.58f * ramp) * proximity * visual.currentAlpha;
            strongest = Math.max(strongest, darkness);
        }

        if (strongest <= 0.01f) return;

        int alpha = Mth.clamp((int) (strongest * 255.0f), 0, 178);
        int color = (alpha << 24);
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        event.getGuiGraphics().fill(0, 0, width, height, color);
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
        long now = mc.level.getGameTime();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        for (PurpleVisualState visual : VISUALS.values()) {
            Vec3 pos = visual.previousPos.lerp(visual.currentPos, partialTick);
            Vec3 direction = safeDirection(
                    visual.previousDirection.lerp(visual.currentDirection, partialTick)
            );
            float radius = Mth.lerp(partialTick, visual.previousRadius, visual.currentRadius);
            float progress = Mth.lerp(partialTick, visual.previousProgress, visual.currentProgress);
            float alpha = Mth.lerp(partialTick, visual.previousAlpha, visual.currentAlpha);

            renderResidualTrail(pose, camera, visual, direction, now, partialTick);

            if (alpha <= 0.01f) continue;

            if (visual.mode == MODE_CASTING) {
                renderCast(pose, camera, pos, direction, progress, alpha, now, partialTick);
            } else if (visual.mode == MODE_PROJECTILE) {
                renderProjectile(
                        pose,
                        camera,
                        visual,
                        pos,
                        direction,
                        Math.max(0.04f, radius),
                        alpha,
                        now,
                        partialTick
                );
            }
        }

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
            long now,
            float partialTick
    ) {
        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        Vec3 backCenter = eye.subtract(direction.scale(1.30)).add(0.0, -0.18, 0.0);
        Vec3 redBack = backCenter.add(right.scale(-2.10));
        Vec3 blueBack = backCenter.add(right.scale(2.10));
        Vec3 mergePoint = eye.add(direction.scale(2.35)).add(0.0, -0.12, 0.0);

        double time = now + partialTick;

        if (progress < 0.38f) {
            float grow = (float) smooth(progress / 0.38f);
            float orbRadius = 0.18f + grow * 1.05f;

            renderLimitlessOrb(
                    pose, camera, redBack, direction,
                    orbRadius, 1.0f, 0.008f, 0.018f,
                    alpha, time, false
            );
            renderLimitlessOrb(
                    pose, camera, blueBack, direction,
                    orbRadius, 0.008f, 0.46f, 1.0f,
                    alpha, time, true
            );
            return;
        }

        if (progress < 0.72f) {
            float merge = (float) smooth((progress - 0.38f) / 0.34f);
            Vec3 red = redBack.lerp(mergePoint, merge);
            Vec3 blue = blueBack.lerp(mergePoint, merge);
            float orbRadius = 1.22f - merge * 0.62f;

            renderLimitlessOrb(
                    pose, camera, red, direction,
                    orbRadius, 1.0f, 0.008f, 0.018f,
                    alpha * (1.0f - merge * 0.22f), time, false
            );
            renderLimitlessOrb(
                    pose, camera, blue, direction,
                    orbRadius, 0.008f, 0.46f, 1.0f,
                    alpha * (1.0f - merge * 0.22f), time, true
            );

            Vec3 midpoint = red.lerp(blue, 0.5);
            float purpleRadius = 0.18f + merge * 0.70f;
            renderPurpleBody(
                    pose, camera, midpoint, direction,
                    purpleRadius, alpha,
                    time,
                    0.55f + 0.45f * merge
            );

            // During the merge, red and blue energy still remain visibly distinct.
            drawOrientedRingAt(
                    pose, camera, midpoint,
                    safeDirection(direction.add(right.scale(0.55))),
                    purpleRadius * 1.55f,
                    Math.max(0.025f, purpleRadius * 0.055f),
                    time * 2.4,
                    1.0f, 0.02f, 0.08f,
                    alpha * 0.56f
            );
            drawOrientedRingAt(
                    pose, camera, midpoint,
                    safeDirection(direction.add(up.scale(-0.55))),
                    purpleRadius * 1.72f,
                    Math.max(0.025f, purpleRadius * 0.045f),
                    -time * 2.0,
                    0.02f, 0.55f, 1.0f,
                    alpha * 0.56f
            );
            return;
        }

        float growth = (float) smooth((progress - 0.72f) / 0.28f);
        float radius = 0.82f + growth * (2.0f - 0.82f);

        renderPurpleBody(
                pose,
                camera,
                mergePoint,
                direction,
                radius,
                alpha,
                time,
                0.86f + 0.14f * growth
        );

        // Final compression rings become tighter just before release.
        drawOrientedRingAt(
                pose, camera, mergePoint, direction,
                radius * (1.72f - growth * 0.18f),
                Math.max(0.035f, radius * 0.038f),
                time * 3.1,
                0.90f, 0.16f, 1.0f,
                alpha * (0.52f + growth * 0.22f)
        );
        drawOrientedRingAt(
                pose, camera, mergePoint,
                safeDirection(direction.add(right.scale(0.42)).add(up.scale(0.22))),
                radius * (1.48f - growth * 0.08f),
                Math.max(0.030f, radius * 0.032f),
                -time * 2.7,
                1.0f, 0.03f, 0.58f,
                alpha * 0.62f
        );
    }

    private static void renderProjectile(
            PoseStack pose,
            Vec3 camera,
            PurpleVisualState visual,
            Vec3 position,
            Vec3 direction,
            float radius,
            float alpha,
            long now,
            float partialTick
    ) {
        double time = now + partialTick;

        renderPurpleBody(
                pose,
                camera,
                position,
                direction,
                radius,
                alpha,
                time,
                1.0f
        );

        // Tight spatial rings around the moving annihilation core.
        for (int i = 0; i < 3; i++) {
            Vec3 ringCenter = position.subtract(direction.scale(0.30 + i * 0.34));
            float ringRadius = radius * (1.38f + i * 0.18f);

            drawOrientedRingAt(
                    pose,
                    camera,
                    ringCenter,
                    direction,
                    ringRadius,
                    Math.max(0.025f, radius * (0.040f - i * 0.006f)),
                    time * (3.8 - i * 0.45) + i * 48.0,
                    i == 1 ? 1.0f : 0.58f,
                    i == 1 ? 0.04f : 0.02f,
                    i == 1 ? 0.62f : 1.0f,
                    alpha * (0.68f - i * 0.10f)
            );
        }

        renderReleaseBurst(pose, camera, visual, alpha, time);
    }

    private static void renderReleaseBurst(
            PoseStack pose,
            Vec3 camera,
            PurpleVisualState visual,
            float alpha,
            double time
    ) {
        if (visual.releaseOrigin == null || visual.releaseAge >= 24) return;

        float life = 1.0f - visual.releaseAge / 24.0f;
        float eased = (float) smooth(1.0f - life);
        Vec3 direction = safeDirection(visual.releaseDirection);

        // Reference-like spatial shock structure: several concentric 3D rings
        // stretch forward instead of one flat flash.
        for (int i = 0; i < 5; i++) {
            Vec3 center = visual.releaseOrigin.add(direction.scale(i * 0.95));
            float ringRadius = 2.0f + i * 0.90f + eased * (2.2f + i * 0.42f);
            float thickness = 0.10f + i * 0.018f;

            float r = i % 3 == 1 ? 1.0f : 0.55f;
            float g = i % 3 == 1 ? 0.06f : 0.015f;
            float b = i % 3 == 1 ? 0.72f : 1.0f;

            drawOrientedRingAt(
                    pose,
                    camera,
                    center,
                    direction,
                    ringRadius,
                    thickness,
                    time * (2.4 + i * 0.31) + i * 27.0,
                    r, g, b,
                    alpha * life * (0.82f - i * 0.08f)
            );
        }

        // A bright inner ring gives the same white-hot punch visible in the reference.
        drawOrientedRingAt(
                pose,
                camera,
                visual.releaseOrigin.add(direction.scale(0.40)),
                direction,
                1.25f + eased * 1.25f,
                0.075f,
                -time * 4.0,
                1.0f, 0.86f, 1.0f,
                alpha * life * 0.88f
        );
    }

    private static void renderResidualTrail(
            PoseStack pose,
            Vec3 camera,
            PurpleVisualState visual,
            Vec3 direction,
            long now,
            float partialTick
    ) {
        if (visual.trail.isEmpty()) return;

        int index = 0;
        for (PurpleTrailPoint node : visual.trail) {
            long ageTicks = now - node.bornTick;
            if (ageTicks < 0 || ageTicks >= TRAIL_LIFETIME) {
                index++;
                continue;
            }

            // Every third stored node is enough to produce a continuous afterimage,
            // while keeping the number of geometry submissions bounded.
            if (index % 3 != 0) {
                index++;
                continue;
            }

            float age = Mth.clamp((ageTicks + partialTick) / (float) TRAIL_LIFETIME, 0.0f, 1.0f);
            float fade = 1.0f - age;
            float radius = Math.max(0.18f, node.radius * (0.92f + age * 0.10f));

            drawOrientedRingAt(
                    pose,
                    camera,
                    node.position,
                    direction,
                    radius * 1.12f,
                    Math.max(0.018f, radius * 0.025f),
                    now * 1.7 + index * 19.0,
                    0.52f, 0.015f, 1.0f,
                    fade * 0.42f
            );

            drawOrientedRingAt(
                    pose,
                    camera,
                    node.position.add(direction.scale(0.10)),
                    safeDirection(direction.add(rightFor(direction).scale(0.18))),
                    radius * 0.86f,
                    Math.max(0.014f, radius * 0.018f),
                    -now * 1.3 - index * 11.0,
                    1.0f, 0.02f, 0.58f,
                    fade * 0.28f
            );

            index++;
        }
    }

    private static void renderLimitlessOrb(
            PoseStack pose,
            Vec3 camera,
            Vec3 position,
            Vec3 direction,
            float radius,
            float red,
            float green,
            float blue,
            float alpha,
            double time,
            boolean reverse
    ) {
        drawSphereAt(
                pose, camera, position,
                radius * 1.10f,
                16, 24,
                red * 0.42f,
                green * 0.42f,
                blue * 0.42f,
                alpha * 0.20f
        );
        drawSphereAt(
                pose, camera, position,
                radius * 0.88f,
                18, 26,
                red,
                green,
                blue,
                alpha * 0.64f
        );
        drawSphereAt(
                pose, camera, position,
                radius * 0.34f,
                14, 20,
                Math.min(1.0f, red + 0.26f),
                Math.min(1.0f, green + 0.26f),
                Math.min(1.0f, blue + 0.26f),
                alpha * 0.88f
        );

        Vec3 right = rightFor(direction);
        Vec3 tilted = safeDirection(direction.add(right.scale(reverse ? -0.48 : 0.48)));

        drawOrientedRingAt(
                pose, camera, position,
                tilted,
                radius * 1.32f,
                Math.max(0.018f, radius * 0.045f),
                (reverse ? -1.0 : 1.0) * time * 2.6,
                red, green, blue,
                alpha * 0.62f
        );
    }

    private static void renderPurpleBody(
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

        // Layer order is intentional because depth writes are disabled:
        // outer spatial haze -> violet plasma -> dark void -> white-hot core.
        drawSphereAt(
                pose, camera, position,
                radius * 1.12f,
                20, 30,
                0.28f, 0.015f, 0.56f,
                a * 0.20f
        );
        drawSphereAt(
                pose, camera, position,
                radius,
                22, 32,
                0.58f, 0.018f, 1.0f,
                a * 0.34f
        );
        drawSphereAt(
                pose, camera, position,
                radius * 0.90f,
                20, 30,
                1.0f, 0.025f, 0.58f,
                a * 0.20f
        );
        drawSphereAt(
                pose, camera, position,
                radius * 0.72f,
                18, 28,
                0.045f, 0.002f, 0.085f,
                a * 0.82f
        );
        drawSphereAt(
                pose, camera, position,
                radius * 0.38f,
                16, 24,
                1.0f, 0.82f, 1.0f,
                a * 0.92f
        );
        drawSphereAt(
                pose, camera, position,
                radius * 0.18f,
                14, 22,
                1.0f, 1.0f, 1.0f,
                a
        );

        Vec3 right = rightFor(direction);
        Vec3 up = right.cross(direction).normalize();

        Vec3 normalA = safeDirection(direction.add(right.scale(0.58)).add(up.scale(0.18)));
        Vec3 normalB = safeDirection(direction.add(right.scale(-0.26)).add(up.scale(0.62)));
        Vec3 normalC = safeDirection(direction.add(right.scale(0.14)).add(up.scale(-0.72)));

        drawOrientedRingAt(
                pose, camera, position,
                normalA,
                radius * 1.22f,
                Math.max(0.022f, radius * 0.035f),
                time * 3.6,
                0.60f, 0.025f, 1.0f,
                a * 0.74f
        );
        drawOrientedRingAt(
                pose, camera, position,
                normalB,
                radius * 1.34f,
                Math.max(0.020f, radius * 0.030f),
                -time * 3.0,
                1.0f, 0.025f, 0.58f,
                a * 0.66f
        );
        drawOrientedRingAt(
                pose, camera, position,
                normalC,
                radius * 1.45f,
                Math.max(0.018f, radius * 0.026f),
                time * 2.5 + 90.0,
                0.82f, 0.30f, 1.0f,
                a * 0.52f
        );
    }

    private static void drawSphereAt(
            PoseStack pose,
            Vec3 camera,
            Vec3 worldPosition,
            float radius,
            int latitudeSegments,
            int longitudeSegments,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (radius <= 0.0f || alpha <= 0.0f) return;

        pose.pushPose();
        pose.translate(
                worldPosition.x - camera.x,
                worldPosition.y - camera.y,
                worldPosition.z - camera.z
        );

        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int lat = 0; lat < latitudeSegments; lat++) {
            double v0 = lat / (double) latitudeSegments;
            double v1 = (lat + 1) / (double) latitudeSegments;
            double phi0 = -Math.PI / 2.0 + Math.PI * v0;
            double phi1 = -Math.PI / 2.0 + Math.PI * v1;

            float y0 = (float) (Math.sin(phi0) * radius);
            float y1 = (float) (Math.sin(phi1) * radius);
            float ring0 = (float) (Math.cos(phi0) * radius);
            float ring1 = (float) (Math.cos(phi1) * radius);

            for (int lon = 0; lon < longitudeSegments; lon++) {
                double u0 = lon / (double) longitudeSegments;
                double u1 = (lon + 1) / (double) longitudeSegments;
                double a0 = Math.PI * 2.0 * u0;
                double a1 = Math.PI * 2.0 * u1;

                float x00 = (float) (Math.cos(a0) * ring0);
                float z00 = (float) (Math.sin(a0) * ring0);
                float x01 = (float) (Math.cos(a1) * ring0);
                float z01 = (float) (Math.sin(a1) * ring0);
                float x10 = (float) (Math.cos(a0) * ring1);
                float z10 = (float) (Math.sin(a0) * ring1);
                float x11 = (float) (Math.cos(a1) * ring1);
                float z11 = (float) (Math.sin(a1) * ring1);

                vertex(buffer, matrix, x00, y0, z00, red, green, blue, alpha);
                vertex(buffer, matrix, x10, y1, z10, red, green, blue, alpha);
                vertex(buffer, matrix, x11, y1, z11, red, green, blue, alpha);

                vertex(buffer, matrix, x00, y0, z00, red, green, blue, alpha);
                vertex(buffer, matrix, x11, y1, z11, red, green, blue, alpha);
                vertex(buffer, matrix, x01, y0, z01, red, green, blue, alpha);
            }
        }

        BufferUploader.drawWithShader(buffer.end());
        pose.popPose();
    }

    private static void drawOrientedRingAt(
            PoseStack pose,
            Vec3 camera,
            Vec3 worldPosition,
            Vec3 normal,
            float radius,
            float thickness,
            double rotationDegrees,
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
        pose.translate(
                worldPosition.x - camera.x,
                worldPosition.y - camera.y,
                worldPosition.z - camera.z
        );

        Matrix4f matrix = pose.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        int segments = 64;
        double rotation = Math.toRadians(rotationDegrees);
        float half = Math.max(0.006f, thickness);

        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments + rotation;
            double a1 = Math.PI * 2.0 * (i + 1) / segments + rotation;

            Vec3 q0 = ringPoint(right, up, a0, radius + half);
            Vec3 q1 = ringPoint(right, up, a1, radius + half);
            Vec3 r0 = ringPoint(right, up, a0, radius - half);
            Vec3 r1 = ringPoint(right, up, a1, radius - half);

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
        return right.scale(Math.cos(angle) * radius)
                .add(up.scale(Math.sin(angle) * radius));
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

    private static void vertex(
            BufferBuilder buffer,
            Matrix4f matrix,
            Vec3 point,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        vertex(
                buffer,
                matrix,
                (float) point.x,
                (float) point.y,
                (float) point.z,
                red,
                green,
                blue,
                alpha
        );
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
                .color(red, green, blue, alpha)
                .endVertex();
    }
}
