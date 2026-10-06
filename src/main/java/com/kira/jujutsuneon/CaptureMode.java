package com.kira.jujutsuneon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.util.Locale;

/**
 * Служебная съёмка катсцен для сверки с референсом (только для разработки).
 *
 * Включается, ТОЛЬКО если в папке игры лежит файл {@code jn_capture.txt} — у игроков его нет,
 * мод ведёт себя как обычно. В режиме съёмки клиент сам создаёт плоский мир, запускает катсцену
 * Максимального Фиолетового и сохраняет каждый отрисованный кадр в screenshots/ с временем
 * катсцены в имени (t_0123.456.png — тики по 0,05 с), потом закрывает игру.
 * Запускается в GitHub Actions (.github/workflows/jujutsu-capture.yml).
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CaptureMode {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static Boolean enabled;
    private static int state;
    private static int wait;
    private static int shots;
    private static double lastShot = -1.0;

    private CaptureMode() {
    }

    static boolean enabled() {
        if (enabled == null) {
            enabled = new File(Minecraft.getInstance().gameDirectory, "jn_capture.txt").exists();
            if (enabled) LOG.info("[jn-capture] режим съёмки включён");
        }
        return enabled;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        switch (state) {
            case 0 -> {
                if (mc.screen instanceof TitleScreen && ++wait > 40) {
                    LOG.info("[jn-capture] создаю мир");
                    LevelSettings settings = new LevelSettings("jncap", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("jncap" + System.currentTimeMillis(), settings,
                            new WorldOptions(12345L, false, false), CaptureMode::flat);
                    state = 1;
                    wait = 0;
                }
            }
            case 1 -> {
                if (mc.level != null && mc.player != null && mc.screen == null) {
                    if (++wait == 20) {
                        MinecraftServer server = mc.getSingleplayerServer();
                        if (server != null) server.execute(() -> {
                            for (String c : new String[]{"time set 6000", "gamerule doDaylightCycle false",
                                    "weather clear", "gamerule doWeatherCycle false", "gamerule doMobSpawning false"}) {
                                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c);
                            }
                        });
                    }
                    if (wait > 160) {
                        LOG.info("[jn-capture] старт катсцены");
                        MaximumPurpleClient.onStart(mc.player.getUUID(), mc.player.position(), 0.0f);
                        state = 2;
                        wait = 0;
                    }
                }
            }
            case 2 -> {
                double t = MaximumPurpleClient.localClockForCapture();
                if (t > MaximumPurple.ANIM_TICKS + 6 || ++wait > 20 * 60) {
                    LOG.info("[jn-capture] готово, кадров: " + shots);
                    state = 3;
                    wait = 0;
                }
            }
            case 3 -> {
                if (++wait > 60) mc.stop();
            }
            default -> {
            }
        }
    }

    private static WorldDimensions flat(RegistryAccess access) {
        return access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || state != 2 || !enabled()) return;
        double t = MaximumPurpleClient.localClockForCapture();
        if (t < 0.0 || t - lastShot < 0.25) return;
        lastShot = t;
        Minecraft mc = Minecraft.getInstance();
        String name = String.format(Locale.ROOT, "t_%08.3f.png", t);
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {
        });
        shots++;
    }
}
