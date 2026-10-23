package com.kira.jujutsuneon;

import com.kira.jujutsuneon.rbx.PlayerAnimations;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Служебная съёмка анимаций тела в настоящей игре (только для разработки, см. {@link CaptureMode}):
 * рядом появляется «актёр» — второй игрок в повязке, которым управляет сценарий (стоит, идёт, бежит,
 * сверхбег, присед, прыжки, дэши, зарядка, взлёт, полёт, потом техники). Свой игрок — оператор:
 * в режиме наблюдателя смотрит на актёра сбоку или спереди. Кадры — в screenshots/ с тиком и сценой в имени.
 */
final class CaptureAnimScene {

    static final int END = 1240;

    private static RemotePlayer actor;
    private static Vec3 origin;
    private static double x, y, z, speed, vy;
    private static boolean ground = true, flying, landed;
    static int tick;
    static String label = "";

    private CaptureAnimScene() {
    }

    static void start(Minecraft mc) {
        origin = mc.player.position();
        x = origin.x + 2;
        y = origin.y;
        z = origin.z;
        UUID id = UUID.nameUUIDFromBytes("jn-capture-actor".getBytes());
        for (int i = 0; (id.hashCode() & 1) != 0 && i < 64; i++) id = UUID.nameUUIDFromBytes(("jn-capture-actor" + i).getBytes());
        actor = new RemotePlayer(mc.level, new GameProfile(id, "Gojo"));
        actor.setId(912345);
        actor.moveTo(x, y, z, -90f, 0f);
        actor.setYHeadRot(-90f);
        actor.yHeadRotO = -90f;
        actor.yBodyRot = actor.yBodyRotO = -90f;
        actor.setItemSlot(EquipmentSlot.HEAD, new ItemStack(JujutsuNeonMod.GOJO_BLINDFOLD.get()));
        mc.level.addPlayer(actor.getId(), actor);
        mc.options.hideGui = true;
        tick = 0;
    }

    /** Один тик сценария; true — сценарий закончен. */
    static boolean tick(Minecraft mc) {
        if (actor == null || mc.player == null) return true;
        int t = tick++;
        double target;
        boolean sneak = false;
        int view = 0; // 0 — сбоку, 1 — спереди на три четверти, 2 — издали (полёт)

        if (t < 40) {
            label = "idle";
            target = 0;
        } else if (t < 110) {
            label = "walk";
            target = 4.3;
        } else if (t < 170) {
            label = "run";
            target = 5.6;
        } else if (t < 230) {
            label = "superrun";
            target = 22;
        } else if (t < 270) {
            label = "stop";
            target = 0;
        } else if (t < 335) {
            label = "sneak";
            sneak = true;
            target = t >= 290 && t < 320 ? 1.3 : 0;
        } else if (t < 380) {
            label = "jump";
            target = 0;
            if (t == 345) jump();
        } else if (t < 430) {
            label = "runjump";
            target = t < 420 ? 5.6 : 0;
            if (t == 395) jump();
        } else if (t < 540) {
            label = "dash";
            target = 0;
            dash(t, 440, MovementFx.DASH_FRONT, 1, 0);
            dash(t, 475, MovementFx.DASH_LEFT, 0, -1);
            dash(t, 505, MovementFx.DASH_BACK, -1, 0);
        } else if (t < 760) {
            label = "flight";
            view = 2;
            target = flight(t);
        } else {
            target = 0;
            view = 1;
            techniques(t);
        }

        // движение актёра: разгон/торможение как у игрока, вперёд — +X
        double accel = target > speed ? (target > 8 ? 30 : 22) : 30;
        speed += Math.max(-accel * 0.05, Math.min(accel * 0.05, target - speed));
        x += speed * 0.05;
        if (!ground) {
            y += vy * 0.05;
            if (!flying) vy = (vy / 20.0 - 0.08) * 0.98 * 20.0;
            if (y <= origin.y && vy <= 0) {
                y = origin.y;
                vy = 0;
                ground = true;
            }
        }
        actor.setShiftKeyDown(sneak);
        actor.setPose(sneak ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
        actor.setOnGround(ground);
        actor.setPos(x, y, z);
        actor.setYRot(-90f);
        actor.setYHeadRot(-90f);

        camera(mc, view);
        return t >= END;
    }

    private static void jump() {
        vy = 0.42 * 20;
        ground = false;
    }

    private static void dash(int t, int at, int kind, double fx, double fz) {
        int a = t - at;
        if (a == 0) MovementFxClient.onRemote(actor.getId(), MovementFx.EV_DASH, kind);
        boolean side = kind == MovementFx.DASH_LEFT || kind == MovementFx.DASH_RIGHT;
        int len = side ? MovementFx.SIDE_DASH_TICKS : MovementFx.FRONT_DASH_TICKS;
        if (a >= 0 && a < len) {
            double v = (side ? 1.4 : 0.9) * (1.0 - a / (double) len);
            x += fx * v;
            z += fz * v;
        }
    }

    /** Зарядка, взлёт, зависание, полёт вперёд, ускорение, посадка. Возвращает скорость вперёд. */
    private static double flight(int t) {
        int id = actor.getId();
        if (t == 545) MovementFxClient.onRemote(id, MovementFx.EV_TAKEOFF, MovementFx.TAKEOFF_CROUCH);
        if (t == 575) {
            MovementFxClient.onRemote(id, MovementFx.EV_TAKEOFF, MovementFx.TAKEOFF_LEAP);
            vy = 1.3 * 20;
            ground = false;
        }
        if (t == 590) {
            MovementFxClient.onRemote(id, MovementFx.EV_FLIGHT, MovementFx.FLIGHT_ON);
            flying = true;
        }
        if (flying && t < 715) vy *= 0.85; // зависание: вертикальная скорость плавно гаснет
        if (t == 660) MovementFxClient.onRemote(id, MovementFx.EV_FLIGHT, MovementFx.FLIGHT_BOOST);
        if (t == 690) MovementFxClient.onRemote(id, MovementFx.EV_FLIGHT, MovementFx.FLIGHT_ON);
        if (t == 715) MovementFxClient.onRemote(id, MovementFx.EV_FLIGHT, MovementFx.FLIGHT_LANDING);
        if (t >= 715 && !ground) vy = Math.max(-7, (origin.y - y) * 2.5 - 0.5);
        if (t >= 715 && ground && !landed) {
            landed = true;
            flying = false;
            MovementFxClient.onRemote(id, MovementFx.EV_FLIGHT, MovementFx.FLIGHT_OFF);
        }
        if (t < 620) return 0;
        if (t < 660) return 8;
        if (t < 690) return 24;
        return t < 715 ? 8 : 0;
    }

    private static void techniques(int t) {
        if (t == 770) play("red_cast", "red_cast", new PlayerAnimations.Play().fade(0.1));
        if (t == 830) play("lapse_blue", "lapse_blue", new PlayerAnimations.Play().fade(0.1));
        if (t == 900) play("m1", "m1_jab_r", new PlayerAnimations.Play().fade(0.07));
        if (t == 907) play("m1", "m1_jab_l", new PlayerAnimations.Play().fade(0.07));
        if (t == 914) play("m1", "m1_jab_r", new PlayerAnimations.Play().fade(0.07));
        if (t == 921) play("m1", "m1_final", new PlayerAnimations.Play().fade(0.07));
        if (t == 960) play("blue_charge", "blue_charge", new PlayerAnimations.Play());
        if (t == 980) play("max_blue", "max_blue", new PlayerAnimations.Play());
        if (t == 1040) play("simple_domain", "simple_domain", new PlayerAnimations.Play().fade(0.15));
        if (t == 1100) play("domain_cast", "domain_cast", new PlayerAnimations.Play().fade(0.2));
        if (t == 1200) PlayerAnimations.stop(actor, 0.4);
    }

    private static void play(String lbl, String clip, PlayerAnimations.Play opts) {
        label = lbl;
        PlayerAnimations.play(actor, clip, opts);
    }

    /** Оператор-наблюдатель: сбоку (справа от актёра), спереди на три четверти или издали. */
    private static void camera(Minecraft mc, int view) {
        double cx, cy, cz;
        switch (view) {
            case 1 -> {
                cx = x + 2.6;
                cy = y + 1.35;
                cz = z + 2.6;
            }
            case 2 -> {
                cx = x + 1.5;
                cy = y + 1.6;
                cz = z + 7.5;
            }
            default -> {
                cx = x;
                cy = y + 1.15;
                cz = z + 4.2;
            }
        }
        double dx = x - cx, dy = y + 0.95 - cy, dz = z - cz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        var p = mc.player;
        p.getAbilities().flying = true;
        p.setDeltaMovement(Vec3.ZERO);
        p.setPos(cx, cy - p.getEyeHeight(), cz);
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
    }
}
