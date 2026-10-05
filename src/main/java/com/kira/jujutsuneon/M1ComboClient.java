package com.kira.jujutsuneon;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.kira.jujutsuneon.M1Combo.*;

/**
 * M1-комбо на клиенте: ЛКМ в повязке с пустой рукой — серия ударов (анимация сразу, попадание решает
 * сервер), эффекты и звуки попаданий, блок управления оглушённого, «лежащие» сбитые мобы.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class M1ComboClient {

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(JujutsuNeonMod.MODID, "textures/gui/" + name + ".png");
    }

    private static final ResourceLocation TEX_GLOW = tex("cursed_glow");
    private static final ResourceLocation TEX_RIPPLE = tex("cursed_ripple");
    private static final ResourceLocation TEX_STAR = tex("move_star");
    private static final ResourceLocation TEX_STREAK = tex("move_streak");
    private static final ResourceLocation TEX_PUFF = tex("move_puff");

    // ---- своя серия (предсказание; попадание решает сервер)
    private static long clock;
    private static int step;
    private static long lastSwing = Long.MIN_VALUE / 4;
    private static long readyAt;
    private static boolean leftFirst;
    private static int pendingClick;
    private static boolean attackHeldPrev;

    // ---- оглушение своего игрока
    private static int stunTicks;

    /** Сбитые с ног (не игроки): entityId → [осталось, всего]. */
    private static final Map<Integer, int[]> KNOCKED = new HashMap<>();
    private static final Set<Integer> PUSHED = new HashSet<>();

    private static final class Impact {
        final Vec3 pos, dir;
        final int kind;
        final long seed;
        int age;

        Impact(Vec3 pos, Vec3 dir, int kind) {
            this.pos = pos;
            this.dir = dir;
            this.kind = kind;
            this.seed = RND.nextLong();
        }

        int life() {
            return kind == 1 ? 16 : 9;
        }
    }

    private static final List<Impact> IMPACTS = new ArrayList<>();
    private static final Random RND = new Random();

    private M1ComboClient() {
    }

    /** Свой игрок оглушён ударом. */
    public static boolean locksLocalPlayer() {
        return stunTicks > 0;
    }

    // ================================================================== ввод

    private static boolean active(Minecraft mc) {
        if (mc.player == null || mc.screen != null || mc.player.isSpectator()) return false;
        if (!JujutsuNeonMod.ClientForgeEvents.hudHasBlindfold()) return false;
        if (!mc.player.getMainHandItem().isEmpty()) return false;
        return !JujutsuNeonMod.ClientForgeEvents.attackClickBusy();
    }

    private static boolean blocked(Minecraft mc) {
        return locksLocalPlayer() || MaximumPurpleClient.isLocalActive() || DomainExpansionClient.locksLocalPlayer()
                || LapseBlueClient.locksLocalPlayer() || RedTechniqueClient.locksLocalPlayer();
    }

    /** ЛКМ с пустой рукой в повязке — не ванильный удар/ломание, а M1. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onAttackKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        // Удержание по блоку шлёт это событие каждый тик — это не новый клик.
        if (!attackHeldPrev) pendingClick = 6;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onStunnedInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!locksLocalPlayer()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!locksLocalPlayer()) return;
        var input = event.getInput();
        input.forwardImpulse = 0.0f;
        input.leftImpulse = 0.0f;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (!KNOCKED.isEmpty() || !IMPACTS.isEmpty() || stunTicks > 0) resetAll();
            return;
        }
        if (mc.isPaused()) return;
        clock++;
        if (stunTicks > 0) {
            stunTicks--;
            mc.player.setSprinting(false);
        }

        boolean held = mc.options.keyAttack.isDown();
        if (active(mc) && !blocked(mc)) {
            if ((pendingClick > 0 || held) && clock >= readyAt) doSwing(mc);
        } else {
            pendingClick = 0;
        }
        if (pendingClick > 0) pendingClick--;
        attackHeldPrev = held;

        Iterator<Map.Entry<Integer, int[]>> ki = KNOCKED.entrySet().iterator();
        while (ki.hasNext()) {
            int[] k = ki.next().getValue();
            if (--k[0] <= 0) ki.remove();
        }
        IMPACTS.removeIf(i -> ++i.age > i.life());
    }

    private static void doSwing(Minecraft mc) {
        pendingClick = 0;
        if (clock - lastSwing > COMBO_WINDOW && step != 0) {
            step = 0;
            leftFirst = !leftFirst;
        }
        int s = step;
        boolean left = s < 3 && ((s % 2 == 0) == leftFirst);
        lastSwing = clock;
        if (s < 3) {
            step++;
            readyAt = clock + (step == 3 ? FINAL_INTERVAL : JAB_INTERVAL);
        } else {
            step = 0;
            leftFirst = !leftFirst;
            readyAt = clock + FINAL_HIT_DELAY + FINAL_RECOVERY;
        }
        playSwing(mc.player, s, left, true);
        M1Combo.request();
    }

    private static void playSwing(Player p, int s, boolean left, boolean local) {
        String anim = s == 3 ? "m1_final" : (left ? "m1_jab_l" : "m1_jab_r");
        if (p instanceof AbstractClientPlayer cp) {
            if (local) MaximumPurpleAnimation.playWithBothArms(cp, anim, s == 3 ? 14 : 9);
            else MaximumPurpleAnimation.play(cp, anim);
        }
        sound(p.position().add(0, 1.3, 0), JujutsuNeonMod.SFX_M1_SWING, s == 3 ? 0.9f : 0.6f,
                (s == 3 ? 0.8f : 1.05f) + RND.nextFloat() * 0.15f);
    }

    static void onRemoteSwing(int entityId, int s, boolean left) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e instanceof Player p && p != mc.player) playSwing(p, s, left, false);
    }

    static void onHit(int attackerId, int kind, Vec3 pos, Vec3 dir, int blockState) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        switch (kind) {
            case 0 -> {
                sound(pos, JujutsuNeonMod.SFX_M1_HIT, 1.0f, 0.9f + RND.nextFloat() * 0.25f);
                IMPACTS.add(new Impact(pos, dir, 0));
            }
            case 1 -> {
                sound(pos, JujutsuNeonMod.SFX_M1_FINAL, 1.6f, 0.95f + RND.nextFloat() * 0.1f);
                IMPACTS.add(new Impact(pos, dir, 1));
            }
            case 2 -> {
                sound(pos, JujutsuNeonMod.SFX_M1_BLOCK, 0.8f, 0.9f + RND.nextFloat() * 0.2f);
                BlockState state = Block.stateById(blockState);
                if (!state.isAir()) {
                    BlockParticleOption opt = new BlockParticleOption(ParticleTypes.BLOCK, state);
                    for (int i = 0; i < 14; i++) {
                        Vec3 v = dir.scale(0.08 + RND.nextDouble() * 0.12)
                                .add((RND.nextDouble() - 0.5) * 0.18, RND.nextDouble() * 0.12, (RND.nextDouble() - 0.5) * 0.18);
                        mc.level.addParticle(opt, pos.x + dir.x * 0.05, pos.y + dir.y * 0.05, pos.z + dir.z * 0.05, v.x, v.y, v.z);
                    }
                }
                IMPACTS.add(new Impact(pos.add(dir.scale(0.03)), dir, 2));
            }
            default -> {
            }
        }
    }

    static void onStun(int entityId, int ticks, boolean knockdown) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e == mc.player) {
            stunTicks = Math.max(stunTicks, ticks);
            if (knockdown) stunTicks = ticks;
        }
        if (!knockdown) return;
        if (e instanceof AbstractClientPlayer p) MaximumPurpleAnimation.play(p, "m1_knockdown");
        else if (e instanceof LivingEntity) KNOCKED.put(entityId, new int[]{ticks, ticks});
    }

    private static void sound(Vec3 at, RegistryObject<SoundEvent> s, float vol, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.level.playLocalSound(at.x, at.y, at.z, s.get(), SoundSource.PLAYERS, vol, pitch, false);
    }

    private static void resetAll() {
        KNOCKED.clear();
        IMPACTS.clear();
        PUSHED.clear();
        stunTicks = 0;
        step = 0;
        pendingClick = 0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

    // ================================================================== сбитые мобы лежат

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (KNOCKED.isEmpty()) return;
        LivingEntity e = event.getEntity();
        int[] k = KNOCKED.get(e.getId());
        if (k == null) return;
        float pt = event.getPartialTick();
        double t = (k[1] - k[0]) + pt;
        // падает за 5 тиков, лежит, поднимается за последние 5
        double lie = Math.min(smooth(t / 5.0), 1.0 - smooth((t - (k[1] - 5)) / 5.0));
        if (lie <= 0.001) return;
        float yaw = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot);
        double rad = Math.toRadians(yaw);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        PUSHED.add(e.getId());
        pose.translate(0.0, e.getBbWidth() * 0.5 * lie, 0.0);
        // на спину: поворот вокруг горизонтальной оси поперёк взгляда
        pose.mulPose(new Quaternionf().rotateAxis((float) Math.toRadians(-90.0 * lie),
                (float) Math.cos(rad), 0f, (float) Math.sin(rad)));
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (PUSHED.remove(event.getEntity().getId())) event.getPoseStack().popPose();
    }

    // ================================================================== эффекты попаданий

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || IMPACTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PoseStack pose = event.getPoseStack();
        Camera cam = event.getCamera();
        Vec3 camera = cam.getPosition();
        float pt = mc.isPaused() ? 0.0f : event.getPartialTick();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // волна удара — поперёк направления удара
        begin(pose, camera, TEX_RIPPLE, true);
        for (Impact i : IMPACTS) {
            double k = (i.age + pt) / i.life();
            float big = i.kind == 1 ? 3.4f : (i.kind == 2 ? 0.9f : 1.3f);
            float a = (float) ((1.0 - k) * (1.0 - k));
            orientedQuad(i.pos, i.dir, (float) (0.2 + big * (1.0 - Math.pow(1.0 - k, 2.5))), 1f, 1f, 1f, a * 0.9f);
            if (i.kind == 1 && k > 0.15) {
                double k2 = (k - 0.15) / 0.85;
                orientedQuad(i.pos.add(i.dir.scale(0.4)), i.dir, (float) (0.4 + 2.2 * k2), 1f, 0.95f, 0.9f, (float) (1.0 - k2) * 0.6f);
            }
        }
        end();
        // вспышка-звёздочка в точке удара
        begin(pose, camera, TEX_STAR, true);
        for (Impact i : IMPACTS) {
            double k = (i.age + pt) / i.life();
            float a = (float) Math.max(0.0, 1.0 - k * 2.2);
            float sz = i.kind == 1 ? 2.2f : (i.kind == 2 ? 0.7f : 1.1f);
            sprite(cam, i.pos, sz * (0.7f + 0.5f * (float) k), 1f, 1f, 1f, a);
        }
        end();
        begin(pose, camera, TEX_GLOW, true);
        for (Impact i : IMPACTS) {
            if (i.kind != 1) continue;
            double k = (i.age + pt) / i.life();
            sprite(cam, i.pos, (float) (1.0 + 2.0 * k), 1f, 0.95f, 0.85f, (float) Math.max(0.0, 1.0 - k * 3.0) * 0.8f);
        }
        end();
        // разлетающиеся штрихи
        begin(pose, camera, TEX_STREAK, true);
        for (Impact i : IMPACTS) {
            if (i.kind == 2) continue;
            double k = (i.age + pt) / i.life();
            Vec3 n = i.dir.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : i.dir.normalize();
            Vec3 ref = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 u = n.cross(ref).normalize(), w = n.cross(u).normalize();
            Random r = new Random(i.seed);
            int count = i.kind == 1 ? 12 : 6;
            float a = (float) (1.0 - k);
            for (int j = 0; j < count; j++) {
                double ang = j / (double) count * Math.PI * 2.0 + r.nextDouble() * 0.5;
                Vec3 d = u.scale(Math.cos(ang)).add(w.scale(Math.sin(ang))).add(n.scale(0.5 + r.nextDouble() * 0.6)).normalize();
                double reach = (i.kind == 1 ? 2.4 : 1.0) * (0.6 + r.nextDouble() * 0.5);
                Vec3 a0 = i.pos.add(d.scale(0.15 + reach * k));
                Vec3 a1 = a0.add(d.scale((i.kind == 1 ? 0.7 : 0.35) * (1.0 - k * 0.5)));
                seg(a0, a1, i.kind == 1 ? 0.05f : 0.03f, 1f, 1f, 1f, a);
            }
        }
        end();
        // пыль у блока
        begin(pose, camera, TEX_PUFF, false);
        for (Impact i : IMPACTS) {
            if (i.kind != 2) continue;
            double k = (i.age + pt) / i.life();
            sprite(cam, i.pos.add(i.dir.scale(0.15 * k)), (float) (0.4 + 0.6 * k), 0.85f, 0.83f, 0.8f, (float) (1.0 - k) * 0.5f);
        }
        end();

        HollowPurpleReferenceClient.mpAlphaBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static double smooth(double v) {
        v = Mth.clamp(v, 0.0, 1.0);
        return v * v * (3.0 - 2.0 * v);
    }

    // ================================================================== пакетная отрисовка

    private static BufferBuilder buf;
    private static Matrix4f mat;
    private static Vec3 cam0;
    private static ResourceLocation pendingTex;
    private static boolean pendingAdditive;
    private static PoseStack pendingPose;

    private static void begin(PoseStack pose, Vec3 camera, ResourceLocation texture, boolean additive) {
        end();
        pendingPose = pose;
        cam0 = camera;
        pendingTex = texture;
        pendingAdditive = additive;
    }

    private static void open() {
        if (buf != null || pendingTex == null) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, pendingTex);
        if (pendingAdditive) HollowPurpleReferenceClient.mpAdditiveBlend();
        else HollowPurpleReferenceClient.mpAlphaBlend();
        mat = pendingPose.last().pose();
        buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void end() {
        if (buf != null) {
            BufferUploader.drawWithShader(buf.end());
            buf = null;
        }
        pendingTex = null;
    }

    private static void v(double x, double y, double z, float u, float vv, float r, float g, float b, float a) {
        buf.vertex(mat, (float) (x - cam0.x), (float) (y - cam0.y), (float) (z - cam0.z)).uv(u, vv).color(r, g, b, a).endVertex();
    }

    private static void seg(Vec3 from, Vec3 to, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-8) return;
        Vec3 side = axis.cross(cam0.subtract(from.add(to).scale(0.5)));
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width);
        open();
        v(from.x + side.x, from.y + side.y, from.z + side.z, 0f, 0f, r, g, b, alpha);
        v(from.x - side.x, from.y - side.y, from.z - side.z, 1f, 0f, r, g, b, alpha);
        v(to.x - side.x, to.y - side.y, to.z - side.z, 1f, 1f, r, g, b, alpha);
        v(to.x + side.x, to.y + side.y, to.z + side.z, 0f, 1f, r, g, b, alpha);
    }

    private static void sprite(Camera cam, Vec3 c, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || size <= 0f) return;
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        double h = size * 0.5;
        open();
        v(c.x + (left.x() + up.x()) * h, c.y + (left.y() + up.y()) * h, c.z + (left.z() + up.z()) * h, 0f, 0f, r, g, b, alpha);
        v(c.x + (left.x() - up.x()) * h, c.y + (left.y() - up.y()) * h, c.z + (left.z() - up.z()) * h, 0f, 1f, r, g, b, alpha);
        v(c.x + (-left.x() - up.x()) * h, c.y + (-left.y() - up.y()) * h, c.z + (-left.z() - up.z()) * h, 1f, 1f, r, g, b, alpha);
        v(c.x + (-left.x() + up.x()) * h, c.y + (-left.y() + up.y()) * h, c.z + (-left.z() + up.z()) * h, 1f, 0f, r, g, b, alpha);
    }

    private static void orientedQuad(Vec3 c, Vec3 normal, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vec3 n = normal.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : normal.normalize();
        Vec3 ref = Math.abs(n.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = n.cross(ref).normalize().scale(size * 0.5);
        Vec3 w = n.cross(u).normalize().scale(size * 0.5);
        open();
        v(c.x - u.x - w.x, c.y - u.y - w.y, c.z - u.z - w.z, 0f, 0f, r, g, b, alpha);
        v(c.x - u.x + w.x, c.y - u.y + w.y, c.z - u.z + w.z, 0f, 1f, r, g, b, alpha);
        v(c.x + u.x + w.x, c.y + u.y + w.y, c.z + u.z + w.z, 1f, 1f, r, g, b, alpha);
        v(c.x + u.x - w.x, c.y + u.y - w.y, c.z + u.z - w.z, 1f, 0f, r, g, b, alpha);
    }
}
