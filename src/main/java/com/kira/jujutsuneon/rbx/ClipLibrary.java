package com.kira.jujutsuneon.rbx;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.util.JsonUtil;

import java.io.IOException;
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
import java.util.stream.Stream;

/**
 * Все анимации игрока из Blockbench.
 *
 * Откуда:
 *  - ресурсы: {@code assets/<мод>/animations/player/*.json} — их загружает и запекает GeckoLib (F3+T перечитывает);
 *  - папка игры: {@code config/jujutsu_neon/animations/**.json} — тот же формат, подхватывается через секунду
 *    после сохранения; анимация с тем же именем заменяет встроенную.
 *
 * Имя анимации — её ключ в файле. Если ключ вида {@code animation.player.walk}, анимация доступна и как {@code walk}.
 */
public final class ClipLibrary {

    public static final String RESOURCE_DIR = "animations/player/";

    private static final Map<String, Clip> BUILTIN = new TreeMap<>();
    private static final Map<String, Clip> EXTERNAL = new TreeMap<>();
    private static final Map<Path, Long> STAMPS = new HashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static Map<ResourceLocation, BakedAnimations> lastCache;
    private static boolean externalLoaded;
    private static int generation;

    private ClipLibrary() {
    }

    public static Clip get(String name) {
        if (name == null) return null;
        refresh();
        String k = name.toLowerCase(Locale.ROOT);
        Clip c = EXTERNAL.get(k);
        return c != null ? c : BUILTIN.get(k);
    }

    public static Collection<String> names() {
        refresh();
        TreeMap<String, Clip> all = new TreeMap<>(BUILTIN);
        all.putAll(EXTERNAL);
        all.keySet().removeIf(n -> n.startsWith("animation."));
        return all.keySet();
    }

    public static List<String> errors() {
        return ERRORS;
    }

    /** Меняется при каждой перезагрузке (треки по старым анимациям можно пересоздать). */
    public static int generation() {
        refresh();
        return generation;
    }

    public static boolean isExternal(String name) {
        return EXTERNAL.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Перечитать встроенные, если GeckoLib перезагрузил ресурсы. */
    private static void refresh() {
        Map<ResourceLocation, BakedAnimations> cache;
        try {
            cache = GeckoLibCache.getBakedAnimations();
        } catch (RuntimeException e) {
            return;
        }
        if (cache != lastCache) {
            lastCache = cache;
            BUILTIN.clear();
            ERRORS.removeIf(s -> s.startsWith("[мод]"));
            for (Map.Entry<ResourceLocation, BakedAnimations> en : cache.entrySet()) {
                ResourceLocation loc = en.getKey();
                if (!loc.getPath().startsWith(RESOURCE_DIR)) continue;
                add(BUILTIN, en.getValue(), loc.toString());
            }
            generation++;
            RbxLog.LOGGER.info("Player animations: {} built-in", BUILTIN.size());
        }
        if (!externalLoaded) reloadExternal();
    }

    private static void add(Map<String, Clip> into, BakedAnimations baked, String source) {
        if (baked == null || baked.animations() == null) return;
        for (Map.Entry<String, Animation> a : baked.animations().entrySet()) {
            String key = a.getKey().toLowerCase(Locale.ROOT);
            try {
                Clip c = new Clip(shortName(key), source, a.getValue());
                into.put(key, c);
                into.put(c.name, c);
            } catch (RuntimeException e) {
                ERRORS.add((into == EXTERNAL ? "[папка] " : "[мод] ") + source + " / " + key + ": " + e);
            }
        }
    }

    static String shortName(String key) {
        int i = key.lastIndexOf('.');
        return key.startsWith("animation.") && i >= 0 && i < key.length() - 1 ? key.substring(i + 1) : key;
    }

    // ------------------------------------------------------------------ папка config

    public static Path externalDir() {
        return FMLPaths.CONFIGDIR.get().resolve("jujutsu_neon").resolve("animations");
    }

    /** Перечитать папку. Возвращает число анимаций из неё. */
    public static int reloadExternal() {
        externalLoaded = true;
        EXTERNAL.clear();
        STAMPS.clear();
        ERRORS.removeIf(s -> s.startsWith("[папка]"));
        Path dir = externalDir();
        try {
            Files.createDirectories(dir);
            writeReadmeOnce(dir);
        } catch (IOException ignored) {
        }
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".json")).forEach(p -> {
                    try {
                        STAMPS.put(p, Files.getLastModifiedTime(p).toMillis());
                        JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                        JsonElement anims = root.get("animations");
                        if (anims == null || !anims.isJsonObject()) return; // не анимации (например, модель)
                        BakedAnimations baked = JsonUtil.GEO_GSON.fromJson(anims, BakedAnimations.class);
                        add(EXTERNAL, baked, dir.relativize(p).toString());
                    } catch (Exception e) {
                        ERRORS.add("[папка] " + dir.relativize(p) + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                ERRORS.add("[папка] " + e.getMessage());
            }
        }
        generation++;
        return (int) EXTERNAL.keySet().stream().filter(k -> !k.startsWith("animation.")).count();
    }

    /** Изменились ли файлы в папке (раз в секунду из тика клиента). */
    public static boolean externalChanged() {
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
        return !now.equals(STAMPS);
    }

    public static boolean externalLoaded() {
        return externalLoaded;
    }

    public static String source(String name) {
        Clip c = get(name);
        return c == null ? "?" : c.source;
    }

    private static void writeReadmeOnce(Path dir) throws IOException {
        Path readme = dir.resolve("README.txt");
        if (Files.exists(readme)) return;
        Files.writeString(readme, String.join("\n",
                "Анимации игрока Jujutsu Neon (Blockbench).",
                "",
                "Сюда кладутся .animation.json из Blockbench (Bedrock / GeckoLib) для модели игрока R15.",
                "Модель для Blockbench лежит в моде: assets/jujutsu_neon/geo/player_r15.geo.json (её же можно",
                "вытащить командой /jnanim export). Анимация подхватывается в игре через секунду после сохранения;",
                "анимация с тем же именем заменяет встроенную (например, walk — ходьба).",
                "",
                "Свойства анимации — инструкцией на дорожке Timeline: @priority=action, @fade_in=0.1, @look=0.3, @fp=arms…",
                "Маркеры — любые другие инструкции (hit, release=2). Подробно — docs/ANIMATION.md в репозитории мода.",
                "",
                "В игре: /jnanim list, /jnanim play <имя>, /jnanim loop <имя>, /jnanim pose <имя> <секунда>, /jnanim stop,",
                "/jnanim speed <x>, /jnanim info <имя>, /jnanim reload, /jnanim errors.",
                ""), StandardCharsets.UTF_8);
    }
}
