package com.kira.jujutsuneon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Покадровые анимации — реестр и API (клиент).
 *
 * Откуда берутся:
 *  - из мода: assets/jujutsu_neon/frame_animations/**.json (перезагружаются вместе с ресурсами, F3+T);
 *  - из папки config/jujutsu_neon/animations/**.json — подхватываются на лету: сохранили файл —
 *    через секунду новая версия уже в игре (удобно править покадрово, не пересобирая мод).
 *    Файл из папки с тем же именем заменяет встроенный.
 *
 * Как запустить из кода:  FrameAnimations.play(player, "имя");  с сервера — FrameAnimNet.play(serverPlayer, "имя").
 * Как посмотреть в игре:  /jnanim play имя, /jnanim frame имя 12 (стоп-кадр), /jnanim step 1, /jnanim speed 0.1 …
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FrameAnimations {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Выше техник (2000) и движения (1000): покадровая анимация перекрывает их на тех частях тела, что задаёт. */
    static final int LAYER_PRIORITY = 3000;
    static final String RESOURCE_DIR = "frame_animations";

    private static final Map<String, FrameAnimation> BUILTIN = new TreeMap<>();
    private static final Map<String, FrameAnimation> EXTERNAL = new TreeMap<>();
    private static final Map<Path, Long> EXTERNAL_STAMPS = new HashMap<>();
    private static final Map<AbstractClientPlayer, ModifierLayer<IAnimation>> LAYERS = new WeakHashMap<>();
    private static final List<EventListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static float debugSpeed = 1f;
    private static int watchTimer;
    private static boolean externalLoaded;

    /** Слушатель событий кадров ("events" в файле): например, вспышка ровно на кадре удара. */
    public interface EventListener {
        void onFrameEvent(Entity entity, FrameAnimation animation, FrameAnimation.Event event);
    }

    /** Параметры запуска. */
    public static final class PlayOptions {
        float speed = 1f;
        boolean mirror;
        double startFrame;
        Boolean loop;
        /** Плавный вход, тики (-1 — из файла). */
        int fadeTicks = -1;
        double frozenFrame = Double.NaN;

        public PlayOptions speed(float s) {
            speed = s;
            return this;
        }

        public PlayOptions mirror(boolean m) {
            mirror = m;
            return this;
        }

        public PlayOptions startFrame(double f) {
            startFrame = f;
            return this;
        }

        public PlayOptions loop(Boolean l) {
            loop = l;
            return this;
        }

        public PlayOptions fadeTicks(int t) {
            fadeTicks = t;
            return this;
        }

        public PlayOptions freezeAt(double frame) {
            frozenFrame = frame;
            return this;
        }
    }

    private FrameAnimations() {
    }

    // ================================================================== реестр

    public static FrameAnimation get(String name) {
        if (name == null) return null;
        ensureExternal();
        String k = name.toLowerCase(Locale.ROOT);
        FrameAnimation a = EXTERNAL.get(k);
        return a != null ? a : BUILTIN.get(k);
    }

    public static Collection<String> names() {
        ensureExternal();
        TreeMap<String, FrameAnimation> all = new TreeMap<>(BUILTIN);
        all.putAll(EXTERNAL);
        return all.keySet();
    }

    static List<String> errors() {
        return ERRORS;
    }

    static float debugSpeed() {
        return debugSpeed;
    }

    static void setDebugSpeed(float s) {
        debugSpeed = Math.max(0.01f, Math.min(10f, s));
    }

    public static void addEventListener(EventListener l) {
        LISTENERS.add(l);
    }

    static void fireEvent(int entityId, FrameAnimation anim, FrameAnimation.Event ev) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level != null ? mc.level.getEntity(entityId) : null;
        for (EventListener l : LISTENERS) {
            try {
                l.onFrameEvent(e, anim, ev);
            } catch (RuntimeException ex) {
                LOGGER.warn("Frame event listener failed: {}", ex.toString());
            }
        }
    }

    // ================================================================== проигрывание

    public static FrameAnimationPlayer play(AbstractClientPlayer player, String name) {
        return play(player, name, new PlayOptions());
    }

    public static FrameAnimationPlayer play(AbstractClientPlayer player, String name, PlayOptions opts) {
        FrameAnimation a = get(name);
        if (a == null || player == null) return null;
        return play(player, a, opts);
    }

    public static FrameAnimationPlayer play(AbstractClientPlayer player, FrameAnimation a, PlayOptions opts) {
        if (player == null || a == null) return null;
        try {
            ModifierLayer<IAnimation> layer = layer(player);
            if (layer == null) return null;
            FrameAnimationPlayer p = new FrameAnimationPlayer(a, player.getId(), opts.speed, opts.mirror, opts.startFrame, opts.loop);
            p.frozenFrame = opts.frozenFrame;
            int fade = opts.fadeTicks >= 0 ? opts.fadeTicks : Math.round(a.fadeIn * 20f);
            if (fade <= 0) {
                layer.setAnimation(p);
            } else {
                layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(fade, Ease.INOUTSINE), p, true);
            }
            return p;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Frame animation {} failed to start: {}", a.name, e.toString());
            return null;
        }
    }

    /** Остановить с плавным выходом (секунды). */
    public static void stop(AbstractClientPlayer player, float fadeSeconds) {
        FrameAnimationPlayer p = current(player);
        if (p != null) p.stop(fadeSeconds);
    }

    public static FrameAnimationPlayer current(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = player == null ? null : LAYERS.get(player);
        if (layer == null) return null;
        IAnimation a = layer.getAnimation();
        return a instanceof FrameAnimationPlayer p && p.isActive() ? p : null;
    }

    public static boolean isPlaying(AbstractClientPlayer player) {
        return current(player) != null;
    }

    private static ModifierLayer<IAnimation> layer(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = LAYERS.get(player);
        if (layer != null) return layer;
        try {
            layer = new ModifierLayer<>();
            PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(LAYER_PRIORITY, layer);
            LAYERS.put(player, layer);
            return layer;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Пакет с сервера: запустить/остановить анимацию у игрока. */
    static void onNet(int entityId, String name, float speed, boolean mirror, float startFrame, byte loop, boolean stop, float fade) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(entityId) instanceof AbstractClientPlayer p)) return;
        if (stop) {
            stop(p, fade);
            return;
        }
        PlayOptions o = new PlayOptions().speed(speed).mirror(mirror).startFrame(startFrame)
                .loop(loop < 0 ? null : loop == 1);
        play(p, name, o);
    }

    // ================================================================== загрузка

    /** Встроенные анимации из ресурсов мода (вызывается при загрузке/перезагрузке ресурсов). */
    static void reloadBuiltin(ResourceManager rm) {
        BUILTIN.clear();
        ERRORS.removeIf(s -> s.startsWith("[мод]"));
        Map<ResourceLocation, Resource> found = rm.listResources(RESOURCE_DIR, loc -> loc.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> en : found.entrySet()) {
            ResourceLocation loc = en.getKey();
            String file = loc.getPath().substring(loc.getPath().lastIndexOf('/') + 1);
            String base = file.substring(0, file.length() - 5);
            try (Reader r = en.getValue().openAsReader()) {
                for (FrameAnimation a : FrameAnimationLoader.parse(base, loc.toString(), JsonParser.parseReader(r))) {
                    BUILTIN.put(a.name.toLowerCase(Locale.ROOT), a);
                }
            } catch (Exception e) {
                ERRORS.add("[мод] " + loc + ": " + e.getMessage());
                LOGGER.warn("Frame animation {} failed: {}", loc, e.toString());
            }
        }
        LOGGER.info("Loaded {} built-in frame animations", BUILTIN.size());
    }

    static Path externalDir() {
        return FMLPaths.CONFIGDIR.get().resolve("jujutsu_neon").resolve("animations");
    }

    private static void ensureExternal() {
        if (!externalLoaded) reloadExternal();
    }

    /** Анимации из config/jujutsu_neon/animations (и подпапок). Возвращает количество. */
    static int reloadExternal() {
        externalLoaded = true;
        EXTERNAL.clear();
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
                    String base = file.substring(0, file.length() - 5);
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    for (FrameAnimation a : FrameAnimationLoader.parse(base, p.toString(), JsonParser.parseString(text))) {
                        EXTERNAL.put(a.name.toLowerCase(Locale.ROOT), a);
                    }
                } catch (Exception e) {
                    ERRORS.add("[папка] " + dir.relativize(p) + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            ERRORS.add("[папка] " + e.getMessage());
        }
        return EXTERNAL.size();
    }

    /** Изменились ли файлы в папке (раз в секунду). */
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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++watchTimer < 20) return;
        watchTimer = 0;
        if (!externalLoaded) return;
        if (externalChanged()) {
            int n = reloadExternal();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Покадровые анимации обновлены из папки: " + n + (ERRORS.isEmpty() ? "" : " (ошибок: " + ERRORS.size() + ")")), true);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LAYERS.clear();
        debugSpeed = 1f;
    }

    // ================================================================== экспорт

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Записать анимацию в плотный покадровый формат (каждый кадр — все заданные каналы, градусы).
     * Так любую анимацию (ключи, Blockbench, старую эмоцию) можно открыть и править по кадрам.
     */
    static Path export(FrameAnimation a, String fileName) throws IOException {
        Path dir = externalDir().resolve("export");
        Files.createDirectories(dir);
        Path out = dir.resolve(fileName.endsWith(".json") ? fileName : fileName + ".json");
        Files.writeString(out, toJson(a), StandardCharsets.UTF_8);
        return out;
    }

    static String toJson(FrameAnimation a) {
        List<FrameChannel> chans = a.usedChannels();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"format\": \"jn_frames\",\n");
        sb.append("  \"name\": ").append(GSON.toJson(a.name)).append(",\n");
        sb.append("  \"fps\": ").append(fmt(a.fps)).append(",\n");
        sb.append("  \"loop\": ").append(a.loop).append(",\n");
        if (a.loop) sb.append("  \"loop_from\": ").append(a.loopFrom).append(",\n");
        if (a.holdLast) sb.append("  \"hold_last\": true,\n");
        sb.append("  \"interpolation\": \"").append(a.interp.name().toLowerCase(Locale.ROOT)).append("\",\n");
        sb.append("  \"fade_in\": ").append(fmt(a.fadeIn)).append(",\n");
        sb.append("  \"fade_out\": ").append(fmt(a.fadeOut)).append(",\n");
        sb.append("  \"head\": \"").append(a.headMode == FrameAnimation.HeadMode.ADDITIVE ? "additive" : "absolute").append("\",\n");
        if (a.firstPerson != FrameAnimation.FirstPerson.NONE) {
            sb.append("  \"first_person\": {\"mode\": \"").append(a.firstPerson == FrameAnimation.FirstPerson.MODEL ? "model" : "vanilla")
                    .append("\", \"right_arm\": ").append(a.fpRightArm).append(", \"left_arm\": ").append(a.fpLeftArm)
                    .append(", \"until\": ").append(a.fpUntil).append("},\n");
        }
        if (!a.events.isEmpty()) {
            JsonArray ev = new JsonArray();
            for (FrameAnimation.Event e : a.events) {
                JsonObject eo = new JsonObject();
                eo.addProperty("frame", e.frame);
                eo.addProperty("name", e.name);
                if (!e.data.isEmpty()) eo.addProperty("data", e.data);
                ev.add(eo);
            }
            sb.append("  \"events\": ").append(new Gson().toJson(ev)).append(",\n");
        }
        sb.append("  \"channels\": [");
        for (int i = 0; i < chans.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(chans.get(i).key).append('"');
        }
        sb.append("],\n");
        sb.append("  \"frames\": [\n");
        for (int f = 0; f < a.frameCount; f++) {
            sb.append("    [");
            for (int i = 0; i < chans.size(); i++) {
                if (i > 0) sb.append(", ");
                float v = a.values[f][chans.get(i).ordinal()];
                sb.append(Float.isNaN(v) ? "null" : fmt(chans.get(i).toFile(v)));
            }
            sb.append(f == a.frameCount - 1 ? "]\n" : "],\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 1.0E-4f) return Integer.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.3f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Старую эмоцию Player Animator (player_animation/имя.json) прочитать как покадровую. */
    static FrameAnimation fromEmote(String name) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation loc = new ResourceLocation(JujutsuNeonMod.MODID, "player_animation/" + name + ".json");
        var res = mc.getResourceManager().getResource(loc);
        if (res.isEmpty()) throw new IOException("нет такой эмоции: " + loc);
        try (Reader r = res.get().openAsReader()) {
            JsonElement root = JsonParser.parseReader(r);
            return FrameAnimationLoader.parse(name, loc.toString(), root).get(0);
        }
    }

    static List<String> emoteNames() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        for (ResourceLocation loc : mc.getResourceManager().listResources("player_animation", l -> l.getPath().endsWith(".json")).keySet()) {
            if (!loc.getNamespace().equals(JujutsuNeonMod.MODID)) continue;
            String p = loc.getPath();
            out.add(p.substring(p.lastIndexOf('/') + 1, p.length() - 5));
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void writeReadmeOnce(Path dir) throws IOException {
        Path readme = dir.resolve("README.txt");
        if (Files.exists(readme)) return;
        Files.writeString(readme, String.join("\n",
                "Покадровые анимации Jujutsu Neon.",
                "",
                "Положите сюда .json — анимация подхватится в игре через секунду после сохранения файла.",
                "Файл с тем же именем, что и встроенная анимация, заменяет её.",
                "",
                "Форматы: jn_frames (каждый кадр), jn_keys (ключи с кривыми), анимации Blockbench (Bedrock),",
                "эмоции Player Animator. Подробно — docs/ANIMATION.md в репозитории мода.",
                "",
                "В игре: /jnanim list, /jnanim play <имя> [скорость], /jnanim loop <имя>, /jnanim mirror <имя>,",
                "/jnanim frame <имя> <кадр> (стоп-кадр), /jnanim step <±кадры>, /jnanim speed <x>, /jnanim stop,",
                "/jnanim info <имя>, /jnanim export <имя>, /jnanim emote <эмоция>, /jnanim bake <эмоция>, /jnanim errors.",
                ""), StandardCharsets.UTF_8);
    }
}
