package com.kira.jujutsuneon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Эффекты Бесконечности и обратной проклятой техники — сеть.
 * Механику (лечение, блок урона, расход энергии) не трогает: только сообщает клиентам, что показать.
 */
public final class CursedFx {

    static final int EV_INFINITY_ON = 0;
    static final int EV_INFINITY_OFF = 1;
    /** Бесконечность включена (повтор раз в секунду — для тех, кто подошёл позже). */
    static final int EV_INFINITY_HOLD = 2;
    /** Удар остановлен Бесконечностью; dir — от игрока к источнику удара. */
    static final int EV_INFINITY_BLOCK = 3;
    static final int EV_RCT = 4;

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "cursed_fx"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        CHANNEL.registerMessage(0, EventPacket.class, EventPacket::encode, EventPacket::decode, EventPacket::handle);
    }

    private CursedFx() {
    }

    static void init() {
    }

    public record EventPacket(int entityId, int type, float dx, float dy, float dz) {
        static void encode(EventPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entityId);
            buf.writeByte(msg.type);
            buf.writeFloat(msg.dx);
            buf.writeFloat(msg.dy);
            buf.writeFloat(msg.dz);
        }

        static EventPacket decode(FriendlyByteBuf buf) {
            return new EventPacket(buf.readVarInt(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        static void handle(EventPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    CursedFxClient.onEvent(msg.entityId, msg.type, new Vec3(msg.dx, msg.dy, msg.dz))));
            context.setPacketHandled(true);
        }
    }

    static void send(ServerPlayer player, int type, Vec3 dir) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new EventPacket(player.getId(), type, (float) dir.x, (float) dir.y, (float) dir.z));
    }

    static void send(ServerPlayer player, int type) {
        send(player, type, Vec3.ZERO);
    }
}
