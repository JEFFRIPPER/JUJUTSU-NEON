package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
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

import static com.kira.jujutsuneon.CursedFx.*;

/**
 * Картинка Бесконечности и обратной проклятой техники.
 *
 * Бесконечность — невидимая преграда, поэтому эффекты тихие: при включении из груди расходится
 * рябь пространства, вокруг тела проступает тонкая сетка-оболочка, а пылинки, летящие к телу,
 * вязнут и замирают на ней; дальше оболочка едва заметна, по ней изредка проходит блик.
 * Удар, который она остановила, — рябь и вспышка ровно в точке касания, оболочка вздрагивает.
 *
 * Обратная техника — свет снизу вверх: кольцо энергии стягивается к ногам, вокруг тела поднимаются
 * две светлые спирали, из земли всплывают искры, грудь вспыхивает в такт ударам сердца.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CursedFxClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_GLOW = tex("cursed_glow");
    private static final ResourceLocation TEX_RIPPLE = tex("cursed_ripple");
    private static final ResourceLocation TEX_MOTE = tex("cursed_mote");
    private static final ResourceLocation TEX_STREAK = tex("move_streak");
    private static final ResourceLocation TEX_RING = tex("move_ring");

    // Оболочка Бесконечности: эллипсоид вокруг тела.
    private static final double SHELL_RX = 1.05;
    private static final double SHELL_RY = 1.30;
    private static final double SHELL_CY = 0.95;

    private static final int ON_LIFE = 44;
    private static final int OFF_LIFE = 16;
    private static final int BLOCK_LIFE = 18;
    private static final int RCT_LIFE = 46;

    private static final class Inf {
        boolean on = true;
        long lastSeen;
        float w, wo;
        float flash, flashO;
        int age;
        final long seed;

        Inf(int id) {
            seed = id * 7919L + 13L;
        }
    }

    private static final class Fx {
        final int entityId;
        final int type;
        final Vec3 dir;
        final long seed;
        int age;
        final int life;

        Fx(int entityId, int type, Vec3 dir, int life) {
            this.entityId = entityId;
            this.type = type;
            this.dir = dir;
            this.life = life;
            this.seed = entityId * 31L + System.nanoTime();
        }
    }

    private static final class Mote {
        Vec3 pos, prev, vel;
        Vec3 target;
        final int life;
        int age;
        final float size, r, g, b;
        final double drag;

        Mote(Vec3 pos, Vec3 vel, Vec3 target, int life, float size, float r, float g, float b, double drag) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.target = target;
            this.life = life;
            this.size = size;
            this.r = r;
            this.g = g;
            this.b = b;
            this.drag = drag;
        }
    }

    private static final Map<Integer, Inf> INF = new HashMap<>();
    private static final List<Fx> FX = new ArrayList<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final Random RND = new Random();
    private static long clock;

    private CursedFxClient() {
    }

    // ================================================================== события

    static void onEvent(int entityId, int type, Vec3 dir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        switch (type) {
            case EV_INFINITY_ON -> {
                Inf inf = INF.computeIfAbsent(entityId, Inf::new);
                inf.on = true;
                inf.lastSeen = clock;
                inf.age = 0;
                FX.add(new Fx(entityId, type, Vec3.ZERO, ON_LIFE));
                if (e != null) infinityMotes(center(e, 1f));
            }
            case EV_INFINITY_HOLD -> {
                Inf inf = INF.get(entityId);
                if (inf == null) {
                    inf = new Inf(entityId);
                    inf.age = ON_LIFE;
                    INF.put(entityId, inf);
                }
                inf.on = true;
                inf.lastSeen = clock;
            }
            case EV_INFINITY_OFF -> {
                Inf inf = INF.get(entityId);
                if (inf != null) inf.on = false;
                FX.add(new Fx(entityId, type, Vec3.ZERO, OFF_LIFE));
                if (e != null) {
                    Vec3 c = center(e, 1f);
                    for (int i = 0; i < 10; i++) {
                        Vec3 d = randDir(RND);
                        Vec3 p = shellPoint(c, d, 0.95);
                        MOTES.add(new Mote(p, d.scale(0.03).add(0, -0.01, 0), null, 14 + RND.nextInt(8), 0.10f,
                                0.7f, 0.9f, 1f, 0.92));
                    }
                }
            }
            case EV_INFINITY_BLOCK -> {
                Inf inf = INF.computeIfAbsent(entityId, Inf::new);
                inf.on = true;
                inf.lastSeen = clock;
                inf.flash = 1f;
                Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : dir.normalize();
                FX.add(new Fx(entityId, type, d, BLOCK_LIFE));
                if (e != null) {
                    Vec3 p = shellPoint(center(e, 1f), d, 1.0);
                    for (int i = 0; i < 8; i++) {
                        Vec3 v = randDir(RND).scale(0.05).add(d.scale(0.04));
                        MOTES.add(new Mote(p, v, null, 10 + RND.nextInt(8), 0.09f, 0.75f, 0.92f, 1f, 0.8));
                    }
                }
            }
            case EV_RCT -> FX.add(new Fx(entityId, type, Vec3.ZERO, RCT_LIFE));
            default -> {
            }
        }
    }

    /** Пылинки летят к телу и вязнут в Бесконечности, замирая на оболочке. */
    private static void infinityMotes(Vec3 c) {
        for (int i = 0; i < 28; i++) {
            Vec3 d = randDir(RND);
            Vec3 start = c.add(d.x * 3.2, d.y * 2.6, d.z * 3.2);
            Vec3 stop = shellPoint(c, d, 1.0 + RND.nextDouble() * 0.15);
            MOTES.add(new Mote(start, Vec3.ZERO, stop, 34 + RND.nextInt(20), 0.07f + RND.nextFloat() * 0.06f,
                    0.75f, 0.92f, 1f, 0.16 + RND.nextDouble() * 0.06));
        }
    }

    // ================================================================== тики

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!INF.isEmpty() || !FX.isEmpty() || !MOTES.isEmpty()) resetAll();
            return;
        }
        if (mc.isPaused()) return;
        clock++;

        Iterator<Map.Entry<Integer, Inf>> ii = INF.entrySet().iterator();
        while (ii.hasNext()) {
            Map.Entry<Integer, Inf> en = ii.next();
            Inf inf = en.getValue();
            // Сервер напоминает раз в секунду; пропало напоминание — Бесконечность выключили (сняли повязку и т.п.).
            if (inf.on && clock - inf.lastSeen > 50) inf.on = false;
            inf.age++;
            inf.wo = inf.w;
            inf.flashO = inf.flash;
            float target = inf.on ? 1f : 0f;
            inf.w += (target - inf.w) * (inf.on ? 0.10f : 0.25f);
            inf.flash *= 0.8f;
            if (!inf.on && inf.w < 0.01f) ii.remove();
            else if (mc.level.getEntity(en.getKey()) == null && clock - inf.lastSeen > 60) ii.remove();
        }

        Iterator<Fx> fi = FX.iterator();
        while (fi.hasNext()) {
            Fx f = fi.next();
            f.age++;
            if (f.type == EV_RCT) tickRct(mc, f);
            if (f.age > f.life) fi.remove();
        }

        Iterator<Mote> mi = MOTES.iterator();
        while (mi.hasNext()) {
            Mote m = mi.next();
            m.prev = m.pos;
            m.age++;
            if (m.target != null) {
                // Экспоненциальное замедление к точке на оболочке — «время вязнет».
                m.pos = m.pos.lerp(m.target, m.drag);
            } else {
                m.pos = m.pos.add(m.vel);
                m.vel = m.vel.scale(m.drag);
            }
            if (m.age > m.life) mi.remove();
        }
    }

    private static void tickRct(Minecraft mc, Fx f) {
        Entity e = mc.level.getEntity(f.entityId);
        if (e == null) return;
        Vec3 feet = e.position();
        if (f.age >= 3 && f.age <= 32) {
            for (int i = 0; i < 2; i++) {
                double a = RND.nextDouble() * Math.PI * 2.0;
                double r = 0.35 + RND.nextDouble() * 0.6;
                Vec3 p = feet.add(Math.cos(a) * r, 0.05 + RND.nextDouble() * 0.3, Math.sin(a) * r);
                boolean white = RND.nextInt(3) == 0;
                MOTES.add(new Mote(p, new Vec3(0, 0.05 + RND.nextDouble() * 0.06, 0), null, 20 + RND.nextInt(14),
                        0.07f + RND.nextFloat() * 0.05f, white ? 1f : 0.55f, 1f, white ? 0.95f : 0.75f, 0.965));
            }
        }
        if (f.age == 30) {
            Vec3 c = feet.add(0, 1.2, 0);
            for (int i = 0; i < 16; i++) {
                Vec3 d = randDir(RND);
                Vec3 v = new Vec3(d.x * 0.08, Math.abs(d.y) * 0.08 + 0.03, d.z * 0.08);
                MOTES.add(new Mote(c, v, null, 16 + RND.nextInt(10), 0.08f, 0.85f, 1f, 0.9f, 0.9));
            }
        }
    }

    private static void resetAll() {
        INF.clear();
        FX.clear();
        MOTES.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

    // ================================================================== отрисовка

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (INF.isEmpty() && FX.isEmpty() && MOTES.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        Vec3 camera = cam.getPosition();
        float pt = mc.isPaused() ? 0.0f : event.getPartialTick();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // Оболочки Бесконечности.
        for (Map.Entry<Integer, Inf> en : INF.entrySet()) {
            Entity e = mc.level.getEntity(en.getKey());
            if (e == null) continue;
            if (e == mc.player && firstPerson) continue; // своя оболочка — вокруг камеры, не мешаем обзору
            Inf inf = en.getValue();
            float w = Mth.lerp(pt, inf.wo, inf.w);
            float flash = Mth.lerp(pt, inf.flashO, inf.flash);
            double t = inf.age + pt;
            float burst = inf.age < ON_LIFE ? (float) (1.0 - smooth((t - 6) / 26.0)) : 0f;
            double grow = inf.age < ON_LIFE ? 0.25 + 0.75 * (1.0 - Math.pow(1.0 - Mth.clamp(t / 9.0, 0, 1), 3)) : 1.0;
            if (!inf.on) grow = 0.35 + 0.65 * w;
            float a = w * (0.07f + 0.32f * burst + 0.35f * flash);
            renderShell(pose, camera, center(e, pt), grow, t, a, inf.seed);
        }

        for (Fx f : FX) {
            Entity e = mc.level.getEntity(f.entityId);
            if (e == null) continue;
            double t = f.age + pt;
            boolean self = e == mc.player && firstPerson;
            switch (f.type) {
                case EV_INFINITY_ON -> renderInfinityOn(pose, cam, camera, e, t, pt, self);
                case EV_INFINITY_OFF -> renderInfinityOff(pose, cam, camera, e, t, pt, self);
                case EV_INFINITY_BLOCK -> renderBlock(pose, cam, camera, e, f, t, pt, firstPerson);
                case EV_RCT -> renderRct(pose, cam, camera, e, f, t, pt, self);
                default -> {
                }
            }
        }

        begin(pose, camera, TEX_MOTE, true);
        for (Mote m : MOTES) {
            double k = (m.age + pt) / m.life;
            float a = (float) (smooth(k / 0.15) * (1.0 - smooth((k - 0.6) / 0.4)));
            Vec3 at = m.prev.lerp(m.pos, pt);
            a *= nearFade(at, camera, firstPerson);
            float tw = 0.75f + 0.25f * (float) Math.sin((m.age + pt) * 1.3 + m.size * 100);
            sprite(cam, at, m.size * tw, m.r, m.g, m.b, a);
        }
        end();

        HollowPurpleReferenceClient.mpAlphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Сетка-оболочка: параллели и меридианы, медленно вращается; по ней снизу вверх проходит блик. */
    private static void renderShell(PoseStack pose, Vec3 camera, Vec3 c, double scale, double t, float alpha, long seed) {
        if (alpha <= 0.004f) return;
        double rot = t * 0.012 + (seed % 100) * 0.06;
        double sweep = ((t * 0.012 + (seed % 37) * 0.03) % 1.6) * Math.PI - 0.2; // блик идёт снизу вверх, потом пауза
        begin(pose, camera, TEX_STREAK, true);
        // параллели
        for (int i = 1; i <= 6; i++) {
            double th = Math.PI * i / 7.0;
            float shine = (float) Math.exp(-Math.pow((th - sweep) / 0.22, 2)) * 2.2f;
            float a = alpha * (0.8f + shine);
            Vec3 prev = null;
            for (int j = 0; j <= 28; j++) {
                double ph = rot + Math.PI * 2.0 * j / 28.0;
                Vec3 p = ell(c, th, ph, scale);
                if (prev != null) seg(prev, p, 0.014f, 0.62f, 0.86f, 1f, a);
                prev = p;
            }
        }
        // меридианы
        for (int i = 0; i < 8; i++) {
            double ph = rot + Math.PI * 2.0 * i / 8.0;
            Vec3 prev = null;
            for (int j = 0; j <= 16; j++) {
                double th = 0.18 + (Math.PI - 0.36) * j / 16.0;
                float shine = (float) Math.exp(-Math.pow((th - sweep) / 0.22, 2)) * 2.2f;
                Vec3 p = ell(c, th, ph, scale);
                if (prev != null) seg(prev, p, 0.012f, 0.62f, 0.86f, 1f, alpha * (0.6f + shine));
                prev = p;
            }
        }
        end();
    }

    private static void renderInfinityOn(PoseStack pose, Camera cam, Vec3 camera, Entity e, double t, float pt, boolean self) {
        Vec3 c = center(e, pt);
        Vec3 ft = feet(e, pt);
        double k = t / ON_LIFE;
        // вспышка в груди
        float g = (float) Math.pow(Math.max(0.0, 1.0 - t / 10.0), 2.0);
        if (!self) {
            begin(pose, camera, TEX_GLOW, true);
            sprite(cam, c, (float) (0.8 + 2.2 * (1.0 - g)), 0.55f, 0.85f, 1f, g * 0.7f);
            end();
        }
        // рябь пространства — расходится от тела (от первого лица — только круг по земле)
        begin(pose, camera, TEX_RIPPLE, true);
        if (self) t = -100;
        for (int i = 0; i < 3; i++) {
            double tt = t - i * 4.0;
            if (tt < 0 || tt > 16) continue;
            double kk = tt / 16.0;
            float a = (float) ((1.0 - kk) * (1.0 - kk)) * (0.75f - i * 0.18f);
            sprite(cam, c, (float) (1.2 + 5.5 * (1.0 - Math.pow(1.0 - kk, 2.0))), 0.6f, 0.88f, 1f,
                    a * (self ? 0.35f : 1f));
        }
        end();
        // круг по земле
        float ga = (float) (1.0 - smooth(k / 0.6));
        if (ga > 0.01f) {
            begin(pose, camera, TEX_RIPPLE, true);
            flatQuad(ft.add(0, 0.04, 0), (float) (1.0 + 6.0 * (1.0 - Math.pow(1.0 - Math.min(1.0, k * 2.0), 2.0))),
                    0.55f, 0.85f, 1f, ga * 0.6f);
            end();
        }
    }

    private static void renderInfinityOff(PoseStack pose, Camera cam, Vec3 camera, Entity e, double t, float pt, boolean self) {
        if (self) return;
        Vec3 c = center(e, pt);
        double k = t / OFF_LIFE;
        float a = (float) (1.0 - k);
        begin(pose, camera, TEX_GLOW, true);
        sprite(cam, c, (float) (2.4 * (1.0 - k) + 0.4), 0.55f, 0.82f, 1f, a * a * 0.35f);
        end();
    }

    private static void renderBlock(PoseStack pose, Camera cam, Vec3 camera, Entity e, Fx f, double t, float pt, boolean firstPerson) {
        Vec3 p = shellPoint(center(e, pt), f.dir, 1.0);
        float near = nearFade(p, camera, firstPerson);
        // вспышка в точке касания
        float g = (float) Math.max(0.0, 1.0 - t / 6.0);
        begin(pose, camera, TEX_GLOW, true);
        sprite(cam, p, 0.5f + 0.9f * g, 0.75f, 0.92f, 1f, g * 0.9f * near);
        end();
        // рябь по оболочке в точке удара (три волны)
        begin(pose, camera, TEX_RIPPLE, true);
        for (int i = 0; i < 3; i++) {
            double tt = t - i * 3.0;
            if (tt < 0 || tt > 12) continue;
            double kk = tt / 12.0;
            float a = (float) (1.0 - kk) * (0.95f - i * 0.25f) * near;
            orientedQuad(p, f.dir, (float) (0.25 + 1.9 * (1.0 - Math.pow(1.0 - kk, 2.0))), 0.62f, 0.88f, 1f, a);
        }
        end();
        // короткие трещинки света по касательной
        Vec3 ref = Math.abs(f.dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = f.dir.cross(ref).normalize();
        Vec3 v = f.dir.cross(u).normalize();
        double kk = Mth.clamp(t / 10.0, 0, 1);
        float la = (float) (1.0 - kk) * near;
        Random rnd = new Random(f.seed);
        begin(pose, camera, TEX_STREAK, true);
        for (int i = 0; i < 9; i++) {
            double ang = i / 9.0 * Math.PI * 2.0 + rnd.nextDouble() * 0.4;
            Vec3 dir = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
            Vec3 a0 = p.add(dir.scale(0.12 + 0.5 * kk));
            seg(a0, a0.add(dir.scale(0.25 + 0.2 * rnd.nextDouble())), 0.025f, 0.85f, 0.95f, 1f, la);
        }
        end();
    }

    private static void renderRct(PoseStack pose, Camera cam, Vec3 camera, Entity e, Fx f, double t, float pt, boolean self) {
        Vec3 ft = feet(e, pt);
        Vec3 chest = ft.add(0, 1.15, 0);
        boolean fp = self;

        // кольцо энергии стягивается к ногам
        float ringA = (float) (smooth(t / 3.0) * (1.0 - smooth((t - 14) / 8.0)));
        begin(pose, camera, TEX_RING, true);
        if (ringA > 0.01f) {
            double s = 4.6 - 3.8 * smooth(t / 18.0);
            flatQuad(ft.add(0, 0.04, 0), (float) s, 0.5f, 1f, 0.72f, ringA * 0.85f);
            flatQuad(ft.add(0, 0.05, 0), (float) (s * 0.78), 0.95f, 1f, 0.95f, ringA * 0.45f);
        }
        end();
        float groundA = (float) (smooth(t / 4.0) * (1.0 - smooth((t - 26) / 18.0)));
        begin(pose, camera, TEX_GLOW, true);
        if (groundA > 0.01f) flatQuad(ft.add(0, 0.03, 0), 3.0f, 0.45f, 1f, 0.7f, groundA * 0.35f);
        // сердце: две вспышки в груди
        for (double beat : new double[]{1.0, 20.0}) {
            double tt = t - beat;
            if (tt < 0 || tt > 8 || fp) continue;
            double kk = tt / 8.0;
            float a = (float) ((1.0 - kk) * (1.0 - kk));
            sprite(cam, chest, (float) (0.6 + 1.8 * kk), 0.75f, 1f, 0.85f, a * 0.8f);
        }
        end();
        if (!fp) {
            begin(pose, camera, TEX_RIPPLE, true);
            for (double beat : new double[]{1.0, 20.0}) {
                double tt = t - beat;
                if (tt < 0 || tt > 10) continue;
                double kk = tt / 10.0;
                sprite(cam, chest, (float) (0.5 + 2.6 * kk), 0.6f, 1f, 0.8f, (float) (1.0 - kk) * 0.55f);
            }
            end();
        }

        // две спирали света поднимаются вокруг тела
        float ribA = (float) (smooth((t - 3) / 5.0) * (1.0 - smooth((t - 30) / 14.0)));
        if (ribA > 0.01f) {
            double head = 0.1 + 2.3 * smooth((t - 3) / 24.0);
            double tail = Math.max(0.0, head - 1.3);
            begin(pose, camera, TEX_STREAK, true);
            for (int strand = 0; strand < 2; strand++) {
                double phase = strand * Math.PI;
                float r = strand == 0 ? 0.55f : 1f, g = 1f, b = strand == 0 ? 0.75f : 0.9f;
                Vec3 prev = null;
                int n = 24;
                for (int j = 0; j <= n; j++) {
                    double y = tail + (head - tail) * j / n;
                    double ang = y * 4.2 + t * 0.22 + phase;
                    double rad = 0.55 + 0.07 * Math.sin(y * 3.0 + t * 0.3);
                    Vec3 p = ft.add(Math.cos(ang) * rad, y, Math.sin(ang) * rad);
                    if (prev != null) {
                        double u = j / (double) n;
                        float aa = ribA * (float) Math.pow(u, 1.4) * nearFade(p, camera, fp);
                        seg(prev, p, 0.10f, r, g, b, aa * 0.35f);
                        seg(prev, p, 0.035f, 1f, 1f, 1f, aa);
                    }
                    prev = p;
                }
            }
            end();
            // яркие «головы» спиралей
            begin(pose, camera, TEX_GLOW, true);
            for (int strand = 0; strand < 2; strand++) {
                double ang = head * 4.2 + t * 0.22 + strand * Math.PI;
                double rad = 0.55 + 0.07 * Math.sin(head * 3.0 + t * 0.3);
                Vec3 p = ft.add(Math.cos(ang) * rad, head, Math.sin(ang) * rad);
                sprite(cam, p, 0.55f, 0.8f, 1f, 0.9f, ribA * 0.8f * nearFade(p, camera, fp));
            }
            end();
        }
        // мягкий ореол вокруг тела в конце
        float halo = (float) (smooth((t - 18) / 8.0) * (1.0 - smooth((t - 32) / 14.0)));
        if (halo > 0.01f && !fp) {
            begin(pose, camera, TEX_GLOW, true);
            sprite(cam, ft.add(0, 1.0, 0), 2.6f, 0.5f, 1f, 0.75f, halo * 0.3f);
            end();
        }
    }

    // ================================================================== геометрия

    private static Vec3 feet(Entity e, float pt) {
        return new Vec3(Mth.lerp(pt, e.xOld, e.getX()), Mth.lerp(pt, e.yOld, e.getY()), Mth.lerp(pt, e.zOld, e.getZ()));
    }

    private static Vec3 center(Entity e, float pt) {
        return feet(e, pt).add(0, SHELL_CY, 0);
    }

    private static Vec3 ell(Vec3 c, double th, double ph, double scale) {
        double s = Math.sin(th);
        return c.add(s * Math.cos(ph) * SHELL_RX * scale, Math.cos(th) * SHELL_RY * scale, s * Math.sin(ph) * SHELL_RX * scale);
    }

    /** Точка оболочки в направлении d (d нормирован). */
    private static Vec3 shellPoint(Vec3 c, Vec3 d, double scale) {
        double k = 1.0 / Math.sqrt((d.x * d.x + d.z * d.z) / (SHELL_RX * SHELL_RX) + d.y * d.y / (SHELL_RY * SHELL_RY));
        return c.add(d.scale(k * scale));
    }

    private static Vec3 randDir(Random rnd) {
        double a = rnd.nextDouble() * Math.PI * 2.0, z = rnd.nextDouble() * 2.0 - 1.0, r = Math.sqrt(1.0 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    private static double smooth(double v) {
        v = Mth.clamp(v, 0.0, 1.0);
        return v * v * (3.0 - 2.0 * v);
    }

    private static float nearFade(Vec3 at, Vec3 camera, boolean firstPerson) {
        if (!firstPerson) return 1.0f;
        return (float) smooth((at.distanceTo(camera) - 0.6) / 1.2);
    }

    // ================================================================== пакетная отрисовка

    private static BufferBuilder buf;
    private static Matrix4f mat;
    private static Vec3 cam0;
    private static ResourceLocation pendingTex;
    private static boolean pendingAdditive;
    private static PoseStack pendingPose;

    /** Начать пачку квадов с одной текстурой (буфер открывается при первом квадe). */
    private static void begin(PoseStack pose, Vec3 camera, ResourceLocation texture, boolean additive) {
        end();
        pendingPose = pose;
        cam0 = camera;
        pendingTex = texture;
        pendingAdditive = additive;
    }

    private static void open() {
        if (buf != null || pendingTex == null) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, pendingTex);
        if (pendingAdditive) HollowPurpleReferenceClient.mpAdditiveBlend();
        else HollowPurpleReferenceClient.mpAlphaBlend();
        mat = pendingPose.last().pose();
        buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void end() {
        if (buf != null) {
            BufferUploader.drawWithShader(buf.end());
            buf = null;
        }
        pendingTex = null;
    }

    private static void v(double x, double y, double z, float u, float vv, float r, float g, float b, float a) {
        buf.vertex(mat, (float) (x - cam0.x), (float) (y - cam0.y), (float) (z - cam0.z)).uv(u, vv).color(r, g, b, a).endVertex();
    }

    private static void seg(Vec3 from, Vec3 to, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-8) return;
        Vec3 view = cam0.subtract(from.add(to).scale(0.5));
        Vec3 side = axis.cross(view);
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width);
        open();
        v(from.x + side.x, from.y + side.y, from.z + side.z, 0f, 0f, r, g, b, alpha);
        v(from.x - side.x, from.y - side.y, from.z - side.z, 1f, 0f, r, g, b, alpha);
        v(to.x - side.x, to.y - side.y, to.z - side.z, 1f, 1f, r, g, b, alpha);
        v(to.x + side.x, to.y + side.y, to.z + side.z, 0f, 1f, r, g, b, alpha);
    }

    private static void sprite(Camera cam, Vec3 c, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || size <= 0f) return;
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        double h = size * 0.5;
        open();
        v(c.x + (left.x() + up.x()) * h, c.y + (left.y() + up.y()) * h, c.z + (left.z() + up.z()) * h, 0f, 0f, r, g, b, alpha);
        v(c.x + (left.x() - up.x()) * h, c.y + (left.y() - up.y()) * h, c.z + (left.z() - up.z()) * h, 0f, 1f, r, g, b, alpha);
        v(c.x + (-left.x() - up.x()) * h, c.y + (-left.y() - up.y()) * h, c.z + (-left.z() - up.z()) * h, 1f, 1f, r, g, b, alpha);
        v(c.x + (-left.x() + up.x()) * h, c.y + (-left.y() + up.y()) * h, c.z + (-left.z() + up.z()) * h, 1f, 0f, r, g, b, alpha);
    }

    private static void flatQuad(Vec3 c, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || size <= 0f) return;
        double h = size * 0.5;
        open();
        v(c.x - h, c.y, c.z - h, 0f, 0f, r, g, b, alpha);
        v(c.x - h, c.y, c.z + h, 0f, 1f, r, g, b, alpha);
        v(c.x + h, c.y, c.z + h, 1f, 1f, r, g, b, alpha);
        v(c.x + h, c.y, c.z - h, 1f, 0f, r, g, b, alpha);
    }

    private static void orientedQuad(Vec3 c, Vec3 normal, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vec3 n = normal.normalize();
        Vec3 ref = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = n.cross(ref).normalize().scale(size * 0.5);
        Vec3 w = n.cross(u).normalize().scale(size * 0.5);
        open();
        v(c.x - u.x - w.x, c.y - u.y - w.y, c.z - u.z - w.z, 0f, 0f, r, g, b, alpha);
        v(c.x - u.x + w.x, c.y - u.y + w.y, c.z - u.z + w.z, 0f, 1f, r, g, b, alpha);
        v(c.x + u.x + w.x, c.y + u.y + w.y, c.z + u.z + w.z, 1f, 1f, r, g, b, alpha);
        v(c.x + u.x - w.x, c.y + u.y - w.y, c.z + u.z - w.z, 1f, 0f, r, g, b, alpha);
    }
}
