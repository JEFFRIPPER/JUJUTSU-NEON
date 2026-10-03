package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Кат-сцена Максимального Фиолетового по референсу.
 *
 * Камера — клиентская сущность-маркер, которой Minecraft отдаёт роль камеры.
 * Minecraft при этом не рисует локального игрока, поэтому он рендерится вручную.
 * Таймлайн (тики от старта, общий с сервером — см. {@link MaximumPurple}):
 *   0–34   вихрь и красная энергия в руке (крупные планы)
 *   34     появляются полосы «кино»
 *   46–58  общий план, красный всплеск над головой
 *   70–102 Красный и Синий у плеч, крупный план лица
 *   102–114 шары сходятся
 *   114–122 манга-кадр удара
 *   122–142 фиолетовый космос, вспышка разрастается
 *   142+   белый экран, пока сервер вырезает кратер
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MaximumPurpleClient {

    private static final ResourceLocation TEX_IMPACT = new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/max_purple_impact.png");
    private static final ResourceLocation TEX_COSMOS = new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/max_purple_cosmos.png");
    private static final ResourceLocation TEX_BLOOM = new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/max_purple_bloom.png");
    private static final ResourceLocation TEX_RED_ORB = new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/max_purple_red_orb.png");
    private static final ResourceLocation TEX_BLUE_ORB = new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/max_purple_blue_orb.png");

    private static final int FADE_OUT_TICKS = 20;
    private static final int LOCAL_TIMEOUT_TICKS = 1800;
    private static final int OBSERVER_LIFETIME = 200;

    private static final double ORB_SIDE = 1.30;
    private static final float ORB_RADIUS = 0.42f;

    private static final Map<UUID, Scene> SCENES = new HashMap<>();

    /** Кат-сцена локального игрока (он же владелец техники). */
    private static Scene local;
    private static Marker cameraEntity;
    private static CameraType savedCameraType;
    private static boolean changedGravity;
    private static float camEyeOld;
    private static float camEye;
    /** Белый экран после конца: сколько тиков осталось до полного исчезновения. */
    private static int fadeOut;

    private MaximumPurpleClient() {
    }

    private static final class Scene {
        final UUID ownerId;
        final ClientLevel level;
        final Vec3 feet;
        final float yaw;
        final Vec3 forward;
        final Vec3 right;
        int age;
        boolean ended;
        int endedAge;

        Scene(UUID ownerId, ClientLevel level, Vec3 feet, float yaw) {
            this.ownerId = ownerId;
            this.level = level;
            this.feet = feet;
            this.yaw = yaw;
            double rad = Math.toRadians(yaw);
            this.forward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
            this.right = new Vec3(-forward.z, 0.0, forward.x);
        }

        /** Точка в системе игрока: f — вперёд, r — вправо от игрока, u — вверх от ног. */
        Vec3 at(double f, double r, double u) {
            return new Vec3(
                    feet.x + forward.x * f + right.x * r,
                    feet.y + u,
                    feet.z + forward.z * f + right.z * r);
        }
    }

    // ------------------------------------------------------------------ API

    /** Кат-сцена локального игрока идёт (до сигнала сервера о конце). */
    public static boolean isLocalActive() {
        return local != null && !local.ended;
    }

    static void onStart(UUID ownerId, Vec3 feet, float yaw) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Scene scene = new Scene(ownerId, mc.level, feet, yaw);
        SCENES.put(ownerId, scene);

        if (ownerId.equals(mc.player.getUUID())) {
            stopLocalCamera(mc);
            local = scene;
            fadeOut = 0;
            startLocalCamera(mc);
            JujutsuNeonMod.ClientForgeEvents.startAnim("MAX_PURPLE", MaximumPurple.ANIM_TICKS);
        }
    }

    static void onEnd(UUID ownerId) {
        Scene scene = SCENES.get(ownerId);
        if (scene == null) return;
        scene.ended = true;
        scene.endedAge = scene.age;
        if (scene == local) {
            Minecraft mc = Minecraft.getInstance();
            stopLocalCamera(mc);
            fadeOut = FADE_OUT_TICKS;
            local = null;
        }
    }

    private static void startLocalCamera(Minecraft mc) {
        LocalPlayer player = mc.player;
        cameraEntity = new Marker(EntityType.MARKER, mc.level);
        Vec3 eye = player.getEyePosition();
        cameraEntity.setPos(eye.x, eye.y, eye.z);
        savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        camEye = camEyeOld = player.getEyeHeight();
        mc.setCameraEntity(cameraEntity);
        if (!player.isNoGravity()) {
            player.setNoGravity(true);
            changedGravity = true;
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
    }

    private static void stopLocalCamera(Minecraft mc) {
        if (cameraEntity != null) {
            if (mc.player != null) mc.setCameraEntity(mc.player);
            cameraEntity = null;
        }
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
        if (changedGravity && mc.player != null) {
            mc.player.setNoGravity(false);
        }
        changedGravity = false;
    }

    private static void resetAll() {
        Minecraft mc = Minecraft.getInstance();
        stopLocalCamera(mc);
        SCENES.clear();
        local = null;
        fadeOut = 0;
    }

    // ------------------------------------------------------------------ ticks

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!SCENES.isEmpty() || cameraEntity != null) resetAll();
            return;
        }

        if (event.phase == TickEvent.Phase.START) {
            if (isLocalActive()) {
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.setSprinting(false);
                mc.player.fallDistance = 0.0f;
            }
            return;
        }

        if (fadeOut > 0) fadeOut--;

        Iterator<Map.Entry<UUID, Scene>> it = SCENES.entrySet().iterator();
        while (it.hasNext()) {
            Scene scene = it.next().getValue();
            if (scene.level != mc.level) {
                if (scene == local) {
                    stopLocalCamera(mc);
                    local = null;
                }
                it.remove();
                continue;
            }

            scene.age++;
            spawnParticles(mc, scene);

            if (scene == local) {
                camEyeOld = camEye;
                camEye += (0.0f - camEye) * 0.5f;
                if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (mc.getCameraEntity() != cameraEntity && cameraEntity != null) mc.setCameraEntity(cameraEntity);
                if (scene.age > LOCAL_TIMEOUT_TICKS) onEnd(scene.ownerId);
            }

            boolean expired = scene.ended
                    ? scene.age - scene.endedAge > 60
                    : scene != local && scene.age > OBSERVER_LIFETIME;
            if (expired) it.remove();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isLocalActive()) return;
        var input = event.getInput();
        input.forwardImpulse = 0.0f;
        input.leftImpulse = 0.0f;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    // ------------------------------------------------------------------ camera

    private record Shot(int start, int end,
                        double cf0, double cr0, double cu0, double cf1, double cr1, double cu1,
                        double tf0, double tr0, double tu0, double tf1, double tr1, double tu1,
                        float fov0, float fov1, float roll, float pitch) {
    }

    /** Монтаж по референсу: жёсткие склейки, внутри плана — медленный наезд. */
    private static final Shot[] SHOTS = {
            // 1. Присел, общий план спереди-слева (реф. 2.4)
            new Shot(0, 14, 3.0, -0.8, 1.0, 2.6, -0.7, 1.0, 0.0, 0.0, 0.9, 0.0, 0.0, 0.95, 70f, 68f, 0f, 0f),
            // 2. Крупно спереди: вихрь вокруг, красная энергия в руке (2.9–3.2)
            new Shot(14, 34, 1.9, -0.5, 1.5, 1.6, -0.4, 1.45, 0.0, 0.1, 1.25, 0.0, 0.1, 1.3, 72f, 70f, -5f, 0f),
            // 3. Сбоку крупно: рука поднята, красная точка на пальце (3.6)
            new Shot(34, 46, 0.9, -1.7, 1.5, 0.8, -1.6, 1.55, 0.0, -0.1, 1.85, 0.0, -0.1, 1.9, 70f, 68f, 0f, -15f),
            // 4. Общий план спереди, красный всплеск над головой (4.1)
            new Shot(46, 58, 8.0, 0.6, 1.6, 7.4, 0.6, 1.7, 0.0, 0.0, 1.7, 0.0, 0.0, 1.75, 60f, 60f, 0f, -10f),
            // 5. Сверху-сзади, взгляд через плечо на дорогу (4.6–5.0)
            new Shot(58, 70, -2.2, 0.9, 2.7, -1.9, 0.8, 2.5, 2.0, 0.0, 0.4, 2.0, 0.0, 0.5, 70f, 70f, 0f, 0f),
            // 6. Средний план спереди: Красный и Синий у плеч (5.3–5.7)
            new Shot(70, 84, 4.4, 0.0, 1.5, 3.8, 0.0, 1.5, 0.0, 0.0, 1.35, 0.0, 0.0, 1.4, 70f, 70f, 0f, 0f),
            // 7. Лицо крупным планом, шары по краям кадра (6.2–6.6)
            new Shot(84, 102, 1.15, 0.0, 1.5, 0.95, 0.0, 1.55, 0.0, 0.0, 1.6, 0.0, 0.0, 1.62, 84f, 80f, 0f, 12f),
            // 8. Из-за плеча: шары сходятся впереди (7.1–7.4)
            new Shot(102, 122, -1.3, -1.4, 1.75, -1.1, -1.3, 1.75, 1.8, 0.3, 1.45, 1.8, 0.3, 1.45, 70f, 66f, 0f, 0f),
            // 9. Снизу спереди, «голландский» угол, космос за спиной (8.6–9.0)
            new Shot(122, 200, 1.5, 0.5, 0.45, 2.3, 0.6, 0.35, 0.0, 0.0, 1.55, 0.0, 0.0, 1.6, 75f, 84f, -14f, -18f),
    };

    private static Shot shotAt(double t) {
        for (Shot shot : SHOTS) {
            if (t < shot.end) return shot;
        }
        return SHOTS[SHOTS.length - 1];
    }

    private static double shotProgress(Shot shot, double t) {
        return Mth.clamp((t - shot.start) / (double) (shot.end - shot.start), 0.0, 1.0);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Scene scene = local;
        Marker camera = cameraEntity;
        Minecraft mc = Minecraft.getInstance();
        if (scene == null || camera == null || mc.player == null) return;

        float pt = event.renderTickTime;
        double t = scene.age + pt;
        Shot shot = shotAt(t);
        double s = shotProgress(shot, t);

        Vec3 pos = scene.at(Mth.lerp(s, shot.cf0, shot.cf1), Mth.lerp(s, shot.cr0, shot.cr1), Mth.lerp(s, shot.cu0, shot.cu1));
        Vec3 target = scene.at(Mth.lerp(s, shot.tf0, shot.tf1), Mth.lerp(s, shot.tr0, shot.tr1), Mth.lerp(s, shot.tu0, shot.tu1));
        Vec3 d = target.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));

        // Camera добавляет к позиции «высоту глаз» сущности, которая после смены
        // камеры плавно уходит к нулю. Компенсируем её, чтобы кадр не плыл.
        double eye = Mth.lerp(pt, camEyeOld, camEye);
        double y = pos.y - eye;
        camera.setPos(pos.x, y, pos.z);
        camera.xo = camera.xOld = pos.x;
        camera.yo = camera.yOld = y;
        camera.zo = camera.zOld = pos.z;
        camera.setYRot(yaw);
        camera.yRotO = yaw;
        camera.setXRot(pitch);
        camera.xRotO = pitch;

        // Тело игрока смотрит туда, куда смотрел при старте.
        LocalPlayer player = mc.player;
        player.setYRot(scene.yaw);
        player.yRotO = scene.yaw;
        player.yBodyRot = scene.yaw;
        player.yBodyRotO = scene.yaw;
        player.yHeadRot = scene.yaw;
        player.yHeadRotO = scene.yaw;
        player.setXRot(shot.pitch);
        player.xRotO = shot.pitch;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Scene scene = local;
        if (scene == null || cameraEntity == null) return;
        double t = scene.age + event.getPartialTick();
        Shot shot = shotAt(t);
        float roll = shot.roll;

        float shake = 0.0f;
        if (t >= MaximumPurple.T_IMPACT && t < MaximumPurple.T_IMPACT + 20) {
            shake = 2.2f * (1.0f - (float) (t - MaximumPurple.T_IMPACT) / 20.0f);
        } else if (t >= MaximumPurple.T_MERGE && t < MaximumPurple.T_IMPACT) {
            shake = 0.35f * (float) ((t - MaximumPurple.T_MERGE) / (MaximumPurple.T_IMPACT - MaximumPurple.T_MERGE));
        }
        event.setYaw(event.getYaw() + (float) Math.sin(t * 5.1) * shake);
        event.setPitch(event.getPitch() + (float) Math.cos(t * 6.3) * shake * 0.7f);
        event.setRoll(roll + (float) Math.sin(t * 7.7) * shake * 0.5f);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFov(ViewportEvent.ComputeFov event) {
        Scene scene = local;
        if (scene == null || cameraEntity == null || !event.usedConfiguredFov()) return;
        double t = scene.age + event.getPartialTick();
        Shot shot = shotAt(t);
        event.setFOV(Mth.lerp(shotProgress(shot, t), shot.fov0, shot.fov1));
    }

    // ------------------------------------------------------------------ hide UI / hands

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderHand(RenderHandEvent event) {
        if (cameraEntity != null) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlay(RenderGuiOverlayEvent.Pre event) {
        if (cameraEntity != null) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onNameTag(RenderNameTagEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (cameraEntity != null && event.getEntity() == mc.player) event.setResult(Event.Result.DENY);
    }

    // ------------------------------------------------------------------ world rendering

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || SCENES.isEmpty()) return;

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            Scene scene = local;
            if (scene == null || cameraEntity == null || mc.player == null) return;
            renderLocalPlayer(mc, event);
            double t = scene.age + event.getPartialTick();
            if (t >= MaximumPurple.T_COSMOS - 1) renderCosmosBackdrop(event, t);
            return;
        }

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        float pt = event.getPartialTick();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        for (Scene scene : SCENES.values()) {
            double t = scene.age + (scene.ended ? 0.0f : pt);
            renderScene(pose, camera, scene, t);
        }

        HollowPurpleReferenceClient.mpAlphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Minecraft не рисует локального игрока, когда камера не он сам. */
    private static void renderLocalPlayer(Minecraft mc, RenderLevelStageEvent event) {
        LocalPlayer player = mc.player;
        float pt = event.getPartialTick();
        Vec3 camera = event.getCamera().getPosition();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        double x = Mth.lerp(pt, player.xOld, player.getX()) - camera.x;
        double y = Mth.lerp(pt, player.yOld, player.getY()) - camera.y;
        double z = Mth.lerp(pt, player.zOld, player.getZ()) - camera.z;
        float yaw = Mth.lerp(pt, player.yRotO, player.getYRot());
        int light = dispatcher.getPackedLightCoords(player, pt);

        dispatcher.render(player, x, y, z, yaw, pt, event.getPoseStack(), buffers, light);
        buffers.endBatch();
    }

    /** Фиолетовый космос за спиной игрока (8.6–9.0): текстурный экран в 9 блоках от камеры. */
    private static void renderCosmosBackdrop(RenderLevelStageEvent event, double t) {
        if (t >= MaximumPurple.T_WHITE_FULL + 4) return;
        float alpha = (float) Mth.clamp((t - (MaximumPurple.T_COSMOS - 1)) / 3.0, 0.0, 1.0);
        if (alpha <= 0.01f) return;

        Camera cam = event.getCamera();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        double dist = 9.0;
        Minecraft mc = Minecraft.getInstance();
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        double halfH = dist * Math.tan(Math.toRadians(45.0)) * 1.5;
        double halfW = halfH * aspect;

        double cx = look.x() * dist, cy = look.y() * dist, cz = look.z() * dist;
        double zoom = 0.08 * ((t - MaximumPurple.T_COSMOS) / 20.0);
        float u0 = (float) zoom, v0 = (float) zoom, u1 = (float) (1.0 - zoom), v1 = (float) (1.0 - zoom);

        PoseStack pose = event.getPoseStack();
        Matrix4f m = pose.last().pose();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_COSMOS);

        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(b, m, cx + left.x() * halfW + up.x() * halfH, cy + left.y() * halfW + up.y() * halfH, cz + left.z() * halfW + up.z() * halfH, u0, v0, alpha);
        quadVertex(b, m, cx + left.x() * halfW - up.x() * halfH, cy + left.y() * halfW - up.y() * halfH, cz + left.z() * halfW - up.z() * halfH, u0, v1, alpha);
        quadVertex(b, m, cx - left.x() * halfW - up.x() * halfH, cy - left.y() * halfW - up.y() * halfH, cz - left.z() * halfW - up.z() * halfH, u1, v1, alpha);
        quadVertex(b, m, cx - left.x() * halfW + up.x() * halfH, cy - left.y() * halfW + up.y() * halfH, cz - left.z() * halfW + up.z() * halfH, u1, v0, alpha);
        BufferUploader.drawWithShader(b.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void quadVertex(BufferBuilder b, Matrix4f m, double x, double y, double z, float u, float v, float alpha) {
        b.vertex(m, (float) x, (float) y, (float) z).uv(u, v).color(1.0f, 1.0f, 1.0f, alpha).endVertex();
    }

    private static void renderScene(PoseStack pose, Vec3 camera, Scene scene, double t) {
        double time = t;
        Vec3 dir = scene.forward;
        Vec3 chest = scene.at(0.0, 0.0, 1.1);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // ---- Вихрь вокруг игрока (2.9–3.2)
        if (t >= 8 && t < 48) {
            float in = (float) smooth((t - 8) / 8.0);
            float out = 1.0f - (float) smooth((t - 40) / 8.0);
            float a = in * out;
            for (int i = 0; i < 5; i++) {
                Vec3 normal = dir.add(scene.right.scale((i - 2) * 0.08)).add(0.0, (i % 2 == 0 ? 0.06 : -0.05), 0.0).normalize();
                float radius = (1.55f + i * 0.30f) * (0.75f + 0.25f * in);
                HollowPurpleReferenceClient.mpAlphaBlend();
                HollowPurpleReferenceClient.mpRing(pose, camera, chest, normal, radius, 0.10f + i * 0.035f,
                        (i % 2 == 0 ? 1 : -1) * time * (9.0 + i * 2.5), 6, 3, a * (0.62f - i * 0.07f), 0.16f, 31.0 + i * 13.0, true);
                HollowPurpleReferenceClient.mpAdditiveBlend();
                HollowPurpleReferenceClient.mpRing(pose, camera, chest, normal, radius * 1.02f, 0.018f,
                        (i % 2 == 0 ? 1 : -1) * time * (9.0 + i * 2.5) + 40.0, 4, 5, a * 0.22f, 0.16f, 77.0 + i * 13.0, true);
            }
        }

        // ---- Красная энергия в руке (2.9–3.6)
        if (t >= 6 && t < 46) {
            Vec3 hand = t < 34 ? scene.at(0.42, 0.28, 1.38) : scene.at(0.05, 0.36, 2.45);
            float a = (float) smooth((t - 6) / 6.0);
            float r = (t < 34 ? 0.11f : 0.075f) + 0.02f * (float) Math.sin(time * 1.7);
            HollowPurpleReferenceClient.mpLimitless(pose, camera, hand, dir, r, a, time, false);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            for (int i = 0; i < 3; i++) {
                double ang = time * 0.9 + i * 2.1;
                Vec3 end = hand.add(scene.right.scale(Math.cos(ang) * 0.45)).add(0.0, Math.sin(ang) * 0.35, 0.0);
                HollowPurpleReferenceClient.mpArc(pose, camera, hand, end, 0.012f, 6, time, 11.0 + i * 5.0, i == 0 ? 2 : 1, a * 0.7f, 0.08f);
            }
        }

        // ---- Красный всплеск над головой (4.1)
        if (t >= 44 && t < 62) {
            float life = 1.0f - (float) smooth((t - 54) / 8.0);
            float grow = (float) smooth((t - 44) / 6.0);
            Vec3 top = scene.at(0.0, 0.15, 2.35);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            for (int i = 0; i < 22; i++) {
                double a = i * 2.39996;
                double spread = 0.35 + (i % 5) * 0.12;
                Vec3 out = scene.forward.scale(Math.cos(a) * spread).add(scene.right.scale(Math.sin(a) * spread * 2.2))
                        .add(0.0, 0.55 + (i % 3) * 0.2, 0.0).normalize();
                double len = (1.2 + (i % 4) * 0.55) * grow;
                HollowPurpleReferenceClient.mpArc(pose, camera, top, top.add(out.scale(len)), 0.03f + (i % 3) * 0.012f,
                        7, time, 101.0 + i * 7.0, i % 4 == 0 ? 2 : 1, life * 0.85f, 0.18f);
            }
            HollowPurpleReferenceClient.mpSphere(pose, camera, top, 0.35f * grow, 10, 16, 1, 2, life * 0.5f, 0.2f, time, 5.0);
        }

        // ---- Красный и Синий у плеч, затем схождение (5.3–7.4)
        if (t >= MaximumPurple.T_ORBS && t < MaximumPurple.T_IMPACT + 1) {
            double pop = smooth((t - MaximumPurple.T_ORBS) / 8.0);
            float radius = (float) (ORB_RADIUS * (pop + 0.18 * Math.sin(Math.PI * pop)));
            double m = Mth.clamp((t - MaximumPurple.T_MERGE) / (double) (MaximumPurple.T_IMPACT - MaximumPurple.T_MERGE), 0.0, 1.0);
            double mm = m * m;
            radius *= (float) (1.0 - 0.35 * m);
            Vec3 merge = scene.at(1.35, 0.0, 1.45);
            Vec3 red = scene.at(0.15, ORB_SIDE, 1.45).lerp(merge, mm);
            Vec3 blue = scene.at(0.15, -ORB_SIDE, 1.45).lerp(merge, mm);
            float alpha = t >= MaximumPurple.T_IMPACT ? 0.0f : 1.0f;

            if (alpha > 0.0f && radius > 0.01f) {
                drawTexturedOrb(pose, camera, red, radius, TEX_RED_ORB, time * 0.012, alpha);
                drawTexturedOrb(pose, camera, blue, radius, TEX_BLUE_ORB, -time * 0.015, alpha);

                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                HollowPurpleReferenceClient.mpAdditiveBlend();
                HollowPurpleReferenceClient.mpSphere(pose, camera, red, radius * 1.16f, 14, 22, 1, 2, 0.22f, 0.04f, time, 3.0);
                HollowPurpleReferenceClient.mpSphere(pose, camera, blue, radius * 1.16f, 14, 22, 4, 5, 0.22f, 0.04f, time, 9.0);
                if (m > 0.0) {
                    HollowPurpleReferenceClient.mpBridge(pose, camera, red, blue, (float) (0.25 + 0.6 * m), time, 21.0);
                }
            }
        }

        // ---- Рождение Фиолетового в точке схождения (7.8–8.2)
        if (t >= MaximumPurple.T_IMPACT - 1 && t < MaximumPurple.T_IMPACT + 14) {
            Vec3 merge = scene.at(1.35, 0.0, 1.45);
            double g = smooth((t - (MaximumPurple.T_IMPACT - 1)) / 8.0);
            float fade = 1.0f - (float) smooth((t - (MaximumPurple.T_IMPACT + 8)) / 6.0);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpPurple(pose, camera, merge, dir, (float) (0.35 + 2.4 * g), fade, time, 1.0f, 1.0f);
        }

        // ---- Взрыв: купол радиусом до 100 блоков
        if (t >= MaximumPurple.T_IMPACT + 4 && t < MaximumPurple.T_IMPACT + 70) {
            double x = Mth.clamp((t - (MaximumPurple.T_IMPACT + 4)) / 28.0, 0.0, 1.0);
            float radius = (float) (2.0 + (MaximumPurple.RADIUS - 2.0) * (1.0 - Math.pow(1.0 - x, 3.0)));
            float life = 1.0f - (float) smooth((t - (MaximumPurple.T_IMPACT + 36)) / 34.0);
            Vec3 center = scene.at(0.0, 0.0, 1.0);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAlphaBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, radius, 22, 34, 6, 7, 0.42f * life, 0.05f, time * 0.4, 401.0);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, radius * 1.015f, 22, 34, 9, 10, 0.30f * life, 0.06f, time * 0.6, 419.0);
            for (int i = 0; i < 3; i++) {
                HollowPurpleReferenceClient.mpRing(pose, camera, scene.at(0.0, 0.0, 0.3 + i * 1.5), new Vec3(0.0, 1.0, 0.0),
                        radius * (1.03f + i * 0.04f), Math.max(0.2f, radius * 0.025f), time * (3.0 + i), 9, 11,
                        life * (0.75f - i * 0.18f), 0.06f, 451.0 + i * 17.0, i > 0);
            }
        }
    }

    /** Шар с текстурой (Красный — с чёрными разводами, Синий — с голубыми прожилками, как в референсе). */
    private static void drawTexturedOrb(PoseStack pose, Vec3 camera, Vec3 position, float radius,
                                        ResourceLocation texture, double spin, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);

        pose.pushPose();
        pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
        Matrix4f m = pose.last().pose();
        Vec3 toCamera = camera.subtract(position).normalize();

        int lat = 18, lon = 28;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < lat; i++) {
            double p0 = -Math.PI / 2 + Math.PI * i / lat;
            double p1 = -Math.PI / 2 + Math.PI * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                double a0 = Math.PI * 2 * j / lon;
                double a1 = Math.PI * 2 * (j + 1) / lon;
                orbVertex(b, m, p0, a0, radius, i, j, lat, lon, spin, toCamera, alpha);
                orbVertex(b, m, p0, a1, radius, i, j + 1, lat, lon, spin, toCamera, alpha);
                orbVertex(b, m, p1, a1, radius, i + 1, j + 1, lat, lon, spin, toCamera, alpha);
                orbVertex(b, m, p0, a0, radius, i, j, lat, lon, spin, toCamera, alpha);
                orbVertex(b, m, p1, a1, radius, i + 1, j + 1, lat, lon, spin, toCamera, alpha);
                orbVertex(b, m, p1, a0, radius, i + 1, j, lat, lon, spin, toCamera, alpha);
            }
        }
        BufferUploader.drawWithShader(b.end());
        pose.popPose();

        RenderSystem.depthMask(false);
    }

    private static void orbVertex(BufferBuilder b, Matrix4f m, double phi, double theta, float radius,
                                  int i, int j, int lat, int lon, double spin, Vec3 toCamera, float alpha) {
        double cp = Math.cos(phi);
        double nx = Math.cos(theta) * cp, ny = Math.sin(phi), nz = Math.sin(theta) * cp;
        float shade = (float) (0.62 + 0.38 * Math.max(0.0, nx * toCamera.x + ny * toCamera.y + nz * toCamera.z));
        float u = (float) (j / (double) lon + spin);
        float v = (float) (1.0 - i / (double) lat);
        b.vertex(m, (float) (nx * radius), (float) (ny * radius), (float) (nz * radius))
                .uv(u, v).color(shade, shade, shade, alpha).endVertex();
    }

    // ------------------------------------------------------------------ particles

    private static void spawnParticles(Minecraft mc, Scene scene) {
        ClientLevel level = mc.level;
        if (level == null) return;
        int t = scene.age;
        var rnd = level.random;

        if (t >= 8 && t < 34) {
            Vec3 hand = scene.at(0.42, 0.28, 1.38);
            for (int i = 0; i < 2; i++) {
                level.addParticle(new DustParticleOptions(new Vector3f(1.0f, 0.05f, 0.1f), 0.9f),
                        hand.x + (rnd.nextDouble() - 0.5) * 0.4, hand.y + (rnd.nextDouble() - 0.5) * 0.4,
                        hand.z + (rnd.nextDouble() - 0.5) * 0.4, 0, 0, 0);
            }
        }
        if (t >= 46 && t < 52) {
            Vec3 top = scene.at(0.0, 0.15, 2.35);
            for (int i = 0; i < 10; i++) {
                level.addParticle(new DustParticleOptions(new Vector3f(1.0f, 0.08f, 0.15f), 1.6f),
                        top.x + (rnd.nextDouble() - 0.5) * 2.5, top.y + rnd.nextDouble() * 1.5,
                        top.z + (rnd.nextDouble() - 0.5) * 2.5, 0, 0, 0);
            }
        }
        if (t >= MaximumPurple.T_ORBS && t < MaximumPurple.T_IMPACT && t % 2 == 0) {
            Vec3 red = scene.at(0.15, ORB_SIDE, 1.45);
            Vec3 blue = scene.at(0.15, -ORB_SIDE, 1.45);
            level.addParticle(new DustParticleOptions(new Vector3f(1.0f, 0.1f, 0.12f), 0.7f),
                    red.x + (rnd.nextDouble() - 0.5) * 1.1, red.y + (rnd.nextDouble() - 0.5) * 1.1, red.z + (rnd.nextDouble() - 0.5) * 1.1, 0, 0, 0);
            level.addParticle(new DustParticleOptions(new Vector3f(0.15f, 0.55f, 1.0f), 0.7f),
                    blue.x + (rnd.nextDouble() - 0.5) * 1.1, blue.y + (rnd.nextDouble() - 0.5) * 1.1, blue.z + (rnd.nextDouble() - 0.5) * 1.1, 0, 0, 0);
        }
        if (t == MaximumPurple.T_IMPACT) {
            Vec3 merge = scene.at(1.35, 0.0, 1.45);
            level.addParticle(ParticleTypes.FLASH, merge.x, merge.y, merge.z, 0, 0, 0);
        }
        if (t >= MaximumPurple.T_IMPACT + 2 && t < MaximumPurple.T_IMPACT + 16) {
            Vec3 c = scene.at(0.0, 0.0, 1.0);
            for (int i = 0; i < 26; i++) {
                double vx = rnd.nextGaussian(), vy = rnd.nextGaussian() * 0.6, vz = rnd.nextGaussian();
                double len = Math.max(0.001, Math.sqrt(vx * vx + vy * vy + vz * vz));
                double speed = 1.2 + rnd.nextDouble() * 1.8;
                level.addParticle(i % 3 == 0 ? ParticleTypes.END_ROD : ParticleTypes.DRAGON_BREATH,
                        c.x, c.y, c.z, vx / len * speed, vy / len * speed, vz / len * speed);
            }
        }
    }

    // ------------------------------------------------------------------ GUI overlays

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();
        float pt = event.getPartialTick();

        Scene scene = local;
        if (scene != null) {
            double t = scene.age + pt;
            drawLocalOverlays(g, w, h, t);
            return;
        }

        if (fadeOut > 0) {
            float a = Mth.clamp((fadeOut - pt) / FADE_OUT_TICKS, 0.0f, 1.0f);
            fillWhite(g, w, h, a);
            return;
        }

        // Наблюдатели: вспышка, если взрыв рядом.
        float flash = 0.0f;
        Vec3 eye = mc.player.getEyePosition();
        for (Scene other : SCENES.values()) {
            if (other.ownerId.equals(mc.player.getUUID())) continue;
            double dist = eye.distanceTo(other.feet);
            float proximity = (float) (1.0 - Mth.clamp((dist - 30.0) / 130.0, 0.0, 1.0));
            if (proximity <= 0.0f) continue;
            double t = other.age + pt;
            float in = (float) smooth((t - MaximumPurple.T_IMPACT) / 8.0);
            float out = 1.0f - (float) smooth((t - (MaximumPurple.T_WHITE_FULL + 8)) / 30.0);
            flash = Math.max(flash, in * out * proximity * 0.92f);
        }
        if (flash > 0.005f) fillWhite(g, w, h, flash);
    }

    private static void drawLocalOverlays(GuiGraphics g, int w, int h, double t) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Полосы «кино»
        if (t >= MaximumPurple.T_LETTERBOX && t < MaximumPurple.T_COSMOS + 8) {
            float in = (float) smooth((t - MaximumPurple.T_LETTERBOX) / 4.0);
            int bar = (int) (h * 0.12f * in);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
        }

        // Манга-кадр удара (7.8)
        if (t >= MaximumPurple.T_IMPACT && t < MaximumPurple.T_COSMOS) {
            double k = t - MaximumPurple.T_IMPACT;
            float scale = 1.04f + 0.03f * (float) Math.sin(k * 2.7);
            int dw = (int) (w * scale), dh = (int) (h * scale);
            int ox = (w - dw) / 2 + (int) (Math.sin(k * 9.1) * 3.0);
            int oy = (h - dh) / 2 + (int) (Math.cos(k * 7.3) * 3.0);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.blit(TEX_IMPACT, ox, oy, dw, dh, 0.0f, 0.0f, 1280, 720, 1280, 720);
        }

        // Белое свечение разрастается (8.2–9.6)
        if (t >= MaximumPurple.T_COSMOS - 2 && t < MaximumPurple.T_WHITE_FULL + 2) {
            float grow = (float) smooth((t - (MaximumPurple.T_COSMOS - 2)) / (MaximumPurple.T_WHITE_FULL - MaximumPurple.T_COSMOS + 2.0));
            int size = (int) (h * (0.6f + 3.4f * grow));
            int cx = (int) (w * 0.66f), cy = (int) (h * 0.45f);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, Math.min(1.0f, 0.55f + grow));
            g.blit(TEX_BLOOM, cx - size / 2, cy - size / 2, size, size, 0.0f, 0.0f, 512, 512, 512, 512);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        // Белый экран, пока вырезается кратер
        if (t >= MaximumPurple.T_WHITE_IN) {
            float a = (float) smooth((t - MaximumPurple.T_WHITE_IN) / (double) (MaximumPurple.T_WHITE_FULL - MaximumPurple.T_WHITE_IN));
            fillWhite(g, w, h, a);
        }
    }

    private static void fillWhite(GuiGraphics g, int w, int h, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        if (a <= 0) return;
        g.fill(0, 0, w, h, (a << 24) | 0x00FFFFFF);
    }

    private static double smooth(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
