package com.kira.jujutsuneon.rbx;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Анимации игроков по сети — как репликация анимаций в Roblox: анимация, которую клиент запустил своему игроку
 * с {@code replicate(true)}, уходит на сервер и оттуда всем, кто этого игрока видит. Сервер сам тоже может
 * запустить анимацию игроку — её увидят все рядом и он сам:
 * <pre>
 *   RbxNet.play(serverPlayer, "red_cast");
 *   RbxNet.play(serverPlayer, new RbxNet.Anim("red_cast").speed(1.2f).startAt(0.3f));
 *   RbxNet.stop(serverPlayer, 0.2f);
 * </pre>
 */
public final class RbxNet {

    private static final String PROTOCOL = "1";
    private static final int MAX_NAME = 96;
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(R15Rig.NS, "rbx_anim"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        CHANNEL.registerMessage(0, Anim.class, Anim::encode, Anim::decode, RbxNet::handle);
    }

    private RbxNet() {
    }

    /** Вызвать при запуске мода (регистрация канала). */
    public static void init() {
    }

    /** Запуск или остановка анимации у игрока (entityId — кому; 0 в пакете от клиента — себе). */
    public static final class Anim {
        int entityId;
        String name = "";
        float fade = Float.NaN, weight = 1, speed = 1, startAt;
        boolean mirror;
        /** −1 — как в клипе, 0/1 — без петли / петля. */
        byte loop = -1;
        /** −1 — как в клипе, иначе номер {@link AnimationPriority}. */
        byte priority = -1;
        boolean exclusive = true;
        boolean stop;

        public Anim(String name) {
            this.name = name == null ? "" : name;
        }

        Anim() {
        }

        public Anim fade(float s) {
            fade = s;
            return this;
        }

        public Anim weight(float w) {
            weight = w;
            return this;
        }

        public Anim speed(float s) {
            speed = s;
            return this;
        }

        public Anim mirror(boolean m) {
            mirror = m;
            return this;
        }

        public Anim startAt(float s) {
            startAt = s;
            return this;
        }

        public Anim loop(Boolean l) {
            loop = l == null ? -1 : (byte) (l ? 1 : 0);
            return this;
        }

        public Anim priority(AnimationPriority p) {
            priority = p == null ? -1 : (byte) p.ordinal();
            return this;
        }

        public Anim exclusive(boolean e) {
            exclusive = e;
            return this;
        }

        static Anim stopAll(float fade) {
            Anim a = new Anim("");
            a.stop = true;
            a.fade = fade;
            return a;
        }

        static void encode(Anim m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeUtf(m.name, MAX_NAME);
            buf.writeFloat(m.fade);
            buf.writeFloat(m.weight);
            buf.writeFloat(m.speed);
            buf.writeFloat(m.startAt);
            buf.writeBoolean(m.mirror);
            buf.writeByte(m.loop);
            buf.writeByte(m.priority);
            buf.writeBoolean(m.exclusive);
            buf.writeBoolean(m.stop);
        }

        static Anim decode(FriendlyByteBuf buf) {
            Anim m = new Anim();
            m.entityId = buf.readVarInt();
            m.name = buf.readUtf(MAX_NAME);
            m.fade = buf.readFloat();
            m.weight = buf.readFloat();
            m.speed = buf.readFloat();
            m.startAt = buf.readFloat();
            m.mirror = buf.readBoolean();
            m.loop = buf.readByte();
            m.priority = buf.readByte();
            m.exclusive = buf.readBoolean();
            m.stop = buf.readBoolean();
            return m;
        }
    }

    // ------------------------------------------------------------------ сервер

    public static void play(ServerPlayer player, String name) {
        play(player, new Anim(name));
    }

    public static void play(ServerPlayer player, Anim a) {
        a.entityId = player.getId();
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), a);
    }

    /** Плавно остановить анимации техник (fade меньше нуля — как в клипах). */
    public static void stop(ServerPlayer player, float fade) {
        play(player, Anim.stopAll(fade));
    }

    // ------------------------------------------------------------------ приём

    private static void handle(Anim m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        if (c.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
            ServerPlayer sender = c.getSender();
            if (sender != null && m.name.length() <= MAX_NAME) {
                c.enqueueWork(() -> {
                    m.entityId = sender.getId();
                    CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> sender), m);
                });
            }
        } else {
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Client.receive(m)));
        }
        c.setPacketHandled(true);
    }

    /** Отправить на сервер анимацию своего игрока (вызывает {@link PlayerAnimations}). */
    static void sendToServer(Anim a) {
        a.entityId = 0;
        CHANNEL.sendToServer(a);
    }

    private static final class Client {
        static void receive(Anim m) {
            PlayerAnimations.onNet(m);
        }
    }
}
