package com.kira.jujutsuneon;

import com.kira.jujutsuneon.rbx.R15Render;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Режим тестера (только для разработки): игрой управляют команды из файла, как будто играет человек.
 *
 * Включается, ТОЛЬКО если в папке игры есть файл {@code jn_pilot/on} — у игроков его нет. Команды дописываются
 * построчно в {@code jn_pilot/cmd.txt}; мод читает новые строки и выполняет их по очереди: нажимает те же
 * клавиши (KeyMapping), что и игрок, крутит камеру, делает скриншоты в {@code jn_pilot/shots}. Ответы —
 * в {@code jn_pilot/out.log}. Системные клавиатура и мышь не трогаются, поэтому окно игры может быть
 * не в фокусе (за другими окнами или на другом рабочем столе Windows), но не свёрнуто.
 *
 * Команды: join, hold &lt;клавиша&gt; &lt;тики&gt;, tap &lt;клавиша&gt;, release, wait &lt;тики&gt;, look &lt;yaw&gt; &lt;pitch&gt;,
 * turn &lt;dyaw&gt; &lt;dpitch&gt; &lt;тики&gt;, cam first|back|front, shot [имя], burst &lt;имя&gt; &lt;кол-во&gt; &lt;каждые_тиков&gt;,
 * cmd &lt;команда без /&gt;, close, status, keys, stop. Клавиша — имя KeyMapping (key.jump) или его хвост (jump, blue).
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PilotMode {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private static Path dir;
    private static boolean on;
    private static int checkIn;
    private static long readPos;
    private static long tick;
    private static int waitTicks;
    private static boolean joining;
    private static int shotSeq;

    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static final Map<KeyMapping, Integer> HELD = new IdentityHashMap<>();
    private static final ArrayDeque<String> PENDING_SHOTS = new ArrayDeque<>();
    private static String burstName;
    private static int burstLeft, burstEvery, burstIn;
    private static float turnYaw, turnPitch;
    private static int turnLeft;

    private PilotMode() {
    }

    private static boolean enabled(Minecraft mc) {
        if (--checkIn > 0) return on;
        checkIn = 20;
        if (dir == null) dir = mc.gameDirectory.toPath().resolve("jn_pilot");
        boolean now = Files.exists(dir.resolve("on"));
        if (now && !on) {
            try {
                Path cmd = dir.resolve("cmd.txt");
                readPos = Files.exists(cmd) ? Files.size(cmd) : 0; // старые команды не повторяем
                Files.createDirectories(dir.resolve("shots"));
            } catch (IOException e) {
                LOG.warn("[jn-pilot] {}", e.toString());
            }
            out("pilot on");
            // в полноэкранном режиме окно сворачивается при любом Alt+Tab и перестаёт рисовать
            if (mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
        }
        if (!now && on) releaseAll();
        on = now;
        return on;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (!enabled(mc)) return;
        tick++;
        // окно не в фокусе — игра не должна вставать на паузу
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof PauseScreen && !mc.isWindowActive()) mc.setScreen(null);

        readCommands();
        for (Iterator<Map.Entry<KeyMapping, Integer>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<KeyMapping, Integer> e = it.next();
            if (e.getValue() <= 0) {
                e.getKey().setDown(false);
                it.remove();
            } else {
                e.getKey().setDown(true);
                e.setValue(e.getValue() - 1);
            }
        }
        LocalPlayer p = mc.player;
        if (turnLeft > 0 && p != null) {
            p.setYRot(p.getYRot() + turnYaw);
            p.setXRot(Math.max(-90f, Math.min(90f, p.getXRot() + turnPitch)));
            turnLeft--;
        }
        if (burstLeft > 0 && --burstIn <= 0) {
            PENDING_SHOTS.add(burstName + "_" + (burstLeft--));
            burstIn = burstEvery;
        }

        if (joining) {
            if (mc.level != null && mc.player != null && mc.screen == null) {
                joining = false;
                out("joined");
            }
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (!QUEUE.isEmpty() && waitTicks == 0 && !joining) {
            String line = QUEUE.poll();
            try {
                run(mc, line);
            } catch (RuntimeException e) {
                out(line + " -> error " + e);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !on || PENDING_SHOTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        String name = PENDING_SHOTS.poll();
        NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        Path file = dir.resolve("shots").resolve(String.format(Locale.ROOT, "%04d_%s.png", ++shotSeq, name));
        Util.ioPool().execute(() -> {
            try {
                img.writeToFile(file);
            } catch (IOException e) {
                LOG.warn("[jn-pilot] screenshot {}", e.toString());
            } finally {
                img.close();
            }
        });
        out("shot " + file.getFileName());
    }

    private static void readCommands() {
        Path cmd = dir.resolve("cmd.txt");
        try {
            if (!Files.exists(cmd)) return;
            long size = Files.size(cmd);
            if (size < readPos) readPos = 0; // файл пересоздали
            if (size == readPos) return;
            byte[] buf;
            try (RandomAccessFile f = new RandomAccessFile(cmd.toFile(), "r")) {
                f.seek(readPos);
                buf = new byte[(int) (size - readPos)];
                f.readFully(buf);
            }
            int end = 0;
            for (int i = 0; i < buf.length; i++) {
                if (buf[i] != '\n') continue;
                String line = new String(buf, end, i - end, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty() && !line.startsWith("#")) QUEUE.add(line);
                end = i + 1;
            }
            readPos += end; // недописанную строку прочитаем в следующий раз
        } catch (IOException e) {
            LOG.warn("[jn-pilot] {}", e.toString());
        }
    }

    private static void run(Minecraft mc, String line) {
        String[] a = line.split("\\s+");
        LocalPlayer p = mc.player;
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (mc.level != null) {
                    out("join -> already in world");
                    return;
                }
                if (!(mc.screen instanceof TitleScreen)) {
                    QUEUE.addFirst(line); // ждём главное меню
                    waitTicks = 10;
                    return;
                }
                joinTopWorld(mc);
            }
            case "hold" -> {
                KeyMapping k = key(mc, a[1]);
                HELD.put(k, a.length > 2 ? Integer.parseInt(a[2]) : 20);
                k.setDown(true);
            }
            case "tap" -> {
                KeyMapping k = key(mc, a[1]);
                if (k.isUnbound()) throw new IllegalArgumentException("key unbound: " + k.getName());
                KeyMapping.click(k.getKey());
                HELD.put(k, 2);
                k.setDown(true);
            }
            case "release" -> releaseAll();
            case "wait" -> waitTicks = Integer.parseInt(a[1]);
            case "look" -> {
                if (p == null) throw new IllegalStateException("no player");
                p.setYRot(Float.parseFloat(a[1]));
                p.setXRot(Float.parseFloat(a[2]));
            }
            case "turn" -> {
                int n = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 1;
                turnYaw = Float.parseFloat(a[1]) / n;
                turnPitch = Float.parseFloat(a[2]) / n;
                turnLeft = n;
            }
            case "cam" -> mc.options.setCameraType(switch (a[1]) {
                case "back" -> CameraType.THIRD_PERSON_BACK;
                case "front" -> CameraType.THIRD_PERSON_FRONT;
                default -> CameraType.FIRST_PERSON;
            });
            case "shot" -> PENDING_SHOTS.add(a.length > 1 ? a[1] : "shot");
            case "burst" -> {
                burstName = a[1];
                burstLeft = Integer.parseInt(a[2]);
                burstEvery = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 2;
                burstIn = 0;
            }
            case "cmd" -> {
                if (p == null) throw new IllegalStateException("no player");
                p.connection.sendCommand(line.substring(line.indexOf(' ') + 1).replaceFirst("^/", ""));
            }
            case "close" -> mc.setScreen(null);
            case "status" -> out("status " + status(mc));
            case "keys" -> {
                StringBuilder sb = new StringBuilder("keys");
                for (KeyMapping k : mc.options.keyMappings) {
                    sb.append(' ').append(k.getName()).append('=').append(k.getKey().getName());
                }
                out(sb.toString());
            }
            case "stop" -> {
                releaseAll();
                mc.stop();
            }
            default -> throw new IllegalArgumentException("unknown command");
        }
        if (!a[0].equalsIgnoreCase("status") && !a[0].equalsIgnoreCase("keys")) out(line + " -> ok");
    }

    private static KeyMapping key(Minecraft mc, String name) {
        KeyMapping tail = null;
        for (KeyMapping k : mc.options.keyMappings) {
            if (k.getName().equalsIgnoreCase(name)) return k;
            if (k.getName().toLowerCase(Locale.ROOT).endsWith("." + name.toLowerCase(Locale.ROOT))) tail = k;
        }
        if (tail == null) throw new IllegalArgumentException("no key " + name);
        return tail;
    }

    private static void releaseAll() {
        for (KeyMapping k : HELD.keySet()) k.setDown(false);
        HELD.clear();
        turnLeft = 0;
        burstLeft = 0;
    }

    /** Самый верхний мир в «Одиночной игре» (последний, в который играли). */
    private static void joinTopWorld(Minecraft mc) {
        LevelStorageSource src = mc.getLevelSource();
        joining = true;
        try {
            src.loadLevelSummaries(src.findLevelCandidates()).thenAccept(list -> mc.execute(() -> {
                LevelSummary top = list.stream().filter(s -> !s.isDisabled())
                        .max(Comparator.comparingLong(LevelSummary::getLastPlayed)).orElse(null);
                if (top == null) {
                    joining = false;
                    out("join -> no worlds");
                    return;
                }
                out("join " + top.getLevelId() + " (" + top.getLevelName() + ")");
                mc.createWorldOpenFlows().loadLevel(mc.screen, top.getLevelId());
            }));
        } catch (Exception e) {
            joining = false;
            out("join -> error " + e);
        }
    }

    private static String status(Minecraft mc) {
        LocalPlayer p = mc.player;
        String s = "screen=" + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName())
                + " focused=" + mc.isWindowActive() + " cam=" + mc.options.getCameraType();
        if (p == null) return s;
        return s + String.format(Locale.ROOT, " pos=%.1f,%.1f,%.1f yaw=%.0f pitch=%.0f hp=%.0f mode=%s skin=%s",
                p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getHealth(),
                mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode(), p.getModelName())
                + " r15[" + R15Render.debugState(p) + "]";
    }

    private static void out(String msg) {
        LOG.info("[jn-pilot] {}", msg);
        if (dir == null) return;
        try {
            Files.writeString(dir.resolve("out.log"), tick + " " + msg + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
