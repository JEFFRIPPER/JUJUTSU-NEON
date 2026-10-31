package com.kira.jujutsuneon.rbx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;

/** Элитры, которые держатся за корпус R15 (ванильный слой ставит их от ванильного корпуса). */
final class R15ElytraModel extends ElytraModel<LivingEntity> {

    R15ElytraModel(ModelPart root) {
        super(root);
    }

    @Override
    public void renderToBuffer(PoseStack ps, VertexConsumer vc, int light, int overlay, float r, float g, float b, float a) {
        R15Render.Context c = R15Render.current();
        if (c == null || c.layerPose == null) {
            super.renderToBuffer(ps, vc, light, overlay, r, g, b, a);
            return;
        }
        ps.pushPose();
        R15Render.applyBodyDelta(ps, c);
        super.renderToBuffer(ps, vc, light, overlay, r, g, b, a);
        ps.popPose();
    }
}
