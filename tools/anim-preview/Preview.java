import com.kira.jujutsuneon.anim.AnimMath;
import com.kira.jujutsuneon.anim.Animator;
import com.kira.jujutsuneon.anim.Clip;
import com.kira.jujutsuneon.anim.ClipLoader;
import com.kira.jujutsuneon.anim.Joint;
import com.kira.jujutsuneon.anim.Locomotion;
import com.kira.jujutsuneon.anim.Pose;
import com.kira.jujutsuneon.anim.Rig;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Превью анимаций мода тем же кодом движка, что и в игре (пакет com.kira.jujutsuneon.anim):
 * модель игрока из коробок (с иерархией костей и сгибами локтей/коленей), GIF и лист кадров.
 *
 * <pre>
 * tools/anim-preview/run.sh clip red_cast            # анимация техники
 * tools/anim-preview/run.sh loco run                 # движение: idle, walk, run, sprint, sneak, jump, charge, fly, dash
 * </pre>
 * Результат — в tools/anim-preview/out/: имя.gif (реальная скорость), имя_sheet.png (кадры с подписями).
 */
public final class Preview {

    static final Map<String, Clip> CLIPS = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 2 ? args[2] : ".");
        loadDir(root.resolve("src/main/resources/assets/jujutsu_neon/player_animation"));
        loadDir(root.resolve("src/main/resources/assets/jujutsu_neon/body_anims"));
        Path out = root.resolve("tools/anim-preview/out");
        Files.createDirectories(out);
        String mode = args.length > 0 ? args[0] : "loco";
        String name = args.length > 1 ? args[1] : "run";
        List<Frame> frames = mode.equals("clip") ? playClip(name) : playLoco(name);
        String base = (mode.equals("clip") ? "" : "loco_") + name;
        writeGif(frames, out.resolve(base + ".gif").toFile(), 4);
        writeSheet(frames, out.resolve(base + "_sheet.png").toFile());
        System.out.println("ok " + out.resolve(base + ".gif") + " (" + frames.size() + " кадров)");
    }

    static void loadDir(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".json"))::iterator) {
                String n = dir.relativize(p).toString().replace('\\', '/');
                n = n.substring(0, n.length() - 5);
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    CLIPS.put(n, ClipLoader.load(n, r));
                } catch (Exception e) {
                    System.err.println("ошибка в " + p + ": " + e.getMessage());
                }
            }
        }
    }

    record Frame(Rig.Output rig, String label) {
    }

    static final double FPS = 25;

    // ------------------------------------------------------------------ сценарии

    static List<Frame> playClip(String name) {
        Clip c = CLIPS.get(name);
        if (c == null) throw new IllegalArgumentException("нет клипа " + name + "; есть: " + CLIPS.keySet());
        Animator an = new Animator();
        Locomotion loco = new Locomotion(CLIPS::get);
        Locomotion.Input in = new Locomotion.Input();
        in.active = true;
        in.onGround = true;
        Pose base = new Pose(), fin = new Pose();
        Rig rig = new Rig();
        List<Frame> frames = new ArrayList<>();
        double total = 0.4 + c.length + c.fadeOut + 0.4;
        int n = (int) Math.ceil(total * FPS);
        boolean started = false;
        for (int i = 0; i <= n; i++) {
            double t = i / FPS;
            while (an.time(0) + 1.0E-9 < t - 0.05) an.tick();
            double pt = (t - an.time(0)) * 20;
            double now = an.time(pt);
            if (!started && t >= 0.4) {
                an.play(Animator.LAYER_ACTION, c, new Animator.Play(), now);
                started = true;
            }
            in.dt = 1 / FPS;
            loco.evaluate(in, base);
            an.evaluate(now, base, loco.look, loco.follow, fin);
            Animator.applyLook(fin, 0, 0, an.lookWeight, an.followWeight, loco.rootComp);
            Rig.Output o = new Rig.Output();
            rig.solve(fin, false, o);
            frames.add(new Frame(o, String.format(Locale.ROOT, "%.2fс т%.0f", t - 0.4, (t - 0.4) * 20)));
        }
        return frames;
    }

    static List<Frame> playLoco(String scenario) {
        Locomotion loco = new Locomotion(CLIPS::get);
        Locomotion.Input in = new Locomotion.Input();
        Pose p = new Pose();
        Rig rig = new Rig();
        List<Frame> frames = new ArrayList<>();
        double len = switch (scenario) {
            case "idle" -> 4;
            case "jump" -> 2.2;
            case "charge" -> 2.6;
            case "fly" -> 6;
            case "dash" -> 3.6;
            default -> 4;
        };
        int n = (int) Math.ceil(len * FPS);
        double y = 0, vy = 0;
        for (int i = 0; i <= n; i++) {
            double t = i / FPS;
            in.active = true;
            in.dt = 1 / FPS;
            in.onGround = true;
            in.vy = 0;
            in.speed = 0;
            in.sneaking = false;
            in.chargeWeight = 0;
            in.leapAge = -1;
            in.flightW = 0;
            in.dashKind = -1;
            String label;
            switch (scenario) {
                case "walk" -> in.speed = ramp(t, 0.3, 1.0, 3.2, 3.8) * 4.3;
                case "run" -> in.speed = ramp(t, 0.3, 1.0, 3.2, 3.8) * 5.6;
                case "sprint" -> in.speed = ramp(t, 0.3, 1.2, 3.2, 3.8) * 24.0;
                case "speeds" -> in.speed = t < 1 ? 0 : (t < 2 ? 4.3 : (t < 3 ? 5.6 : 24));
                case "sneak" -> {
                    in.sneaking = t > 0.2;
                    in.speed = ramp(t, 1.0, 1.4, 3.2, 3.6) * 1.3;
                }
                case "jump" -> {
                    // прыжок с места: 0,42 блока/тик вверх, гравитация как в игре
                    if (t >= 0.3 && y == 0 && vy == 0) vy = 0.42 * 20;
                    if (t >= 0.3) {
                        y += vy / FPS;
                        vy -= 0.08 * 20 * 20 / FPS;
                        if (y <= 0) {
                            y = 0;
                            vy = 0;
                        }
                    }
                    in.onGround = y <= 0;
                    in.vy = in.onGround ? 0 : vy;
                }
                case "charge" -> {
                    if (t < 1.0) {
                        in.chargeWeight = AnimMath.smooth(t / 0.25);
                        in.chargeAge = t;
                    } else {
                        in.leapAge = t - 1.0;
                        in.onGround = t > 2.2;
                        in.vy = in.onGround ? 0 : 20 * (1.34 - 0.08 * (t - 1.0) * 20);
                        in.chargeWeight = Math.max(0, 1 - (t - 1.0) * 10);
                        in.chargeAge = 1.0;
                    }
                }
                case "fly" -> {
                    in.onGround = false;
                    in.flightW = AnimMath.smooth(t / 0.6);
                    in.flightMove = ramp(t, 1.5, 2.2, 5.0, 5.6);
                    in.boostW = ramp(t, 3.0, 3.4, 4.4, 4.8);
                    in.elevation = t < 4.6 ? 0 : -40;
                    in.speed = in.flightMove * 10;
                }
                case "dash" -> {
                    double[] starts = {0.3, 1.4, 2.2, 2.9};
                    for (int k = 3; k >= 0; k--) {
                        if (t >= starts[k]) {
                            in.dashKind = k;
                            in.dashAge = (t - starts[k]) * 20;
                            break;
                        }
                    }
                }
                default -> {
                }
            }
            loco.evaluate(in, p);
            Animator.applyLook(p, 0, 0, loco.look, loco.follow, loco.rootComp);
            Rig.Output o = new Rig.Output();
            rig.solve(p, false, o);
            o.rootPos[1] += y;
            label = String.format(Locale.ROOT, "%.2fс v%.0f", t, in.speed);
            frames.add(new Frame(o, label));
        }
        return frames;
    }

    static double ramp(double t, double a, double b, double c, double d) {
        if (t <= a || t >= d) return 0;
        if (t < b) return AnimMath.smooth((t - a) / (b - a));
        if (t <= c) return 1;
        return 1 - AnimMath.smooth((t - c) / (d - c));
    }

    // ------------------------------------------------------------------ отрисовка

    static final int W = 220, H = 260;

    /** Вид: поворот камеры вокруг вертикали (градусы; 0 — спереди, 90 — справа от игрока). */
    static final double[] VIEWS = {20, 90, 155};

    static BufferedImage render(Frame f) {
        BufferedImage img = new BufferedImage(W * VIEWS.length, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(236, 238, 242));
        g.fillRect(0, 0, img.getWidth(), H);
        for (int v = 0; v < VIEWS.length; v++) drawView(g, f, v * W, VIEWS[v]);
        g.setColor(new Color(60, 60, 70));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.drawString(f.label, 6, 14);
        g.dispose();
        return img;
    }

    record Quad(double[][] pts, Color color, double depth, double light, double[] normal) {
    }

    static final Color HEAD = new Color(232, 196, 166), FACE = new Color(40, 42, 54), TORSO = new Color(52, 58, 80),
            RARM = new Color(70, 118, 214), LARM = new Color(220, 120, 76), RLEG = new Color(46, 72, 140), LLEG = new Color(140, 64, 46),
            BAND = new Color(250, 250, 255);

    static void drawView(Graphics2D g, Frame f, int ox, double viewDeg) {
        double scale = 92; // пикселей на блок
        double cx = ox + W / 2.0, cy = H - 30;
        double yaw = Math.toRadians(viewDeg);
        // пол
        g.setColor(new Color(205, 208, 214));
        g.fillRect(ox, (int) cy, W, H - (int) cy);
        g.setColor(new Color(180, 184, 190));
        g.drawLine(ox, (int) cy, ox + W, (int) cy);
        List<Quad> quads = new ArrayList<>();
        Rig.Output o = f.rig;
        // голова: 8×8×8 над шеей
        box(quads, o, o.parts[Joint.HEAD.ordinal()], -4, -8, -4, 4, 0, 4, HEAD, true, 0, 0, 0);
        // корпус 8×12×4 под шеей
        box(quads, o, o.parts[Joint.WAIST.ordinal()], -4, 0, -2, 4, 12, 2, TORSO, false, 0, 0, 0);
        limb(quads, o, Joint.RIGHT_ARM, -3, -2, -2, 1, 10, 2, RARM);
        limb(quads, o, Joint.LEFT_ARM, -1, -2, -2, 3, 10, 2, LARM);
        limb(quads, o, Joint.RIGHT_LEG, -2, 0, -2, 2, 12, 2, RLEG);
        limb(quads, o, Joint.LEFT_LEG, -2, 0, -2, 2, 12, 2, LLEG);
        // в пространство камеры
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        List<double[]> proj = new ArrayList<>();
        List<Quad> view = new ArrayList<>();
        for (Quad q : quads) {
            double[][] p2 = new double[4][3];
            double depth = 0;
            for (int i = 0; i < 4; i++) {
                double x = q.pts[i][0], y = q.pts[i][1], z = q.pts[i][2];
                // камера смотрит на игрока спереди (с −Z тела) и поворачивается вокруг него
                double vx = x * cs - z * sn;
                double vz = x * sn + z * cs;
                // с наклоном сверху 12°
                double tilt = Math.toRadians(12);
                double vy = y * Math.cos(tilt) + vz * Math.sin(tilt);
                double vd = -y * Math.sin(tilt) + vz * Math.cos(tilt);
                p2[i][0] = cx - vx * scale; // экран: правая сторона игрока слева (смотрим ему в лицо)
                p2[i][1] = cy - vy * scale;
                p2[i][2] = vd;
                depth += vd;
            }
            // видимость: внешняя нормаль грани смотрит на камеру (камера — со стороны −vd)
            double[] nn = q.normal;
            double tiltN = Math.toRadians(12);
            double nvz = nn[0] * sn + nn[2] * cs;
            double nvd = -nn[1] * Math.sin(tiltN) + nvz * Math.cos(tiltN);
            if (nvd >= -1.0E-6) continue;
            view.add(new Quad(p2, q.color, depth / 4, q.light, nn));
        }
        view.sort((a, b) -> Double.compare(b.depth, a.depth)); // дальние (больше vd) первыми
        for (Quad q : view) {
            Polygon poly = new Polygon();
            for (double[] p : q.pts) poly.addPoint((int) Math.round(p[0]), (int) Math.round(p[1]));
            float k = (float) q.light;
            Color c = new Color(Math.min(255, (int) (q.color.getRed() * k)), Math.min(255, (int) (q.color.getGreen() * k)),
                    Math.min(255, (int) (q.color.getBlue() * k)));
            g.setColor(c);
            g.fillPolygon(poly);
            g.setColor(new Color(20, 20, 26, 90));
            g.setStroke(new BasicStroke(0.8f));
            g.drawPolygon(poly);
        }
    }

    static void limb(List<Quad> quads, Rig.Output o, Joint j, double x0, double y0, double z0, double x1, double y1, double z1, Color c) {
        Rig.Part p = o.parts[j.ordinal()];
        double mid = (y0 + y1) / 2;
        box(quads, o, p, x0, y0, z0, x1, mid, z1, c, false, 0, 0, 0);
        // нижняя половина: поворот вокруг середины на сгиб (ось — по направлению сгиба)
        box(quads, o, p, x0, mid, z0, x1, y1, z1, darker(c), false, p.bend, p.bendAxis, mid);
    }

    static Color darker(Color c) {
        return new Color((int) (c.getRed() * 0.86), (int) (c.getGreen() * 0.86), (int) (c.getBlue() * 0.86));
    }

    static void box(List<Quad> quads, Rig.Output o, Rig.Part p, double x0, double y0, double z0, double x1, double y1, double z1,
                    Color c, boolean face, double bend, double axis, double mid) {
        double[][] v = new double[8][];
        int k = 0;
        for (int i = 0; i < 2; i++)
            for (int jj = 0; jj < 2; jj++)
                for (int l = 0; l < 2; l++) {
                    double[] loc = {i == 0 ? x0 : x1, jj == 0 ? y0 : y1, l == 0 ? z0 : z1};
                    if (bend != 0) {
                        double[] q = new double[4];
                        double ax = Math.cos(-axis), az = Math.sin(-axis);
                        double s = Math.sin(bend / 2);
                        q[0] = ax * s;
                        q[1] = 0;
                        q[2] = az * s;
                        q[3] = Math.cos(bend / 2);
                        double[] d = {loc[0], loc[1] - mid, loc[2]};
                        AnimMath.rotate(q, d, d);
                        loc = new double[]{d[0], d[1] + mid, d[2]};
                    }
                    double[] w = new double[3];
                    Rig.partPointToBody(o, p, loc, w);
                    v[k++] = w;
                }
        // индексы: (x,y,z) → i*4 + j*2 + l; y0 — верх (модель Y вниз)
        int[][] faces = {
                {0, 1, 3, 2}, // x0
                {4, 6, 7, 5}, // x1
                {0, 4, 5, 1}, // y0 (верх)
                {2, 3, 7, 6}, // y1 (низ)
                {0, 2, 6, 4}, // z0 (перед)
                {1, 5, 7, 3}, // z1 (спина)
        };
        double[] light = {0.80, 0.80, 1.05, 0.62, 0.95, 0.72};
        double[] center = new double[3];
        for (double[] p8 : v) for (int i = 0; i < 3; i++) center[i] += p8[i] / 8;
        for (int fi = 0; fi < 6; fi++) {
            int[] f = faces[fi];
            double[][] pts = {v[f[0]], v[f[1]], v[f[2]], v[f[3]]};
            double[] nrm = new double[3];
            for (double[] pp : pts) for (int i = 0; i < 3; i++) nrm[i] += pp[i] / 4;
            for (int i = 0; i < 3; i++) nrm[i] -= center[i];
            Color col = c;
            if (face && fi == 4) col = FACE;
            quads.add(new Quad(pts, col, 0, light[fi], nrm));
            if (face && fi == 4) {
                // повязка: светлая полоса на лице
                double[][] band = new double[4][];
                band[0] = lerp3(pts[0], pts[1], 0.30);
                band[1] = lerp3(pts[0], pts[1], 0.55);
                band[2] = lerp3(pts[3], pts[2], 0.55);
                band[3] = lerp3(pts[3], pts[2], 0.30);
                double nl = Math.sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2]);
                for (double[] b : band) {
                    b[0] += nrm[0] / nl * 0.004;
                    b[1] += nrm[1] / nl * 0.004;
                    b[2] += nrm[2] / nl * 0.004;
                }
                quads.add(new Quad(band, BAND, 0, 1.0, nrm));
            }
        }
    }

    static double[] lerp3(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double[] normalOut(double[][] p) {
        double[] a = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
        double[] b = {p[3][0] - p[0][0], p[3][1] - p[0][1], p[3][2] - p[0][2]};
        double[] n = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        double l = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        return new double[]{n[0] / l, n[1] / l, n[2] / l};
    }

    // ------------------------------------------------------------------ файлы

    static void writeSheet(List<Frame> frames, File file) throws Exception {
        int step = Math.max(1, frames.size() / 24);
        List<Frame> pick = new ArrayList<>();
        for (int i = 0; i < frames.size(); i += step) pick.add(frames.get(i));
        int cols = 4;
        int rows = (pick.size() + cols - 1) / cols;
        int fw = W * VIEWS.length / 2, fh = H / 2;
        BufferedImage sheet = new BufferedImage(cols * fw, rows * fh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int i = 0; i < pick.size(); i++) {
            BufferedImage im = render(pick.get(i));
            g.drawImage(im, (i % cols) * fw, (i / cols) * fh, fw, fh, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", file);
    }

    static void writeGif(List<Frame> frames, File file, int delayCs) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream os = ImageIO.createImageOutputStream(file)) {
            w.setOutput(os);
            w.prepareWriteSequence(null);
            boolean first = true;
            for (Frame f : frames) {
                BufferedImage im = render(f);
                BufferedImage small = new BufferedImage(im.getWidth() * 3 / 4, im.getHeight() * 3 / 4, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = small.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(im, 0, 0, small.getWidth(), small.getHeight(), null);
                g.dispose();
                ImageTypeSpecifier spec = ImageTypeSpecifier.createFromRenderedImage(small);
                IIOMetadata md = w.getDefaultImageMetadata(spec, null);
                String fmt = md.getNativeMetadataFormatName();
                IIOMetadataNode rootNode = (IIOMetadataNode) md.getAsTree(fmt);
                IIOMetadataNode gce = child(rootNode, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", Integer.toString(delayCs));
                gce.setAttribute("transparentColorIndex", "0");
                if (first) {
                    IIOMetadataNode apps = child(rootNode, "ApplicationExtensions");
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, 0, 0});
                    apps.appendChild(app);
                    first = false;
                }
                md.setFromTree(fmt, rootNode);
                w.writeToSequence(new IIOImage(small, null, md), null);
            }
            w.endWriteSequence();
        }
    }

    static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }
}
