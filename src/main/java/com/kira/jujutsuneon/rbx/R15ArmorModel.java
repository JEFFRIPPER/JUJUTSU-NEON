package com.kira.jujutsuneon.rbx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Броня игрока по сегментам R15 (рука и нога гнутся вместе с телом). Подставляется вместо ванильной модели брони
 * в слое брони игрока; текстуры, окраска кожаной брони, отделка и зачарование — ванильные (слой рисует как обычно,
 * меняется только сетка). Если игрок сейчас не R15 (Фиолетовый, ванильная модель), рисуется ванильная броня.
 */
final class R15ArmorModel extends HumanoidArmorModel<AbstractClientPlayer> {

    private final boolean inner;
    private final boolean[] visible = new boolean[R15Rig.Part.values().length];
    private final boolean[] overlays = new boolean[R15Rig.Part.values().length];

    R15ArmorModel(ModelPart root, boolean inner) {
        super(root);
        this.inner = inner;
    }

    @Override
    public void renderToBuffer(PoseStack ps, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a) {
        R15Render.Context c = R15Render.current();
        R15Rig.Model m = c == null ? null : (inner ? c.armorInner : c.armorOuter);
        if (m == null) {
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
        if (c.firstPerson) {
            for (R15Rig.Part part : R15Rig.Part.values()) {
                visible[part.ordinal()] &= c.partShown(part);
                overlays[part.ordinal()] &= c.partShown(part);
            }
        }
        m.apply(c.state.finalPose);
        ps.pushPose();
        R15Render.toGeo(ps, c);
        m.render(ps, c.state.finalPose, vc, light, overlay, r, g, b, a, visible, overlays);
        ps.popPose();
    }
}
