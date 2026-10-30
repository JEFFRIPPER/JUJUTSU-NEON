package com.kira.jujutsuneon.rbx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;

/**
 * Модель игрока на время отрисовки (см. {@link R15Render}): рисует сетку R15 в позе из {@link PlayerAnimations},
 * а ванильные части (по ним работают слои: броня других модов, головной предмет, стрелы, попугаи…) ставит
 * по костям R15. Вне отрисовки R15 ведёт себя как обычная ванильная модель.
 */
public final class R15PlayerModel extends PlayerModel<AbstractClientPlayer> {

    final boolean slim;
    private final boolean[] visible = new boolean[R15Rig.Part.values().length];
    private final boolean[] overlays = new boolean[R15Rig.Part.values().length];
    private final Matrix4f tmp = new Matrix4f(), vanillaBody = new Matrix4f(), ourBody = new Matrix4f();

    R15PlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
        this.slim = slim;
    }

    /** Состояние, которое PlayerRenderer уже выставил своей модели до RenderPlayerEvent.Pre. */
    void copyState(PlayerModel<AbstractClientPlayer> src) {
        leftArmPose = src.leftArmPose;
        rightArmPose = src.rightArmPose;
        crouching = src.crouching;
        swimAmount = src.swimAmount;
        head.visible = src.head.visible;
        hat.visible = src.hat.visible;
        body.visible = src.body.visible;
        rightArm.visible = src.rightArm.visible;
        leftArm.visible = src.leftArm.visible;
        rightLeg.visible = src.rightLeg.visible;
        leftLeg.visible = src.leftLeg.visible;
        jacket.visible = src.jacket.visible;
        rightSleeve.visible = src.rightSleeve.visible;
        leftSleeve.visible = src.leftSleeve.visible;
        rightPants.visible = src.rightPants.visible;
        leftPants.visible = src.leftPants.visible;
    }

    @Override
    public void setupAnim(AbstractClientPlayer p, float limbSwing, float limbAmount, float age, float netHeadYaw, float headPitch) {
        super.setupAnim(p, limbSwing, limbAmount, age, netHeadYaw, headPitch);
        R15Render.Context c = R15Render.current(this, p);
        if (c == null) return;
        R15Render.partMatrix(body, vanillaBody);

        PlayerAnimations.State s = c.state;
        s.solve(netHeadYaw, headPitch);
        c.skin.apply(s.finalPose);
        int n = Bones.count();
        if (s.boneMatrices.length < n) s.boneMatrices = java.util.Arrays.copyOf(s.boneMatrices, n);
        if (s.joints.length < n) s.joints = java.util.Arrays.copyOf(s.joints, n);
        c.skin.matrices(s.finalPose, s.boneMatrices, s.joints);
        s.drawnFrame = PlayerAnimations.frame();

        float armY = slim ? 2.5f : 2.0f;
        sync(c, head, Bones.HEAD, 0, 0, 0);
        sync(c, body, Bones.UPPER_TORSO, 0, 0, 0);
        sync(c, rightArm, Bones.R_UPPER_ARM, -5, armY, 0);
        sync(c, leftArm, Bones.L_UPPER_ARM, 5, armY, 0);
        sync(c, rightLeg, Bones.R_UPPER_LEG, -1.9f, 12, 0);
        sync(c, leftLeg, Bones.L_UPPER_LEG, 1.9f, 12, 0);
        hat.copyFrom(head);
        jacket.copyFrom(body);
        rightSleeve.copyFrom(rightArm);
        leftSleeve.copyFrom(leftArm);
        rightPants.copyFrom(rightLeg);
        leftPants.copyFrom(leftLeg);

        // корпус: ванильный → R15 (для плаща и элитр)
        if (R15Render.partFrame(c, Bones.UPPER_TORSO, 0, 0, 0, ourBody) != null) {
            c.bodyDelta.set(ourBody).mul(new Matrix4f(vanillaBody).invert());
        } else {
            c.bodyDelta.identity();
        }
    }

    private void sync(R15Render.Context c, ModelPart part, String bone, float vx, float vy, float vz) {
        if (R15Render.partFrame(c, bone, vx, vy, vz, tmp) != null) R15Render.decompose(tmp, part);
    }

    @Override
    public void renderToBuffer(PoseStack ps, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a) {
        R15Render.Context c = R15Render.current(this, null);
        if (c == null) {
            super.renderToBuffer(ps, vc, light, overlay, r, g, b, a);
            return;
        }
        visible[R15Rig.Part.HEAD.ordinal()] = head.visible;
        visible[R15Rig.Part.BODY.ordinal()] = body.visible;
        visible[R15Rig.Part.RIGHT_ARM.ordinal()] = rightArm.visible;
        visible[R15Rig.Part.LEFT_ARM.ordinal()] = leftArm.visible;
        visible[R15Rig.Part.RIGHT_LEG.ordinal()] = rightLeg.visible;
        visible[R15Rig.Part.LEFT_LEG.ordinal()] = leftLeg.visible;
        overlays[R15Rig.Part.HEAD.ordinal()] = hat.visible;
        overlays[R15Rig.Part.BODY.ordinal()] = jacket.visible;
        overlays[R15Rig.Part.RIGHT_ARM.ordinal()] = rightSleeve.visible;
        overlays[R15Rig.Part.LEFT_ARM.ordinal()] = leftSleeve.visible;
        overlays[R15Rig.Part.RIGHT_LEG.ordinal()] = rightPants.visible;
        overlays[R15Rig.Part.LEFT_LEG.ordinal()] = leftPants.visible;
        if (c.firstPerson) {
            for (R15Rig.Part part : R15Rig.Part.values()) {
                visible[part.ordinal()] &= c.partShown(part);
                overlays[part.ordinal()] &= c.partShown(part);
            }
        }
        ps.pushPose();
        R15Render.toGeo(ps, c);
        c.skin.render(ps, c.state.finalPose, vc, light, overlay, r, g, b, a, visible, overlays);
        ps.popPose();
    }

    /** Предмет в руке: ванильная рамка руки, жёстко прикреплённая к кисти R15 (локоть согнут — предмет в кисти). */
    @Override
    public void translateToHand(HumanoidArm arm, PoseStack ps) {
        R15Render.Context c = R15Render.current(this, null);
        if (c == null) {
            super.translateToHand(arm, ps);
            return;
        }
        boolean right = arm == HumanoidArm.RIGHT;
        if (!c.partShown(right ? R15Rig.Part.RIGHT_ARM : R15Rig.Part.LEFT_ARM)) {
            // от первого лица эта рука ванильная: её предмет рисует ванильный вид руки, здесь — схлопнуть
            ps.scale(0, 0, 0);
            return;
        }
        float armY = slim ? 2.5f : 2.0f;
        // у тонких рук ванилла сдвигает предмет на полпикселя к телу
        float x = (right ? -5 : 5) + (slim ? (right ? 0.5f : -0.5f) : 0);
        if (R15Render.partFrame(c, right ? Bones.R_HAND : Bones.L_HAND, x, armY, 0, tmp) == null) {
            super.translateToHand(arm, ps);
            return;
        }
        R15Render.mulPose(ps, tmp);
    }

    @Override
    public void renderCloak(PoseStack ps, VertexConsumer vc, int light, int overlay) {
        R15Render.Context c = R15Render.current(this, null);
        if (c == null || c.layerPose == null) {
            super.renderCloak(ps, vc, light, overlay);
            return;
        }
        ps.pushPose();
        R15Render.applyBodyDelta(ps, c);
        super.renderCloak(ps, vc, light, overlay);
        ps.popPose();
    }
}
