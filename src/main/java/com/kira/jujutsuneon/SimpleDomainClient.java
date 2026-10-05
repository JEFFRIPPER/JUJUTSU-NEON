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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
import java.util.UUID;

import static com.kira.jujutsuneon.SimpleDomain.*;

/**
 * Простая территория — картинка (по референсу): игрок приседает с печатью, у ног вспыхивает бирюзовое
 * кольцо, закручивается и расходится; по краю — вихрь, похожий на пламя, пол залит полупрозрачным
 * бирюзовым, в воздухе медленно всплывают искры. Удар по щиту — рябь и вспышка в точке касания;
 * третий удар — круг лопается осколками. Конец — круг сжимается в точку.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SimpleDomainClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_FLAME = tex("simple_flame");
    private static final ResourceLocation TEX_FLOOR = tex("simple_floor");
    private static final ResourceLocation TEX_GLOW = tex("cursed_glow");
    private static final ResourceLocation TEX_RIPPLE = tex("cursed_ripple");
    private static final ResourceLocation TEX_MOTE = tex("cursed_mote");
    private static final ResourceLocation TEX_STREAK = tex("move_streak");

    // бирюзовый из референса
    private static final float CR = 0.32f, CG = 1.0f, CB = 0.90f;

    private static final class Scene {
        final UUID ownerId;
        final int entityId;
        final Vec3 center;
        final boolean inDomain;
        final long seed;
        int age;
        boolean ending;
        boolean broken;
        int endAge;
        float pulse, pulseO;
        int outside = -1;

        Scene(UUID ownerId, int entityId, Vec3 center, boolean inDomain) {
            this.ownerId = ownerId;
            this.entityId = entityId;
            this.center = center;
            this.inDomain = inDomain;
            this.seed = ownerId.getLeastSignificantBits();
        }

        /** Радиус круга сейчас (растёт при раскрытии, сжимается в конце). */
        double radius(double t, double te) {
            if (ending) {
                if (broken) return RADIUS * (1.0 + 0.12 * Math.sin(Math.min(1.0, te / BREAK_TICKS) * Math.PI));
                double k = Mth.clamp(te / COLLAPSE_TICKS, 0.0, 1.0);
                return RADIUS * (1.0 - k * k * (3.0 - 2.0 * k));
            }
            double k = Mth.clamp(t / FORM_TICKS, 0.0, 1.0);
            return 0.35 + (RADIUS - 0.35) * (1.0 - Math.pow(1.0 - k, 3.0));
        }

        float alpha(double te) {
            if (!ending) return 1f;
            double life = broken ? BREAK_TICKS : COLLAPSE_TICKS;
            return (float) (1.0 - Mth.clamp(te / life, 0.0, 1.0) * (broken ? 1.0 : 0.4));
        }
    }

    private static final class Mote {
        Vec3 pos, prev, vel;
        final int life;
        int age;
        final float size;

        Mote(Vec3 pos, Vec3 vel, int life, float size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
        }
    }

    private static final class Hit {
        final Vec3 pos, normal;
        int age;

        Hit(Vec3 pos, Vec3 normal) {
            this.pos = pos;
            this.normal = normal;
        }
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final List<Hit> HITS = new ArrayList<>();
    private static final Random RND = new Random();

    private SimpleDomainClient() {
    }

    // ================================================================== API

    /** Локальный игрок стоит в своей простой территории (или ещё 0,5 с после выхода) — обездвиживание снято. */
    public static boolean freesLocalPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        Scene s = SCENES.get(mc.player.getUUID());
        return s != null && !s.ending && s.age >= CAST_TICKS && s.outside <= GRACE_TICKS;
    }

    static void onStart(UUID owner, int entityId, Vec3 center, boolean inDomain) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        SCENES.put(owner, new Scene(owner, entityId, center, inDomain));
        if (mc.level.getEntity(entityId) instanceof AbstractClientPlayer p) {
            MaximumPurpleAnimation.play(p, "simple_domain");
        }
    }

    static void onHit(UUID owner, Vec3 at, int hits) {
        Scene s = SCENES.get(owner);
        if (s == null) return;
        s.pulse = 1f;
        Vec3 n = at.subtract(s.center.add(0, 1.0, 0));
        HITS.add(new Hit(at, n.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : n.normalize()));
        for (int i = 0; i < 10; i++) {
            Vec3 v = randDir(RND).scale(0.06);
            MOTES.add(new Mote(at, v, 10 + RND.nextInt(10), 0.10f));
        }
    }

    static void onEnd(UUID owner, boolean broken) {
        Scene s = SCENES.get(owner);
        if (s == null || s.ending) return;
        s.ending = true;
        s.broken = broken;
        s.endAge = 0;
        if (broken) {
            // Лопнул: осколки света разлетаются от края во все стороны.
            for (int i = 0; i < 60; i++) {
                double a = RND.nextDouble() * Math.PI * 2.0;
                Vec3 p = s.center.add(Math.cos(a) * RADIUS, 0.2 + RND.nextDouble() * 1.6, Math.sin(a) * RADIUS);
                Vec3 v = new Vec3(Math.cos(a) * (0.08 + RND.nextDouble() * 0.15), 0.04 + RND.nextDouble() * 0.12,
                        Math.sin(a) * (0.08 + RND.nextDouble() * 0.15));
                MOTES.add(new Mote(p, v, 14 + RND.nextInt(14), 0.12f + RND.nextFloat() * 0.08f));
            }
        }
    }

    // ================================================================== тики

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!SCENES.isEmpty() || !MOTES.isEmpty()) resetAll();
            return;
        }
        if (mc.isPaused()) return;

        Iterator<Scene> it = SCENES.values().iterator();
        while (it.hasNext()) {
            Scene s = it.next();
            s.age++;
            s.pulseO = s.pulse;
            s.pulse *= 0.82f;
            if (s.ending) {
                s.endAge++;
                if (s.endAge > (s.broken ? BREAK_TICKS : COLLAPSE_TICKS) + 2) it.remove();
                continue;
            }
            if (s.age > 20 * 60 * 10) {
                it.remove();
                continue;
            }
            // Свой круг: внутри ли я (то же правило, что на сервере).
            if (mc.player != null && s.ownerId.equals(mc.player.getUUID()) && s.age >= CAST_TICKS) {
                Vec3 p = mc.player.position();
                double dx = p.x - s.center.x, dz = p.z - s.center.z;
                boolean inside = dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(p.y - s.center.y) < 4.0;
                s.outside = inside ? -1 : (s.outside < 0 ? 1 : s.outside + 1);
            }
            // Искры: медленно всплывают внутри круга.
            double r = s.radius(s.age, 0);
            for (int i = 0; i < 2; i++) {
                double a = RND.nextDouble() * Math.PI * 2.0;
                double d = Math.sqrt(RND.nextDouble()) * r * 0.95;
                Vec3 p = s.center.add(Math.cos(a) * d, 0.1 + RND.nextDouble() * 2.4, Math.sin(a) * d);
                MOTES.add(new Mote(p, new Vec3(0, 0.008 + RND.nextDouble() * 0.014, 0), 30 + RND.nextInt(24),
                        0.06f + RND.nextFloat() * 0.07f));
            }
        }

        Iterator<Mote> mi = MOTES.iterator();
        while (mi.hasNext()) {
            Mote m = mi.next();
            m.prev = m.pos;
            m.pos = m.pos.add(m.vel);
            m.vel = m.vel.scale(0.94).add(0, m.vel.y > 0.03 ? -0.004 : 0, 0);
            if (++m.age > m.life) mi.remove();
        }
        HITS.removeIf(h -> ++h.age > 14);
    }

    private static void resetAll() {
        SCENES.clear();
        MOTES.clear();
        HITS.clear();
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
        if (mc.level == null || (SCENES.isEmpty() && MOTES.isEmpty() && HITS.isEmpty())) return;

        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        Vec3 camera = cam.getPosition();
        float pt = mc.isPaused() ? 0.0f : event.getPartialTick();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        for (Scene s : SCENES.values()) renderScene(pose, cam, camera, s, pt);

        begin(pose, camera, TEX_RIPPLE, true);
        for (Hit h : HITS) {
            double k = (h.age + pt) / 14.0;
            orientedQuad(h.pos, h.normal, (float) (0.3 + 2.4 * (1.0 - Math.pow(1.0 - k, 2.0))), CR, CG, CB, (float) (1.0 - k));
        }
        end();
        begin(pose, camera, TEX_GLOW, true);
        for (Hit h : HITS) {
            float g = (float) Math.max(0.0, 1.0 - (h.age + pt) / 6.0);
            sprite(cam, h.pos, 0.6f + 1.4f * g, 0.7f, 1f, 0.95f, g);
        }
        end();

        begin(pose, camera, TEX_MOTE, true);
        boolean fp = mc.options.getCameraType().isFirstPerson();
        for (Mote m : MOTES) {
            double k = (m.age + pt) / m.life;
            float a = (float) (smooth(k / 0.2) * (1.0 - smooth((k - 0.6) / 0.4)));
            Vec3 at = m.prev.lerp(m.pos, pt);
            if (fp) a *= (float) smooth((at.distanceTo(camera) - 0.6) / 1.0);
            float tw = 0.7f + 0.3f * (float) Math.sin((m.age + pt) * 1.1 + m.size * 97);
            sprite(cam, at, m.size * tw, 0.6f, 1f, 0.95f, a);
        }
        end();

        HollowPurpleReferenceClient.mpAlphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static void renderScene(PoseStack pose, Camera cam, Vec3 camera, Scene s, float pt) {
        double t = s.age + pt;
        double te = s.ending ? s.endAge + pt : 0.0;
        double r = s.radius(t, te);
        if (r < 0.03) return;
        float a = s.alpha(te);
        float pulse = Mth.lerp(pt, s.pulseO, s.pulse);
        Vec3 c = s.center;
        double form = Mth.clamp(t / FORM_TICKS, 0.0, 1.0);

        // пол: полупрозрачная бирюзовая заливка со светлым краем
        begin(pose, camera, TEX_FLOOR, true);
        flatQuad(c.add(0, 0.03, 0), (float) (r * 2.0), (float) (t * 0.004), CR * 0.8f, CG * 0.9f, CB * 0.9f,
                a * (0.55f + 0.25f * pulse));
        end();

        // стена-вихрь: два слоя ленты пламени, закручиваются в разные стороны, верх уходит вбок
        double hScale = (0.35 + 0.65 * form) * (s.ending && !s.broken ? Math.max(0.2, r / RADIUS) : 1.0);
        begin(pose, camera, TEX_FLAME, true);
        wall(c, r, 1.55 * hScale, 3.0, t * 0.012, 0.45, t * 0.03, a * (0.85f + 0.5f * pulse), s.seed);
        wall(c, r * 0.985, 1.05 * hScale, 5.0, -t * 0.009 + 0.37, -0.3, t * 0.045, a * (0.55f + 0.4f * pulse), s.seed + 5);
        end();

        // раскрытие: у ног вспыхивает и закручивается кольцо, несколько спиральных лент
        if (t < FORM_TICKS + 8 && !s.ending) {
            float fa = (float) (1.0 - smooth((t - FORM_TICKS) / 8.0));
            begin(pose, camera, TEX_STREAK, true);
            for (int arm = 0; arm < 3; arm++) {
                Vec3 prev = null;
                for (int j = 0; j <= 20; j++) {
                    double u = j / 20.0;
                    double ang = arm * 2.094 + u * 2.6 + t * 0.35;
                    double rr = r * (0.25 + 0.75 * u);
                    Vec3 p = c.add(Math.cos(ang) * rr, 0.08 + 0.3 * u, Math.sin(ang) * rr);
                    if (prev != null) seg(prev, p, 0.09f * (float) (1.0 - u * 0.5), CR, CG, CB, fa * (float) Math.sin(Math.PI * u));
                    prev = p;
                }
            }
            end();
            begin(pose, camera, TEX_GLOW, true);
            flatQuad(c.add(0, 0.05, 0), (float) (1.0 + r * 1.2), 0f, CR, CG, CB, fa * 0.6f);
            end();
        }

        // лопнул: вспышка и кольцо осколков
        if (s.broken) {
            double k = Mth.clamp(te / BREAK_TICKS, 0.0, 1.0);
            begin(pose, camera, TEX_RIPPLE, true);
            flatQuad(c.add(0, 0.1, 0), (float) (RADIUS * 2.0 * (1.0 + 0.6 * k)), 0f, CR, CG, CB, (float) (1.0 - k));
            end();
        }
    }

    /**
     * Стена круга: лента из квадов по окружности, текстура «пламени» бежит вдоль неё,
     * верхний край смещён по касательной (lean) — языки закручиваются в вихрь.
     */
    private static void wall(Vec3 c, double r, double h, double repeats, double scroll, double lean, double wob, float alpha,
                             long seed) {
        if (alpha <= 0.01f || h <= 0.02) return;
        int n = 72;
        open();
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2.0 * i / n, a1 = Math.PI * 2.0 * (i + 1) / n;
            // ярче на отдельных дугах (как в референсе), медленно плывёт
            float k0 = (float) (0.55 + 0.45 * Math.sin(a0 * 2.0 + wob * 0.6 + (seed % 7)));
            float k1 = (float) (0.55 + 0.45 * Math.sin(a1 * 2.0 + wob * 0.6 + (seed % 7)));
            double h0 = h * (0.8 + 0.2 * Math.sin(a0 * 3.0 + wob));
            double h1 = h * (0.8 + 0.2 * Math.sin(a1 * 3.0 + wob));
            float u0 = (float) (i / (double) n * repeats + scroll), u1 = (float) ((i + 1) / (double) n * repeats + scroll);
            Vec3 b0 = c.add(Math.cos(a0) * r, 0.02, Math.sin(a0) * r);
            Vec3 b1 = c.add(Math.cos(a1) * r, 0.02, Math.sin(a1) * r);
            Vec3 t0 = c.add(Math.cos(a0 + lean / Math.max(1.0, r)) * r * 1.04, h0, Math.sin(a0 + lean / Math.max(1.0, r)) * r * 1.04);
            Vec3 t1 = c.add(Math.cos(a1 + lean / Math.max(1.0, r)) * r * 1.04, h1, Math.sin(a1 + lean / Math.max(1.0, r)) * r * 1.04);
            v(b0.x, b0.y, b0.z, u0, 1f, CR, CG, CB, alpha * k0);
            v(t0.x, t0.y, t0.z, u0, 0f, CR, CG, CB, alpha * k0);
            v(t1.x, t1.y, t1.z, u1, 0f, CR, CG, CB, alpha * k1);
            v(b1.x, b1.y, b1.z, u1, 1f, CR, CG, CB, alpha * k1);
        }
    }

    // ================================================================== геометрия

    private static Vec3 randDir(Random rnd) {
        double a = rnd.nextDouble() * Math.PI * 2.0, z = rnd.nextDouble() * 2.0 - 1.0, r = Math.sqrt(1.0 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    private static double smooth(double v) {
        v = Mth.clamp(v, 0.0, 1.0);
        return v * v * (3.0 - 2.0 * v);
    }

    // ================================================================== пакетная отрисовка

    private static BufferBuilder buf;
    private static Matrix4f mat;
    private static Vec3 cam0;
    private static ResourceLocation pendingTex;
    private static boolean pendingAdditive;
    private static PoseStack pendingPose;

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
        Vec3 side = axis.cross(cam0.subtract(from.add(to).scale(0.5)));
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

    private static void flatQuad(Vec3 c, float size, float rot, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || size <= 0f) return;
        double h = size * 0.5, cs = Math.cos(rot) * h, sn = Math.sin(rot) * h;
        open();
        v(c.x - cs + sn, c.y, c.z - sn - cs, 0f, 0f, r, g, b, alpha);
        v(c.x - cs - sn, c.y, c.z - sn + cs, 0f, 1f, r, g, b, alpha);
        v(c.x + cs - sn, c.y, c.z + sn + cs, 1f, 1f, r, g, b, alpha);
        v(c.x + cs + sn, c.y, c.z + sn - cs, 1f, 0f, r, g, b, alpha);
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
