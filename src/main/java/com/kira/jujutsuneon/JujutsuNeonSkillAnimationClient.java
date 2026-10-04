package com.kira.jujutsuneon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Full-body technique animation layer.
 *
 * Design rules:
 * - vanilla locomotion stays the base pose;
 * - a skill animation is applied only while its technique is active;
 * - the player renderer itself is not replaced permanently;
 * - the temporary model subclasses PlayerModel and calls super.setupAnim first,
 *   so armor/held-item layers keep following the same animated limbs;
 * - Blue and Hollow Purple are the first two reference-driven animations.
 *
 * This class deliberately avoids a hard dependency on a third-party player-animation
 * library. The renderer model is swapped only for the duration of one render call and
 * restored in RenderPlayerEvent.Post.
 */
@Mod.EventBusSubscriber(
        modid = JujutsuNeonMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class JujutsuNeonSkillAnimationClient {

    private enum Skill {
        NONE,
        BLUE_CHARGE,
        BLUE,
        MAX_BLUE,
        PURPLE,
        MAX_PURPLE,
        FRONT_DASH,
        BACK_DASH,
        SIDE_DASH_LEFT,
        SIDE_DASH_RIGHT
    }

    private record SkillFrame(Skill skill, float progress) {
        static final SkillFrame NONE = new SkillFrame(Skill.NONE, 0.0f);
    }

    private static final Class<?> CLIENT_EVENTS_CLASS;
    private static final Field ACTIVE_ANIM;
    private static final Field ACTIVE_ANIM_TICKS;
    private static final Field ACTIVE_ANIM_LENGTH;
    private static final Field CHARGING_ANIM;
    private static final Field CHARGING_PROGRESS;
    private static final Field HUD_BLINDFOLD;

    private static final Field RENDERER_MODEL_FIELD;

    private static AnimatedPlayerModel defaultAnimatedModel;
    private static AnimatedPlayerModel slimAnimatedModel;

    /** Renderer -> original model for the current render call. */
    private static final Map<Object, PlayerModel<AbstractClientPlayer>> ORIGINAL_MODELS = new IdentityHashMap<>();

    static {
        try {
            CLIENT_EVENTS_CLASS = Class.forName("com.kira.jujutsuneon.JujutsuNeonMod$ClientForgeEvents");

            ACTIVE_ANIM = staticField(CLIENT_EVENTS_CLASS, "activeAnim");
            ACTIVE_ANIM_TICKS = staticField(CLIENT_EVENTS_CLASS, "activeAnimTicks");
            ACTIVE_ANIM_LENGTH = staticField(CLIENT_EVENTS_CLASS, "activeAnimLength");
            CHARGING_ANIM = staticField(CLIENT_EVENTS_CLASS, "chargingAnim");
            CHARGING_PROGRESS = staticField(CLIENT_EVENTS_CLASS, "chargingProgress");
            HUD_BLINDFOLD = staticField(CLIENT_EVENTS_CLASS, "hudBlindfold");

            RENDERER_MODEL_FIELD = findRendererModelField();
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private JujutsuNeonSkillAnimationClient() {
    }

    private static Field staticField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /**
     * The Mojang name of LivingEntityRenderer#model changes under reobfuscation.
     * Find the field by erased type instead of relying on the runtime field name.
     */
    private static Field findRendererModelField() throws NoSuchFieldException {
        Class<?> cursor;
        try {
            cursor = Class.forName("net.minecraft.client.renderer.entity.LivingEntityRenderer");
        } catch (ClassNotFoundException ignored) {
            cursor = net.minecraft.client.renderer.entity.player.PlayerRenderer.class;
        }

        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (EntityModel.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            cursor = cursor.getSuperclass();
        }

        throw new NoSuchFieldException("LivingEntityRenderer model field");
    }

    private static boolean hasBlindfold() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null &&
                mc.player.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get());
    }

    private static boolean hudBlindfold() {
        try {
            return HUD_BLINDFOLD.getBoolean(null);
        } catch (IllegalAccessException ignored) {
            return hasBlindfold();
        }
    }

    private static SkillFrame currentFrame() {
        try {
            String charging = (String) CHARGING_ANIM.get(null);
            float chargeProgress = CHARGING_PROGRESS.getFloat(null);

            // First second of holding Z: reference preparation/hand-sign phase.
            if ("CHARGE_BLUE".equals(charging)) {
                return new SkillFrame(Skill.BLUE_CHARGE, clamp01(chargeProgress));
            }

            String active = (String) ACTIVE_ANIM.get(null);
            int ticks = ACTIVE_ANIM_TICKS.getInt(null);
            int length = Math.max(1, ACTIVE_ANIM_LENGTH.getInt(null));
            if (ticks <= 0 || "NONE".equals(active)) return SkillFrame.NONE;
            float progress = clamp01(1.0f - ticks / (float) length);

            return switch (active) {
                case "BLUE" -> new SkillFrame(Skill.BLUE, progress);
                case "MAX_BLUE" -> new SkillFrame(Skill.MAX_BLUE, progress);
                case "PURPLE_CAST", "HOLLOW_PURPLE" -> new SkillFrame(Skill.PURPLE, progress);
                // MAX_PURPLE анимирует Player Animator (MaximumPurpleAnimation) — модель не подменяем.
                case "FRONT_DASH" -> new SkillFrame(Skill.FRONT_DASH, progress);
                case "BACK_DASH" -> new SkillFrame(Skill.BACK_DASH, progress);
                case "SIDE_DASH_LEFT" -> new SkillFrame(Skill.SIDE_DASH_LEFT, progress);
                case "SIDE_DASH_RIGHT" -> new SkillFrame(Skill.SIDE_DASH_RIGHT, progress);
                default -> SkillFrame.NONE;
            };
        } catch (IllegalAccessException ignored) {
            return SkillFrame.NONE;
        }
    }

    @SuppressWarnings("unchecked")
    private static PlayerModel<AbstractClientPlayer> rendererModel(Object renderer) {
        try {
            return (PlayerModel<AbstractClientPlayer>) RENDERER_MODEL_FIELD.get(renderer);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static void setRendererModel(Object renderer, PlayerModel<AbstractClientPlayer> model) {
        try {
            RENDERER_MODEL_FIELD.set(renderer, model);
        } catch (IllegalAccessException ignored) {
        }
    }

    private static AnimatedPlayerModel animatedModel(boolean slim) {
        Minecraft mc = Minecraft.getInstance();
        if (slim) {
            if (slimAnimatedModel == null) {
                slimAnimatedModel = new AnimatedPlayerModel(
                        mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM),
                        true
                );
            }
            return slimAnimatedModel;
        }

        if (defaultAnimatedModel == null) {
            defaultAnimatedModel = new AnimatedPlayerModel(
                    mc.getEntityModels().bakeLayer(ModelLayers.PLAYER),
                    false
            );
        }
        return defaultAnimatedModel;
    }

    private static void copyRendererState(
            PlayerModel<AbstractClientPlayer> source,
            AnimatedPlayerModel target,
            SkillFrame frame
    ) {
        target.frame = frame;

        // PlayerRenderer#setModelProperties has already run before RenderPlayerEvent.Pre.
        // Carry those state fields over to the temporary model before LivingEntityRenderer
        // calls prepareMobModel/setupAnim.
        target.leftArmPose = source.leftArmPose;
        target.rightArmPose = source.rightArmPose;
        target.crouching = source.crouching;
        target.swimAmount = source.swimAmount;

        target.head.visible = source.head.visible;
        target.hat.visible = source.hat.visible;
        target.body.visible = source.body.visible;
        target.rightArm.visible = source.rightArm.visible;
        target.leftArm.visible = source.leftArm.visible;
        target.rightLeg.visible = source.rightLeg.visible;
        target.leftLeg.visible = source.leftLeg.visible;
        target.jacket.visible = source.jacket.visible;
        target.rightSleeve.visible = source.rightSleeve.visible;
        target.leftSleeve.visible = source.leftSleeve.visible;
        target.rightPants.visible = source.rightPants.visible;
        target.leftPants.visible = source.leftPants.visible;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player) return;

        Object renderer = event.getRenderer();

        // Defensive restore in case a third-party renderer cancelled a previous render
        // after our model swap and prevented RenderPlayerEvent.Post from firing.
        PlayerModel<AbstractClientPlayer> stuckOriginal = ORIGINAL_MODELS.remove(renderer);
        if (stuckOriginal != null) setRendererModel(renderer, stuckOriginal);

        if (!hudBlindfold() || !hasBlindfold()) return;

        SkillFrame frame = currentFrame();
        if (frame.skill == Skill.NONE) return;

        PlayerModel<AbstractClientPlayer> original = rendererModel(renderer);
        if (original == null || original instanceof AnimatedPlayerModel) return;

        boolean slim = "slim".equals(((AbstractClientPlayer) event.getEntity()).getModelName());
        AnimatedPlayerModel animated = animatedModel(slim);
        copyRendererState(original, animated, frame);

        ORIGINAL_MODELS.put(renderer, original);
        setRendererModel(renderer, animated);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        Object renderer = event.getRenderer();
        PlayerModel<AbstractClientPlayer> original = ORIGINAL_MODELS.remove(renderer);
        if (original != null) setRendererModel(renderer, original);
    }

    private static final class AnimatedPlayerModel extends PlayerModel<AbstractClientPlayer> {
        private SkillFrame frame = SkillFrame.NONE;

        AnimatedPlayerModel(ModelPart root, boolean slim) {
            super(root, slim);
        }

        @Override
        public void setupAnim(
                AbstractClientPlayer player,
                float limbSwing,
                float limbSwingAmount,
                float ageInTicks,
                float netHeadYaw,
                float headPitch
        ) {
            // Vanilla stays authoritative for idle/walk/sprint/crouch/head tracking.
            super.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            SkillFrame localFrame = frame;
            if (localFrame.skill == Skill.NONE) return;

            switch (localFrame.skill) {
                case BLUE_CHARGE -> applyBlueCharge(localFrame.progress, ageInTicks);
                case BLUE -> applyNormalBlue(localFrame.progress, ageInTicks);
                case MAX_BLUE -> applyMaximumBlue(localFrame.progress, ageInTicks);
                case PURPLE -> applyPurple(localFrame.progress, ageInTicks);
                case MAX_PURPLE -> applyMaxPurple(localFrame.progress, ageInTicks);
                case FRONT_DASH -> applyFrontDash(localFrame.progress);
                case BACK_DASH -> applyBackDash(localFrame.progress);
                case SIDE_DASH_LEFT -> applySideDash(localFrame.progress, -1.0f);
                case SIDE_DASH_RIGHT -> applySideDash(localFrame.progress, 1.0f);
                default -> {
                }
            }

            syncWearLayers();
        }

        /**
         * Z hold preparation: player compresses inward toward the forming Blue.
         * This is intentionally subtle so the violent MAX_BLUE bloom has somewhere
         * to escalate from.
         */
        private void applyBlueCharge(float progress, float age) {
            float p = smooth(progress);
            float pulse = (float) Math.sin(age * 0.32f) * 0.035f * p;

            rotate(body,
                    lerp(body.xRot, rad(7.0f), p),
                    lerp(body.yRot, rad(-9.0f), p),
                    lerp(body.zRot, rad(-2.0f), p));

            // Right hand rises toward the upper torso/face where the seed is born.
            rotate(rightArm,
                    lerp(rightArm.xRot, rad(-61.0f) + pulse, p),
                    lerp(rightArm.yRot, rad(-24.0f), p),
                    lerp(rightArm.zRot, rad(31.0f), p));

            // Left hand braces under/across the chest.
            rotate(leftArm,
                    lerp(leftArm.xRot, rad(-43.0f) - pulse, p),
                    lerp(leftArm.yRot, rad(33.0f), p),
                    lerp(leftArm.zRot, rad(-29.0f), p));

            rotate(rightLeg,
                    lerp(rightLeg.xRot, rad(8.0f), p),
                    lerp(rightLeg.yRot, rad(-5.0f), p),
                    lerp(rightLeg.zRot, rad(3.0f), p));
            rotate(leftLeg,
                    lerp(leftLeg.xRot, rad(-5.0f), p),
                    lerp(leftLeg.yRot, rad(7.0f), p),
                    lerp(leftLeg.zRot, rad(-4.0f), p));

            head.xRot = lerp(head.xRot, head.xRot + rad(4.0f), p * 0.55f);
        }

        /** Short normal Blue: same visual language, compressed into a snap cast. */
        private void applyNormalBlue(float progress, float age) {
            float in = smooth(clamp01(progress / 0.28f));
            float out = 1.0f - smooth(clamp01((progress - 0.72f) / 0.28f));
            float w = Math.min(in, out);

            rotate(body,
                    lerp(body.xRot, rad(5.0f), w),
                    lerp(body.yRot, rad(-12.0f), w),
                    body.zRot);
            rotate(rightArm,
                    lerp(rightArm.xRot, rad(-88.0f), w),
                    lerp(rightArm.yRot, rad(-13.0f), w),
                    lerp(rightArm.zRot, rad(8.0f), w));
            rotate(leftArm,
                    lerp(leftArm.xRot, rad(-38.0f), w),
                    lerp(leftArm.yRot, rad(28.0f), w),
                    lerp(leftArm.zRot, rad(-24.0f), w));
        }

        /**
         * Reference Maximum Blue body choreography.
         * 0.00-0.18  seed posture, compact around the torso
         * 0.18-0.52  violent bloom: stance opens and the casting arm follows it
         * 0.52-0.88  sustained control pose
         * 0.88-1.00  settle/release accent
         */
        private void applyMaximumBlue(float progress, float age) {
            float seed = smooth(clamp01(progress / 0.18f));
            float bloom = smooth(clamp01((progress - 0.18f) / 0.34f));
            float control = smooth(clamp01((progress - 0.52f) / 0.26f));
            float release = smooth(clamp01((progress - 0.88f) / 0.12f));
            float pulse = (float) Math.sin(age * 0.43f) * (1.0f - release);

            // Start from the compact charge pose.
            float bodyX = mixDeg(7.0f, 2.0f, bloom);
            float bodyY = mixDeg(-9.0f, -18.0f, bloom);
            bodyY = mixDeg(bodyY, -12.0f, release);
            rotate(body, rad(bodyX), rad(bodyY), rad(mixDeg(-2.0f, -5.0f, bloom)));

            // Casting arm moves from chest to the outside/front of the anomaly.
            float rightX = mixDeg(-61.0f, -82.0f, bloom);
            float rightY = mixDeg(-24.0f, -19.0f, bloom);
            float rightZ = mixDeg(31.0f, 10.0f, bloom);
            rightX = mixDeg(rightX, -94.0f, control * 0.55f);
            rightX = mixDeg(rightX, -76.0f, release);
            rightZ += pulse * 2.2f;
            rotate(rightArm, rad(rightX), rad(rightY), rad(rightZ));

            // Support arm stays close to the torso, then opens slightly as Blue grows.
            float leftX = mixDeg(-43.0f, -53.0f, bloom);
            float leftY = mixDeg(33.0f, 39.0f, bloom);
            float leftZ = mixDeg(-29.0f, -18.0f, bloom);
            leftZ += pulse * 1.5f;
            rotate(leftArm, rad(leftX), rad(leftY), rad(leftZ));

            // Wide planted stance sells the mass/pull of the technique.
            rotate(rightLeg,
                    rad(mixDeg(8.0f, 12.0f, bloom)),
                    rad(-8.0f),
                    rad(5.0f));
            rotate(leftLeg,
                    rad(mixDeg(-5.0f, -9.0f, bloom)),
                    rad(10.0f),
                    rad(-6.0f));

            // Preserve camera head tracking but add the slight reference dip.
            head.xRot += rad(3.0f * Math.max(seed, bloom) * (1.0f - release));
        }

        /**
         * Hollow Purple reference choreography:
         * crossed hands at the chest -> controlled hold -> right-hand push/release.
         */
        private void applyPurple(float progress, float age) {
            float crossIn = smooth(clamp01(progress / 0.22f));
            float hold = smooth(clamp01((progress - 0.22f) / 0.42f));
            float preparePush = smooth(clamp01((progress - 0.64f) / 0.16f));
            float push = smooth(clamp01((progress - 0.80f) / 0.20f));
            float pulse = (float) Math.sin(age * 0.27f) * (1.0f - push);

            float weight = Math.max(crossIn, Math.max(hold, preparePush));

            rotate(body,
                    lerp(body.xRot, rad(5.0f), weight),
                    lerp(body.yRot, rad(-4.0f + 9.0f * push), weight),
                    lerp(body.zRot, rad(0.0f), weight));

            // Cross both arms over the chest. The asymmetry prevents the pose from
            // reading as a flat X when seen from 3/4 angles.
            float rightX = mixDeg(-12.0f, -58.0f, crossIn);
            float rightY = mixDeg(0.0f, -48.0f, crossIn);
            float rightZ = mixDeg(0.0f, 43.0f, crossIn);

            float leftX = mixDeg(-8.0f, -54.0f, crossIn);
            float leftY = mixDeg(0.0f, 46.0f, crossIn);
            float leftZ = mixDeg(0.0f, -39.0f, crossIn);

            // During the hold the crossed pose subtly breathes instead of freezing.
            rightZ += pulse * 1.7f;
            leftZ -= pulse * 1.4f;

            // Before release, right shoulder uncoils from the cross.
            rightY = mixDeg(rightY, -14.0f, preparePush);
            rightZ = mixDeg(rightZ, 11.0f, preparePush);
            rightX = mixDeg(rightX, -75.0f, preparePush);

            // Final reference beat: right arm thrusts forward to send Purple away.
            rightX = mixDeg(rightX, -101.0f, push);
            rightY = mixDeg(rightY, -6.0f, push);
            rightZ = mixDeg(rightZ, 2.0f, push);

            // Left arm stays anchored across the torso while the right hand releases.
            leftX = mixDeg(leftX, -48.0f, push);
            leftY = mixDeg(leftY, 38.0f, push);
            leftZ = mixDeg(leftZ, -31.0f, push);

            rotate(rightArm, rad(rightX), rad(rightY), rad(rightZ));
            rotate(leftArm, rad(leftX), rad(leftY), rad(leftZ));

            // Stable, slightly offset stance; no skating leg animation while casting.
            rotate(rightLeg,
                    rad(mixDeg(0.0f, 5.0f, weight)),
                    rad(-5.0f * weight),
                    rad(3.0f * weight));
            rotate(leftLeg,
                    rad(mixDeg(0.0f, -4.0f, weight)),
                    rad(6.0f * weight),
                    rad(-3.0f * weight));

            head.xRot += rad(2.0f * weight - 3.0f * push);
        }

        /**
         * Максимальный Фиолетовый, позы по кадрам референса (тики кат-сцены):
         * присед с рукой у лица -> рука вверх -> обе вверх -> руки вниз-в стороны ->
         * руки разведены (шары у плеч) -> голова опущена (крупный план) ->
         * руки вперёд, сводят шары -> руки широко раскинуты во вспышке.
         * Значения: bodyX, bodyY, headX, rArmX, rArmY, rArmZ, lArmX, lArmY, lArmZ, rLegX, lLegX (градусы).
         */
        private static final float[][] MAX_PURPLE_KEYS = {
                // t, bodyX, bodyY, headX, rArmX, rArmY, rArmZ, lArmX, lArmY, lArmZ, rLegX, lLegX
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {3, 0, -6, 0, -80, -10, 0, -5, 0, -5, 0, 0},          // Синий в руке
                {11, 0, -6, 0, -80, -10, 0, -5, 0, -5, 0, 0},
                {14, -5, 0, -15, -175, 0, 10, -5, 0, -8, 0, 0},     // бросок вверх
                {19, -3, 0, -15, -165, 0, 10, -5, 0, -8, 0, 0},
                {27, 0, 0, -8, -5, 0, 5, -5, 0, -5, 0, 0},
                {35, 0, 0, 0, -5, 0, 5, -5, 0, -5, 0, 0},
                {37, 4, -8, 0, -60, -25, 10, -10, 0, -10, 0, 0},     // Красный у груди
                {44, 4, -8, 0, -60, -25, 10, -10, 0, -10, 0, 0},
                {47, 22, -10, -12, -125, -25, 10, -30, 0, -20, -25, 20}, // присед, вихрь
                {56, 22, -10, -12, -125, -25, 10, -30, 0, -20, -25, 20},
                {60, 0, 0, 8, -150, -30, 20, -20, 0, -15, 0, 0},     // Красный к лицу
                {66, 0, 0, 8, -150, -30, 20, -20, 0, -15, 0, 0},
                {68, 0, 0, -20, -178, 0, 6, 10, 0, -12, 0, 0},       // выстрел вверх
                {76, 0, 0, -20, -178, 0, 6, 10, 0, -12, 0, 0},
                {80, 0, 0, -10, -10, 0, 10, -10, 0, -10, 0, 0},
                {82, 28, 0, -20, 35, 0, 15, 35, 0, -15, -35, 20},     // присед перед прыжком
                {84, 28, 0, -20, 35, 0, 15, 35, 0, -15, -35, 20},
                {86, -5, 0, -10, -170, 0, 20, -170, 0, -20, 10, -10},  // толчок, руки вверх
                {92, 10, 0, 0, -60, 0, 30, -60, 0, -30, -60, -60},     // группировка в сальто
                {98, 0, 0, 0, -10, 0, 55, -10, 0, -55, 5, -5},         // в небе, руки разведены
                {114, 0, 0, 0, -10, 0, 55, -10, 0, -55, 5, -5},
                {117, 0, 0, 12, -10, 0, 55, -10, 0, -55, 5, -5},       // лицо, голова опущена
                {128, 0, 0, 12, -10, 0, 55, -10, 0, -55, 5, -5},
                {132, 4, 0, 0, -88, -22, 0, -88, 22, 0, 6, -6},         // сводит шары
                {144, 4, 0, 0, -88, -22, 0, -88, 22, 0, 6, -6},
                {156, -10, 0, -20, -40, 0, 105, -40, 0, -105, 10, -10}, // руки раскинуты во вспышке
                {212, -10, 0, -20, -40, 0, 105, -40, 0, -105, 10, -10},
        };

        private void applyMaxPurple(float progress, float age) {
            float t = clamp01(progress) * MaximumPurple.ANIM_TICKS;
            float[] a = MAX_PURPLE_KEYS[0];
            float[] b = MAX_PURPLE_KEYS[MAX_PURPLE_KEYS.length - 1];
            for (int i = 0; i + 1 < MAX_PURPLE_KEYS.length; i++) {
                if (t >= MAX_PURPLE_KEYS[i][0] && t <= MAX_PURPLE_KEYS[i + 1][0]) {
                    a = MAX_PURPLE_KEYS[i];
                    b = MAX_PURPLE_KEYS[i + 1];
                    break;
                }
            }
            float span = Math.max(0.001f, b[0] - a[0]);
            float w = smooth((t - a[0]) / span);
            float[] p = new float[12];
            for (int i = 1; i < 12; i++) p[i] = a[i] + (b[i] - a[i]) * w;
            float breath = (float) Math.sin(age * 0.3f) * 1.5f;

            rotate(body, rad(p[1]), rad(p[2]), 0.0f);
            head.xRot = rad(p[3]);
            rotate(rightArm, rad(p[4]), rad(p[5]), rad(p[6] + breath));
            rotate(leftArm, rad(p[7]), rad(p[8]), rad(p[9] - breath));
            rotate(rightLeg, rad(p[10]), 0.0f, rad(3.0f));
            rotate(leftLeg, rad(p[11]), 0.0f, rad(-3.0f));
        }

        /**
         * Reference front dash.  This is a deliberate full-body burst pose, not a
         * sped-up vanilla run: rapid compression -> long forward drive -> recovery.
         */
        private void applyFrontDash(float progress) {
            float enter = smooth(clamp01(progress / 0.12f));
            float exit = 1.0f - smooth(clamp01((progress - 0.75f) / 0.25f));
            float w = Math.min(enter, exit);
            if (w <= 0.001f) return;

            // Strong forward commitment while the head remains readable instead of
            // rotating down with the torso like a rigid mannequin.
            rotate(body,
                    lerp(body.xRot, rad(43.0f), w),
                    lerp(body.yRot, rad(-2.0f), w),
                    lerp(body.zRot, rad(0.0f), w));
            head.xRot = lerp(head.xRot, head.xRot - rad(21.0f), w);
            head.zRot = lerp(head.zRot, rad(0.0f), w);

            // Compact aerodynamic arms; slight asymmetry keeps the silhouette alive.
            rotate(rightArm,
                    lerp(rightArm.xRot, rad(54.0f), w),
                    lerp(rightArm.yRot, rad(-13.0f), w),
                    lerp(rightArm.zRot, rad(12.0f), w));
            rotate(leftArm,
                    lerp(leftArm.xRot, rad(38.0f), w),
                    lerp(leftArm.yRot, rad(15.0f), w),
                    lerp(leftArm.zRot, rad(-14.0f), w));

            // One leg drives backward while the other tucks under the body.
            rotate(rightLeg,
                    lerp(rightLeg.xRot, rad(39.0f), w),
                    lerp(rightLeg.yRot, rad(-5.0f), w),
                    lerp(rightLeg.zRot, rad(4.0f), w));
            rotate(leftLeg,
                    lerp(leftLeg.xRot, rad(-27.0f), w),
                    lerp(leftLeg.yRot, rad(7.0f), w),
                    lerp(leftLeg.zRot, rad(-4.0f), w));
        }

        /** Reverse longitudinal dash: opposite momentum, dedicated silhouette. */
        private void applyBackDash(float progress) {
            float enter = smooth(clamp01(progress / 0.12f));
            float exit = 1.0f - smooth(clamp01((progress - 0.75f) / 0.25f));
            float w = Math.min(enter, exit);
            if (w <= 0.001f) return;

            rotate(body,
                    lerp(body.xRot, rad(-32.0f), w),
                    lerp(body.yRot, rad(2.0f), w),
                    lerp(body.zRot, rad(0.0f), w));
            head.xRot = lerp(head.xRot, head.xRot + rad(16.0f), w);
            head.zRot = lerp(head.zRot, rad(0.0f), w);

            rotate(rightArm,
                    lerp(rightArm.xRot, rad(-46.0f), w),
                    lerp(rightArm.yRot, rad(-11.0f), w),
                    lerp(rightArm.zRot, rad(10.0f), w));
            rotate(leftArm,
                    lerp(leftArm.xRot, rad(-34.0f), w),
                    lerp(leftArm.yRot, rad(13.0f), w),
                    lerp(leftArm.zRot, rad(-12.0f), w));

            rotate(rightLeg,
                    lerp(rightLeg.xRot, rad(-34.0f), w),
                    lerp(rightLeg.yRot, rad(-5.0f), w),
                    lerp(rightLeg.zRot, rad(4.0f), w));
            rotate(leftLeg,
                    lerp(leftLeg.xRot, rad(24.0f), w),
                    lerp(leftLeg.yRot, rad(7.0f), w),
                    lerp(leftLeg.zRot, rad(-4.0f), w));
        }

        /**
         * Mirrored side-dash pose from the reference.  side=-1 is left, +1 is right.
         * The body banks into the dash while the head counter-rotates toward control.
         */
        private void applySideDash(float progress, float side) {
            float enter = smooth(clamp01(progress / 0.18f));
            float exit = 1.0f - smooth(clamp01((progress - 0.66f) / 0.34f));
            float w = Math.min(enter, exit);
            if (w <= 0.001f) return;

            rotate(body,
                    lerp(body.xRot, rad(10.0f), w),
                    lerp(body.yRot, rad(-6.0f * side), w),
                    lerp(body.zRot, rad(-11.0f * side), w));

            head.xRot = lerp(head.xRot, head.xRot - rad(4.0f), w);
            head.yRot = lerp(head.yRot, head.yRot + rad(5.0f * side), w);
            head.zRot = lerp(head.zRot, rad(6.0f * side), w);

            if (side > 0.0f) {
                rotate(rightArm,
                        lerp(rightArm.xRot, rad(30.0f), w),
                        lerp(rightArm.yRot, rad(-10.0f), w),
                        lerp(rightArm.zRot, rad(16.0f), w));
                rotate(leftArm,
                        lerp(leftArm.xRot, rad(-18.0f), w),
                        lerp(leftArm.yRot, rad(-18.0f), w),
                        lerp(leftArm.zRot, rad(-20.0f), w));
            } else {
                rotate(rightArm,
                        lerp(rightArm.xRot, rad(-18.0f), w),
                        lerp(rightArm.yRot, rad(18.0f), w),
                        lerp(rightArm.zRot, rad(20.0f), w));
                rotate(leftArm,
                        lerp(leftArm.xRot, rad(30.0f), w),
                        lerp(leftArm.yRot, rad(10.0f), w),
                        lerp(leftArm.zRot, rad(-16.0f), w));
            }

            rotate(rightLeg,
                    lerp(rightLeg.xRot, rad(side > 0.0f ? 18.0f : -10.0f), w),
                    lerp(rightLeg.yRot, rad(-4.0f * side), w),
                    lerp(rightLeg.zRot, rad(5.0f * side), w));
            rotate(leftLeg,
                    lerp(leftLeg.xRot, rad(side > 0.0f ? -10.0f : 18.0f), w),
                    lerp(leftLeg.yRot, rad(4.0f * side), w),
                    lerp(leftLeg.zRot, rad(-5.0f * side), w));
        }

        private void syncWearLayers() {
            hat.copyFrom(head);
            jacket.copyFrom(body);
            rightSleeve.copyFrom(rightArm);
            leftSleeve.copyFrom(leftArm);
            rightPants.copyFrom(rightLeg);
            leftPants.copyFrom(leftLeg);
        }
    }

    private static void rotate(ModelPart part, float x, float y, float z) {
        part.xRot = x;
        part.yRot = y;
        part.zRot = z;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float smooth(float value) {
        float t = clamp01(value);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * clamp01(t);
    }

    private static float mixDeg(float from, float to, float t) {
        return lerp(from, to, t);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
