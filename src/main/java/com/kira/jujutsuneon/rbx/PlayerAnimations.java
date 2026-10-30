package com.kira.jujutsuneon.rbx;

import com.kira.jujutsuneon.MovementFxClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Анимации игроков (клиент) — у каждого игрока свой {@link Animator} и свой скрипт движения {@link AnimateScript},
 * как Animator и Animate у персонажа в Roblox.
 *
 * Из кода мода:
 * <pre>
 *   AnimationTrack t = PlayerAnimations.play(player, "red_cast");               // как в клипе
 *   PlayerAnimations.play(player, "m1_jab_r", new PlayerAnimations.Play().fade(0.07).speed(1.2));
 *   t.getMarkerReachedSignal("release").connect(p -> ...);
 *   PlayerAnimations.stop(player, 0.3);                                         // все анимации техник
 *   PlayerAnimations.animator(player).loadAnimation("wave").play();            // напрямую, как в Roblox
 * </pre>
 * Анимации техник (запущенные через {@link #play}) сменяют друг друга: новая плавно вытесняет прошлую.
 * Время идёт в каждом кадре по настоящим часам (а не по тикам), поэтому всё плавно при любом FPS.
 */
@Mod.EventBusSubscriber(modid = R15Rig.NS, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PlayerAnimations {

    private static final Map<AbstractClientPlayer, State> STATES = new WeakHashMap<>();
    /** Маркер любой анимации любого игрока (кроме тех, на которые подписались у трека). */
    public static final Signal<MarkerHit> MARKERS = new Signal<>();
    private static double debugSpeed = 1;
    private static long frame;
    private static long lastNanos = -1;
    private static int watchTimer;

    public record MarkerHit(AbstractClientPlayer player, AnimationTrack track, Clip.Marker marker) {
    }

    private PlayerAnimations() {
    }

    // ================================================================== настройки запуска

    /** Параметры запуска анимации (всё необязательно). */
    public static final class Play {
        double fade = Double.NaN;
        double weight = 1;
        double speed = 1;
        boolean mirror;
        double startAt;
        Boolean loop;
        AnimationPriority priority;
        boolean exclusive = true;
        boolean replicate;
        boolean remote;
        Clip.FirstPerson firstPerson;
        double firstPersonUntil = Double.POSITIVE_INFINITY;

        /** Плавный вход, секунды (по умолчанию — @fade_in клипа). */
        public Play fade(double s) {
            fade = s;
            return this;
        }

        public Play weight(double w) {
            weight = w;
            return this;
        }

        public Play speed(double s) {
            speed = s;
            return this;
        }

        public Play mirror(boolean m) {
            mirror = m;
            return this;
        }

        /** Начать с этой секунды анимации (догнать уже идущее действие). */
        public Play startAt(double s) {
            startAt = s;
            return this;
        }

        /** Переопределить зацикленность клипа. */
        public Play loop(Boolean l) {
            loop = l;
            return this;
        }

        public Play priority(AnimationPriority p) {
            priority = p;
            return this;
        }

        /** false — не вытеснять другие анимации техник (играть поверх/вместе). */
        public Play exclusive(boolean e) {
            exclusive = e;
            return this;
        }

        /**
         * От первого лица показывать руки модели (а не ванильные): правую, левую или обе — первые seconds
         * секунд анимации. По умолчанию — как задано в клипе (@fp).
         */
        public Play firstPerson(boolean right, boolean left, double seconds) {
            firstPerson = right && left ? Clip.FirstPerson.ARMS : right ? Clip.FirstPerson.RIGHT
                    : left ? Clip.FirstPerson.LEFT : Clip.FirstPerson.NONE;
            firstPersonUntil = seconds;
            return this;
        }

        /** Показать анимацию своего игрока остальным (как репликация анимаций в Roblox). */
        public Play replicate(boolean r) {
            replicate = r;
            return this;
        }
    }

    // ================================================================== API

    public static State state(AbstractClientPlayer p) {
        if (p == null) return null;
        return STATES.computeIfAbsent(p, State::new);
    }

    public static Animator animator(AbstractClientPlayer p) {
        State s = state(p);
        return s == null ? null : s.animator;
    }

    public static AnimationTrack play(AbstractClientPlayer p, String name) {
        return play(p, name, new Play());
    }

    public static AnimationTrack play(AbstractClientPlayer p, String name, Play o) {
        State s = state(p);
        Clip c = ClipLibrary.get(name);
        if (s == null || c == null) {
            if (c == null) RbxLog.LOGGER.debug("Player animation {} not found", name);
            return null;
        }
        double fade = Double.isNaN(o.fade) ? c.fadeIn : o.fade;
        AnimationTrack t = s.animator.loadAnimation(c);
        if (o.priority != null) t.setPriority(o.priority);
        if (o.loop != null) t.setLooped(o.loop);
        t.setMirrored(o.mirror);
        if (o.firstPerson != null) t.setFirstPerson(o.firstPerson, o.firstPersonUntil);
        if (o.exclusive) {
            for (AnimationTrack old : s.actions) {
                if (old.isActive()) old.stop(Math.max(fade, 0.05));
            }
            s.actions.clear();
        }
        s.actions.removeIf(a -> !a.isActive());
        t.play(fade, o.weight, o.speed);
        if (o.startAt > 0) t.setTimePosition(o.startAt);
        s.actions.add(t);
        t.remote = o.remote;
        if (o.replicate && p == Minecraft.getInstance().player) {
            RbxNet.Anim a = new RbxNet.Anim(c.name).fade((float) fade).weight((float) o.weight).speed((float) o.speed)
                    .mirror(o.mirror).startAt((float) o.startAt).loop(o.loop).priority(o.priority).exclusive(o.exclusive);
            RbxNet.sendToServer(a);
        }
        return t;
    }

    /** Анимация с сервера (или от другого клиента через сервер). */
    static void onNet(RbxNet.Anim m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(m.entityId) instanceof AbstractClientPlayer p)) return;
        if (m.stop) {
            if (m.name.isEmpty()) stop(p, m.fade < 0 || Float.isNaN(m.fade) ? Double.NaN : m.fade);
            else stop(p, m.name, m.fade < 0 || Float.isNaN(m.fade) ? Double.NaN : m.fade);
            return;
        }
        State s = state(p);
        // эту же анимацию только что запустил сам клиент (техника пришла своим пакетом) — второй раз не нужно
        for (AnimationTrack t : s.actions) {
            if (t.isPlaying() && t.clip.name.equalsIgnoreCase(m.name) && s.animator.clock() - t.startedAt < 0.3) return;
        }
        Play o = new Play().weight(m.weight).speed(m.speed).mirror(m.mirror).startAt(m.startAt)
                .loop(m.loop < 0 ? null : m.loop == 1).exclusive(m.exclusive);
        if (!Float.isNaN(m.fade)) o.fade(m.fade);
        if (m.priority >= 0 && m.priority < AnimationPriority.values().length) o.priority(AnimationPriority.values()[m.priority]);
        o.remote = true;
        play(p, m.name, o);
    }

    /** Плавно остановить все анимации техник за fade секунд (NaN — как в клипах). */
    public static void stop(AbstractClientPlayer p, double fade) {
        State s = p == null ? null : STATES.get(p);
        if (s == null) return;
        for (AnimationTrack t : s.actions) {
            if (t.isActive()) {
                if (Double.isNaN(fade)) t.stop();
                else t.stop(fade);
            }
        }
        s.actions.clear();
    }

    /** Остановить одну анимацию техники по имени. */
    public static void stop(AbstractClientPlayer p, String name, double fade) {
        State s = p == null ? null : STATES.get(p);
        if (s == null) return;
        for (AnimationTrack t : s.actions) {
            if (t.isActive() && t.clip.name.equalsIgnoreCase(name)) t.stop(Double.isNaN(fade) ? t.clip.fadeOut : fade);
        }
    }

    /** Играет ли анимация техники name (или любая, если name == null). */
    public static boolean isPlaying(AbstractClientPlayer p, String name) {
        State s = p == null ? null : STATES.get(p);
        if (s == null) return false;
        for (AnimationTrack t : s.actions) {
            if (t.isPlaying() && (name == null || t.clip.name.equalsIgnoreCase(name))) return true;
        }
        return false;
    }

    public static double debugSpeed() {
        return debugSpeed;
    }

    public static void setDebugSpeed(double s) {
        debugSpeed = Mth.clamp(s, 0.01, 10.0);
    }

    /**
     * Сустав кости в мире по последней отрисовке модели R15 (например, "RightHand" — запястье), с поправкой
     * along блоков вдоль кости вниз (к кончикам пальцев). null — модель не рисовалась в последние кадры
     * (тогда эффекты берут свою запасную точку).
     */
    public static net.minecraft.world.phys.Vec3 jointWorld(AbstractClientPlayer p, String bone, float pt, double along) {
        State s = p == null ? null : STATES.get(p);
        if (s == null || frame - s.drawnFrame > 2) return null;
        int i = Bones.find(bone);
        if (i < 0 || i >= s.joints.length || s.joints[i] == null || i >= s.boneMatrices.length || s.boneMatrices[i] == null) return null;
        org.joml.Vector3f j = new org.joml.Vector3f(s.joints[i]);
        if (along != 0) {
            // ось кости «вниз» в пространстве модели
            org.joml.Vector3f d = s.boneMatrices[i].transformDirection(new org.joml.Vector3f(0, -1, 0));
            if (d.lengthSquared() > 1.0E-6f) j.add(d.normalize().mul((float) along));
        }
        // модель GeckoLib: лицом к −Z; в мире тело повёрнуто на 180 − yBodyRot и уменьшено до 0.9375
        double yr = Math.toRadians(180 - Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
        double sc = 0.9375, cx = j.x * sc, cz = j.z * sc;
        double wx = cx * Math.cos(yr) + cz * Math.sin(yr);
        double wz = -cx * Math.sin(yr) + cz * Math.cos(yr);
        return new net.minecraft.world.phys.Vec3(Mth.lerp(pt, p.xo, p.getX()) + wx, Mth.lerp(pt, p.yo, p.getY()) + j.y * sc,
                Mth.lerp(pt, p.zo, p.getZ()) + wz);
    }

    /** Номер текущего кадра (меняется один раз за кадр). */
    static long frame() {
        return frame;
    }

    // ================================================================== события

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.isPaused()) {
            for (Player p : mc.level.players()) {
                if (p instanceof AbstractClientPlayer cp) state(cp).tick(cp);
            }
        }
        if (++watchTimer < 20) return;
        watchTimer = 0;
        if (ClipLibrary.externalLoaded() && ClipLibrary.externalChanged()) {
            int n = ClipLibrary.reloadExternal();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Анимации обновлены из папки: " + n
                        + (ClipLibrary.errors().isEmpty() ? "" : " (ошибок: " + ClipLibrary.errors().size() + ", /jnanim errors)")), true);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        frame++;
        long n = System.nanoTime();
        double dt = lastNanos < 0 ? 0 : (n - lastNanos) / 1.0E9;
        lastNanos = n;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.isPaused()) dt = 0;
        dt = Math.min(dt, 0.25) * debugSpeed;
        float pt = event.renderTickTime;
        for (Player p : mc.level.players()) {
            if (p instanceof AbstractClientPlayer cp) state(cp).frame(cp, pt, dt);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
        debugSpeed = 1;
    }

    // ================================================================== игрок

    public static final class State {
        final WeakReference<AbstractClientPlayer> player;
        public final Animator animator = new Animator();
        public final AnimateScript animate = new AnimateScript(animator);
        final AnimateInput in = new AnimateInput();
        final Pose pose = new Pose();
        final Animator.Mix mix = new Animator.Mix();
        /** Анимации техник (через {@link PlayerAnimations#play}). */
        final List<AnimationTrack> actions = new ArrayList<>();
        /** Итоговая поза костей для отрисовки и матрицы костей (пространство модели GeckoLib). */
        final R15Rig.FinalPose finalPose = new R15Rig.FinalPose();
        Matrix4f[] boneMatrices = new Matrix4f[0];
        /** Суставы последней отрисовки (пространство модели, блоки) и номер того кадра. */
        org.joml.Vector3f[] joints = new org.joml.Vector3f[0];
        long drawnFrame = -100;
        long evaluated = -1;

        // скорость (блоки/с) по тикам и её сглаживание
        boolean hasPos;
        double lx, ly, lz;
        double vx, vy, vz, vxo, vyo, vzo;
        float lastBodyYaw;
        boolean hasYaw;
        double turn, turnO, fwd, accel, accelO;
        // взмах рукой
        boolean wasSwinging;
        int lastSwingTime;
        boolean swingPending, swingMain = true;

        State(AbstractClientPlayer p) {
            this.player = new WeakReference<>(p);
            animator.markerReached.connect(e -> {
                AbstractClientPlayer pl = player.get();
                if (pl != null) MARKERS.fire(new MarkerHit(pl, e.track(), e.marker()));
            });
            animator.soundCue.connect(this::onSound);
            animator.particleCue.connect(this::onParticle);
        }

        void tick(AbstractClientPlayer p) {
            vxo = vx;
            vyo = vy;
            vzo = vz;
            double x = p.getX(), y = p.getY(), z = p.getZ();
            if (hasPos) {
                double dx = x - lx, dy = y - ly, dz = z - lz;
                if (dx * dx + dy * dy + dz * dz > 64) dx = dy = dz = 0; // телепорт
                vx += (dx * 20 - vx) * 0.5;
                vy += (dy * 20 - vy) * 0.6;
                vz += (dz * 20 - vz) * 0.5;
            }
            lx = x;
            ly = y;
            lz = z;
            hasPos = true;
            turnO = turn;
            accelO = accel;
            faceLook(p);
            float by = p.yBodyRot;
            if (hasYaw) turn += (Mth.wrapDegrees(by - lastBodyYaw) * 20 - turn) * 0.4;
            lastBodyYaw = by;
            hasYaw = true;
            double yr = Math.toRadians(by);
            double f = -Math.sin(yr) * vx + Math.cos(yr) * vz;
            accel += ((f - fwd) * 20 - accel) * 0.3;
            fwd = f;
            // новый взмах: начался или начался заново, не доиграв
            if (p.swinging && (!wasSwinging || p.swingTime < lastSwingTime)) {
                swingPending = true;
                swingMain = p.swingingArm != InteractionHand.OFF_HAND;
            }
            wasSwinging = p.swinging;
            lastSwingTime = p.swingTime;
        }

        /**
         * Пока идёт техника, тело доворачивается к взгляду: иначе ванильное тело отстаёт от головы до 50–75°,
         * и сзади кажется, что персонаж бьёт вбок.
         */
        private void faceLook(AbstractClientPlayer p) {
            double w = 0;
            for (AnimationTrack t : actions) {
                if (t.isPlaying()) w = Math.max(w, t.clip.face * Math.min(1, t.getWeightCurrent()));
            }
            if (w <= 1.0E-3) return;
            float diff = Mth.wrapDegrees(p.yHeadRot - p.yBodyRot);
            p.yBodyRot += diff * (float) (0.6 * w);
        }

        void frame(AbstractClientPlayer p, float pt, double dt) {
            fill(p, pt, dt);
            animate.update(in);
            animator.step(dt);
        }

        private void fill(AbstractClientPlayer p, float pt, double dt) {
            AnimateInput in = this.in;
            in.dt = dt;
            double x = Mth.lerp(pt, vxo, vx), z = Mth.lerp(pt, vzo, vz);
            in.speed = Math.sqrt(x * x + z * z);
            double yr = Math.toRadians(Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
            double along = -Math.sin(yr) * x + Math.cos(yr) * z;
            in.forward = in.speed > 1.0E-3 ? Mth.clamp(along / in.speed, -1, 1) : 1;
            in.vy = Mth.lerp(pt, vyo, vy);
            in.turnRate = Mth.lerp(pt, turnO, turn);
            in.accel = Mth.lerp(pt, accelO, accel);
            in.onGround = p.onGround();
            in.crouching = p.isCrouching();
            in.sprinting = p.isSprinting();
            in.swimming = p.isVisuallySwimming();
            in.inWater = p.isInWater();
            in.climbing = p.onClimbable() && !p.onGround();
            in.riding = p.isPassenger();
            in.fallFlying = p.isFallFlying();
            in.sleeping = p.isSleeping();
            in.spinAttack = p.isAutoSpinAttack();
            in.dead = p.isDeadOrDying();
            MovementFxClient.fillAnimInput(p, pt, in);

            in.leftHanded = p.getMainArm() == HumanoidArm.LEFT;
            ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
            in.mainHandItem = !main.isEmpty();
            in.offHandItem = !off.isEmpty();
            in.swingStarted = swingPending;
            in.swingMain = swingMain;
            swingPending = false;
            in.use = AnimateInput.Use.NONE;
            if (p.isUsingItem()) {
                UseAnim ua = p.getUseItem().getUseAnimation();
                in.use = switch (ua) {
                    case EAT -> AnimateInput.Use.EAT;
                    case DRINK -> AnimateInput.Use.DRINK;
                    case BLOCK -> AnimateInput.Use.BLOCK;
                    case BOW -> AnimateInput.Use.BOW;
                    case SPEAR -> AnimateInput.Use.SPEAR;
                    case CROSSBOW -> AnimateInput.Use.CROSSBOW;
                    case SPYGLASS -> AnimateInput.Use.SPYGLASS;
                    case TOOT_HORN -> AnimateInput.Use.HORN;
                    case BRUSH -> AnimateInput.Use.BRUSH;
                    default -> AnimateInput.Use.NONE;
                };
                in.useMain = p.getUsedItemHand() == InteractionHand.MAIN_HAND;
            }
            boolean cm = main.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(main);
            boolean co = off.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(off);
            in.crossbowCharged = cm || co;
            in.crossbowMain = cm;
        }

        /** Смешать треки (один раз за кадр). */
        void evaluate() {
            long f = frame;
            if (evaluated == f) return;
            evaluated = f;
            animator.evaluate(pose, mix);
        }

        public Animator.Mix mix() {
            evaluate();
            return mix;
        }

        /**
         * Итоговая поза: смесь треков + взгляд (голова за камерой, плечи за прицелом) + наклоны тела.
         * netYaw и pitch — градусы, как в ванильной модели (поворот головы относительно тела).
         */
        void solve(double netYawDeg, double pitchDeg) {
            evaluate();
            int n = Bones.count();
            R15Rig.FinalPose out = finalPose;
            out.reset();
            pose.ensure(n);
            double[][] q = new double[n][];
            for (int i = 0; i < n; i++) {
                q[i] = new double[4];
                if (pose.has(i, Pose.ROT)) QMath.copy(pose.rot[i], q[i]);
                else QMath.identity(q[i]);
            }
            int lt = Bones.find(Bones.LOWER_TORSO), ut = Bones.find(Bones.UPPER_TORSO), hd = Bones.find(Bones.HEAD);
            int ra = Bones.find(Bones.R_UPPER_ARM), la = Bones.find(Bones.L_UPPER_ARM);
            // поворот корпуса в пространстве модели (родитель головы и плеч)
            double[] parent = new double[4];
            QMath.mul(q[lt], q[ut], parent);
            double[] parentInv = {-parent[0], -parent[1], -parent[2], parent[3]};
            // взгляд в соглашении GeoBone: X и Y со знаком минус относительно ванили
            double yaw = Math.toRadians(Mth.clamp(netYawDeg, -180, 180)), pitch = Math.toRadians(pitchDeg);
            double[] look = new double[4];
            QMath.fromEulerZYX(-pitch, -yaw, 0, look);
            double[] tmp = new double[4], anim = new double[4], target = new double[4];

            double lw = AnimateScript.clamp(mix.look, 0, 1);
            if (lw > 1.0E-3) {
                QMath.mul(parent, q[hd], anim);       // голова по анимации, в пространстве модели
                // голова смотрит, куда игрок, поверх позы клипа в пространстве модели: если клип развернул
                // корпус и довернул голову назад к цели, взгляд не уезжает вбок на угол разворота
                QMath.mul(look, anim, target);
                QMath.slerp(anim, target, lw, tmp);
                QMath.mul(parentInv, tmp, q[hd]);
            }
            aim(q, ra, parent, parentInv, look, mix.aimRight, anim, target, tmp);
            aim(q, la, parent, parentInv, look, mix.aimLeft, anim, target, tmp);

            double[] e = new double[3];
            for (int i = 0; i < n; i++) {
                QMath.toEulerZYX(q[i], e);
                out.rot[i][0] = e[0];
                out.rot[i][1] = e[1];
                out.rot[i][2] = e[2];
                if (pose.has(i, Pose.POS)) System.arraycopy(pose.pos[i], 0, out.pos[i], 0, 3);
                if (pose.has(i, Pose.SCALE)) System.arraycopy(pose.scale[i], 0, out.scale[i], 0, 3);
            }
            // наклоны всего тела вокруг ступней (GeoBone: X со знаком минус; крен вправо — поворот вокруг Z на минус)
            QMath.fromEulerZYX(-Math.toRadians(animate.leanPitch), 0, -Math.toRadians(animate.leanRoll), out.rootRot);
            // точка наклона: у ступней на земле, у таза в полёте — сдвиг, чтобы поворот шёл вокруг (0, h, 0)
            double h = animate.leanPivot / 16.0;
            if (h > 1.0E-4) {
                double[] r = out.rootRot, pv = {0, h, 0, 0}, t1 = new double[4], t2 = new double[4];
                QMath.mul(r, pv, t1);
                QMath.mul(t1, new double[]{-r[0], -r[1], -r[2], r[3]}, t2);
                out.rootPos[0] = -t2[0];
                out.rootPos[1] = h - t2[1];
                out.rootPos[2] = -t2[2];
            }
        }

        private static void aim(double[][] q, int arm, double[] parent, double[] parentInv, double[] look, double w,
                                double[] anim, double[] target, double[] tmp) {
            if (arm < 0 || w <= 1.0E-3) return;
            QMath.mul(parent, q[arm], anim);
            QMath.mul(look, anim, target);
            QMath.slerp(anim, target, AnimateScript.clamp(w, 0, 1), tmp);
            QMath.mul(parentInv, tmp, q[arm]);
        }

        /** Матрицы костей последней отрисовки (пространство модели GeckoLib; null — кость не найдена). */
        public Matrix4f boneMatrix(String bone) {
            int i = Bones.find(bone);
            return i >= 0 && i < boneMatrices.length ? boneMatrices[i] : null;
        }

        // ---------------------------------------------------------- звуки и частицы из Blockbench

        private void onSound(Animator.SoundEvent e) {
            AbstractClientPlayer p = player.get();
            Minecraft mc = Minecraft.getInstance();
            if (p == null || mc.level == null || e.track().getWeightCurrent() < 0.3) return;
            ResourceLocation id = ResourceLocation.tryParse(e.cue().sound().trim().toLowerCase(Locale.ROOT));
            if (id == null) return;
            SoundEvent snd = BuiltInRegistries.SOUND_EVENT.get(id);
            if (snd == null) snd = SoundEvent.createVariableRangeEvent(id);
            mc.level.playLocalSound(p.getX(), p.getY() + 1.0, p.getZ(), snd, SoundSource.PLAYERS, 1.0f, 1.0f, false);
        }

        private void onParticle(Animator.ParticleEvent e) {
            AbstractClientPlayer p = player.get();
            Minecraft mc = Minecraft.getInstance();
            if (p == null || mc.level == null || e.track().getWeightCurrent() < 0.3) return;
            ResourceLocation id = ResourceLocation.tryParse(e.cue().effect().trim().toLowerCase(Locale.ROOT));
            ParticleType<?> type = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.get(id);
            if (!(type instanceof ParticleOptions opt)) return;
            double[] at = worldPoint(p, e.cue().locator());
            mc.level.addParticle(opt, at[0], at[1], at[2], 0, 0, 0);
        }

        /** Точка кости (локатор — имя кости) в мире; без кости — грудь игрока. */
        double[] worldPoint(AbstractClientPlayer p, String bone) {
            Matrix4f m = bone == null || bone.isEmpty() ? null : boneMatrix(bone);
            if (m == null) return new double[]{p.getX(), p.getY() + 1.2, p.getZ()};
            Vector4f v = m.transform(new Vector4f(0, 0, 0, 1));
            // модель GeckoLib: лицом к −Z; в мире тело повёрнуто на 180 − yBodyRot и уменьшено до 0.9375
            double yr = Math.toRadians(180 - p.yBodyRot);
            double s = 0.9375, cx = v.x * s, cz = v.z * s;
            double wx = cx * Math.cos(yr) + cz * Math.sin(yr);
            double wz = -cx * Math.sin(yr) + cz * Math.cos(yr);
            return new double[]{p.getX() + wx, p.getY() + v.y * s, p.getZ() + wz};
        }
    }
}
