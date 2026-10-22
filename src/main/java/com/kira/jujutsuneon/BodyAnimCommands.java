package com.kira.jujutsuneon;

import com.kira.jujutsuneon.anim.Animator;
import com.kira.jujutsuneon.anim.Clip;
import com.kira.jujutsuneon.anim.Joint;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * /jnanim — посмотреть и отладить анимации тела прямо в игре (на себе; F5 — со стороны).
 *
 *  list                      — все клипы (из мода и из папки config/jujutsu_neon/animations)
 *  play &lt;имя&gt; [скорость]     — проиграть;  loop &lt;имя&gt; — по кругу;  mirror &lt;имя&gt; — зеркально
 *  pose &lt;имя&gt; &lt;тик&gt;          — стоп-кадр в любой момент (можно дробный: 12.5)
 *  step &lt;±тики&gt;              — сдвинуть стоп-кадр
 *  speed &lt;x&gt;                 — замедлить/ускорить все анимации (0.1 — в 10 раз медленнее)
 *  stop                      — плавно остановить
 *  info &lt;имя&gt;                — длина, петля, переходы, кости
 *  reload / errors           — перечитать клипы / показать ошибки загрузки
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BodyAnimCommands {

    private static final SuggestionProvider<CommandSourceStack> CLIPS =
            (c, b) -> SharedSuggestionProvider.suggest(BodyAnims.names(), b);

    private BodyAnimCommands() {
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
                                .then(Commands.argument("tick", DoubleArgumentType.doubleArg(0))
                                        .executes(c -> pose(name(c), DoubleArgumentType.getDouble(c, "tick"))))))
                .then(Commands.literal("step")
                        .then(Commands.argument("ticks", DoubleArgumentType.doubleArg(-10000, 10000))
                                .executes(c -> step(DoubleArgumentType.getDouble(c, "ticks")))))
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
        var names = BodyAnims.names();
        say("Анимации тела (" + names.size() + "):", ChatFormatting.AQUA);
        for (String n : names) {
            Clip c = BodyAnims.clip(n);
            say("  " + n + "  — " + fmt(c.length * 20) + " тиков" + (c.loop ? ", петля" : "") + (c.additive ? ", добавочная" : ""),
                    ChatFormatting.WHITE);
        }
        say("Папка для своих: " + BodyAnims.externalDir(), ChatFormatting.GRAY);
        return 1;
    }

    private static int reload() {
        int n = BodyAnims.reloadExternal();
        BodyAnims.reloadBuiltin(Minecraft.getInstance().getResourceManager());
        say("Перечитано. Из папки: " + n + ", всего: " + BodyAnims.names().size()
                + (BodyAnims.errors().isEmpty() ? "" : ", ошибок: " + BodyAnims.errors().size() + " (/jnanim errors)"),
                ChatFormatting.AQUA);
        return 1;
    }

    private static int errors() {
        List<String> e = BodyAnims.errors();
        if (e.isEmpty()) {
            say("Ошибок загрузки нет.", ChatFormatting.GREEN);
            return 1;
        }
        for (String s : e) say(s, ChatFormatting.RED);
        return 1;
    }

    private static Clip find(String name) {
        Clip c = BodyAnims.clip(name);
        if (c == null) say("Нет анимации «" + name + "». /jnanim list", ChatFormatting.RED);
        return c;
    }

    private static int play(String name, double speed, boolean mirror, Boolean loop) {
        LocalPlayer p = Minecraft.getInstance().player;
        Clip c = find(name);
        if (p == null || c == null) return 0;
        BodyAnims.play(p, Animator.LAYER_DEBUG, c, new Animator.Play().speed(speed).mirror(mirror).loop(loop));
        say("▶ " + c.name + " (" + fmt(c.length * 20) + " тиков)", ChatFormatting.AQUA);
        return 1;
    }

    private static int pose(String name, double tick) {
        LocalPlayer p = Minecraft.getInstance().player;
        Clip c = find(name);
        if (p == null || c == null) return 0;
        double t = Math.min(tick / 20.0, c.length);
        BodyAnims.play(p, Animator.LAYER_DEBUG, c, new Animator.Play().freezeAt(t).fadeIn(0.1));
        say("⏸ " + c.name + " — тик " + fmt(t * 20) + " / " + fmt(c.length * 20), ChatFormatting.AQUA);
        return 1;
    }

    private static int step(double ticks) {
        LocalPlayer p = Minecraft.getInstance().player;
        Animator a = BodyAnims.animator(p);
        if (a == null || !a.isPlaying(Animator.LAYER_DEBUG)) {
            say("Сначала стоп-кадр: /jnanim pose <имя> <тик>", ChatFormatting.GRAY);
            return 0;
        }
        Clip c = a.clip(Animator.LAYER_DEBUG);
        double now = BodyAnims.now(p);
        double t = Math.max(0, Math.min(c.length, a.clipTime(Animator.LAYER_DEBUG, now) + ticks / 20.0));
        a.freeze(Animator.LAYER_DEBUG, t, now);
        say("⏸ " + c.name + " — тик " + fmt(t * 20) + " / " + fmt(c.length * 20), ChatFormatting.AQUA);
        return 1;
    }

    private static int speed(double x) {
        BodyAnims.setDebugSpeed(x);
        say("Скорость анимаций: ×" + fmt(BodyAnims.debugSpeed()), ChatFormatting.AQUA);
        return 1;
    }

    private static int stop() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        BodyAnims.stop(p, Animator.LAYER_DEBUG, 0.2);
        BodyAnims.stop(p, Animator.LAYER_ACTION, 0.2);
        return 1;
    }

    private static int info(String name) {
        Clip c = find(name);
        if (c == null) return 0;
        say(c.name + " — " + BodyAnims.source(name), ChatFormatting.AQUA);
        say("  " + fmt(c.length * 20) + " тиков (" + fmt(c.length) + " с)" + (c.loop ? ", петля" : "")
                + (c.holdLast ? ", держит последний кадр" : "") + (c.additive ? ", добавочная" : "")
                + ", вход " + fmt(c.fadeIn) + " с, выход " + fmt(c.fadeOut) + " с, взгляд " + fmt(c.look), ChatFormatting.WHITE);
        StringBuilder sb = new StringBuilder();
        for (Joint j : Joint.ALL) {
            if (!c.hasJoint(j)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(j.partName);
        }
        say("  кости: " + (sb.length() == 0 ? "нет" : sb), ChatFormatting.GRAY);
        return 1;
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 1.0E-3) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
