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
        PURPLE
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
            float progress = clamp01(1.0f - ticks / (float) length);

            return switch (active) {
                case "BLUE" -> new SkillFrame(Skill.BLUE, progress);
                case "MAX_BLUE" -> new SkillFrame(Skill.MAX_BLUE, progress);
                case "PURPLE_CAST", "HOLLOW_PURPLE" -> new SkillFrame(Skill.PURPLE, progress);
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

        boolean slim = "slim".equals(event.getEntity().getModelName());
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
