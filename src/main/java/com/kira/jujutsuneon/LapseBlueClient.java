package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.kira.jujutsuneon.LapseBlue.*;

/**
 * Клиент обычного Синего: тело по сценарию, эффекты, камера спереди в замедлении (только у кастующего),
 * касание стопы по самой модели цели (а не по хитбоксу), плавное следование захваченных блоков за прицелом.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LapseBlueClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_ARC = tex("lapse_arc");
    private static final ResourceLocation TEX_BURST = tex("lapse_burst");
    private static final ResourceLocation TEX_CRESCENT = tex("lapse_crescent");
    private static final ResourceLocation TEX_WIND = tex("lapse_wind");
    private static final ResourceLocation TEX_RING = tex("lapse_ring");
    private static final ResourceLocation TEX_CRACKS = tex("lapse_cracks");
    private static final ResourceLocation TEX_SMOKE = tex("lapse_smoke");
    private static final ResourceLocation TEX_GLINT = tex("lapse_glint");
    private static final ResourceLocation TEX_ENERGY = tex("lapse_energy");
    private static final ResourceLocation TEX_WISP = tex("lapse_wisp");
    private static final ResourceLocation TEX_ORB = tex("max_purple_blue_orb");
    private static final ResourceLocation TEX_BLOOM = tex("max_purple_bloom");

    /** Сколько ещё рисуются эффекты после конца приёма (пыль, трещины). */
    private static final int FX_TAIL = 60;
    private static final int FIRST_PERSON_ARM_TICKS = 24;
    /** Длина анимации захвата; дальше — петля «дыхания» с блоками в руке. */
    private static final int GRAB_ANIM_TICKS = 26;

    private static final Map<UUID, ComboScene> COMBOS = new HashMap<>();
    private static final Map<UUID, GrabScene> GRABS = new HashMap<>();

    private static ComboScene camScene;
    private static Marker cameraEntity;
    private static CameraType savedCameraType;
    private static Boolean savedHideGui;
    private static float camEyeOld;
    private static float camEye;
    private static float currentFov = 64f;
    private static float currentRoll = 0f;
    private static boolean localGravityChanged;

    private LapseBlueClient() {
    }

    private static final class ComboScene {
        final UUID casterId;
        final int casterEntityId;
        final int targetEntityId;
        final Script s;
        final long seed;
        int ticks;
        boolean controlling = true;
        boolean aborted;
        double lift = Double.NaN;
        final boolean localCaster;
        final boolean localTarget;

        ComboScene(UUID casterId, int casterEntityId, int targetEntityId, Script s, boolean localCaster, boolean localTarget) {
            this.casterId = casterId;
            this.casterEntityId = casterEntityId;
            this.targetEntityId = targetEntityId;
            this.s = s;
            this.seed = casterId.getLeastSignificantBits() ^ Double.doubleToLongBits(s.kick.x * 31.0 + s.kick.z);
            this.localCaster = localCaster;
            this.localTarget = localTarget;
        }

        double lift() {
            return Double.isNaN(lift) ? s.boxLift() : lift;
        }
    }

    private static final class GrabScene {
        final UUID casterId;
        final int casterEntityId;
        final int[] ids;
        final List<Vec3> origins;
        final long seed;
        int ticks;

        GrabScene(UUID casterId, int casterEntityId, int[] ids, List<Vec3> origins) {
            this.casterId = casterId;
            this.casterEntityId = casterEntityId;
            this.ids = ids;
            this.origins = origins;
            this.seed = casterId.getMostSignificantBits();
        }

        Vec3 center() {
            Vec3 sum = Vec3.ZERO;
            for (Vec3 v : origins) sum = sum.add(v);
            return origins.isEmpty() ? sum : sum.scale(1.0 / origins.size()).add(0.0, 0.5, 0.0);
        }
    }

    // ================================================================== API

    /** Локальный игрок в комбо Синего (кастует или его бьют) — ни движения, ни техник. */
    public static boolean locksLocalPlayer() {
        for (ComboScene c : COMBOS.values()) {
            if (c.controlling && (c.localCaster || c.localTarget)) return true;
        }
        return false;
    }

    static void onCombo(UUID caster, int casterId, int targetId, Script s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ComboScene old = COMBOS.remove(caster);
        if (old != null) endControl(mc, old);
        boolean localCaster = mc.player.getUUID().equals(caster);
        Entity target = mc.level.getEntity(targetId);
        boolean localTarget = target == mc.player;
        ComboScene scene = new ComboScene(caster, casterId, targetId, s, localCaster, localTarget);
        COMBOS.put(caster, scene);

        if (mc.level.getEntity(casterId) instanceof AbstractClientPlayer p) {
            if (localCaster) MaximumPurpleAnimation.playWithFirstPersonArm(p, "lapse_blue", FIRST_PERSON_ARM_TICKS);
            else MaximumPurpleAnimation.play(p, "lapse_blue");
        }
        if ((localCaster || localTarget) && !mc.player.isNoGravity()) {
            mc.player.setNoGravity(true);
            localGravityChanged = true;
        }
        if (localCaster || localTarget) {
            mc.player.setSprinting(false);
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
    }

    static void onComboEnd(UUID caster, boolean aborted) {
        ComboScene scene = COMBOS.get(caster);
        if (scene == null) return;
        Minecraft mc = Minecraft.getInstance();
        scene.aborted |= aborted;
        endControl(mc, scene);
        if (aborted) {
            if (mc.level != null && mc.level.getEntity(scene.casterEntityId) instanceof AbstractClientPlayer p) {
                MaximumPurpleAnimation.stop(p);
            }
            COMBOS.remove(caster);
        }
    }

    static void onGrab(UUID caster, int casterId, int[] ids, List<Vec3> origins, int variant) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        GRABS.put(caster, new GrabScene(caster, casterId, ids, origins));
        String name = variant == 0 ? "lapse_blue_grab_low" : (variant == 2 ? "lapse_blue_grab_high" : "lapse_blue_grab_mid");
        if (mc.level.getEntity(casterId) instanceof AbstractClientPlayer p) {
            if (p == mc.player) MaximumPurpleAnimation.playWithFirstPersonArm(p, name, FIRST_PERSON_ARM_TICKS);
            else MaximumPurpleAnimation.play(p, name);
        }
    }

    static void onGrabEnd(UUID caster, boolean thrown) {
        GrabScene g = GRABS.remove(caster);
        Minecraft mc = Minecraft.getInstance();
        if (g == null || mc.level == null) return;
        if (mc.level.getEntity(g.casterEntityId) instanceof AbstractClientPlayer p) {
            if (thrown) MaximumPurpleAnimation.play(p, "lapse_blue_throw");
            else if (g.ticks >= GRAB_ANIM_TICKS) MaximumPurpleAnimation.stop(p);
        }
    }

    private static void endControl(Minecraft mc, ComboScene scene) {
        if (!scene.controlling) return;
        scene.controlling = false;
        if (scene == camScene) stopCamera(mc);
        if ((scene.localCaster || scene.localTarget) && mc.player != null) {
            if (localGravityChanged) {
                mc.player.setNoGravity(false);
                localGravityChanged = false;
            }
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.fallDistance = 0.0f;
        }
    }

    private static void resetAll() {
        Minecraft mc = Minecraft.getInstance();
        for (ComboScene c : COMBOS.values()) endControl(mc, c);
        stopCamera(mc);
        COMBOS.clear();
        GRABS.clear();
        localGravityChanged = false;
    }

    // ================================================================== тики

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!COMBOS.isEmpty() || !GRABS.isEmpty() || cameraEntity != null) resetAll();
            return;
        }
        if (mc.isPaused()) return;

        Iterator<Map.Entry<UUID, ComboScene>> it = COMBOS.entrySet().iterator();
        while (it.hasNext()) {
            ComboScene c = it.next().getValue();
            c.ticks++;
            if (c.controlling && c.ticks >= T_END) endControl(mc, c);
            if (c.ticks > T_END + FX_TAIL) {
                it.remove();
                continue;
            }
            if (!c.controlling) continue;

            // Камера спереди — только у кастующего и только на время замедления.
            if (c.localCaster) {
                boolean shot = c.ticks >= T_TP && c.ticks < T_SLOW_END;
                if (shot && cameraEntity == null) startCamera(mc, c);
                if (!shot && c == camScene) stopCamera(mc);
                if (cameraEntity != null && c == camScene) {
                    camEyeOld = camEye;
                    camEye += (0.0f - camEye) * 0.5f;
                    if (!mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
                    if (mc.getCameraEntity() != cameraEntity) mc.setCameraEntity(cameraEntity);
                    if (mc.options.hideGui) mc.options.hideGui = false;
                }
            }
            applyPositions(mc, c, c.ticks, 1.0f);
            if (c.localCaster && cameraEntity != null && c == camScene) DomainExpansionClient.syncPosition(mc);
        }

        Iterator<Map.Entry<UUID, GrabScene>> gi = GRABS.entrySet().iterator();
        while (gi.hasNext()) {
            GrabScene g = gi.next().getValue();
            g.ticks++;
            if (g.ticks > 260 || !(mc.level.getEntity(g.casterEntityId) instanceof Player owner)) {
                gi.remove();
                continue;
            }
            // Блоки притянуты — персонаж «дышит», рука держит блоки (ноги и голова свободны).
            if (g.ticks == GRAB_ANIM_TICKS && owner instanceof AbstractClientPlayer p) {
                MaximumPurpleAnimation.play(p, "lapse_blue_hold");
            }
            applyGrab(mc, g, g.ticks, 1.0f);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float pt = mc.isPaused() ? 0.0f : event.renderTickTime;
        for (ComboScene c : COMBOS.values()) {
            if (!c.controlling) continue;
            double t = Math.min(c.ticks + pt, T_END);
            updateLift(mc, c, t, pt);
            applyPositions(mc, c, t, pt);
            if (c == camScene && cameraEntity != null) placeCamera(mc, c, t, pt);
        }
        for (GrabScene g : GRABS.values()) applyGrab(mc, g, g.ticks + pt, pt);
    }

    /** Ставит цель и кастующего по сценарию (и запоминает как «прошлое» положение — без интерполяции). */
    private static void applyPositions(Minecraft mc, ComboScene c, double t, float pt) {
        Script s = c.s;
        if (t < T_LEAP && mc.level.getEntity(c.targetEntityId) instanceof LivingEntity target && target.isAlive()) {
            Vec3 p = s.targetAt(t);
            pin(target, p);
            if (target == mc.player) mc.player.fallDistance = 0.0f;
        }
        Entity e = mc.level.getEntity(c.casterEntityId);
        if (e instanceof Player caster) {
            Vec3 p = s.casterAt(t, c.lift());
            pin(caster, p);
            caster.yBodyRot = s.yaw;
            caster.yBodyRotO = s.yaw;
            boolean shot = c == camScene && cameraEntity != null;
            if (caster != mc.player || shot) {
                caster.setYRot(s.yaw);
                caster.yRotO = s.yaw;
                caster.yHeadRot = s.yaw;
                caster.yHeadRotO = s.yaw;
                if (shot) {
                    caster.setXRot(0.0f);
                    caster.xRotO = 0.0f;
                }
            }
            if (caster == mc.player) {
                mc.player.fallDistance = 0.0f;
                mc.player.setSprinting(false);
            }
        }
    }

    private static void pin(Entity e, Vec3 p) {
        e.setPos(p.x, p.y, p.z);
        e.xo = e.xOld = p.x;
        e.yo = e.yOld = p.y;
        e.zo = e.zOld = p.z;
        e.setDeltaMovement(Vec3.ZERO);
    }

    private static void applyGrab(Minecraft mc, GrabScene g, double t, float pt) {
        if (!(mc.level.getEntity(g.casterEntityId) instanceof Player owner)) return;
        Vec3 eye = owner.getEyePosition(pt);
        Vec3 look = owner.getViewVector(pt);
        for (int i = 0; i < g.ids.length && i < g.origins.size(); i++) {
            Entity block = mc.level.getEntity(g.ids[i]);
            if (block == null) continue;
            pin(block, grabPos(g.origins.get(i), holdPos(eye, look, i), t));
        }
    }

    // ================================================================== касание по модели

    /** Высота ног игрока над ногами цели, при которой стопа ровно касается модели цели. */
    private static void updateLift(Minecraft mc, ComboScene c, double t, float pt) {
        if (t < T_TP - 1 || t >= T_LEAP) return;
        if (!(mc.level.getEntity(c.targetEntityId) instanceof LivingEntity target)) return;
        Script s = c.s;
        Vec3 tFeet = s.targetAt(t);
        pin(target, tFeet);
        Vec3 air = s.airXZ();
        double need = Double.NaN;
        try {
            Capture cap = Capture.of(mc, target, pt);
            if (cap != null) {
                // Каждая точка стопы (и весь путь разгибающейся ноги) — над моделью цели.
                for (double[] f : feetSamples(t)) {
                    double fx = air.x + s.forward.x * f[0] + s.right.x * f[1];
                    double fz = air.z + s.forward.z * f[0] + s.right.z * f[1];
                    double top = cap.topAround(fx - tFeet.x, fz - tFeet.z, s.forward, s.right);
                    if (Double.isNaN(top)) continue;
                    double req = top + CONTACT_MARGIN - f[2];
                    need = Double.isNaN(need) ? req : Math.max(need, req);
                }
            }
        } catch (Throwable ignored) {
            need = Double.NaN;
        }
        if (Double.isNaN(need)) need = s.boxLift();
        // Вверх — сразу (нога никогда не уходит внутрь), вниз — плавно.
        if (Double.isNaN(c.lift) || need > c.lift) c.lift = need;
        else c.lift += (need - c.lift) * 0.12;
    }

    /** Вершины модели цели в том виде, как её рисует игра (с бронёй, шерстью и т.п.). */
    private static final class Capture implements VertexConsumer {
        private float[] data = new float[4096];
        private int size;
        private final float[] cur = new float[3];

        static Capture of(Minecraft mc, LivingEntity e, float pt) {
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            EntityRenderer<? super LivingEntity> renderer = dispatcher.getRenderer(e);
            if (renderer == null) return null;
            Capture cap = new Capture();
            MultiBufferSource source = type -> cap.accepts(type) ? cap : NullConsumer.INSTANCE;
            float yaw = Mth.lerp(pt, e.yRotO, e.getYRot());
            renderer.render(e, yaw, pt, new PoseStack(), source, 15728880);
            return cap.size >= 12 ? cap : null;
        }

        private boolean accepts(RenderType type) {
            String n = type.toString();
            if (n.contains("shadow") || n.contains("text") || n.contains("lines") || n.contains("leash")) return false;
            return n.contains("entity") || n.contains("armor") || n.contains("eyes") || n.contains("cutout") || n.contains("solid");
        }

        /** Верх модели под стопой (точка и 4 соседние в квадрате стопы); NaN — под стопой модели нет. */
        double topAround(double x, double z, Vec3 fwd, Vec3 side) {
            double best = Double.NaN;
            double h = 0.1;
            double[][] pts = {{0, 0}, {h, 0}, {-h, 0}, {0, h}, {0, -h}};
            for (double[] o : pts) {
                double px = x + fwd.x * o[0] + side.x * o[1];
                double pz = z + fwd.z * o[0] + side.z * o[1];
                double y = topAt(px, pz);
                if (!Double.isNaN(y) && (Double.isNaN(best) || y > best)) best = y;
            }
            return best;
        }

        private double topAt(double px, double pz) {
            double best = Double.NaN;
            int quads = size / 12;
            for (int q = 0; q < quads; q++) {
                int o = q * 12;
                double area = 0.0;
                for (int i = 0; i < 4; i++) {
                    int a = o + i * 3, b = o + ((i + 1) % 4) * 3;
                    area += data[a] * (double) data[b + 2] - data[b] * (double) data[a + 2];
                }
                if (Math.abs(area) < 1.0E-5) continue;
                boolean inside = true;
                for (int i = 0; i < 4 && inside; i++) {
                    int a = o + i * 3, b = o + ((i + 1) % 4) * 3;
                    double cross = (data[b] - data[a]) * (pz - data[a + 2]) - (data[b + 2] - data[a + 2]) * (px - data[a]);
                    if (cross * area < -1.0E-9) inside = false;
                }
                if (!inside) continue;
                double ax = data[o + 3] - data[o], ay = data[o + 4] - data[o + 1], az = data[o + 5] - data[o + 2];
                double bx = data[o + 6] - data[o], by = data[o + 7] - data[o + 1], bz = data[o + 8] - data[o + 2];
                double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                double y;
                if (Math.abs(ny) < 1.0E-7) {
                    y = Math.max(Math.max(data[o + 1], data[o + 4]), Math.max(data[o + 7], data[o + 10]));
                } else {
                    y = data[o + 1] - (nx * (px - data[o]) + nz * (pz - data[o + 2])) / ny;
                }
                if (Double.isNaN(best) || y > best) best = y;
            }
            return best;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            cur[0] = (float) x;
            cur[1] = (float) y;
            cur[2] = (float) z;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            if (size + 3 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = cur[0];
            data[size++] = cur[1];
            data[size++] = cur[2];
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }

    private static final class NullConsumer implements VertexConsumer {
        static final NullConsumer INSTANCE = new NullConsumer();

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }

    // ================================================================== камера в замедлении

    private static void startCamera(Minecraft mc, ComboScene c) {
        stopCamera(mc);
        camScene = c;
        LocalPlayer player = mc.player;
        cameraEntity = new Marker(EntityType.MARKER, mc.level);
        Vec3 eye = player.getEyePosition();
        cameraEntity.setPos(eye.x, eye.y, eye.z);
        savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = false;
        camEye = camEyeOld = player.getEyeHeight();
        mc.setCameraEntity(cameraEntity);
    }

    private static void stopCamera(Minecraft mc) {
        if (cameraEntity != null) {
            if (mc.player != null) mc.setCameraEntity(mc.player);
            cameraEntity = null;
        }
        if (savedCameraType != null) {
            mc.options.setCameraType(savedCameraType);
            savedCameraType = null;
        }
        if (savedHideGui != null) {
            mc.options.hideGui = savedHideGui;
            savedHideGui = null;
        }
        camScene = null;
    }

    /** Камера спереди: игрок над целью, удар ногой вниз, полёт к земле. */
    private static void placeCamera(Minecraft mc, ComboScene c, double t, float pt) {
        Script s = c.s;
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        Vec3 p = s.casterAt(t, c.lift());
        Vec3 chest = p.add(0.0, 0.95, 0.0);
        Vec3 foot = s.rightFoot(p).add(0.0, FOOT_RIGHT_LOW + 0.25, 0.0);
        double toFoot = smooth((t - (T_TP + 2)) / 10.0);
        Vec3 aim = chest.add(foot.subtract(chest).scale(toFoot * 0.75));

        double dolly = Mth.lerp(smooth((t - T_TP) / (T_CONTACT - T_TP)), 4.6, 3.3);
        dolly = Mth.lerp(smooth((t - T_IMPACT) / 6.0), dolly, 4.1);
        double lift = Mth.lerp(smooth((t - T_TP) / (T_CONTACT - T_TP)), 0.35, -0.2);
        Vec3 cam = aim.add(s.forward.scale(dolly)).add(s.right.scale(0.8)).add(up.scale(lift));
        boolean ground = s.groundY > s.apexY - 13.0;
        if (ground && cam.y < s.groundY + 0.45) cam = new Vec3(cam.x, s.groundY + 0.45, cam.z);

        // Не заходить в стены.
        BlockHitResult hit = mc.level.clip(new ClipContext(aim, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, cameraEntity));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3 dir = cam.subtract(aim).normalize();
            cam = hit.getLocation().subtract(dir.scale(0.2));
        }

        Vec3 d = aim.subtract(cam);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        double eye = Mth.lerp(pt, camEyeOld, camEye);
        double y = cam.y - eye;
        cameraEntity.setPos(cam.x, y, cam.z);
        cameraEntity.xo = cameraEntity.xOld = cam.x;
        cameraEntity.yo = cameraEntity.yOld = y;
        cameraEntity.zo = cameraEntity.zOld = cam.z;
        cameraEntity.setYRot(yaw);
        cameraEntity.yRotO = yaw;
        cameraEntity.setXRot(pitch);
        cameraEntity.xRotO = pitch;

        currentFov = (float) (64.0 - 7.0 * smooth((t - T_TP) / (T_CONTACT - T_TP)) + 9.0 * smooth((t - T_IMPACT) / 5.0));
        currentRoll = -4.0f;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        ComboScene c = camScene;
        if (c == null || cameraEntity == null) return;
        double t = c.ticks + event.getPartialTick();
        float shake = 0.0f;
        if (t >= T_CONTACT && t < T_CONTACT + 5) shake = 0.9f * (float) (1.0 - (t - T_CONTACT) / 5.0);
        if (t >= T_IMPACT && t < T_IMPACT + 7) shake = 2.8f * (float) (1.0 - (t - T_IMPACT) / 7.0);
        event.setYaw(event.getYaw() + (float) Math.sin(t * 5.7) * shake + (float) Math.sin(t * 0.31) * 0.15f);
        event.setPitch(event.getPitch() + (float) Math.cos(t * 6.9) * shake * 0.7f + (float) Math.sin(t * 0.27) * 0.12f);
        event.setRoll(currentRoll + (float) Math.sin(t * 8.3) * shake * 0.5f);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (camScene == null || cameraEntity == null || !event.usedConfiguredFov()) return;
        event.setFOV(currentFov);
    }

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
        if (cameraEntity != null && event.getEntity() == Minecraft.getInstance().player) event.setResult(Event.Result.DENY);
    }

    // ================================================================== ввод

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

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

    // ================================================================== отрисовка мира

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || (COMBOS.isEmpty() && GRABS.isEmpty())) return;

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (cameraEntity != null && mc.player != null) renderLocalPlayer(mc, event);
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

        for (ComboScene c : COMBOS.values()) {
            double t = c.ticks + (mc.isPaused() ? 0.0 : pt);
            renderCombo(mc, pose, cam, camera, c, t);
        }
        for (GrabScene g : GRABS.values()) {
            double t = g.ticks + (mc.isPaused() ? 0.0 : pt);
            if (t < G_FX_END + 2) renderGrab(mc, pose, cam, camera, g, t, pt);
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

    private static double win(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0.0, 1.0);
    }

    /** Плавно появиться за fin тиков после a и исчезнуть за fout тиков до b. */
    private static float env(double t, double a, double b, double fin, double fout) {
        if (t < a || t > b) return 0.0f;
        return (float) (smooth((t - a) / fin) * (1.0 - smooth((t - (b - fout)) / fout)));
    }

    private static void renderCombo(Minecraft mc, PoseStack pose, Camera cam, Vec3 camera, ComboScene c, double t) {
        Script s = c.s;
        double h = s.targetHeight, w = s.targetWidth;
        double size = Math.max(h, w);
        Vec3 tc = s.targetAt(Math.min(t, T_END)).add(0.0, h * 0.5, 0.0);
        Vec3 up = new Vec3(0.0, 1.0, 0.0);

        // 1. Дуга-молния вокруг цели и синий шар в ладони.
        float arc = env(t, T_ARC, 15, 3, 4);
        if (arc > 0.01f) {
            float flick = (float) (0.75 + 0.25 * Math.sin(t * 13.0));
            float sz = (float) (1.9 + size * 0.9);
            billboard(pose, cam, camera, tc, sz, (float) (t * 0.02), TEX_ARC, 1f, 1f, 1f, arc * flick, true);
            billboard(pose, cam, camera, tc, sz * 0.86f, (float) (Math.PI + t * -0.03), TEX_ARC, 0.7f, 0.9f, 1f, arc * 0.6f, true);
        }
        // Синий шар в ладони — объёмная модель: ядро, две закрученные оболочки, ободок, молнии, клубы.
        Vec3 palm = s.start.add(up.scale(1.22)).add(s.forward.scale(0.62)).add(s.right.scale(0.30));
        float orb = env(t, 2, 12, 2, 3);
        if (orb > 0.01f) {
            float r = (float) (0.10 + 0.24 * smooth((t - 2) / 5.0));
            blueOrb(pose, cam, camera, palm, r, t, orb, c.seed);
        }
        // Синий срывается с ладони к цели.
        Vec3 t0c = s.t0.add(0.0, h * 0.5, 0.0);
        float beam = env(t, 5, 9, 0.5, 2);
        if (beam > 0.01f) energyBeam(pose, camera, palm, t0c, t, beam, c.seed);

        // 2. Синий взрыв на месте цели — объёмный купол из закрученной энергии, вспышка, лучи.
        float burst = env(t, T_BURST, T_BURST + 9, 0.5, 6);
        if (burst > 0.01f) {
            double k = win(t, T_BURST, T_BURST + 8);
            blueBurst(pose, cam, camera, t0c, k, burst, t, (float) (0.7 + size * 0.3), c.seed);
            Random rnd = new Random(c.seed * 977L + 2);
            for (int i = 0; i < 8; i++) {
                Vec3 dir = randDir(rnd);
                double len = 0.6 + rnd.nextDouble();
                Vec3 a = t0c.add(dir.scale(0.4 + 2.8 * k));
                streak(pose, camera, a, a.add(dir.scale(len * (1.0 - k * 0.5))), 0.07f, TEX_GLINT, 0.6f, 0.9f, 1f, burst, true);
            }
        }

        // 3. Чёрно-белый полумесяц ударной волны (там же).
        float cres = env(t, T_CRESCENT, T_CRESCENT + 6, 0.5, 4);
        if (cres > 0.01f) {
            double k = win(t, T_CRESCENT, T_CRESCENT + 6);
            float sz = (float) ((2.8 + 1.6 * k) * (0.7 + size * 0.3));
            billboard(pose, cam, camera, t0c, sz, (float) (0.5 + k * 0.6), TEX_CRESCENT, 1f, 1f, 1f, cres, false);
            billboard(pose, cam, camera, t0c, sz * 0.82f, (float) (3.6 - k * 0.8), TEX_CRESCENT, 1f, 1f, 1f, cres * 0.85f, false);
        }

        // 4. Призмы-искры (розовый → фиолетовый → бирюзовый → белый) и вихрь.
        float prism = env(t, T_PRISM, 26, 1, 5);
        if (prism > 0.01f) {
            double k = win(t, T_PRISM, 26);
            float[] col = prismColor(k);
            for (int i = 0; i < 3; i++) {
                float sz = (float) ((1.7 - i * 0.35) * (0.6 + size * 0.45));
                diamond(pose, cam, camera, tc, sz, (float) (t * 0.04 * (i % 2 == 0 ? 1 : -1) + i * 0.4),
                        col[0], col[1], col[2], prism * (0.42f - i * 0.08f));
            }
            flat(pose, camera, tc.add(0.0, -h * 0.1, 0.0), (float) (2.4 + size), (float) (t * 0.12), TEX_WIND, 1f, 1f, 1f, prism * 0.7f, false);
        }

        // 5. Удар ногой вверх: белый вихрь, искры, след взлёта.
        float launch = env(t, T_KICK_UP, T_KICK_UP + 10, 0.5, 7);
        if (launch > 0.01f) {
            double k = win(t, T_KICK_UP, T_KICK_UP + 10);
            flat(pose, camera, s.kick.add(0.0, 0.08, 0.0), (float) (2.2 + 3.0 * k), (float) (t * 0.15), TEX_WIND, 1f, 1f, 1f, launch * 0.85f, false);
            billboard(pose, cam, camera, tc, (float) (1.0 + size), (float) (-t * 0.1), TEX_WIND, 1f, 1f, 1f, launch * 0.6f, false);
            if (t < T_KICK_UP + 3) {
                float f = (float) (1.0 - (t - T_KICK_UP) / 3.0);
                billboard(pose, cam, camera, s.kick.add(0.0, h * 0.3, 0.0), 1.6f, 0f, TEX_BLOOM, 1f, 1f, 1f, f * 0.9f, true);
            }
            Random rnd = new Random(c.seed * 977L + 5);
            for (int i = 0; i < 10; i++) {
                double a = rnd.nextDouble() * Math.PI * 2.0;
                Vec3 dir = new Vec3(Math.cos(a) * 0.6, 0.6 + rnd.nextDouble(), Math.sin(a) * 0.6).normalize();
                Vec3 base = s.kick.add(0.0, h * 0.4, 0.0).add(dir.scale(0.3 + 3.0 * k * (0.6 + rnd.nextDouble() * 0.6)));
                streak(pose, camera, base, base.add(dir.scale(0.45)), 0.06f, TEX_GLINT, 1f, 1f, 1f, launch, true);
            }
            if (t < T_APEX) {
                Vec3 bottom = s.targetAt(t);
                for (int i = 0; i < 5; i++) {
                    double a = i * 1.256 + c.seed % 7;
                    Vec3 o = bottom.add(Math.cos(a) * w * 0.45, 0.0, Math.sin(a) * w * 0.45);
                    streak(pose, camera, o, o.add(0.0, -1.2 - 0.4 * (i % 3), 0.0), 0.04f, TEX_GLINT, 1f, 1f, 1f, launch * 0.6f, true);
                }
            }
        }

        // 6. Телепорт: вспышка там, где был, и там, где появился (без стекла).
        float blink = env(t, T_TP - 1, T_TP + 4, 0.5, 4);
        if (blink > 0.01f) {
            billboard(pose, cam, camera, s.start.add(0.0, 1.0, 0.0), 1.8f, 0f, TEX_BLOOM, 0.7f, 0.9f, 1f, blink * 0.8f, true);
            Vec3 at = s.casterAt(T_TP, c.lift()).add(0.0, 1.0, 0.0);
            billboard(pose, cam, camera, at, 2.0f, 0f, TEX_BLOOM, 0.8f, 0.95f, 1f, blink * 0.7f, true);
        }

        // 7. Касание стопой: круг удара (и вспышка) ровно в точке касания.
        Vec3 casterNow = s.casterAt(Math.min(t, T_LEAP - 0.001), c.lift());
        Vec3 contact = s.rightFoot(casterNow).add(0.0, FOOT_RIGHT_LOW, 0.0);
        float ring = env(t, T_CONTACT, T_CONTACT + 9, 0.3, 6);
        if (ring > 0.01f) {
            double k = win(t, T_CONTACT, T_CONTACT + 9);
            float sz = (float) (0.4 + 3.4 * (1.0 - Math.pow(1.0 - k, 2.5)));
            billboard(pose, cam, camera, contact, sz, 0f, TEX_RING, 1f, 1f, 1f, ring, true);
            double k2 = win(t, T_CONTACT + 1.5, T_CONTACT + 9);
            billboard(pose, cam, camera, contact, (float) (0.3 + 2.2 * k2), 0f, TEX_RING, 0.7f, 0.9f, 1f, ring * 0.7f, true);
            flat(pose, camera, contact, (float) (0.5 + 3.0 * k), 0f, TEX_RING, 1f, 1f, 1f, ring * 0.8f, true);
            if (t < T_CONTACT + 3) billboard(pose, cam, camera, contact, 1.4f, 0f, TEX_BLOOM, 1f, 1f, 1f, (float) (1.0 - (t - T_CONTACT) / 3.0), true);
            Random rnd = new Random(c.seed * 977L + 7);
            for (int i = 0; i < 9; i++) {
                double a = i / 9.0 * Math.PI * 2.0 + rnd.nextDouble() * 0.4;
                Vec3 dir = s.forward.scale(Math.cos(a)).add(s.right.scale(Math.sin(a))).add(0.0, 0.15, 0.0).normalize();
                Vec3 b = contact.add(dir.scale(0.3 + 2.2 * k));
                streak(pose, camera, b, b.add(dir.scale(0.5)), 0.05f, TEX_GLINT, 1f, 1f, 1f, ring, true);
            }
        }

        // 8. Падение к земле: полосы скорости и дымный след.
        float ride = env(t, T_CONTACT, T_IMPACT + 1, 1, 2);
        if (ride > 0.01f) {
            Vec3 tp = s.targetAt(t);
            for (int i = 0; i < 7; i++) {
                double a = i / 7.0 * Math.PI * 2.0 + 0.3;
                double r = w * 0.5 + 0.35 + (i % 3) * 0.2;
                double phase = (t * 0.9 + i * 0.37) % 1.0;
                Vec3 o = tp.add(Math.cos(a) * r, h * (0.2 + phase * 1.6), Math.sin(a) * r);
                streak(pose, camera, o, o.add(0.0, 1.1, 0.0), 0.035f, TEX_GLINT, 1f, 1f, 1f, ride * 0.55f, true);
            }
            for (int i = 0; i < 6; i++) {
                double back = (i + 1) * 0.9;
                double tt = t - back;
                if (tt < T_CONTACT) continue;
                Vec3 p = s.targetAt(tt).add(0.0, h * 0.5, 0.0);
                float a = ride * 0.45f * (1.0f - i / 6.0f);
                billboard(pose, cam, camera, p, (float) (1.0 + i * 0.25 + w * 0.5), (float) (i * 0.7), TEX_SMOKE, 1f, 1f, 1f, a, false);
            }
        }

        // 9. Удар о землю: кольцо по земле, вихрь, пыль, трещины, искры.
        if (t >= T_IMPACT - 0.5) {
            Vec3 g = new Vec3(s.kick.x, s.groundY + 0.06, s.kick.z);
            double k = win(t, T_IMPACT, T_IMPACT + 10);
            float ringA = env(t, T_IMPACT, T_IMPACT + 10, 0.3, 7);
            if (ringA > 0.01f) {
                flat(pose, camera, g, (float) (1.0 + 9.0 * (1.0 - Math.pow(1.0 - k, 2.0))), 0f, TEX_RING, 1f, 1f, 1f, ringA, true);
                flat(pose, camera, g.add(0.0, 0.25, 0.0), (float) (3.0 + 4.5 * k), (float) (t * 0.12), TEX_WIND, 1f, 1f, 1f, ringA * 0.8f, false);
            }
            if (t < T_IMPACT + 3) billboard(pose, cam, camera, g.add(0.0, 0.5, 0.0), 3.2f, 0f, TEX_BLOOM, 1f, 1f, 1f, (float) (1.0 - (t - T_IMPACT) / 3.0), true);
            float dust = env(t, T_IMPACT, T_END + FX_TAIL - 10, 1, 25);
            if (dust > 0.01f) {
                Random rnd = new Random(c.seed * 977L + 9);
                double kd = win(t, T_IMPACT, T_IMPACT + 30);
                for (int i = 0; i < 12; i++) {
                    double a = i / 12.0 * Math.PI * 2.0 + rnd.nextDouble() * 0.5;
                    double r = 1.0 + (2.2 + rnd.nextDouble() * 1.5) * (1.0 - Math.pow(1.0 - kd, 2.0));
                    Vec3 p = g.add(Math.cos(a) * r, 0.4 + kd * (0.5 + rnd.nextDouble() * 0.8), Math.sin(a) * r);
                    billboard(pose, cam, camera, p, (float) (1.4 + kd * 1.8), (float) (a + t * 0.01), TEX_SMOKE, 1f, 1f, 1f, dust * 0.55f, false);
                }
            }
            float cracks = env(t, T_IMPACT, T_END + FX_TAIL, 0.5, 20);
            if (cracks > 0.01f) {
                flat(pose, camera, new Vec3(s.kick.x, s.groundY + 0.02, s.kick.z), (float) (CRATER_R * 2.0 + 4.0), (float) (c.seed % 6),
                        TEX_CRACKS, 1f, 1f, 1f, cracks, false);
            }
            float glints = env(t, T_IMPACT, T_IMPACT + 12, 0.3, 8);
            if (glints > 0.01f) {
                Random rnd = new Random(c.seed * 977L + 10);
                for (int i = 0; i < 12; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2.0;
                    Vec3 dir = new Vec3(Math.cos(a) * 0.8, 0.5 + rnd.nextDouble(), Math.sin(a) * 0.8).normalize();
                    Vec3 b = g.add(dir.scale(0.4 + 4.0 * k * (0.6 + rnd.nextDouble() * 0.5)));
                    streak(pose, camera, b, b.add(dir.scale(0.55)), 0.06f, TEX_GLINT, 1f, 1f, 1f, glints, true);
                }
            }
        }

        // 10. Приземление после отпрыгивания — лёгкая пыль.
        float land = env(t, T_LAND, T_LAND + 14, 0.5, 10);
        if (land > 0.01f) {
            double k = win(t, T_LAND, T_LAND + 14);
            flat(pose, camera, s.landing.add(0.0, 0.05, 0.0), (float) (0.8 + 2.4 * k), (float) (t * 0.1), TEX_WIND, 1f, 1f, 1f, land * 0.6f, false);
        }
    }

    private static void renderGrab(Minecraft mc, PoseStack pose, Camera cam, Vec3 camera, GrabScene g, double t, float pt) {
        Random rnd = new Random(g.seed);
        Vec3 center = g.center();
        // Рука: от глаз чуть вниз, вперёд по взгляду, правее.
        Vec3 palm = center;
        Vec3 eye = Vec3.ZERO, look = new Vec3(0, 0, 1);
        if (mc.level.getEntity(g.casterEntityId) instanceof Player owner) {
            eye = owner.getEyePosition(pt);
            look = owner.getViewVector(pt);
            Vec3 side = look.cross(new Vec3(0.0, 1.0, 0.0));
            side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
            palm = eye.add(0.0, -0.42, 0.0).add(look.scale(0.62)).add(side.scale(0.30));
        }

        float arc = env(t, T_ARC, 15, 3, 4);
        if (arc > 0.01f) {
            float flick = (float) (0.75 + 0.25 * Math.sin(t * 13.0));
            billboard(pose, cam, camera, center, 3.4f, (float) (t * 0.02), TEX_ARC, 1f, 1f, 1f, arc * flick, true);
            billboard(pose, cam, camera, center, 2.9f, (float) (Math.PI - t * 0.03), TEX_ARC, 0.7f, 0.9f, 1f, arc * 0.6f, true);
        }
        float orb = env(t, 2, 12, 2, 3);
        if (orb > 0.01f) {
            float r = (float) (0.09 + 0.22 * smooth((t - 2) / 5.0));
            blueOrb(pose, cam, camera, palm, r, t, orb, g.seed);
        }
        float beam = env(t, 5, 9, 0.5, 2);
        if (beam > 0.01f) energyBeam(pose, camera, palm, center, t, beam, g.seed);
        float burst = env(t, T_BURST, T_BURST + 9, 0.5, 6);
        if (burst > 0.01f) blueBurst(pose, cam, camera, center, win(t, T_BURST, T_BURST + 8), burst, t, 0.9f, g.seed);
        // Притягивание: голубые штрихи от блоков к руке, потом синий гаснет совсем.
        float pull = env(t, G_PULL_START, G_FX_END, 1, 5);
        if (pull > 0.01f) {
            for (int i = 0; i < g.origins.size(); i++) {
                Vec3 p = grabPos(g.origins.get(i), holdPos(eye, look, i), t).add(0.0, 0.5, 0.0);
                Vec3 dir = palm.subtract(p);
                double len = dir.length();
                if (len < 1.0E-3) continue;
                dir = dir.scale(1.0 / len);
                for (int k = 0; k < 3; k++) {
                    double ph = ((t * 0.25 + k / 3.0 + rnd.nextDouble()) % 1.0) * len;
                    Vec3 a = p.add(dir.scale(ph));
                    streak(pose, camera, a, a.add(dir.scale(Math.min(0.5, len - ph))), 0.05f, TEX_GLINT, 0.55f, 0.85f, 1f, pull * 0.8f, true);
                }
            }
        }
    }

    private static float[] prismColor(double k) {
        float[][] c = {{1.0f, 0.25f, 0.95f}, {0.66f, 0.42f, 1.0f}, {0.45f, 0.95f, 0.92f}, {0.92f, 0.96f, 0.97f}};
        double x = Mth.clamp(k, 0.0, 0.9999) * 3.0;
        int i = (int) x;
        float f = (float) (x - i);
        return new float[]{Mth.lerp(f, c[i][0], c[i + 1][0]), Mth.lerp(f, c[i][1], c[i + 1][1]), Mth.lerp(f, c[i][2], c[i + 1][2])};
    }

    private static Vec3 randDir(Random rnd) {
        double a = rnd.nextDouble() * Math.PI * 2.0, z = rnd.nextDouble() * 2.0 - 1.0, r = Math.sqrt(1.0 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    // ================================================================== модель Синего

    /** Объёмный синий шар: ядро, две закрученные навстречу оболочки, светлый ободок, молнии, клубы и ореол. */
    private static void blueOrb(PoseStack pose, Camera cam, Vec3 camera, Vec3 c, float r, double t, float alpha, long seed) {
        billboard(pose, cam, camera, c, r * 6.0f, 0f, TEX_BLOOM, 0.30f, 0.62f, 1f, alpha * 0.7f, true);
        for (int i = 0; i < 3; i++) {
            float roll = (float) (t * 0.07 * (i % 2 == 0 ? 1 : -1) + i * 2.1);
            billboard(pose, cam, camera, c, r * (3.4f + i * 0.5f), roll, TEX_WISP, 0.55f, 0.75f, 1f, alpha * 0.55f, false);
        }
        sphere(pose, camera, c, r * 1.22f, TEX_ENERGY, t * 0.021, t * 0.009, 0.22f, 0.50f, 1f, alpha * 0.55f, 1.8f, 16, 24);
        sphere(pose, camera, c, r * 0.95f, TEX_ENERGY, -t * 0.034, -t * 0.013 + 0.5, 0.55f, 0.85f, 1f, alpha * 0.8f, 1.0f, 14, 22);
        colorSphere(pose, camera, c, r * 0.55f, 0.92f, 0.98f, 1f, alpha, 12, 18);
        crackles(pose, camera, c, r * 1.15f, t, alpha, seed, 5);
    }

    /** Синий взрыв: расширяющийся купол из закрученной энергии с ярким краем, ядро гаснет. */
    private static void blueBurst(PoseStack pose, Camera cam, Vec3 camera, Vec3 c, double k, float alpha, double t,
                                  float scale, long seed) {
        float big = (float) ((0.6 + 4.0 * (1.0 - Math.pow(1.0 - k, 3.0))) * scale);
        billboard(pose, cam, camera, c, big * 2.2f, (float) (t * 0.05), TEX_BURST, 1f, 1f, 1f, alpha * 0.8f, true);
        sphere(pose, camera, c, big, TEX_ENERGY, t * 0.05, t * 0.02, 0.30f, 0.75f, 1f, alpha * 0.55f, 2.4f, 18, 28);
        sphere(pose, camera, c, big * 0.82f, TEX_ENERGY, -t * 0.07, -t * 0.03 + 0.3, 0.55f, 0.9f, 1f, alpha * 0.4f, 1.2f, 16, 24);
        float core = (float) (1.0 - k);
        if (core > 0.02f) {
            colorSphere(pose, camera, c, big * 0.32f, 0.95f, 1f, 1f, alpha * core, 12, 18);
            billboard(pose, cam, camera, c, big * 1.1f, 0f, TEX_BLOOM, 0.85f, 0.95f, 1f, alpha * core, true);
        }
        // Спирали энергии вокруг центра.
        for (int arm = 0; arm < 3; arm++) {
            Vec3 prev = null;
            for (int i = 0; i <= 18; i++) {
                double u = i / 18.0;
                double a = arm * 2.094 + u * 5.0 + t * 0.35;
                double rr = big * (0.25 + 0.75 * u);
                Vec3 p = c.add(Math.cos(a) * rr, (u - 0.5) * big * 0.9, Math.sin(a) * rr);
                if (prev != null) streak(pose, camera, prev, p, 0.05f * scale, TEX_GLINT, 0.6f, 0.9f, 1f, alpha * (float) (1.0 - u * 0.6), true);
                prev = p;
            }
        }
        crackles(pose, camera, c, big * 0.95f, t, alpha, seed + 3, 7);
    }

    /** Луч-молния от ладони к цели в момент броска синего. */
    private static void energyBeam(PoseStack pose, Vec3 camera, Vec3 from, Vec3 to, double t, float alpha, long seed) {
        Random rnd = new Random(seed * 131L + (long) (t * 0.5));
        Vec3 d = to.subtract(from);
        double len = d.length();
        if (len < 0.05) return;
        Vec3 dir = d.scale(1.0 / len);
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 upv = side.cross(dir).normalize();
        int n = Math.max(6, (int) (len * 3));
        Vec3 prev = from;
        for (int i = 1; i <= n; i++) {
            double u = i / (double) n;
            double j = i == n ? 0.0 : 0.18 * Math.sin(Math.PI * u);
            Vec3 p = from.add(d.scale(u)).add(side.scale((rnd.nextDouble() - 0.5) * 2 * j)).add(upv.scale((rnd.nextDouble() - 0.5) * 2 * j));
            streak(pose, camera, prev, p, 0.09f, TEX_GLINT, 0.55f, 0.85f, 1f, alpha * 0.8f, true);
            streak(pose, camera, prev, p, 0.03f, TEX_GLINT, 1f, 1f, 1f, alpha, true);
            prev = p;
        }
    }

    /** Мелкие молнии, бегущие по поверхности шара (меняются каждые 2 тика). */
    private static void crackles(PoseStack pose, Vec3 camera, Vec3 c, float r, double t, float alpha, long seed, int count) {
        Random rnd = new Random(seed * 7919L + (long) (t * 0.5));
        for (int k = 0; k < count; k++) {
            Vec3 a = randDir(rnd);
            Vec3 b = randDir(rnd);
            Vec3 prev = null;
            for (int i = 0; i <= 6; i++) {
                double u = i / 6.0;
                Vec3 dir = a.scale(1.0 - u).add(b.scale(u));
                if (dir.lengthSqr() < 1.0E-6) continue;
                double jitter = 1.0 + (rnd.nextDouble() - 0.5) * 0.25;
                Vec3 p = c.add(dir.normalize().scale(r * jitter));
                if (prev != null) streak(pose, camera, prev, p, 0.025f, TEX_GLINT, 0.8f, 0.95f, 1f, alpha * 0.9f, true);
                prev = p;
            }
        }
    }

    /** Сфера с бесшовной текстурой; край ярче (ободок), прозрачная середина — видно ядро. */
    private static void sphere(PoseStack pose, Vec3 camera, Vec3 c, float radius, ResourceLocation texture,
                               double uScroll, double vScroll, float r, float g, float b, float alpha, float rim,
                               int lat, int lon) {
        if (alpha <= 0.003f || radius <= 0.001f) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Vec3 view = camera.subtract(c);
        double vl = view.length();
        Vec3 v = vl < 1.0E-4 ? new Vec3(0, 0, 1) : view.scale(1.0 / vl);
        boolean inside = vl < radius;
        Matrix4f m = pose.last().pose();
        double ox = c.x - camera.x, oy = c.y - camera.y, oz = c.z - camera.z;
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int i = 0; i < lat; i++) {
            double p0 = -Math.PI / 2 + Math.PI * i / lat, p1 = -Math.PI / 2 + Math.PI * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                double a0 = Math.PI * 2 * j / lon, a1 = Math.PI * 2 * (j + 1) / lon;
                sphereVertex(buf, m, ox, oy, oz, radius, p0, a0, i, j, lat, lon, uScroll, vScroll, v, inside, r, g, b, alpha, rim);
                sphereVertex(buf, m, ox, oy, oz, radius, p0, a1, i, j + 1, lat, lon, uScroll, vScroll, v, inside, r, g, b, alpha, rim);
                sphereVertex(buf, m, ox, oy, oz, radius, p1, a1, i + 1, j + 1, lat, lon, uScroll, vScroll, v, inside, r, g, b, alpha, rim);
                sphereVertex(buf, m, ox, oy, oz, radius, p0, a0, i, j, lat, lon, uScroll, vScroll, v, inside, r, g, b, alpha, rim);
                sphereVertex(buf, m, ox, oy, oz, radius, p1, a1, i + 1, j + 1, lat, lon, uScroll, vScroll, v, inside, r, g, b, alpha, rim);
                sphereVertex(buf, m, ox, oy, oz, radius, p1, a0, i + 1, j, lat, lon, uScroll, vScroll, v, inside, r, g, b, alpha, rim);
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void sphereVertex(BufferBuilder buf, Matrix4f m, double ox, double oy, double oz, float radius,
                                     double phi, double theta, int i, int j, int lat, int lon, double uScroll, double vScroll,
                                     Vec3 v, boolean inside, float r, float g, float b, float alpha, float rim) {
        double cp = Math.cos(phi);
        double nx = Math.cos(theta) * cp, ny = Math.sin(phi), nz = Math.sin(theta) * cp;
        double facing = Math.abs(nx * v.x + ny * v.y + nz * v.z);
        double edge = inside ? 0.6 : Math.pow(1.0 - facing, 2.0);
        float a = (float) Mth.clamp(alpha * (0.35 + rim * edge), 0.0, 1.0);
        float u = (float) (j / (double) lon * 2.0 + uScroll);
        float tv = (float) (i / (double) lat + vScroll);
        buf.vertex(m, (float) (ox + nx * radius), (float) (oy + ny * radius), (float) (oz + nz * radius))
                .uv(u, tv).color(r, g, b, a).endVertex();
    }

    /** Сплошная светящаяся сфера (ядро): ярче в центре, мягкий край. */
    private static void colorSphere(PoseStack pose, Vec3 camera, Vec3 c, float radius, float r, float g, float b,
                                    float alpha, int lat, int lon) {
        if (alpha <= 0.003f || radius <= 0.001f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Vec3 view = camera.subtract(c);
        double vl = view.length();
        Vec3 v = vl < 1.0E-4 ? new Vec3(0, 0, 1) : view.scale(1.0 / vl);
        Matrix4f m = pose.last().pose();
        double ox = c.x - camera.x, oy = c.y - camera.y, oz = c.z - camera.z;
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < lat; i++) {
            double p0 = -Math.PI / 2 + Math.PI * i / lat, p1 = -Math.PI / 2 + Math.PI * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                double a0 = Math.PI * 2 * j / lon, a1 = Math.PI * 2 * (j + 1) / lon;
                double[][] pts = {{p0, a0}, {p0, a1}, {p1, a1}, {p0, a0}, {p1, a1}, {p1, a0}};
                for (double[] q : pts) {
                    double cp = Math.cos(q[0]);
                    double nx = Math.cos(q[1]) * cp, ny = Math.sin(q[0]), nz = Math.sin(q[1]) * cp;
                    double facing = Math.max(0.0, nx * v.x + ny * v.y + nz * v.z);
                    float a = (float) (alpha * Math.pow(facing, 0.7));
                    buf.vertex(m, (float) (ox + nx * radius), (float) (oy + ny * radius), (float) (oz + nz * radius))
                            .color(r, g, b, a).endVertex();
                }
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    // ================================================================== примитивы

    private static void quadVertex(BufferBuilder b, Matrix4f m, double x, double y, double z, float u, float v,
                                   float r, float g, float bl, float a) {
        b.vertex(m, (float) x, (float) y, (float) z).uv(u, v).color(r, g, bl, a).endVertex();
    }

    private static void blend(boolean additive) {
        if (additive) HollowPurpleReferenceClient.mpAdditiveBlend();
        else HollowPurpleReferenceClient.mpAlphaBlend();
    }

    /** Квадрат, повёрнутый к камере. */
    private static void billboard(PoseStack pose, Camera cam, Vec3 camera, Vec3 center, float size, float roll,
                                  ResourceLocation texture, float r, float g, float b, float alpha, boolean additive) {
        if (alpha <= 0.003f || size <= 0.0f) return;
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double lx = leftV.x() * cr + upV.x() * sr, ly = leftV.y() * cr + upV.y() * sr, lz = leftV.z() * cr + upV.z() * sr;
        double ux = upV.x() * cr - leftV.x() * sr, uy = upV.y() * cr - leftV.y() * sr, uz = upV.z() * cr - leftV.z() * sr;
        double hs = size * 0.5;
        double x = center.x - camera.x, y = center.y - camera.y, z = center.z - camera.z;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        blend(additive);
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(buf, m, x + (lx + ux) * hs, y + (ly + uy) * hs, z + (lz + uz) * hs, 0f, 0f, r, g, b, alpha);
        quadVertex(buf, m, x + (lx - ux) * hs, y + (ly - uy) * hs, z + (lz - uz) * hs, 0f, 1f, r, g, b, alpha);
        quadVertex(buf, m, x + (-lx - ux) * hs, y + (-ly - uy) * hs, z + (-lz - uz) * hs, 1f, 1f, r, g, b, alpha);
        quadVertex(buf, m, x + (-lx + ux) * hs, y + (-ly + uy) * hs, z + (-lz + uz) * hs, 1f, 0f, r, g, b, alpha);
        BufferUploader.drawWithShader(buf.end());
    }

    /** Горизонтальный квадрат (кольца, вихри, трещины на земле). */
    private static void flat(PoseStack pose, Vec3 camera, Vec3 center, float size, float rot, ResourceLocation texture,
                             float r, float g, float b, float alpha, boolean additive) {
        if (alpha <= 0.003f || size <= 0.0f) return;
        double hs = size * 0.5, c = Math.cos(rot) * hs, s = Math.sin(rot) * hs;
        double x = center.x - camera.x, y = center.y - camera.y, z = center.z - camera.z;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        blend(additive);
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(buf, m, x - c + s, y, z - s - c, 0f, 0f, r, g, b, alpha);
        quadVertex(buf, m, x - c - s, y, z - s + c, 0f, 1f, r, g, b, alpha);
        quadVertex(buf, m, x + c - s, y, z + s + c, 1f, 1f, r, g, b, alpha);
        quadVertex(buf, m, x + c + s, y, z + s - c, 1f, 0f, r, g, b, alpha);
        BufferUploader.drawWithShader(buf.end());
    }

    /** Вытянутый блик вдоль отрезка, повёрнутый к камере. */
    private static void streak(PoseStack pose, Vec3 camera, Vec3 from, Vec3 to, float width, ResourceLocation texture,
                               float r, float g, float b, float alpha, boolean additive) {
        if (alpha <= 0.003f) return;
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-8) return;
        Vec3 mid = from.add(to).scale(0.5);
        Vec3 view = camera.subtract(mid);
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        blend(additive);
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Vec3 a = from.subtract(camera), c = to.subtract(camera);
        quadVertex(buf, m, a.x + side.x, a.y + side.y, a.z + side.z, 0f, 0f, r, g, b, alpha);
        quadVertex(buf, m, a.x - side.x, a.y - side.y, a.z - side.z, 1f, 0f, r, g, b, alpha);
        quadVertex(buf, m, c.x - side.x, c.y - side.y, c.z - side.z, 1f, 1f, r, g, b, alpha);
        quadVertex(buf, m, c.x + side.x, c.y + side.y, c.z + side.z, 0f, 1f, r, g, b, alpha);
        BufferUploader.drawWithShader(buf.end());
    }

    /** Полупрозрачный ромб к камере (призмы-искры). */
    private static void diamond(PoseStack pose, Camera cam, Vec3 camera, Vec3 center, float size, float roll,
                                float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vector3f upV = cam.getUpVector();
        Vector3f leftV = cam.getLeftVector();
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double lx = leftV.x() * cr + upV.x() * sr, ly = leftV.y() * cr + upV.y() * sr, lz = leftV.z() * cr + upV.z() * sr;
        double ux = upV.x() * cr - leftV.x() * sr, uy = upV.y() * cr - leftV.y() * sr, uz = upV.z() * cr - leftV.z() * sr;
        double hs = size * 0.5;
        double x = center.x - camera.x, y = center.y - camera.y, z = center.z - camera.z;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAlphaBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(m, (float) (x + ux * hs), (float) (y + uy * hs), (float) (z + uz * hs)).color(r, g, b, alpha).endVertex();
        buf.vertex(m, (float) (x + lx * hs), (float) (y + ly * hs), (float) (z + lz * hs)).color(r, g, b, alpha * 0.6f).endVertex();
        buf.vertex(m, (float) (x - ux * hs), (float) (y - uy * hs), (float) (z - uz * hs)).color(r, g, b, alpha).endVertex();
        buf.vertex(m, (float) (x - lx * hs), (float) (y - ly * hs), (float) (z - lz * hs)).color(r, g, b, alpha * 0.6f).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }

    // ================================================================== интерфейс

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || COMBOS.isEmpty()) return;
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();
        float pt = event.getPartialTick();
        for (ComboScene c : COMBOS.values()) {
            if (!c.localCaster) continue;
            double t = c.ticks + pt;
            // Чем ближе взрыв к глазам, тем сильнее волна света (издалека — почти нет).
            Vec3 t0c = c.s.t0.add(0.0, c.s.targetHeight * 0.5, 0.0);
            double dist = mc.gameRenderer.getMainCamera().getPosition().distanceTo(t0c);
            float near = (float) (1.0 - Mth.clamp((dist - 4.0) / 10.0, 0.0, 0.85));
            // Синий взрыв прямо перед глазами — голубая волна и белая вспышка.
            float cyan = env(t, T_BURST, T_BURST + 4, 0.6, 2.5) * 0.42f * near;
            if (cyan > 0.01f) fill(event, w, h, cyan, 0x18D8FF);
            float flash = env(t, T_BURST + 2.5, T_BURST + 5.5, 0.4, 2.5) * 0.30f * near;
            if (flash > 0.01f) fill(event, w, h, flash, 0xEFFBFF);
            // Удар о землю в замедлении — белая вспышка.
            float white = env(t, T_IMPACT, T_IMPACT + 3, 0.2, 2.5) * 0.45f;
            if (white > 0.01f && c == camScene) fill(event, w, h, white, 0xFFFFFF);
        }
    }

    private static void fill(RenderGuiEvent.Post event, int w, int h, float alpha, int rgb) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        if (a <= 0) return;
        event.getGuiGraphics().fill(0, 0, w, h, (a << 24) | (rgb & 0x00FFFFFF));
    }
}
