package com.kira.jujutsuneon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Покадровые анимации — сеть: сервер запускает анимацию у игрока, её видят он сам и все, кто рядом.
 *
 *   FrameAnimNet.play(serverPlayer, "имя");
 *   FrameAnimNet.play(serverPlayer, "имя", 1.0f, false, 0f, null);   // скорость, зеркально, с кадра, петля
 *   FrameAnimNet.stop(serverPlayer, 0.2f);
 */
public final class FrameAnimNet {

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "frame_anim"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        CHANNEL.registerMessage(0, PlayPacket.class, PlayPacket::encode, PlayPacket::decode, PlayPacket::handle);
    }

    private FrameAnimNet() {
    }

    static void init() {
    }

    public static void play(ServerPlayer player, String name) {
        play(player, name, 1f, false, 0f, null);
    }

    public static void play(ServerPlayer player, String name, float speed, boolean mirror, float startFrame, Boolean loop) {
        byte l = loop == null ? (byte) -1 : (byte) (loop ? 1 : 0);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new PlayPacket(player.getId(), name, speed, mirror, startFrame, l, false, 0f));
    }

    public static void stop(ServerPlayer player, float fadeSeconds) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new PlayPacket(player.getId(), "", 1f, false, 0f, (byte) -1, true, fadeSeconds));
    }

    public record PlayPacket(int entityId, String name, float speed, boolean mirror, float startFrame, byte loop,
                             boolean stop, float fade) {
        static void encode(PlayPacket m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeUtf(m.name, 128);
            buf.writeFloat(m.speed);
            buf.writeBoolean(m.mirror);
            buf.writeFloat(m.startFrame);
            buf.writeByte(m.loop);
            buf.writeBoolean(m.stop);
            buf.writeFloat(m.fade);
        }

        static PlayPacket decode(FriendlyByteBuf buf) {
            return new PlayPacket(buf.readVarInt(), buf.readUtf(128), buf.readFloat(), buf.readBoolean(), buf.readFloat(),
                    buf.readByte(), buf.readBoolean(), buf.readFloat());
        }

        static void handle(PlayPacket m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    FrameAnimations.onNet(m.entityId, m.name, m.speed, m.mirror, m.startFrame, m.loop, m.stop, m.fade)));
            context.setPacketHandled(true);
        }
    }

    /** Встроенные анимации перечитываются вместе с ресурсами (вход в мир, F3+T). */
    @Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientSetup {
        private ClientSetup() {
        }

        @SubscribeEvent
        public static void onReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                    FrameAnimations::reloadBuiltin);
        }
    }
}
