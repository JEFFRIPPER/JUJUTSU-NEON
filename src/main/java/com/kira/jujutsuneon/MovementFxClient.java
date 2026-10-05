package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

import static com.kira.jujutsuneon.MovementFx.*;

/**
 * Клиент движения: анимации тела дэшей, взлёта и полёта (свой слой Player Animator — весь
 * корпус наклоняется целиком, руки и ноги остаются на месте, модель больше не «ломается»)
 * и эффекты по референсам:
 *  - дэш вперёд/назад: кольцо удара по земле, рывок с наклоном, белые полосы ветра, шлейф пыли
 *    из блоков под ногами, торможение с выбросом пыли;
 *  - дэш вбок: завал корпуса в сторону рывка, полосы-силуэты на старте, длинный низкий дым;
 *  - взлёт: полуприсед с руками у тела, прыжок, пыль, закрученные белые серпы ветра и искры;
 *  - полёт: спокойное зависание, наклон в движении, «супермен» в ускорении с вихрем и полосами;
 *  - сверхбег: пыль из-под ног, полосы ветра, шлейф, шаги.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class MovementFxClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_ARC = tex("move_arc");
    private static final ResourceLocation TEX_RING = tex("move_ring");
    private static final ResourceLocation TEX_STAR = tex("move_star");
    private static final ResourceLocation TEX_STREAK = tex("move_streak");
    private static final ResourceLocation TEX_PUFF = tex("move_puff");

    private static final int LAYER_PRIORITY = 1000;
    private static final int CROUCH_FULL_TICKS = 15;

    private MovementFxClient() {
    }

    // ================================================================== состояние игроков

    private static final class PState {
        final int entityId;
        int dashKind = -1;
        int dashAge;
        Vec3 dashDir = Vec3.ZERO;
        Vec3 dashPrev = Vec3.ZERO;

        int flight = FLIGHT_OFF;
        float flightW, flightWo;
        float boostW, boostWo;
        float landW, landWo;

        boolean crouching;
        int crouchAge;
        float crouchW, crouchWo;
        int leapAge = -1;

        Vec3 lastPos;
        Vec3 vel = Vec3.ZERO;
        Vec3 velO = Vec3.ZERO;
        int runTicks;
        int stepTimer;
        int idle;
        int age;

        PState(int entityId) {
            this.entityId = entityId;
        }

        boolean animating() {
            return dashKind >= 0 || flightW > 0.001f || flightWo > 0.001f || crouchW > 0.001f || crouchWo > 0.001f
                    || (leapAge >= 0 && leapAge < 24);
        }
    }

    private static final Map<Integer, PState> STATES = new HashMap<>();
    private static final Map<AbstractClientPlayer, MoveAnim> ANIMS = new WeakHashMap<>();
    private static final Random RND = new Random();

    private static PState state(int id) {
        return STATES.computeIfAbsent(id, PState::new);
    }

    // ================================================================== события (свой игрок — сразу, чужие — пакетом)

    static void localDash(JujutsuNeonMovementPatch.DashKind kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int k = switch (kind) {
            case BACK -> DASH_BACK;
            case LEFT -> DASH_LEFT;
            case RIGHT -> DASH_RIGHT;
            default -> DASH_FRONT;
        };
        startDash(mc.player, k);
    }

    static void localFlight(int mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) setFlight(mc.player, mode);
    }

    static void localCrouch(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        setCrouch(mc.player, on);
        MovementFx.requestTakeoff(on ? TAKEOFF_CROUCH : TAKEOFF_CANCEL);
    }

    static void localLeap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        leap(mc.player);
        MovementFx.requestTakeoff(TAKEOFF_LEAP);
    }

    static void onRemote(int entityId, int type, int value) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (!(e instanceof Player p) || p == mc.player) return;
        switch (type) {
            case EV_DASH -> startDash(p, value);
            case EV_FLIGHT -> setFlight(p, value);
            case EV_TAKEOFF -> {
                if (value == TAKEOFF_LEAP) leap(p);
                else setCrouch(p, value == TAKEOFF_CROUCH);
            }
            default -> {
            }
        }
    }

    private static void startDash(Player p, int kind) {
        PState s = state(p.getId());
        s.dashKind = kind;
        s.dashAge = 0;
        double rad = Math.toRadians(p.getYRot());
        Vec3 f = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
        Vec3 r = new Vec3(-f.z, 0.0, f.x);
        s.dashDir = switch (kind) {
            case DASH_BACK -> f.scale(-1.0);
            case DASH_LEFT -> r.scale(-1.0);
            case DASH_RIGHT -> r;
            default -> f;
        };
        s.dashPrev = p.position();
        s.crouching = false;
        Vec3 feet = p.position();
        boolean side = kind == DASH_LEFT || kind == DASH_RIGHT;
        sound(p, side ? JujutsuNeonMod.SFX_MOVE_DASH_SIDE : JujutsuNeonMod.SFX_MOVE_DASH_FRONT, 1.0f, side ? 1.05f : 1.0f);
        if (side) {
            // Полосы-силуэты на месте рывка, вдоль всего пути.
            for (int i = 0; i < 5; i++) {
                double h = 0.25 + i * 0.32;
                Vec3 a = feet.add(0.0, h, 0.0).add(s.dashDir.scale(-0.2 + RND.nextDouble() * 0.3));
                Vec3 b = a.add(s.dashDir.scale(3.5 + RND.nextDouble() * 1.5));
                LINES.add(new Line(a, b, 0.05f + RND.nextFloat() * 0.05f, 9 + RND.nextInt(4), 0.80f, 1.0f, 0.88f, 0.75f, true));
            }
        } else {
            RINGS.add(new Ring(feet.add(0.0, 0.06, 0.0), 0.3f, 4.0f, 9, 1f, 1f, 1f, 0.95f));
            groundBurst(p, 8, 0.18, 0.10);
            for (int i = 0; i < 6; i++) {
                double a = i / 6.0 * Math.PI * 2.0;
                Vec3 v = new Vec3(Math.cos(a) * 0.08, 0.02, Math.sin(a) * 0.08);
                PUFFS.add(new Puff(feet.add(0.0, 0.15, 0.0), v, 0.5f, 1.6f, 18, 0.9f, 0.9f, 0.88f, 0.55f));
            }
        }
    }

    private static void setFlight(Player p, int mode) {
        PState s = state(p.getId());
        int old = s.flight;
        s.flight = mode;
        if (mode == old) return;
        if (mode == FLIGHT_ON && old == FLIGHT_OFF) {
            swirl(p, 22, 4, 0.9f);
            sparkles(p, 10);
        }
        if (mode == FLIGHT_BOOST && old != FLIGHT_BOOST) {
            sound(p, JujutsuNeonMod.SFX_MOVE_BOOST, 0.9f, 1.0f);
            Vec3 v = s.vel.lengthSqr() > 1.0E-4 ? s.vel.normalize() : p.getLookAngle();
            RINGS.add(new Ring(p.position().add(0.0, 0.9, 0.0), v, 0.4f, 3.2f, 8, 1f, 1f, 1f, 0.8f));
        }
        if (mode == FLIGHT_OFF && old != FLIGHT_OFF) {
            if (p.onGround() || groundBelow(p, 1.2)) {
                sound(p, JujutsuNeonMod.SFX_MOVE_LAND, 0.8f, 1.0f);
                RINGS.add(new Ring(p.position().add(0.0, 0.06, 0.0), 0.3f, 2.6f, 8, 1f, 1f, 1f, 0.7f));
                groundBurst(p, 6, 0.12, 0.08);
            }
        }
    }

    private static void setCrouch(Player p, boolean on) {
        PState s = state(p.getId());
        if (on && !s.crouching) {
            s.crouching = true;
            s.crouchAge = 0;
            sound(p, JujutsuNeonMod.SFX_MOVE_CROUCH, 0.7f, 1.0f);
        } else if (!on) {
            s.crouching = false;
        }
    }

    private static void leap(Player p) {
        PState s = state(p.getId());
        s.crouching = false;
        s.leapAge = 0;
        Vec3 feet = p.position();
        sound(p, JujutsuNeonMod.SFX_MOVE_TAKEOFF, 1.1f, 1.0f);
        RINGS.add(new Ring(feet.add(0.0, 0.06, 0.0), 0.4f, 5.5f, 11, 1f, 1f, 1f, 0.95f));
        RINGS.add(new Ring(feet.add(0.0, 0.10, 0.0), 0.2f, 3.0f, 7, 1f, 1f, 1f, 0.7f));
        groundBurst(p, 18, 0.25, 0.22);
        for (int i = 0; i < 12; i++) {
            double a = i / 12.0 * Math.PI * 2.0 + RND.nextDouble() * 0.3;
            Vec3 v = new Vec3(Math.cos(a) * 0.14, 0.03 + RND.nextDouble() * 0.04, Math.sin(a) * 0.14);
            PUFFS.add(new Puff(feet.add(0.0, 0.12, 0.0), v, 0.6f, 2.2f, 24 + RND.nextInt(10), 0.92f, 0.92f, 0.9f, 0.6f));
        }
        swirl(p, 28, 4, 1.0f);
        sparkles(p, 14);
    }

    // ================================================================== тики

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!STATES.isEmpty() || !PUFFS.isEmpty()) resetAll();
            return;
        }
        if (mc.isPaused()) return;

        for (Player p : mc.level.players()) {
            if (!(p instanceof AbstractClientPlayer cp)) continue;
            ensureAnim(cp);
            PState s = state(p.getId());
            tickState(mc, p, s);
        }
        STATES.values().removeIf(s -> mc.level.getEntity(s.entityId) == null);

        tickFx();
    }

    private static void ensureAnim(AbstractClientPlayer p) {
        if (ANIMS.containsKey(p)) return;
        MoveAnim anim = new MoveAnim(p.getId());
        ANIMS.put(p, anim);
        try {
            PlayerAnimationAccess.getPlayerAnimLayer(p).addAnimLayer(LAYER_PRIORITY, anim);
        } catch (RuntimeException | LinkageError ignored) {
            // Библиотека анимаций недоступна — без анимации тела.
        }
    }

    private static void tickState(Minecraft mc, Player p, PState s) {
        s.age++;
        Vec3 pos = p.position();
        s.velO = s.vel;
        if (s.lastPos != null) {
            Vec3 d = pos.subtract(s.lastPos);
            if (d.lengthSqr() > 64.0) d = Vec3.ZERO; // телепорт
            s.vel = s.vel.lerp(d, 0.5);
        }
        s.lastPos = pos;

        // Веса анимаций плавно идут к цели.
        s.flightWo = s.flightW;
        s.boostWo = s.boostW;
        s.landWo = s.landW;
        s.crouchWo = s.crouchW;
        float fT = s.flight != FLIGHT_OFF ? 1f : 0f;
        float bT = s.flight == FLIGHT_BOOST ? 1f : 0f;
        float lT = s.flight == FLIGHT_LANDING ? 1f : 0f;
        s.flightW += (fT - s.flightW) * (fT > s.flightW ? 0.22f : 0.3f);
        s.boostW += (bT - s.boostW) * 0.2f;
        s.landW += (lT - s.landW) * 0.25f;
        if (s.crouching) s.crouchAge++;
        // Вес слоя — быстро к 1; глубину приседа набирает сама поза (плавно, без рывков).
        float cT = s.crouching ? 1f : 0f;
        s.crouchW += (cT - s.crouchW) * (s.crouching ? 0.5f : 0.35f);
        if (Math.abs(s.flightW) < 0.001f) s.flightW = 0f;
        if (Math.abs(s.crouchW) < 0.001f) s.crouchW = 0f;

        if (s.leapAge >= 0) {
            s.leapAge++;
            if (s.leapAge > 40) s.leapAge = -1;
        }

        // ---- дэш
        if (s.dashKind >= 0) {
            tickDashFx(p, s);
            s.dashAge++;
            boolean side = s.dashKind == DASH_LEFT || s.dashKind == DASH_RIGHT;
            if (s.dashAge > (side ? SIDE_DASH_TICKS + 10 : FRONT_DASH_TICKS + 4)) s.dashKind = -1;
        }
        s.dashPrev = pos;

        // ---- присед перед взлётом: пыль у ног
        if (s.crouching && s.crouchAge % 3 == 0) {
            Vec3 f = p.position();
            for (int i = 0; i < 2; i++) {
                double a = RND.nextDouble() * Math.PI * 2.0;
                Vec3 at = f.add(Math.cos(a) * 0.6, 0.05, Math.sin(a) * 0.6);
                PUFFS.add(new Puff(at, new Vec3(-Math.cos(a) * 0.02, 0.015, -Math.sin(a) * 0.02), 0.25f, 0.7f, 14, 0.9f, 0.9f, 0.9f, 0.35f));
            }
            if (s.crouchAge == CROUCH_FULL_TICKS) {
                RINGS.add(new Ring(f.add(0.0, 0.05, 0.0), 2.2f, 0.4f, 7, 1f, 1f, 1f, 0.6f));
            }
        }

        // ---- полёт
        if (s.flight != FLIGHT_OFF) tickFlightFx(p, s);

        // ---- сверхбег (по фактической скорости — видно и у чужих)
        double hs = Math.sqrt(s.vel.x * s.vel.x + s.vel.z * s.vel.z);
        boolean running = s.flight == FLIGHT_OFF && s.dashKind < 0 && hs > 0.55 && (p.onGround() || onWaterSurface(p));
        s.runTicks = running ? s.runTicks + 1 : 0;
        if (running) tickRunFx(p, s, hs);
    }

    private static void tickDashFx(Player p, PState s) {
        Vec3 pos = p.position();
        boolean side = s.dashKind == DASH_LEFT || s.dashKind == DASH_RIGHT;
        int a = s.dashAge;
        if (side) {
            if (a <= SIDE_DASH_TICKS + 1) {
                // Длинный низкий дым по пути рывка.
                Vec3 from = s.dashPrev;
                for (int i = 0; i < 4; i++) {
                    Vec3 at = from.lerp(pos, RND.nextDouble()).add(0.0, 0.12 + RND.nextDouble() * 0.35, 0.0);
                    Vec3 v = new Vec3((RND.nextDouble() - 0.5) * 0.02, 0.006, (RND.nextDouble() - 0.5) * 0.02);
                    PUFFS.add(new Puff(at, v, 0.45f, 1.3f, 30 + RND.nextInt(14), 0.82f, 0.82f, 0.8f, 0.55f));
                }
                groundDust(p, 2, 0.06);
            }
            return;
        }
        if (a < FRONT_DASH_TICKS - 4) {
            // Рывок: полосы ветра у тела и шлейф пыли по земле.
            Vec3 back = s.dashDir.scale(-1.0);
            for (int i = 0; i < 3; i++) {
                double h = 0.2 + RND.nextDouble() * 1.5;
                Vec3 side2 = new Vec3(-s.dashDir.z, 0.0, s.dashDir.x).scale((RND.nextDouble() - 0.5) * 1.1);
                Vec3 st = pos.add(0.0, h, 0.0).add(side2);
                LINES.add(new Line(st, st.add(back.scale(2.5 + RND.nextDouble() * 2.5)), 0.025f + RND.nextFloat() * 0.025f,
                        5 + RND.nextInt(3), 1f, 1f, 1f, 0.75f, false));
            }
            Vec3 from = s.dashPrev;
            for (int i = 0; i < 3; i++) {
                Vec3 at = from.lerp(pos, RND.nextDouble()).add(0.0, 0.08, 0.0);
                PUFFS.add(new Puff(at, new Vec3(0.0, 0.01, 0.0), 0.35f, 1.1f, 22 + RND.nextInt(10), 0.86f, 0.84f, 0.8f, 0.45f));
            }
            groundDust(p, 3, 0.08);
        } else if (a < FRONT_DASH_TICKS + 1) {
            // Торможение: из-под ног вперёд летят пыль и комья.
            BlockState ground = groundState(p);
            for (int i = 0; i < 6; i++) {
                Vec3 v = s.dashDir.scale(0.12 + RND.nextDouble() * 0.18).add((RND.nextDouble() - 0.5) * 0.12, 0.10 + RND.nextDouble() * 0.12,
                        (RND.nextDouble() - 0.5) * 0.12);
                if (ground != null) {
                    p.level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), pos.x, pos.y + 0.1, pos.z, v.x * 3, v.y * 3, v.z * 3);
                }
            }
            for (int i = 0; i < 2; i++) {
                PUFFS.add(new Puff(pos.add(s.dashDir.scale(0.4)).add(0.0, 0.15, 0.0), s.dashDir.scale(0.05).add(0.0, 0.02, 0.0),
                        0.5f, 1.6f, 20, 0.88f, 0.86f, 0.82f, 0.55f));
            }
        }
    }

    private static void tickFlightFx(Player p, PState s) {
        Vec3 c = p.position().add(0.0, 0.9, 0.0);
        if (s.flight == FLIGHT_BOOST) {
            Vec3 v = s.vel;
            double sp = v.length();
            Vec3 back = sp > 1.0E-3 ? v.scale(-1.0 / sp) : p.getLookAngle().scale(-1.0);
            for (int i = 0; i < 3; i++) {
                Vec3 off = randDir(RND).scale(0.35 + RND.nextDouble() * 0.5);
                Vec3 st = c.add(off);
                LINES.add(new Line(st, st.add(back.scale(2.0 + sp * 2.0)), 0.025f + RND.nextFloat() * 0.03f, 5 + RND.nextInt(3),
                        1f, 1f, 1f, 0.7f, false));
            }
            if (s.age % 6 == 0) swirl(p, 12, 2, 0.7f);
            if (RND.nextInt(3) == 0) sparkles(p, 1);
        } else {
            // Спокойный полёт: лёгкие светлые пылинки и редкий тихий серп ветра.
            if (s.age % 3 == 0) {
                Vec3 at = p.position().add((RND.nextDouble() - 0.5) * 1.2, RND.nextDouble() * 0.4, (RND.nextDouble() - 0.5) * 1.2);
                MOTES.add(new Mote(at, new Vec3(0.0, -0.015 - RND.nextDouble() * 0.01, 0.0), 0.05f, 24, false));
            }
            if (s.age % 16 == 0) swirl(p, 16, 1, 0.35f);
        }
    }

    private static void tickRunFx(Player p, PState s, double hs) {
        Vec3 pos = p.position();
        Vec3 dir = new Vec3(s.vel.x, 0.0, s.vel.z).scale(1.0 / Math.max(1.0E-4, hs));
        boolean water = onWaterSurface(p);
        if (water) {
            for (int i = 0; i < 4; i++) {
                p.level().addParticle(ParticleTypes.SPLASH, pos.x + (RND.nextDouble() - 0.5) * 0.8, pos.y + 0.05,
                        pos.z + (RND.nextDouble() - 0.5) * 0.8, -dir.x * 0.2, 0.15, -dir.z * 0.2);
            }
            if (s.runTicks % 3 == 0) RINGS.add(new Ring(pos.add(0.0, 0.04, 0.0), 0.3f, 1.8f, 10, 0.85f, 0.95f, 1f, 0.5f));
        } else {
            // Пыль из-под ног и низкий шлейф позади.
            groundDust(p, 2, 0.05);
            Vec3 at = pos.subtract(dir.scale(0.4)).add(0.0, 0.1, 0.0);
            PUFFS.add(new Puff(at, dir.scale(-0.02).add(0.0, 0.012, 0.0), 0.35f, 1.25f, 18 + RND.nextInt(8), 0.86f, 0.85f, 0.82f, 0.4f));
        }
        // Полосы ветра вдоль тела.
        for (int i = 0; i < 2; i++) {
            double h = 0.25 + RND.nextDouble() * 1.45;
            Vec3 sideV = new Vec3(-dir.z, 0.0, dir.x).scale((RND.nextDouble() - 0.5) * 1.0);
            Vec3 st = pos.add(0.0, h, 0.0).add(sideV);
            LINES.add(new Line(st, st.subtract(dir.scale(1.8 + RND.nextDouble() * 2.2)), 0.02f + RND.nextFloat() * 0.02f,
                    4 + RND.nextInt(3), 1f, 1f, 1f, 0.6f, false));
        }
        // Шаги.
        if (++s.stepTimer >= 5) {
            s.stepTimer = 0;
            sound(p, JujutsuNeonMod.SFX_MOVE_STEP, 0.45f, 0.9f + RND.nextFloat() * 0.25f);
            if (!water) RINGS.add(new Ring(pos.add(0.0, 0.05, 0.0), 0.2f, 1.2f, 6, 1f, 1f, 1f, 0.35f));
        }
    }

    // ================================================================== вспомогательное

    private static void sound(Entity e, RegistryObject<SoundEvent> s, float vol, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.playLocalSound(e.getX(), e.getY() + 0.5, e.getZ(), s.get(), SoundSource.PLAYERS, vol, pitch, false);
    }

    private static BlockState groundState(Player p) {
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
        BlockState st = p.level().getBlockState(below);
        if (st.isAir() || st.getRenderShape() == RenderShape.INVISIBLE) return null;
        return st;
    }

    private static boolean groundBelow(Player p, double dist) {
        for (double d = 0.1; d <= dist; d += 0.25) {
            BlockPos bp = BlockPos.containing(p.getX(), p.getY() - d, p.getZ());
            if (!p.level().getBlockState(bp).getCollisionShape(p.level(), bp).isEmpty()) return true;
        }
        return false;
    }

    private static boolean onWaterSurface(Player p) {
        BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.18, p.getZ());
        return p.level().getFluidState(below).is(FluidTags.WATER) && !p.isUnderWater();
    }

    private static void groundDust(Player p, int n, double spread) {
        BlockState st = groundState(p);
        if (st == null) return;
        for (int i = 0; i < n; i++) {
            p.level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, st),
                    p.getX() + (RND.nextDouble() - 0.5) * 0.6, p.getY() + 0.08, p.getZ() + (RND.nextDouble() - 0.5) * 0.6,
                    (RND.nextDouble() - 0.5) * spread * 4, 0.1 + RND.nextDouble() * 0.15, (RND.nextDouble() - 0.5) * spread * 4);
        }
    }

    private static void groundBurst(Player p, int n, double speed, double up) {
        BlockState st = groundState(p);
        if (st == null) return;
        for (int i = 0; i < n; i++) {
            double a = RND.nextDouble() * Math.PI * 2.0;
            p.level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, st),
                    p.getX() + Math.cos(a) * 0.4, p.getY() + 0.1, p.getZ() + Math.sin(a) * 0.4,
                    Math.cos(a) * speed * 4, up * 4 + RND.nextDouble() * 0.2, Math.sin(a) * speed * 4);
        }
    }

    private static void swirl(Player p, int life, int arcs, float alpha) {
        SWIRLS.add(new Swirl(p.getId(), life, arcs, alpha, RND.nextLong()));
    }

    private static void sparkles(Player p, int n) {
        Vec3 c = p.position().add(0.0, 0.9, 0.0);
        for (int i = 0; i < n; i++) {
            Vec3 at = c.add(randDir(RND).scale(0.6 + RND.nextDouble() * 1.4));
            MOTES.add(new Mote(at, randDir(RND).scale(0.02), 0.25f + RND.nextFloat() * 0.25f, 10 + RND.nextInt(10), true));
        }
    }

    private static Vec3 randDir(Random rnd) {
        double a = rnd.nextDouble() * Math.PI * 2.0, z = rnd.nextDouble() * 2.0 - 1.0, r = Math.sqrt(1.0 - z * z);
        return new Vec3(Math.cos(a) * r, z, Math.sin(a) * r);
    }

    private static double smooth(double v) {
        v = Mth.clamp(v, 0.0, 1.0);
        return v * v * (3.0 - 2.0 * v);
    }

    private static void resetAll() {
        STATES.clear();
        PUFFS.clear();
        LINES.clear();
        RINGS.clear();
        MOTES.clear();
        SWIRLS.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
        ANIMS.clear();
    }

    // ================================================================== эффекты

    private static final class Puff {
        Vec3 pos, prev;
        final Vec3 vel;
        final float s0, s1, a0, r, g, b;
        final int life;
        final float rot;
        int age;

        Puff(Vec3 pos, Vec3 vel, float s0, float s1, int life, float r, float g, float b, float a0) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.s0 = s0;
            this.s1 = s1;
            this.life = life;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a0 = a0;
            this.rot = RND.nextFloat() * 6.28f;
        }
    }

    private static final class Line {
        final Vec3 a, b;
        final float width, r, g, bl, a0;
        final int life;
        final boolean grow;
        int age;

        Line(Vec3 a, Vec3 b, float width, int life, float r, float g, float bl, float a0, boolean grow) {
            this.a = a;
            this.b = b;
            this.width = width;
            this.life = life;
            this.r = r;
            this.g = g;
            this.bl = bl;
            this.a0 = a0;
            this.grow = grow;
        }
    }

    private static final class Ring {
        final Vec3 c;
        final Vec3 normal;
        final float r0, r1, r, g, b, a0;
        final int life;
        int age;

        Ring(Vec3 c, float r0, float r1, int life, float r, float g, float b, float a0) {
            this(c, null, r0, r1, life, r, g, b, a0);
        }

        Ring(Vec3 c, Vec3 normal, float r0, float r1, int life, float r, float g, float b, float a0) {
            this.c = c;
            this.normal = normal;
            this.r0 = r0;
            this.r1 = r1;
            this.life = life;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a0 = a0;
        }
    }

    private static final class Mote {
        Vec3 pos, prev;
        final Vec3 vel;
        final float size;
        final int life;
        final boolean star;
        int age;

        Mote(Vec3 pos, Vec3 vel, float size, int life, boolean star) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.size = size;
            this.life = life;
            this.star = star;
        }
    }

    /** Закрученные белые серпы ветра вокруг игрока (как в референсе взлёта). */
    private static final class Swirl {
        final int entityId;
        final int life;
        final int arcs;
        final float alpha;
        final long seed;
        int age;

        Swirl(int entityId, int life, int arcs, float alpha, long seed) {
            this.entityId = entityId;
            this.life = life;
            this.arcs = arcs;
            this.alpha = alpha;
            this.seed = seed;
        }
    }

    private static final List<Puff> PUFFS = new ArrayList<>();
    private static final List<Line> LINES = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final List<Swirl> SWIRLS = new ArrayList<>();

    private static void tickFx() {
        for (Iterator<Puff> it = PUFFS.iterator(); it.hasNext(); ) {
            Puff p = it.next();
            if (++p.age > p.life) {
                it.remove();
                continue;
            }
            p.prev = p.pos;
            p.pos = p.pos.add(p.vel.scale(1.0 - p.age / (double) p.life));
        }
        LINES.removeIf(l -> ++l.age > l.life);
        RINGS.removeIf(r -> ++r.age > r.life);
        for (Iterator<Mote> it = MOTES.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            if (++m.age > m.life) {
                it.remove();
                continue;
            }
            m.prev = m.pos;
            m.pos = m.pos.add(m.vel);
        }
        SWIRLS.removeIf(s -> ++s.age > s.life);
        if (PUFFS.size() > 900) PUFFS.subList(0, PUFFS.size() - 900).clear();
        if (LINES.size() > 600) LINES.subList(0, LINES.size() - 600).clear();
        if (MOTES.size() > 400) MOTES.subList(0, MOTES.size() - 400).clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (PUFFS.isEmpty() && LINES.isEmpty() && RINGS.isEmpty() && MOTES.isEmpty() && SWIRLS.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        Vec3 camera = cam.getPosition();
        float pt = mc.isPaused() ? 0.0f : event.getPartialTick();
        // Свой эффект от первого лица не лезет в глаза: всё ближе 0,6 блока к камере гаснет.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        for (Puff p : PUFFS) {
            double k = (p.age + pt) / p.life;
            float a = (float) (p.a0 * (1.0 - smooth((k - 0.3) / 0.7)) * smooth(k / 0.12));
            float size = (float) (p.s0 + (p.s1 - p.s0) * (1.0 - Math.pow(1.0 - k, 2.0)));
            Vec3 at = p.prev.lerp(p.pos, pt);
            a *= nearFade(at, camera, firstPerson);
            billboard(pose, cam, camera, at, size, p.rot + (float) k * 0.6f, TEX_PUFF, p.r, p.g, p.b, a, false);
        }
        for (Line l : LINES) {
            double k = (l.age + pt) / l.life;
            float a = (float) (l.a0 * (1.0 - k));
            Vec3 from = l.a, to = l.b;
            if (l.grow) to = l.a.lerp(l.b, Math.min(1.0, smooth(k * 3.0)));
            a *= nearFade(from.add(to).scale(0.5), camera, firstPerson);
            streak(pose, camera, from, to, l.width, TEX_STREAK, l.r, l.g, l.bl, a, true);
        }
        for (Ring r : RINGS) {
            double k = (r.age + pt) / r.life;
            float rad = (float) (r.r0 + (r.r1 - r.r0) * (1.0 - Math.pow(1.0 - k, 2.2)));
            float a = (float) (r.a0 * (1.0 - smooth((k - 0.2) / 0.8)));
            if (r.normal == null) flat(pose, camera, r.c, rad * 2.0f, 0f, TEX_RING, r.r, r.g, r.b, a, true);
            else oriented(pose, camera, r.c, r.normal, rad * 2.0f, TEX_RING, r.r, r.g, r.b, a);
        }
        for (Mote m : MOTES) {
            double k = (m.age + pt) / m.life;
            float a = (float) (Math.sin(Math.PI * k));
            Vec3 at = m.prev.lerp(m.pos, pt);
            a *= nearFade(at, camera, firstPerson);
            if (m.star) {
                float tw = 0.7f + 0.3f * (float) Math.sin((m.age + pt) * 1.7);
                billboard(pose, cam, camera, at, m.size * tw, 0f, TEX_STAR, 1f, 1f, 1f, a, true);
            } else {
                billboard(pose, cam, camera, at, m.size, 0f, TEX_STAR, 0.9f, 0.95f, 1f, a * 0.8f, true);
            }
        }
        for (Swirl s : SWIRLS) renderSwirl(mc, pose, camera, s, pt, firstPerson);

        HollowPurpleReferenceClient.mpAlphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static float nearFade(Vec3 at, Vec3 camera, boolean firstPerson) {
        if (!firstPerson) return 1.0f;
        double d = at.distanceTo(camera);
        return (float) smooth((d - 0.6) / 1.2);
    }

    /** Серпы ветра: наклонённые кольца-дуги вокруг тела, быстро вращаются, растут и гаснут. */
    private static void renderSwirl(Minecraft mc, PoseStack pose, Vec3 camera, Swirl s, float pt, boolean firstPerson) {
        Entity e = mc.level.getEntity(s.entityId);
        if (e == null) return;
        Vec3 c = new Vec3(Mth.lerp(pt, e.xOld, e.getX()), Mth.lerp(pt, e.yOld, e.getY()) + 0.9, Mth.lerp(pt, e.zOld, e.getZ()));
        double t = s.age + pt;
        double k = t / s.life;
        float fade = (float) (smooth(k / 0.15) * (1.0 - smooth((k - 0.55) / 0.45))) * s.alpha;
        if (fade <= 0.01f) return;
        if (firstPerson && e == mc.player) fade *= 0.55f;
        Random rnd = new Random(s.seed);
        for (int i = 0; i < s.arcs; i++) {
            double tiltX = (rnd.nextDouble() - 0.5) * 1.2;
            double tiltZ = (rnd.nextDouble() - 0.5) * 1.2;
            double spin = (rnd.nextBoolean() ? 1 : -1) * (0.45 + rnd.nextDouble() * 0.35);
            double radius = (0.75 + rnd.nextDouble() * 0.5) * (0.85 + 0.45 * smooth(k));
            double yOff = (rnd.nextDouble() - 0.5) * 0.9;
            double start = rnd.nextDouble() * Math.PI * 2.0 + t * spin;
            double span = Math.PI * (0.7 + rnd.nextDouble() * 0.5);
            Vec3 prev = null;
            int n = 18;
            for (int j = 0; j <= n; j++) {
                double u = j / (double) n;
                double a = start + span * u;
                double x = Math.cos(a) * radius, z = Math.sin(a) * radius;
                // Наклон кольца: поворот вокруг X и Z.
                double y1 = z * Math.sin(tiltX);
                double z1 = z * Math.cos(tiltX);
                double x2 = x * Math.cos(tiltZ) - y1 * Math.sin(tiltZ);
                double y2 = x * Math.sin(tiltZ) + y1 * Math.cos(tiltZ);
                Vec3 p = c.add(x2, y2 + yOff, z1);
                if (prev != null) {
                    float taper = (float) Math.sin(Math.PI * u);
                    streak(pose, camera, prev, p, 0.07f * taper + 0.01f, TEX_STREAK, 0.85f, 0.95f, 1f, fade * taper * 0.55f, true);
                    streak(pose, camera, prev, p, 0.03f * taper + 0.005f, TEX_STREAK, 1f, 1f, 1f, fade * taper, true);
                }
                prev = p;
            }
        }
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

    /** Квадрат, перпендикулярный normal (кольцо ударной волны поперёк полёта). */
    private static void oriented(PoseStack pose, Vec3 camera, Vec3 center, Vec3 normal, float size, ResourceLocation texture,
                                 float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vec3 n = normal.normalize();
        Vec3 ref = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = n.cross(ref).normalize().scale(size * 0.5);
        Vec3 v = n.cross(u).normalize().scale(size * 0.5);
        Vec3 o = center.subtract(camera);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        blend(true);
        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        quadVertex(buf, m, o.x - u.x - v.x, o.y - u.y - v.y, o.z - u.z - v.z, 0f, 0f, r, g, b, alpha);
        quadVertex(buf, m, o.x - u.x + v.x, o.y - u.y + v.y, o.z - u.z + v.z, 0f, 1f, r, g, b, alpha);
        quadVertex(buf, m, o.x + u.x + v.x, o.y + u.y + v.y, o.z + u.z + v.z, 1f, 1f, r, g, b, alpha);
        quadVertex(buf, m, o.x + u.x - v.x, o.y + u.y - v.y, o.z + u.z - v.z, 1f, 0f, r, g, b, alpha);
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

    // ================================================================== анимация тела

    /** Поза: значения в градусах (тело: y в блоках). NaN — не трогать. */
    private static final class Pose {
        float bodyY, bodyPitch, bodyYaw, bodyRoll;
        float torsoBend;
        float headPitch, headYaw, headRoll; // добавка к взгляду
        final float[] rArm = new float[4], lArm = new float[4], rLeg = new float[4], lLeg = new float[4];
        float w;

        Pose set(float[] part, float pitch, float yaw, float roll, float bend) {
            part[0] = pitch;
            part[1] = yaw;
            part[2] = roll;
            part[3] = bend;
            return this;
        }

        void lerpTo(Pose o, float k) {
            bodyY += (o.bodyY - bodyY) * k;
            bodyPitch += (o.bodyPitch - bodyPitch) * k;
            bodyYaw += (o.bodyYaw - bodyYaw) * k;
            bodyRoll += (o.bodyRoll - bodyRoll) * k;
            torsoBend += (o.torsoBend - torsoBend) * k;
            headPitch += (o.headPitch - headPitch) * k;
            headYaw += (o.headYaw - headYaw) * k;
            headRoll += (o.headRoll - headRoll) * k;
            for (int i = 0; i < 4; i++) {
                rArm[i] += (o.rArm[i] - rArm[i]) * k;
                lArm[i] += (o.lArm[i] - lArm[i]) * k;
                rLeg[i] += (o.rLeg[i] - rLeg[i]) * k;
                lLeg[i] += (o.lLeg[i] - lLeg[i]) * k;
            }
        }
    }

    private static float crouchY(float deg) {
        return (float) (-0.75 * 0.9375 * (1.0 - Math.cos(Math.toRadians(deg))));
    }

    /** Слой анимации движения: позы по состоянию, плавно смешанные с ванильной ходьбой. */
    private static final class MoveAnim implements IAnimation {
        final int entityId;
        final List<Pose> layers = new ArrayList<>();
        boolean active;

        MoveAnim(int entityId) {
            this.entityId = entityId;
        }

        @Override
        public boolean isActive() {
            PState s = STATES.get(entityId);
            return s != null && s.animating();
        }

        @Override
        public void setupAnim(float pt) {
            layers.clear();
            PState s = STATES.get(entityId);
            if (s == null) return;
            Minecraft mc = Minecraft.getInstance();
            Entity e = mc.level != null ? mc.level.getEntity(entityId) : null;

            float fw = Mth.lerp(pt, s.flightWo, s.flightW);
            if (fw > 0.001f) layers.add(flightPose(s, pt, fw, e));
            if (s.leapAge >= 0) {
                Pose lp = leapPose(s, pt, fw);
                if (lp.w > 0.001f) layers.add(lp);
            }
            float cw = Mth.lerp(pt, s.crouchWo, s.crouchW);
            if (cw > 0.001f) layers.add(crouchPose(s, pt, cw));
            if (s.dashKind >= 0) {
                Pose dp = dashPose(s, pt);
                if (dp.w > 0.001f) layers.add(dp);
            }
        }

        @Override
        public Vec3f get3DTransform(String part, TransformType type, float tickDelta, Vec3f v0) {
            if (layers.isEmpty()) return v0;
            float x = v0.getX(), y = v0.getY(), z = v0.getZ();
            for (Pose p : layers) {
                float k = Mth.clamp(p.w, 0f, 1f);
                switch (part) {
                    case "body" -> {
                        if (type == TransformType.POSITION) {
                            y = Mth.lerp(k, y, p.bodyY);
                        } else if (type == TransformType.ROTATION) {
                            x = Mth.lerp(k, x, rad(p.bodyPitch));
                            y = Mth.lerp(k, y, rad(p.bodyYaw));
                            z = Mth.lerp(k, z, rad(p.bodyRoll));
                        }
                    }
                    case "torso" -> {
                        if (type == TransformType.BEND) y = Mth.lerp(k, y, rad(p.torsoBend));
                    }
                    case "head" -> {
                        if (type == TransformType.ROTATION) {
                            x += rad(p.headPitch) * k;
                            y += rad(p.headYaw) * k;
                            z += rad(p.headRoll) * k;
                        }
                    }
                    case "rightArm" -> {
                        float[] q = limb(type, p.rArm, k, x, y, z);
                        x = q[0]; y = q[1]; z = q[2];
                    }
                    case "leftArm" -> {
                        float[] q = limb(type, p.lArm, k, x, y, z);
                        x = q[0]; y = q[1]; z = q[2];
                    }
                    case "rightLeg" -> {
                        float[] q = limb(type, p.rLeg, k, x, y, z);
                        x = q[0]; y = q[1]; z = q[2];
                    }
                    case "leftLeg" -> {
                        float[] q = limb(type, p.lLeg, k, x, y, z);
                        x = q[0]; y = q[1]; z = q[2];
                    }
                    default -> {
                        return v0;
                    }
                }
            }
            return new Vec3f(x, y, z);
        }

        private static float[] limb(TransformType type, float[] t, float k, float x, float y, float z) {
            if (type == TransformType.ROTATION) {
                return new float[]{Mth.lerp(k, x, rad(t[0])), Mth.lerp(k, y, rad(t[1])), Mth.lerp(k, z, rad(t[2]))};
            }
            if (type == TransformType.BEND) {
                return new float[]{x, Mth.lerp(k, y, rad(t[3])), z};
            }
            return new float[]{x, y, z};
        }

        private static float rad(float deg) {
            return deg * Mth.DEG_TO_RAD;
        }
    }

    // ---- позы

    private static Pose dashPose(PState s, float pt) {
        double a = s.dashAge + pt;
        Pose p = new Pose();
        boolean side = s.dashKind == DASH_LEFT || s.dashKind == DASH_RIGHT;
        if (side) {
            float sg = s.dashKind == DASH_LEFT ? 1f : -1f;
            p.w = (float) (smooth(a / 1.2) * (1.0 - smooth((a - SIDE_DASH_TICKS - 1) / 8.0)));
            p.bodyRoll = 38f * sg;
            p.bodyYaw = -6f * sg;
            p.bodyPitch = -6f;
            p.bodyY = -0.08f;
            p.torsoBend = 8f;
            p.headRoll = -26f * sg;
            p.headPitch = -4f;
            if (sg > 0) {
                // Влево: правая нога отстаёт наружу, правая рука развевается.
                p.set(p.rLeg, 6f, 0f, 30f, 14f).set(p.lLeg, -12f, 0f, -6f, 28f);
                p.set(p.rArm, -24f, 0f, 62f, -16f).set(p.lArm, 18f, 0f, -14f, -22f);
            } else {
                p.set(p.lLeg, 6f, 0f, -30f, 14f).set(p.rLeg, -12f, 0f, 6f, 28f);
                p.set(p.lArm, -24f, 0f, -62f, -16f).set(p.rArm, 18f, 0f, 14f, -22f);
            }
            return p;
        }

        p.w = (float) (smooth(a / 2.0) * (1.0 - smooth((a - FRONT_DASH_TICKS) / 4.0)));
        double brake = smooth((a - (FRONT_DASH_TICKS - 5)) / 3.0);
        Pose drive = new Pose();
        if (s.dashKind == DASH_FRONT) {
            // Рывок вперёд: корпус целиком наклонён, быстрый бег, руки работают.
            double ph = a * 1.35 * Math.PI * 0.5;
            float sn = (float) Math.sin(ph);
            drive.bodyPitch = -34f;
            drive.bodyY = -0.06f;
            drive.torsoBend = 10f;
            drive.headPitch = -27f;
            drive.set(drive.rLeg, -12f + 52f * sn, 0f, 3f, 22f + 30f * Math.max(0f, -sn));
            drive.set(drive.lLeg, -12f - 52f * sn, 0f, -3f, 22f + 30f * Math.max(0f, sn));
            drive.set(drive.rArm, 34f - 44f * sn, 0f, 12f, -72f);
            drive.set(drive.lArm, 34f + 44f * sn, 0f, -12f, -72f);
        } else {
            // Рывок назад: низкая стойка, скольжение спиной вперёд, руки вперёд для равновесия.
            drive.bodyPitch = -14f;
            drive.bodyY = -0.18f;
            drive.torsoBend = 12f;
            drive.headPitch = -10f;
            drive.set(drive.rLeg, -34f, 0f, 6f, 54f).set(drive.lLeg, 24f, 0f, -6f, 34f);
            drive.set(drive.rArm, -52f, 0f, 22f, -30f).set(drive.lArm, -46f, 0f, -22f, -30f);
        }
        Pose stop = new Pose();
        // Торможение: корпус откидывается, передняя нога упирается, руки в стороны.
        stop.bodyPitch = s.dashKind == DASH_FRONT ? 16f : -12f;
        stop.bodyY = -0.18f;
        stop.torsoBend = 4f;
        stop.headPitch = s.dashKind == DASH_FRONT ? 10f : -6f;
        stop.set(stop.rLeg, -46f, 0f, 4f, 12f).set(stop.lLeg, 22f, 0f, -4f, 60f);
        stop.set(stop.rArm, -55f, 0f, 38f, -20f).set(stop.lArm, -42f, 0f, -42f, -20f);
        drive.lerpTo(stop, (float) brake);
        drive.w = p.w;
        return drive;
    }

    private static Pose crouchPose(PState s, float pt, float w) {
        Pose p = new Pose();
        p.w = w;
        double t = s.crouchAge + (s.crouching ? pt : 0f);
        // Как перед настоящим прыжком: колени плавно сгибаются, таз уходит назад, корпус чуть
        // наклоняется вперёд, руки отводятся назад для замаха, взгляд вперёд-вверх. Без тряски —
        // набрав глубину, просто держит стойку и едва заметно дышит.
        float d = (float) smooth(t / CROUCH_FULL_TICKS);
        float breath = d >= 0.999f ? (float) Math.sin(t * 0.22) * 0.006f : 0f;
        p.bodyY = crouchY(40f * d) + breath;
        p.bodyPitch = -7f * d;
        p.torsoBend = 14f * d;
        p.headPitch = -5f * d;
        p.set(p.rLeg, -44f * d, 0f, 3f * d, 84f * d).set(p.lLeg, -40f * d, 0f, -3f * d, 80f * d);
        p.set(p.rArm, 30f * d, 0f, 7f, -6f - 12f * d).set(p.lArm, 30f * d, 0f, -7f, -6f - 12f * d);
        return p;
    }

    private static Pose leapPose(PState s, float pt, float flightW) {
        double a = s.leapAge + pt;
        Pose up = new Pose();
        // Толчок: тело вытянуто вверх, руки вскинуты.
        up.bodyPitch = -4f;
        up.torsoBend = -8f;
        up.headPitch = -12f;
        up.set(up.rLeg, 8f, 0f, 2f, 4f).set(up.lLeg, -2f, 0f, -2f, 6f);
        up.set(up.rArm, -164f, 0f, 18f, -10f).set(up.lArm, -164f, 0f, -18f, -10f);
        Pose rise = new Pose();
        // Подъём: руки раскрываются в стороны, ноги чуть согнуты — переход к зависанию.
        rise.bodyPitch = 3f;
        rise.torsoBend = 2f;
        rise.set(rise.rLeg, 16f, 0f, 4f, 32f).set(rise.lLeg, -6f, 0f, -3f, 16f);
        rise.set(rise.rArm, -30f, 0f, 38f, -22f).set(rise.lArm, -26f, 0f, -40f, -20f);
        up.lerpTo(rise, (float) smooth((a - 3.0) / 8.0));
        double out = Math.max(smooth((a - 14.0) / 10.0), flightW > 0.5f ? smooth((a - 8.0) / 6.0) : 0.0);
        up.w = (float) (smooth(a / 1.5) * (1.0 - out));
        return up;
    }

    private static Pose flightPose(PState s, float pt, float w, Entity e) {
        Vec3 v = s.velO.lerp(s.vel, pt);
        double hs = Math.sqrt(v.x * v.x + v.z * v.z);
        double elev = Math.toDegrees(Math.atan2(v.y, Math.max(1.0E-4, hs)));
        float move = (float) Mth.clamp(v.length() / 0.25, 0.0, 1.0);
        float boost = Mth.lerp(pt, s.boostWo, s.boostW) * move;
        float land = Mth.lerp(pt, s.landWo, s.landW);
        double t = s.age + pt;
        float bob = (float) Math.sin(t * 0.16);

        // Поворот вбок — небольшой крен.
        float bank = 0f;
        if (e != null && hs > 0.05) {
            double yaw = Math.toRadians(e.getYRot());
            Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
            bank = (float) Mth.clamp(v.dot(right) / Math.max(0.1, v.length()), -1.0, 1.0);
        }

        Pose hover = new Pose();
        hover.bodyPitch = 4f + bob;
        hover.bodyY = 0.04f * bob;
        hover.torsoBend = 2f;
        hover.headPitch = -2f;
        hover.set(hover.rLeg, 16f, 0f, 4f, 34f).set(hover.lLeg, -6f, 0f, -3f, 14f);
        hover.set(hover.rArm, -22f, 0f, 24f + bob * 2f, -22f).set(hover.lArm, -18f, 0f, -26f - bob * 2f, -18f);

        Pose glide = new Pose();
        float glidePitch = (float) Mth.clamp((elev - 90.0) * 0.18, -26.0, 4.0);
        glide.bodyPitch = glidePitch;
        glide.bodyRoll = -bank * 14f;
        glide.torsoBend = 4f;
        glide.headPitch = glidePitch * 0.8f;
        glide.set(glide.rLeg, 22f, 0f, 3f, 26f).set(glide.lLeg, 10f, 0f, -3f, 16f);
        glide.set(glide.rArm, 22f, 0f, 20f, -16f).set(glide.lArm, 24f, 0f, -20f, -16f);

        Pose dash = new Pose();
        // Ускорение: «супермен» — тело вдоль полёта, правый кулак вперёд, левая рука вдоль тела.
        float sp = (float) Mth.clamp(elev - 90.0, -165.0, -8.0);
        dash.bodyPitch = sp;
        dash.bodyRoll = -bank * 24f;
        dash.torsoBend = -4f;
        dash.headPitch = sp * 0.8f;
        dash.set(dash.rArm, -172f, 0f, 6f, -4f).set(dash.lArm, 14f, 0f, -14f, -6f);
        dash.set(dash.rLeg, 6f, 0f, 3f, 6f).set(dash.lLeg, 12f, 0f, -3f, 18f);

        Pose landing = new Pose();
        landing.bodyPitch = 6f;
        landing.torsoBend = 6f;
        landing.set(landing.rLeg, -6f, 0f, 4f, 10f).set(landing.lLeg, 4f, 0f, -4f, 8f);
        landing.set(landing.rArm, -28f, 0f, 36f, -18f).set(landing.lArm, -24f, 0f, -36f, -18f);

        hover.lerpTo(glide, move * (1f - boost));
        hover.lerpTo(dash, boost);
        hover.lerpTo(landing, land);
        hover.w = w;
        return hover;
    }
}
