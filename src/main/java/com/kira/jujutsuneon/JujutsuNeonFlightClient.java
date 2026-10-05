package com.kira.jujutsuneon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Client half of Jujutsu Neon flight.
 *
 * Flight deliberately starts AFTER the existing charged jump reaches its apex.
 * Nothing here changes the jump impulse or its 1/2/3-second charge tiers.
 */
@Mod.EventBusSubscriber(
        modid = JujutsuNeonMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class JujutsuNeonFlightClient {

    // Полёт начинается в верхней точке любого прыжка, если до земли или воды 4+ блока.
    private static final double MIN_FLIGHT_HEIGHT = 4.0;
    private static final double AUTO_LAND_HEIGHT = 3.0;
    private static final double LANDING_SPEED = 0.18;
    private static final float NORMAL_FLY_SPEED = 0.05F;
    private static final float BOOST_FLY_SPEED = 0.10F;
    private static final double NORMAL_VECTOR_SPEED = 0.42;
    // Ускоренный полёт (Ctrl): примерно в 3,5 раза быстрее прежнего (было 0.82).
    private static final double BOOST_VECTOR_SPEED = 2.9;

    private static boolean armedFromChargedJump = false;
    private static int armedTicks = 0;
    private static double previousVerticalVelocity = 0.0;

    private static boolean customFlight = false;
    private static boolean landing = false;
    private static boolean boost = false;
    private static Vec3 previousFlightVelocity = Vec3.ZERO;

    private static boolean originalMayfly = false;
    private static boolean originalFlying = false;
    private static float originalFlySpeed = NORMAL_FLY_SPEED;

    private static float flightBlend = 0.0F;
    private static float boostBlend = 0.0F;

    private static final Class<?> CLIENT_EVENTS_CLASS;
    private static final Field ACTIVE_ANIM;
    private static final Field ACTIVE_ANIM_TICKS;
    private static final Field CHARGING_ANIM;
    private static final Field RENDERER_MODEL_FIELD;

    private static FlightPlayerModel defaultModel;
    private static FlightPlayerModel slimModel;
    private static final Map<Object, PlayerModel<AbstractClientPlayer>> ORIGINAL_MODELS = new IdentityHashMap<>();

    static {
        try {
            CLIENT_EVENTS_CLASS = Class.forName("com.kira.jujutsuneon.JujutsuNeonMod$ClientForgeEvents");
            ACTIVE_ANIM = field(CLIENT_EVENTS_CLASS, "activeAnim");
            ACTIVE_ANIM_TICKS = field(CLIENT_EVENTS_CLASS, "activeAnimTicks");
            CHARGING_ANIM = field(CLIENT_EVENTS_CLASS, "chargingAnim");
            RENDERER_MODEL_FIELD = findRendererModelField();
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private JujutsuNeonFlightClient() {
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

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

    public static boolean isCustomFlightActive() {
        return customFlight;
    }

    public static boolean isSprintFlightActive() {
        return customFlight && boost && !landing;
    }

    private static boolean hasBlindfold(Minecraft mc) {
        return mc.player != null &&
                mc.player.getItemBySlot(EquipmentSlot.HEAD).is(JujutsuNeonMod.GOJO_BLINDFOLD.get());
    }

    private static boolean techniqueAnimationActive() {
        try {
            String charging = (String) CHARGING_ANIM.get(null);
            String active = (String) ACTIVE_ANIM.get(null);
            int ticks = ACTIVE_ANIM_TICKS.getInt(null);
            return !"NONE".equals(charging) || (!"NONE".equals(active) && ticks > 0);
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    /** Максимальный Фиолетовый: после кат-сцены игрок остаётся в воздухе в режиме полёта. */
    public static void beginFlightForTechnique() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !hasBlindfold(mc)) return;
        if (customFlight) return;
        beginFlight(mc);
    }

    /**
     * Обычные и двойные прыжки полёт больше не включают (раньше он включался сам).
     * Полёт начинается только после взлёта — заряженного прыжка (см. onTakeoff).
     */
    public static void onJumpFired() {
    }

    /** Взлёт (присед и прыжок на ~13 блоков): в верхней точке начинается полёт. */
    public static void onTakeoff() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || customFlight) return;
        if (!hasBlindfold(mc) || mc.player.getAbilities().flying) return;
        armFromExistingJump();
    }

    private static double groundDistance(Minecraft mc, double maxDistance) {
        return groundDistance(mc, maxDistance, ClipContext.Fluid.NONE);
    }

    /** Высота над землёй; с Fluid.ANY поверхность воды тоже считается землёй. */
    private static double groundDistance(Minecraft mc, double maxDistance, ClipContext.Fluid fluid) {
        if (mc.player == null || mc.level == null) return maxDistance + 1.0;
        if (mc.player.onGround()) return 0.0;

        Vec3 start = mc.player.position().add(0.0, 0.08, 0.0);
        Vec3 end = start.add(0.0, -Math.max(0.25, maxDistance), 0.0);
        BlockHitResult hit = mc.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                fluid,
                mc.player
        ));

        if (hit.getType() == HitResult.Type.MISS) return maxDistance + 1.0;
        return Math.max(0.0, start.y - hit.getLocation().y);
    }

    private static void armFromExistingJump() {
        armedFromChargedJump = true;
        armedTicks = 0;
    }

    private static void beginFlight(Minecraft mc) {
        if (mc.player == null || customFlight) return;

        customFlight = true;
        landing = false;
        boost = false;
        armedFromChargedJump = false;
        armedTicks = 0;

        originalMayfly = mc.player.getAbilities().mayfly;
        originalFlying = mc.player.getAbilities().flying;
        originalFlySpeed = mc.player.getAbilities().getFlyingSpeed();

        mc.player.getAbilities().mayfly = true;
        mc.player.getAbilities().flying = true;
        mc.player.getAbilities().setFlyingSpeed(NORMAL_FLY_SPEED);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.fallDistance = 0.0F;
        mc.player.setSprinting(false);
        mc.player.onUpdateAbilities();

        previousFlightVelocity = Vec3.ZERO;
        JujutsuNeonFlightPatch.send(JujutsuNeonFlightPatch.Action.START);
        MovementFxClient.localFlight(MovementFx.FLIGHT_ON);
    }

    private static void endFlight(Minecraft mc, boolean notifyServer) {
        if (mc.player == null) {
            customFlight = false;
            landing = false;
            boost = false;
            return;
        }

        if (notifyServer && customFlight) {
            JujutsuNeonFlightPatch.send(JujutsuNeonFlightPatch.Action.STOP);
        }

        customFlight = false;
        landing = false;
        boost = false;
        armedFromChargedJump = false;
        armedTicks = 0;
        previousFlightVelocity = Vec3.ZERO;
        MovementFxClient.localFlight(MovementFx.FLIGHT_OFF);

        mc.player.getAbilities().mayfly = originalMayfly;
        mc.player.getAbilities().flying = originalMayfly && originalFlying;
        mc.player.getAbilities().setFlyingSpeed(originalFlySpeed);
        mc.player.fallDistance = 0.0F;
        mc.player.onUpdateAbilities();
    }

    private static void setBoost(Minecraft mc, boolean value) {
        if (mc.player == null || boost == value) return;
        boost = value;
        mc.player.getAbilities().setFlyingSpeed(boost ? BOOST_FLY_SPEED : NORMAL_FLY_SPEED);
        mc.player.setSprinting(false);
        mc.player.onUpdateAbilities();
        JujutsuNeonFlightPatch.send(boost
                ? JujutsuNeonFlightPatch.Action.BOOST_ON
                : JujutsuNeonFlightPatch.Action.BOOST_OFF);
        MovementFxClient.localFlight(boost ? MovementFx.FLIGHT_BOOST : MovementFx.FLIGHT_ON);
    }

    private static boolean movementInputHeld(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() ||
                mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    private static Vec3 flightInputDirection(Minecraft mc) {
        if (mc.player == null) return Vec3.ZERO;

        double forwardInput = (mc.options.keyUp.isDown() ? 1.0 : 0.0) -
                (mc.options.keyDown.isDown() ? 1.0 : 0.0);
        double strafeInput = (mc.options.keyRight.isDown() ? 1.0 : 0.0) -
                (mc.options.keyLeft.isDown() ? 1.0 : 0.0);
        if (Math.abs(forwardInput) < 1.0E-5 && Math.abs(strafeInput) < 1.0E-5) return Vec3.ZERO;

        Vec3 look = mc.player.getLookAngle().normalize();
        Vec3 horizontalForward = new Vec3(look.x, 0.0, look.z);
        if (horizontalForward.lengthSqr() < 1.0E-6) {
            double yaw = Math.toRadians(mc.player.getYRot());
            horizontalForward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        horizontalForward = horizontalForward.normalize();
        Vec3 right = new Vec3(-horizontalForward.z, 0.0, horizontalForward.x);

        Vec3 result = look.scale(forwardInput).add(right.scale(strafeInput));
        return result.lengthSqr() > 1.0E-8 ? result.normalize() : Vec3.ZERO;
    }

    private static void tickArmedJump(Minecraft mc, double currentVerticalVelocity) {
        if (!armedFromChargedJump || mc.player == null) return;

        armedTicks++;

        if (!hasBlindfold(mc) || mc.player.isSpectator()) {
            armedFromChargedJump = false;
            return;
        }

        // If the jump has already returned to ground, it never reached a legal flight height.
        if (armedTicks > 3 && mc.player.onGround()) {
            armedFromChargedJump = false;
            return;
        }

        boolean crossedApex = armedTicks > 2 &&
                previousVerticalVelocity > 0.035 &&
                currentVerticalVelocity <= 0.035;

        if (!crossedApex) return;

        if (groundDistance(mc, MIN_FLIGHT_HEIGHT + 0.35, ClipContext.Fluid.ANY) + 1.0E-4 >= MIN_FLIGHT_HEIGHT) {
            beginFlight(mc);
        } else {
            armedFromChargedJump = false;
        }
    }

    private static void tickFlight(Minecraft mc) {
        if (mc.player == null || mc.level == null || !customFlight) return;

        if (!mc.player.isAlive() || mc.player.isSpectator() || !hasBlindfold(mc)) {
            endFlight(mc, true);
            return;
        }

        if (mc.player.isInWaterOrBubble()) {
            endFlight(mc, true);
            return;
        }

        if (!mc.player.getAbilities().mayfly || !mc.player.getAbilities().flying) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }

        boolean crash = boost && !landing &&
                (mc.player.verticalCollision || mc.player.onGround()) &&
                previousFlightVelocity.y < -0.10 &&
                previousFlightVelocity.lengthSqr() > 0.035;

        if (crash) {
            JujutsuNeonFlightPatch.send(JujutsuNeonFlightPatch.Action.IMPACT);
            endFlight(mc, false);
            return;
        }

        Vec3 inputDirection = flightInputDirection(mc);
        boolean moving = inputDirection.lengthSqr() > 1.0E-8;

        if (!landing) {
            boolean wantsBoost = JujutsuNeonMod.ClientModEvents.SUPER_SPEED_KEY.isDown() && moving;
            setBoost(mc, wantsBoost);

            if (!boost && groundDistance(mc, AUTO_LAND_HEIGHT + 0.25) <= AUTO_LAND_HEIGHT &&
                    (!moving || inputDirection.y < -0.08)) {
                landing = true;
                setBoost(mc, false);
                MovementFxClient.localFlight(MovementFx.FLIGHT_LANDING);
            }
        }

        if (landing) {
            double distance = groundDistance(mc, AUTO_LAND_HEIGHT + 0.25);
            if (mc.player.onGround() || distance <= 0.20) {
                mc.player.setDeltaMovement(Vec3.ZERO);
                endFlight(mc, true);
                return;
            }

            Vec3 velocity = mc.player.getDeltaMovement();
            mc.player.getAbilities().setFlyingSpeed(0.02F);
            mc.player.setDeltaMovement(
                    velocity.x * 0.38,
                    -Math.min(LANDING_SPEED, Math.max(0.08, distance * 0.12)),
                    velocity.z * 0.38
            );
            mc.player.fallDistance = 0.0F;
        } else {
            double speed = boost ? BOOST_VECTOR_SPEED : NORMAL_VECTOR_SPEED;
            Vec3 target = moving ? inputDirection.scale(speed) : Vec3.ZERO;
            Vec3 current = mc.player.getDeltaMovement();
            double blend = boost ? 0.46 : 0.34;
            Vec3 next = current.lerp(target, blend);
            if (!moving && next.lengthSqr() < 0.0004) next = Vec3.ZERO;

            mc.player.setDeltaMovement(next);
            mc.player.getAbilities().setFlyingSpeed(boost ? BOOST_FLY_SPEED : NORMAL_FLY_SPEED);
            mc.player.setSprinting(false);
            mc.player.fallDistance = 0.0F;
        }

        previousFlightVelocity = mc.player.getDeltaMovement();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            customFlight = false;
            armedFromChargedJump = false;
            flightBlend = 0.0F;
            boostBlend = 0.0F;
            return;
        }

        double currentVerticalVelocity = mc.player.getDeltaMovement().y;

        // Прыжок «взводит» полёт через onJumpFired() (любой прыжок, не только заряженный).

        if (MaximumPurpleClient.isLocalActive() || DomainExpansionClient.locksLocalPlayer() || LapseBlueClient.locksLocalPlayer() || RedTechniqueClient.locksLocalPlayer()) {
            // Кат-сцена Максимального Фиолетового: полёт снимается и не взводится.
            if (customFlight) endFlight(mc, true);
            armedFromChargedJump = false;
        } else {
            tickArmedJump(mc, currentVerticalVelocity);
            tickFlight(mc);
        }

        float targetFlightBlend = customFlight ? 1.0F : 0.0F;
        float targetBoostBlend = customFlight && boost && !landing ? 1.0F : 0.0F;
        flightBlend += (targetFlightBlend - flightBlend) * (customFlight ? 0.24F : 0.34F);
        boostBlend += (targetBoostBlend - boostBlend) * 0.26F;

        if (Math.abs(flightBlend) < 0.001F) flightBlend = 0.0F;
        if (Math.abs(boostBlend) < 0.001F) boostBlend = 0.0F;

        previousVerticalVelocity = currentVerticalVelocity;
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

    private static FlightPlayerModel animatedModel(boolean slim) {
        Minecraft mc = Minecraft.getInstance();

        if (slim) {
            if (slimModel == null) {
                slimModel = new FlightPlayerModel(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimModel;
        }

        if (defaultModel == null) {
            defaultModel = new FlightPlayerModel(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return defaultModel;
    }

    private static void copyRendererState(PlayerModel<AbstractClientPlayer> source, FlightPlayerModel target) {
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

        target.flightWeight = flightBlend;
        target.boostWeight = boostBlend;
        target.landingPose = landing;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player) return;

        Object renderer = event.getRenderer();
        PlayerModel<AbstractClientPlayer> stuck = ORIGINAL_MODELS.remove(renderer);
        if (stuck != null) setRendererModel(renderer, stuck);

        // Позу полёта теперь ставит слой Player Animator (MovementFxClient): весь корпус
        // наклоняется целиком, и модель не разваливается. Старую подмену модели не используем.
        if (true) return;
        if (flightBlend <= 0.01F || techniqueAnimationActive() || !hasBlindfold(mc)) return;

        PlayerModel<AbstractClientPlayer> original = rendererModel(renderer);
        if (original == null || original instanceof FlightPlayerModel) return;

        boolean slim = "slim".equals(((AbstractClientPlayer) event.getEntity()).getModelName());
        FlightPlayerModel animated = animatedModel(slim);
        copyRendererState(original, animated);

        ORIGINAL_MODELS.put(renderer, original);
        setRendererModel(renderer, animated);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        Object renderer = event.getRenderer();
        PlayerModel<AbstractClientPlayer> original = ORIGINAL_MODELS.remove(renderer);
        if (original != null) setRendererModel(renderer, original);
    }

    private static final class FlightPlayerModel extends PlayerModel<AbstractClientPlayer> {
        private float flightWeight;
        private float boostWeight;
        private boolean landingPose;

        FlightPlayerModel(ModelPart root, boolean slim) {
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
            super.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            float w = Mth.clamp(flightWeight, 0.0F, 1.0F);
            if (w <= 0.001F) return;

            Vec3 velocity = player.getDeltaMovement();
            double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            float movement = Mth.clamp((float) (horizontal / 0.16), 0.0F, 1.0F);
            float boostW = Mth.clamp(boostWeight, 0.0F, 1.0F) * movement;
            float normalW = movement * (1.0F - boostW);
            float hoverW = 1.0F - movement;

            if (landingPose) {
                hoverW = 0.35F;
                normalW = 0.65F;
                boostW = 0.0F;
            }

            float vertical = Mth.clamp((float) velocity.y, -0.35F, 0.35F);
            // Calm-flight reference: almost upright, composed and weightless.
            // The body only leans a little until Ctrl/sprint flight takes over.
            float bob = (float) Math.sin(ageInTicks * 0.16F) * 0.85F * hoverW;

            float bodyPitchDeg =
                    hoverW * (2.5F + bob) +
                    normalW * 8.5F +
                    boostW * 48.0F;

            if (landingPose) bodyPitchDeg = 7.0F;
            bodyPitchDeg -= vertical * (8.0F + 12.0F * boostW);

            float bank = 0.0F;
            Minecraft mc = Minecraft.getInstance();
            if (!landingPose) {
                if (mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown()) {
                    bank = 3.5F + 10.0F * boostW;
                } else if (mc.options.keyRight.isDown() && !mc.options.keyLeft.isDown()) {
                    bank = -3.5F - 10.0F * boostW;
                }
            }

            body.xRot = lerp(body.xRot, rad(bodyPitchDeg), w);
            body.zRot = lerp(body.zRot, rad(bank), w);

            // Counter some torso pitch with the head so the player still appears to look forward.
            head.xRot = lerp(head.xRot, head.xRot - rad(bodyPitchDeg * 0.52F), w);
            head.zRot = lerp(head.zRot, rad(-bank * 0.32F), w);

            // Roblox calm-flight reference: arms are lifted and relaxed around the torso,
            // while the legs hang in a slightly asymmetric tucked pose.  Sprint flight
            // deliberately retains the much more aggressive aerodynamic silhouette.
            float rightArmX = hoverW * -31.0F + normalW * -23.0F + boostW * 72.0F;
            float leftArmX = hoverW * -25.0F + normalW * -19.0F + boostW * 72.0F;
            float rightArmY = hoverW * -11.0F + normalW * -8.0F;
            float leftArmY = hoverW * 9.0F + normalW * 7.0F;
            float armSpread = hoverW * 20.0F + normalW * 16.0F + boostW * 13.0F;

            rightArm.xRot = lerp(rightArm.xRot, rad(rightArmX - vertical * (4.0F + 8.0F * boostW)), w);
            leftArm.xRot = lerp(leftArm.xRot, rad(leftArmX - vertical * (4.0F + 8.0F * boostW)), w);
            rightArm.yRot = lerp(rightArm.yRot, rad(rightArmY), w * (1.0F - boostW));
            leftArm.yRot = lerp(leftArm.yRot, rad(leftArmY), w * (1.0F - boostW));
            rightArm.zRot = lerp(rightArm.zRot, rad(armSpread + bank * 0.24F), w);
            leftArm.zRot = lerp(leftArm.zRot, rad(-armSpread + bank * 0.24F), w);

            float rightLegPitch = hoverW * 18.0F + normalW * 14.0F + boostW * 27.0F;
            float leftLegPitch = hoverW * -9.0F + normalW * -4.0F + boostW * 21.0F;
            rightLeg.xRot = lerp(rightLeg.xRot, rad(rightLegPitch), w);
            leftLeg.xRot = lerp(leftLeg.xRot, rad(leftLegPitch), w);
            rightLeg.zRot = lerp(rightLeg.zRot, rad(5.0F + bank * 0.14F), w);
            leftLeg.zRot = lerp(leftLeg.zRot, rad(-4.0F + bank * 0.14F), w);

            if (landingPose) {
                rightArm.xRot = lerp(rightArm.xRot, rad(-24.0F), w * 0.72F);
                leftArm.xRot = lerp(leftArm.xRot, rad(-21.0F), w * 0.72F);
                rightArm.zRot = lerp(rightArm.zRot, rad(14.0F), w * 0.72F);
                leftArm.zRot = lerp(leftArm.zRot, rad(-14.0F), w * 0.72F);
                rightLeg.xRot = lerp(rightLeg.xRot, rad(11.0F), w);
                leftLeg.xRot = lerp(leftLeg.xRot, rad(-4.0F), w);
            }

            syncWearLayers();
        }

        private void syncWearLayers() {
            copyPose(head, hat);
            copyPose(body, jacket);
            copyPose(rightArm, rightSleeve);
            copyPose(leftArm, leftSleeve);
            copyPose(rightLeg, rightPants);
            copyPose(leftLeg, leftPants);
        }

        private static void copyPose(ModelPart source, ModelPart target) {
            target.x = source.x;
            target.y = source.y;
            target.z = source.z;
            target.xRot = source.xRot;
            target.yRot = source.yRot;
            target.zRot = source.zRot;
            target.visible = source.visible;
        }

        private static float lerp(float from, float to, float amount) {
            return from + (to - from) * Mth.clamp(amount, 0.0F, 1.0F);
        }

        private static float rad(float degrees) {
            return degrees * ((float) Math.PI / 180.0F);
        }
    }
}
