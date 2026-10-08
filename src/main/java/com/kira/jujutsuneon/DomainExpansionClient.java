package com.kira.jujutsuneon;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.kira.jujutsuneon.DomainExpansion.*;

/**
 * Клиент Расширения территории: катсцена каста (у владельца и у всех, кто попал в радиус 40),
 * вид «Бесконечной пустоты» изнутри, гладкая чёрная сфера снаружи, разрушение со звуком
 * бьющегося стекла, маленький таймер обездвиживания внизу экрана.
 *
 * Катсцена (10,8 с, покадрово по референсу; время — по реальным часам, синхронно с голосом):
 *   0–10    общий план
 *   10–63   быстрые кадры сквозь косые прорези: камера ведёт кончик руки, которая поднимается к лицу
 *   64–106  крупно лицо и согнутая рука у лица; на 92 рука закрывает лицо
 *   108–112 мир растворяется белыми кляксами, остаётся персонаж
 *   112–123 белая пустота, полосы кино, крупно лицо
 *   124–130 белый экран, проступают фиолетовые кляксы
 *   131–159 полёт сквозь космос, белые силуэты всех, кто в территории
 *   160–163 космос сжимается в полоску, затем темнота
 *   176–197 широкая полоса: глаза крупно в синем свете, сзади уже пустота
 *   198–216 камера из-за плеча отъезжает — управление возвращается
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DomainExpansionClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_SKY = tex("domain_void_sky");
    private static final ResourceLocation TEX_SMOKE = tex("domain_smoke");
    private static final ResourceLocation TEX_BLOTS = tex("domain_blots");
    private static final ResourceLocation TEX_BLACKHOLE = tex("domain_blackhole");
    private static final ResourceLocation TEX_WINGS = tex("domain_wings");
    private static final ResourceLocation TEX_FLOOR = tex("domain_floor");
    private static final ResourceLocation TEX_GALAXY = tex("domain_galaxy");
    private static final ResourceLocation TEX_CRACKS = tex("domain_cracks");
    private static final ResourceLocation TEX_BLOOM = tex("max_purple_bloom");

    private static final ResourceLocation SND_VOICE = new ResourceLocation(JujutsuNeonMod.MODID, "domain_voice");
    private static final ResourceLocation SND_VOICE_WORLD = new ResourceLocation(JujutsuNeonMod.MODID, "domain_voice_world");
    private static final ResourceLocation SND_SHATTER = new ResourceLocation(JujutsuNeonMod.MODID, "domain_shatter");
    private static final ResourceLocation SND_SHATTER_WORLD = new ResourceLocation(JujutsuNeonMod.MODID, "domain_shatter_world");

    private static final int END_FADE_TICKS = 20;
    /** Множитель цвета модели «в пересвет» — белый силуэт. */
    private static final float WHITE_TINT = 60.0f;

    private static final Map<UUID, Scene> SCENES = new HashMap<>();

    /** Сцена, катсцену которой сейчас смотрит локальный игрок (владелец или пойманный). */
    private static Scene cutscene;
    private static boolean cutsceneOwner;
    private static Marker cameraEntity;
    private static CameraType savedCameraType;
    private static Boolean savedHideGui;
    private static boolean changedGravity;
    private static float camEyeOld;
    private static float camEye;
    private static float currentFov = 70f;
    private static float currentRoll = 0f;

    /** Обездвиживание локального игрока. */
    private static UUID stunOwner;
    private static boolean stunIndefinite;
    private static int stunTicks;

    /** Белый экран после конца территории (у тех, кто был внутри). */
    private static int endFade;
    private static boolean chatChecked;

    private static boolean weatherOverridden;
    private static float savedRain;
    private static float savedThunder;

    private DomainExpansionClient() {
    }

    private static final class Scene {
        final UUID ownerId;
        final ClientLevel level;
        final Vec3 center;
        final int floorY;
        final float yaw;
        final Vec3 forward;
        final Vec3 right;
        final List<UUID> watchers;
        final long seed;
        double clock;
        long lastNanos;
        boolean collapsing;
        double collapseAt;
        boolean ended;
        double endedAt;
        boolean localInside;

        Scene(UUID ownerId, ClientLevel level, Vec3 center, int floorY, float yaw, List<UUID> watchers) {
            this.ownerId = ownerId;
            this.level = level;
            this.center = center;
            this.floorY = floorY;
            this.yaw = yaw;
            double rad = Math.toRadians(yaw);
            this.forward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
            this.right = new Vec3(-forward.z, 0.0, forward.x);
            this.watchers = new ArrayList<>(watchers);
            this.seed = ownerId.getLeastSignificantBits() ^ Double.doubleToLongBits(center.x * 31.0 + center.z);
            this.lastNanos = System.nanoTime();
        }

        void advance(boolean paused) {
            long now = System.nanoTime();
            double dt = (now - lastNanos) / 5.0E7;
            lastNanos = now;
            if (!paused) clock += Mth.clamp(dt, 0.0, 20.0);
        }

        /** Точка от ног владельца: f — вперёд, r — вправо, u — вверх. */
        Vec3 at(double f, double r, double u) {
            return new Vec3(center.x + forward.x * f + right.x * r, center.y + u, center.z + forward.z * f + right.z * r);
        }

        Vec3 off(Vec3 base, double f, double r, double u) {
            return new Vec3(base.x + forward.x * f + right.x * r, base.y + u, base.z + forward.z * f + right.z * r);
        }

        boolean wallsVisible() {
            return clock >= T_WALL && !ended;
        }

        double sphereRadius() {
            if (clock < T_FORM) return 0.0;
            double k = Mth.clamp((clock - T_FORM) / (double) (T_WALL - T_FORM), 0.0, 1.0);
            return OUTER_RADIUS * (1.0 - Math.pow(1.0 - k, 3.0));
        }
    }

    // ------------------------------------------------------------------ API

    /** Локальный игрок ничего не может делать: смотрит катсцену или обездвижен. */
    public static boolean locksLocalPlayer() {
        return cutscene != null || (stunOwner != null && !SimpleDomainClient.freesLocalPlayer());
    }

    /** Локальный игрок обездвижен территорией (даже если сейчас ходит в своей простой территории). */
    public static boolean isStunnedLocal() {
        return stunOwner != null;
    }

    /** Идёт катсцена каста у локального игрока. */
    public static boolean isLocalCutscene() {
        return cutscene != null;
    }

    static void onStart(UUID ownerId, Vec3 center, int floorY, float yaw, int age, boolean collapsing, List<UUID> watchers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Scene old = SCENES.remove(ownerId);
        if (old != null && old == cutscene) stopCutscene(mc);

        Scene s = new Scene(ownerId, mc.level, center, floorY, yaw, watchers);
        s.clock = age;
        if (collapsing) {
            s.collapsing = true;
            s.collapseAt = age;
        }
        SCENES.put(ownerId, s);

        UUID me = mc.player.getUUID();
        boolean owner = me.equals(ownerId);
        boolean watcher = owner || watchers.contains(me);
        if (age < T_CAST_END && !collapsing) {
            if (mc.level.getPlayerByUUID(ownerId) instanceof AbstractClientPlayer p) {
                MaximumPurpleAnimation.play(p, "domain_cast");
            }
            // Свою кат-сцену Максимального Фиолетового сервер к этому моменту уже оборвал;
            // если сигнал ещё не дошёл — камеру не перехватываем.
            if (watcher && !MaximumPurpleClient.isLocalActive()) startCutscene(mc, s, owner);
            if (age < 8) playVoice(mc, s, watcher);
        }
    }

    static void onCollapse(UUID ownerId) {
        Scene s = SCENES.get(ownerId);
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (s.collapsing) return;
        s.collapsing = true;
        s.collapseAt = s.clock;
        if (s == cutscene) stopCutscene(mc);
        stopOwnerAnimation(s);
        if (mc.player == null) return;
        boolean inside = mc.player.getEyePosition().distanceTo(s.center) < RADIUS + 1.5
                || mc.player.getUUID().equals(ownerId) || s.watchers.contains(mc.player.getUUID());
        s.localInside = inside;
        if (inside) {
            if (savedHideGui == null) {
                savedHideGui = mc.options.hideGui;
                mc.options.hideGui = false;
            }
            mc.getSoundManager().play(new SimpleSoundInstance(SND_SHATTER, SoundSource.MASTER, 1.0f, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
        } else {
            Vec3 c = s.center;
            mc.getSoundManager().play(new SimpleSoundInstance(SND_SHATTER_WORLD, SoundSource.PLAYERS, 6.0f, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR, c.x, c.y + 8.0, c.z, false));
        }
    }

    static void onEnd(UUID ownerId) {
        Scene s = SCENES.get(ownerId);
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (s == cutscene) stopCutscene(mc);
        stopOwnerAnimation(s);
        if (!s.collapsing) {
            s.collapsing = true;
            s.collapseAt = s.clock;
        }
        s.ended = true;
        s.endedAt = s.clock;
        if (s.localInside) {
            endFade = END_FADE_TICKS;
            restoreHideGui(mc);
        }
    }

    static void onStun(UUID owner, int ticks, int estimate) {
        if (ticks == 0) {
            stunOwner = null;
            stunIndefinite = false;
            stunTicks = 0;
            return;
        }
        stunOwner = owner;
        if (ticks < 0) {
            stunIndefinite = true;
            stunTicks = Math.max(0, estimate) + 2400;
        } else {
            stunIndefinite = false;
            stunTicks = ticks;
        }
    }

    private static void playVoice(Minecraft mc, Scene s, boolean watcher) {
        if (watcher) {
            mc.getSoundManager().play(new SimpleSoundInstance(SND_VOICE, SoundSource.MASTER, 1.0f, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
        } else {
            Vec3 c = s.center.add(0.0, 1.6, 0.0);
            mc.getSoundManager().play(new SimpleSoundInstance(SND_VOICE_WORLD, SoundSource.PLAYERS, 4.0f, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR, c.x, c.y, c.z, false));
        }
    }

    private static void stopOwnerAnimation(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getPlayerByUUID(s.ownerId) instanceof AbstractClientPlayer p) {
            MaximumPurpleAnimation.stop(p);
        }
    }

    private static void startCutscene(Minecraft mc, Scene s, boolean owner) {
        stopCutscene(mc);
        cutscene = s;
        cutsceneOwner = owner;
        LocalPlayer player = mc.player;
        cameraEntity = new Marker(EntityType.MARKER, mc.level);
        Vec3 eye = player.getEyePosition();
        cameraEntity.setPos(eye.x, eye.y, eye.z);
        savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        if (savedHideGui == null) savedHideGui = mc.options.hideGui;
        mc.options.hideGui = false;
        camEye = camEyeOld = player.getEyeHeight();
        mc.setCameraEntity(cameraEntity);
        if (owner && !player.isNoGravity()) {
            player.setNoGravity(true);
            changedGravity = true;
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
        mc.getToasts().clear();
    }

    private static void stopCutscene(Minecraft mc) {
        if (cameraEntity != null) {
            if (mc.player != null) mc.setCameraEntity(mc.player);
            cameraEntity = null;
        }
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
        if (changedGravity && mc.player != null) mc.player.setNoGravity(false);
        changedGravity = false;
        Scene s = cutscene;
        cutscene = null;
        cutsceneOwner = false;
        if (s != null) stopOwnerAnimation(s);
        // Интерфейс возвращаем, если не держим белый экран разрушения.
        boolean whiteHold = false;
        for (Scene o : SCENES.values()) whiteHold |= o.localInside && o.collapsing && !o.ended;
        if (!whiteHold) restoreHideGui(mc);
    }

    private static void restoreHideGui(Minecraft mc) {
        if (savedHideGui != null) {
            mc.options.hideGui = savedHideGui;
            savedHideGui = null;
        }
    }

    private static void resetAll() {
        Minecraft mc = Minecraft.getInstance();
        stopCutscene(mc);
        for (Scene s : SCENES.values()) stopOwnerAnimation(s);
        SCENES.clear();
        stunOwner = null;
        stunTicks = 0;
        endFade = 0;
        restoreWeather(mc);
        restoreHideGui(mc);
    }

    // ------------------------------------------------------------------ тики

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!SCENES.isEmpty() || cameraEntity != null || stunOwner != null) resetAll();
            return;
        }
        checkChatKey(mc);

        KeyMapping key = JujutsuNeonMod.ClientModEvents.DOMAIN_EXPANSION_KEY;
        while (key.consumeClick()) {
            if (mc.screen == null && !locksLocalPlayer() && !MaximumPurpleClient.isLocalActive()) {
                DomainExpansion.request();
            }
        }

        boolean paused = mc.isPaused();
        Iterator<Map.Entry<UUID, Scene>> it = SCENES.entrySet().iterator();
        while (it.hasNext()) {
            Scene s = it.next().getValue();
            if (s.level != mc.level) {
                if (s == cutscene) stopCutscene(mc);
                stopOwnerAnimation(s);
                it.remove();
                continue;
            }
            s.advance(paused);
            if (s.ended && s.clock - s.endedAt > 60) {
                it.remove();
                continue;
            }
            // Сервер пропал без сигнала — не держим вечно.
            if (!s.ended && s.clock > T_CAST_END + ACTIVE_TICKS + 2400) {
                if (s == cutscene) stopCutscene(mc);
                stopOwnerAnimation(s);
                it.remove();
                if (!whiteHold()) restoreHideGui(mc);
            }
        }

        Scene cs = cutscene;
        if (cs != null) {
            if (cs.clock >= T_CAST_END || !SCENES.containsKey(cs.ownerId)) {
                stopCutscene(mc);
            } else {
                camEyeOld = camEye;
                camEye += (0.0f - camEye) * 0.5f;
                if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
                if (cameraEntity != null && mc.getCameraEntity() != cameraEntity) mc.setCameraEntity(cameraEntity);
                if (mc.options.hideGui) mc.options.hideGui = false;
                mc.getToasts().clear();
                if (cutsceneOwner) holdOwner(mc.player, cs);
                syncPosition(mc);
            }
        }

        if (stunOwner != null && !paused) {
            stunTicks--;
            if (stunTicks <= -200) {
                // Сигнал о снятии потерялся — снимаем сами с запасом.
                stunOwner = null;
            }
        }
        if (endFade > 0) endFade--;

        updateWeather(mc);
    }

    /**
     * Стена — сразу после движения игрока и до отправки позиции серверу: сервер видит игрока
     * уже внутри (или снаружи), без рывков назад.
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.CLIENT || SCENES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.player != mc.player) return;
        clampLocal(mc);
    }

    /**
     * Пока камера не у игрока, клиент сам не шлёт серверу позицию — шлём её сами,
     * иначе сервер кикнет «за полёт» и после кат-сцены дёрнет игрока назад.
     */
    static void syncPosition(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || p.isPassenger() || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(p.getX(), p.getY(), p.getZ(),
                p.getYRot(), p.getXRot(), p.onGround()));
    }

    private static void holdOwner(LocalPlayer player, Scene s) {
        player.setPos(s.center.x, s.center.y, s.center.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0f;
        player.setSprinting(false);
    }

    /**
     * Раньше мод снимал чат с T (T была Расширением территории). Теперь территория на U, чат не трогаем;
     * один раз возвращаем чат на T тем, у кого он остался без клавиши после старых версий.
     */
    private static void checkChatKey(Minecraft mc) {
        if (chatChecked) return;
        chatChecked = true;
        java.io.File flag = new java.io.File(mc.gameDirectory, "config/jujutsu_neon_chat_restored.txt");
        if (flag.exists()) return;
        if (mc.options.keyChat.isUnbound()) {
            mc.options.keyChat.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_T));
            KeyMapping.resetMapping();
            mc.options.save();
        }
        try {
            flag.getParentFile().mkdirs();
            java.nio.file.Files.writeString(flag.toPath(), "chat key restored once by Jujutsu Neon 1.7.1\n");
        } catch (java.io.IOException ignored) {
        }
    }

    /** Через стену территории не пройти: изнутри не выйти, снаружи не войти. */
    private static void clampLocal(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || p.isSpectator()) return;
        UUID me = p.getUUID();
        for (Scene s : SCENES.values()) {
            if (s.clock < T_WALL || s.ended) continue;
            boolean insider = me.equals(s.ownerId) || s.watchers.contains(me) || s.ownerId.equals(stunOwner);
            double half = p.getBbHeight() * 0.5;
            Vec3 c = p.position().add(0.0, half, 0.0);
            Vec3 off = c.subtract(s.center);
            double d = off.length();
            double extent = Math.max(p.getBbWidth(), p.getBbHeight()) * 0.5;
            Vec3 n = d < 1.0E-4 ? new Vec3(0.0, 1.0, 0.0) : off.scale(1.0 / d);
            double limit;
            boolean violate;
            if (insider) {
                limit = RADIUS - extent - 0.05;
                violate = d > limit;
            } else {
                limit = OUTER_RADIUS + extent + 0.05;
                violate = d < limit;
            }
            if (!violate) continue;
            Vec3 target = s.center.add(n.scale(limit)).subtract(0.0, half, 0.0);
            p.setPos(target.x, target.y, target.z);
            Vec3 v = p.getDeltaMovement();
            double radial = v.dot(n);
            if (insider ? radial > 0 : radial < 0) p.setDeltaMovement(v.subtract(n.scale(radial)));
        }
    }

    /** Внутри пустоты нет ни дождя, ни грозы. */
    private static void updateWeather(Minecraft mc) {
        boolean inside = false;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        for (Scene s : SCENES.values()) {
            if (s.wallsVisible() && eye.distanceTo(s.center) < RADIUS) inside = true;
        }
        ClientLevel level = mc.level;
        if (level == null) return;
        if (inside) {
            if (!weatherOverridden) {
                savedRain = level.getRainLevel(1.0f);
                savedThunder = level.getThunderLevel(1.0f);
                weatherOverridden = true;
            }
            level.setRainLevel(0.0f);
            level.setThunderLevel(0.0f);
        } else {
            restoreWeather(mc);
        }
    }

    private static void restoreWeather(Minecraft mc) {
        if (!weatherOverridden) return;
        weatherOverridden = false;
        if (mc.level != null) {
            mc.level.setRainLevel(savedRain);
            mc.level.setThunderLevel(savedThunder);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
        FROZEN.clear();
        chatChecked = false;
    }

    // ------------------------------------------------------------------ ввод

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!locksLocalPlayer()) return;
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!locksLocalPlayer()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (locksLocalPlayer() && event.getNewScreen() instanceof AbstractContainerScreen<?>) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ камера катсцены

    private static final int A_GROUND = 0;
    private static final int A_HEAD = 1;
    private static final int A_HAND = 2;

    private record CamKey(int t, int pa, double cf, double cr, double cu,
                          int ta, double tf, double tr, double tu, float fov, float roll, boolean cut) {
    }

    private static CamKey ck(int t, int pa, double cf, double cr, double cu,
                             int ta, double tf, double tr, double tu, float fov, float roll, boolean cut) {
        return new CamKey(t, pa, cf, cr, cu, ta, tf, tr, tu, fov, roll, cut);
    }

    private static final CamKey[] CAM = {
            // 0–0,5 с: общий план
            ck(0, A_GROUND, 6.5, -2.2, 1.5, A_GROUND, 0.0, 0.0, 1.1, 58f, 0f, true),
            ck(9, A_GROUND, 5.9, -2.0, 1.45, A_GROUND, 0.0, 0.0, 1.15, 56f, 0f, false),
            // 0,5–3,1 с: камера ведёт кончик руки, кадры сквозь прорези
            ck(10, A_HAND, 0.30, 0.65, 0.10, A_HAND, 0.0, 0.0, 0.0, 40f, -6f, true),
            ck(15, A_HAND, 0.55, 0.15, -0.25, A_HAND, 0.0, 0.0, 0.0, 42f, 4f, true),
            ck(21, A_HAND, 0.45, -0.30, 0.10, A_HAND, 0.0, 0.0, 0.0, 40f, -3f, true),
            ck(31, A_HAND, -0.25, 0.55, 0.25, A_HAND, 0.0, 0.0, 0.0, 44f, 8f, true),
            ck(36, A_HAND, 0.20, 0.60, -0.30, A_HAND, 0.0, 0.0, 0.0, 40f, -5f, true),
            ck(41, A_HAND, 0.55, 0.35, 0.20, A_HAND, 0.0, 0.0, 0.0, 42f, 3f, true),
            ck(46, A_HAND, 0.40, -0.45, 0.25, A_HAND, 0.0, 0.0, 0.0, 44f, -8f, true),
            ck(57, A_HAND, 0.65, 0.05, 0.05, A_HAND, 0.0, 0.0, 0.0, 38f, 0f, true),
            // 3,2–5,3 с: крупно лицо и рука у лица, медленный облёт
            ck(64, A_HEAD, 0.85, 0.45, -0.05, A_HEAD, 0.0, 0.05, 0.0, 50f, 0f, true),
            ck(80, A_HEAD, 0.80, 0.05, 0.05, A_HEAD, 0.0, 0.0, 0.0, 48f, 0f, false),
            ck(92, A_HEAD, 0.75, -0.25, 0.0, A_HEAD, 0.0, 0.0, 0.0, 46f, 0f, false),
            ck(106, A_HEAD, 0.95, -0.10, -0.08, A_HEAD, 0.0, 0.0, 0.0, 50f, 0f, false),
            // 5,4–6,2 с: белая пустота, крупно лицо
            ck(108, A_HEAD, 0.75, 0.0, 0.0, A_HEAD, 0.0, 0.0, 0.0, 46f, 0f, true),
            ck(123, A_HEAD, 0.62, 0.0, 0.0, A_HEAD, 0.0, 0.0, 0.0, 44f, 0f, false),
            // 8,8–9,8 с: глаза крупно, сзади уже пустота
            ck(176, A_HEAD, 0.50, 0.0, 0.10, A_HEAD, 0.0, 0.0, 0.10, 40f, 0f, true),
            ck(196, A_HEAD, 0.45, 0.0, 0.10, A_HEAD, 0.0, 0.0, 0.10, 38f, 0f, false),
            // 9,9–10,8 с: из-за плеча — отъезд
            ck(198, A_HEAD, -0.55, 0.45, 0.25, A_HEAD, 3.0, 0.0, -0.3, 60f, 0f, true),
            ck(216, A_HEAD, -4.0, 1.0, 1.2, A_HEAD, 2.0, 0.0, -0.6, 70f, 0f, false),
    };

    /** Кончик правой руки по ходу анимации (совпадает с domain_cast.json). */
    private static final double[][] HAND = {
            {0, 0.05, 0.40, 0.72},
            {10, 0.08, 0.40, 0.74},
            {22, 0.35, 0.36, 1.00},
            {36, 0.42, 0.24, 1.30},
            {50, 0.38, 0.10, 1.52},
            {60, 0.34, 0.05, 1.62},
            {216, 0.34, 0.04, 1.63},
    };

    private static double smooth(double v) {
        double t = Mth.clamp(v, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double win(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0.0, 1.0);
    }

    private static Vec3 handPos(Scene s, double t) {
        for (int i = 0; i + 1 < HAND.length; i++) {
            double[] a = HAND[i], b = HAND[i + 1];
            if (t <= b[0]) {
                double k = smooth((t - a[0]) / (b[0] - a[0]));
                return s.at(Mth.lerp(k, a[1], b[1]), Mth.lerp(k, a[2], b[2]), Mth.lerp(k, a[3], b[3]));
            }
        }
        double[] e = HAND[HAND.length - 1];
        return s.at(e[1], e[2], e[3]);
    }

    private static Vec3 anchor(Scene s, int a, double f, double r, double u, double t) {
        return switch (a) {
            case A_HEAD -> s.off(s.center.add(0.0, 1.58, 0.0), f, r, u);
            case A_HAND -> s.off(handPos(s, t), f, r, u);
            default -> s.at(f, r, u);
        };
    }

    private record CamState(Vec3 pos, Vec3 target, float fov, float roll) {
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
        return new Vec3(catmull(p0.x, p1.x, p2.x, p3.x, u), catmull(p0.y, p1.y, p2.y, p3.y, u), catmull(p0.z, p1.z, p2.z, p3.z, u));
    }

    private static double catmull(double p0, double p1, double p2, double p3, double u) {
        double u2 = u * u, u3 = u2 * u;
        return 0.5 * (2.0 * p1 + (-p0 + p2) * u + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3);
    }

    private static CamState cameraAt(Scene s, double t) {
        int i = 0;
        while (i + 1 < CAM.length && CAM[i + 1].t <= t) i++;
        CamKey k1 = CAM[i];
        boolean hasNext = i + 1 < CAM.length && !CAM[i + 1].cut;
        if (!hasNext) {
            return new CamState(anchor(s, k1.pa, k1.cf, k1.cr, k1.cu, t), anchor(s, k1.ta, k1.tf, k1.tr, k1.tu, t), k1.fov, k1.roll);
        }
        CamKey k2 = CAM[i + 1];
        CamKey k0 = k1.cut || i == 0 ? k1 : CAM[i - 1];
        CamKey k3 = i + 2 < CAM.length && !CAM[i + 2].cut ? CAM[i + 2] : k2;
        double u = Mth.clamp((t - k1.t) / (double) (k2.t - k1.t), 0.0, 1.0);
        Vec3 pos = catmull(anchor(s, k0.pa, k0.cf, k0.cr, k0.cu, t), anchor(s, k1.pa, k1.cf, k1.cr, k1.cu, t),
                anchor(s, k2.pa, k2.cf, k2.cr, k2.cu, t), anchor(s, k3.pa, k3.cf, k3.cr, k3.cu, t), u);
        Vec3 target = catmull(anchor(s, k0.ta, k0.tf, k0.tr, k0.tu, t), anchor(s, k1.ta, k1.tf, k1.tr, k1.tu, t),
                anchor(s, k2.ta, k2.tf, k2.tr, k2.tu, t), anchor(s, k3.ta, k3.tf, k3.tr, k3.tu, t), u);
        float fov = (float) catmull(k0.fov, k1.fov, k2.fov, k3.fov, u);
        float roll = (float) catmull(k0.roll, k1.roll, k2.roll, k3.roll, u);
        return new CamState(pos, target, fov, roll);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        boolean paused = mc.isPaused();
        for (Scene s : SCENES.values()) s.advance(paused);

        Scene s = cutscene;
        Marker camera = cameraEntity;
        if (s == null || camera == null || mc.player == null) return;
        double t = s.clock;
        CamState cs = cameraAt(s, t);
        currentFov = cs.fov;
        currentRoll = cs.roll;

        Vec3 pos = cs.pos;
        Vec3 d = cs.target.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        double eye = Mth.lerp(event.renderTickTime, camEyeOld, camEye);
        double y = pos.y - eye;
        camera.setPos(pos.x, y, pos.z);
        camera.xo = camera.xOld = pos.x;
        camera.yo = camera.yOld = y;
        camera.zo = camera.zOld = pos.z;
        camera.setYRot(yaw);
        camera.yRotO = yaw;
        camera.setXRot(pitch);
        camera.xRotO = pitch;

        if (cutsceneOwner) {
            LocalPlayer p = mc.player;
            p.setPos(s.center.x, s.center.y, s.center.z);
            p.xo = p.xOld = s.center.x;
            p.yo = p.yOld = s.center.y;
            p.zo = p.zOld = s.center.z;
            p.setYRot(s.yaw);
            p.yRotO = s.yaw;
            p.yBodyRot = s.yaw;
            p.yBodyRotO = s.yaw;
            p.yHeadRot = s.yaw;
            p.yHeadRotO = s.yaw;
            p.setXRot(0.0f);
            p.xRotO = 0.0f;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        float shake = 0.0f;
        double t = 0.0;
        Scene s = cutscene;
        if (s != null && cameraEntity != null) {
            t = s.clock;
            if (t >= T_FORM && t < T_FORM + 6) shake = 1.4f * (1.0f - (float) (t - T_FORM) / 6.0f);
            float hand = 0.12f;
            event.setYaw(event.getYaw() + (float) Math.sin(t * 5.3) * shake + (float) Math.sin(t * 0.33) * hand);
            event.setPitch(event.getPitch() + (float) Math.cos(t * 6.1) * shake * 0.7f + (float) Math.sin(t * 0.27 + 1.1) * hand);
            event.setRoll(currentRoll + (float) Math.sin(t * 7.3) * shake * 0.5f);
            return;
        }
        // Трещины при разрушении — экран трясёт.
        for (Scene o : SCENES.values()) {
            if (!o.localInside || !o.collapsing || o.ended) continue;
            double k = o.clock - o.collapseAt;
            if (k < T_SHATTER + 4) {
                float a = 1.8f * (float) (1.0 - k / (T_SHATTER + 4.0));
                event.setYaw(event.getYaw() + (float) Math.sin(o.clock * 9.1) * a);
                event.setPitch(event.getPitch() + (float) Math.cos(o.clock * 8.3) * a * 0.7f);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (cutscene == null || cameraEntity == null || !event.usedConfiguredFov()) return;
        event.setFOV(currentFov);
    }

    // ------------------------------------------------------------------ скрыть интерфейс, руки, ники

    private static boolean whiteHold() {
        for (Scene s : SCENES.values()) if (s.localInside && s.collapsing && !s.ended) return true;
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderHand(RenderHandEvent event) {
        if (cameraEntity != null) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlay(RenderGuiOverlayEvent.Pre event) {
        if (cameraEntity != null || whiteHold()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onNameTag(RenderNameTagEvent event) {
        if (cameraEntity != null && event.getEntity() instanceof net.minecraft.world.entity.player.Player) {
            event.setResult(Event.Result.DENY);
        }
    }

    // ------------------------------------------------------------------ мир

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || SCENES.isEmpty()) return;
        RenderLevelStageEvent.Stage stage = event.getStage();
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();

        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            for (Scene s : SCENES.values()) {
                if (s.wallsVisible() && camPos.distanceTo(s.center) < RADIUS + 0.5) renderInterior(pose, cam, camPos, s);
            }
            return;
        }

        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            for (Scene s : SCENES.values()) {
                if (camPos.distanceTo(s.center) >= RADIUS + 0.5) renderExterior(pose, camPos, s);
            }
            Scene cs = cutscene;
            if (cs != null && cameraEntity != null && mc.player != null) {
                double t = cs.clock;
                boolean backdrop = t >= T_FORM && t < 124;
                if (backdrop) renderWhiteBackdrop(event, cam, cs, t);
                if (cutsceneOwner) {
                    renderPlayer(mc, event, mc.player);
                } else if (backdrop && mc.level.getPlayerByUUID(cs.ownerId) instanceof AbstractClientPlayer owner) {
                    // Чужой владелец уже нарисован миром — поверх белого рисуем его ещё раз.
                    renderPlayer(mc, event, owner);
                }
            }
            return;
        }

        if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            for (Scene s : SCENES.values()) {
                if (s.wallsVisible() && camPos.distanceTo(s.center) < RADIUS + 0.5) renderFloatingBlots(pose, cam, camPos, s);
            }
        }
    }

    private static void renderPlayer(Minecraft mc, RenderLevelStageEvent event, AbstractClientPlayer player) {
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

    private static void vertex(BufferBuilder b, Matrix4f m, double x, double y, double z, float u, float v,
                               float r, float g, float bl, float a) {
        b.vertex(m, (float) x, (float) y, (float) z).uv(u, v).color(r, g, bl, a).endVertex();
    }

    private static void vertexC(BufferBuilder b, Matrix4f m, double x, double y, double z, float r, float g, float bl, float a) {
        b.vertex(m, (float) x, (float) y, (float) z).color(r, g, bl, a).endVertex();
    }

    /** Изнутри: небо пустоты, дым, чёрная дыра с кольцом, «луна», пол с кляксами. */
    private static void renderInterior(PoseStack pose, Camera cam, Vec3 camPos, Scene s) {
        Matrix4f m = pose.last().pose();
        Vec3 c = s.center.subtract(camPos);
        double t = s.clock;

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Небо — непрозрачная сфера: закрывает весь мир за стенами.
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_SKY);
        sphereBand(m, c, RADIUS, -Math.PI / 2, Math.PI / 2, 24, 48, 0.0, 1.0f, 1.0f);

        // Пол — непрозрачный круг на высоте пола.
        double fy = s.floorY + 1.0 - s.center.y;
        double fr = Math.sqrt(Math.max(0.0, RADIUS * RADIUS - fy * fy));
        RenderSystem.setShaderTexture(0, TEX_FLOOR);
        floorDisc(m, c, fy + 0.002, fr, s);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        // Струи дыма у горизонта медленно плывут.
        RenderSystem.setShaderTexture(0, TEX_SMOKE);
        sphereBand(m, c, RADIUS - 1.4, -0.35, 0.70, 8, 48, t * 0.0006, 1.0f, 0.75f);
        sphereBand(m, c, RADIUS - 3.2, -0.25, 0.55, 8, 48, 0.37 - t * 0.0009, 1.0f, 0.55f);

        // Чёрная дыра с кольцом и белыми крыльями — впереди по взгляду владельца, чуть выше горизонта.
        double el = Math.toRadians(14.0);
        Vec3 dir = s.forward.scale(Math.cos(el)).add(0.0, Math.sin(el), 0.0);
        Vec3 bh = s.center.add(dir.scale(RADIUS - 5.0));
        quadFacing(m, cam, camPos, bh.add(dir.scale(0.6)), 78.0, 39.0, 0.0f, TEX_WINGS, 1f, 1f, 1f, 0.95f);
        quadFacing(m, cam, camPos, bh, 25.0, 25.0, (float) (t * 0.002), TEX_BLACKHOLE, 1f, 1f, 1f, 1.0f);

        // Светящаяся «луна».
        double el2 = Math.toRadians(32.0);
        Vec3 dir2 = s.forward.scale(-Math.cos(el2) * 0.55).add(s.right.scale(Math.cos(el2) * 0.83)).add(0.0, Math.sin(el2), 0.0).normalize();
        HollowPurpleReferenceClient.mpAdditiveBlend();
        quadFacing(m, cam, camPos, s.center.add(dir2.scale(RADIUS - 4.0)), 18.0, 18.0, 0.0f, TEX_BLOOM, 0.75f, 0.82f, 1.0f, 0.9f);
        RenderSystem.defaultBlendFunc();

        // Кляксы на полу.
        RenderSystem.setShaderTexture(0, TEX_BLOTS);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Random rnd = new Random(s.seed * 31L + 7L);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < 34; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            double rr = Math.sqrt(rnd.nextDouble()) * (fr - 4.0);
            double size = 1.5 + rnd.nextDouble() * 5.5;
            double rot = rnd.nextDouble() * Math.PI * 2.0;
            int cell = rnd.nextInt(4);
            float u0 = (cell % 2) * 0.5f, v0 = (cell / 2) * 0.5f;
            double px = c.x + Math.cos(a) * rr, pz = c.z + Math.sin(a) * rr, py = c.y + fy + 0.012 + i * 0.0004;
            double h = size * 0.5, cr = Math.cos(rot) * h, sr = Math.sin(rot) * h;
            float alpha = 0.92f;
            vertex(b, m, px - cr + sr, py, pz - sr - cr, u0, v0, 1f, 1f, 1f, alpha);
            vertex(b, m, px - cr - sr, py, pz - sr + cr, u0, v0 + 0.5f, 1f, 1f, 1f, alpha);
            vertex(b, m, px + cr - sr, py, pz + sr + cr, u0 + 0.5f, v0 + 0.5f, 1f, 1f, 1f, alpha);
            vertex(b, m, px + cr + sr, py, pz + sr - cr, u0 + 0.5f, v0, 1f, 1f, 1f, alpha);
        }
        BufferUploader.drawWithShader(b.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Кляксы, висящие в пустоте (с учётом глубины — сущности их закрывают). */
    private static void renderFloatingBlots(PoseStack pose, Camera cam, Vec3 camPos, Scene s) {
        Matrix4f m = pose.last().pose();
        double t = s.clock;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEX_BLOTS);
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        Random rnd = new Random(s.seed);
        double fy = s.floorY + 1.0;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < 40; i++) {
            double a = rnd.nextDouble() * Math.PI * 2.0;
            double rr = 14.0 + rnd.nextDouble() * (RADIUS - 18.0);
            double hgt = 2.0 + rnd.nextDouble() * 24.0;
            double size = 2.0 + rnd.nextDouble() * 7.0;
            double phase = rnd.nextDouble() * 10.0;
            double rot = rnd.nextDouble() * Math.PI * 2.0 + t * (rnd.nextDouble() - 0.5) * 0.004;
            int cell = rnd.nextInt(4);
            double drift = t * 0.0015 * (rnd.nextBoolean() ? 1 : -1);
            Vec3 p = new Vec3(s.center.x + Math.cos(a + drift) * rr, fy + hgt + Math.sin(t * 0.03 + phase) * 0.6,
                    s.center.z + Math.sin(a + drift) * rr);
            if (p.distanceTo(s.center) > RADIUS - size) continue;
            float u0 = (cell % 2) * 0.5f, v0 = (cell / 2) * 0.5f;
            double h = size * 0.5;
            double cr = Math.cos(rot), sr = Math.sin(rot);
            double lx = (leftV.x() * cr + upV.x() * sr) * h, ly = (leftV.y() * cr + upV.y() * sr) * h, lz = (leftV.z() * cr + upV.z() * sr) * h;
            double ux = (upV.x() * cr - leftV.x() * sr) * h, uy = (upV.y() * cr - leftV.y() * sr) * h, uz = (upV.z() * cr - leftV.z() * sr) * h;
            double x = p.x - camPos.x, y = p.y - camPos.y, z = p.z - camPos.z;
            vertex(b, m, x + lx + ux, y + ly + uy, z + lz + uz, u0, v0, 1f, 1f, 1f, 0.95f);
            vertex(b, m, x + lx - ux, y + ly - uy, z + lz - uz, u0, v0 + 0.5f, 1f, 1f, 1f, 0.95f);
            vertex(b, m, x - lx - ux, y - ly - uy, z - lz - uz, u0 + 0.5f, v0 + 0.5f, 1f, 1f, 1f, 0.95f);
            vertex(b, m, x - lx + ux, y - ly + uy, z - lz + uz, u0 + 0.5f, v0, 1f, 1f, 1f, 0.95f);
        }
        BufferUploader.drawWithShader(b.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Снаружи: гладкая чёрная сфера с еле заметным фиолетовым ободком. */
    private static void renderExterior(PoseStack pose, Vec3 camPos, Scene s) {
        double radius = s.sphereRadius();
        if (radius <= 0.05) return;
        float alpha = 1.0f;
        if (s.ended) {
            alpha = 1.0f - (float) Mth.clamp((s.clock - s.endedAt) / 10.0, 0.0, 1.0);
            if (alpha <= 0.01f) return;
        }
        float flash = 0.0f;
        if (s.collapsing && !s.ended) {
            double k = s.clock - s.collapseAt;
            flash = (float) Math.max(0.0, 1.0 - Math.abs(k - T_SHATTER) / 6.0);
        }
        Matrix4f m = pose.last().pose();
        Vec3 c = s.center.subtract(camPos);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        if (alpha < 0.999f) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        int lat = 32, lon = 64;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < lat; i++) {
            double p0 = -Math.PI / 2 + Math.PI * i / lat, p1 = -Math.PI / 2 + Math.PI * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                double a0 = Math.PI * 2 * j / lon, a1 = Math.PI * 2 * (j + 1) / lon;
                exteriorVertex(b, m, c, radius, p0, a0, flash, alpha);
                exteriorVertex(b, m, c, radius, p0, a1, flash, alpha);
                exteriorVertex(b, m, c, radius, p1, a1, flash, alpha);
                exteriorVertex(b, m, c, radius, p0, a0, flash, alpha);
                exteriorVertex(b, m, c, radius, p1, a1, flash, alpha);
                exteriorVertex(b, m, c, radius, p1, a0, flash, alpha);
            }
        }
        BufferUploader.drawWithShader(b.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void exteriorVertex(BufferBuilder b, Matrix4f m, Vec3 c, double radius, double phi, double theta,
                                       float flash, float alpha) {
        double cp = Math.cos(phi);
        double nx = Math.cos(theta) * cp, ny = Math.sin(phi), nz = Math.sin(theta) * cp;
        double x = c.x + nx * radius, y = c.y + ny * radius, z = c.z + nz * radius;
        double len = Math.sqrt(x * x + y * y + z * z);
        double facing = len < 1.0E-4 ? 1.0 : Math.abs((nx * -x + ny * -y + nz * -z) / len);
        float rim = (float) Math.pow(1.0 - facing, 3.0);
        float r = 0.012f + rim * 0.16f + flash * 0.8f;
        float g = 0.006f + rim * 0.05f + flash * 0.8f;
        float bl = 0.02f + rim * 0.26f + flash * 0.85f;
        vertexC(b, m, x, y, z, r, g, bl, alpha);
    }

    /** Полоса сферы (или вся сфера) с развёрткой текстуры по долготе/широте. */
    private static void sphereBand(Matrix4f m, Vec3 c, double radius, double latMin, double latMax, int lat, int lon,
                                   double uOffset, float bright, float alpha) {
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < lat; i++) {
            double p0 = latMin + (latMax - latMin) * i / lat, p1 = latMin + (latMax - latMin) * (i + 1) / lat;
            float v0 = 1.0f - (float) i / lat, v1 = 1.0f - (float) (i + 1) / lat;
            if (latMin <= -Math.PI / 2 + 1e-6 && latMax >= Math.PI / 2 - 1e-6) {
                v0 = (float) (0.5 - p0 / Math.PI);
                v1 = (float) (0.5 - p1 / Math.PI);
            }
            for (int j = 0; j < lon; j++) {
                double a0 = Math.PI * 2 * j / lon, a1 = Math.PI * 2 * (j + 1) / lon;
                float u0 = (float) (j / (double) lon + uOffset), u1 = (float) ((j + 1) / (double) lon + uOffset);
                bandVertex(b, m, c, radius, p0, a0, u0, v0, bright, alpha);
                bandVertex(b, m, c, radius, p0, a1, u1, v0, bright, alpha);
                bandVertex(b, m, c, radius, p1, a1, u1, v1, bright, alpha);
                bandVertex(b, m, c, radius, p0, a0, u0, v0, bright, alpha);
                bandVertex(b, m, c, radius, p1, a1, u1, v1, bright, alpha);
                bandVertex(b, m, c, radius, p1, a0, u0, v1, bright, alpha);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void bandVertex(BufferBuilder b, Matrix4f m, Vec3 c, double radius, double phi, double theta,
                                   float u, float v, float bright, float alpha) {
        double cp = Math.cos(phi);
        vertex(b, m, c.x + Math.cos(theta) * cp * radius, c.y + Math.sin(phi) * radius, c.z + Math.sin(theta) * cp * radius,
                u, v, bright, bright, bright, alpha);
    }

    private static void floorDisc(Matrix4f m, Vec3 c, double y, double radius, Scene s) {
        int seg = 72;
        double tile = 1.0 / 14.0;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int ring = 0; ring < 4; ring++) {
            double r0 = radius * ring / 4.0, r1 = radius * (ring + 1) / 4.0;
            float b0 = (float) (1.0 - 0.35 * (ring / 4.0)), b1 = (float) (1.0 - 0.35 * ((ring + 1) / 4.0));
            for (int j = 0; j < seg; j++) {
                double a0 = Math.PI * 2 * j / seg, a1 = Math.PI * 2 * (j + 1) / seg;
                double[][] pts = {
                        {Math.cos(a0) * r0, Math.sin(a0) * r0, b0}, {Math.cos(a1) * r0, Math.sin(a1) * r0, b0},
                        {Math.cos(a1) * r1, Math.sin(a1) * r1, b1}, {Math.cos(a0) * r1, Math.sin(a0) * r1, b1}};
                int[] order = {0, 1, 2, 0, 2, 3};
                for (int k : order) {
                    double px = pts[k][0], pz = pts[k][1];
                    float br = (float) pts[k][2];
                    vertex(b, m, c.x + px, c.y + y, c.z + pz,
                            (float) ((s.center.x + px) * tile), (float) ((s.center.z + pz) * tile), br, br, br, 1.0f);
                }
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    /** Прямоугольник, повёрнутый к камере. */
    private static void quadFacing(Matrix4f m, Camera cam, Vec3 camPos, Vec3 center, double width, double height, float roll,
                                   ResourceLocation texture, float r, float g, float bl, float a) {
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double lx = leftV.x() * cr + upV.x() * sr, ly = leftV.y() * cr + upV.y() * sr, lz = leftV.z() * cr + upV.z() * sr;
        double ux = upV.x() * cr - leftV.x() * sr, uy = upV.y() * cr - leftV.y() * sr, uz = upV.z() * cr - leftV.z() * sr;
        double hw = width * 0.5, hh = height * 0.5;
        double x = center.x - camPos.x, y = center.y - camPos.y, z = center.z - camPos.z;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertex(b, m, x + lx * hw + ux * hh, y + ly * hw + uy * hh, z + lz * hw + uz * hh, 0f, 0f, r, g, bl, a);
        vertex(b, m, x + lx * hw - ux * hh, y + ly * hw - uy * hh, z + lz * hw - uz * hh, 0f, 1f, r, g, bl, a);
        vertex(b, m, x - lx * hw - ux * hh, y - ly * hw - uy * hh, z - lz * hw - uz * hh, 1f, 1f, r, g, bl, a);
        vertex(b, m, x - lx * hw + ux * hh, y - ly * hw + uy * hh, z - lz * hw + uz * hh, 1f, 0f, r, g, bl, a);
        BufferUploader.drawWithShader(b.end());
    }

    /** 5,4–6,2 с: мир растворяется белыми кляксами, потом белая пустота; персонаж рисуется поверх. */
    private static void renderWhiteBackdrop(RenderLevelStageEvent event, Camera cam, Scene s, double t) {
        Matrix4f m = event.getPoseStack().last().pose();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        Minecraft mc = Minecraft.getInstance();
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        double dist = 12.0;
        double halfH = dist * 1.3, halfW = halfH * aspect;
        double cx = look.x() * dist, cy = look.y() * dist, cz = look.z() * dist;

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        double grow = win(t, T_FORM, 112);
        if (grow < 1.0) {
            // Белые кляксы разрастаются по экрану.
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, TEX_BLOTS);
            Random rnd = new Random(s.seed + 99L);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < 14; i++) {
                double sx = (rnd.nextDouble() * 2.0 - 1.0) * halfW, sy = (rnd.nextDouble() * 2.0 - 1.0) * halfH;
                double delay = rnd.nextDouble() * 0.45;
                double k = Mth.clamp((grow - delay) / (1.0 - delay), 0.0, 1.0);
                double size = (halfH * 2.2) * (1.0 - Math.pow(1.0 - k, 2.0)) * (0.7 + rnd.nextDouble() * 0.6);
                if (size < 0.01) continue;
                int cell = rnd.nextInt(4);
                float u0 = (cell % 2) * 0.5f, v0 = (cell / 2) * 0.5f;
                double h = size * 0.5;
                double px = cx + left.x() * sx + up.x() * sy, py = cy + left.y() * sx + up.y() * sy, pz = cz + left.z() * sx + up.z() * sy;
                vertex(b, m, px + left.x() * h + up.x() * h, py + left.y() * h + up.y() * h, pz + left.z() * h + up.z() * h, u0, v0, 1f, 1f, 1f, 1f);
                vertex(b, m, px + left.x() * h - up.x() * h, py + left.y() * h - up.y() * h, pz + left.z() * h - up.z() * h, u0, v0 + 0.5f, 1f, 1f, 1f, 1f);
                vertex(b, m, px - left.x() * h - up.x() * h, py - left.y() * h - up.y() * h, pz - left.z() * h - up.z() * h, u0 + 0.5f, v0 + 0.5f, 1f, 1f, 1f, 1f);
                vertex(b, m, px - left.x() * h + up.x() * h, py - left.y() * h + up.y() * h, pz - left.z() * h + up.z() * h, u0 + 0.5f, v0, 1f, 1f, 1f, 1f);
            }
            BufferUploader.drawWithShader(b.end());
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            vertexC(b, m, cx + left.x() * halfW + up.x() * halfH, cy + left.y() * halfW + up.y() * halfH, cz + left.z() * halfW + up.z() * halfH, 1f, 1f, 1f, 1f);
            vertexC(b, m, cx + left.x() * halfW - up.x() * halfH, cy + left.y() * halfW - up.y() * halfH, cz + left.z() * halfW - up.z() * halfH, 1f, 1f, 1f, 1f);
            vertexC(b, m, cx - left.x() * halfW - up.x() * halfH, cy - left.y() * halfW - up.y() * halfH, cz - left.z() * halfW - up.z() * halfH, 1f, 1f, 1f, 1f);
            vertexC(b, m, cx - left.x() * halfW + up.x() * halfH, cy - left.y() * halfW + up.y() * halfH, cz - left.z() * halfW + up.z() * halfH, 1f, 1f, 1f, 1f);
            BufferUploader.drawWithShader(b.end());
        }

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ интерфейс

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean collapse = false;
        for (Scene s : SCENES.values()) collapse |= s.collapsing && !s.ended;
        if (cutscene == null && !collapse && endFade <= 0 && stunOwner == null) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();
        float pt = event.getPartialTick();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Scene cs = cutscene;
        if (cs != null) drawCutscene(g, w, h, cs, cs.clock);

        // Разрушение: трещины стекла, потом белый экран, пока сервер возвращает мир.
        for (Scene s : SCENES.values()) {
            if (s.localInside && s.collapsing && !s.ended) {
                double k = s.clock - s.collapseAt;
                if (k < T_SHATTER) {
                    float zoom = 1.0f + 0.04f * (float) (k / T_SHATTER);
                    int dw = (int) (w * zoom), dh = (int) (h * zoom);
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                    // fill() выключает смешивание — трещинам нужна прозрачность.
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    g.blit(TEX_CRACKS, (w - dw) / 2, (h - dh) / 2, dw, dh, 0f, 0f, 1920, 1080, 1920, 1080);
                    fill(g, w, h, (float) (0.25 * k / T_SHATTER), 0xFFFFFF);
                } else {
                    fill(g, w, h, (float) smooth((k - T_SHATTER) / 2.0), 0xFFFFFF);
                }
            } else if (s.collapsing && !s.localInside && mc.player != null) {
                // Снаружи рядом — короткая вспышка.
                double dist = mc.player.getEyePosition().distanceTo(s.center) - OUTER_RADIUS;
                float prox = (float) (1.0 - Mth.clamp(dist / 50.0, 0.0, 1.0));
                double k = s.clock - s.collapseAt;
                float a = (float) (smooth((k - T_SHATTER + 1) / 2.0) * (1.0 - smooth((k - T_SHATTER - 3) / 14.0)));
                if (prox * a > 0.01f) fill(g, w, h, prox * a * 0.75f, 0xFFFFFF);
            }
        }
        if (endFade > 0) fill(g, w, h, Mth.clamp((endFade - pt) / END_FADE_TICKS, 0f, 1f), 0xFFFFFF);

        // Маленький таймер обездвиживания внизу.
        if (stunOwner != null && cs == null) {
            int ticks = Math.max(0, stunTicks);
            int sec = (ticks + 19) / 20;
            String text = (sec / 60) + ":" + String.format("%02d", sec % 60);
            g.pose().pushPose();
            float scale = 0.8f;
            g.pose().scale(scale, scale, 1.0f);
            int tw = mc.font.width(text);
            int x = (int) ((w / 2.0f) / scale - tw / 2.0f);
            int y = (int) ((h - 56) / scale);
            g.drawString(mc.font, text, x, y, 0xFFFF8080, true);
            g.pose().popPose();
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static void drawCutscene(GuiGraphics g, int w, int h, Scene s, double t) {
        // 0,5–3,1 с: кадры сквозь косые прорези, между ними пара полностью чёрных
        if (t >= 10 && t < 64) {
            if ((t >= 27 && t < 31) || (t >= 52 && t < 57)) {
                fill(g, w, h, 1f, 0x000000);
            } else {
                double[] band = slitFor(t);
                slit(g, w, h, band[0], band[1], band[2]);
            }
        }

        // 5,6–6,2 с: полосы кино на белом
        if (t >= 112 && t < 123) {
            int bar = (int) (h * 0.12f);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
        }

        // 6,2–6,5 с: белый экран, проступают фиолетовые кляксы
        if (t >= 123 && t < 131) {
            fill(g, w, h, 1f, 0xFFFFFF);
            // fill() выключает смешивание — кляксам нужны мягкие края и проявление.
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            double k = win(t, 124, 131);
            Random rnd = new Random(s.seed + 5L);
            for (int i = 0; i < 7; i++) {
                double delay = rnd.nextDouble() * 0.5;
                double kk = Mth.clamp((k - delay) / (1.0 - delay), 0.0, 1.0);
                int size = (int) (h * (0.15 + rnd.nextDouble() * 0.5) * kk);
                int x = (int) (rnd.nextDouble() * w), y = (int) (rnd.nextDouble() * h);
                int cell = rnd.nextInt(4);
                if (size < 2) continue;
                RenderSystem.setShaderColor(0.62f, 0.32f, 0.95f, (float) Math.min(1.0, kk * 1.4));
                g.blit(TEX_BLOTS, x - size / 2, y - size / 2, size, size, (cell % 2) * 512f, (cell / 2) * 512f, 512, 512, 1024, 1024);
            }
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        // 6,6–8,0 с: полёт сквозь космос, белые силуэты всех, кто в территории
        if (t >= 131 && t < 163) {
            double zoom = 1.0 + 0.10 * win(t, 131, 160);
            int dw = (int) (w * zoom), dh = (int) (h * zoom);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.blit(TEX_GALAXY, (w - dw) / 2, (h - dh) / 2, dw, dh, 0f, 0f, 1920, 1080, 1920, 1080);
            streaks(g, w, h, t, s.seed);
            drawSilhouettes(g, w, h, s);
            // 8,0 с: кадр сжимается в полоску
            if (t >= 160) {
                double k = win(t, 160, 163);
                int band = (int) (h * (1.0 - k) * 0.5);
                g.fill(0, 0, w, h / 2 - band, 0xFF000000);
                g.fill(0, h / 2 + band, w, h, 0xFF000000);
            }
        }

        // 8,15–8,8 с: темнота
        if (t >= 163 && t < 176) fill(g, w, h, 1f, 0x000000);

        // 8,8–10,1 с: широкая полоса, глаза в синем свете; потом полосы уходят
        if (t >= 176 && t < 203) {
            double open = win(t, 197, 203);
            int bar = (int) (h * 0.32 * (1.0 - smooth(open)));
            fill(g, w, h, (float) (0.20 * (1.0 - open)), 0x1A3A9C);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
        }
    }

    /** Прорезь для кадра: центр по высоте (доля), толщина (доля), наклон в градусах. */
    private static double[] slitFor(double t) {
        if (t < 15) return new double[]{0.30, 0.22, -8};
        if (t < 21) return new double[]{0.40, 0.32, -10};
        if (t < 27) return new double[]{0.50, 0.26, -6};
        if (t < 36) return new double[]{0.45, 0.70, -16};
        if (t < 41) return new double[]{0.52, 0.30, -10};
        if (t < 46) return new double[]{0.55, 0.38, -12};
        if (t < 52) return new double[]{0.62, 0.60, -20};
        return new double[]{0.33, 0.13, -4};
    }

    private static void slit(GuiGraphics g, int w, int h, double centerFrac, double thickFrac, double angleDeg) {
        double tan = Math.tan(Math.toRadians(angleDeg));
        double half = thickFrac * h * 0.5;
        double yl = centerFrac * h + tan * (-w * 0.5);
        double yr = centerFrac * h + tan * (w * 0.5);
        g.flush();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // над прорезью
        guiV(b, m, 0, 0, 0f, 0f, 0f, 1f);
        guiV(b, m, 0, (float) (yl - half), 0f, 0f, 0f, 1f);
        guiV(b, m, w, (float) (yr - half), 0f, 0f, 0f, 1f);
        guiV(b, m, w, 0, 0f, 0f, 0f, 1f);
        // под прорезью
        guiV(b, m, 0, (float) (yl + half), 0f, 0f, 0f, 1f);
        guiV(b, m, 0, h, 0f, 0f, 0f, 1f);
        guiV(b, m, w, h, 0f, 0f, 0f, 1f);
        guiV(b, m, w, (float) (yr + half), 0f, 0f, 0f, 1f);
        BufferUploader.drawWithShader(b.end());
        RenderSystem.enableCull();
    }

    private static void guiV(BufferBuilder b, Matrix4f m, float x, float y, float r, float gr, float bl, float a) {
        b.vertex(m, x, y, 0.0f).color(r, gr, bl, a).endVertex();
    }

    /** Лучи «гиперпрыжка»: летят от центра к краям экрана. */
    private static void streaks(GuiGraphics g, int w, int h, double t, long seed) {
        g.flush();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        Random rnd = new Random(seed + 3L);
        float cx = w * 0.5f, cy = h * 0.5f;
        float maxR = (float) Math.hypot(w, h) * 0.6f;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 70; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2.0;
            double speed = 0.6 + rnd.nextDouble() * 1.2;
            double phase = rnd.nextDouble();
            double k = (t * 0.035 * speed + phase) % 1.0;
            float r0 = (float) (k * k * maxR);
            float len = (float) (20 + k * 160 * speed);
            float width = (float) (0.6 + rnd.nextDouble() * 1.8 + k * 1.5);
            int kind = rnd.nextInt(3);
            float cr = kind == 0 ? 1f : (kind == 1 ? 1f : 0.55f), cg = kind == 0 ? 0.55f : (kind == 1 ? 0.95f : 0.6f), cb = 1f;
            float a = (float) (0.85 * Math.min(1.0, k * 4.0) * (1.0 - k * 0.3));
            float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
            float nx = -dy * width, ny = dx * width;
            float x0 = cx + dx * r0, y0 = cy + dy * r0, x1 = cx + dx * (r0 + len), y1 = cy + dy * (r0 + len);
            guiV(b, m, x0 + nx, y0 + ny, cr, cg, cb, 0f);
            guiV(b, m, x0 - nx, y0 - ny, cr, cg, cb, 0f);
            guiV(b, m, x1 - nx, y1 - ny, cr, cg, cb, a);
            guiV(b, m, x1 + nx, y1 + ny, cr, cg, cb, a);
        }
        BufferUploader.drawWithShader(b.end());
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
    }

    /** Белый силуэт только владельца — по центру; остальных в этом кадре нет. */
    private static void drawSilhouettes(GuiGraphics g, int w, int h, Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.player.Player owner = mc.level.getPlayerByUUID(s.ownerId);
        if (owner == null) return;
        int x = (int) (w / 2.0f);
        int baseY = (int) (h * 0.66f);
        int scale = (int) (h * 0.13f);
        silhouette(g, owner, x, baseY, scale);
    }

    private static void silhouette(GuiGraphics g, LivingEntity e, int x, int y, int scale) {
        float bodyRot = e.yBodyRot, bodyRotO = e.yBodyRotO, yRot = e.getYRot(), yRotO = e.yRotO;
        float xRot = e.getXRot(), xRotO = e.xRotO, head = e.yHeadRot, headO = e.yHeadRotO;
        e.yBodyRot = 180.0f;
        e.yBodyRotO = 180.0f;
        e.setYRot(180.0f);
        e.yRotO = 180.0f;
        e.setXRot(0.0f);
        e.xRotO = 0.0f;
        e.yHeadRot = 180.0f;
        e.yHeadRotO = 180.0f;
        g.flush();
        RenderSystem.setShaderColor(WHITE_TINT, WHITE_TINT, WHITE_TINT, 1.0f);
        InventoryScreen.renderEntityInInventory(g, x, y, scale, new Quaternionf().rotateZ((float) Math.PI), null, e);
        g.flush();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        e.yBodyRot = bodyRot;
        e.yBodyRotO = bodyRotO;
        e.setYRot(yRot);
        e.yRotO = yRotO;
        e.setXRot(xRot);
        e.xRotO = xRotO;
        e.yHeadRot = head;
        e.yHeadRotO = headO;
    }

    private static void fill(GuiGraphics g, int w, int h, float alpha, int rgb) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        if (a <= 0) return;
        g.fill(0, 0, w, h, (a << 24) | (rgb & 0x00FFFFFF));
    }

    // ------------------------------------------------------------------ мобы-статуи

    /** id мобов, которые застыли статуей: id → tickCount в момент заморозки. */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap FROZEN = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    private static boolean renderingFrozen;

    static void onFreeze(int id, boolean frozen) {
        Minecraft mc = Minecraft.getInstance();
        if (!frozen) {
            FROZEN.remove(id);
            return;
        }
        Entity e = mc.level != null ? mc.level.getEntity(id) : null;
        FROZEN.put(id, e != null ? e.tickCount : 0);
        if (e instanceof LivingEntity le) calmLimbs(le);
    }

    private static void calmLimbs(LivingEntity e) {
        e.walkAnimation.setSpeed(0.0f);
        e.walkAnimation.update(0.0f, 1.0f);
    }

    /**
     * Свой тик у статуи не идёт: ни анимаций, ни поворотов, ни плескания. Только позиция
     * плавно идёт за сервером — отбрасывание от ударов видно.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        if (FROZEN.isEmpty()) return;
        LivingEntity e = event.getEntity();
        if (!e.level().isClientSide || !FROZEN.containsKey(e.getId())) return;
        if (e.isDeadOrDying() || e instanceof net.minecraft.world.entity.player.Player) return;
        event.setCanceled(true);
        e.tickCount = FROZEN.get(e.getId());
        if (e.hurtTime > 0) e.hurtTime--;
        if (e.invulnerableTime > 0) e.invulnerableTime--;
        calmLimbs(e);
        Vec3 target = e.getPositionCodec().decode(0L, 0L, 0L);
        Vec3 pos = e.position();
        Vec3 d = target.subtract(pos);
        if (d.lengthSqr() > 64.0) {
            e.setPos(target.x, target.y, target.z);
        } else if (d.lengthSqr() > 1.0E-6) {
            Vec3 next = pos.add(d.scale(0.5));
            e.setPos(next.x, next.y, next.z);
        }
    }

    /** Статую рисуем без «дрожи» между тиками: время анимаций застыло. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLiving(net.minecraftforge.client.event.RenderLivingEvent.Pre event) {
        if (renderingFrozen || FROZEN.isEmpty()) return;
        LivingEntity e = event.getEntity();
        if (!FROZEN.containsKey(e.getId()) || e.isDeadOrDying()) return;
        if (event.getPartialTick() == 0.0f) return;
        event.setCanceled(true);
        renderingFrozen = true;
        try {
            calmLimbs(e);
            ((net.minecraft.client.renderer.entity.LivingEntityRenderer) event.getRenderer()).render(e, e.getYRot(), 0.0f,
                    event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
        } finally {
            renderingFrozen = false;
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && !FROZEN.isEmpty()) FROZEN.remove(event.getEntity().getId());
    }

    /**
     * Камера чужой кат-сцены внутри территории не выходит за её стену — иначе вместо кадра
     * видна чёрная сфера снаружи.
     */
    static Vec3 clampCameraInside(Vec3 anchor, Vec3 camera) {
        for (Scene s : SCENES.values()) {
            if (!s.wallsVisible() || anchor.distanceTo(s.center) >= RADIUS) continue;
            Vec3 off = camera.subtract(s.center);
            double max = RADIUS - 1.5;
            if (off.length() > max) return s.center.add(off.normalize().scale(max));
            return camera;
        }
        return camera;
    }

    /** Нужна ли клиенту отрисовка через Entity (для совместимости при вызове из других мест). */
    static boolean isInsideClient(Entity e) {
        for (Scene s : SCENES.values()) {
            if (s.wallsVisible() && e.position().distanceTo(s.center) < RADIUS) return true;
        }
        return false;
    }
}
