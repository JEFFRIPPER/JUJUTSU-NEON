package com.kira.jujutsuneon.rbx;

import com.kira.jujutsuneon.JujutsuNeonSkillAnimationClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Игрок моделью R15 в ванильном рендере.
 *
 * Рендер игрока не заменяется: на время одной отрисовки у PlayerRenderer подменяется только модель
 * ({@link R15PlayerModel}) — она рисует сетку R15 в позе из {@link PlayerAnimations}, а ванильные части
 * (голова, корпус, руки, ноги) ставит по костям R15. Поэтому всё остальное работает как в ванили и с другими
 * модами: повороты тела (плавание, элитры, сон, смерть), ник, тень, невидимость, свечение, слои.
 * Броня рисуется по сегментам R15 ({@link R15ArmorModel}), предмет — в кисти, плащ и элитры идут за корпусом.
 * Пока у игрока идёт Фиолетовый, ничего не подменяется — он рисуется как раньше.
 */
@Mod.EventBusSubscriber(modid = R15Rig.NS, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class R15Render {

    /** Высота ванильной модели над ногами: LivingEntityRenderer сдвигает модель на −1.501. */
    static final float VANILLA_OFFSET = 1.501f;
    /** Ванильный рендер опускает присевшего игрока на 1/8 блока; позу присяда целиком задаёт анимация. */
    static final float CROUCH_OFFSET = 0.125f / 0.9375f;

    /** Что рисуется сейчас (между RenderPlayerEvent.Pre и Post). */
    static final class Context {
        AbstractClientPlayer player;
        PlayerAnimations.State state;
        R15PlayerModel model;
        R15Rig.Model skin, armorOuter, armorInner;
        boolean slim;
        float lift;
        /** Матрица пространства модели у слоёв (её ловит {@link CaptureLayer}). */
        Matrix4f layerPose;
        /** Поворот корпуса: ванильный → R15 (в пространстве ванильной модели) — для плаща и элитр. */
        final Matrix4f bodyDelta = new Matrix4f();
        /** Свои руки от первого лица ({@link R15FirstPerson}): видны только эти руки. */
        boolean firstPerson, fpRight, fpLeft;

        /** Видна ли часть тела в этой отрисовке (от первого лица — только показанные руки). */
        boolean partShown(R15Rig.Part part) {
            if (!firstPerson) return true;
            return (part == R15Rig.Part.RIGHT_ARM && fpRight) || (part == R15Rig.Part.LEFT_ARM && fpLeft);
        }
    }

    private static Context current;
    private static final Map<Object, PlayerModel<AbstractClientPlayer>> ORIGINALS = new IdentityHashMap<>();
    private static final Map<Object, Boolean> PREPARED = new WeakHashMap<>();
    /** Слои, убранные на время отрисовки рук от первого лица. */
    private static final Map<Object, List<Object>> SAVED_LAYERS = new IdentityHashMap<>();
    private static R15PlayerModel wideModel, slimModel;
    private static R15ArmorModel innerArmor, outerArmor;
    private static R15ElytraModel elytra;
    private static Boolean enabled;
    /** Чем кончилась последняя попытка подмены модели у игрока (для {@link #debugState}). */
    private static final Map<Object, String> LAST_RESULT = new WeakHashMap<>();

    private static Field rendererModel, rendererLayers, elytraField;
    private static boolean reflectionFailed;

    private R15Render() {
    }

    // ------------------------------------------------------------------ включение (настройка клиента)

    public static boolean enabled() {
        if (enabled == null) {
            enabled = true;
            try {
                Path f = settingsFile();
                if (Files.exists(f)) enabled = !Files.readString(f, StandardCharsets.UTF_8).contains("model=vanilla");
            } catch (IOException ignored) {
            }
        }
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        try {
            Path f = settingsFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, "# Jujutsu Neon: модель игрока (r15 — анимации из Blockbench, vanilla — ванильная)\nmodel="
                    + (on ? "r15" : "vanilla") + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static Path settingsFile() {
        return FMLPaths.CONFIGDIR.get().resolve("jujutsu_neon").resolve("client.properties");
    }

    /** Рисуется ли игрок моделью R15 прямо сейчас (для своего вида от первого лица и отладки). */
    public static boolean activeFor(AbstractClientPlayer p) {
        return enabled() && !JujutsuNeonSkillAnimationClient.purpleActive(p)
                && R15Rig.model(isSlim(p) ? R15Rig.PLAYER_SLIM : R15Rig.PLAYER_WIDE) != null;
    }

    /** Почему игрок рисуется (или не рисуется) моделью R15 — для отладки (/jnanim, режим тестера). */
    public static String debugState(AbstractClientPlayer p) {
        boolean slim = isSlim(p);
        return "enabled=" + enabled() + " reflectionFailed=" + reflectionFailed
                + " purple=" + JujutsuNeonSkillAnimationClient.purpleActive(p)
                + " slim=" + slim + " skinModel=" + (R15Rig.model(slim ? R15Rig.PLAYER_SLIM : R15Rig.PLAYER_WIDE) != null)
                + " active=" + activeFor(p) + " prepared=" + PREPARED.size()
                + " lastRender=" + LAST_RESULT.getOrDefault(p, "never");
    }

    static boolean isSlim(AbstractClientPlayer p) {
        return "slim".equals(p.getModelName());
    }

    // ------------------------------------------------------------------ события

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPre(RenderPlayerEvent.Pre event) {
        PlayerRenderer renderer = event.getRenderer();
        restore(renderer);
        current = null;
        if (!(event.getEntity() instanceof AbstractClientPlayer p)) return;
        if (reflectionFailed) {
            LAST_RESULT.put(p, "reflectionFailed");
            return;
        }
        if (!activeFor(p)) {
            LAST_RESULT.put(p, "inactive");
            return;
        }
        boolean slim = isSlim(p);
        R15Rig.Model skin = R15Rig.model(slim ? R15Rig.PLAYER_SLIM : R15Rig.PLAYER_WIDE);
        if (skin == null || !prepare(renderer)) {
            LAST_RESULT.put(p, skin == null ? "noSkinModel" : "prepareFailed");
            return;
        }
        PlayerModel<AbstractClientPlayer> original = renderer.getModel();
        if (original instanceof R15PlayerModel) {
            LAST_RESULT.put(p, "alreadyR15");
            return;
        }
        R15PlayerModel model = model(slim);
        model.copyState(original);
        if (!setModel(renderer, model)) {
            LAST_RESULT.put(p, "setModelFailed");
            return;
        }
        LAST_RESULT.put(p, "r15 model=" + original.getClass().getName());
        ORIGINALS.put(renderer, original);

        Context c = new Context();
        c.player = p;
        c.state = PlayerAnimations.state(p);
        c.model = model;
        c.skin = skin;
        c.armorOuter = R15Rig.model(R15Rig.ARMOR_OUTER);
        c.armorInner = R15Rig.model(R15Rig.ARMOR_INNER);
        c.slim = slim;
        c.lift = p.isCrouching() ? CROUCH_OFFSET : 0;
        if (R15FirstPerson.pass && p == Minecraft.getInstance().player) {
            c.firstPerson = true;
            c.fpRight = R15FirstPerson.showRight;
            c.fpLeft = R15FirstPerson.showLeft;
            keepFirstPersonLayers(renderer);
        }
        current = c;
    }

    /** От первого лица из слоёв остаются только броня и предметы в руках (плащ, головной предмет, стрелы — нет). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void keepFirstPersonLayers(PlayerRenderer renderer) {
        try {
            List<Object> layers = (List) rendererLayers.get(renderer);
            SAVED_LAYERS.put(renderer, new ArrayList<>(layers));
            layers.removeIf(l -> !(l instanceof CaptureLayer || l instanceof HumanoidArmorLayer<?, ?, ?>
                    || l instanceof ItemInHandLayer<?, ?>));
        } catch (ReflectiveOperationException | RuntimeException e) {
            RbxLog.LOGGER.warn("R15 first person: layer filter failed", e);
        }
    }

    /** Конец отрисовки рук от первого лица: вернуть модель и слои, даже если отрисовка упала. */
    static void endPass(PlayerRenderer renderer) {
        restore(renderer);
        current = null;
    }

    /** Если отрисовку отменил кто-то после нас — сразу вернуть всё как было. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onPreCancelled(RenderPlayerEvent.Pre event) {
        // свои руки от первого лица рисуются только моделью R15: если подменить не вышло — не рисовать ничего
        if (!event.isCanceled() && R15FirstPerson.pass && (current == null || current.player != event.getEntity())) {
            event.setCanceled(true);
        }
        if (event.isCanceled()) {
            restore(event.getRenderer());
            current = null;
        }
    }

    @SubscribeEvent
    public static void onPost(RenderPlayerEvent.Post event) {
        restore(event.getRenderer());
        current = null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restore(Object renderer) {
        PlayerModel<AbstractClientPlayer> original = ORIGINALS.remove(renderer);
        if (original != null) setModel(renderer, original);
        List<Object> saved = SAVED_LAYERS.remove(renderer);
        if (saved != null) {
            try {
                List<Object> layers = (List) rendererLayers.get(renderer);
                layers.clear();
                layers.addAll(saved);
            } catch (ReflectiveOperationException | RuntimeException e) {
                RbxLog.LOGGER.error("R15 first person: layer restore failed", e);
            }
        }
    }

    /** Контекст текущей отрисовки, если модель m сейчас рисует игрока p (p == null — любого). */
    static Context current(Object m, AbstractClientPlayer p) {
        Context c = current;
        if (c == null || c.model != m) return null;
        return p == null || c.player == p ? c : null;
    }

    static Context current() {
        return current;
    }

    // ------------------------------------------------------------------ модели и подмены

    private static R15PlayerModel model(boolean slim) {
        Minecraft mc = Minecraft.getInstance();
        if (slim) {
            if (slimModel == null) slimModel = new R15PlayerModel(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            return slimModel;
        }
        if (wideModel == null) wideModel = new R15PlayerModel(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        return wideModel;
    }

    /** Один раз на каждый рендер игрока: слой-ловушка матрицы, броня по сегментам, элитры за корпусом. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean prepare(PlayerRenderer renderer) {
        if (PREPARED.containsKey(renderer)) return true;
        try {
            if (rendererModel == null) {
                rendererModel = fieldOfType(LivingEntityRenderer.class, EntityModel.class);
                rendererLayers = fieldOfType(LivingEntityRenderer.class, List.class);
            }
            List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> layers =
                    (List) rendererLayers.get(renderer);
            layers.add(0, new CaptureLayer(renderer));
            Minecraft mc = Minecraft.getInstance();
            for (RenderLayer<?, ?> layer : layers) {
                if (layer instanceof HumanoidArmorLayer<?, ?, ?> armor) swapArmor(armor, mc);
                else if (layer instanceof ElytraLayer<?, ?> el) swapElytra(el, mc);
            }
            PREPARED.put(renderer, Boolean.TRUE);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            RbxLog.LOGGER.error("R15 player model disabled: renderer access failed", e);
            return false;
        }
    }

    private static void swapArmor(HumanoidArmorLayer<?, ?, ?> layer, Minecraft mc) throws ReflectiveOperationException {
        if (innerArmor == null) {
            innerArmor = new R15ArmorModel(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR), true);
            outerArmor = new R15ArmorModel(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR), false);
        }
        // какая из двух моделей внутренняя — спросить сам слой (поножи рисуются внутренней)
        Method get = null;
        for (Method m : HumanoidArmorLayer.class.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == EquipmentSlot.class
                    && HumanoidModel.class.isAssignableFrom(m.getReturnType())) {
                get = m;
                break;
            }
        }
        if (get == null) throw new NoSuchMethodException("HumanoidArmorLayer#getArmorModel");
        get.setAccessible(true);
        Object inner = get.invoke(layer, EquipmentSlot.LEGS);
        Object outer = get.invoke(layer, EquipmentSlot.CHEST);
        for (Field f : HumanoidArmorLayer.class.getDeclaredFields()) {
            if (!HumanoidModel.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v = f.get(layer);
            if (v == inner) f.set(layer, innerArmor);
            else if (v == outer) f.set(layer, outerArmor);
        }
    }

    private static void swapElytra(ElytraLayer<?, ?> layer, Minecraft mc) throws ReflectiveOperationException {
        if (elytraField == null) elytraField = fieldOfType(ElytraLayer.class, ElytraModel.class);
        if (elytra == null) elytra = new R15ElytraModel(mc.getEntityModels().bakeLayer(ModelLayers.ELYTRA));
        elytraField.set(layer, elytra);
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException(owner.getSimpleName() + " " + type.getSimpleName());
    }

    private static boolean setModel(Object renderer, PlayerModel<AbstractClientPlayer> model) {
        try {
            if (rendererModel == null) rendererModel = fieldOfType(LivingEntityRenderer.class, EntityModel.class);
            rendererModel.set(renderer, model);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            RbxLog.LOGGER.error("R15 player model disabled: renderer model swap failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ пространства

    /** Из пространства ванильной модели (Y вниз, у макушки) — в пространство модели GeckoLib (Y вверх, у ног). */
    static void toGeo(PoseStack ps, Context c) {
        ps.translate(0, VANILLA_OFFSET - c.lift, 0);
        ps.scale(-1, -1, 1);
    }

    static Matrix4f toGeoMatrix(Context c) {
        return new Matrix4f().translate(0, VANILLA_OFFSET - c.lift, 0).scale(-1, -1, 1);
    }

    /** Точка ванильной модели (пиксели) → пространство GeckoLib (пиксели), без сдвига присяда. */
    static Vector3f vanillaToGeoPx(float x, float y, float z) {
        return new Vector3f(-x, VANILLA_OFFSET * 16 - y, z);
    }

    /**
     * Рамка ванильной части, жёстко прикреплённой к кости R15: точка (vx, vy, vz) ванильной модели в покое
     * (пиксели) переносится вместе с костью; оси — оси кости в соглашении ванили. Пространство — ванильной модели.
     */
    static Matrix4f partFrame(Context c, String bone, float vx, float vy, float vz, Matrix4f out) {
        Matrix4f m = c.state.boneMatrix(bone);
        if (m == null) return null;
        // матрица кости GeckoLib уже содержит перенос «от пивота», поэтому точку покоя берём как есть
        Vector3f g = vanillaToGeoPx(vx, vy, vz).div(16);
        out.set(toGeoMatrix(c)).mul(m).translate(g).scale(-1, -1, 1);
        return out;
    }

    /** ps · m (с нормалями). */
    static void mulPose(PoseStack ps, Matrix4f m) {
        PoseStack.Pose last = ps.last();
        last.pose().mul(m);
        last.normal().mul(rotationOf(m));
    }

    /** Поворот из матрицы (столбцы 3×3 без масштаба). */
    static Matrix3f rotationOf(Matrix4f m) {
        Matrix3f n = new Matrix3f(m);
        Vector3f c0 = n.getColumn(0, new Vector3f()).normalize();
        Vector3f c1 = n.getColumn(1, new Vector3f()).normalize();
        Vector3f c2 = n.getColumn(2, new Vector3f()).normalize();
        return n.setColumn(0, c0).setColumn(1, c1).setColumn(2, c2);
    }

    /** Разложить матрицу на ванильные x, y, z (пиксели) и xRot, yRot, zRot (R = Rz·Ry·Rx). */
    static void decompose(Matrix4f m, ModelPart part) {
        Vector3f t = m.getTranslation(new Vector3f());
        part.x = t.x * 16;
        part.y = t.y * 16;
        part.z = t.z * 16;
        Matrix3f r = new Matrix3f(m);
        // убрать масштаб (если кость масштабирована анимацией)
        Vector3f c0 = r.getColumn(0, new Vector3f()).normalize();
        Vector3f c1 = r.getColumn(1, new Vector3f()).normalize();
        Vector3f c2 = r.getColumn(2, new Vector3f()).normalize();
        // R[row][col]: R20 = c0.z, R21 = c1.z, R22 = c2.z, R10 = c0.y, R00 = c0.x
        float sy = Math.max(-1, Math.min(1, -c0.z));
        part.yRot = (float) Math.asin(sy);
        if (Math.abs(sy) < 0.9999f) {
            part.xRot = (float) Math.atan2(c1.z, c2.z);
            part.zRot = (float) Math.atan2(c0.y, c0.x);
        } else {
            part.xRot = (float) Math.atan2(-c2.y, c1.y);
            part.zRot = 0;
        }
    }

    /** Матрица ванильной части (как ModelPart#translateAndRotate). */
    static Matrix4f partMatrix(ModelPart p, Matrix4f out) {
        return out.identity().translate(p.x / 16f, p.y / 16f, p.z / 16f)
                .rotate(new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot));
    }

    /** ps · (P · Δ · P⁻¹): переставить то, что слой рисует от ванильного корпуса, на корпус R15. */
    static void applyBodyDelta(PoseStack ps, Context c) {
        if (c.layerPose == null) return;
        Matrix4f w = new Matrix4f(c.layerPose).mul(c.bodyDelta).mul(new Matrix4f(c.layerPose).invert());
        PoseStack.Pose last = ps.last();
        last.pose().set(new Matrix4f(w).mul(last.pose()));
        last.normal().set(rotationOf(w).mul(last.normal()));
    }

    // ------------------------------------------------------------------ слой-ловушка

    /** Первый слой: запоминает матрицу пространства модели (для плаща и элитр). Сам ничего не рисует. */
    static final class CaptureLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        CaptureLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int light, AbstractClientPlayer p,
                           float limbSwing, float limbAmount, float pt, float age, float netYaw, float pitch) {
            Context c = current;
            if (c != null && c.player == p) c.layerPose = new Matrix4f(ps.last().pose());
        }
    }
}
