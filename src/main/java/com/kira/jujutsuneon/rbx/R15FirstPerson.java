package com.kira.jujutsuneon.rbx;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Вид от первого лица во время анимаций с {@code @fp=arms|right|left} (и {@link PlayerAnimations.Play#firstPerson}):
 * вместо ванильных рук в кадре — руки самой модели R15 в той позе, что видят другие игроки (как руки персонажа
 * от первого лица в Roblox). Рука рисуется в мире от настоящего тела игрока, вместе с предметом и бронёй на ней;
 * всё остальное тело, голова и прочие слои скрыты. Ванильная рука той же стороны в это время не рисуется.
 */
@Mod.EventBusSubscriber(modid = R15Rig.NS, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class R15FirstPerson {

    /** Идёт отрисовка своих рук от первого лица (её видит {@link R15Render#onPre}). */
    static boolean pass;
    static boolean showRight, showLeft;

    private R15FirstPerson() {
    }

    /** Какие руки модели показать сейчас от первого лица (null — никакие, ванильные руки как обычно). */
    static Clip.FirstPerson mode(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.getCameraEntity() != p || !mc.options.getCameraType().isFirstPerson()) return null;
        if (mc.options.hideGui || p.isSleeping() || mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return null;
        }
        if (!R15Render.activeFor(p)) return null;
        Animator.Mix mix = PlayerAnimations.state(p).mix();
        return mix.firstPerson == Clip.FirstPerson.NONE ? null : mix.firstPerson;
    }

    static boolean shows(Clip.FirstPerson m, HumanoidArm arm) {
        return m == Clip.FirstPerson.ARMS
                || (m == Clip.FirstPerson.RIGHT && arm == HumanoidArm.RIGHT)
                || (m == Clip.FirstPerson.LEFT && arm == HumanoidArm.LEFT);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Clip.FirstPerson m = mode(mc);
        if (m == null) return;
        HumanoidArm main = mc.player.getMainArm();
        HumanoidArm arm = event.getHand() == InteractionHand.MAIN_HAND ? main : main.getOpposite();
        if (shows(m, arm)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        Clip.FirstPerson m = mode(mc);
        if (m == null) return;
        LocalPlayer p = mc.player;
        EntityRenderer<? super LocalPlayer> r = mc.getEntityRenderDispatcher().getRenderer(p);
        if (!(r instanceof PlayerRenderer renderer)) return;

        float pt = event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 off = renderer.getRenderOffset(p, pt);
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(Mth.lerp(pt, p.xo, p.getX()) - cam.x + off.x,
                Mth.lerp(pt, p.yo, p.getY()) - cam.y + off.y,
                Mth.lerp(pt, p.zo, p.getZ()) - cam.z + off.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(p, pt);
        pass = true;
        showRight = shows(m, HumanoidArm.RIGHT);
        showLeft = shows(m, HumanoidArm.LEFT);
        try {
            renderer.render(p, Mth.lerp(pt, p.yRotO, p.getYRot()), pt, ps, buffers, light);
        } finally {
            pass = false;
            R15Render.endPass(renderer);
            ps.popPose();
        }
        buffers.endBatch();
    }
}
