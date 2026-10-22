package com.kira.jujutsuneon;

import com.kira.jujutsuneon.anim.AnimMath;
import com.kira.jujutsuneon.anim.Animator;
import com.kira.jujutsuneon.anim.Clip;
import com.kira.jujutsuneon.anim.ClipLoader;
import com.kira.jujutsuneon.anim.Joint;
import com.kira.jujutsuneon.anim.Locomotion;
import com.kira.jujutsuneon.anim.Pose;
import com.kira.jujutsuneon.anim.Rig;
import com.mojang.logging.LogUtils;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * Движок анимаций тела (клиент). У каждого игрока свой аниматор: снизу — движение (в повязке
 * Годжо: стойка, шаг, бег, прыжки, полёт, дэши), сверху — клипы техник. Поза считается в каждом
 * кадре отрисовки по точному времени (тики + доля тика), смены анимаций всегда плавные.
 * В Player Animator это один слой: части модели ставятся по скелету с иерархией (таз → корпус →
 * голова и руки), так что руки и голова всегда держатся за корпус.
 *
 * Откуда клипы:
 *  - из мода: assets/jujutsu_neon/body_anims/**.json (формат jn_anim) и player_animation/*.json
 *    (старые анимации Player Animator — переводятся на лету); перечитываются с ресурсами (F3+T);
 *  - из папки config/jujutsu_neon/animations/**.json — файл подхватывается через секунду после
 *    сохранения; файл с тем же именем заменяет встроенный.
 *
 * Из кода:  BodyAnims.play(player, "red_cast");  BodyAnims.stop(player, 0.25);
 * С сервера: BodyAnimNet.play(serverPlayer, "имя").  В игре: /jnanim.
 * Фиолетовый (max_purple) идёт отдельно — MaximumPurpleAnimation, слоем выше.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BodyAnims {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Выше старых слоёв движения, ниже Фиолетового (2000): его кат-сцена перекрывает наши позы. */
    static final int LAYER_PRIORITY = 1500;
    /** Папки ресурсов с клипами; одноимённый клип из более поздней папки заменяет ранний. */
    private static final String[] RESOURCE_DIRS = {"player_animation", "body_anims"};

    private static final Map<String, Clip> BUILTIN = new TreeMap<>();
    private static final Map<String, String> BUILTIN_SOURCE = new HashMap<>();
    private static final Map<String, Clip> EXTERNAL = new TreeMap<>();
    private static final Map<String, String> EXTERNAL_SOURCE = new HashMap<>();
    private static final Map<Path, Long> EXTERNAL_STAMPS = new HashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static final Map<AbstractClientPlayer, State> STATES = new WeakHashMap<>();
    private static double debugSpeed = 1;
    private static int watchTimer;
    private static boolean externalLoaded;
    private static boolean builtinLoaded;

    private BodyAnims() {
    }

    // ================================================================== реестр

    public static Clip clip(String name) {
        if (name == null) return null;
        ensureLoaded();
        String k = name.toLowerCase(Locale.ROOT);
        Clip c = EXTERNAL.get(k);
        return c != null ? c : BUILTIN.get(k);
    }

    public static Collection<String> names() {
        ensureLoaded();
        TreeMap<String, Clip> all = new TreeMap<>(BUILTIN);
        all.putAll(EXTERNAL);
        return all.keySet();
    }

    static String source(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        String s = EXTERNAL_SOURCE.get(k);
        return s != null ? s : BUILTIN_SOURCE.getOrDefault(k, "?");
    }

    static List<String> errors() {
        return ERRORS;
    }

    static double debugSpeed() {
        return debugSpeed;
    }

    static void setDebugSpeed(double s) {
        debugSpeed = Mth.clamp(s, 0.01, 10.0);
    }

    // ================================================================== проигрывание

    /** Запустить клип на слое действий (с плавным переходом из текущей позы). */
    public static boolean play(AbstractClientPlayer player, String name) {
        return play(player, name, new Animator.Play());
    }

    public static boolean play(AbstractClientPlayer player, String name, Animator.Play opts) {
        Clip c = clip(name);
        if (c == null) {
            LOGGER.debug("Body animation {} not found", name);
            return false;
        }
        return play(player, Animator.LAYER_ACTION, c, opts);
    }

    public static boolean play(AbstractClientPlayer player, int layer, Clip clip, Animator.Play opts) {
        State s = state(player);
        if (s == null || clip == null) return false;
        s.animator.play(layer, clip, opts, s.now(0));
        return true;
    }

    /** Плавно остановить клип на слое действий за fade секунд (NaN — как задано в клипе). */
    public static void stop(AbstractClientPlayer player, double fade) {
        stop(player, Animator.LAYER_ACTION, fade);
    }

    public static void stop(AbstractClientPlayer player, int layer, double fade) {
        State s = player == null ? null : STATES.get(player);
        if (s != null) s.animator.stop(layer, fade, s.now(0));
    }

    /** Играет ли клип name (или любой, если name == null) на слое действий. */
    public static boolean isPlaying(AbstractClientPlayer player, String name) {
        State s = player == null ? null : STATES.get(player);
        if (s == null || !s.animator.isPlaying(Animator.LAYER_ACTION)) return false;
        Clip c = s.animator.clip(Animator.LAYER_ACTION);
        return name == null || (c != null && c.name.equalsIgnoreCase(name));
    }

    /** Для отладки: аниматор игрока и его текущее время. */
    static Animator animator(AbstractClientPlayer player) {
        State s = state(player);
        return s == null ? null : s.animator;
    }

    static double now(AbstractClientPlayer player) {
        State s = player == null ? null : STATES.get(player);
        return s == null ? 0 : s.now(0);
    }

    /** Пакет с сервера: запустить/остановить клип у игрока. */
    static void onNet(int entityId, String name, float speed, boolean mirror, float startAt, byte loop, boolean stop, float fade) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(entityId) instanceof AbstractClientPlayer p)) return;
        if (stop) {
            stop(p, fade < 0 ? Double.NaN : fade);
            return;
        }
        Animator.Play o = new Animator.Play().speed(speed).mirror(mirror).startAt(startAt)
                .loop(loop < 0 ? null : loop == 1);
        play(p, name, o);
    }

    private static State state(AbstractClientPlayer player) {
        if (player == null) return null;
        State s = STATES.get(player);
        if (s != null) return s;
        s = new State(player);
        try {
            PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(LAYER_PRIORITY, s);
        } catch (RuntimeException | LinkageError e) {
            // Player Animator недоступен — анимаций тела не будет, но и падать не из-за чего.
            LOGGER.warn("Body animation layer failed: {}", e.toString());
        }
        STATES.put(player, s);
        return s;
    }

    // ================================================================== тики

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
        if (externalLoaded && externalChanged()) {
            int n = reloadExternal();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Анимации обновлены из папки: " + n
                        + (ERRORS.isEmpty() ? "" : " (ошибок: " + ERRORS.size() + ", /jnanim errors)")), true);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
        debugSpeed = 1;
    }

    // ================================================================== загрузка

    private static void ensureLoaded() {
        if (!builtinLoaded) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getResourceManager() != null) reloadBuiltin(mc.getResourceManager());
        }
        if (!externalLoaded) reloadExternal();
    }

    /** Встроенные клипы из ресурсов (вход в игру, F3+T). */
    static void reloadBuiltin(ResourceManager rm) {
        builtinLoaded = true;
        BUILTIN.clear();
        BUILTIN_SOURCE.clear();
        ERRORS.removeIf(s -> s.startsWith("[мод]"));
        for (String dir : RESOURCE_DIRS) {
            Map<ResourceLocation, Resource> found = rm.listResources(dir, loc -> loc.getPath().endsWith(".json"));
            for (Map.Entry<ResourceLocation, Resource> en : found.entrySet()) {
                ResourceLocation loc = en.getKey();
                String path = loc.getPath();
                String name = path.substring(dir.length() + 1, path.length() - 5).toLowerCase(Locale.ROOT);
                try (Reader r = en.getValue().openAsReader()) {
                    BUILTIN.put(name, ClipLoader.load(name, r));
                    BUILTIN_SOURCE.put(name, loc.toString());
                } catch (Exception e) {
                    ERRORS.add("[мод] " + loc + ": " + e.getMessage());
                    LOGGER.warn("Body animation {} failed: {}", loc, e.toString());
                }
            }
        }
        LOGGER.info("Loaded {} built-in body animations", BUILTIN.size());
    }

    static Path externalDir() {
        return FMLPaths.CONFIGDIR.get().resolve("jujutsu_neon").resolve("animations");
    }

    /** Клипы из config/jujutsu_neon/animations (и подпапок). Возвращает их число. */
    static int reloadExternal() {
        externalLoaded = true;
        EXTERNAL.clear();
        EXTERNAL_SOURCE.clear();
        EXTERNAL_STAMPS.clear();
        ERRORS.removeIf(s -> s.startsWith("[папка]"));
        Path dir = externalDir();
        try {
            Files.createDirectories(dir);
            writeReadmeOnce(dir);
        } catch (IOException ignored) {
        }
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json")).forEach(p -> {
                try {
                    EXTERNAL_STAMPS.put(p, Files.getLastModifiedTime(p).toMillis());
                    String file = p.getFileName().toString();
                    String name = file.substring(0, file.length() - 5).toLowerCase(Locale.ROOT);
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    EXTERNAL.put(name, ClipLoader.load(name, new StringReader(text)));
                    EXTERNAL_SOURCE.put(name, p.toString());
                } catch (Exception e) {
                    ERRORS.add("[папка] " + dir.relativize(p) + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            ERRORS.add("[папка] " + e.getMessage());
        }
        return EXTERNAL.size();
    }

    private static boolean externalChanged() {
        Path dir = externalDir();
        if (!Files.isDirectory(dir)) return false;
        Map<Path, Long> now = new HashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json")).forEach(p -> {
                try {
                    now.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            return false;
        }
        return !now.equals(EXTERNAL_STAMPS);
    }

    private static void writeReadmeOnce(Path dir) throws IOException {
        Path readme = dir.resolve("README.txt");
        if (Files.exists(readme)) return;
        Files.writeString(readme, String.join("\n",
                "Анимации тела Jujutsu Neon.",
                "",
                "Положите сюда .json — анимация подхватится в игре через секунду после сохранения файла.",
                "Файл с тем же именем, что и встроенная анимация, заменяет её (например, loco_run.json — бег).",
                "",
                "Формат jn_anim (ключи и плавные кривые) и старые анимации Player Animator. Подробно —",
                "docs/ANIMATION.md в репозитории мода.",
                "",
                "В игре: /jnanim list, /jnanim play <имя> [скорость], /jnanim loop <имя>, /jnanim mirror <имя>,",
                "/jnanim pose <имя> <тик> (стоп-кадр), /jnanim step <±тики>, /jnanim speed <x>, /jnanim stop,",
                "/jnanim info <имя>, /jnanim reload, /jnanim errors.",
                ""), StandardCharsets.UTF_8);
    }

    /** Встроенные клипы перечитываются вместе с ресурсами (вход в мир, F3+T). */
    @Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientSetup {
        private ClientSetup() {
        }

        @SubscribeEvent
        public static void onReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) BodyAnims::reloadBuiltin);
        }
    }

    // ================================================================== игрок

    /** Аниматор одного игрока и его слой в Player Animator. */
    private static final class State implements IAnimation {
        final WeakReference<AbstractClientPlayer> player;
        final Animator animator = new Animator();
        final Locomotion loco = new Locomotion(BodyAnims::clip);
        final Locomotion.Input in = new Locomotion.Input();
        final Pose locoPose = new Pose(), base = new Pose(), pose = new Pose();
        final Rig rig = new Rig();
        final Rig.Output out = new Rig.Output();
        final Animator.FirstPersonView fp = new Animator.FirstPersonView();
        final double[] van = new double[3], res = new double[3];

        /** Часы аниматора, секунды (идут по тикам; /jnanim speed замедляет всё сразу). */
        double clock;
        boolean hasPos;
        double lx, ly, lz;
        double vx, vy, vz, vxo, vyo, vzo;
        float lastBodyYaw;
        boolean hasYaw;
        double turn, turnO, fwd, accel, accelO;
        boolean locoTarget;
        double locoW, armR, armL;
        long lastNanos = -1;
        boolean computed;
        double crouchLift;

        State(AbstractClientPlayer p) {
            this.player = new WeakReference<>(p);
        }

        double now(float pt) {
            return clock + pt * 0.05 * debugSpeed;
        }

        void tick(AbstractClientPlayer p) {
            clock += 0.05 * debugSpeed;
            // скорость (блоки/с), сглаженная по тикам; между тиками — интерполяция
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
            // поворот тела (градусы/с, +вправо) и разгон вперёд (блоки/с²)
            turnO = turn;
            accelO = accel;
            float by = p.yBodyRot;
            if (hasYaw) turn += (Mth.wrapDegrees(by - lastBodyYaw) * 20 - turn) * 0.4;
            lastBodyYaw = by;
            hasYaw = true;
            double yr = Math.toRadians(by);
            double f = -Math.sin(yr) * vx + Math.cos(yr) * vz;
            accel += ((f - fwd) * 20 - accel) * 0.3;
            fwd = f;
            locoTarget = wantsLocomotion(p);
        }

        @Override
        public boolean isActive() {
            return computed || locoTarget || animator.anyActive();
        }

        @Override
        public void tick() {
            // время идёт в onClientTick (и когда слой не активен)
        }

        @Override
        public void setupAnim(float pt) {
            computed = false;
            AbstractClientPlayer p = player.get();
            if (p == null) return;
            Minecraft mc = Minecraft.getInstance();
            long n = System.nanoTime();
            double dt = lastNanos < 0 ? 0 : (n - lastNanos) / 1.0E9;
            lastNanos = n;
            if (mc.isPaused()) dt = 0;
            dt = Math.min(dt, 0.25) * debugSpeed;
            double now = now(pt);

            locoW = AnimMath.approach(locoW, locoTarget ? 1 : 0, 7, dt);
            if (!locoTarget && locoW < 1.0E-3) locoW = 0;
            if (locoTarget && locoW > 0.999) locoW = 1;
            boolean[] arms = vanillaArms(p);
            armR = AnimMath.approach(armR, arms[0] ? 1 : 0, arms[0] ? 22 : 9, dt);
            armL = AnimMath.approach(armL, arms[1] ? 1 : 0, arms[1] ? 22 : 9, dt);

            base.setVanilla();
            double look = 0, follow = 0, rootComp = 0;
            if (locoW > 0) {
                fillInput(p, pt, dt);
                loco.evaluate(in, locoPose);
                base.blendTowards(locoPose, locoW);
                look = loco.look * locoW;
                follow = loco.follow * locoW;
                rootComp = loco.rootComp * locoW;
            } else {
                in.active = false;
                loco.evaluate(in, locoPose);
            }
            if (locoW <= 0 && !animator.anyActive()) return;
            animator.evaluate(now, base, look, follow, pose);

            float bodyYaw = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
            float headYaw = Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot);
            double netYaw = Math.toRadians(Mth.wrapDegrees(headYaw - bodyYaw));
            double pitch = Math.toRadians(Mth.lerp(pt, p.xRotO, p.getXRot()));
            Animator.applyLook(pose, netYaw, pitch, animator.lookWeight, animator.followWeight, rootComp);
            rig.solve(pose, "slim".equals(p.getModelName()), out);
            // присевшего игрока ванильный рендер опускает на 1/8 блока — наша поза сама ставит ноги на землю
            crouchLift = p.isCrouching() ? 0.125 * (1 - AnimMath.clamp01(pose.vanilla[Joint.ROOT.ordinal()])) : 0;
            computed = true;
        }

        private void fillInput(AbstractClientPlayer p, float pt, double dt) {
            in.active = true;
            in.dt = dt;
            double x = Mth.lerp(pt, vxo, vx), z = Mth.lerp(pt, vzo, vz);
            in.speed = Math.sqrt(x * x + z * z);
            double yr = Math.toRadians(Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
            double along = -Math.sin(yr) * x + Math.cos(yr) * z;
            in.backwards = in.speed > 0.3 && along < -0.25 * in.speed;
            in.vy = Mth.lerp(pt, vyo, vy);
            in.onGround = p.onGround();
            in.sneaking = p.isCrouching();
            in.turnRate = Mth.lerp(pt, turnO, turn);
            in.accel = Mth.lerp(pt, accelO, accel);
            MovementFxClient.fillAnimInput(p, pt, in);
            in.rightArmVanilla = armR;
            in.leftArmVanilla = armL;
        }

        @Override
        public Vec3f get3DTransform(String part, TransformType type, float tickDelta, Vec3f v0) {
            if (!computed) return v0;
            if (part.equals("body")) {
                double vr = AnimMath.clamp01(pose.vanilla[Joint.ROOT.ordinal()]);
                if (type == TransformType.POSITION) {
                    return new Vec3f(
                            (float) (out.rootPos[0] + (v0.getX() - out.rootPos[0]) * vr),
                            (float) (out.rootPos[1] + crouchLift + (v0.getY() - out.rootPos[1]) * vr),
                            (float) (out.rootPos[2] + (v0.getZ() - out.rootPos[2]) * vr));
                }
                if (type == TransformType.ROTATION) {
                    van[0] = v0.getX();
                    van[1] = v0.getY();
                    van[2] = v0.getZ();
                    rig.blendRootRotation(pose, out, van, res);
                    return new Vec3f((float) res[0], (float) res[1], (float) res[2]);
                }
                return v0;
            }
            Joint j = Joint.byPartName(part);
            if (j == null || j == Joint.ROOT) return v0;
            switch (type) {
                case POSITION -> {
                    van[0] = v0.getX();
                    van[1] = v0.getY();
                    van[2] = v0.getZ();
                    rig.blendPivot(pose, out, j, van, res);
                    return new Vec3f((float) res[0], (float) res[1], (float) res[2]);
                }
                case ROTATION -> {
                    van[0] = v0.getX();
                    van[1] = v0.getY();
                    van[2] = v0.getZ();
                    rig.blendRotation(pose, out, j, van, res);
                    return new Vec3f((float) res[0], (float) res[1], (float) res[2]);
                }
                case BEND -> {
                    double vj = AnimMath.clamp01(pose.vanilla[j.ordinal()]);
                    float bend = (float) (Rig.blendBend(pose, out, j) + v0.getY() * vj);
                    float axis = (float) (out.parts[j.ordinal()].bendAxis * (1 - vj) + v0.getX() * vj);
                    return new Vec3f(axis, bend, v0.getZ());
                }
                default -> {
                    return v0;
                }
            }
        }

        @Override
        public FirstPersonMode getFirstPersonMode(float tickDelta) {
            if (!animator.firstPerson(now(tickDelta), fp)) return FirstPersonMode.NONE;
            return switch (fp.mode) {
                case MODEL -> FirstPersonMode.THIRD_PERSON_MODEL;
                case VANILLA -> FirstPersonMode.VANILLA;
                default -> FirstPersonMode.NONE;
            };
        }

        @Override
        public FirstPersonConfiguration getFirstPersonConfiguration(float tickDelta) {
            animator.firstPerson(now(tickDelta), fp);
            return new FirstPersonConfiguration(fp.rightArm, fp.leftArm, fp.rightArm, fp.leftArm);
        }
    }

    // ================================================================== когда двигаемся «по-своему»

    /** Наше движение — в повязке Годжо (или во время дэша, полёта, взлёта); ваниль — в лодке, во сне, в воде, на элитрах… */
    private static boolean wantsLocomotion(AbstractClientPlayer p) {
        if (p.isSpectator() || p.isPassenger() || p.isSleeping() || p.isDeadOrDying()) return false;
        if (p.isVisuallySwimming() || p.isFallFlying() || p.isAutoSpinAttack()) return false;
        if (p.isInWater() && !p.onGround()) return false;
        if (p.onClimbable() && !p.onGround()) return false;
        if (p.getPose() != net.minecraft.world.entity.Pose.STANDING && p.getPose() != net.minecraft.world.entity.Pose.CROUCHING) {
            return false;
        }
        return p.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get()) || MovementFxClient.animating(p);
    }

    /** Какие руки сейчас нужны ванили: {правая, левая}. Взмах, щит, лук, еда, заряженный арбалет. */
    private static boolean[] vanillaArms(AbstractClientPlayer p) {
        boolean right = false, left = false;
        HumanoidArm main = p.getMainArm();
        if (p.swinging) {
            boolean mainSwing = p.swingingArm == InteractionHand.MAIN_HAND;
            if ((main == HumanoidArm.RIGHT) == mainSwing) right = true;
            else left = true;
        }
        if (p.isUsingItem()) {
            ItemStack use = p.getUseItem();
            UseAnim anim = use.getUseAnimation();
            if (anim == UseAnim.BOW || anim == UseAnim.CROSSBOW || anim == UseAnim.SPEAR) {
                right = left = true;
            } else if (anim != UseAnim.NONE) {
                boolean mainUse = p.getUsedItemHand() == InteractionHand.MAIN_HAND;
                if ((main == HumanoidArm.RIGHT) == mainUse) right = true;
                else left = true;
            }
        }
        if (CrossbowItem.isCharged(p.getMainHandItem()) || CrossbowItem.isCharged(p.getOffhandItem())) {
            right = left = true;
        }
        return new boolean[]{right, left};
    }
}
