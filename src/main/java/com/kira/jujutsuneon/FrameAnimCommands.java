package com.kira.jujutsuneon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * /jnanim — посмотреть и отладить покадровые анимации прямо в игре (на себе; F5 — посмотреть со стороны).
 *
 *  list                      — все анимации (из мода и из папки config/jujutsu_neon/animations)
 *  play &lt;имя&gt; [скорость]     — проиграть;  loop &lt;имя&gt; — по кругу;  mirror &lt;имя&gt; — зеркально
 *  frame &lt;имя&gt; &lt;кадр&gt;        — стоп-кадр на любом кадре (можно дробный: 12.5)
 *  step &lt;±кадры&gt;             — сдвинуть стоп-кадр
 *  speed &lt;x&gt;                 — замедлить/ускорить все покадровые анимации (0.1 — в 10 раз медленнее)
 *  stop                      — остановить
 *  info &lt;имя&gt;                — fps, кадры, длительность, каналы, события
 *  export &lt;имя&gt;              — записать в покадровый формат (config/jujutsu_neon/animations/export)
 *  emote &lt;эмоция&gt;            — проиграть старую анимацию мода через покадровый проигрыватель
 *  bake &lt;эмоция&gt;             — перевести старую анимацию мода в покадровый файл для правки
 *  reload / errors           — перечитать папку / показать ошибки загрузки
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FrameAnimCommands {

    private static final SuggestionProvider<CommandSourceStack> ANIMS =
            (c, b) -> SharedSuggestionProvider.suggest(FrameAnimations.names(), b);
    private static final SuggestionProvider<CommandSourceStack> EMOTES =
            (c, b) -> SharedSuggestionProvider.suggest(FrameAnimations.emoteNames(), b);

    private FrameAnimCommands() {
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
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ANIMS)
                                .executes(c -> play(name(c), 1f, false, null))
                                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.01f, 10f))
                                        .executes(c -> play(name(c), FloatArgumentType.getFloat(c, "speed"), false, null)))))
                .then(Commands.literal("loop")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ANIMS)
                                .executes(c -> play(name(c), 1f, false, Boolean.TRUE))))
                .then(Commands.literal("mirror")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ANIMS)
                                .executes(c -> play(name(c), 1f, true, null))))
                .then(Commands.literal("frame")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ANIMS)
                                .then(Commands.argument("frame", DoubleArgumentType.doubleArg(0.0))
                                        .executes(c -> frame(name(c), DoubleArgumentType.getDouble(c, "frame"))))))
                .then(Commands.literal("step")
                        .then(Commands.argument("delta", DoubleArgumentType.doubleArg(-10000.0, 10000.0))
                                .executes(c -> step(DoubleArgumentType.getDouble(c, "delta")))))
                .then(Commands.literal("speed")
                        .then(Commands.argument("x", FloatArgumentType.floatArg(0.01f, 10f))
                                .executes(c -> speed(FloatArgumentType.getFloat(c, "x")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ANIMS)
                                .executes(c -> info(name(c)))))
                .then(Commands.literal("export")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ANIMS)
                                .executes(c -> export(name(c)))))
                .then(Commands.literal("emote")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(EMOTES)
                                .executes(c -> emote(name(c), false))))
                .then(Commands.literal("bake")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(EMOTES)
                                .executes(c -> emote(name(c), true)))));
    }

    private static String name(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    private static void say(String text, ChatFormatting color) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(text).withStyle(color), false);
    }

    private static int list() {
        var names = FrameAnimations.names();
        if (names.isEmpty()) {
            say("Покадровых анимаций пока нет. Папка: " + FrameAnimations.externalDir(), ChatFormatting.GRAY);
            return 1;
        }
        say("Покадровые анимации (" + names.size() + "):", ChatFormatting.AQUA);
        for (String n : names) {
            FrameAnimation a = FrameAnimations.get(n);
            say("  " + n + "  — " + a.frameCount + " кадров, " + fmt(a.fps) + " к/с, " + fmt(a.duration()) + " с", ChatFormatting.WHITE);
        }
        return 1;
    }

    private static int reload() {
        int n = FrameAnimations.reloadExternal();
        FrameAnimations.reloadBuiltin(Minecraft.getInstance().getResourceManager());
        say("Перечитано. Из папки: " + n + ", всего: " + FrameAnimations.names().size()
                + (FrameAnimations.errors().isEmpty() ? "" : ", ошибок: " + FrameAnimations.errors().size() + " (/jnanim errors)"),
                ChatFormatting.AQUA);
        return 1;
    }

    private static int errors() {
        List<String> e = FrameAnimations.errors();
        if (e.isEmpty()) {
            say("Ошибок загрузки нет.", ChatFormatting.GREEN);
            return 1;
        }
        for (String s : e) say(s, ChatFormatting.RED);
        return 1;
    }

    private static int play(String name, float speed, boolean mirror, Boolean loop) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        FrameAnimation a = FrameAnimations.get(name);
        if (a == null) {
            say("Нет анимации «" + name + "». /jnanim list", ChatFormatting.RED);
            return 0;
        }
        FrameAnimations.play(p, a, new FrameAnimations.PlayOptions().speed(speed).mirror(mirror).loop(loop));
        say("▶ " + a.name + " (" + a.frameCount + " кадров, " + fmt(a.fps) + " к/с)", ChatFormatting.AQUA);
        return 1;
    }

    private static int frame(String name, double frame) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return 0;
        FrameAnimation a = FrameAnimations.get(name);
        if (a == null) {
            say("Нет анимации «" + name + "».", ChatFormatting.RED);
            return 0;
        }
        double f = Math.min(frame, a.frameCount - 1);
        FrameAnimations.play(p, a, new FrameAnimations.PlayOptions().freezeAt(f).fadeTicks(0));
        say("⏸ " + a.name + " — кадр " + fmt((float) f) + " / " + (a.frameCount - 1), ChatFormatting.AQUA);
        return 1;
    }

    private static int step(double delta) {
        LocalPlayer p = Minecraft.getInstance().player;
        FrameAnimationPlayer cur = FrameAnimations.current(p);
        if (cur == null || Double.isNaN(cur.frozenFrame)) {
            say("Сначала стоп-кадр: /jnanim frame <имя> <кадр>", ChatFormatting.GRAY);
            return 0;
        }
        cur.frozenFrame = Math.max(0.0, Math.min(cur.anim.frameCount - 1, cur.frozenFrame + delta));
        say("⏸ " + cur.anim.name + " — кадр " + fmt((float) cur.frozenFrame) + " / " + (cur.anim.frameCount - 1), ChatFormatting.AQUA);
        return 1;
    }

    private static int speed(float x) {
        FrameAnimations.setDebugSpeed(x);
        say("Скорость покадровых анимаций: ×" + fmt(FrameAnimations.debugSpeed()), ChatFormatting.AQUA);
        return 1;
    }

    private static int stop() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) FrameAnimations.stop(p, 0.15f);
        return 1;
    }

    private static int info(String name) {
        FrameAnimation a = FrameAnimations.get(name);
        if (a == null) {
            say("Нет анимации «" + name + "».", ChatFormatting.RED);
            return 0;
        }
        say(a.name + " — " + a.source, ChatFormatting.AQUA);
        say("  " + a.frameCount + " кадров, " + fmt(a.fps) + " к/с, " + fmt(a.duration()) + " с, сглаживание: "
                + a.interp.name().toLowerCase(Locale.ROOT) + (a.loop ? ", петля с кадра " + a.loopFrom : "")
                + (a.holdLast ? ", держит последний кадр" : ""), ChatFormatting.WHITE);
        StringBuilder ch = new StringBuilder();
        for (FrameChannel c : a.usedChannels()) {
            if (ch.length() > 0) ch.append(", ");
            ch.append(c.key);
        }
        say("  каналы (" + a.usedChannels().size() + "): " + ch, ChatFormatting.GRAY);
        if (!a.events.isEmpty()) {
            StringBuilder ev = new StringBuilder();
            for (FrameAnimation.Event e : a.events) {
                if (ev.length() > 0) ev.append(", ");
                ev.append(e.frame).append(": ").append(e.name);
            }
            say("  события: " + ev, ChatFormatting.GRAY);
        }
        if (a.secondary.enabled) say("  живой слой: включён (сила " + fmt(a.secondary.amount) + ")", ChatFormatting.GRAY);
        return 1;
    }

    private static int export(String name) {
        FrameAnimation a = FrameAnimations.get(name);
        if (a == null) {
            say("Нет анимации «" + name + "».", ChatFormatting.RED);
            return 0;
        }
        try {
            Path out = FrameAnimations.export(a, a.name);
            say("Записано по кадрам: " + out, ChatFormatting.GREEN);
        } catch (Exception e) {
            say("Не удалось записать: " + e.getMessage(), ChatFormatting.RED);
        }
        return 1;
    }

    /** Старая анимация мода — проиграть покадрово (bake=false) или записать в файл для правки (bake=true). */
    private static int emote(String name, boolean bake) {
        try {
            FrameAnimation a = FrameAnimations.fromEmote(name);
            if (bake) {
                Path out = FrameAnimations.export(a, name);
                say("Переведено в кадры (" + a.frameCount + " кадров): " + out, ChatFormatting.GREEN);
                say("Переложите файл в config/jujutsu_neon/animations, правьте по кадрам — подхватится сразу.", ChatFormatting.GRAY);
            } else {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) FrameAnimations.play(p, a, new FrameAnimations.PlayOptions());
                say("▶ " + name + " (эмоция → " + a.frameCount + " кадров)", ChatFormatting.AQUA);
            }
            return 1;
        } catch (Exception e) {
            say("Не удалось: " + e.getMessage(), ChatFormatting.RED);
            return 0;
        }
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.round(v)) < 1.0E-3f) return Integer.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
