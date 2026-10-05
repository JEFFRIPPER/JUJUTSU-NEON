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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
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
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kira.jujutsuneon.MaximumPurple.*;

/**
 * Кат-сцена Максимального Фиолетового — 20,4 с под саундтрек (24,2 с), покадрово по референсу.
 *
 * Время кат-сцены идёт по реальным часам (с паузой игры), а не по тикам — так картинка
 * не уезжает от музыки, даже если игра подлагивает. 1 «тик» кат-сцены = 0,05 с звука.
 * Пока играет музыка, у владельца выключены все остальные звуки Minecraft. Остальные
 * игроки рядом слышат ту же музыку из точки техники и ничего не теряют.
 *
 * Камера — клиентская сущность-маркер. В начале игрок всегда крупным планом, камера
 * с него не уходит: Синий и Красный просто вылетают из кадра.
 *
 *   0–12     наезд снизу спереди, разворот, рука выходит вперёд ладонью вверх
 *   14       Синий вспыхивает в ладони (синяя вспышка на весь экран)
 *   14–33    персонаж смотрит на Синего; камера медленно облетает
 *   34–41    рука проводит четверть круга по диагонали (~45°) и отпускает Синего
 *   40–46    боковые полосы, белая вспышка, синий взрыв
 *   41–55    Синий по спирали уходит ввысь (за кадр), камера на игроке
 *   56–65    спокойный план со спины, рука поднята и согнута, в кулаке у лица загорается Красный
 *   66–67    манга-кадр
 *   69–72    Красный вылетает из поднятой руки вверх, красные ленты разрядов
 *   73–98    общий план: Красный поднимается со следом, игрок внизу
 *   100–126  небо: Синий дугой с длинным следом, Красный висит
 *   128–158  Синий подлетает к Красному и кружит, пролетает сквозь камеру
 *   160–226  погоня вокруг фиолетово-белого электрического ядра
 *   228–282  снизу: орбита становится кольцом, игрок поднимается к центру; кольцо раскрывается
 *   283–304  свет гаснет, ровно секунда темноты
 *   305–331  «космос»: Красный и Синий, молнии между ними, ядро растёт
 *   332–335  четыре аниме-вставки
 *   336–349  один большой фиолетовый шар, белый закрученный взрыв
 *   350–358  крупно спереди: руки скрещены перед лицом, огромная спираль за спиной
 *   359–364  выпрямляется: руки в стороны, голова назад; 362 — главный удар
 *   365–368  манга-кадр с рукой, кадр с белым «X»
 *   369–408  белый экран (вырезается кратер), потом игрок висит в воздухе в режиме полёта
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MaximumPurpleClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_BLOOM = tex("max_purple_bloom");
    private static final ResourceLocation TEX_RED_ORB = tex("max_purple_red_orb");
    private static final ResourceLocation TEX_BLUE_ORB = tex("max_purple_blue_orb");
    private static final ResourceLocation TEX_SPACE = tex("max_purple_space");
    private static final ResourceLocation TEX_SWIRL = tex("max_purple_swirl");
    private static final ResourceLocation TEX_SPLASH = tex("max_purple_splash");
    private static final ResourceLocation TEX_HATCH = tex("max_purple_hatch");
    private static final ResourceLocation MANGA_A1 = tex("max_purple_manga_a1");
    private static final ResourceLocation MANGA_A2 = tex("max_purple_manga_a2");
    private static final ResourceLocation MANGA_C1 = tex("max_purple_manga_c1");
    private static final ResourceLocation MANGA_C2 = tex("max_purple_manga_c2");
    private static final ResourceLocation[] INSERTS = {
            tex("max_purple_insert_1"), tex("max_purple_insert_2"), tex("max_purple_insert_3"), tex("max_purple_insert_4")
    };

    private static final ResourceLocation THEME = new ResourceLocation(JujutsuNeonMod.MODID, "max_purple_theme");
    private static final ResourceLocation THEME_WORLD = new ResourceLocation(JujutsuNeonMod.MODID, "max_purple_theme_world");

    private static final int FADE_OUT_TICKS = 20;
    private static final int LOCAL_TIMEOUT_TICKS = 1800;

    /** Ядро (фиолетовая молния) — над головой поднявшегося игрока. */
    private static final double CORE_U = RISE_HEIGHT + 3.4;
    private static final double CHASE_R = 3.4;
    private static final double OPEN_R = 3.2;
    private static final double OMEGA_CHASE = Math.PI * 2.0 / 15.0;
    private static final double OMEGA_RING = Math.PI * 2.0 / 10.0;

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final List<Decal> DECALS = new ArrayList<>();
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
    /** Саундтрек у владельца; пока он играет, остальные звуки выключены. */
    private static SoundInstance themeInstance;
    /** Скрытый интерфейс (F1) на время кат-сцены включаем обратно — иначе не видно кадров и белого экрана. */
    private static Boolean savedHideGui;

    private MaximumPurpleClient() {
    }

    private static final class Scene {
        final UUID ownerId;
        final ClientLevel level;
        final Vec3 feet;
        final float yaw;
        final Vec3 forward;
        final Vec3 right;
        /** Время кат-сцены в тиках (по реальным часам). */
        double clock;
        long lastNanos;
        int spawned = -1;
        /** Музыка у наблюдателя (из точки техники). */
        SoundInstance worldTheme;
        boolean ended;
        double endedAt;

        Scene(UUID ownerId, ClientLevel level, Vec3 feet, float yaw) {
            this.ownerId = ownerId;
            this.level = level;
            this.feet = feet;
            this.yaw = yaw;
            double rad = Math.toRadians(yaw);
            this.forward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
            this.right = new Vec3(-forward.z, 0.0, forward.x);
            this.lastNanos = System.nanoTime();
        }

        void advance(boolean paused) {
            long now = System.nanoTime();
            double dt = (now - lastNanos) / 5.0E7;
            lastNanos = now;
            if (!paused) clock += Mth.clamp(dt, 0.0, 20.0);
        }

        /** Время в кадре; partial не нужен — часы и так непрерывные. */
        double t() {
            return clock;
        }

        /** Точка относительно места старта: f — вперёд, r — вправо от игрока, u — вверх. */
        Vec3 at(double f, double r, double u) {
            return new Vec3(
                    feet.x + forward.x * f + right.x * r,
                    feet.y + u,
                    feet.z + forward.z * f + right.z * r);
        }

        /** Точка относительно игрока в момент t (с учётом подъёма). */
        Vec3 atP(double f, double r, double u, double t) {
            return at(f, r, u + heightAt(t));
        }

        Vec3 off(Vec3 base, double f, double r, double u) {
            return new Vec3(base.x + forward.x * f + right.x * r, base.y + u, base.z + forward.z * f + right.z * r);
        }

        Vec3 core() {
            return at(0.0, 0.0, CORE_U);
        }
    }

    private static final class Spark {
        Vec3 pos;
        Vec3 vel;
        final float size;
        final float r, g, b;
        final int life;
        final double drag;
        final double gravity;
        final boolean splash;
        int age;

        Spark(Vec3 pos, Vec3 vel, float size, float r, float g, float b, int life, double drag, double gravity, boolean splash) {
            this.pos = pos;
            this.vel = vel;
            this.size = size;
            this.r = r;
            this.g = g;
            this.b = b;
            this.life = life;
            this.drag = drag;
            this.gravity = gravity;
            this.splash = splash;
        }
    }

    /** Плоская брызга на земле. */
    private static final class Decal {
        final Vec3 pos;
        final float size;
        final float rot;
        final float r, g, b;
        final int life;
        int age;

        Decal(Vec3 pos, float size, float rot, float r, float g, float b, int life) {
            this.pos = pos;
            this.size = size;
            this.rot = rot;
            this.r = r;
            this.g = g;
            this.b = b;
            this.life = life;
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
        // Живая анимация тела (Player Animator + сгибы bendy-lib) — у владельца, видят все.
        if (mc.level.getPlayerByUUID(ownerId) instanceof AbstractClientPlayer owner) {
            MaximumPurpleAnimation.play(owner);
        }

        if (ownerId.equals(mc.player.getUUID())) {
            stopLocalCamera(mc);
            local = scene;
            fadeOut = 0;
            savedMayfly = mc.player.getAbilities().mayfly;
            savedFlying = mc.player.getAbilities().flying;
            savedHideGui = mc.options.hideGui;
            mc.options.hideGui = false;
            startLocalCamera(mc);
            JujutsuNeonMod.ClientForgeEvents.startAnim("MAX_PURPLE", ANIM_TICKS);
            startTheme(mc);
        } else {
            // Остальные слышат ту же музыку из точки техники.
            Vec3 p = scene.at(0.0, 0.0, 10.0);
            scene.worldTheme = new SimpleSoundInstance(THEME_WORLD, SoundSource.PLAYERS, 6.0f, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                    p.x, p.y, p.z, false);
            mc.getSoundManager().play(scene.worldTheme);
        }
    }

    static void onEnd(UUID ownerId, boolean completed) {
        Scene scene = SCENES.get(ownerId);
        if (scene == null) return;
        scene.ended = true;
        scene.endedAt = scene.clock;
        stopAnimation(scene);
        Minecraft mc = Minecraft.getInstance();
        // Прервали — музыка обрывается. Дошли до конца — она сама затихает до 24,2 с.
        if (!completed) stopWorldTheme(mc, scene);
        if (scene == local) {
            stopLocalCamera(mc);
            fadeOut = FADE_OUT_TICKS;
            local = null;
            if (!completed) stopTheme(mc);
            restoreLocal(mc);
            // Игрок остаётся там, куда поднялся, — в режиме полёта.
            if (completed && mc.player != null) JujutsuNeonFlightClient.beginFlightForTechnique();
        }
    }

    private static void restoreLocal(Minecraft mc) {
        if (savedHideGui != null) {
            mc.options.hideGui = savedHideGui;
            savedHideGui = null;
        }
        if (mc.player == null) return;
        mc.player.getAbilities().mayfly = savedMayfly;
        mc.player.getAbilities().flying = savedMayfly && savedFlying;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.fallDistance = 0.0f;
    }

    private static void stopWorldTheme(Minecraft mc, Scene scene) {
        if (scene.worldTheme != null) {
            mc.getSoundManager().stop(scene.worldTheme);
            scene.worldTheme = null;
        }
    }

    private static void startTheme(Minecraft mc) {
        stopTheme(mc);
        // Все посторонние звуки — сразу выключить; дальше новые глушит onPlaySound.
        mc.getMusicManager().stopPlaying();
        mc.getSoundManager().stop();
        themeInstance = new SimpleSoundInstance(THEME, SoundSource.MASTER, 1.0f, 1.0f,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0, true);
        mc.getSoundManager().play(themeInstance);
    }

    private static void stopTheme(Minecraft mc) {
        if (themeInstance != null) {
            mc.getSoundManager().stop(themeInstance);
            themeInstance = null;
        }
    }

    /** Глушим всё, кроме саундтрека, пока идёт кат-сцена владельца или пока играет музыка. */
    private static boolean isMuting() {
        if (isLocalActive()) return true;
        if (themeInstance == null) return false;
        if (Minecraft.getInstance().getSoundManager().isActive(themeInstance)) return true;
        themeInstance = null;
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null || !isMuting()) return;
        ResourceLocation id = sound.getLocation();
        if (THEME.equals(id) || THEME_WORLD.equals(id)) return;
        event.setSound(null);
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

    private static void stopAnimation(Scene scene) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getPlayerByUUID(scene.ownerId) instanceof AbstractClientPlayer owner) {
            MaximumPurpleAnimation.stop(owner);
        }
    }

    private static void resetAll() {
        Minecraft mc = Minecraft.getInstance();
        stopLocalCamera(mc);
        stopTheme(mc);
        if (savedHideGui != null) {
            mc.options.hideGui = savedHideGui;
            savedHideGui = null;
        }
        for (Scene scene : SCENES.values()) {
            stopAnimation(scene);
            stopWorldTheme(mc, scene);
        }
        SCENES.clear();
        SPARKS.clear();
        DECALS.clear();
        local = null;
        fadeOut = 0;
    }

    // ------------------------------------------------------------------ math

    private static double clamp01(double v) {
        return Mth.clamp(v, 0.0, 1.0);
    }

    private static double smooth(double value) {
        double t = clamp01(value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double easeOut(double value) {
        double t = clamp01(value);
        return 1.0 - Math.pow(1.0 - t, 3.0);
    }

    private static double easeIn(double value) {
        double t = clamp01(value);
        return t * t * t;
    }

    /** 0..1 в окне [a, b]. */
    private static double win(double t, double a, double b) {
        return clamp01((t - a) / (b - a));
    }

    /** Плавно появиться за fin тиков после a и исчезнуть за fout тиков до b. */
    private static float env(double t, double a, double b, double fin, double fout) {
        if (t < a || t > b) return 0.0f;
        return (float) (smooth((t - a) / fin) * (1.0 - smooth((t - (b - fout)) / fout)));
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double k) {
        return a.add(b.subtract(a).scale(k));
    }

    private static Vec3 bezier(Vec3 a, Vec3 c, Vec3 b, double k) {
        return lerp(lerp(a, c, k), lerp(c, b, k), k);
    }

    /** Горизонтальная окружность: angle 0 = вправо от игрока, PI/2 = вперёд. */
    private static Vec3 circle(Scene s, double angle, double radius, double squash) {
        return s.right.scale(Math.cos(angle) * radius).add(s.forward.scale(Math.sin(angle) * radius * squash));
    }

    /** Перпендикулярная спираль вокруг пути полёта, радиус сходится к нулю. */
    private static Vec3 spiralAround(Scene s, Vec3 axis, double p, double turns, double radius0) {
        Vec3 dir = axis.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : axis.normalize();
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-4) side = s.right;
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();
        double r = radius0 * Math.pow(1.0 - p, 1.3);
        double a = turns * Math.PI * 2.0 * (1.0 - (1.0 - p) * (1.0 - p));
        return side.scale(Math.cos(a) * r).add(up.scale(Math.sin(a) * r));
    }

    // ------------------------------------------------------------------ orb paths

    /** Угол погони: сначала 1 оборот за 0,75 с, в кольце — ещё быстрее. */
    private static double chaseAngle(double t) {
        double a = (Math.min(t, T_RING) - T_CHASE) * OMEGA_CHASE;
        if (t > T_RING) a += (Math.min(t, T_RING_OPEN + 16) - T_RING) * OMEGA_RING;
        if (t > T_RING_OPEN + 16) a += (t - T_RING_OPEN - 16) * OMEGA_RING * 0.2;
        return a;
    }

    /** Сдвиг Синего относительно Красного: в погоне ~150°, в кольце — ровно напротив. */
    private static double chaseGap(double t) {
        double g = Math.PI * 0.84 + 0.22 * Math.sin(t * 0.09);
        return Mth.lerp(smooth(win(t, T_RING - 8, T_RING + 12)), g, Math.PI);
    }

    private static Vec3 orbitPos(Scene s, double t, boolean blue) {
        double a = chaseAngle(t) + (blue ? chaseGap(t) : 0.0);
        double ringK = smooth(win(t, T_RING - 8, T_RING + 12));
        double radius = Mth.lerp(ringK, CHASE_R + 0.35 * Math.sin(t * 0.13 + (blue ? 1.7 : 0.0)), 3.0);
        double squash = Mth.lerp(ringK, 0.85, 1.0);
        double bob = (1.0 - ringK) * 0.45 * Math.sin(a * 2.0 + (blue ? 1.0 : 0.0));
        Vec3 c = s.core().add(circle(s, a, radius, squash)).add(0.0, bob, 0.0);
        // Кольцо раскрывается: Красный — вправо от игрока (на экране слева), Синий — влево.
        double open = smooth(win(t, T_RING_OPEN, T_RING_OPEN + 16));
        if (open > 0.0) {
            Vec3 side = s.core().add(s.right.scale(blue ? -OPEN_R : OPEN_R))
                    .add(0.0, 0.12 * Math.sin(t * 0.17 + (blue ? 2.0 : 0.0)), 0.0);
            c = lerp(c, side, open);
        }
        return c;
    }

    /** Шары в «космосе»: по бокам, медленно сходятся, в конце — рывком в центр. */
    private static Vec3 spacePos(Scene s, double t, boolean blue) {
        double approach = 0.5 * smooth(win(t, T_SPACE, T_INSERTS - 4)) + 0.5 * easeIn(win(t, T_INSERTS - 4, T_MERGED));
        double side = Mth.lerp(approach, OPEN_R, 0.25);
        return s.core().add(s.right.scale(blue ? -side : side))
                .add(0.0, 0.18 * Math.sin(t * 0.21 + (blue ? 1.3 : 0.0)), 0.0);
    }

    /** Синий в ладони, пока персонаж на него смотрит. */
    private static final int T_SWEEP = 34;
    private static final int T_RELEASE = 41;

    /**
     * Толчок Синего: рука проводит по воздуху четверть круга по диагонали (плоскость наклонена
     * на 45° — снизу-справа вверх-влево) и отпускает шар. theta от -45° до +45°.
     */
    private static Vec3 sweepPos(Scene s, double theta, double t) {
        double c = Math.cos(theta) * 0.62, sn = Math.sin(theta) * 0.62;
        // F = вперёд, U' = (вверх - вправо) / sqrt(2)
        return s.atP(0.08 + c, 0.32 - sn * 0.7071, 1.62 + sn * 0.7071, t);
    }

    /** Позиция Синего или null. */
    private static Vec3 bluePos(Scene s, double t) {
        if (t < T_BLUE_SPAWN - 1 || t >= T_MERGED) return null;
        if (t < T_SWEEP) {
            return sweepPos(s, -Math.PI / 4.0, t).add(0.0, 0.02 * Math.sin(t * 0.35), 0.0);
        }
        if (t < T_RELEASE) {
            double k = win(t, T_SWEEP, T_RELEASE);
            return sweepPos(s, -Math.PI / 4.0 + Math.PI / 2.0 * k * k, t);
        }
        Vec3 top = sweepPos(s, Math.PI / 4.0, T_RELEASE);
        Vec3 b1 = s.at(-2.5, -7.0, 25.0);
        if (t < T_SKY) {
            double p = win(t, T_RELEASE, 66);
            Vec3 base = lerp(top, b1, easeOut(p));
            Vec3 pos = base.add(spiralAround(s, b1.subtract(top), p, 3.0, 1.7 * smooth(win(t, T_RELEASE, 45))));
            return pos.add(0.0, 0.08 * Math.sin(t * 0.2) * smooth(win(t, 66, 72)), 0.0);
        }

        Vec3 b2 = s.at(3.5, 1.0, 29.5);
        if (t < T_APPROACH) {
            return bezier(b1, s.at(1.0, -3.0, 34.0), b2, smooth(win(t, T_SKY, T_APPROACH)));
        }

        // Подлетает к Красному и кружит вокруг него, потом уходит в погоню вокруг ядра.
        Vec3 red = orbitPos(s, T_CHASE, false);
        Vec3 rel = b2.subtract(red);
        double phi0 = Math.atan2(rel.dot(s.forward), rel.dot(s.right));
        double p = win(t, T_APPROACH, T_CHASE + 10);
        double phi = phi0 + Math.PI * 2.0 * 1.3 * (1.0 - Math.pow(1.0 - p, 1.6));
        double rad = Mth.lerp(smooth(p), Math.sqrt(rel.x * rel.x + rel.z * rel.z), 1.5);
        Vec3 around = red.add(circle(s, phi, rad, 1.0)).add(0.0, rel.y * (1.0 - smooth(p)), 0.0);
        if (t < 150) return around;
        if (t < T_SPACE - 2 && t >= T_CHASE + 10) return orbitPos(s, t, true);
        if (t < T_SPACE - 2) return lerp(around, orbitPos(s, t, true), smooth(win(t, 150, T_CHASE + 10)));
        return spacePos(s, t, true);
    }

    private static float blueRadius(double t) {
        if (t < T_VORTEX) return (float) (0.05 + 0.10 * smooth(win(t, T_BLUE_SPAWN - 1, T_VORTEX)));
        if (t < T_RELEASE) return 0.16f;
        if (t < T_SKY) return (float) (0.16 + 0.16 * easeOut(win(t, T_RELEASE, 60)));
        if (t < T_SPACE) return (float) (0.32 + 0.06 * smooth(win(t, T_APPROACH, T_CHASE)));
        return 0.42f;
    }

    /** Позиция Красного или null. */
    private static Vec3 redPos(Scene s, double t) {
        if (t < T_RED_GLOW || t >= T_MERGED) return null;
        // Кулак поднятой согнутой руки — на уровне головы рядом с лицом.
        Vec3 top = s.atP(0.32, 0.30, 1.9, t);
        if (t < T_RED_FIRE) return top;

        Vec3 park = orbitPos(s, T_CHASE, false);
        if (t < T_CHASE) {
            double p = win(t, T_RED_FIRE, 104);
            Vec3 pos = lerp(top, park, easeOut(p));
            // лёгкое «виляние» на взлёте — след красиво изгибается
            pos = pos.add(s.right.scale(0.6 * Math.sin(p * 9.0) * (1.0 - p))).add(s.forward.scale(0.4 * Math.sin(p * 7.0) * (1.0 - p)));
            return pos.add(0.0, 0.08 * Math.sin(t * 0.2 + 1.7) * smooth(win(t, 104, 112)), 0.0);
        }
        if (t < T_SPACE - 2) return orbitPos(s, t, false);
        return spacePos(s, t, false);
    }

    private static float redRadius(double t) {
        if (t < T_RED_FIRE) return (float) (0.03 + 0.07 * smooth(win(t, T_RED_GLOW, 66)) + 0.01 * Math.sin(t * 1.7));
        if (t < T_CHASE) return (float) (0.10 + 0.22 * easeOut(win(t, 69, 90)));
        if (t < T_SPACE) return (float) (0.32 + 0.06 * smooth(win(t, T_CHASE, T_CHASE + 20)));
        return 0.42f;
    }

    /** Огромная фиолетовая сфера — всегда за спиной игрока (17,5–18,2 с). */
    private static Vec3 bigSpherePos(Scene s, double t) {
        return s.atP(-5.0, 0.0, 1.6, t);
    }

    // ------------------------------------------------------------------ ticks

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!SCENES.isEmpty() || cameraEntity != null || !SPARKS.isEmpty() || themeInstance != null) resetAll();
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
        tickParticles();

        boolean paused = mc.isPaused();
        Iterator<Map.Entry<UUID, Scene>> it = SCENES.entrySet().iterator();
        while (it.hasNext()) {
            Scene scene = it.next().getValue();
            if (scene.level != mc.level) {
                stopAnimation(scene);
                stopWorldTheme(mc, scene);
                if (scene == local) {
                    stopLocalCamera(mc);
                    stopTheme(mc);
                    restoreLocal(mc);
                    local = null;
                }
                it.remove();
                continue;
            }

            scene.advance(paused);
            int now = (int) Math.floor(scene.clock);
            int budget = 4;
            while (scene.spawned < now && budget-- > 0) {
                scene.spawned++;
                spawnSparks(scene, scene.spawned);
            }
            if (scene.spawned < now) scene.spawned = now;

            if (scene == local) {
                camEyeOld = camEye;
                camEye += (0.0f - camEye) * 0.5f;
                if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (mc.getCameraEntity() != cameraEntity && cameraEntity != null) mc.setCameraEntity(cameraEntity);
                mc.getToasts().clear();
                if (mc.options.hideGui) mc.options.hideGui = false;

                // Игрок идёт по траектории кат-сцены: стоит, потом плавно поднимается на 24 блока.
                Vec3 target = scene.feet.add(0.0, heightAt(scene.clock), 0.0);
                mc.player.setPos(target.x, target.y, target.z);
                mc.player.setDeltaMovement(Vec3.ZERO);
                // Камера не у игрока — сам клиент позицию не шлёт; без этого на сервере
                // после кат-сцены «moved too quickly» и рывок вниз.
                if (cameraEntity != null) DomainExpansionClient.syncPosition(mc);

                if (scene.clock > LOCAL_TIMEOUT_TICKS) onEnd(scene.ownerId, false);
            }

            boolean expired = scene.ended
                    ? scene.clock - scene.endedAt > 60
                    : scene != local && scene.clock > ANIM_TICKS + 80;
            if (expired) {
                stopAnimation(scene);
                it.remove();
            }
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

    private static final int A_PLAYER = 0;
    private static final int A_GROUND = 1;
    private static final int A_CORE = 2;
    private static final int A_RED = 3;
    private static final int A_BLUE = 4;

    /**
     * Ключ камеры: позиция и точка взгляда — смещения (вперёд, вправо, вверх) от «якоря»
     * (игрок, земля, ядро, Красный, Синий). Между ключами — сплайн Катмулла–Рома.
     * cut = жёсткая склейка: с этого ключа начинается новый план.
     */
    private record CamKey(int t, int pa, double cf, double cr, double cu,
                          int ta, double tf, double tr, double tu, float fov, float roll, boolean cut) {
    }

    private static CamKey ck(int t, int pa, double cf, double cr, double cu,
                             int ta, double tf, double tr, double tu, float fov, float roll, boolean cut) {
        return new CamKey(t, pa, cf, cr, cu, ta, tf, tr, tu, fov, roll, cut);
    }

    private static final CamKey[] CAM = {
            // 0–0,6 с: быстрый наезд, камера низко спереди; игрок поднимает руку
            ck(0, A_PLAYER, 6.2, 0.6, 1.5, A_PLAYER, 0.0, 0.0, 1.2, 64f, 0f, true),
            ck(3, A_PLAYER, 2.3, -0.3, 0.75, A_PLAYER, 0.0, 0.05, 1.45, 72f, 0f, false),
            ck(12, A_PLAYER, 1.85, -0.55, 0.55, A_PLAYER, 0.0, 0.2, 1.75, 76f, -3f, false),
            // 0,7 с: вспышка Синего
            ck(14, A_PLAYER, 2.4, -1.1, 0.95, A_PLAYER, 0.15, 0.2, 1.45, 82f, -6f, true),
            // 0,8–1,9 с: вихрь — камера медленно облетает игрока
            ck(16, A_PLAYER, 2.5, -1.2, 1.0, A_PLAYER, 0.0, 0.0, 1.05, 80f, -5f, false),
            ck(24, A_PLAYER, 2.7, 0.2, 0.95, A_PLAYER, 0.0, 0.0, 1.05, 78f, 0f, false),
            ck(32, A_PLAYER, 2.3, 1.4, 0.9, A_PLAYER, 0.0, 0.0, 1.1, 78f, 4f, false),
            ck(38, A_PLAYER, 1.9, 1.6, 0.8, A_PLAYER, 0.0, 0.0, 1.05, 80f, 5f, false),
            // 2,0–2,6 с: бросок — камера остаётся на игроке, Синий уходит из кадра вверх
            ck(40, A_PLAYER, 2.6, 1.3, 0.75, A_PLAYER, 0.0, 0.0, 1.4, 86f, 2f, false),
            ck(46, A_PLAYER, 3.1, 1.0, 0.7, A_PLAYER, 0.0, 0.0, 1.55, 82f, 0f, false),
            ck(55, A_PLAYER, 3.5, 0.6, 0.95, A_PLAYER, 0.0, 0.0, 1.35, 74f, 0f, false),
            // 2,8–3,25 с: спокойный план со спины с медленным наездом; кисть у лица
            ck(56, A_PLAYER, -2.6, -1.4, 1.95, A_PLAYER, 0.35, 0.1, 1.5, 64f, 0f, true),
            ck(65, A_PLAYER, -1.85, -1.0, 1.85, A_PLAYER, 0.35, 0.1, 1.55, 57f, 0f, false),
            // 3,45–3,6 с: выстрел — сзади-слева, рука поднята, Красный вылетает из неё
            ck(68, A_PLAYER, -1.6, -1.0, 1.75, A_PLAYER, 0.3, 0.25, 1.75, 70f, 0f, true),
            ck(72, A_PLAYER, -1.95, -1.2, 1.7, A_PLAYER, 0.3, 0.25, 1.8, 78f, 2f, false),
            // 3,7–4,9 с: общий план — Красный поднимается со следом, игрок внизу
            ck(73, A_GROUND, 22.0, -9.0, 4.0, A_GROUND, 0.0, 0.0, 6.0, 60f, 0f, true),
            ck(98, A_GROUND, 21.0, -8.5, 4.5, A_GROUND, 0.0, 0.0, 15.0, 60f, 0f, false),
            // 5,0–6,3 с: взгляд в небо — Синий летит дугой, Красный висит
            ck(100, A_GROUND, 11.0, -15.0, 19.0, A_CORE, 0.0, -2.5, 1.0, 66f, 0f, true),
            ck(126, A_GROUND, 9.5, -13.0, 20.0, A_CORE, 0.5, -0.5, 1.6, 64f, 0f, false),
            // 6,4–7,9 с: рядом с Красным, Синий подлетает и кружит; в 7,6 — сквозь камеру
            ck(128, A_RED, 3.0, -2.0, 1.6, A_RED, 0.0, 0.0, 0.0, 66f, -4f, true),
            ck(146, A_RED, 2.5, -1.5, 1.2, A_RED, 0.0, 0.0, 0.0, 70f, -2f, false),
            ck(152, A_BLUE, 0.9, 0.2, 0.4, A_BLUE, 0.0, 0.0, 0.0, 84f, 6f, false),
            ck(158, A_BLUE, 0.35, 0.05, 0.15, A_BLUE, 0.0, 0.0, 0.0, 92f, 10f, false),
            // 8,0–11,3 с: погоня — дальний план на уровне шаров, медленная панорама
            ck(160, A_CORE, 16.0, -5.5, -1.6, A_CORE, 0.0, 0.0, 0.0, 54f, 0f, true),
            ck(226, A_CORE, 15.0, 3.0, -1.0, A_CORE, 0.0, 0.0, -0.3, 52f, 0f, false),
            // 11,4–14,1 с: снизу — кольцо, игрок поднимается к центру, кольцо раскрывается
            ck(228, A_CORE, 4.6, -1.2, -5.2, A_CORE, 0.0, 0.0, 0.0, 76f, 0f, true),
            ck(238, A_GROUND, 12.0, 0.6, 12.0, A_GROUND, 0.0, 0.0, 23.0, 64f, 0f, false),
            ck(266, A_GROUND, 15.5, 0.4, 9.5, A_GROUND, 0.0, 0.0, 22.5, 58f, 0f, false),
            ck(283, A_GROUND, 15.0, 0.4, 10.0, A_GROUND, 0.0, 0.0, 23.0, 56f, 0f, false),
            // 15,25–16,55 с: «космос» — Красный слева, Синий справа, ядро растёт
            ck(304, A_CORE, 6.6, 0.0, -0.3, A_CORE, 0.0, 0.0, 0.0, 62f, -4f, true),
            ck(331, A_CORE, 5.0, 0.0, -0.15, A_CORE, 0.0, 0.0, 0.0, 58f, -2f, false),
            // 16,8–17,45 с: один большой фиолетовый шар, белый вихрь
            ck(336, A_CORE, 3.6, 0.0, 0.0, A_CORE, 0.0, 0.0, 0.0, 70f, 0f, true),
            ck(349, A_CORE, 1.9, 0.0, 0.0, A_CORE, 0.0, 0.0, 0.0, 82f, 8f, false),
            // 17,5–17,9 с: крупно спереди — руки скрещены перед лицом, огромная спираль за спиной
            ck(350, A_PLAYER, 1.55, -0.25, 1.75, A_PLAYER, 0.0, 0.0, 1.6, 60f, 0f, true),
            ck(358, A_PLAYER, 1.3, -0.2, 1.7, A_PLAYER, 0.0, 0.0, 1.6, 56f, 0f, false),
            // 17,95–18,2 с: выпрямляется — спереди чуть сверху, сфера за спиной
            ck(359, A_PLAYER, 3.0, 0.5, 2.1, A_PLAYER, 0.0, 0.0, 1.25, 72f, -4f, true),
            ck(368, A_PLAYER, 3.6, 0.7, 2.2, A_PLAYER, 0.0, 0.0, 1.2, 80f, -6f, false),
    };

    private static Vec3 anchor(Scene s, int a, double f, double r, double u, double t) {
        return switch (a) {
            case A_GROUND -> s.at(f, r, u);
            case A_CORE -> s.off(s.core(), f, r, u);
            case A_RED -> {
                Vec3 p = redPos(s, t);
                yield s.off(p != null ? p : s.core(), f, r, u);
            }
            case A_BLUE -> {
                Vec3 p = bluePos(s, t);
                yield s.off(p != null ? p : s.core(), f, r, u);
            }
            default -> s.atP(f, r, u, t);
        };
    }

    private static Vec3 keyPos(Scene s, CamKey k, double t) {
        return anchor(s, k.pa, k.cf, k.cr, k.cu, t);
    }

    private static Vec3 keyTarget(Scene s, CamKey k, double t) {
        return anchor(s, k.ta, k.tf, k.tr, k.tu, t);
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        return new Vec3(catmull(p0.x, p1.x, p2.x, p3.x, u), catmull(p0.y, p1.y, p2.y, p3.y, u), catmull(p0.z, p1.z, p2.z, p3.z, u));
    }

    private static double catmull(double p0, double p1, double p2, double p3, double u) {
        double u2 = u * u, u3 = u2 * u;
        return 0.5 * (2.0 * p1 + (-p0 + p2) * u + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3);
    }

    private record CamState(Vec3 pos, Vec3 target, float fov, float roll) {
    }

    private static CamState cameraAt(Scene s, double t) {
        int i = 0;
        while (i + 1 < CAM.length && CAM[i + 1].t <= t) i++;
        CamKey k1 = CAM[i];
        boolean hasNext = i + 1 < CAM.length && !CAM[i + 1].cut;
        if (!hasNext) {
            return new CamState(keyPos(s, k1, t), keyTarget(s, k1, t), k1.fov, k1.roll);
        }
        CamKey k2 = CAM[i + 1];
        CamKey k0 = k1.cut || i == 0 ? k1 : CAM[i - 1];
        CamKey k3 = i + 2 < CAM.length && !CAM[i + 2].cut ? CAM[i + 2] : k2;
        double u = clamp01((t - k1.t) / (double) (k2.t - k1.t));

        Vec3 pos = catmull(keyPos(s, k0, t), keyPos(s, k1, t), keyPos(s, k2, t), keyPos(s, k3, t), u);
        Vec3 target = catmull(keyTarget(s, k0, t), keyTarget(s, k1, t), keyTarget(s, k2, t), keyTarget(s, k3, t), u);
        float fov = (float) catmull(k0.fov, k1.fov, k2.fov, k3.fov, u);
        float roll = (float) catmull(k0.roll, k1.roll, k2.roll, k3.roll, u);
        return new CamState(pos, target, fov, roll);
    }

    private static float currentFov = 70f;
    private static float currentRoll = 0f;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        boolean paused = mc.isPaused();
        for (Scene scene : SCENES.values()) scene.advance(paused);

        Scene scene = local;
        Marker camera = cameraEntity;
        if (scene == null || camera == null || mc.player == null) return;

        float pt = event.renderTickTime;
        double t = scene.t();
        CamState cs = cameraAt(scene, t);
        currentFov = cs.fov;
        currentRoll = cs.roll;

        // Внутри территории камера не выходит за её стену (иначе в кадре — чёрная сфера снаружи).
        Vec3 pos = DomainExpansionClient.clampCameraInside(scene.feet, cs.pos);
        Vec3 d = cs.target.subtract(pos);
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

        // Настоящая позиция игрока — каждый кадр ровно по часам кат-сцены. Иначе она обновляется
        // 20 раз в секунду и на быстром подъёме отстаёт от модели почти на блок: всё, что рисует
        // игрока по его позиции, даёт вторую копию («двоение»).
        LocalPlayer player = mc.player;
        Vec3 at = scene.feet.add(0.0, heightAt(t), 0.0);
        player.setPos(at.x, at.y, at.z);
        player.xo = player.xOld = at.x;
        player.yo = player.yOld = at.y;
        player.zo = player.zOld = at.z;
        player.setDeltaMovement(Vec3.ZERO);

        // Тело игрока смотрит туда, куда смотрел при старте; голову ведёт анимация.
        player.setYRot(scene.yaw);
        player.yRotO = scene.yaw;
        player.yBodyRot = scene.yaw;
        player.yBodyRotO = scene.yaw;
        player.yHeadRot = scene.yaw;
        player.yHeadRotO = scene.yaw;
        player.setXRot(0.0f);
        player.xRotO = 0.0f;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Scene scene = local;
        if (scene == null || cameraEntity == null) return;
        double t = scene.t();

        float shake = 0.0f;
        if (t >= T_EXPLODE && t < T_EXPLODE + 8) shake = 3.2f * (1.0f - (float) (t - T_EXPLODE) / 8.0f);
        else if (t >= T_BLUE_THROW && t < T_BLUE_THROW + 7) shake = 2.0f * (1.0f - (float) (t - T_BLUE_THROW) / 7.0f);
        else if (t >= T_RED_FIRE && t < T_RED_FIRE + 6) shake = 1.6f * (1.0f - (float) (t - T_RED_FIRE) / 6.0f);
        else if (t >= T_BLUE_SPAWN && t < T_BLUE_SPAWN + 4) shake = 1.2f;
        else if (t >= T_SWEEP && t < T_RELEASE) shake = 0.35f;
        else if (t >= T_SPACE && t < T_BEHIND) shake = 0.25f + 0.9f * (float) win(t, T_INSERTS, T_BEHIND);
        else if (t >= T_BEHIND && t < T_EXPLODE) shake = 0.5f + 0.8f * (float) win(t, T_BEHIND, T_EXPLODE);
        // «Ручная» камера: лёгкое живое покачивание всегда
        float hand = 0.18f;
        event.setYaw(event.getYaw() + (float) Math.sin(t * 5.1) * shake + (float) Math.sin(t * 0.37) * hand);
        event.setPitch(event.getPitch() + (float) Math.cos(t * 6.3) * shake * 0.7f + (float) Math.sin(t * 0.29 + 1.3) * hand);
        event.setRoll(currentRoll + (float) Math.sin(t * 7.7) * shake * 0.5f + (float) Math.sin(t * 0.23) * hand * 0.6f);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFov(ViewportEvent.ComputeFov event) {
        Scene scene = local;
        if (scene == null || cameraEntity == null || !event.usedConfiguredFov()) return;
        event.setFOV(currentFov);
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

    // ------------------------------------------------------------------ world rendering

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || (SCENES.isEmpty() && SPARKS.isEmpty() && DECALS.isEmpty())) return;

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            Scene scene = local;
            if (scene == null || cameraEntity == null || mc.player == null) return;
            double t = scene.t();
            if (t >= T_SPACE - 1 && t < T_BEHIND) renderSpaceBackdrop(event, t);
            // В темноте и «космосе» игрока не видно.
            if (t < T_DARK || t >= T_BEHIND) renderLocalPlayer(mc, event);
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

        renderDecals(pose, camera, pt);
        UUID self = mc.player != null ? mc.player.getUUID() : null;
        for (Scene scene : SCENES.values()) {
            renderScene(pose, cam, camera, scene, scene.t(), scene.ownerId.equals(self));
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

        // Позиция игрока уже выставлена по часам кат-сцены в этом кадре (onRenderTick).
        double x = Mth.lerp(pt, player.xOld, player.getX()) - camera.x;
        double y = Mth.lerp(pt, player.yOld, player.getY()) - camera.y;
        double z = Mth.lerp(pt, player.zOld, player.getZ()) - camera.z;
        float yaw = Mth.lerp(pt, player.yRotO, player.getYRot());
        int light = dispatcher.getPackedLightCoords(player, pt);

        dispatcher.render(player, x, y, z, yaw, pt, event.getPoseStack(), buffers, light);
        buffers.endBatch();
    }

    /** «Космос»: экран с текстурой в 30 блоках, закрывает весь мир (глубина пишется поверх). */
    private static void renderSpaceBackdrop(RenderLevelStageEvent event, double t) {
        float alpha = (float) (smooth(win(t, T_SPACE - 1, T_SPACE + 1)) * (1.0 - smooth(win(t, T_SWIRL + 6, T_BEHIND))));
        if (alpha <= 0.01f) return;

        Camera cam = event.getCamera();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        double dist = 30.0;
        Minecraft mc = Minecraft.getInstance();
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        double halfH = dist * 1.4;
        double halfW = halfH * aspect;

        double cx = look.x() * dist, cy = look.y() * dist, cz = look.z() * dist;
        double zoom = 0.12 * win(t, T_SPACE, T_MERGED);
        float u0 = (float) zoom, v0 = (float) zoom, u1 = (float) (1.0 - zoom), v1 = (float) (1.0 - zoom);

        Matrix4f m = event.getPoseStack().last().pose();

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_SPACE);

        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(b, m, cx + left.x() * halfW + up.x() * halfH, cy + left.y() * halfW + up.y() * halfH, cz + left.z() * halfW + up.z() * halfH, u0, v0, 1f, 1f, 1f, alpha);
        quadVertex(b, m, cx + left.x() * halfW - up.x() * halfH, cy + left.y() * halfW - up.y() * halfH, cz + left.z() * halfW - up.z() * halfH, u0, v1, 1f, 1f, 1f, alpha);
        quadVertex(b, m, cx - left.x() * halfW - up.x() * halfH, cy - left.y() * halfW - up.y() * halfH, cz - left.z() * halfW - up.z() * halfH, u1, v1, 1f, 1f, 1f, alpha);
        quadVertex(b, m, cx - left.x() * halfW + up.x() * halfH, cy - left.y() * halfW + up.y() * halfH, cz - left.z() * halfW + up.z() * halfH, u1, v0, 1f, 1f, 1f, alpha);
        BufferUploader.drawWithShader(b.end());

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
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

    /**
     * Светящаяся лента по точкам (след шара, ленты вихря): повёрнута к камере,
     * яркая по центру и прозрачная по краям, сужается и гаснет к хвосту.
     */
    private static void ribbon(PoseStack pose, Vec3 camera, List<Vec3> pts, float width0, float width1,
                               float r, float g, float b, float alpha0, float alpha1) {
        int n = pts.size();
        if (n < 2 || Math.max(alpha0, alpha1) <= 0.003f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Vec3 pl = null, pc = null, pr = null;
        float pa = 0f;
        for (int i = 0; i < n; i++) {
            Vec3 p = pts.get(i);
            Vec3 tangent = pts.get(Math.min(n - 1, i + 1)).subtract(pts.get(Math.max(0, i - 1)));
            Vec3 toCam = camera.subtract(p);
            Vec3 side = tangent.cross(toCam);
            if (side.lengthSqr() < 1.0E-10) side = new Vec3(0, 1, 0);
            float k = i / (float) (n - 1);
            float w = Mth.lerp(k, width0, width1);
            float a = Mth.lerp(k, alpha0, alpha1);
            side = side.normalize().scale(w);
            Vec3 c = p.subtract(camera);
            Vec3 l = c.add(side);
            Vec3 rr = c.subtract(side);
            if (pc != null) {
                tri(buf, m, pl, 0f, pc, pa, c, a, r, g, b);
                tri(buf, m, pl, 0f, c, a, l, 0f, r, g, b);
                tri(buf, m, pc, pa, pr, 0f, rr, 0f, r, g, b);
                tri(buf, m, pc, pa, rr, 0f, c, a, r, g, b);
            }
            pl = l;
            pc = c;
            pr = rr;
            pa = a;
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void tri(BufferBuilder buf, Matrix4f m, Vec3 a, float aa, Vec3 b, float ba, Vec3 c, float ca,
                            float r, float g, float bl) {
        buf.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, aa).endVertex();
        buf.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, ba).endVertex();
        buf.vertex(m, (float) c.x, (float) c.y, (float) c.z).color(r, g, bl, ca).endVertex();
    }

    /** След шара: точки пути в предыдущие моменты (путь аналитический — след идеально гладкий). */
    private static void orbTrail(PoseStack pose, Vec3 camera, Scene s, double t, boolean blue,
                                 double length, float width, float alpha) {
        if (alpha <= 0.01f || length <= 0.1) return;
        List<Vec3> pts = new ArrayList<>();
        int samples = Math.max(6, (int) (length * 2.0));
        for (int i = 0; i <= samples; i++) {
            double tt = t - length * i / samples;
            Vec3 p = blue ? bluePos(s, tt) : redPos(s, tt);
            if (p == null) break;
            pts.add(p);
        }
        if (pts.size() < 2) return;
        if (blue) {
            ribbon(pose, camera, pts, width, 0.0f, 0.2f, 0.45f, 1.0f, alpha, 0.0f);
            ribbon(pose, camera, pts, width * 0.35f, 0.0f, 0.85f, 0.95f, 1.0f, alpha * 0.9f, 0.0f);
        } else {
            ribbon(pose, camera, pts, width, 0.0f, 1.0f, 0.06f, 0.14f, alpha, 0.0f);
            ribbon(pose, camera, pts, width * 0.35f, 0.0f, 1.0f, 0.6f, 0.65f, alpha * 0.8f, 0.0f);
        }
    }

    private static double hash(double v) {
        double x = Math.sin(v * 127.1 + 311.7) * 43758.5453;
        return x - Math.floor(x);
    }

    private static Vec3 hashDir(double seed) {
        double a = hash(seed) * Math.PI * 2.0;
        double z = hash(seed + 7.3) * 2.0 - 1.0;
        double r = Math.sqrt(1.0 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    /** Фиолетово-белое электрическое ядро: мерцающее свечение и молнии во все стороны. */
    private static void renderCore(PoseStack pose, Camera cam, Vec3 camera, Vec3 c, float intensity, double t, float reach) {
        if (intensity <= 0.01f) return;
        float flick = 0.75f + 0.25f * (float) Math.sin(t * 2.7) * (float) Math.sin(t * 1.3 + 0.4);
        billboard(pose, cam, camera, c, 3.6f * reach * intensity, (float) (t * 0.1), TEX_BLOOM, 0.75f, 0.3f, 1.0f, 0.75f * intensity, true);
        billboard(pose, cam, camera, c, 1.7f * reach * intensity * flick, 0f, TEX_BLOOM, 1.0f, 0.92f, 1.0f, 0.95f * intensity, true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        double bucket = Math.floor(t * 1.5);
        for (int i = 0; i < 7; i++) {
            double seed = bucket * 13.0 + i * 3.1;
            Vec3 dir = hashDir(seed);
            double len = (0.9 + hash(seed + 1.1) * 1.6) * reach;
            HollowPurpleReferenceClient.mpArc(pose, camera, c, c.add(dir.scale(len)), 0.035f * reach + 0.01f, 8, t,
                    seed, i % 3 == 0 ? 11 : (i % 3 == 1 ? 10 : 9), 0.9f * intensity, 0.28f * reach);
        }
    }

    private static void renderScene(PoseStack pose, Camera cam, Vec3 camera, Scene s, double t, boolean isLocal) {
        // ---- 0,7 с: Синий вспыхивает в поднятой руке — кольца
        Vec3 blue = bluePos(s, t);
        if (blue != null && t < T_VORTEX + 4) {
            float flash = env(t, T_BLUE_SPAWN - 1, T_VORTEX + 4, 1.0, 5.0);
            billboard(pose, cam, camera, blue, 3.2f * flash + 0.5f, 0f, TEX_BLOOM, 0.3f, 0.65f, 1.0f, 0.95f * flash, true);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            float grow = (float) win(t, T_BLUE_SPAWN - 1, T_VORTEX + 4);
            for (int i = 0; i < 3; i++) {
                Vec3 normal = camera.subtract(blue).normalize().add(0.15 * (i - 1), 0.2, 0.0).normalize();
                HollowPurpleReferenceClient.mpRing(pose, camera, blue, normal, 0.3f + grow * (1.6f + i * 0.5f), 0.04f,
                        t * (18.0 + i * 6.0), 4, 5, flash * (0.85f - i * 0.2f), 0.12f, 61.0 + i * 7.0, true);
            }
        }

        // ---- 2,0–2,3 с: бросок — боковые сине-фиолетовые полосы, синий взрыв
        float streaks = env(t, T_BLUE_THROW, T_BLUE_THROW + 9, 1.0, 6.0);
        if (streaks > 0.0f) {
            double grow = easeOut(win(t, T_BLUE_THROW, T_BLUE_THROW + 5));
            for (int i = 0; i < 12; i++) {
                double side = i % 2 == 0 ? 1.0 : -1.0;
                double h = 0.4 + (i / 2) * 0.32;
                double len = (3.0 + hash(i * 1.7) * 6.0) * grow;
                List<Vec3> pts = new ArrayList<>();
                for (int j = 0; j <= 8; j++) {
                    double k = j / 8.0;
                    pts.add(s.atP(0.3 * Math.sin(k * 3 + i), side * (0.4 + len * k), h + 0.25 * Math.sin(k * 4.0 + i), t));
                }
                boolean purple = i % 3 == 0;
                ribbon(pose, camera, pts, 0.09f, 0.02f, purple ? 0.6f : 0.2f, purple ? 0.2f : 0.5f, 1.0f, 0.9f * streaks, 0.0f);
            }
            float burst = env(t, T_BLUE_THROW, T_BLUE_THROW + 8, 1.0, 6.0);
            billboard(pose, cam, camera, sweepPos(s, Math.PI / 4.0, t), 6.0f * burst, 0f, TEX_BLOOM, 0.3f, 0.6f, 1.0f, 0.9f * burst, true);
        }

        // ---- Синий
        if (blue != null) {
            float br = blueRadius(t);
            if (t >= T_SWEEP + 1 && t < T_RELEASE) orbTrail(pose, camera, s, t, true, Math.min(t - T_SWEEP, 5.0), 0.12f, 0.9f);
            else if (t >= T_RELEASE && t < T_CALM) orbTrail(pose, camera, s, t, true, 7.0, br * 0.9f, 0.85f);
            else if (t >= T_SKY && t < T_APPROACH) orbTrail(pose, camera, s, t, true, 12.0, br * 0.85f, 0.9f);
            else if (t >= T_APPROACH && t < T_CHASE) orbTrail(pose, camera, s, t, true, 9.0, br * 0.8f, 0.9f);
            else if (t >= T_CHASE && t < T_RING) orbTrail(pose, camera, s, t, true, 5.0, br * 0.8f, 0.9f);
            else if (t >= T_RING && t < T_RING_OPEN + 12) orbTrail(pose, camera, s, t, true,
                    9.0 * (1.0 - smooth(win(t, T_RING_OPEN, T_RING_OPEN + 12))), br * 0.7f, 0.9f);
            renderOrb(pose, cam, camera, blue, br, true, t, t < T_BLUE_THROW + 1);
        }

        // ---- Красный: в ладони у лица, выстрел, подъём со следом, погоня
        Vec3 red = redPos(s, t);
        if (red != null) {
            float rr = redRadius(t);
            if (t >= T_RED_FIRE + 1 && t < T_CHASE) orbTrail(pose, camera, s, t, false, 8.0, rr * 0.9f, 0.9f * (1.0f - (float) smooth(win(t, 100, 110))));
            else if (t >= T_CHASE && t < T_RING) orbTrail(pose, camera, s, t, false, 5.0, rr * 0.8f, 0.9f);
            else if (t >= T_RING && t < T_RING_OPEN + 12) orbTrail(pose, camera, s, t, false,
                    9.0 * (1.0 - smooth(win(t, T_RING_OPEN, T_RING_OPEN + 12))), rr * 0.7f, 0.9f);
            renderOrb(pose, cam, camera, red, rr, false, t, t < T_RED_FIRE + 1);
        }

        // ---- 3,45–3,6 с: выстрел — красные ленты разрядов вокруг игрока
        float fire = env(t, T_RED_FIRE - 1, T_RED_FIRE + 9, 1.0, 6.0);
        if (fire > 0.0f) {
            double grow = easeOut(win(t, T_RED_FIRE - 1, T_RED_FIRE + 4));
            Vec3 origin = s.atP(0.32, 0.30, 1.9, t);
            for (int i = 0; i < 16; i++) {
                double a0 = i * 2.39996;
                double tilt = 0.4 + hash(i * 3.3) * 1.1;
                double len = (1.2 + hash(i * 5.1) * 2.4) * grow;
                List<Vec3> pts = new ArrayList<>();
                for (int j = 0; j <= 10; j++) {
                    double k = j / 10.0;
                    double a = a0 + k * 1.6;
                    Vec3 dir = circle(s, a, 1.0, 1.0).add(0.0, Math.sin(tilt + k) * 0.8 - 0.2, 0.0);
                    pts.add(origin.add(dir.scale(0.2 + len * k)));
                }
                ribbon(pose, camera, pts, 0.03f, 0.14f, 1.0f, 0.06f, 0.12f, 0.95f * fire, 0.2f * fire);
            }
            billboard(pose, cam, camera, origin.add(0.0, 0.6, 0.0), 6.5f * (float) grow, 0f, TEX_BLOOM, 1.0f, 0.08f, 0.16f, 0.85f * fire, true);
        }

        // ---- 7,5–14,1 с: фиолетово-белое электрическое ядро между шарами
        float core = env(t, 148, T_DARK + 1, 14.0, 2.0);
        if (core > 0.0f) {
            Vec3 c = s.core();
            renderCore(pose, cam, camera, c, core, t, 1.0f);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            if (blue != null && red != null && (int) (t / 3.0) % 3 != 2) {
                HollowPurpleReferenceClient.mpArc(pose, camera, c, red, 0.05f, 10, t, Math.floor(t / 3.0) * 7.0, 9, 0.8f * core, 0.45f);
                HollowPurpleReferenceClient.mpArc(pose, camera, c, blue, 0.05f, 10, t, Math.floor(t / 3.0) * 7.0 + 3.0, 10, 0.8f * core, 0.45f);
            }
            float ring = env(t, T_RING, T_RING_OPEN + 8, 8.0, 8.0);
            if (ring > 0.0f) {
                HollowPurpleReferenceClient.mpRing(pose, camera, c, new Vec3(0.0, 1.0, 0.0), 3.0f, 0.12f, t * 6.0,
                        8, 9, 0.55f * ring, 0.1f, 71.0, false);
            }
        }

        // ---- 15,25–16,55 с: «космос» — молнии между Красным и Синим, ядро растёт, ударное кольцо
        float space = env(t, T_SPACE, T_MERGED + 1, 1.0, 1.0);
        if (space > 0.0f && red != null && blue != null) {
            Vec3 c = s.core();
            double grow = win(t, T_SPACE, T_MERGED);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            for (int i = 0; i < 4; i++) {
                double seed = Math.floor(t * 2.0) * 5.0 + i * 1.9;
                HollowPurpleReferenceClient.mpArc(pose, camera, red, blue, 0.07f - i * 0.012f, 16, t, seed,
                        i == 0 ? 11 : (i == 1 ? 10 : 8), 0.95f * space, 0.55f + i * 0.15f);
            }
            HollowPurpleReferenceClient.mpPurple(pose, camera, c, s.forward, (float) (0.35 + 0.65 * grow), 1.0f, t, 1.0f, (float) grow);
            renderCore(pose, cam, camera, c, space, t, (float) (1.0 + 1.2 * grow));
            double ringK = (t - T_SPACE) % 9.0 / 9.0;
            HollowPurpleReferenceClient.mpRing(pose, camera, c, camera.subtract(c).normalize(), (float) (0.6 + ringK * 3.5), 0.08f,
                    t * 4.0, 8, 10, (float) ((1.0 - ringK) * 0.8 * space), 0.15f, 83.0, true);
        }

        // ---- 16,8–17,45 с: один большой фиолетовый шар в молниях, затем белый закрученный взрыв
        float merged = env(t, T_MERGED, T_BEHIND, 1.0, 2.0);
        if (merged > 0.0f) {
            Vec3 c = s.core();
            double g = win(t, T_MERGED, T_BEHIND);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpPurple(pose, camera, c, s.forward, (float) (0.9 + 1.4 * g), 1.0f, t, 1.0f, 1.0f);
            renderCore(pose, cam, camera, c, merged, t, (float) (1.8 + 1.5 * g));
            float swirl = env(t, T_SWIRL, T_BEHIND, 1.0, 1.0);
            billboard(pose, cam, camera, c, (float) (3.0 + 7.0 * g), (float) (t * 0.35), TEX_SWIRL, 1f, 1f, 1f, 0.95f * swirl, false);
            billboard(pose, cam, camera, c, (float) (2.4 + 6.0 * g), (float) (-t * 0.5 + 1.0), TEX_SWIRL, 1f, 0.9f, 1f, 0.6f * swirl, true);
        }

        // ---- 17,5–18,2 с: огромная фиолетовая спираль перед игроком
        float big = env(t, T_BEHIND, T_MANGA_C + 3, 1.0, 3.0);
        if (big > 0.0f) {
            Vec3 c = bigSpherePos(s, t);
            double g = win(t, T_BEHIND, T_EXPLODE);
            float size = (float) (6.6 + 1.6 * g);
            billboard(pose, cam, camera, c, size * 1.5f, 0f, TEX_BLOOM, 0.75f, 0.2f, 1.0f, 0.7f * big, true);
            billboard(pose, cam, camera, c, size, (float) (t * 0.3), TEX_SWIRL, 1f, 1f, 1f, big, false);
            billboard(pose, cam, camera, c, size * 0.8f, (float) (-t * 0.45 + 2.0), TEX_SWIRL, 1f, 1f, 1f, 0.55f * big, true);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, c, size * 0.5f, 18, 28, 8, 9, 0.35f * big, 0.08f, t, 501.0);
            renderCore(pose, cam, camera, c, big * 0.8f, t, 1.6f);
            // далёкие молнии в небе
            for (int i = 0; i < 3; i++) {
                double seed = Math.floor(t / 2.0) * 3.0 + i;
                if (hash(seed) < 0.35) continue;
                Vec3 top = s.at(-18.0 - i * 9.0, -22.0 + i * 20.0, RISE_HEIGHT + 22.0);
                Vec3 bottom = s.at(-20.0 - i * 9.0, -20.0 + i * 20.0, 2.0);
                HollowPurpleReferenceClient.mpArc(pose, camera, top, bottom, 0.35f, 14, t, seed * 11.0, 10, 0.9f * big, 3.0f);
            }
        }

        // ---- 18,1 с: главный удар — вспышка в сфере
        float boom = env(t, T_EXPLODE, T_EXPLODE + 8, 1.0, 6.0);
        if (boom > 0.0f) {
            billboard(pose, cam, camera, bigSpherePos(s, t), 22.0f * boom, 0f, TEX_BLOOM, 1.0f, 0.85f, 1.0f, boom, true);
        }

        // ---- Взрыв вокруг игрока для всех остальных: купол до 100 блоков
        if (!isLocal && t >= T_EXPLODE && t < T_EXPLODE + 70) {
            double x = clamp01((t - T_EXPLODE) / 16.0);
            float radius = (float) (2.0 + (RADIUS - 2.0) * (1.0 - Math.pow(1.0 - x, 3.0)));
            float life = 1.0f - (float) smooth((t - (T_EXPLODE + 24)) / 46.0);
            Vec3 center = s.at(0.0, 0.0, RISE_HEIGHT + 1.0);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpAlphaBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, radius, 22, 34, 6, 7, 0.40f * life, 0.05f, t * 0.4, 401.0);
            HollowPurpleReferenceClient.mpAdditiveBlend();
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, radius * 1.015f, 22, 34, 9, 10, 0.32f * life, 0.06f, t * 0.6, 419.0);
            HollowPurpleReferenceClient.mpSphere(pose, camera, center, Math.min(radius, 8.0f) * 0.6f, 16, 24, 10, 11, 0.8f * life, 0.08f, t, 433.0);
            for (int i = 0; i < 3; i++) {
                HollowPurpleReferenceClient.mpRing(pose, camera, s.at(0.0, 0.0, 0.5 + i * 2.0), new Vec3(0.0, 1.0, 0.0),
                        radius * (0.9f + i * 0.06f), Math.max(0.25f, radius * 0.03f), t * (3.0 + i), 9, 11,
                        life * (0.85f - i * 0.2f), 0.06f, 451.0 + i * 17.0, i > 0);
            }
        }
    }

    private static void renderOrb(PoseStack pose, Camera cam, Vec3 camera, Vec3 pos, float radius, boolean blue, double t,
                                  boolean inHand) {
        if (radius <= 0.01f) return;
        float hr = blue ? 0.25f : 1.0f, hg = blue ? 0.55f : 0.12f, hb = blue ? 1.0f : 0.2f;
        if (inHand) {
            // Маленький шарик энергии в руке
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            HollowPurpleReferenceClient.mpLimitless(pose, camera, pos, cam.getPosition().subtract(pos).normalize(), radius, 1.0f, t, blue);
            billboard(pose, cam, camera, pos, radius * 9.0f, 0f, TEX_BLOOM, hr, hg, hb, 0.85f, true);
            return;
        }
        drawTexturedOrb(pose, camera, pos, radius, blue ? TEX_BLUE_ORB : TEX_RED_ORB, (blue ? -1 : 1) * t * 0.013, 1.0f);
        billboard(pose, cam, camera, pos, radius * 4.4f, 0f, TEX_BLOOM, hr, hg, hb, 0.6f, true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        HollowPurpleReferenceClient.mpSphere(pose, camera, pos, radius * 1.12f, 14, 22, blue ? 4 : 1, blue ? 5 : 2, 0.18f, 0.04f, t, blue ? 9.0 : 3.0);
    }

    /** Шар с текстурой (Красный — с чёрными разводами, Синий — с голубыми прожилками). */
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

    // ------------------------------------------------------------------ свои частицы и брызги

    private static void spark(Vec3 pos, Vec3 vel, float size, float r, float g, float b, int life, double drag) {
        if (SPARKS.size() > 1800) return;
        SPARKS.add(new Spark(pos, vel, size, r, g, b, life, drag, 0.0, false));
    }

    private static Vec3 rndVec(double scale) {
        return new Vec3(RNG.nextGaussian() * scale, RNG.nextGaussian() * scale, RNG.nextGaussian() * scale);
    }

    private static void spawnSparks(Scene s, int t) {
        Vec3 blue = bluePos(s, t);
        Vec3 red = redPos(s, t);

        if (t == T_BLUE_SPAWN && blue != null) {
            for (int i = 0; i < 26; i++) spark(blue, rndVec(0.22), 0.16f, 0.4f, 0.8f, 1.0f, 12, 0.84);
        }
        // бросок: синий взрыв
        if (t >= T_RELEASE && t < T_RELEASE + 2) {
            Vec3 top = sweepPos(s, Math.PI / 4.0, t);
            for (int i = 0; i < 12; i++) {
                boolean white = RNG.nextInt(3) == 0;
                spark(top, rndVec(0.3), 0.22f, white ? 0.9f : 0.3f, white ? 0.95f : 0.6f, 1.0f, 8, 0.8);
            }
        }
        // Красный в ладони
        if (red != null && t < T_RED_FIRE) {
            for (int i = 0; i < 2; i++) spark(red.add(rndVec(0.06)), rndVec(0.025), 0.07f, 1.0f, 0.12f, 0.2f, 8, 0.85);
        }
        // выстрел Красного
        if (t >= T_RED_FIRE && t < T_RED_FIRE + 3) {
            Vec3 top = s.atP(0.32, 0.30, 2.0, t);
            for (int i = 0; i < 22; i++) {
                spark(top, rndVec(0.3).add(0.0, 0.25, 0.0), 0.2f, 1.0f, 0.08f + RNG.nextFloat() * 0.3f, 0.2f, 16, 0.86);
            }
        }
        if (red != null && t >= T_RED_FIRE && t < T_SPACE && t % 2 == 0) {
            spark(red.add(rndVec(0.15)), rndVec(0.02), 0.12f, 1.0f, 0.15f, 0.22f, 12, 0.9);
        }
        // искры ядра
        if (t >= 150 && t < T_DARK) {
            Vec3 c = s.core();
            for (int i = 0; i < 2; i++) spark(c, rndVec(0.18), 0.14f, 0.9f, 0.6f, 1.0f, 10, 0.86);
        }
        // главный удар
        if (t >= T_EXPLODE && t < T_EXPLODE + 4) {
            Vec3 c = bigSpherePos(s, t);
            for (int i = 0; i < 40; i++) {
                Vec3 v = rndVec(1.0).normalize().scale(0.8 + RNG.nextDouble() * 1.6);
                boolean white = RNG.nextInt(3) == 0;
                spark(c, v, 0.9f + RNG.nextFloat() * 1.2f, white ? 1.0f : 0.8f, white ? 0.9f : 0.25f, 1.0f, 20, 0.92);
            }
        }
    }

    private static void tickParticles() {
        Iterator<Spark> it = SPARKS.iterator();
        while (it.hasNext()) {
            Spark p = it.next();
            p.age++;
            if (p.age >= p.life) {
                it.remove();
                continue;
            }
            p.pos = p.pos.add(p.vel);
            p.vel = p.vel.scale(p.drag).add(0.0, -p.gravity, 0.0);
        }
        Iterator<Decal> dit = DECALS.iterator();
        while (dit.hasNext()) {
            Decal d = dit.next();
            if (++d.age >= d.life) dit.remove();
        }
    }

    private static void renderSparks(PoseStack pose, Camera cam, Vec3 camera, float pt) {
        if (SPARKS.isEmpty()) return;
        renderSparkBatch(pose, cam, camera, pt, false);
        renderSparkBatch(pose, cam, camera, pt, true);
    }

    private static void renderSparkBatch(PoseStack pose, Camera cam, Vec3 camera, float pt, boolean splash) {
        boolean any = false;
        for (Spark p : SPARKS) {
            if (p.splash == splash) {
                any = true;
                break;
            }
        }
        if (!any) return;
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, splash ? TEX_SPLASH : TEX_BLOOM);
        if (splash) HollowPurpleReferenceClient.mpAlphaBlend();
        else HollowPurpleReferenceClient.mpAdditiveBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Spark p : SPARKS) {
            if (p.splash != splash) continue;
            float k = (p.age + pt) / p.life;
            float a = (1.0f - k) * Math.min(1.0f, (p.age + pt) * 0.5f + 0.2f);
            if (splash) a = Math.min(0.9f, a * 1.4f);
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

    private static void renderDecals(PoseStack pose, Vec3 camera, float pt) {
        if (DECALS.isEmpty()) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_SPLASH);
        HollowPurpleReferenceClient.mpAlphaBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Decal d : DECALS) {
            float k = (d.age + pt) / d.life;
            float a = 0.85f * Math.min(1.0f, (d.age + pt) / 3.0f) * (1.0f - (float) smooth((k - 0.5) / 0.5));
            float grow = (float) (0.6 + 0.4 * easeOut(Math.min(1.0, (d.age + pt) / 5.0)));
            double h = d.size * 0.5 * grow;
            double c = Math.cos(d.rot) * h, sn = Math.sin(d.rot) * h;
            double x = d.pos.x - camera.x, y = d.pos.y - camera.y, z = d.pos.z - camera.z;
            quadVertex(buf, m, x + c - sn, y, z + sn + c, 0f, 0f, d.r, d.g, d.b, a);
            quadVertex(buf, m, x + c + sn, y, z + sn - c, 0f, 1f, d.r, d.g, d.b, a);
            quadVertex(buf, m, x - c + sn, y, z - sn - c, 1f, 1f, d.r, d.g, d.b, a);
            quadVertex(buf, m, x - c - sn, y, z - sn + c, 1f, 0f, d.r, d.g, d.b, a);
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
            drawLocalOverlays(g, w, h, scene.t());
            return;
        }

        if (fadeOut > 0) {
            float a = Mth.clamp((fadeOut - pt) / FADE_OUT_TICKS, 0.0f, 1.0f);
            fillColor(g, w, h, a, 0xFFFFFF);
            return;
        }

        // Наблюдатели: вспышка, если взрыв рядом.
        float flash = 0.0f;
        Vec3 eye = mc.player.getEyePosition();
        for (Scene other : SCENES.values()) {
            if (other.ownerId.equals(mc.player.getUUID())) continue;
            double dist = eye.distanceTo(other.at(0.0, 0.0, RISE_HEIGHT));
            float proximity = (float) (1.0 - clamp01((dist - 30.0) / 130.0));
            if (proximity <= 0.0f) continue;
            double t = other.t();
            float in = (float) smooth((t - T_EXPLODE) / 4.0);
            float out = 1.0f - (float) smooth((t - (T_EXPLODE + 12)) / 30.0);
            flash = Math.max(flash, in * out * proximity * 0.92f);
        }
        if (flash > 0.005f) fillColor(g, w, h, flash, 0xFFFFFF);
    }

    private static void drawLocalOverlays(GuiGraphics g, int w, int h, double t) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // 0,7 с: синяя вспышка на весь экран с кольцом
        float blueFlash = env(t, T_BLUE_SPAWN - 0.5, T_BLUE_SPAWN + 4, 0.5, 3.5);
        if (blueFlash > 0.0f) {
            fillColor(g, w, h, 0.45f * blueFlash, 0x5AA0FF);
            int size = (int) (h * (1.2f + 2.4f * (float) win(t, T_BLUE_SPAWN - 0.5, T_BLUE_SPAWN + 4)));
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(0.6f, 0.85f, 1.0f, blueFlash);
            g.blit(TEX_BLOOM, w / 2 - size / 2, h / 2 - size / 2, size, size, 0.0f, 0.0f, 512, 512, 512, 512);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        // 2,1 с: белая вспышка броска
        float throwFlash = env(t, T_BLUE_THROW + 1, T_BLUE_THROW + 5, 0.5, 3.0);
        if (throwFlash > 0.0f) fillColor(g, w, h, 0.9f * throwFlash, 0xF4F8FF);

        // 3,30–3,37 с: манга-кадр — белый силуэт игрока на чёрной штриховке
        if (t >= T_MANGA_A && t < T_MANGA_A + 2) {
            boolean first = t < T_MANGA_A + 1;
            drawFrame(g, w, h, first ? MANGA_A1 : MANGA_A2, t - T_MANGA_A);
            drawSilhouetteAt(g, w, h, WHITE_TINT, WHITE_TINT, WHITE_TINT, first ? 1.0f : 1.08f, 0.0f, 0.0f, 180.0f);
            drawFrame(g, w, h, TEX_HATCH, 0.0);
        }

        // 3,45 с: красный отсвет выстрела
        float redFlash = env(t, T_RED_FIRE, T_RED_FIRE + 4, 0.5, 3.0);
        if (redFlash > 0.0f) fillColor(g, w, h, 0.35f * redFlash, 0xFF1030);

        // 14,15–15,2 с: свет гаснет, ровно секунда темноты, потом «космос» проступает
        if (t >= T_DARK && t < T_SPACE + 1) {
            float a = (float) (smooth(win(t, T_DARK, T_DARK + 1.2)) * (1.0 - smooth(win(t, T_BLACK_END, T_SPACE + 1))));
            fillColor(g, w, h, a, 0x000000);
        }

        // 16,6–16,75 с: четыре аниме-вставки
        if (t >= T_INSERTS && t < T_INSERTS + 4) {
            int idx = Mth.clamp((int) (t - T_INSERTS), 0, INSERTS.length - 1);
            drawFrame(g, w, h, INSERTS[idx], t - T_INSERTS);
        }

        // 16,9–17,45 с: белый закрученный взрыв на весь экран
        float swirl = env(t, T_SWIRL, T_BEHIND, 0.5, 1.5);
        if (swirl > 0.0f) {
            float white = (float) (1.0 - 0.75 * smooth(win(t, T_SWIRL, T_SWIRL + 4)));
            fillColor(g, w, h, white * swirl, 0xFFF6FF);
            drawSpinning(g, w, h, TEX_SWIRL, (float) (t * 0.32), (float) (1.6 + 0.6 * win(t, T_SWIRL, T_BEHIND)),
                    0.85f * swirl * (float) smooth(win(t, T_SWIRL, T_SWIRL + 3)));
        }

        // 18,1 с: главный удар
        float boom = env(t, T_EXPLODE, T_MANGA_C, 0.4, 2.0);
        if (boom > 0.0f) fillColor(g, w, h, boom, 0xFFFFFF);

        // 18,25–18,4 с: манга-кадр с рукой, потом кадр с белым «X»
        if (t >= T_MANGA_C && t < T_MANGA_C + 2) {
            drawFrame(g, w, h, MANGA_C1, t - T_MANGA_C);
            // контур чёрным, сверху белый силуэт в позе Годжо и штриховка
            for (int i = 0; i < 4; i++) {
                float dx = (i % 2 == 0 ? 1.0f : -1.0f) * 2.5f, dy = (i < 2 ? 1.0f : -1.0f) * 2.5f;
                drawSilhouetteAt(g, w, h, 0.0f, 0.0f, 0.0f, 1.55f, dx, dy, 110.0f);
            }
            drawSilhouetteAt(g, w, h, WHITE_TINT, WHITE_TINT, WHITE_TINT, 1.55f, 0.0f, 0.0f, 110.0f);
            drawFrame(g, w, h, TEX_HATCH, 0.0);
        } else if (t >= T_MANGA_C + 2 && t < T_WHITE_IN) {
            drawFrame(g, w, h, MANGA_C2, t - T_MANGA_C - 2);
        }

        // Белый экран, пока дорезается кратер
        if (t >= T_WHITE_IN) {
            fillColor(g, w, h, (float) smooth(win(t, T_WHITE_IN, T_WHITE_IN + 1.5)), 0xFFFFFF);
        }
    }

    /** Множитель цвета модели «в пересвет»: любой непрозрачный пиксель скина становится белым. */
    private static final float WHITE_TINT = 60.0f;

    private static void drawFrame(GuiGraphics g, int w, int h, ResourceLocation frame, double k) {
        // Лёгкий наезд и дрожь, как у вставок в референсе
        float scale = 1.03f + 0.03f * (float) k;
        int dw = (int) (w * scale), dh = (int) (h * scale);
        int ox = (w - dw) / 2 + (int) (Math.sin(k * 9.1) * 2.0);
        int oy = (h - dh) / 2 + (int) (Math.cos(k * 7.3) * 2.0);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        g.blit(frame, ox, oy, dw, dh, 0.0f, 0.0f, 1920, 1080, 1920, 1080);
    }

    /** Квадратная текстура по центру экрана, вращается. */
    private static void drawSpinning(GuiGraphics g, int w, int h, ResourceLocation texture, float angle, float scale, float alpha) {
        if (alpha <= 0.01f) return;
        int size = (int) (Math.max(w, h) * scale);
        g.pose().pushPose();
        g.pose().translate(w / 2.0f, h / 2.0f, 0.0f);
        g.pose().mulPose(Axis.ZP.rotation(angle));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        g.blit(texture, -size / 2, -size / 2, size, size, 0.0f, 0.0f, 1024, 1024, 1024, 1024);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        g.pose().popPose();
    }

    /** Силуэт игрока в текущей позе (его настоящая модель), закрашенный одним цветом; bodyYaw 180 — лицом к экрану. */
    private static void drawSilhouetteAt(GuiGraphics g, int w, int h, float r, float gr, float b, float zoom,
                                         float dx, float dy, float bodyYaw) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        float bodyRot = p.yBodyRot, bodyRotO = p.yBodyRotO, yRot = p.getYRot(), yRotO = p.yRotO;
        float xRot = p.getXRot(), xRotO = p.xRotO, head = p.yHeadRot, headO = p.yHeadRotO;
        p.yBodyRot = bodyYaw;
        p.yBodyRotO = bodyYaw;
        p.setYRot(bodyYaw);
        p.yRotO = bodyYaw;
        p.setXRot(0.0f);
        p.xRotO = 0.0f;
        p.yHeadRot = bodyYaw;
        p.yHeadRotO = bodyYaw;

        g.flush();
        RenderSystem.setShaderColor(r, gr, b, 1.0f);
        int scale = (int) (h * 0.62f * zoom);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        InventoryScreen.renderEntityInInventory(g, (int) (w / 2 + dx), (int) (h * (1.32f + 0.75f * (zoom - 1.0f)) + dy), scale, pose, null, p);
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

    private static void fillColor(GuiGraphics g, int w, int h, float alpha, int rgb) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        if (a <= 0) return;
        g.fill(0, 0, w, h, (a << 24) | (rgb & 0x00FFFFFF));
    }
}
