package com.kira.jujutsuneon.rbx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.util.RenderUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Модели R15 из Blockbench (запекает GeckoLib) и их отрисовка по позе.
 *
 * Пространство модели — как у GeckoLib: блоки, Y вверх, ноги на y = 0, лицом к −Z. Поза ставится прямо
 * в кости запечённой модели (поворот = исходный + анимация, смещение и масштаб — как в GeckoLib),
 * вершины выводятся так же, как это делает GeoRenderer. Сам GeckoLib не меняется.
 */
public final class R15Rig {

    public static final String NS = "jujutsu_neon";
    public static final ResourceLocation PLAYER_WIDE = new ResourceLocation(NS, "geo/player_r15.geo.json");
    public static final ResourceLocation PLAYER_SLIM = new ResourceLocation(NS, "geo/player_r15_slim.geo.json");
    public static final ResourceLocation ARMOR_OUTER = new ResourceLocation(NS, "geo/armor_r15_outer.geo.json");
    public static final ResourceLocation ARMOR_INNER = new ResourceLocation(NS, "geo/armor_r15_inner.geo.json");

    /** Части тела — как части ванильной модели (видимость, броня, слои одежды скина). */
    public enum Part {HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG}

    private static final Map<ResourceLocation, Model> MODELS = new HashMap<>();
    private static Map<ResourceLocation, BakedGeoModel> lastCache;

    private R15Rig() {
    }

    /** Запечённая модель (или null, если её нет — например, до загрузки ресурсов). */
    public static Model model(ResourceLocation loc) {
        Map<ResourceLocation, BakedGeoModel> cache;
        try {
            cache = GeckoLibCache.getBakedModels();
        } catch (RuntimeException e) {
            return null;
        }
        if (cache != lastCache) {
            lastCache = cache;
            MODELS.clear();
        }
        Model m = MODELS.get(loc);
        if (m == null) {
            BakedGeoModel baked = cache.get(loc);
            if (baked == null) return null;
            m = new Model(baked);
            MODELS.put(loc, m);
        }
        return m;
    }

    public static Part partOf(String bone) {
        String b = bone.endsWith("Layer") ? bone.substring(0, bone.length() - 5) : bone;
        return switch (b) {
            case Bones.HEAD -> Part.HEAD;
            case Bones.LOWER_TORSO, Bones.UPPER_TORSO -> Part.BODY;
            case Bones.R_UPPER_ARM, Bones.R_LOWER_ARM, Bones.R_HAND -> Part.RIGHT_ARM;
            case Bones.L_UPPER_ARM, Bones.L_LOWER_ARM, Bones.L_HAND -> Part.LEFT_ARM;
            case Bones.R_UPPER_LEG, Bones.R_LOWER_LEG, Bones.R_FOOT -> Part.RIGHT_LEG;
            case Bones.L_UPPER_LEG, Bones.L_LOWER_LEG, Bones.L_FOOT -> Part.LEFT_LEG;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ итоговая поза

    /** Итоговые значения костей для отрисовки (по номерам {@link Bones}), в соглашении GeoBone. */
    public static final class FinalPose {
        public double[][] rot = new double[0][], pos = new double[0][], scale = new double[0][];
        /** Поворот всего тела вокруг точки между ступнями (наклоны), кватернион. */
        public final double[] rootRot = {0, 0, 0, 1};
        /** Сдвиг всего тела, блоки (в пространстве модели). */
        public final double[] rootPos = new double[3];

        public void ensure(int n) {
            if (rot.length >= n) return;
            int old = rot.length;
            rot = Arrays.copyOf(rot, n);
            pos = Arrays.copyOf(pos, n);
            scale = Arrays.copyOf(scale, n);
            for (int i = old; i < n; i++) {
                rot[i] = new double[3];
                pos[i] = new double[3];
                scale[i] = new double[]{1, 1, 1};
            }
        }

        public void reset() {
            ensure(Bones.count());
            for (int i = 0; i < rot.length; i++) {
                Arrays.fill(rot[i], 0);
                Arrays.fill(pos[i], 0);
                Arrays.fill(scale[i], 1);
            }
            QMath.identity(rootRot);
            Arrays.fill(rootPos, 0);
        }
    }

    // ------------------------------------------------------------------ модель

    public static final class Model {
        public final BakedGeoModel baked;
        /** Все кости в порядке обхода (родитель раньше детей). */
        final GeoBone[] order;
        /** Номер кости в {@link Bones} для каждой кости из order. */
        final int[] index;
        /** Часть тела кости (или null) и признак «слой одежды» (*Layer). */
        final Part[] part;
        final boolean[] overlay;
        /** Исходное положение костей (как в файле модели). */
        final float[][] rest;
        private final Map<Integer, Integer> byIndex = new HashMap<>();

        Model(BakedGeoModel baked) {
            this.baked = baked;
            List<GeoBone> all = new ArrayList<>();
            for (GeoBone b : baked.topLevelBones()) collect(b, all);
            order = all.toArray(new GeoBone[0]);
            index = new int[order.length];
            part = new Part[order.length];
            overlay = new boolean[order.length];
            rest = new float[order.length][];
            for (int i = 0; i < order.length; i++) {
                GeoBone b = order[i];
                index[i] = Bones.index(b.getName());
                part[i] = partOf(b.getName());
                overlay[i] = b.getName().endsWith("Layer");
                rest[i] = new float[]{b.getRotX(), b.getRotY(), b.getRotZ(), b.getPosX(), b.getPosY(), b.getPosZ(),
                        b.getScaleX(), b.getScaleY(), b.getScaleZ()};
                byIndex.put(index[i], i);
            }
        }

        private static void collect(GeoBone b, List<GeoBone> out) {
            out.add(b);
            for (GeoBone c : b.getChildBones()) collect(c, out);
        }

        public GeoBone bone(String name) {
            Integer i = byIndex.get(Bones.find(name));
            return i == null ? null : order[i];
        }

        /** Поставить позу в кости модели. */
        public void apply(FinalPose p) {
            p.ensure(Bones.count());
            for (int i = 0; i < order.length; i++) {
                GeoBone b = order[i];
                float[] r = rest[i];
                int k = index[i];
                double[] rot = p.rot[k], pos = p.pos[k], sc = p.scale[k];
                b.setRotX(r[0] + (float) rot[0]);
                b.setRotY(r[1] + (float) rot[1]);
                b.setRotZ(r[2] + (float) rot[2]);
                b.setPosX(r[3] + (float) pos[0]);
                b.setPosY(r[4] + (float) pos[1]);
                b.setPosZ(r[5] + (float) pos[2]);
                b.setScaleX(r[6] * (float) sc[0]);
                b.setScaleY(r[7] * (float) sc[1]);
                b.setScaleZ(r[8] * (float) sc[2]);
            }
        }

        /** Вершина кости (в пространстве модели GeckoLib, пиксели) — точка вращения из файла. */
        public Vector3f pivot(String name) {
            GeoBone b = bone(name);
            return b == null ? new Vector3f() : new Vector3f(b.getPivotX(), b.getPivotY(), b.getPivotZ());
        }

        /**
         * Матрицы всех костей в пространстве модели (после {@link #apply}); out — по номерам {@link Bones}.
         * Корневой поворот/сдвиг позы учитывается.
         */
        public void matrices(FinalPose p, Matrix4f[] out) {
            matrices(p, out, null);
        }

        /** То же и точки суставов (точка вращения кости) в пространстве модели, блоки. */
        public void matrices(FinalPose p, Matrix4f[] out, org.joml.Vector3f[] joints) {
            PoseStack ps = new PoseStack();
            root(ps, p);
            walk(ps, baked.topLevelBones(), out, joints);
        }

        private void walk(PoseStack ps, List<GeoBone> bones, Matrix4f[] out, org.joml.Vector3f[] joints) {
            for (GeoBone b : bones) {
                ps.pushPose();
                RenderUtils.prepMatrixForBone(ps, b);
                Integer i = byIndex.get(Bones.find(b.getName()));
                if (i != null) {
                    int k = index[i];
                    if (k < out.length) {
                        if (out[k] == null) out[k] = new Matrix4f();
                        out[k].set(ps.last().pose());
                    }
                    if (joints != null && k < joints.length) {
                        if (joints[k] == null) joints[k] = new org.joml.Vector3f();
                        ps.last().pose().transformPosition(b.getPivotX() / 16f, b.getPivotY() / 16f, b.getPivotZ() / 16f, joints[k]);
                    }
                }
                walk(ps, b.getChildBones(), out, joints);
                ps.popPose();
            }
        }

        /**
         * Нарисовать модель. Матрица ps — пространство модели GeckoLib. visible — какие части видны,
         * overlays — какие слои одежды (null — все как части).
         */
        public void render(PoseStack ps, FinalPose p, VertexConsumer vc, int light, int overlayTex,
                           float r, float g, float b, float a, boolean[] visible, boolean[] overlays) {
            ps.pushPose();
            root(ps, p);
            for (GeoBone bone : baked.topLevelBones()) renderBone(ps, bone, vc, light, overlayTex, r, g, b, a, visible, overlays);
            ps.popPose();
        }

        private void renderBone(PoseStack ps, GeoBone bone, VertexConsumer vc, int light, int overlayTex,
                                float r, float g, float b, float a, boolean[] visible, boolean[] overlays) {
            ps.pushPose();
            RenderUtils.prepMatrixForBone(ps, bone);
            Integer i = byIndex.get(Bones.find(bone.getName()));
            boolean show = !bone.isHidden();
            if (show && i != null && part[i] != null) {
                int pi = part[i].ordinal();
                show = overlay[i] ? (overlays != null ? overlays[pi] : visible == null || visible[pi])
                        : (visible == null || visible[pi]);
            }
            if (show) {
                for (GeoCube cube : bone.getCubes()) renderCube(ps, cube, vc, light, overlayTex, r, g, b, a);
            }
            for (GeoBone c : bone.getChildBones()) renderBone(ps, c, vc, light, overlayTex, r, g, b, a, visible, overlays);
            ps.popPose();
        }
    }

    /** Корень: наклон всего тела вокруг точки между ступнями и общий сдвиг. */
    static void root(PoseStack ps, FinalPose p) {
        if (p.rootPos[0] != 0 || p.rootPos[1] != 0 || p.rootPos[2] != 0) {
            ps.translate(p.rootPos[0], p.rootPos[1], p.rootPos[2]);
        }
        double[] q = p.rootRot;
        if (q[3] < 0.999999) {
            ps.mulPose(new org.joml.Quaternionf((float) q[0], (float) q[1], (float) q[2], (float) q[3]));
        }
    }

    /** Как GeoRenderer#renderCube / #createVerticesOfQuad. */
    static void renderCube(PoseStack ps, GeoCube cube, VertexConsumer vc, int light, int overlayTex,
                           float r, float g, float b, float a) {
        ps.pushPose();
        RenderUtils.translateToPivotPoint(ps, cube);
        RenderUtils.rotateMatrixAroundCube(ps, cube);
        RenderUtils.translateAwayFromPivotPoint(ps, cube);
        Matrix3f normalMat = ps.last().normal();
        Matrix4f poseMat = ps.last().pose();
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) continue;
            Vector3f normal = normalMat.transform(new Vector3f(quad.normal()));
            RenderUtils.fixInvertedFlatCube(cube, normal);
            for (GeoVertex v : quad.vertices()) {
                Vector3f pos = v.position();
                Vector4f w = poseMat.transform(new Vector4f(pos.x(), pos.y(), pos.z(), 1.0f));
                vc.vertex(w.x(), w.y(), w.z(), r, g, b, a, v.texU(), v.texV(), overlayTex, light,
                        normal.x(), normal.y(), normal.z());
            }
        }
        ps.popPose();
    }
}
