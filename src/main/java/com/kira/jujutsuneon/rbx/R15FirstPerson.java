package com.kira.jujutsuneon.rbx;

import com.kira.jujutsuneon.GojoSuit;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderArmEvent;
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

    /** Что сейчас показывается от первого лица (для отладки: режим тестера, /jnanim). */
    public static String debugState(Minecraft mc) {
        Clip.FirstPerson m = mode(mc);
        return "fp=" + (m == null ? "vanilla" : m) + " hand=" + lastHand;
    }

    /** Чем кончилась последняя отрисовка ванильной руки (для {@link #debugState}). */
    private static String lastHand = "never";

    static boolean shows(Clip.FirstPerson m, HumanoidArm arm) {
        return m == Clip.FirstPerson.ARMS
                || (m == Clip.FirstPerson.RIGHT && arm == HumanoidArm.RIGHT)
                || (m == Clip.FirstPerson.LEFT && arm == HumanoidArm.LEFT);
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.isCanceled()) {
            lastHand = event.getHand() + " cancelled-by-other";
            return;
        }
        Clip.FirstPerson m = mode(mc);
        if (m == null) {
            lastHand = event.getHand() + " vanilla";
            return;
        }
        HumanoidArm main = mc.player.getMainArm();
        HumanoidArm arm = event.getHand() == InteractionHand.MAIN_HAND ? main : main.getOpposite();
        if (shows(m, arm)) event.setCanceled(true);
        lastHand = event.getHand() + " r15=" + m;
    }

    private static final ResourceLocation SUIT_SKIN = new ResourceLocation(GojoSuit.SKIN);

    /**
     * Обычная ванильная рука от первого лица (пустая ладонь, вне анимаций с {@code @fp}): при полном костюме Годжо —
     * рукав и кисть со скина костюма, а не со скина игрока. Широкая модель, как у самого костюма.
     */
    @SubscribeEvent
    public static void onRenderArm(RenderArmEvent event) {
        AbstractClientPlayer p = event.getPlayer();
        if (!GojoSuit.isFull(p)) return;
        Minecraft mc = Minecraft.getInstance();
        EntityRenderer<?> r = mc.getEntityRenderDispatcher().getSkinMap().get("default");
        if (!(r instanceof PlayerRenderer renderer)) return;
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        event.setCanceled(true);
        boolean right = event.getArm() == HumanoidArm.RIGHT;
        ModelPart arm = right ? model.rightArm : model.leftArm;
        ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
        model.attackTime = 0.0F;
        model.crouching = false;
        model.swimAmount = 0.0F;
        model.setupAnim(p, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        arm.visible = true;
        sleeve.visible = true;
        arm.xRot = 0.0F;
        sleeve.xRot = 0.0F;
        PoseStack ps = event.getPoseStack();
        MultiBufferSource buf = event.getMultiBufferSource();
        int light = event.getPackedLight();
        arm.render(ps, buf.getBuffer(RenderType.entitySolid(SUIT_SKIN)), light, OverlayTexture.NO_OVERLAY);
        sleeve.render(ps, buf.getBuffer(RenderType.entityTranslucent(SUIT_SKIN)), light, OverlayTexture.NO_OVERLAY);
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
