package com.kira.jujutsuneon;

import com.mojang.blaze3d.platform.GlStateManager;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.event.TickEvent;
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
import java.util.Random;
import java.util.UUID;

import static com.kira.jujutsuneon.RedTechnique.*;

/**
 * Клиент Красного и Максимального Красного: анимация тела (Player Animator), объёмный маленький шар
 * (ядро, оболочки, ореол, молнии, волны жара), след и искры в полёте, взрыв, аура и ленты энергии
 * Максимального Красного, ударная волна, кадры негатива, луч-конус с красными обломками.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RedTechniqueClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_ENERGY = tex("red_energy");
    private static final ResourceLocation TEX_AURA = tex("red_aura");
    private static final ResourceLocation TEX_SHOCK = tex("red_shock");
    private static final ResourceLocation TEX_GLOW = tex("red_glow");
    private static final ResourceLocation TEX_HEAT = tex("red_heat");
    private static final ResourceLocation TEX_STREAK = tex("red_streak");
    private static final ResourceLocation TEX_GLINT = tex("lapse_glint");
    private static final ResourceLocation TEX_SMOKE = tex("lapse_smoke");

    private static final int ARM_TICKS_RED = 16;
    private static final int ARM_TICKS_MAX = 34;
    private static final int CAST_TAIL = 20;

    private RedTechniqueClient() {
    }

    // ================================================================== состояние

    private static final class Cast {
        final UUID casterId;
        final int entityId;
        final boolean max;
        final float yaw;
        final long seed;
        int ticks;

        Cast(UUID casterId, int entityId, boolean max, float yaw) {
            this.casterId = casterId;
            this.entityId = entityId;
            this.max = max;
            this.yaw = yaw;
            this.seed = casterId.getLeastSignificantBits() ^ (long) (yaw * 1000.0f) ^ System.nanoTime();
        }
    }

    private static final class Charge {
        final int entityId;
        int ticks;

        Charge(int entityId) {
            this.entityId = entityId;
        }
    }

    private static final class Shot {
        final int id;
        final int casterEntity;
        final Vec3 start;
        final Vec3 velocity;
        final Vec3 hand;
        final long seed;
        int ticks;
        Vec3 endPos;
        int endTicks;

        Shot(int id, int casterEntity, Vec3 start, Vec3 velocity, Vec3 hand) {
            this.id = id;
            this.casterEntity = casterEntity;
            this.start = start;
            this.velocity = velocity;
            this.hand = hand;
            this.seed = id * 7919L + 13L;
        }

        /** Точка полёта: с ладони плавно сводится на линию сервера за ~2,5 тика. */
        Vec3 at(double t) {
            if (endPos != null) return endPos;
            double max = RED_MAX_DISTANCE / velocity.length();
            Vec3 line = start.add(velocity.scale(Math.min(t, max)));
            if (hand == null) return line;
            double k = smooth(t / 2.5);
            return hand.lerp(line, k);
        }
    }

    private static final class Boom {
        final Vec3 pos;
        final long seed;
        int ticks;

        Boom(Vec3 pos, long seed) {
            this.pos = pos;
            this.seed = seed;
        }
    }

    private static final class Beam {
        final int casterEntity;
        final Vec3 origin;
        final Vec3 dir;
        final Vec3 right;
        final Vec3 up;
        final long seed;
        int ticks;

        Beam(int casterEntity, Vec3 origin, Vec3 dir) {
            this.casterEntity = casterEntity;
            this.origin = origin;
            this.dir = dir.normalize();
            Vec3 ref = Math.abs(this.dir.y) > 0.92 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 r = this.dir.cross(ref);
            this.right = r.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : r.normalize();
            this.up = this.right.cross(this.dir).normalize();
            this.seed = Double.doubleToLongBits(origin.x * 31.0 + origin.z) ^ System.nanoTime();
        }
    }

    /** Красный куб-обломок от луча или искра. */
    private static final class Bit {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        final float size;
        final int life;
        final boolean cube;
        final double drag;
        final double gravity;
        final Vector3f axis;
        final float spin;
        int age;

        Bit(Vec3 pos, Vec3 vel, float size, int life, boolean cube, double drag, double gravity, Random rnd) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.size = size;
            this.life = life;
            this.cube = cube;
            this.drag = drag;
            this.gravity = gravity;
            this.axis = new Vector3f((float) rnd.nextGaussian(), (float) rnd.nextGaussian(), (float) rnd.nextGaussian());
            if (this.axis.lengthSquared() < 1.0E-4f) this.axis.set(0, 1, 0);
            this.axis.normalize();
            this.spin = (float) (rnd.nextDouble() * 0.5 + 0.15) * (rnd.nextBoolean() ? 1 : -1);
        }
    }

    private static final Map<UUID, Cast> CASTS = new HashMap<>();
    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    private static final Map<Integer, Shot> SHOTS = new HashMap<>();
    private static final List<Boom> BOOMS = new ArrayList<>();
    private static final List<Beam> BEAMS = new ArrayList<>();
    private static final List<Bit> BITS = new ArrayList<>();
    private static final Random RND = new Random();

    // ================================================================== пакеты

    static void onCharge(UUID caster, int entityId, boolean on) {
        if (on) CHARGES.put(caster, new Charge(entityId));
        else CHARGES.remove(caster);
    }

    static void onCast(UUID caster, int entityId, boolean max, float yaw) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        CHARGES.remove(caster);
        Cast c = new Cast(caster, entityId, max, yaw);
        CASTS.put(caster, c);
        if (mc.level.getEntity(entityId) instanceof AbstractClientPlayer p) {
            String anim = max ? "max_red" : "red_cast";
            if (p == mc.player) MaximumPurpleAnimation.playWithFirstPersonArm(p, anim, max ? ARM_TICKS_MAX : ARM_TICKS_RED);
            else MaximumPurpleAnimation.play(p, anim);
        }
        if (max && mc.player != null && mc.player.getId() == entityId) {
            mc.player.setSprinting(false);
            mc.player.setDeltaMovement(new Vec3(0.0, Math.min(0.0, mc.player.getDeltaMovement().y), 0.0));
        }
    }

    static void onShot(int shotId, int casterEntity, Vec3 start, Vec3 velocity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 hand = null;
        for (Cast c : CASTS.values()) {
            if (c.entityId == casterEntity && !c.max) {
                hand = redHand(mc, c, R_FIRE, 1.0f);
                break;
            }
        }
        SHOTS.put(shotId, new Shot(shotId, casterEntity, start, velocity, hand));
    }

    /** Точки свежих взрывов Красного: ванильный звук взрыва там глушится (у Красного свой). */
    private static final List<double[]> MUTE_EXPLODE = new ArrayList<>();

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null || MUTE_EXPLODE.isEmpty()) return;
        if (!SoundEvents.GENERIC_EXPLODE.getLocation().equals(sound.getLocation())) return;
        long now = System.currentTimeMillis();
        MUTE_EXPLODE.removeIf(m -> now - (long) m[3] > 1500L);
        for (double[] m : MUTE_EXPLODE) {
            double dx = sound.getX() - m[0], dy = sound.getY() - m[1], dz = sound.getZ() - m[2];
            if (dx * dx + dy * dy + dz * dz < 36.0) {
                event.setSound(null);
                return;
            }
        }
    }

    static void onShotEnd(int shotId, Vec3 pos, boolean exploded) {
        if (exploded) MUTE_EXPLODE.add(new double[]{pos.x, pos.y, pos.z, System.currentTimeMillis()});
        Shot s = SHOTS.get(shotId);
        if (s != null && s.endPos == null) {
            s.endPos = pos;
            s.endTicks = 0;
        }
        if (exploded) {
            BOOMS.add(new Boom(pos, shotId * 31L + 7L));
            Random rnd = new Random(shotId * 977L);
            for (int i = 0; i < 70; i++) {
                Vec3 d = randDir(rnd);
                double sp = 0.35 + rnd.nextDouble() * 1.3;
                BITS.add(new Bit(pos, d.scale(sp).add(0.0, 0.15, 0.0), (float) (0.05 + rnd.nextDouble() * 0.10),
                        14 + rnd.nextInt(18), false, 0.88, 0.035, rnd));
            }
            for (int i = 0; i < 14; i++) {
                Vec3 d = randDir(rnd);
                BITS.add(new Bit(pos, d.scale(0.4 + rnd.nextDouble() * 0.8).add(0.0, 0.35, 0.0),
                        (float) (0.12 + rnd.nextDouble() * 0.22), 26 + rnd.nextInt(20), true, 0.94, 0.045, rnd));
            }
        }
    }

    static void onBeam(UUID caster, int casterEntity, Vec3 origin, Vec3 dir) {
        Beam b = new Beam(casterEntity, origin, dir);
        Minecraft mc = Minecraft.getInstance();
        // Луч идёт из ладони, а не из глаз.
        Cast c = CASTS.get(caster);
        Vec3 from = origin;
        if (c != null && c.max && mc.level != null) from = maxHand(mc, c, M_FIRE, 1.0f);
        Beam real = new Beam(casterEntity, from, dir);
        BEAMS.add(real);
        // Красные светящиеся куски — разлетаются вдоль луча и в стороны.
        Random rnd = new Random(b.seed);
        for (int i = 0; i < 46; i++) {
            double d = 2.0 + rnd.nextDouble() * 30.0;
            double rad = beamRadius(d) * (0.4 + rnd.nextDouble() * 0.8);
            double a = rnd.nextDouble() * Math.PI * 2.0;
            Vec3 off = real.right.scale(Math.cos(a) * rad).add(real.up.scale(Math.sin(a) * rad));
            Vec3 p = real.origin.add(real.dir.scale(d * 0.25)).add(off.scale(0.3));
            Vec3 v = real.dir.scale(0.9 + rnd.nextDouble() * 1.6).add(off.normalize().scale(0.25 + rnd.nextDouble() * 0.6))
                    .add(0.0, 0.2 + rnd.nextDouble() * 0.4, 0.0);
            BITS.add(new Bit(p, v, (float) (0.18 + rnd.nextDouble() * 0.55), 22 + rnd.nextInt(26), true, 0.93, 0.05, rnd));
        }
        for (int i = 0; i < 60; i++) {
            Vec3 p = real.origin.add(real.dir.scale(rnd.nextDouble() * 6.0));
            Vec3 v = real.dir.scale(1.2 + rnd.nextDouble() * 2.0).add(randDir(rnd).scale(0.4));
            BITS.add(new Bit(p, v, (float) (0.05 + rnd.nextDouble() * 0.09), 10 + rnd.nextInt(14), false, 0.86, 0.01, rnd));
        }
    }

    /** Локальный игрок в анимации Максимального Красного — стоит на месте. */
    public static boolean locksLocalPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        Cast c = CASTS.get(mc.player.getUUID());
        return c != null && c.max && c.ticks < M_END;
    }

    // ================================================================== тики

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!CASTS.isEmpty() || !SHOTS.isEmpty() || !BITS.isEmpty() || !BEAMS.isEmpty() || !BOOMS.isEmpty() || !CHARGES.isEmpty()) {
                resetAll();
            }
            return;
        }
        if (mc.isPaused()) return;

        // Максимальный Красный: стоит на месте и не падает (иначе сервер дёргал бы его назад).
        if (mc.player != null) {
            if (locksLocalPlayer()) {
                if (!mc.player.isNoGravity()) {
                    mc.player.setNoGravity(true);
                    gravityChanged = true;
                }
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.setSprinting(false);
            } else if (gravityChanged) {
                mc.player.setNoGravity(false);
                gravityChanged = false;
            }
        }

        Iterator<Cast> ci = CASTS.values().iterator();
        while (ci.hasNext()) {
            Cast c = ci.next();
            c.ticks++;
            if (c.ticks > (c.max ? M_END : R_END) + CAST_TAIL || mc.level.getEntity(c.entityId) == null) ci.remove();
        }
        Iterator<Charge> chi = CHARGES.values().iterator();
        while (chi.hasNext()) {
            Charge ch = chi.next();
            ch.ticks++;
            if (ch.ticks > 200 || mc.level.getEntity(ch.entityId) == null) chi.remove();
        }

        Iterator<Shot> si = SHOTS.values().iterator();
        while (si.hasNext()) {
            Shot s = si.next();
            s.ticks++;
            if (s.endPos != null) {
                s.endTicks++;
                if (s.endTicks > 8) si.remove();
                continue;
            }
            if (s.ticks > 80) {
                si.remove();
                continue;
            }
            // Искры, срывающиеся с шарика в полёте.
            Vec3 p = s.at(s.ticks);
            Vec3 v = s.velocity;
            for (int i = 0; i < 4; i++) {
                double back = RND.nextDouble();
                Vec3 q = p.subtract(v.scale(back)).add(randDir(RND).scale(0.12));
                Vec3 vel = randDir(RND).scale(0.05 + RND.nextDouble() * 0.10).add(v.scale(0.02));
                BITS.add(new Bit(q, vel, (float) (0.03 + RND.nextDouble() * 0.05), 6 + RND.nextInt(8), false, 0.86, -0.004, RND));
            }
        }

        BOOMS.removeIf(b -> ++b.ticks > 46);
        BEAMS.removeIf(b -> ++b.ticks > 26);

        Iterator<Bit> bi = BITS.iterator();
        while (bi.hasNext()) {
            Bit b = bi.next();
            b.age++;
            if (b.age > b.life) {
                bi.remove();
                continue;
            }
            b.prev = b.pos;
            b.pos = b.pos.add(b.vel);
            b.vel = b.vel.scale(b.drag).add(0.0, -b.gravity, 0.0);
        }
        if (BITS.size() > 1600) BITS.subList(0, BITS.size() - 1600).clear();
    }

    private static boolean gravityChanged;

    private static void resetAll() {
        Minecraft mc = Minecraft.getInstance();
        if (gravityChanged && mc.player != null) mc.player.setNoGravity(false);
        gravityChanged = false;
        CASTS.clear();
        CHARGES.clear();
        SHOTS.clear();
        BOOMS.clear();
        BEAMS.clear();
        BITS.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

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

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!locksLocalPlayer()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** Отдача Максимального Красного и взрыв рядом — тряска камеры. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double pt = event.getPartialTick();
        float shake = 0.0f;
        Cast c = CASTS.get(mc.player.getUUID());
        if (c != null && c.max) {
            double t = c.ticks + pt;
            if (t >= M_AURA && t < M_SHOCK) shake = Math.max(shake, 0.35f);
            if (t >= M_FIRE && t < M_FIRE + 9) shake = Math.max(shake, 2.6f * (float) (1.0 - (t - M_FIRE) / 9.0));
        }
        Vec3 cam = event.getCamera().getPosition();
        for (Boom b : BOOMS) {
            double t = b.ticks + pt;
            if (t > 12) continue;
            double d = cam.distanceTo(b.pos);
            float k = (float) Mth.clamp(1.0 - d / 40.0, 0.0, 1.0);
            shake = Math.max(shake, 2.2f * k * (float) (1.0 - t / 12.0));
        }
        if (shake <= 0.0f) return;
        double tt = mc.level != null ? mc.level.getGameTime() + pt : pt;
        event.setYaw(event.getYaw() + (float) Math.sin(tt * 4.7) * shake);
        event.setPitch(event.getPitch() + (float) Math.cos(tt * 5.9) * shake * 0.7f);
        event.setRoll(event.getRoll() + (float) Math.sin(tt * 7.3) * shake * 0.4f);
    }

    // ================================================================== положение руки по анимации

    private static Vec3[] basis(float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        Vec3 f = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
        Vec3 r = new Vec3(-f.z, 0.0, f.x);
        return new Vec3[]{f, r};
    }

    private static Vec3 feet(Entity e, float pt) {
        return new Vec3(Mth.lerp(pt, e.xOld, e.getX()), Mth.lerp(pt, e.yOld, e.getY()), Mth.lerp(pt, e.zOld, e.getZ()));
    }

    private static Vec3 point(Vec3 feet, Vec3[] b, double f, double r, double u) {
        return feet.add(b[0].scale(f)).add(b[1].scale(r)).add(0.0, u, 0.0);
    }

    /** Кусочно-линейная интерполяция ключей {t, f, r, u} со сглаживанием. */
    private static double[] keyed(double[][] keys, double t) {
        if (t <= keys[0][0]) return keys[0];
        for (int i = 0; i + 1 < keys.length; i++) {
            if (t <= keys[i + 1][0]) {
                double k = smooth((t - keys[i][0]) / (keys[i + 1][0] - keys[i][0]));
                double[] a = keys[i], b = keys[i + 1];
                return new double[]{t, Mth.lerp(k, a[1], b[1]), Mth.lerp(k, a[2], b[2]), Mth.lerp(k, a[3], b[3])};
            }
        }
        return keys[keys.length - 1];
    }

    // Ладонь правой руки в обычном Красном (вперёд, вправо, вверх от ног; модель 0,9375).
    private static final double[][] RED_HAND = {
            {0, 0.12, 0.36, 0.78},
            {2, 0.28, 0.52, 1.72},
            {4, 0.30, 0.18, 2.02},
            {8, 0.30, 0.16, 2.00},
            {10, 0.44, 0.02, 1.30},
            {11, 0.42, 0.06, 1.28},
            {12, 0.32, 0.46, 1.06},
            {13, 0.86, 0.34, 1.36},
            {18, 0.20, 0.36, 0.80},
    };

    private static Vec3 redHand(Minecraft mc, Cast c, double t, float pt) {
        Entity e = mc.level.getEntity(c.entityId);
        if (e == null) return Vec3.ZERO;
        float yaw = e instanceof LivingEntity le ? Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot) : c.yaw;
        double[] k = keyed(RED_HAND, t);
        return point(feet(e, pt), basis(yaw), k[1], k[2], k[3]);
    }

    // Ладонь правой руки в Максимальном Красном (вперёд, вправо, вверх от ног; лицом к цели, без разворота).
    // Посчитано по позам max_red.json (tools/gen_red_anim.py); на выстреле — кончики пальцев вытянутой руки.
    private static final double[][] MAX_HAND = {
            {0, 0.03, 0.33, 0.76},
            {1, 0.51, 0.31, 1.25},
            {3, 0.10, 0.28, 1.62},
            {5, 0.10, 0.30, 1.60},
            {7, -0.10, 0.20, 1.70},
            {9, -0.15, 0.12, 1.66},
            {10, -0.14, 0.12, 1.66},
            {12, 0.30, 0.08, 1.42},
            {14, 0.32, 0.08, 1.40},
            {16, 0.34, 0.26, 1.36},
            {18, 0.33, 0.26, 1.36},
            {20, 0.34, 0.26, 1.36},
            {22, 0.34, 0.26, 1.31},
            {25, 0.26, 0.33, 1.28},
            {26, 0.66, 0.20, 1.27},
            {28, 0.50, 0.22, 1.56},
            {30, 0.52, 0.22, 1.50},
            {33, 0.41, 0.30, 1.01},
            {36, 0.03, 0.33, 0.76},
    };

    private static Vec3 maxHand(Minecraft mc, Cast c, double t, float pt) {
        Entity e = mc.level.getEntity(c.entityId);
        if (e == null) return Vec3.ZERO;
        float yaw = e instanceof LivingEntity le ? Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot) : c.yaw;
        double[] k = keyed(MAX_HAND, t);
        return point(feet(e, pt), basis(yaw), k[1], k[2], k[3]);
    }

    // ================================================================== отрисовка мира

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (CASTS.isEmpty() && SHOTS.isEmpty() && BOOMS.isEmpty() && BEAMS.isEmpty() && BITS.isEmpty() && CHARGES.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        Vec3 camera = cam.getPosition();
        float pt = mc.isPaused() ? 0.0f : event.getPartialTick();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        for (Charge ch : CHARGES.values()) renderCharge(mc, pose, cam, camera, ch, pt);
        for (Cast c : CASTS.values()) {
            double t = c.ticks + pt;
            if (c.max) renderMaxCast(mc, pose, cam, camera, c, t, pt);
            else renderRedCast(mc, pose, cam, camera, c, t, pt);
        }
        for (Beam b : BEAMS) renderBeam(pose, cam, camera, b, b.ticks + pt);
        for (Shot s : SHOTS.values()) renderShot(pose, cam, camera, s, pt);
        for (Boom b : BOOMS) renderBoom(pose, cam, camera, b, b.ticks + pt);
        renderBits(pose, cam, camera, pt);

        HollowPurpleReferenceClient.mpAlphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Заряд в опущенной руке: тёплое свечение и искры у пальцев, растёт к 2 секундам. */
    private static void renderCharge(Minecraft mc, PoseStack pose, Camera cam, Vec3 camera, Charge ch, float pt) {
        Entity e = mc.level.getEntity(ch.entityId);
        if (!(e instanceof LivingEntity le)) return;
        double t = ch.ticks + pt;
        float k = (float) Mth.clamp(t / CHARGE_MAX_TICKS, 0.0, 1.0);
        float yaw = Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot);
        Vec3 hand = point(feet(e, pt), basis(yaw), 0.14, 0.38, 0.72);
        float a = (float) smooth(t / 4.0);
        billboard(pose, cam, camera, hand, 0.5f + 0.9f * k, 0f, TEX_GLOW, 1f, 0.18f, 0.12f, a * (0.35f + 0.45f * k), true);
        colorSphere(pose, camera, hand, 0.025f + 0.035f * k, 1f, 0.75f, 0.65f, a, 8, 12);
        crackles(pose, camera, hand, 0.06f + 0.10f * k, t, a * k, ch.entityId, 2 + (int) (k * 3), 1f, 0.25f, 0.2f);
    }

    /** Обычный Красный в руке: искра над головой → шарик в ладони у груди → бросок. */
    private static void renderRedCast(Minecraft mc, PoseStack pose, Camera cam, Vec3 camera, Cast c, double t, float pt) {
        Entity e = mc.level.getEntity(c.entityId);
        if (e == null) return;
        Vec3 ft = feet(e, pt);
        float yaw = e instanceof LivingEntity le ? Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot) : c.yaw;
        Vec3[] b = basis(yaw);
        Vec3 hand = redHand(mc, c, t, pt);

        // Тёплое свечение персонажа (как в референсе) и красный свет по земле.
        float aura = env(t, 0, R_END + 2, 2, 6);
        if (aura > 0.01f) {
            billboard(pose, cam, camera, ft.add(0.0, 1.05, 0.0), 2.6f, 0f, TEX_GLOW, 1f, 0.55f, 0.25f, aura * 0.32f, true);
            double k = win(t, 1, 14);
            Vec3 g = point(ft, b, 0.6 + 1.6 * k, 0.0, 0.04);
            flat(pose, camera, g, (float) (1.6 + 5.0 * k), 0f, TEX_GLOW, 1f, 0.12f, 0.08f, aura * 0.55f, true);
        }

        // Искра над головой (R_SPARK) опускается в ладонь к груди (R_CHEST) и растёт.
        if (t >= R_SPARK - 0.5 && t < R_FIRE) {
            Vec3 above = point(ft, b, 0.26, 0.14, 2.42);
            double k = win(t, R_CHEST - 1, R_CHEST + 1.5);
            Vec3 p = above.lerp(hand, smooth(k));
            float r = (float) (0.018 + 0.03 * win(t, R_SPARK, R_CHEST) + 0.11 * win(t, R_CHEST, R_FIRE));
            float a = (float) smooth((t - R_SPARK + 0.5) / 1.5);
            redOrb(pose, cam, camera, p, r, t, a, c.seed);
        }
        // Вспышка у руки в момент броска.
        float flash = env(t, R_FIRE - 0.5, R_FIRE + 3, 0.3, 2.5);
        if (flash > 0.01f) {
            Vec3 p = redHand(mc, c, R_FIRE, pt);
            billboard(pose, cam, camera, p, 1.4f, 0f, TEX_GLOW, 1f, 0.4f, 0.3f, flash, true);
            billboard(pose, cam, camera, p, 2.2f, (float) (t * 0.2), TEX_SHOCK, 1f, 0.25f, 0.18f, flash * 0.6f, true);
        }
    }

    /** Максимальный Красный: печать, огромная аура, ленты энергии, шар у плеча, ударная волна. */
    private static void renderMaxCast(Minecraft mc, PoseStack pose, Camera cam, Vec3 camera, Cast c, double t, float pt) {
        Entity e = mc.level.getEntity(c.entityId);
        if (e == null) return;
        Vec3 ft = feet(e, pt);
        Vec3[] b = basis(c.yaw);
        Vec3 chest = ft.add(0.0, 1.2, 0.0);
        Vec3 hand = maxHand(mc, c, t, pt);

        // Свечение пальцев у лба (печать).
        float sign = env(t, 1, M_CONDENSE, 2, 3);
        if (sign > 0.01f) {
            billboard(pose, cam, camera, hand, 0.8f, 0f, TEX_GLOW, 1f, 0.35f, 0.25f, sign * 0.8f, true);
        }

        // Огромное красное кольцо-аура: вспыхивает на M_AURA, пульсирует, стягивается к шару.
        float aura = env(t, M_AURA - 0.5, M_ORB + 3, 0.8, 6);
        if (aura > 0.01f) {
            double grow = 1.0 - Math.pow(1.0 - win(t, M_AURA, M_AURA + 3), 3.0);
            double shrink = win(t, M_CONDENSE, M_ORB + 3);
            float size = (float) ((9.5 * grow) * (1.0 - 0.75 * smooth(shrink)) + 0.6 * Math.sin(t * 1.3));
            billboard(pose, cam, camera, chest, size, (float) (t * 0.06), TEX_AURA, 1f, 0.16f, 0.12f, aura * 0.95f, true);
            billboard(pose, cam, camera, chest, size * 0.8f, (float) (-t * 0.09 + 1.0), TEX_AURA, 1f, 0.35f, 0.28f, aura * 0.55f, true);
            billboard(pose, cam, camera, chest, size * 1.2f, 0f, TEX_GLOW, 1f, 0.12f, 0.08f, aura * 0.45f, true);
        }
        // Красный свет по земле.
        float ground = env(t, M_AURA, M_END, 2, 8);
        if (ground > 0.01f) {
            flat(pose, camera, ft.add(0.0, 0.04, 0.0), (float) (6.0 + 3.0 * win(t, M_AURA, M_FIRE)), 0f, TEX_GLOW, 1f, 0.12f, 0.08f, ground * 0.75f, true);
        }

        // Белые закрученные ленты энергии сходятся в шар.
        float rib = env(t, M_AURA, M_ORB + 1, 0.6, 3);
        if (rib > 0.01f) {
            double conv = smooth(win(t, M_AURA + 4, M_ORB));
            Vec3 target = maxHand(mc, c, Math.max(t, M_CONDENSE), pt);
            Random rnd = new Random(c.seed * 13L);
            for (int i = 0; i < 9; i++) {
                double a0 = rnd.nextDouble() * Math.PI * 2.0;
                double tilt = (rnd.nextDouble() - 0.5) * 1.6;
                double speed = (0.32 + rnd.nextDouble() * 0.25) * (rnd.nextBoolean() ? 1 : -1);
                double r0 = 2.2 + rnd.nextDouble() * 1.8;
                double r = r0 * (1.0 - conv) + 0.25;
                Vec3 center = chest.lerp(target, conv);
                Vec3 prev = null;
                for (int s = 0; s <= 16; s++) {
                    double u = s / 16.0;
                    double a = a0 + t * speed + u * 2.6;
                    double rr = r * (0.55 + 0.45 * Math.sin(u * Math.PI));
                    Vec3 p = center.add(b[1].scale(Math.cos(a) * rr)).add(b[0].scale(Math.sin(a) * rr * Math.cos(tilt)))
                            .add(0.0, Math.sin(a) * rr * Math.sin(tilt) + (u - 0.5) * r * 0.6, 0.0);
                    if (prev != null) {
                        float w = (float) (0.06 + 0.08 * Math.sin(u * Math.PI)) * (float) (1.0 - conv * 0.6);
                        float aa = rib * (float) Math.sin(u * Math.PI);
                        streak(pose, camera, prev, p, w * 2.2f, TEX_STREAK, 1f, 0.35f, 0.32f, aa * 0.55f, true);
                        streak(pose, camera, prev, p, w, TEX_STREAK, 1f, 0.95f, 0.95f, aa, true);
                    }
                    prev = p;
                }
            }
        }

        // Шар у ладони: рождается из лент, растёт, пульсирует.
        if (t >= M_CONDENSE - 1 && t < M_FIRE + 0.5) {
            float r = (float) (0.05 + 0.27 * smooth(win(t, M_CONDENSE - 1, M_ORB + 2)) + 0.02 * Math.sin(t * 2.1));
            float a = (float) smooth((t - M_CONDENSE + 1) / 2.0);
            redOrb(pose, cam, camera, hand, r, t, a, c.seed);
        }

        // Ударная волна перед выстрелом.
        float shock = env(t, M_SHOCK, M_SHOCK + 6, 0.3, 4);
        if (shock > 0.01f) {
            double k = 1.0 - Math.pow(1.0 - win(t, M_SHOCK, M_SHOCK + 6), 2.0);
            billboard(pose, cam, camera, chest, (float) (1.0 + 13.0 * k), 0f, TEX_SHOCK, 1f, 0.35f, 0.3f, shock, true);
            billboard(pose, cam, camera, chest, (float) (2.0 + 16.0 * k), 0f, TEX_GLOW, 1f, 0.6f, 0.55f, shock * 0.5f, true);
            flat(pose, camera, ft.add(0.0, 0.06, 0.0), (float) (1.0 + 14.0 * k), 0f, TEX_SHOCK, 1f, 0.3f, 0.25f, shock * 0.8f, true);
        }
        // Пыль под ногами от отдачи.
        float dust = env(t, M_FIRE, M_END + 10, 0.5, 8);
        if (dust > 0.01f) {
            double k = win(t, M_FIRE, M_FIRE + 16);
            Random rnd = new Random(c.seed * 977L + 3);
            for (int i = 0; i < 8; i++) {
                double a = i / 8.0 * Math.PI * 2.0 + rnd.nextDouble() * 0.5;
                double r = 0.6 + 2.2 * k * (0.7 + rnd.nextDouble() * 0.6);
                Vec3 p = ft.add(Math.cos(a) * r, 0.3 + 0.4 * k, Math.sin(a) * r);
                billboard(pose, cam, camera, p, (float) (1.0 + 1.4 * k), (float) a, TEX_SMOKE, 1f, 0.9f, 0.9f, dust * 0.45f, false);
            }
        }
    }

    /** Луч Максимального Красного: огромный конус, горячее ядро, фронт уходит вперёд. */
    private static void renderBeam(PoseStack pose, Camera cam, Vec3 camera, Beam b, double t) {
        double front = Math.min(BEAM_LENGTH, (t + 0.6) * BEAM_SPEED);
        float alpha = (float) (1.0 - smooth(win(t, 7, 18)));
        if (alpha <= 0.01f) return;
        // Вспышка у руки.
        float muzzle = env(t, 0, 8, 0.2, 6);
        if (muzzle > 0.01f) {
            billboard(pose, cam, camera, b.origin, 3.6f, 0f, TEX_GLOW, 1f, 0.55f, 0.45f, muzzle, true);
            billboard(pose, cam, camera, b.origin, 6.0f, (float) (t * 0.3), TEX_SHOCK, 1f, 0.2f, 0.15f, muzzle * 0.7f, true);
        }
        double scroll = t * 0.25;
        beamCone(pose, camera, b, 0.0, front, 1.0, 1f, 0.05f, 0.03f, alpha * 0.85f, scroll, true);
        beamCone(pose, camera, b, 0.0, front, 0.55, 1f, 0.32f, 0.22f, alpha * 0.6f, -scroll * 1.6, true);
        beamCone(pose, camera, b, 0.0, front, 0.18, 1f, 0.92f, 0.88f, alpha, scroll * 2.0, false);
        // Ударные кольца вдоль луча.
        for (int i = 0; i < 6; i++) {
            double d = 3.0 + i * 7.5;
            if (d > front) break;
            Vec3 p = b.origin.add(b.dir.scale(d));
            float ring = alpha * (float) (1.0 - win(t, 1 + i * 0.6, 12));
            ringAround(pose, camera, b, p, (float) (beamRadius(d) * (1.05 + 0.25 * win(t, 0, 10))), 1f, 0.3f, 0.25f, ring * 0.6f);
        }
        // Светящийся фронт.
        if (front < BEAM_LENGTH) {
            Vec3 tip = b.origin.add(b.dir.scale(front));
            billboard(pose, cam, camera, tip, (float) (beamRadius(front) * 2.6), 0f, TEX_GLOW, 1f, 0.4f, 0.3f, alpha, true);
        }
    }

    /** Поверхность конуса луча: кольца по длине, текстура энергии бежит вдоль. */
    private static void beamCone(PoseStack pose, Vec3 camera, Beam b, double d0, double d1, double scale,
                                 float r, float g, float bl, float alpha, double scroll, boolean textured) {
        if (alpha <= 0.003f || d1 - d0 < 0.05) return;
        int segs = 24;
        int sides = 28;
        if (textured) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, TEX_ENERGY);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, textured ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segs; i++) {
            double u0 = i / (double) segs, u1 = (i + 1) / (double) segs;
            double da = Mth.lerp(u0, d0, d1), db = Mth.lerp(u1, d0, d1);
            double ra = beamRadius(Math.max(1.0, da)) * scale * (da < 1.0 ? da : 1.0);
            double rb = beamRadius(Math.max(1.0, db)) * scale * (db < 1.0 ? db : 1.0);
            // К фронту прозрачнее.
            float fa = alpha * (float) Math.min(1.0, (d1 - da) / 4.0 + 0.15);
            float fb = alpha * (float) Math.min(1.0, (d1 - db) / 4.0 + 0.15);
            Vec3 ca = b.origin.add(b.dir.scale(da)).subtract(camera);
            Vec3 cb = b.origin.add(b.dir.scale(db)).subtract(camera);
            for (int j = 0; j < sides; j++) {
                double a0 = Math.PI * 2 * j / sides, a1 = Math.PI * 2 * (j + 1) / sides;
                Vec3 n0 = b.right.scale(Math.cos(a0)).add(b.up.scale(Math.sin(a0)));
                Vec3 n1 = b.right.scale(Math.cos(a1)).add(b.up.scale(Math.sin(a1)));
                Vec3 p00 = ca.add(n0.scale(ra)), p01 = ca.add(n1.scale(ra));
                Vec3 p11 = cb.add(n1.scale(rb)), p10 = cb.add(n0.scale(rb));
                // Край конуса (по отношению к взгляду) ярче — объём.
                float e0 = rim(n0, b.origin.add(b.dir.scale(da)), camera);
                float e1 = rim(n1, b.origin.add(b.dir.scale(da)), camera);
                float su0 = (float) (j / (double) sides * 3.0), su1 = (float) ((j + 1) / (double) sides * 3.0);
                float sv0 = (float) (da * 0.08 - scroll), sv1 = (float) (db * 0.08 - scroll);
                if (textured) {
                    buf.vertex(m, (float) p00.x, (float) p00.y, (float) p00.z).uv(su0, sv0).color(r, g, bl, fa * e0).endVertex();
                    buf.vertex(m, (float) p01.x, (float) p01.y, (float) p01.z).uv(su1, sv0).color(r, g, bl, fa * e1).endVertex();
                    buf.vertex(m, (float) p11.x, (float) p11.y, (float) p11.z).uv(su1, sv1).color(r, g, bl, fb * e1).endVertex();
                    buf.vertex(m, (float) p10.x, (float) p10.y, (float) p10.z).uv(su0, sv1).color(r, g, bl, fb * e0).endVertex();
                } else {
                    buf.vertex(m, (float) p00.x, (float) p00.y, (float) p00.z).color(r, g, bl, fa * (1.1f - e0) * 0.8f).endVertex();
                    buf.vertex(m, (float) p01.x, (float) p01.y, (float) p01.z).color(r, g, bl, fa * (1.1f - e1) * 0.8f).endVertex();
                    buf.vertex(m, (float) p11.x, (float) p11.y, (float) p11.z).color(r, g, bl, fb * (1.1f - e1) * 0.8f).endVertex();
                    buf.vertex(m, (float) p10.x, (float) p10.y, (float) p10.z).color(r, g, bl, fb * (1.1f - e0) * 0.8f).endVertex();
                }
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static float rim(Vec3 normal, Vec3 at, Vec3 camera) {
        Vec3 v = camera.subtract(at);
        double l = v.length();
        if (l < 1.0E-4) return 1.0f;
        double facing = Math.abs(normal.dot(v.scale(1.0 / l)));
        return (float) (0.35 + 0.65 * Math.pow(1.0 - facing, 1.5));
    }

    private static void ringAround(PoseStack pose, Vec3 camera, Beam b, Vec3 c, float radius, float r, float g, float bl, float alpha) {
        if (alpha <= 0.003f) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int n = 40;
        double w = Math.max(0.15, radius * 0.08);
        Vec3 o = c.subtract(camera);
        for (int j = 0; j < n; j++) {
            double a0 = Math.PI * 2 * j / n, a1 = Math.PI * 2 * (j + 1) / n;
            Vec3 n0 = b.right.scale(Math.cos(a0)).add(b.up.scale(Math.sin(a0)));
            Vec3 n1 = b.right.scale(Math.cos(a1)).add(b.up.scale(Math.sin(a1)));
            Vec3 i0 = o.add(n0.scale(radius - w)), i1 = o.add(n1.scale(radius - w));
            Vec3 o0 = o.add(n0.scale(radius + w)), o1 = o.add(n1.scale(radius + w));
            buf.vertex(m, (float) i0.x, (float) i0.y, (float) i0.z).color(r, g, bl, 0f).endVertex();
            buf.vertex(m, (float) i1.x, (float) i1.y, (float) i1.z).color(r, g, bl, 0f).endVertex();
            Vec3 m1 = o.add(n1.scale(radius)), m0 = o.add(n0.scale(radius));
            buf.vertex(m, (float) m1.x, (float) m1.y, (float) m1.z).color(r, g, bl, alpha).endVertex();
            buf.vertex(m, (float) m0.x, (float) m0.y, (float) m0.z).color(r, g, bl, alpha).endVertex();
            buf.vertex(m, (float) m0.x, (float) m0.y, (float) m0.z).color(r, g, bl, alpha).endVertex();
            buf.vertex(m, (float) m1.x, (float) m1.y, (float) m1.z).color(r, g, bl, alpha).endVertex();
            buf.vertex(m, (float) o1.x, (float) o1.y, (float) o1.z).color(r, g, bl, 0f).endVertex();
            buf.vertex(m, (float) o0.x, (float) o0.y, (float) o0.z).color(r, g, bl, 0f).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());
    }

    /** Летящий маленький Красный: шар, огненный след, волны жара. */
    private static void renderShot(PoseStack pose, Camera cam, Vec3 camera, Shot s, float pt) {
        double t = s.ticks + pt;
        float alpha = 1.0f;
        float scale = 1.0f;
        if (s.endPos != null) {
            double k = (s.endTicks + pt) / 6.0;
            alpha = (float) Math.max(0.0, 1.0 - k);
            scale = (float) Math.max(0.0, 1.0 - k * 0.7);
            if (alpha <= 0.01f) return;
        }
        Vec3 p = s.at(t);
        Vec3 dir = s.velocity.normalize();
        float r = RED_ORB_RADIUS * scale;

        // След: светящаяся лента позади шара (длиной ~2 тика полёта), сужается к хвосту.
        if (s.endPos == null) {
            double len = Math.min(7.5, t * s.velocity.length());
            Vec3 prev = p;
            int n = 10;
            for (int i = 1; i <= n; i++) {
                double u = i / (double) n;
                Vec3 q = p.subtract(dir.scale(len * u));
                float w = (float) (r * 1.6 * (1.0 - u) + 0.01);
                float a = (float) (1.0 - u);
                streak(pose, camera, prev, q, w * 2.4f, TEX_STREAK, 1f, 0.12f, 0.08f, a * 0.55f, true);
                streak(pose, camera, prev, q, w, TEX_STREAK, 1f, 0.75f, 0.65f, a * 0.9f, true);
                prev = q;
            }
            // Волны жара — воздух дрожит вокруг шара.
            billboard(pose, cam, camera, p, r * 14.0f, (float) (t * 0.4), TEX_HEAT, 1f, 0.55f, 0.45f, 0.18f, true);
        }
        redOrb(pose, cam, camera, p, r, t, alpha, s.seed);
    }

    /** Взрыв Красного: вспышка, красная сфера, волна по земле, дым. */
    private static void renderBoom(PoseStack pose, Camera cam, Vec3 camera, Boom b, double t) {
        double k = 1.0 - Math.pow(1.0 - win(t, 0, 10), 3.0);
        float core = env(t, 0, 10, 0.2, 7);
        if (core > 0.01f) {
            colorSphere(pose, camera, b.pos, (float) (1.0 + 4.5 * k), 1f, 0.85f, 0.8f, core, 14, 20);
            billboard(pose, cam, camera, b.pos, (float) (6.0 + 10.0 * k), 0f, TEX_GLOW, 1f, 0.55f, 0.45f, core, true);
        }
        float shell = env(t, 0, 22, 0.3, 12);
        if (shell > 0.01f) {
            sphere(pose, camera, b.pos, (float) (1.5 + 9.0 * k), TEX_ENERGY, t * 0.05, t * 0.03, 1f, 0.12f, 0.08f, shell * 0.75f, 2.2f, 18, 28);
            sphere(pose, camera, b.pos, (float) (1.2 + 7.0 * k), TEX_ENERGY, -t * 0.07, -t * 0.02, 1f, 0.4f, 0.3f, shell * 0.45f, 1.4f, 16, 24);
        }
        float wave = env(t, 0, 16, 0.2, 10);
        if (wave > 0.01f) {
            double kw = 1.0 - Math.pow(1.0 - win(t, 0, 16), 2.0);
            billboard(pose, cam, camera, b.pos, (float) (2.0 + 26.0 * kw), 0f, TEX_SHOCK, 1f, 0.3f, 0.25f, wave, true);
            flat(pose, camera, b.pos.add(0.0, -0.4, 0.0), (float) (2.0 + 30.0 * kw), 0f, TEX_SHOCK, 1f, 0.3f, 0.25f, wave * 0.8f, true);
        }
        float smoke = env(t, 2, 46, 3, 20);
        if (smoke > 0.01f) {
            Random rnd = new Random(b.seed);
            double ks = win(t, 0, 40);
            for (int i = 0; i < 16; i++) {
                Vec3 d = randDir(rnd);
                Vec3 q = b.pos.add(d.scale(2.0 + 6.0 * ks * (0.6 + rnd.nextDouble() * 0.6))).add(0.0, ks * 2.0, 0.0);
                billboard(pose, cam, camera, q, (float) (3.0 + 3.0 * ks), (float) (i + t * 0.01), TEX_SMOKE, 0.35f, 0.25f, 0.25f, smoke * 0.6f, false);
            }
        }
    }

    /** Искры и красные кубы-обломки. */
    private static void renderBits(PoseStack pose, Camera cam, Vec3 camera, float pt) {
        if (BITS.isEmpty()) return;
        for (Bit b : BITS) {
            double age = b.age + pt;
            float a = (float) (1.0 - smooth((age - b.life * 0.55) / (b.life * 0.45)));
            if (a <= 0.01f) continue;
            Vec3 p = b.prev.lerp(b.pos, pt);
            if (b.cube) {
                cube(pose, camera, p, b.size, b.axis, (float) (age * b.spin), a);
                billboard(pose, cam, camera, p, b.size * 3.2f, 0f, TEX_GLOW, 1f, 0.1f, 0.06f, a * 0.55f, true);
            } else {
                Vec3 tail = p.subtract(b.vel.scale(1.6));
                streak(pose, camera, tail, p, b.size, TEX_GLINT, 1f, 0.45f, 0.3f, a, true);
            }
        }
    }

    /** Светящийся красный куб (кусок, вырванный лучом). */
    private static void cube(PoseStack pose, Vec3 camera, Vec3 c, float size, Vector3f axis, float angle, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        HollowPurpleReferenceClient.mpAdditiveBlend();
        Quaternionf q = new Quaternionf().rotateAxis(angle, axis.x(), axis.y(), axis.z());
        Matrix4f m = pose.last().pose();
        float h = size * 0.5f;
        float ox = (float) (c.x - camera.x), oy = (float) (c.y - camera.y), oz = (float) (c.z - camera.z);
        float[][] corners = new float[8][3];
        for (int i = 0; i < 8; i++) {
            Vector3f v = new Vector3f((i & 1) == 0 ? -h : h, (i & 2) == 0 ? -h : h, (i & 4) == 0 ? -h : h);
            q.transform(v);
            corners[i][0] = v.x() + ox;
            corners[i][1] = v.y() + oy;
            corners[i][2] = v.z() + oz;
        }
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < faces.length; f++) {
            float shade = 0.65f + 0.35f * (f % 3) / 2.0f;
            for (int idx : faces[f]) {
                buf.vertex(m, corners[idx][0], corners[idx][1], corners[idx][2]).color(1f, 0.06f * shade, 0.04f, alpha * 0.9f * shade).endVertex();
            }
        }
        BufferUploader.drawWithShader(buf.end());
    }

    // ================================================================== модель Красного

    /**
     * Маленький объёмный Красный: белое горячее ядро, красная сфера, две закрученные навстречу
     * оболочки энергии, мягкий ореол и бегущие по поверхности молнии.
     */
    private static void redOrb(PoseStack pose, Camera cam, Vec3 camera, Vec3 c, float r, double t, float alpha, long seed) {
        if (alpha <= 0.01f || r <= 0.002f) return;
        billboard(pose, cam, camera, c, r * 9.0f, 0f, TEX_GLOW, 1f, 0.10f, 0.06f, alpha * 0.75f, true);
        billboard(pose, cam, camera, c, r * 4.2f, 0f, TEX_GLOW, 1f, 0.45f, 0.35f, alpha * 0.6f, true);
        sphere(pose, camera, c, r * 1.35f, TEX_ENERGY, t * 0.05, t * 0.02, 1f, 0.10f, 0.06f, alpha * 0.7f, 2.0f, 12, 18);
        sphere(pose, camera, c, r * 1.12f, TEX_ENERGY, -t * 0.08, -t * 0.03 + 0.5, 1f, 0.35f, 0.25f, alpha * 0.65f, 1.2f, 10, 16);
        colorSphere(pose, camera, c, r, 1f, 0.08f, 0.05f, alpha, 10, 16);
        colorSphere(pose, camera, c, r * 0.55f, 1f, 0.92f, 0.88f, alpha, 8, 12);
        crackles(pose, camera, c, r * 1.3f, t, alpha, seed, 3, 1f, 0.55f, 0.45f);
    }

    // ================================================================== интерфейс: кадры негатива

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        float pt = event.getPartialTick();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();

        for (Cast c : CASTS.values()) {
            if (!c.max) continue;
            Entity e = mc.level.getEntity(c.entityId);
            if (e == null || e.position().distanceTo(eye) > 48.0) continue;
            double t = c.ticks + pt;
            // Ударная волна — розовый свет.
            float haze = env(t, M_SHOCK - 0.5, M_FLASH + 0.2, 0.3, 0.4) * 0.55f;
            if (haze > 0.01f) fill(event, w, h, haze, 0xFFC8C8);
            // Три кадра негатива перед выстрелом: красно-чёрный, чёрно-белый, бело-чёрный.
            int frame = (int) Math.floor(t) - M_FLASH;
            if (frame >= 0 && frame < 3) negative(event, w, h, frame);
            // Выстрел — красная вспышка.
            float shot = env(t, M_FIRE, M_FIRE + 5, 0.1, 4) * 0.45f;
            if (shot > 0.01f) fill(event, w, h, shot, 0xFF2010);
        }
        for (Boom b : BOOMS) {
            double t = b.ticks + pt;
            double d = eye.distanceTo(b.pos);
            float near = (float) Mth.clamp(1.0 - d / 50.0, 0.0, 1.0);
            float f = env(t, 0, 6, 0.1, 5) * 0.55f * near;
            if (f > 0.01f) fill(event, w, h, f, 0xFFD8C8);
        }
    }

    /** Инверсия экрана: 0 — красно-чёрный негатив, 1 — негатив, 2 — высветленный негатив. */
    private static void negative(RenderGuiEvent.Post event, int w, int h, int frame) {
        var g = event.getGuiGraphics();
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        // Инверсия: результат = 1 - цвет кадра.
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ZERO);
        quad(m, w, h, 1f, 1f, 1f, 1f);
        if (frame == 0) {
            // Умножение на красный — чёрно-красный кадр.
            RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
            quad(m, w, h, 1f, 0.06f, 0.04f, 1f);
        } else if (frame == 2) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
            quad(m, w, h, 0.25f, 0.25f, 0.25f, 1f);
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private static void quad(Matrix4f m, int w, int h, float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(m, 0, 0, 0).color(r, g, b, a).endVertex();
        buf.vertex(m, 0, h, 0).color(r, g, b, a).endVertex();
        buf.vertex(m, w, h, 0).color(r, g, b, a).endVertex();
        buf.vertex(m, w, 0, 0).color(r, g, b, a).endVertex();
        BufferUploader.drawWithShader(buf.end());
    }

    private static void fill(RenderGuiEvent.Post event, int w, int h, float alpha, int rgb) {
        int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
        if (a <= 0) return;
        event.getGuiGraphics().fill(0, 0, w, h, (a << 24) | (rgb & 0x00FFFFFF));
    }

    // ================================================================== примитивы

    private static double smooth(double v) {
        v = Mth.clamp(v, 0.0, 1.0);
        return v * v * (3.0 - 2.0 * v);
    }

    private static double win(double t, double a, double b) {
        return Mth.clamp((t - a) / (b - a), 0.0, 1.0);
    }

    /** Плавно появиться за fin тиков после a и исчезнуть за fout тиков до b. */
    private static float env(double t, double a, double b, double fin, double fout) {
        if (t < a || t > b) return 0.0f;
        return (float) (smooth((t - a) / fin) * (1.0 - smooth((t - (b - fout)) / fout)));
    }

    private static Vec3 randDir(Random rnd) {
        double a = rnd.nextDouble() * Math.PI * 2.0, z = rnd.nextDouble() * 2.0 - 1.0, r = Math.sqrt(1.0 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    private static void crackles(PoseStack pose, Vec3 camera, Vec3 c, float r, double t, float alpha, long seed, int count,
                                 float cr, float cg, float cb) {
        if (alpha <= 0.01f) return;
        Random rnd = new Random(seed * 7919L + (long) (t * 0.5));
        for (int k = 0; k < count; k++) {
            Vec3 a = randDir(rnd);
            Vec3 b = randDir(rnd);
            Vec3 prev = null;
            for (int i = 0; i <= 5; i++) {
                double u = i / 5.0;
                Vec3 dir = a.scale(1.0 - u).add(b.scale(u));
                if (dir.lengthSqr() < 1.0E-6) continue;
                double jitter = 1.0 + (rnd.nextDouble() - 0.5) * 0.3;
                Vec3 p = c.add(dir.normalize().scale(r * jitter));
                if (prev != null) streak(pose, camera, prev, p, Math.max(0.006f, r * 0.08f), TEX_GLINT, cr, cg, cb, alpha * 0.9f, true);
                prev = p;
            }
        }
    }

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

    private static void quadVertex(BufferBuilder b, Matrix4f m, double x, double y, double z, float u, float v,
                                   float r, float g, float bl, float a) {
        b.vertex(m, (float) x, (float) y, (float) z).uv(u, v).color(r, g, bl, a).endVertex();
    }

    private static void blend(boolean additive) {
        if (additive) HollowPurpleReferenceClient.mpAdditiveBlend();
        else HollowPurpleReferenceClient.mpAlphaBlend();
    }

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
}
