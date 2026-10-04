package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kira.jujutsuneon.MaximumPurple.*;

/**
 * Кат-сцена Максимального Фиолетового по референсу (покадровый разбор видео).
 *
 * Камера — клиентская сущность-маркер, которой Minecraft отдаёт роль камеры.
 * Minecraft при этом не рисует локального игрока, поэтому он рендерится вручную.
 *
 *   2–26    Синий в руке, бросок вверх, зависает в небе
 *   26–34   общий план: Синий над головой
 *   36–45   в руке загорается Красный, манга-кадры А
 *   45–58   тёмный вихрь вокруг, красная энергия
 *   58–68   Красный к лицу; 66 — полосы «кино»
 *   68–80   выстрел Красного вверх, он встаёт на уровень Синего
 *   80–98   присед и прыжок на 13 блоков с сальто
 *   98–128  в небе между шарами, лицо крупным планом
 *   128–144 шары сходятся
 *   144–155 манга-кадры Б (сине-белый, три фиолетовых, силуэт в молниях)
 *   155–178 фиолетовый космос, руки раскинуты
 *   178–184 белая вспышка с силуэтом
 *   184–200 взрыв вокруг игрока, купол до 100 блоков
 *   200+    белый экран, пока сервер дорезает кратер; потом игрок висит в воздухе в режиме полёта
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MaximumPurpleClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_COSMOS = tex("max_purple_cosmos");
    private static final ResourceLocation TEX_BLOOM = tex("max_purple_bloom");
    private static final ResourceLocation TEX_RED_ORB = tex("max_purple_red_orb");
    private static final ResourceLocation TEX_BLUE_ORB = tex("max_purple_blue_orb");
    private static final ResourceLocation TEX_VORTEX = tex("max_purple_vortex");
    private static final ResourceLocation MANGA_A1 = tex("max_purple_manga_a1");
    private static final ResourceLocation MANGA_A2 = tex("max_purple_manga_a2");
    private static final ResourceLocation[] MANGA_B = {
            tex("max_purple_manga_b1"), tex("max_purple_manga_b2"), tex("max_purple_manga_b3"),
            tex("max_purple_manga_b4"), tex("max_purple_manga_b5")
    };

    private static final int FADE_OUT_TICKS = 20;
    private static final int LOCAL_TIMEOUT_TICKS = 1800;
    private static final int OBSERVER_LIFETIME = 260;

    private static final double ORB_SIDE = 1.30;
    private static final float ORB_RADIUS = 0.32f;
    private static final double ORB_LIFT = 1.45;

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final RandomSource RNG = RandomSource.create();

    /** Кат-сцена локального игрока (он же владелец техники). */
    private static Scene local;
    private static Marker cameraEntity;
    private static CameraType savedCameraType;
    private static boolean changedGravity;
    private static boolean savedMayfly;
    private static boolean savedFlying;
    private static float camEyeOld;
    private static float camEye;
    /** Белый экран после конца: сколько тиков осталось до полного исчезновения. */
    private static int fadeOut;
    private static boolean flipPushed;

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

        /** Точка относительно места старта: f — вперёд, r — вправо от игрока, u — вверх. */
        Vec3 at(double f, double r, double u) {
            return new Vec3(
                    feet.x + forward.x * f + right.x * r,
                    feet.y + u,
                    feet.z + forward.z * f + right.z * r);
        }

        /** Точка относительно игрока в момент t (с учётом прыжка). */
        Vec3 atP(double f, double r, double u, double t) {
            return at(f, r, u + heightAt(t));
        }
    }

    private static final class Spark {
        Vec3 pos;
        Vec3 vel;
        final float size;
        final float r, g, b;
        final int life;
        final double drag;
        int age;

        Spark(Vec3 pos, Vec3 vel, float size, float r, float g, float b, int life, double drag) {
            this.pos = pos;
            this.vel = vel;
            this.size = size;
            this.r = r;
            this.g = g;
            this.b = b;
            this.life = life;
            this.drag = drag;
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
            savedMayfly = mc.player.getAbilities().mayfly;
            savedFlying = mc.player.getAbilities().flying;
            startLocalCamera(mc);
            JujutsuNeonMod.ClientForgeEvents.startAnim("MAX_PURPLE", ANIM_TICKS);
        }
    }

    static void onEnd(UUID ownerId, boolean completed) {
        Scene scene = SCENES.get(ownerId);
        if (scene == null) return;
        scene.ended = true;
        scene.endedAge = scene.age;
        if (scene == local) {
            Minecraft mc = Minecraft.getInstance();
            stopLocalCamera(mc);
            fadeOut = FADE_OUT_TICKS;
            local = null;
            if (mc.player != null) {
                mc.player.getAbilities().mayfly = savedMayfly;
                mc.player.getAbilities().flying = savedMayfly && savedFlying;
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.fallDistance = 0.0f;
                // Игрок остаётся там, куда прыгнул, — в режиме полёта.
                if (completed) JujutsuNeonFlightClient.beginFlightForTechnique();
            }
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
        mc.getToasts().clear();
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
        SPARKS.clear();
        local = null;
        fadeOut = 0;
    }

    // ------------------------------------------------------------------ orb paths

    private static double smooth(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double easeOut(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return 1.0 - Math.pow(1.0 - t, 3.0);
    }

    private static double mergeT(double t) {
        double m = Mth.clamp((t - T_MERGE) / (double) (T_MANGA_B - T_MERGE), 0.0, 1.0);
        return m * m;
    }

    private static Vec3 mergePoint(Scene s) {
        return s.at(1.0, 0.0, JUMP_HEIGHT + ORB_LIFT);
    }

    private static Vec3 spiral(Scene s, double t, double start, double e, double amp) {
        double ang = (t - start) * 0.85;
        double a = amp * (1.0 - e);
        return s.right.scale(Math.cos(ang) * a).add(s.forward.scale(Math.sin(ang) * a * 0.6));
    }

    /** Позиция Синего или null, если его ещё нет. */
    private static Vec3 bluePos(Scene s, double t) {
        if (t < T_BLUE_SPAWN || t >= T_MANGA_B) return null;
        Vec3 hover = s.at(0.15, -ORB_SIDE, JUMP_HEIGHT + ORB_LIFT).add(0.0, Math.sin(t * 0.2) * 0.05, 0.0);
        if (t < T_BLUE_THROW) return s.atP(0.5, 0.35, 1.25, t);
        if (t < T_BLUE_HOVER) {
            double e = easeOut((t - T_BLUE_THROW) / (double) (T_BLUE_HOVER - T_BLUE_THROW));
            return s.at(0.5, 0.35, 1.25).lerp(hover, e).add(spiral(s, t, T_BLUE_THROW, e, 1.4));
        }
        return hover.lerp(mergePoint(s), mergeT(t));
    }

    private static float blueRadius(double t) {
        if (t < T_BLUE_THROW) return (float) (0.06 + 0.10 * smooth((t - T_BLUE_SPAWN) / 6.0));
        if (t < T_BLUE_HOVER) {
            double e = easeOut((t - T_BLUE_THROW) / (double) (T_BLUE_HOVER - T_BLUE_THROW));
            return (float) (0.16 + (ORB_RADIUS - 0.16) * e);
        }
        return (float) (ORB_RADIUS * (1.0 - 0.45 * Math.sqrt(mergeT(t))));
    }

    /** Позиция Красного или null. */
    private static Vec3 redPos(Scene s, double t) {
        if (t < T_RED_GLOW || t >= T_MANGA_B) return null;
        Vec3 hover = s.at(0.15, ORB_SIDE, JUMP_HEIGHT + ORB_LIFT).add(0.0, Math.sin(t * 0.2 + 1.7) * 0.05, 0.0);
        if (t < T_VORTEX) return s.atP(0.38, 0.25, 1.15, t);
        if (t < T_RED_FACE) return s.atP(0.42, 0.28, 1.38, t);
        if (t < T_LETTERBOX) return s.atP(0.32, 0.18, 1.85, t);
        if (t < T_RED_THROW) return s.atP(0.05, 0.36, 2.45, t);
        if (t < T_RED_HOVER) {
            double e = easeOut((t - T_RED_THROW) / (double) (T_RED_HOVER - T_RED_THROW));
            return s.at(0.05, 0.36, 2.45).lerp(hover, e).add(spiral(s, t, T_RED_THROW, e, 0.8));
        }
        return hover.lerp(mergePoint(s), mergeT(t));
    }

    private static float redRadius(double t) {
        if (t < T_VORTEX) return (float) (0.03 + 0.03 * smooth((t - T_RED_GLOW) / 4.0));
        if (t < T_RED_FACE) return 0.09f + 0.015f * (float) Math.sin(t * 1.7);
        if (t < T_RED_THROW) return 0.075f;
        if (t < T_RED_HOVER) {
            double e = easeOut((t - T_RED_THROW) / (double) (T_RED_HOVER - T_RED_THROW));
            return (float) (0.075 + (ORB_RADIUS - 0.075) * e);
        }
        return (float) (ORB_RADIUS * (1.0 - 0.45 * Math.sqrt(mergeT(t))));
    }

    // ------------------------------------------------------------------ ticks

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!SCENES.isEmpty() || cameraEntity != null || !SPARKS.isEmpty()) resetAll();
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
        tickSparks();

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
            spawnSparks(scene);

            if (scene == local) {
                camEyeOld = camEye;
                camEye += (0.0f - camEye) * 0.5f;
                if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (mc.getCameraEntity() != cameraEntity && cameraEntity != null) mc.setCameraEntity(cameraEntity);
                mc.getToasts().clear();

                // Игрок идёт по траектории кат-сцены: стоит, потом прыжок на 13 блоков и висит.
                Vec3 target = scene.feet.add(0.0, heightAt(scene.age), 0.0);
                mc.player.setPos(target.x, target.y, target.z);
                mc.player.setDeltaMovement(Vec3.ZERO);

                if (scene.age > LOCAL_TIMEOUT_TICKS) onEnd(scene.ownerId, false);
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

    private static final int TGT_PLAYER = 0;
    private static final int TGT_GROUND = 1;
    private static final int TGT_BLUE = 2;
    private static final int TGT_CHEST = 3;

    private record Shot(int start, int end, boolean camGround, int target,
                        double cf0, double cr0, double cu0, double cf1, double cr1, double cu1,
                        double tf0, double tr0, double tu0, double tf1, double tr1, double tu1,
                        float fov0, float fov1, float roll, float pitch) {
    }

    private static Shot shot(int start, int end, boolean camGround, int target,
                             double[] c0, double[] c1, double[] t0, double[] t1,
                             float fov0, float fov1, float roll, float pitch) {
        return new Shot(start, end, camGround, target, c0[0], c0[1], c0[2], c1[0], c1[1], c1[2],
                t0[0], t0[1], t0[2], t1[0], t1[1], t1[2], fov0, fov1, roll, pitch);
    }

    private static double[] v(double f, double r, double u) {
        return new double[]{f, r, u};
    }

    /** Монтаж по покадровому разбору референса: жёсткие склейки, внутри плана — медленный наезд. */
    private static final Shot[] SHOTS = {
            // Синий в руке, камера из-за спины (0,5–1,1 с)
            shot(0, T_BLUE_THROW, false, TGT_PLAYER, v(-3.4, -0.9, 2.1), v(-3.1, -0.8, 2.0),
                    v(1.5, 0.2, 1.2), v(1.2, 0.3, 1.25), 70f, 70f, 0f, 0f),
            // Бросок: камера провожает Синего вверх (1,2–1,5 с)
            shot(T_BLUE_THROW, T_BLUE_HOVER, false, TGT_BLUE, v(-3.1, -0.8, 2.0), v(-3.6, -1.0, 1.4),
                    v(0, 0, 0), v(0, 0, 0), 70f, 74f, 0f, 0f),
            // Общий план: Синий висит в небе (2,0 с)
            shot(T_BLUE_HOVER, 34, true, TGT_GROUND, v(11.0, 3.5, 4.0), v(10.0, 3.5, 4.4),
                    v(0.0, -0.5, 6.5), v(0.0, -0.5, 6.8), 62f, 62f, 0f, 0f),
            // Крупно спереди, в руке загорается Красный (2,1–2,5 с)
            shot(34, T_VORTEX, false, TGT_PLAYER, v(2.2, -0.4, 1.2), v(1.9, -0.35, 1.25),
                    v(0.0, 0.1, 1.2), v(0.0, 0.1, 1.2), 70f, 70f, 0f, 0f),
            // Вихрь вокруг, красная энергия (2,8–3,2 с)
            shot(T_VORTEX, T_RED_FACE, false, TGT_PLAYER, v(2.1, -0.5, 1.5), v(1.8, -0.4, 1.45),
                    v(0.0, 0.1, 1.3), v(0.0, 0.1, 1.3), 72f, 70f, -5f, 0f),
            // Красный к лицу (3,3–3,8 с)
            shot(T_RED_FACE, T_LETTERBOX, false, TGT_PLAYER, v(1.5, -1.0, 1.35), v(1.35, -0.9, 1.4),
                    v(0.0, 0.15, 1.65), v(0.0, 0.15, 1.7), 68f, 66f, 0f, -10f),
            // Общий план спереди: выстрел Красного вверх (3,9–4,3 с)
            shot(T_LETTERBOX, T_RED_HOVER, false, TGT_GROUND, v(8.0, 0.6, 1.6), v(7.4, 0.6, 1.8),
                    v(0.0, 0.0, 2.2), v(0.0, 0.0, 6.0), 62f, 62f, 0f, -15f),
            // Присед перед прыжком (4,4–4,6 с)
            shot(T_RED_HOVER, 86, false, TGT_PLAYER, v(3.6, 0.9, 0.7), v(3.4, 0.9, 0.7),
                    v(0.0, 0.0, 0.9), v(0.0, 0.0, 0.9), 66f, 66f, 0f, 0f),
            // Взлёт снизу, с земли (4,7–4,9 с)
            shot(86, 92, true, TGT_CHEST, v(2.6, -1.4, 0.9), v(2.6, -1.4, 0.9),
                    v(0, 0, 0), v(0, 0, 0), 76f, 78f, 8f, 0f),
            // Сверху-сзади, внизу дорога (5,0–5,1 с)
            shot(92, T_APEX, false, TGT_PLAYER, v(-1.4, 0.7, 3.4), v(-1.2, 0.6, 3.0),
                    v(1.6, 0.2, -3.0), v(1.6, 0.2, -2.0), 72f, 72f, 0f, 0f),
            // Сзади: шары по бокам на одном уровне (5,2–5,3 с)
            shot(T_APEX, 106, false, TGT_PLAYER, v(-2.4, 0.2, 1.9), v(-2.1, 0.2, 1.8),
                    v(1.2, 0.0, 1.6), v(1.2, 0.0, 1.6), 76f, 76f, 0f, 0f),
            // Средний план спереди в небе (5,4–6,0 с)
            shot(106, T_FACE, false, TGT_PLAYER, v(4.2, 0.0, 1.5), v(3.7, 0.0, 1.5),
                    v(0.0, 0.0, 1.35), v(0.0, 0.0, 1.4), 70f, 70f, 0f, 0f),
            // Лицо крупным планом, шары по краям (6,1–6,8 с)
            shot(T_FACE, T_MERGE, false, TGT_PLAYER, v(1.35, 0.0, 1.5), v(1.2, 0.0, 1.55),
                    v(0.0, 0.0, 1.62), v(0.0, 0.0, 1.62), 70f, 66f, 0f, 12f),
            // Из-за плеча: шары сходятся (6,9–7,6 с), под манга-кадрами камера стоит
            shot(T_MERGE, T_COSMOS, false, TGT_PLAYER, v(-1.3, -1.4, 1.75), v(-1.1, -1.3, 1.75),
                    v(1.6, 0.3, 1.45), v(1.6, 0.3, 1.45), 70f, 66f, 0f, 0f),
            // Космос: снизу спереди, «голландский» угол (8,2–9,3 с)
            shot(T_COSMOS, T_EXPLODE, false, TGT_PLAYER, v(1.5, 0.5, 0.45), v(2.3, 0.6, 0.35),
                    v(0.0, 0.0, 1.55), v(0.0, 0.0, 1.6), 75f, 84f, -14f, -18f),
            // Взрыв издалека (9,9–10,2 с)
            shot(T_EXPLODE, 400, true, TGT_GROUND, v(44.0, 9.0, 18.0), v(48.0, 10.0, 19.0),
                    v(0.0, 0.0, 10.0), v(0.0, 0.0, 11.0), 70f, 70f, 0f, 0f),
    };

    private static Shot shotAt(double t) {
        for (Shot shot : SHOTS) {
            if (t < shot.end) return shot;
        }
        return SHOTS[SHOTS.length - 1];
    }

    private static double shotProgress(Shot shot, double t) {
        int end = Math.min(shot.end, shot.start + 60);
        return Mth.clamp((t - shot.start) / (double) (end - shot.start), 0.0, 1.0);
    }

    private static Vec3 shotTarget(Scene scene, Shot shot, double t, double s) {
        double f = Mth.lerp(s, shot.tf0, shot.tf1), r = Mth.lerp(s, shot.tr0, shot.tr1), u = Mth.lerp(s, shot.tu0, shot.tu1);
        return switch (shot.target) {
            case TGT_GROUND -> scene.at(f, r, u);
            case TGT_BLUE -> {
                Vec3 b = bluePos(scene, t);
                yield b != null ? b : scene.atP(0.0, 0.0, 1.5, t);
            }
            case TGT_CHEST -> scene.atP(0.0, 0.0, 1.1, t);
            default -> scene.atP(f, r, u, t);
        };
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

        double cf = Mth.lerp(s, shot.cf0, shot.cf1), cr = Mth.lerp(s, shot.cr0, shot.cr1), cu = Mth.lerp(s, shot.cu0, shot.cu1);
        Vec3 pos = shot.camGround ? scene.at(cf, cr, cu) : scene.atP(cf, cr, cu, t);
        Vec3 target = shotTarget(scene, shot, t, s);
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

        float shake = 0.0f;
        if (t >= T_EXPLODE && t < T_EXPLODE + 20) {
            shake = 2.4f * (1.0f - (float) (t - T_EXPLODE) / 20.0f);
        } else if (t >= T_JUMP && t < T_JUMP + 5) {
            shake = 1.2f * (1.0f - (float) (t - T_JUMP) / 5.0f);
        } else if (t >= T_MERGE && t < T_MANGA_B) {
            shake = 0.45f * (float) ((t - T_MERGE) / (T_MANGA_B - T_MERGE));
        } else if (t >= T_COSMOS && t < T_FLASH) {
            shake = 0.3f;
        }
        event.setYaw(event.getYaw() + (float) Math.sin(t * 5.1) * shake);
        event.setPitch(event.getPitch() + (float) Math.cos(t * 6.3) * shake * 0.7f);
        event.setRoll(shot.roll + (float) Math.sin(t * 7.7) * shake * 0.5f);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFov(ViewportEvent.ComputeFov event) {
        Scene scene = local;
        if (scene == null || cameraEntity == null || !event.usedConfiguredFov()) return;
        double t = scene.age + event.getPartialTick();
        Shot shot = shotAt(t);
        event.setFOV(Mth.lerp(shotProgress(shot, t), shot.fov0, shot.fov1));
    }

    // ------------------------------------------------------------------ hide UI / hands / name

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

    /** Сальто во время прыжка (4,7–4,9 с референса). */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        flipPushed = false;
        Scene scene = local;
        Minecraft mc = Minecraft.getInstance();
        if (scene == null || event.getEntity() != mc.player) return;
        double t = scene.age + event.getPartialTick();
        if (t < T_JUMP + 2 || t > T_APEX - 2) return;
        float angle = (float) (360.0 * smooth((t - (T_JUMP + 2)) / (double) (T_APEX - T_JUMP - 4)));
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0.0, 0.9, 0.0);
        pose.mulPose(Axis.XP.rotationDegrees(-angle));
        pose.translate(0.0, -0.9, 0.0);
        flipPushed = true;
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (flipPushed) {
            event.getPoseStack().popPose();
            flipPushed = false;
        }
    }

    // ------------------------------------------------------------------ world rendering

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || (SCENES.isEmpty() && SPARKS.isEmpty())) return;

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            Scene scene = local;
            if (scene == null || cameraEntity == null || mc.player == null) return;
            renderLocalPlayer(mc, event);
            double t = scene.age + event.getPartialTick();
            if (t >= T_COSMOS - 1 && t < T_FLASH + 4) renderCosmosBackdrop(event, t);
            return;
        }

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        Vec3 camera = cam.getPosition();
        float pt = event.getPartialTick();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        for (Scene scene : SCENES.values()) {
            renderScene(pose, cam, camera, scene, scene.age + pt);
        }
        renderSparks(pose, cam, camera, pt);

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

    /** Фиолетовый космос за спиной игрока: текстурный экран в 9 блоках от камеры. */
    private static void renderCosmosBackdrop(RenderLevelStageEvent event, double t) {
        float alpha = (float) Mth.clamp((t - (T_COSMOS - 1)) / 3.0, 0.0, 1.0);
        if (alpha <= 0.01f) return;

        Camera cam = event.getCamera();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        double dist = 9.0;
        Minecraft mc = Minecraft.getInstance();
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        double halfH = dist * 1.5;
        double halfW = halfH * aspect;

        double cx = look.x() * dist, cy = look.y() * dist, cz = look.z() * dist;
        double zoom = 0.10 * Mth.clamp((t - T_COSMOS) / (double) (T_FLASH - T_COSMOS), 0.0, 1.0);
        float u0 = (float) zoom, v0 = (float) zoom, u1 = (float) (1.0 - zoom), v1 = (float) (1.0 - zoom);

        Matrix4f m = event.getPoseStack().last().pose();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_COSMOS);

        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(b, m, cx + left.x() * halfW + up.x() * halfH, cy + left.y() * halfW + up.y() * halfH, cz + left.z() * halfW + up.z() * halfH, u0, v0, 1f, 1f, 1f, alpha);
        quadVertex(b, m, cx + left.x() * halfW - up.x() * halfH, cy + left.y() * halfW - up.y() * halfH, cz + left.z() * halfW - up.z() * halfH, u0, v1, 1f, 1f, 1f, alpha);
        quadVertex(b, m, cx - left.x() * halfW - up.x() * halfH, cy - left.y() * halfW - up.y() * halfH, cz - left.z() * halfW - up.z() * halfH, u1, v1, 1f, 1f, 1f, alpha);
        quadVertex(b, m, cx - left.x() * halfW + up.x() * halfH, cy - left.y() * halfW + up.y() * halfH, cz - left.z() * halfW + up.z() * halfH, u1, v0, 1f, 1f, 1f, alpha);
        BufferUploader.drawWithShader(b.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void quadVertex(BufferBuilder b, Matrix4f m, double x, double y, double z,
                                   float u, float v, float r, float g, float bl, float alpha) {
        b.vertex(m, (float) x, (float) y, (float) z).uv(u, v).color(r, g, bl, alpha).endVertex();
    }

    /** Квадрат, повёрнутый к камере (и на угол roll в своей плоскости). */
    private static void billboard(PoseStack pose, Camera cam, Vec3 camera, Vec3 center, float size, float roll,
                                  ResourceLocation texture, float r, float g, float b, float alpha, boolean additive) {
        if (alpha <= 0.003f || size <= 0.0f) return;
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double lx = leftV.x() * cr + upV.x() * sr, ly = leftV.y() * cr + upV.y() * sr, lz = leftV.z() * cr + upV.z() * sr;
        double ux = upV.x() * cr - leftV.x() * sr, uy = upV.y() * cr - leftV.y() * sr, uz = upV.z() * cr - leftV.z() * sr;
        double h = size * 0.5;
        double x = center.x - camera.x, y = center.y - camera.y, z = center.z - camera.z;

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        if (additive) HollowPurpleReferenceClient.mpAdditiveBlend();
        else HollowPurpleReferenceClient.mpAlphaBlend();

        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(buf, m, x + (lx + ux) * h, y + (ly + uy) * h, z + (lz + uz) * h, 0f, 0f, r, g, b, alpha);
        quadVertex(buf, m, x + (lx - ux) * h, y + (ly - uy) * h, z + (lz - uz) * h, 0f, 1f, r, g, b, alpha);
        quadVertex(buf, m, x + (-lx - ux) * h, y + (-ly - uy) * h, z + (-lz - uz) * h, 1f, 1f, r, g, b, alpha);
        quadVertex(buf, m, x + (-lx + ux) * h, y + (-ly + uy) * h, z + (-lz + uz) * h, 1f, 0f, r, g, b, alpha);
        BufferUploader.drawWithShader(buf.end());
    }

    private static void renderScene(PoseStack pose, Camera cam, Vec3 camera, Scene scene, double t) {
        Vec3 dir = scene.forward;

        // ---- Синий: вспышка в руке и закрученные кольца (0,5–1,1 с)
        Vec3 blue = bluePos(scene, t);
        if (blue != null) {
            float br = blueRadius(t);
            if (t < T_BLUE_THROW + 2) {
                float flash = (float) (smooth((t - T_BLUE_SPAWN) / 2.0) * (1.0 - smooth((t - (T_BLUE_SPAWN + 3)) / 5.0)));
                billboard(pose, cam, camera, blue, 2.6f * flash + 0.6f, 0f, TEX_BLOOM, 0.35f, 0.7f, 1.0f, 0.9f * flash, true);
                float ringA = (float) smooth((t - (T_BLUE_SPAWN + 1)) / 3.0);
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                HollowPurpleReferenceClient.mpAdditiveBlend();
                for (int i = 0; i < 3; i++) {
                    Vec3 normal = dir.add(scene.right.scale((i - 1) * 0.25)).add(0.0, 0.2 * (i - 1), 0.0).normalize();
                    HollowPurpleReferenceClient.mpRing(pose, camera, blue, normal, 0.35f + i * 0.14f, 0.025f,
                            t * (18.0 + i * 6.0), 4, 5, ringA * (0.75f - i * 0.18f), 0.12f, 61.0 + i * 7.0, true);
                }
            }
            renderOrb(pose, cam, camera, blue, br, true, t);
        }

        // ---- Красный
        Vec3 red = redPos(scene, t);
        if (red != null) {
            renderOrb(pose, cam, camera, red, redRadius(t), false, t);
        }

        // ---- Тёмный вихрь вокруг игрока (2,8–3,2 с)
        if (t >= T_VORTEX - 1 && t < T_RED_FACE + 4) {
            float in = (float) smooth((t - (T_VORTEX - 1)) / 4.0);
            float out = 1.0f - (float) smooth((t - (T_RED_FACE - 2)) / 6.0);
            Vec3 chest = scene.atP(0.0, 0.0, 1.15, t);
            float size = 5.6f * (0.8f + 0.2f * in);
            billboard(pose, cam, camera, chest, size, (float) (t * 0.22), TEX_VORTEX, 1f, 1f, 1f, in * out, false);
            billboard(pose, cam, camera, chest, size * 0.82f, (float) (-t * 0.31 + 1.0), TEX_VORTEX, 1f, 1f, 1f, in * out * 0.55f, false);
        }

        // ---- Выстрел Красного вверх: красный всплеск над головой (4,0–4,3 с)
        if (t >= T_RED_THROW - 2 && t < T_RED_THROW + 12) {
            float life = 1.0f - (float) smooth((t - (T_RED_THROW + 4)) / 8.0);
            float grow = (float) smooth((t - (T_RED_THROW - 2)) / 4.0);
            Vec3 top = scene.atP(0.05, 0.36, 2.45, t);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            for (int i = 0; i < 22; i++) {
                double a = i * 2.39996;
                double spread = 0.35 + (i % 5) * 0.12;
                Vec3 out = scene.forward.scale(Math.cos(a) * spread).add(scene.right.scale(Math.sin(a) * spread * 2.2))
                        .add(0.0, 0.55 + (i % 3) * 0.2, 0.0).normalize();
                double len = (1.2 + (i % 4) * 0.55) * grow;
                HollowPurpleReferenceClient.mpArc(pose, camera, top, top.add(out.scale(len)), 0.03f + (i % 3) * 0.012f,
                        7, t, 101.0 + i * 7.0, i % 4 == 0 ? 2 : 1, life * 0.85f, 0.18f);
            }
            billboard(pose, cam, camera, top, 3.0f * grow, 0f, TEX_BLOOM, 1.0f, 0.15f, 0.25f, life * 0.8f, true);
        }

        // ---- Толчок от земли при прыжке (4,6 с)
        if (t >= T_JUMP && t < T_JUMP + 12) {
            float k = (float) ((t - T_JUMP) / 12.0);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            for (int i = 0; i < 2; i++) {
                HollowPurpleReferenceClient.mpRing(pose, camera, scene.at(0.0, 0.0, 0.08 + i * 0.05), new Vec3(0.0, 1.0, 0.0),
                        0.6f + k * (3.5f + i * 1.5f), 0.06f, t * 4.0, 10, 11, (1.0f - k) * (0.8f - i * 0.3f), 0.1f, 91.0 + i, true);
            }
        }

        // ---- Слияние: фиолетовое ядро в точке схождения (7,4–7,6 с)
        if (t >= T_MANGA_B - 6 && t < T_MANGA_B + 1) {
            double g = smooth((t - (T_MANGA_B - 6)) / 6.0);
            Vec3 m = mergePoint(scene);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpPurple(pose, camera, m, dir, (float) (0.12 + 0.38 * g), 1.0f, t, 1.0f, 1.0f);
            billboard(pose, cam, camera, m, (float) (1.0 + 2.5 * g), 0f, TEX_BLOOM, 0.85f, 0.35f, 1.0f, (float) g, true);
        }

        // ---- Взрыв вокруг игрока: купол до 100 блоков (9,9–10,2 с)
        if (t >= T_EXPLODE && t < T_EXPLODE + 70) {
            double x = Mth.clamp((t - T_EXPLODE) / 16.0, 0.0, 1.0);
            float radius = (float) (2.0 + (RADIUS - 2.0) * (1.0 - Math.pow(1.0 - x, 3.0)));
            float life = 1.0f - (float) smooth((t - (T_EXPLODE + 24)) / 46.0);
            Vec3 center = scene.at(0.0, 0.0, JUMP_HEIGHT + 1.0);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAlphaBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, radius, 22, 34, 6, 7, 0.40f * life, 0.05f, t * 0.4, 401.0);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, radius * 1.015f, 22, 34, 9, 10, 0.32f * life, 0.06f, t * 0.6, 419.0);
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, Math.min(radius, 8.0f) * 0.6f, 16, 24, 10, 11, 0.8f * life, 0.08f, t, 433.0);
            for (int i = 0; i < 3; i++) {
                HollowPurpleReferenceClient.mpRing(pose, camera, scene.at(0.0, 0.0, 0.5 + i * 2.0), new Vec3(0.0, 1.0, 0.0),
                        radius * (0.9f + i * 0.06f), Math.max(0.25f, radius * 0.03f), t * (3.0 + i), 9, 11,
                        life * (0.85f - i * 0.2f), 0.06f, 451.0 + i * 17.0, i > 0);
            }
            HollowPurpleReferenceClient.mpRing(pose, camera, center, scene.forward, radius * 0.55f,
                    Math.max(0.2f, radius * 0.02f), t * 5.0, 10, 11, life * 0.9f, 0.08f, 477.0, false);
        }
    }

    private static void renderOrb(PoseStack pose, Camera cam, Vec3 camera, Vec3 pos, float radius, boolean blue, double t) {
        if (radius <= 0.01f) return;
        float hr = blue ? 0.25f : 1.0f, hg = blue ? 0.55f : 0.12f, hb = blue ? 1.0f : 0.2f;
        if (radius < 0.17f) {
            // Маленький шарик энергии в руке
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpLimitless(pose, camera, pos, cam.getPosition().subtract(pos).normalize(), radius, 1.0f, t, blue);
            billboard(pose, cam, camera, pos, radius * 9.0f, 0f, TEX_BLOOM, hr, hg, hb, 0.85f, true);
            return;
        }
        drawTexturedOrb(pose, camera, pos, radius, blue ? TEX_BLUE_ORB : TEX_RED_ORB, (blue ? -1 : 1) * t * 0.013, 1.0f);
        billboard(pose, cam, camera, pos, radius * 4.2f, 0f, TEX_BLOOM, hr, hg, hb, 0.55f, true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        HollowPurpleReferenceClient.mpSphere(pose, camera, pos, radius * 1.12f, 14, 22, blue ? 4 : 1, blue ? 5 : 2, 0.18f, 0.04f, t, blue ? 9.0 : 3.0);
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

    // ------------------------------------------------------------------ sparks (свои мягкие частицы)

    private static void spark(Vec3 pos, Vec3 vel, float size, float r, float g, float b, int life, double drag) {
        if (SPARKS.size() > 1500) return;
        SPARKS.add(new Spark(pos, vel, size, r, g, b, life, drag));
    }

    private static Vec3 rndVec(double scale) {
        return new Vec3(RNG.nextGaussian() * scale, RNG.nextGaussian() * scale, RNG.nextGaussian() * scale);
    }

    private static void spawnSparks(Scene s) {
        int t = s.age;

        Vec3 blue = bluePos(s, t);
        if (blue != null) {
            if (t < T_BLUE_THROW) {
                for (int i = 0; i < 3; i++) {
                    spark(blue.add(rndVec(0.15)), rndVec(0.04), 0.12f, 0.35f, 0.75f, 1.0f, 10, 0.85);
                }
            } else if (t < T_BLUE_HOVER) {
                for (int i = 0; i < 4; i++) {
                    spark(blue.add(rndVec(0.12)), rndVec(0.02), 0.22f, 0.3f, 0.65f, 1.0f, 14, 0.9);
                }
            } else if (t < T_MANGA_B && t % 3 == 0) {
                spark(blue.add(rndVec(0.35)), rndVec(0.01), 0.09f, 0.4f, 0.8f, 1.0f, 12, 0.9);
            }
        }

        Vec3 red = redPos(s, t);
        if (red != null) {
            if (t < T_RED_THROW) {
                for (int i = 0; i < 2; i++) {
                    spark(red.add(rndVec(0.08)), rndVec(0.03), 0.08f, 1.0f, 0.12f, 0.2f, 9, 0.85);
                }
            } else if (t < T_RED_HOVER) {
                for (int i = 0; i < 4; i++) {
                    spark(red.add(rndVec(0.1)), rndVec(0.02), 0.2f, 1.0f, 0.15f, 0.22f, 14, 0.9);
                }
            } else if (t < T_MANGA_B && t % 3 == 0) {
                spark(red.add(rndVec(0.35)), rndVec(0.01), 0.09f, 1.0f, 0.2f, 0.25f, 12, 0.9);
            }
        }

        if (t >= T_RED_THROW && t < T_RED_THROW + 4) {
            Vec3 top = s.atP(0.05, 0.36, 2.45, t);
            for (int i = 0; i < 14; i++) {
                Vec3 v = rndVec(0.25).add(0.0, 0.35, 0.0);
                spark(top, v, 0.16f, 1.0f, 0.1f + RNG.nextFloat() * 0.3f, 0.25f, 16, 0.86);
            }
        }

        if (t == T_JUMP) {
            for (int i = 0; i < 40; i++) {
                double a = i / 40.0 * Math.PI * 2.0;
                Vec3 v = new Vec3(Math.cos(a) * 0.55, 0.05 + RNG.nextDouble() * 0.08, Math.sin(a) * 0.55);
                spark(s.at(0.0, 0.0, 0.1), v, 0.25f, 0.85f, 0.92f, 1.0f, 14, 0.82);
            }
        }

        if (t >= T_MERGE && t < T_MANGA_B) {
            Vec3 m = mergePoint(s);
            for (int i = 0; i < 3; i++) {
                Vec3 from = m.add(rndVec(1.4));
                spark(from, m.subtract(from).scale(0.18), 0.12f, 0.75f, 0.3f, 1.0f, 8, 1.0);
            }
        }

        if (t >= T_EXPLODE && t < T_EXPLODE + 8) {
            Vec3 c = s.at(0.0, 0.0, JUMP_HEIGHT + 1.0);
            for (int i = 0; i < 45; i++) {
                Vec3 v = rndVec(1.0).normalize().scale(1.4 + RNG.nextDouble() * 2.2);
                boolean white = RNG.nextInt(3) == 0;
                spark(c, v, 1.4f + RNG.nextFloat() * 1.6f, white ? 1.0f : 0.8f, white ? 0.9f : 0.25f, 1.0f, 22, 0.93);
            }
        }
    }

    private static void tickSparks() {
        Iterator<Spark> it = SPARKS.iterator();
        while (it.hasNext()) {
            Spark p = it.next();
            p.age++;
            if (p.age >= p.life) {
                it.remove();
                continue;
            }
            p.pos = p.pos.add(p.vel);
            p.vel = p.vel.scale(p.drag);
        }
    }

    private static void renderSparks(PoseStack pose, Camera cam, Vec3 camera, float pt) {
        if (SPARKS.isEmpty()) return;
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_BLOOM);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Spark p : SPARKS) {
            float k = (p.age + pt) / p.life;
            float a = (1.0f - k) * Math.min(1.0f, (p.age + pt) * 0.5f + 0.2f);
            double h = p.size * (1.0f - 0.4f * k);
            Vec3 c = p.pos.add(p.vel.scale(pt)).subtract(camera);
            double lx = leftV.x() * h, ly = leftV.y() * h, lz = leftV.z() * h;
            double ux = upV.x() * h, uy = upV.y() * h, uz = upV.z() * h;
            quadVertex(buf, m, c.x + lx + ux, c.y + ly + uy, c.z + lz + uz, 0f, 0f, p.r, p.g, p.b, a);
            quadVertex(buf, m, c.x + lx - ux, c.y + ly - uy, c.z + lz - uz, 0f, 1f, p.r, p.g, p.b, a);
            quadVertex(buf, m, c.x - lx - ux, c.y - ly - uy, c.z - lz - uz, 1f, 1f, p.r, p.g, p.b, a);
            quadVertex(buf, m, c.x - lx + ux, c.y - ly + uy, c.z - lz + uz, 1f, 0f, p.r, p.g, p.b, a);
        }
        BufferUploader.drawWithShader(buf.end());
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
            drawLocalOverlays(g, w, h, scene.age + pt);
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
            double dist = eye.distanceTo(other.at(0.0, 0.0, JUMP_HEIGHT));
            float proximity = (float) (1.0 - Mth.clamp((dist - 30.0) / 130.0, 0.0, 1.0));
            if (proximity <= 0.0f) continue;
            double t = other.age + pt;
            float in = (float) smooth((t - T_EXPLODE) / 6.0);
            float out = 1.0f - (float) smooth((t - (T_WHITE_FULL + 6)) / 30.0);
            flash = Math.max(flash, in * out * proximity * 0.92f);
        }
        if (flash > 0.005f) fillWhite(g, w, h, flash);
    }

    private static void drawLocalOverlays(GuiGraphics g, int w, int h, double t) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Полосы «кино» (3,9–8,7 с)
        if (t >= T_LETTERBOX && t < T_COSMOS + 10) {
            float in = (float) smooth((t - T_LETTERBOX) / 4.0);
            int bar = (int) (h * 0.12f * in);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
        }

        // Манга-кадры А: появление Красного (2,6–2,7 с)
        if (t >= T_MANGA_A && t < T_VORTEX) {
            boolean first = t < T_MANGA_A + 2;
            drawMangaFrame(g, w, h, first ? MANGA_A1 : MANGA_A2, t - T_MANGA_A);
            drawSilhouette(g, w, h, 0.02f, 0.0f, 0.02f, first ? 1.0f : 1.06f);
        }

        // Манга-кадры Б: слияние (7,7–8,1 с)
        if (t >= T_MANGA_B && t < T_COSMOS) {
            int idx = Math.min(MANGA_B.length - 1, (int) ((t - T_MANGA_B) / 2.0));
            drawMangaFrame(g, w, h, MANGA_B[idx], t - T_MANGA_B);
            if (idx == MANGA_B.length - 1) drawSilhouette(g, w, h, 0.02f, 0.0f, 0.04f, 1.0f);
        }

        // Белое свечение разрастается (8,2–9,3 с)
        if (t >= T_COSMOS + 4 && t < T_FLASH) {
            float grow = (float) smooth((t - (T_COSMOS + 4)) / (double) (T_FLASH - T_COSMOS - 4));
            int size = (int) (h * (0.7f + 2.6f * grow));
            int cx = (int) (w * 0.68f), cy = (int) (h * 0.45f);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, Math.min(1.0f, 0.5f + grow * 0.6f));
            g.blit(TEX_BLOOM, cx - size / 2, cy - size / 2, size, size, 0.0f, 0.0f, 512, 512, 512, 512);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        // Белая вспышка с розовым силуэтом (9,4–9,6 с)
        if (t >= T_FLASH && t < T_EXPLODE) {
            fillWhite(g, w, h, 0.93f);
            drawSilhouette(g, w, h, 1.0f, 0.55f, 0.9f, 0.95f);
        }

        // Короткий удар света в момент взрыва
        if (t >= T_EXPLODE && t < T_EXPLODE + 5) {
            fillWhite(g, w, h, 1.0f - (float) ((t - T_EXPLODE) / 5.0));
        }

        // Белый экран, пока дорезается кратер
        if (t >= T_WHITE_IN) {
            float a = (float) smooth((t - T_WHITE_IN) / (double) (T_WHITE_FULL - T_WHITE_IN));
            fillWhite(g, w, h, a);
        }
    }

    private static void drawMangaFrame(GuiGraphics g, int w, int h, ResourceLocation frame, double k) {
        // Лёгкий наезд и дрожь, как у вставок в референсе
        float scale = 1.03f + 0.025f * (float) k;
        int dw = (int) (w * scale), dh = (int) (h * scale);
        int ox = (w - dw) / 2 + (int) (Math.sin(k * 9.1) * 2.0);
        int oy = (h - dh) / 2 + (int) (Math.cos(k * 7.3) * 2.0);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        g.blit(frame, ox, oy, dw, dh, 0.0f, 0.0f, 1920, 1080, 1920, 1080);
    }

    /** Силуэт игрока в текущей позе (его настоящая модель), закрашенный одним цветом. */
    private static void drawSilhouette(GuiGraphics g, int w, int h, float r, float gr, float b, float zoom) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        float bodyRot = p.yBodyRot, bodyRotO = p.yBodyRotO, yRot = p.getYRot(), yRotO = p.yRotO;
        float xRot = p.getXRot(), xRotO = p.xRotO, head = p.yHeadRot, headO = p.yHeadRotO;
        p.yBodyRot = 180.0f;
        p.yBodyRotO = 180.0f;
        p.setYRot(180.0f);
        p.yRotO = 180.0f;
        p.setXRot(0.0f);
        p.xRotO = 0.0f;
        p.yHeadRot = 180.0f;
        p.yHeadRotO = 180.0f;

        g.flush();
        RenderSystem.setShaderColor(r, gr, b, 1.0f);
        int scale = (int) (h * 0.62f * zoom);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        InventoryScreen.renderEntityInInventory(g, w / 2, (int) (h * 1.32f), scale, pose, null, p);
        g.flush();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        p.yBodyRot = bodyRot;
        p.yBodyRotO = bodyRotO;
        p.setYRot(yRot);
        p.yRotO = yRotO;
        p.setXRot(xRot);
        p.xRotO = xRotO;
        p.yHeadRot = head;
        p.yHeadRotO = headO;
    }

    private static void fillWhite(GuiGraphics g, int w, int h, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        if (a <= 0) return;
        g.fill(0, 0, w, h, (a << 24) | 0x00FFFFFF);
    }
}
