package com.kira.jujutsuneon.rbx;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /jnanim — смотреть и отлаживать анимации прямо в игре (на себе; F5 — со стороны). Анимацию видят и другие игроки.
 *
 *  list                      — все анимации (из мода и из папки config/jujutsu_neon/animations)
 *  play &lt;имя&gt; [скорость]     — проиграть;  loop &lt;имя&gt; — по кругу;  mirror &lt;имя&gt; — зеркально
 *  pose &lt;имя&gt; &lt;секунда&gt;      — стоп-кадр в любой момент;  step &lt;±секунды&gt; — сдвинуть стоп-кадр
 *  speed &lt;x&gt;                 — замедлить/ускорить все анимации (0.1 — в 10 раз медленнее)
 *  stop                      — плавно остановить
 *  tracks                    — что сейчас играет (треки, приоритеты, веса)
 *  info &lt;имя&gt;                — длина, петля, приоритет, переходы, маркеры, кости
 *  reload / errors           — перечитать папку / показать ошибки загрузки
 *  export                    — выложить модели R15 для Blockbench в config/jujutsu_neon/blockbench
 *  model r15 | vanilla       — модель игрока: R15 (анимации из Blockbench) или ванильная
 */
@Mod.EventBusSubscriber(modid = R15Rig.NS, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AnimCommands {

    private static final SuggestionProvider<CommandSourceStack> CLIPS =
            (c, b) -> SharedSuggestionProvider.suggest(ClipLibrary.names(), b);
    private static AnimationTrack debug;

    private AnimCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("jnanim")
                .then(Commands.literal("list").executes(c -> list()))
                .then(Commands.literal("reload").executes(c -> reload()))
                .then(Commands.literal("errors").executes(c -> errors()))
                .then(Commands.literal("stop").executes(c -> stop()))
                .then(Commands.literal("tracks").executes(c -> tracks()))
                .then(Commands.literal("export").executes(c -> export()))
                .then(Commands.literal("model")
                        .then(Commands.literal("r15").executes(c -> model(true)))
                        .then(Commands.literal("vanilla").executes(c -> model(false))))
                .then(Commands.literal("play")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLIPS)
                                .executes(c -> play(name(c), 1, false, null))
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.01, 10))
                                        .executes(c -> play(name(c), DoubleArgumentType.getDouble(c, "speed"), false, null)))))
                .then(Commands.literal("loop")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLIPS)
                                .executes(c -> play(name(c), 1, false, Boolean.TRUE))))
                .then(Commands.literal("mirror")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLIPS)
                                .executes(c -> play(name(c), 1, true, null))))
                .then(Commands.literal("pose")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLIPS)
                                .then(Commands.argument("second", DoubleArgumentType.doubleArg(0))
                                        .executes(c -> pose(name(c), DoubleArgumentType.getDouble(c, "second"))))))
                .then(Commands.literal("step")
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(-1000, 1000))
                                .executes(c -> step(DoubleArgumentType.getDouble(c, "seconds")))))
                .then(Commands.literal("speed")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(0.01, 10))
                                .executes(c -> speed(DoubleArgumentType.getDouble(c, "x")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(CLIPS)
                                .executes(c -> info(name(c))))));
    }

    private static String name(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    private static void say(String text, ChatFormatting color) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(text).withStyle(color), false);
    }

    private static int list() {
        var names = ClipLibrary.names();
        say("Анимации (" + names.size() + "):", ChatFormatting.AQUA);
        for (String n : names) {
            Clip c = ClipLibrary.get(n);
            say("  " + n + "  — " + fmt(c.length) + " с, " + c.priority.name().toLowerCase(Locale.ROOT)
                    + (c.looped ? ", петля" : "") + (ClipLibrary.isExternal(n) ? ", из папки" : ""), ChatFormatting.WHITE);
        }
        say("Папка для своих: " + ClipLibrary.externalDir(), ChatFormatting.GRAY);
        return 1;
    }

    private static int reload() {
        int n = ClipLibrary.reloadExternal();
        say("Перечитано. Из папки: " + n + ", всего: " + ClipLibrary.names().size()
                + (ClipLibrary.errors().isEmpty() ? "" : ", ошибок: " + ClipLibrary.errors().size() + " (/jnanim errors)")
                + ". Анимации мода перечитываются с ресурсами (F3+T).", ChatFormatting.AQUA);
        return 1;
    }

    private static int errors() {
        List<String> e = ClipLibrary.errors();
        if (e.isEmpty()) {
            say("Ошибок загрузки нет.", ChatFormatting.GREEN);
            return 1;
        }
        for (String s : e) say(s, ChatFormatting.RED);
        return 1;
    }

    private static Clip find(String name) {
        Clip c = ClipLibrary.get(name);
        if (c == null) say("Нет анимации «" + name + "». /jnanim list", ChatFormatting.RED);
        return c;
    }

    private static int play(String name, double speed, boolean mirror, Boolean loop) {
        LocalPlayer p = Minecraft.getInstance().player;
        Clip c = find(name);
        if (p == null || c == null) return 0;
        debug = PlayerAnimations.play(p, c.name, new PlayerAnimations.Play().speed(speed).mirror(mirror).loop(loop)
                .replicate(true));
        say("▶ " + c.name + " (" + fmt(c.length) + " с, " + c.priority.name().toLowerCase(Locale.ROOT) + ")", ChatFormatting.AQUA);
        return 1;
    }

    private static int pose(String name, double second) {
        LocalPlayer p = Minecraft.getInstance().player;
        Clip c = find(name);
        if (p == null || c == null) return 0;
        debug = PlayerAnimations.play(p, c.name, new PlayerAnimations.Play().fade(0.1).priority(AnimationPriority.ACTION4));
        if (debug == null) return 0;
        debug.setTimePosition(Math.min(second, c.length));
        debug.setFrozen(true);
        say("⏸ " + c.name + " — " + fmt(debug.getTimePosition()) + " / " + fmt(c.length) + " с", ChatFormatting.AQUA);
        return 1;
    }

    private static int step(double seconds) {
        if (debug == null || !debug.isPlaying() || !debug.isFrozen()) {
            say("Сначала стоп-кадр: /jnanim pose <имя> <секунда>", ChatFormatting.GRAY);
            return 0;
        }
        debug.setTimePosition(debug.getTimePosition() + seconds);
        say("⏸ " + debug.getName() + " — " + fmt(debug.getTimePosition()) + " / " + fmt(debug.getLength()) + " с", ChatFormatting.AQUA);
        return 1;
    }

    private static int speed(double x) {
        PlayerAnimations.setDebugSpeed(x);
        say("Скорость анимаций: ×" + fmt(PlayerAnimations.debugSpeed()), ChatFormatting.AQUA);
        return 1;
    }

    private static int stop() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        PlayerAnimations.stop(p, 0.2);
        debug = null;
        return 1;
    }

    private static int tracks() {
        LocalPlayer p = Minecraft.getInstance().player;
        Animator a = PlayerAnimations.animator(p);
        if (a == null) return 0;
        List<AnimationTrack> active = a.activeTracks();
        say("Треки (" + active.size() + "):", ChatFormatting.AQUA);
        for (AnimationTrack t : active) say("  " + t, ChatFormatting.WHITE);
        return 1;
    }

    private static int model(boolean r15) {
        R15Render.setEnabled(r15);
        say(r15 ? "Модель игрока: R15 (анимации из Blockbench)." : "Модель игрока: ванильная.", ChatFormatting.AQUA);
        return 1;
    }

    private static int info(String name) {
        Clip c = find(name);
        if (c == null) return 0;
        say(c.name + " — " + c.source, ChatFormatting.AQUA);
        say("  " + fmt(c.length) + " с" + (c.looped ? ", петля" : "") + (c.holdLastFrame ? ", держит последний кадр" : "")
                + ", приоритет " + c.priority.name().toLowerCase(Locale.ROOT) + (c.additive ? ", добавочная" : "")
                + ", вход " + fmt(c.fadeIn) + " с, выход " + fmt(c.fadeOut) + " с, взгляд " + fmt(c.look)
                + (c.aim > 0 ? ", прицел " + fmt(c.aim) : "") + ", от первого лица: " + c.firstPerson.name().toLowerCase(Locale.ROOT)
                + (c.moveSpeed > 0 ? ", шаг под " + fmt(c.moveSpeed) + " бл/с" : ""), ChatFormatting.WHITE);
        if (c.markers.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (Clip.Marker m : c.markers) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(m.name()).append(m.param().isEmpty() ? "" : "=" + m.param()).append(" @").append(fmt(m.time()));
            }
            say("  маркеры: " + sb, ChatFormatting.GRAY);
        }
        say("  кости: " + (c.bones.length == 0 ? "нет" : String.join(", ", c.bones)), ChatFormatting.GRAY);
        return 1;
    }

    /** Модели R15 (и пример анимации) — в папку игры, чтобы открыть в Blockbench. */
    private static int export() {
        Minecraft mc = Minecraft.getInstance();
        Path dir = FMLPaths.CONFIGDIR.get().resolve("jujutsu_neon").resolve("blockbench");
        String[] files = {"geo/player_r15.geo.json", "geo/player_r15_slim.geo.json", "geo/armor_r15_outer.geo.json",
                "geo/armor_r15_inner.geo.json", "animations/player/locomotion.animation.json"};
        int n = 0;
        try {
            Files.createDirectories(dir);
            for (String f : files) {
                Optional<Resource> r = mc.getResourceManager().getResource(new ResourceLocation(R15Rig.NS, f));
                if (r.isEmpty()) continue;
                try (InputStream in = r.get().open()) {
                    Files.copy(in, dir.resolve(f.substring(f.lastIndexOf('/') + 1)), StandardCopyOption.REPLACE_EXISTING);
                    n++;
                }
            }
        } catch (IOException e) {
            say("Не удалось выложить: " + e.getMessage(), ChatFormatting.RED);
            return 0;
        }
        say("Выложено файлов: " + n + " → " + dir, ChatFormatting.AQUA);
        say("В Blockbench: File → Open Model → player_r15.geo.json, затем Animate → Import Animations.", ChatFormatting.GRAY);
        return 1;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 1.0E-3) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
