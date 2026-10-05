package com.kira.jujutsuneon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Простая территория (Simple Domain) — удержание V 2 секунды.
 *
 * Круг ~4,5 блока стоит там, где его раскрыли (за игроком не ездит).
 *  - Внутри чужой большой территории: пойманный может ходить, пока стоит в круге; вышел — ещё 0,5 с
 *    свободы, потом снова обездвижен (время обездвиживания всё это время идёт). Из техник доступны только
 *    Синий, Максимальный Синий, Красный и Максимальный Красный.
 *  - Вне большой территории: щит. Пока владелец в круге, любой удар по нему (техника, рука, снаряд)
 *    поглощается полностью; третий удар ломает круг. Обычный и Максимальный Фиолетовый бьют насквозь
 *    и не считаются.
 *  - Владелец вышел из круга — круг сжимается и исчезает. Перезарядка 20 секунд.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SimpleDomain {

    static final double RADIUS = 4.5;
    /** Сколько удерживать V. */
    static final int HOLD_TICKS = 40;
    /** Анимация раскрытия (присед с печатью) — в это время круг растёт, ещё не освобождает. */
    static final int CAST_TICKS = 16;
    static final int FORM_TICKS = 14;
    /** Вышел из круга — ещё 0,5 с можно двигаться. */
    static final int GRACE_TICKS = 10;
    static final int COLLAPSE_TICKS = 30;
    static final int BREAK_TICKS = 12;
    static final int MAX_HITS = 3;
    static final long COOLDOWN_TICKS = 400L;
    /** Удары одного и того же источника чаще этого — один удар (техники бьют по несколько раз за тик). */
    private static final long SAME_SOURCE_TICKS = 10L;

    private static final class SD {
        final UUID ownerId;
        final ServerLevel level;
        final Vec3 center;
        final boolean inDomain;
        int age;
        int outside = -1;
        boolean collapsing;
        int hits;
        UUID lastHitSource;
        long lastHitAt = Long.MIN_VALUE;

        SD(ServerPlayer owner, boolean inDomain) {
            this.ownerId = owner.getUUID();
            this.level = owner.serverLevel();
            this.center = owner.position();
            this.inDomain = inDomain;
        }

        boolean inside(Vec3 p) {
            double dx = p.x - center.x, dz = p.z - center.z;
            return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(p.y - center.y) < 4.0;
        }
    }

    private static final Map<UUID, SD> ACTIVE = new HashMap<>();

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "simple_domain"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, RequestPacket.class, RequestPacket::encode, RequestPacket::decode, RequestPacket::handle);
        CHANNEL.registerMessage(id++, StartPacket.class, StartPacket::encode, StartPacket::decode, StartPacket::handle);
        CHANNEL.registerMessage(id++, HitPacket.class, HitPacket::encode, HitPacket::decode, HitPacket::handle);
        CHANNEL.registerMessage(id++, EndPacket.class, EndPacket::encode, EndPacket::decode, EndPacket::handle);
    }

    private SimpleDomain() {
    }

    static void init() {
    }

    // ------------------------------------------------------------------ API

    /** Игрок стоит в своей простой территории (или ещё 0,5 с после выхода) — обездвиживание на него не действует. */
    static boolean frees(Player player) {
        SD sd = ACTIVE.get(player.getUUID());
        return sd != null && !sd.collapsing && sd.age >= CAST_TICKS && sd.outside <= GRACE_TICKS;
    }

    static void request() {
        CHANNEL.sendToServer(new RequestPacket());
    }

    // ------------------------------------------------------------------ сеть

    public record RequestPacket() {
        static void encode(RequestPacket msg, FriendlyByteBuf buf) {
        }

        static RequestPacket decode(FriendlyByteBuf buf) {
            return new RequestPacket();
        }

        static void handle(RequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            ServerPlayer sender = context.getSender();
            context.enqueueWork(() -> {
                if (sender != null) tryStart(sender);
            });
            context.setPacketHandled(true);
        }
    }

    public record StartPacket(UUID owner, int entityId, double x, double y, double z, boolean inDomain) {
        static void encode(StartPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.owner);
            buf.writeVarInt(msg.entityId);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeBoolean(msg.inDomain);
        }

        static StartPacket decode(FriendlyByteBuf buf) {
            return new StartPacket(buf.readUUID(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean());
        }

        static void handle(StartPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    SimpleDomainClient.onStart(msg.owner, msg.entityId, new Vec3(msg.x, msg.y, msg.z), msg.inDomain)));
            context.setPacketHandled(true);
        }
    }

    public record HitPacket(UUID owner, double x, double y, double z, int hits) {
        static void encode(HitPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.owner);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeByte(msg.hits);
        }

        static HitPacket decode(FriendlyByteBuf buf) {
            return new HitPacket(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readByte());
        }

        static void handle(HitPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    SimpleDomainClient.onHit(msg.owner, new Vec3(msg.x, msg.y, msg.z), msg.hits)));
            context.setPacketHandled(true);
        }
    }

    public record EndPacket(UUID owner, boolean broken) {
        static void encode(EndPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.owner);
            buf.writeBoolean(msg.broken);
        }

        static EndPacket decode(FriendlyByteBuf buf) {
            return new EndPacket(buf.readUUID(), buf.readBoolean());
        }

        static void handle(EndPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    SimpleDomainClient.onEnd(msg.owner, msg.broken)));
            context.setPacketHandled(true);
        }
    }

    private static PacketDistributor.PacketTarget near(SD sd) {
        return PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                sd.center.x, sd.center.y, sd.center.z, 160.0, sd.level.dimension()));
    }

    // ------------------------------------------------------------------ раскрытие

    private static void tryStart(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (!JujutsuNeonMod.hasGojoBlindfold(player)) {
            JujutsuNeonMod.requireBlindfoldMessage(player);
            return;
        }
        if (ACTIVE.containsKey(player.getUUID())) return;
        if (DomainExpansion.isCasting(player) || MaximumPurple.isActive(player) || LapseBlue.isBusy(player)
                || JujutsuNeonMod.isHollowPurpleCasting(player) || JujutsuNeonMod.isMaxRedCasting(player)) {
            player.displayClientMessage(Component.literal("Сейчас нельзя раскрыть простую территорию").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        long now = player.level().getGameTime();
        long cd = player.getPersistentData().getLong("jn_cd_simple_domain");
        if (now < cd) {
            double seconds = Math.ceil((cd - now) / 2.0) / 10.0;
            player.displayClientMessage(Component.literal("Перезарядка простой территории: " + seconds + " сек.")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        player.getPersistentData().putLong("jn_cd_simple_domain", now + COOLDOWN_TICKS);

        SD sd = new SD(player, DomainExpansion.isStunned(player));
        ACTIVE.put(player.getUUID(), sd);
        Vec3 c = sd.center;
        sd.level.playSound(null, c.x, c.y, c.z, JujutsuNeonMod.SFX_SIMPLE_DOMAIN_CAST.get(), SoundSource.PLAYERS, 2.0f, 1.0f);
        CHANNEL.send(near(sd), new StartPacket(sd.ownerId, player.getId(), c.x, c.y, c.z, sd.inDomain));
        player.displayClientMessage(Component.literal("SIMPLE DOMAIN").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
    }

    private static void collapse(SD sd, boolean broken) {
        if (sd.collapsing) return;
        sd.collapsing = true;
        sd.age = 0;
        Vec3 c = sd.center;
        sd.level.playSound(null, c.x, c.y, c.z,
                (broken ? JujutsuNeonMod.SFX_SIMPLE_DOMAIN_BREAK : JujutsuNeonMod.SFX_SIMPLE_DOMAIN_END).get(),
                SoundSource.PLAYERS, broken ? 2.0f : 1.4f, 1.0f);
        CHANNEL.send(near(sd), new EndPacket(sd.ownerId, broken));
    }

    // ------------------------------------------------------------------ тик

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        Iterator<SD> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            SD sd = it.next();
            sd.age++;
            if (sd.collapsing) {
                if (sd.age > COLLAPSE_TICKS) it.remove();
                continue;
            }
            ServerPlayer owner = sd.level.getServer().getPlayerList().getPlayer(sd.ownerId);
            if (owner == null || !owner.isAlive() || owner.level() != sd.level) {
                collapse(sd, false);
                continue;
            }
            if (sd.age < CAST_TICKS) continue;
            if (sd.inside(owner.position())) {
                sd.outside = -1;
            } else {
                sd.outside = sd.outside < 0 ? 1 : sd.outside + 1;
                // Вышел из круга: ещё 0,5 с свободы, потом круг сжимается (и снова действует обездвиживание).
                if (sd.outside > GRACE_TICKS) collapse(sd, false);
            }
        }
    }

    // ------------------------------------------------------------------ щит

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        if (ACTIVE.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        SD sd = ACTIVE.get(player.getUUID());
        if (sd == null || sd.collapsing || sd.inDomain || sd.age < 2) return;
        if (!sd.inside(player.position())) return;
        Entity src = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        if (src == null && direct == null) return; // падение, огонь, голод — не удары
        // Фиолетовый (обычный и максимальный) бьёт насквозь.
        if (src instanceof ServerPlayer attacker && attacker.getPersistentData().getBoolean("jn_purple_custom_damage")) return;

        event.setCanceled(true);
        long now = sd.level.getGameTime();
        UUID sourceId = (src != null ? src : direct).getUUID();
        if (sourceId.equals(sd.lastHitSource) && now - sd.lastHitAt < SAME_SOURCE_TICKS) return;
        sd.lastHitSource = sourceId;
        sd.lastHitAt = now;
        sd.hits++;

        Entity from = direct != null ? direct : src;
        Vec3 chest = player.position().add(0, 1.0, 0);
        Vec3 dir = from.position().add(0, from.getBbHeight() * 0.5, 0).subtract(chest);
        if (dir.lengthSqr() < 1.0E-4) dir = player.getLookAngle();
        Vec3 hit = chest.add(dir.normalize().scale(1.1));
        sd.level.playSound(null, hit.x, hit.y, hit.z, JujutsuNeonMod.SFX_SIMPLE_DOMAIN_HIT.get(), SoundSource.PLAYERS,
                1.4f, 0.95f + sd.level.random.nextFloat() * 0.1f);
        CHANNEL.send(near(sd), new HitPacket(sd.ownerId, hit.x, hit.y, hit.z, sd.hits));
        if (sd.hits >= MAX_HITS) collapse(sd, true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SD sd = ACTIVE.get(event.getEntity().getUUID());
        if (sd != null) collapse(sd, false);
    }
}
