package com.kira.jujutsuneon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Эффекты и анимации движения (дэши, взлёт, полёт, сверхбег) — сеть.
 *
 * Свой игрок всё показывает сразу у себя (MovementFxClient), остальным сервер рассылает
 * события, чтобы они видели те же анимации тела и эффекты.
 */
public final class MovementFx {

    // ---- виды дэша ----
    static final int DASH_FRONT = 0;
    static final int DASH_BACK = 1;
    static final int DASH_LEFT = 2;
    static final int DASH_RIGHT = 3;

    // ---- полёт ----
    static final int FLIGHT_OFF = 0;
    static final int FLIGHT_ON = 1;
    static final int FLIGHT_BOOST = 2;
    static final int FLIGHT_LANDING = 3;

    // ---- взлёт ----
    static final int TAKEOFF_CROUCH = 0;
    static final int TAKEOFF_CANCEL = 1;
    static final int TAKEOFF_LEAP = 2;

    /** Длительности дэшей на клиенте (как в JujutsuNeonMod): вперёд/назад 14 тиков, вбок 4. */
    static final int FRONT_DASH_TICKS = 14;
    static final int SIDE_DASH_TICKS = 4;

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "movement_fx"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, EventPacket.class, EventPacket::encode, EventPacket::decode, EventPacket::handle);
        CHANNEL.registerMessage(id++, TakeoffRequest.class, TakeoffRequest::encode, TakeoffRequest::decode, TakeoffRequest::handle);
    }

    private MovementFx() {
    }

    static void init() {
    }

    static final int EV_DASH = 0;
    static final int EV_FLIGHT = 1;
    static final int EV_TAKEOFF = 2;

    /** S2C: событие движения чужого игрока. */
    public record EventPacket(int entityId, int type, int value) {
        static void encode(EventPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeByte(msg.type);
            buf.writeByte(msg.value);
        }

        static EventPacket decode(FriendlyByteBuf buf) {
            return new EventPacket(buf.readVarInt(), buf.readByte(), buf.readByte());
        }

        static void handle(EventPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    MovementFxClient.onRemote(msg.entityId, msg.type, msg.value)));
            context.setPacketHandled(true);
        }
    }

    /** C2S: свой взлёт (присед / отмена / прыжок) — разослать остальным. */
    public record TakeoffRequest(int phase) {
        static void encode(TakeoffRequest msg, FriendlyByteBuf buf) {
            buf.writeByte(msg.phase);
        }

        static TakeoffRequest decode(FriendlyByteBuf buf) {
            return new TakeoffRequest(buf.readByte());
        }

        static void handle(TakeoffRequest msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender != null && msg.phase >= 0 && msg.phase <= 2) broadcast(sender, EV_TAKEOFF, msg.phase);
            });
            context.setPacketHandled(true);
        }
    }

    static void requestTakeoff(int phase) {
        CHANNEL.sendToServer(new TakeoffRequest(phase));
    }

    /** Остальным игрокам, которые видят этого (сам игрок уже показал у себя). */
    static void broadcast(ServerPlayer player, int type, int value) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> player), new EventPacket(player.getId(), type, value));
    }

    static void broadcastDash(ServerPlayer player, int side) {
        int kind = switch (side) {
            case 2 -> DASH_BACK;
            case -1 -> DASH_LEFT;
            case 1 -> DASH_RIGHT;
            default -> DASH_FRONT;
        };
        broadcast(player, EV_DASH, kind);
    }

    static void broadcastFlight(ServerPlayer player, int mode) {
        broadcast(player, EV_FLIGHT, mode);
    }
}
