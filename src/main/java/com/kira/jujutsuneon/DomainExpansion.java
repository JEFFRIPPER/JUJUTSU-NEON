package com.kira.jujutsuneon;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Clearable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;

/**
 * Расширение территории «Бесконечная пустота» (клавиша T).
 *
 * Территория строится прямо в этом мире, без перехода в другое измерение:
 *  - идеальная сфера радиусом 40 блоков вокруг владельца;
 *  - на время территории внутри сферы нет ни одного блока: всё сохраняется (вместе с содержимым
 *    сундуков) и убирается, а ходят по невидимому абсолютно нерушимому полу на высоте блока
 *    под ногами владельца;
 *  - снаружи — гладкая чёрная сфера (рисует клиент), через неё нельзя пройти ни внутрь, ни наружу,
 *    удары и снаряды через неё не проходят;
 *  - все живые внутри, кроме владельца, обездвижены всё время территории и ещё 2 минуты после;
 *  - территория держится 15 секунд после катсцены каста (10,8 с), повторное T рушит её раньше;
 *  - при разрушении мир возвращается в точности как был, а застрявших в блоках поднимает наверх.
 *
 * Стоит 100% проклятой энергии, внутри территории энергия владельца бесконечна, после — 0.
 * Перезарядка 2 минуты от конца территории.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DomainExpansion {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- Таймлайн в тиках от нажатия T (общий для сервера и клиента) ----
    /** 5,4 с: мир растворяется, снаружи вырастает чёрная сфера. */
    static final int T_FORM = 108;
    /** 5,8 с: сфера сомкнулась — стены, пол, внутри убираются блоки. */
    static final int T_WALL = 116;
    /** 10,8 с: катсцена закончилась, владелец свободен. */
    static final int T_CAST_END = 216;
    /** Сколько стоит территория после катсцены. */
    static final int ACTIVE_TICKS = 300;
    /** Сколько после начала разрушения длятся трещины до белого экрана. */
    static final int T_SHATTER = 9;

    static final double RADIUS = 40.0;
    /** Блоки убираются с запасом, чтобы ни один угол блока не торчал внутрь сферы. */
    static final double REMOVE_RADIUS = RADIUS + 1.0;
    /** Внешняя граница для тех, кто снаружи. */
    static final double OUTER_RADIUS = REMOVE_RADIUS;

    private static final long COOLDOWN_TICKS = 2400L;
    private static final long STUN_AFTER_TICKS = 2400L;
    private static final double ENERGY_COST = 100.0;
    private static final int BLOCK_FLAGS = 2 | 16 | 32;
    private static final long BUDGET_REMOVE_NANOS = 26_000_000L;
    private static final long BUDGET_RESTORE_NANOS = 40_000_000L;

    // ---- Блок пола ----
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, JujutsuNeonMod.MODID);
    static final RegistryObject<Block> FLOOR = BLOCKS.register("domain_floor", () -> new FloorBlock(
            BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3_600_000.0f)
                    .noLootTable()
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)
                    .isValidSpawn((s, g, p, t) -> false)
                    .isSuffocating((s, g, p) -> false)
                    .isViewBlocking((s, g, p) -> false)));

    /** Невидимый абсолютно нерушимый пол территории. */
    static final class FloorBlock extends Block {
        FloorBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        public RenderShape getRenderShape(BlockState state) {
            return RenderShape.INVISIBLE;
        }

        @Override
        public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
            return true;
        }

        @Override
        public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
            return 1.0f;
        }
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }

    // ---- Сеть ----
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "domain_expansion"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, RequestPacket.class, RequestPacket::encode, RequestPacket::decode, RequestPacket::handle);
        CHANNEL.registerMessage(id++, StartPacket.class, StartPacket::encode, StartPacket::decode, StartPacket::handle);
        CHANNEL.registerMessage(id++, CollapsePacket.class, CollapsePacket::encode, CollapsePacket::decode, CollapsePacket::handle);
        CHANNEL.registerMessage(id++, EndPacket.class, EndPacket::encode, EndPacket::decode, EndPacket::handle);
        CHANNEL.registerMessage(id++, StunPacket.class, StunPacket::encode, StunPacket::decode, StunPacket::handle);
    }

    /** C2S: нажата T. */
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
                if (sender != null) onRequest(sender);
            });
            context.setPacketHandled(true);
        }
    }

    /** S2C: территория есть (новая или для зашедшего позже). age — сколько тиков прошло. */
    public record StartPacket(UUID ownerId, double x, double y, double z, int floorY, float yaw, int age,
                              boolean collapsing, List<UUID> watchers) {
        static void encode(StartPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeVarInt(msg.floorY);
            buf.writeFloat(msg.yaw);
            buf.writeVarInt(msg.age);
            buf.writeBoolean(msg.collapsing);
            buf.writeVarInt(msg.watchers.size());
            for (UUID id : msg.watchers) buf.writeUUID(id);
        }

        static StartPacket decode(FriendlyByteBuf buf) {
            UUID owner = buf.readUUID();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            int floorY = buf.readVarInt();
            float yaw = buf.readFloat();
            int age = buf.readVarInt();
            boolean collapsing = buf.readBoolean();
            int n = Math.min(buf.readVarInt(), 256);
            List<UUID> watchers = new ArrayList<>(n);
            for (int i = 0; i < n; i++) watchers.add(buf.readUUID());
            return new StartPacket(owner, x, y, z, floorY, yaw, age, collapsing, watchers);
        }

        static void handle(StartPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    DomainExpansionClient.onStart(msg.ownerId, new Vec3(msg.x, msg.y, msg.z), msg.floorY, msg.yaw,
                            msg.age, msg.collapsing, msg.watchers)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: территория начала рушиться (трещины, звук стекла, белый экран). */
    public record CollapsePacket(UUID ownerId) {
        static void encode(CollapsePacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
        }

        static CollapsePacket decode(FriendlyByteBuf buf) {
            return new CollapsePacket(buf.readUUID());
        }

        static void handle(CollapsePacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    DomainExpansionClient.onCollapse(msg.ownerId)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: мир восстановлен — белый экран уходит. */
    public record EndPacket(UUID ownerId) {
        static void encode(EndPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
        }

        static EndPacket decode(FriendlyByteBuf buf) {
            return new EndPacket(buf.readUUID());
        }

        static void handle(EndPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    DomainExpansionClient.onEnd(msg.ownerId)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: обездвиживание игрока. ticks: -1 — пока стоит территория (примерно estimate тиков), 0 — снято. */
    public record StunPacket(UUID domainOwner, int ticks, int estimate) {
        static void encode(StunPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.domainOwner);
            buf.writeVarInt(msg.ticks + 1);
            buf.writeVarInt(msg.estimate);
        }

        static StunPacket decode(FriendlyByteBuf buf) {
            return new StunPacket(buf.readUUID(), buf.readVarInt() - 1, buf.readVarInt());
        }

        static void handle(StunPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    DomainExpansionClient.onStun(msg.domainOwner, msg.ticks, msg.estimate)));
            context.setPacketHandled(true);
        }
    }

    /** Клиент → сервер. */
    static void request() {
        CHANNEL.sendToServer(new RequestPacket());
    }

    // ---- Состояние ----
    private static final int PHASE_CAST = 0;
    private static final int PHASE_ACTIVE = 1;
    private static final int PHASE_COLLAPSE = 2;

    private static final class Domain {
        final UUID ownerId;
        final ServerLevel level;
        final Vec3 center;
        final int floorY;
        final float yaw;
        final boolean ownerMayfly;
        final boolean ownerFlying;
        int age;
        int phase = PHASE_CAST;
        int collapseAge = -1;
        boolean ownerAbilitiesRestored;

        final Set<UUID> captured = new HashSet<>();
        final List<UUID> watchers = new ArrayList<>();

        /** Колонки (x, z), отсортированные по чанкам. */
        int[] columns;
        int removeIndex;
        boolean removed;
        boolean prepared;
        boolean snapshotWritten;

        final LongArrayList floorPositions = new LongArrayList();
        final LongOpenHashSet floorSet = new LongOpenHashSet();
        final LongArrayList savedPos = new LongArrayList();
        final List<BlockState> savedState = new ArrayList<>();
        final Long2ObjectOpenHashMap<CompoundTag> savedBlockEntities = new Long2ObjectOpenHashMap<>();
        final List<CompoundTag> hiddenEntities = new ArrayList<>();

        int restoreIndex;
        boolean floorCleared;
        boolean restored;

        Domain(ServerPlayer owner, int floorY) {
            this.ownerId = owner.getUUID();
            this.level = owner.serverLevel();
            this.center = owner.position();
            this.floorY = floorY;
            this.yaw = owner.getYRot();
            this.ownerMayfly = owner.getAbilities().mayfly;
            this.ownerFlying = owner.getAbilities().flying;
        }

        boolean wallsUp() {
            return age >= T_WALL && !restored;
        }

        boolean contains(Vec3 p, double radius) {
            return p.distanceToSqr(center) <= radius * radius;
        }

        boolean containsBlock(BlockPos pos, double radius) {
            double dx = pos.getX() + 0.5 - center.x, dy = pos.getY() + 0.5 - center.y, dz = pos.getZ() + 0.5 - center.z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }

        int remainingEstimate() {
            if (phase >= PHASE_COLLAPSE) return 0;
            return Math.max(0, T_CAST_END + ACTIVE_TICKS - age);
        }
    }

    private static final Map<UUID, Domain> DOMAINS = new HashMap<>();

    /** Обездвиженные (мобы и игроки). until = Long.MAX_VALUE, пока стоит территория. */
    private static final class Stun {
        final UUID entityId;
        final ServerLevel level;
        final UUID domainOwner;
        long until = Long.MAX_VALUE;

        Stun(UUID entityId, ServerLevel level, UUID domainOwner) {
            this.entityId = entityId;
            this.level = level;
            this.domainOwner = domainOwner;
        }
    }

    private static final Map<UUID, Stun> STUNS = new HashMap<>();
    private static final String TAG_STUN_UNTIL = "jn_dom_stun_until";
    private static final String TAG_PREV_NOAI = "jn_dom_prev_noai";

    private DomainExpansion() {
    }

    // ------------------------------------------------------------------ API для остальных техник

    /** Игрок обездвижен территорией. */
    static boolean isStunned(Player player) {
        return STUNS.containsKey(player.getUUID());
    }

    /** Владелец во время катсцены каста. */
    static boolean isCasting(Player player) {
        Domain d = DOMAINS.get(player.getUUID());
        return d != null && d.age < T_CAST_END;
    }

    /** Игроку сейчас нельзя ничего делать (обездвижен или кастует территорию). */
    static boolean blocksActions(Player player) {
        return isStunned(player) || isCasting(player);
    }

    /** Владелец территории, которая сейчас стоит. */
    static boolean ownsActiveDomain(Player player) {
        Domain d = DOMAINS.get(player.getUUID());
        return d != null && d.phase == PHASE_ACTIVE;
    }

    /** Точка внутри какой-либо территории (со стенами). */
    static boolean isInsideAny(Level level, Vec3 p) {
        for (Domain d : DOMAINS.values()) {
            if (d.level == level && d.wallsUp() && d.contains(p, RADIUS + 0.5)) return true;
        }
        return false;
    }

    /**
     * Можно ли технике ломать этот блок: блоки внутри территории неприкосновенны, а тот, кто
     * сам стоит внутри территории, ничего не ломает и снаружи — удары за стены не выходят.
     */
    static boolean denyTechniqueEdit(Level level, BlockPos pos, Entity source) {
        if (DOMAINS.isEmpty()) return false;
        for (Domain d : DOMAINS.values()) {
            if (d.level != level || d.age < T_FORM || d.restored) continue;
            if (d.containsBlock(pos, REMOVE_RADIUS + 1.0)) return true;
            if (source != null && d.contains(source.position(), RADIUS + 0.5)) return true;
        }
        return false;
    }

    /** Между двумя точками стоит стена какой-либо территории. */
    static boolean separated(Level level, Vec3 a, Vec3 b) {
        for (Domain d : DOMAINS.values()) {
            if (d.level != level || !d.wallsUp()) continue;
            if (d.contains(a, RADIUS + 0.5) != d.contains(b, RADIUS + 0.5)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ старт / разрушение по T

    private static void onRequest(ServerPlayer player) {
        Domain own = DOMAINS.get(player.getUUID());
        if (own != null) {
            // Повторное T рушит территорию (после катсцены каста).
            if (own.phase == PHASE_ACTIVE) beginCollapse(own);
            return;
        }
        tryStart(player);
    }

    private static void tryStart(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (!JujutsuNeonMod.hasGojoBlindfold(player)) {
            JujutsuNeonMod.requireBlindfoldMessage(player);
            return;
        }
        if (isStunned(player) || MaximumPurple.isActive(player)
                || JujutsuNeonMod.isHollowPurpleCasting(player) || JujutsuNeonMod.isMaximumBlueActive(player)) {
            player.displayClientMessage(Component.literal("Сейчас нельзя раскрыть территорию").withStyle(ChatFormatting.GRAY), true);
            return;
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long cooldownUntil = player.getPersistentData().getLong("jn_cd_domain_expansion");
        if (now < cooldownUntil) {
            double seconds = Math.ceil((cooldownUntil - now) / 2.0) / 10.0;
            player.displayClientMessage(Component.literal("Расширение территории: перезарядка " + seconds + " сек.")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }

        for (Domain other : DOMAINS.values()) {
            if (other.level != level) continue;
            if (other.center.distanceTo(player.position()) < OUTER_RADIUS * 2.0 + 2.0) {
                player.displayClientMessage(Component.literal("Пока нельзя открыть территорию: рядом находится другая")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
                return;
            }
        }

        if (JujutsuNeonMod.getEnergy(player) + 1.0E-6 < ENERGY_COST) {
            player.displayClientMessage(Component.literal("Недостаточно проклятой энергии: нужно 100%")
                    .withStyle(ChatFormatting.AQUA), true);
            return;
        }

        JujutsuNeonFlightPatch.stopForTechnique(player);
        JujutsuNeonMod.setEnergy(player, 0.0);
        // Пока стоит территория, повторно её не открыть; настоящий откат — от конца.
        player.getPersistentData().putLong("jn_cd_domain_expansion", now + 1_000_000L);

        // Пол — на высоте блока под ногами (в воздухе — прямо под ногами).
        int floorY = Mth.floor(player.getY() - 1.0E-4) - 1;
        Domain d = new Domain(player, floorY);
        DOMAINS.put(d.ownerId, d);

        // Все живые в радиусе 40 пойманы с момента нажатия; игроки смотрят катсцену вместе с владельцем.
        captureInside(d, true);

        player.getAbilities().mayfly = true;
        player.onUpdateAbilities();
        player.fallDistance = 0.0f;

        sendStart(d, PacketDistributor.DIMENSION.with(() -> level.dimension()));
    }

    private static void sendStart(Domain d, PacketDistributor.PacketTarget target) {
        CHANNEL.send(target, new StartPacket(d.ownerId, d.center.x, d.center.y, d.center.z, d.floorY, d.yaw, d.age,
                d.phase >= PHASE_COLLAPSE, d.watchers));
    }

    private static void captureInside(Domain d, boolean watchers) {
        AABB box = new AABB(d.center, d.center).inflate(RADIUS);
        for (LivingEntity e : d.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand))) {
            if (e.getUUID().equals(d.ownerId) || d.captured.contains(e.getUUID())) continue;
            if (!d.contains(e.position().add(0.0, e.getBbHeight() * 0.5, 0.0), RADIUS)) continue;
            d.captured.add(e.getUUID());
            if (watchers && e instanceof ServerPlayer) d.watchers.add(e.getUUID());
            freeze(d, e);
        }
    }

    // ------------------------------------------------------------------ тик

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!DOMAINS.isEmpty()) {
            Iterator<Map.Entry<UUID, Domain>> it = DOMAINS.entrySet().iterator();
            while (it.hasNext()) {
                Domain d = it.next().getValue();
                if (tickDomain(d)) it.remove();
            }
        }
        if (!STUNS.isEmpty()) tickStuns();
    }

    /** true — территория закончилась и удалена. */
    private static boolean tickDomain(Domain d) {
        ServerPlayer owner = d.level.getServer().getPlayerList().getPlayer(d.ownerId);
        boolean ownerHere = owner != null && owner.isAlive() && owner.level() == d.level;

        if (!ownerHere && d.phase < PHASE_COLLAPSE) beginCollapse(d);

        if (d.phase < PHASE_COLLAPSE) {
            if (d.age == T_WALL) prepare(d);
            if (d.prepared && !d.removed) removeStep(d, BUDGET_REMOVE_NANOS);
            if (d.removed && !d.snapshotWritten) writeSnapshot(d);
            if (d.removed && d.age % 20 == 0) cleanupStep(d);

            if (ownerHere) {
                owner.fallDistance = 0.0f;
                if (d.age < T_CAST_END) {
                    holdOwner(d, owner);
                } else {
                    if (!d.ownerAbilitiesRestored) restoreOwnerAbilities(d, owner);
                    // Внутри территории энергия бесконечная.
                    JujutsuNeonMod.setEnergy(owner, ENERGY_COST);
                }
            }

            if (d.phase == PHASE_CAST && d.age >= T_CAST_END) d.phase = PHASE_ACTIVE;
            if (d.phase == PHASE_ACTIVE && d.age >= T_CAST_END + ACTIVE_TICKS) beginCollapse(d);
        }

        if (d.age >= T_WALL && !d.restored) enforceWalls(d);

        if (d.phase == PHASE_COLLAPSE) {
            if (d.age >= d.collapseAge + T_SHATTER + 2) restoreStep(d, BUDGET_RESTORE_NANOS);
            if (d.restored) {
                finish(d, ownerHere ? owner : d.level.getServer().getPlayerList().getPlayer(d.ownerId));
                return true;
            }
        }

        d.age++;
        return false;
    }

    private static void holdOwner(Domain d, ServerPlayer owner) {
        Vec3 p = owner.position();
        double dx = p.x - d.center.x, dz = p.z - d.center.z;
        if (dx * dx + dz * dz > 1.5 * 1.5 || Math.abs(p.y - d.center.y) > 3.0) {
            owner.connection.teleport(d.center.x, Math.max(d.center.y, d.floorY + 1.0), d.center.z, d.yaw, 0.0f);
        }
    }

    private static void restoreOwnerAbilities(Domain d, ServerPlayer owner) {
        owner.getAbilities().mayfly = d.ownerMayfly;
        owner.getAbilities().flying = d.ownerMayfly && d.ownerFlying;
        owner.onUpdateAbilities();
        d.ownerAbilitiesRestored = true;
    }

    private static void beginCollapse(Domain d) {
        if (d.phase >= PHASE_COLLAPSE) return;
        d.phase = PHASE_COLLAPSE;
        d.collapseAge = d.age;
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> d.level.dimension()), new CollapsePacket(d.ownerId));
        ServerPlayer owner = d.level.getServer().getPlayerList().getPlayer(d.ownerId);
        if (owner != null && !d.ownerAbilitiesRestored) restoreOwnerAbilities(d, owner);
    }

    // ------------------------------------------------------------------ пол, удаление блоков

    private static void prepare(Domain d) {
        // Кто зашёл в радиус, пока сфера росла, — тоже внутри.
        captureInside(d, false);
        for (UUID id : d.captured) {
            Entity e = d.level.getEntity(id);
            if (e instanceof ServerPlayer sp) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new StunPacket(d.ownerId, -1, d.remainingEstimate()));
            }
        }

        hideEntities(d);
        buildColumns(d);
        placeFloor(d);

        // Кто оказался под полом (в пещере внутри сферы) — на пол.
        for (UUID id : d.captured) liftAboveFloor(d, d.level.getEntity(id));
        ServerPlayer owner = d.level.getServer().getPlayerList().getPlayer(d.ownerId);
        if (owner != null) liftAboveFloor(d, owner);
        d.prepared = true;
    }

    private static void liftAboveFloor(Domain d, Entity e) {
        if (e == null || !e.isAlive()) return;
        if (e.getY() < d.floorY + 1.0 && d.contains(e.position(), RADIUS + 1.0)) {
            double y = d.floorY + 1.0;
            if (e instanceof ServerPlayer sp) sp.connection.teleport(e.getX(), y, e.getZ(), sp.getYRot(), sp.getXRot());
            else e.setPos(e.getX(), y, e.getZ());
            e.setDeltaMovement(Vec3.ZERO);
            e.fallDistance = 0.0f;
        }
    }

    /** Предметы, картины, рамки, стойки, вагонетки, лодки — прячем и потом возвращаем; снаряды — убираем. */
    private static void hideEntities(Domain d) {
        AABB box = new AABB(d.center, d.center).inflate(REMOVE_RADIUS + 1.0);
        List<Entity> list = d.level.getEntities((Entity) null, box, e -> !(e instanceof Player));
        for (Entity e : list) {
            if (!e.isAlive() || !d.contains(e.position(), REMOVE_RADIUS + 0.5)) continue;
            if (e instanceof LivingEntity && !(e instanceof ArmorStand)) continue;
            if (e instanceof Projectile) {
                e.discard();
                continue;
            }
            if (e.isPassenger()) continue;
            e.ejectPassengers();
            CompoundTag tag = new CompoundTag();
            if (e.saveAsPassenger(tag)) d.hiddenEntities.add(tag);
            e.discard();
        }
    }

    private static void buildColumns(Domain d) {
        int cx = Mth.floor(d.center.x), cz = Mth.floor(d.center.z);
        int r = (int) Math.ceil(REMOVE_RADIUS) + 1;
        List<long[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                double hx = x + 0.5 - d.center.x, hz = z + 0.5 - d.center.z;
                if (hx * hx + hz * hz > REMOVE_RADIUS * REMOVE_RADIUS) continue;
                long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
                list.add(new long[]{chunkKey, x, z});
            }
        }
        list.sort((a, b) -> Long.compare(a[0], b[0]));
        d.columns = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            d.columns[i * 2] = (int) list.get(i)[1];
            d.columns[i * 2 + 1] = (int) list.get(i)[2];
        }
    }

    private static int columnBottom(Domain d, int x, int z) {
        double hx = x + 0.5 - d.center.x, hz = z + 0.5 - d.center.z;
        double h = Math.sqrt(Math.max(0.0, REMOVE_RADIUS * REMOVE_RADIUS - hx * hx - hz * hz));
        int bottom = (int) Math.ceil(d.center.y - h - 0.5);
        return Math.max(bottom, d.level.getMinBuildHeight());
    }

    private static int columnTop(Domain d, int x, int z) {
        double hx = x + 0.5 - d.center.x, hz = z + 0.5 - d.center.z;
        double h = Math.sqrt(Math.max(0.0, REMOVE_RADIUS * REMOVE_RADIUS - hx * hx - hz * hz));
        int top = (int) Math.floor(d.center.y + h - 0.5);
        return Math.min(top, d.level.getMaxBuildHeight() - 1);
    }

    private static void placeFloor(Domain d) {
        if (d.floorY < d.level.getMinBuildHeight() || d.floorY >= d.level.getMaxBuildHeight()) return;
        BlockState floor = FLOOR.get().defaultBlockState();
        MutableBlockPos pos = new MutableBlockPos();
        for (int i = 0; i < d.columns.length / 2; i++) {
            int x = d.columns[i * 2], z = d.columns[i * 2 + 1];
            pos.set(x, d.floorY, z);
            if (!d.containsBlock(pos, REMOVE_RADIUS)) continue;
            LevelChunk chunk = d.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) continue;
            BlockState state = chunk.getBlockState(pos);
            saveBlock(d, pos, state);
            d.level.setBlock(pos, floor, BLOCK_FLAGS);
            long key = pos.asLong();
            d.floorPositions.add(key);
            d.floorSet.add(key);
        }
    }

    private static void saveBlock(Domain d, BlockPos pos, BlockState state) {
        if (state.isAir()) return;
        long key = pos.asLong();
        d.savedPos.add(key);
        d.savedState.add(state);
        if (state.hasBlockEntity()) {
            BlockEntity be = d.level.getBlockEntity(pos);
            if (be != null) {
                d.savedBlockEntities.put(key, be.saveWithFullMetadata());
                Clearable.tryClear(be);
            }
        }
    }

    private static void removeStep(Domain d, long budget) {
        long deadline = System.nanoTime() + budget;
        int total = d.columns.length / 2;
        BlockState air = Blocks.AIR.defaultBlockState();
        Block floorBlock = FLOOR.get();
        MutableBlockPos pos = new MutableBlockPos();
        LevelChunk chunk = null;
        int lastCX = Integer.MIN_VALUE, lastCZ = Integer.MIN_VALUE;
        while (d.removeIndex < total) {
            int x = d.columns[d.removeIndex * 2], z = d.columns[d.removeIndex * 2 + 1];
            if ((x >> 4) != lastCX || (z >> 4) != lastCZ) {
                lastCX = x >> 4;
                lastCZ = z >> 4;
                chunk = d.level.getChunkSource().getChunkNow(lastCX, lastCZ);
            }
            if (chunk != null) {
                int y = columnTop(d, x, z);
                int bottom = columnBottom(d, x, z);
                while (y >= bottom) {
                    int si = chunk.getSectionIndex(y);
                    LevelChunkSection section = si >= 0 && si < chunk.getSectionsCount() ? chunk.getSection(si) : null;
                    if (section == null || section.hasOnlyAir()) {
                        y = ((y >> 4) << 4) - 1;
                        continue;
                    }
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isAir() && !state.is(floorBlock) && d.containsBlock(pos, REMOVE_RADIUS)) {
                        saveBlock(d, pos, state);
                        d.level.setBlock(pos, air, BLOCK_FLAGS);
                    }
                    y--;
                }
            }
            d.removeIndex++;
            if ((d.removeIndex & 3) == 0 && System.nanoTime() > deadline) return;
        }
        d.removed = true;
    }

    /** Всё, что попало внутрь после (натекла вода, сдвинул поршень), — убираем: внутри нет блоков. */
    private static void cleanupStep(Domain d) {
        BlockState air = Blocks.AIR.defaultBlockState();
        Block floorBlock = FLOOR.get();
        MutableBlockPos pos = new MutableBlockPos();
        for (int i = 0; i < d.columns.length / 2; i++) {
            int x = d.columns[i * 2], z = d.columns[i * 2 + 1];
            LevelChunk chunk = d.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) continue;
            int y = columnTop(d, x, z);
            int bottom = columnBottom(d, x, z);
            while (y >= bottom) {
                int si = chunk.getSectionIndex(y);
                LevelChunkSection section = si >= 0 && si < chunk.getSectionsCount() ? chunk.getSection(si) : null;
                if (section == null || section.hasOnlyAir()) {
                    y = ((y >> 4) << 4) - 1;
                    continue;
                }
                pos.set(x, y, z);
                BlockState state = chunk.getBlockState(pos);
                if (!state.isAir() && !state.is(floorBlock) && d.containsBlock(pos, REMOVE_RADIUS)) {
                    if (state.hasBlockEntity()) Clearable.tryClear(d.level.getBlockEntity(pos));
                    d.level.setBlock(pos, air, BLOCK_FLAGS);
                }
                y--;
            }
        }
    }

    // ------------------------------------------------------------------ восстановление

    private static void restoreStep(Domain d, long budget) {
        long deadline = System.nanoTime() + budget;
        BlockState air = Blocks.AIR.defaultBlockState();
        MutableBlockPos pos = new MutableBlockPos();
        if (!d.floorCleared) {
            for (int i = 0; i < d.floorPositions.size(); i++) {
                pos.set(d.floorPositions.getLong(i));
                d.level.setBlock(pos, air, BLOCK_FLAGS);
            }
            d.floorCleared = true;
        }
        int total = d.savedPos.size();
        while (d.restoreIndex < total) {
            int i = d.restoreIndex;
            long key = d.savedPos.getLong(i);
            pos.set(key);
            BlockState state = d.savedState.get(i);
            d.level.setBlock(pos, state, BLOCK_FLAGS);
            CompoundTag tag = d.savedBlockEntities.get(key);
            if (tag != null) {
                BlockEntity be = d.level.getBlockEntity(pos);
                if (be != null) {
                    be.load(tag);
                    be.setChanged();
                    d.level.sendBlockUpdated(pos, state, state, 3);
                }
            }
            d.restoreIndex++;
            if ((d.restoreIndex & 15) == 0 && System.nanoTime() > deadline && budget > 0) return;
        }
        restoreHiddenEntities(d);
        d.restored = true;
    }

    private static void restoreHiddenEntities(Domain d) {
        for (CompoundTag tag : d.hiddenEntities) {
            Entity e = EntityType.loadEntityRecursive(tag, d.level, ent -> ent);
            if (e != null) d.level.addFreshEntity(e);
        }
        d.hiddenEntities.clear();
    }

    private static void finish(Domain d, ServerPlayer owner) {
        long now = d.level.getGameTime();

        // Кого после возвращения блоков зажало — поднимаем на ближайшее свободное место сверху.
        AABB box = new AABB(d.center, d.center).inflate(REMOVE_RADIUS + 2.0);
        for (LivingEntity e : d.level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (!d.contains(e.position(), REMOVE_RADIUS + 2.0)) continue;
            unstick(e);
            e.fallDistance = 0.0f;
        }

        // Обездвиживание продолжается ещё 2 минуты после разрушения.
        for (Stun s : STUNS.values()) {
            if (!s.domainOwner.equals(d.ownerId) || s.until != Long.MAX_VALUE) continue;
            s.until = now + STUN_AFTER_TICKS;
            Entity e = s.level.getEntity(s.entityId);
            if (e instanceof Mob mob) mob.getPersistentData().putLong(TAG_STUN_UNTIL, s.until);
            if (e instanceof ServerPlayer sp) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new StunPacket(d.ownerId, (int) STUN_AFTER_TICKS, 0));
            }
        }

        if (owner != null) {
            if (!d.ownerAbilitiesRestored) restoreOwnerAbilities(d, owner);
            JujutsuNeonMod.setEnergy(owner, 0.0);
            owner.getPersistentData().putLong("jn_cd_domain_expansion", now + COOLDOWN_TICKS);
            owner.fallDistance = 0.0f;
        }

        deleteSnapshot(d.level.getServer(), d.ownerId);
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> d.level.dimension()), new EndPacket(d.ownerId));
        if (owner != null && owner.level() != d.level) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), new EndPacket(d.ownerId));
        }
    }

    private static void unstick(Entity e) {
        Level level = e.level();
        AABB box = e.getBoundingBox();
        int maxUp = level.getMaxBuildHeight() - Mth.floor(e.getY());
        for (int up = 0; up <= maxUp && up < 400; up++) {
            AABB moved = box.move(0.0, up, 0.0);
            if (level.noCollision(e, moved)) {
                if (up == 0) return;
                double y = Mth.floor(e.getY()) + up;
                if (e instanceof ServerPlayer sp) sp.connection.teleport(e.getX(), y, e.getZ(), sp.getYRot(), sp.getXRot());
                else e.setPos(e.getX(), y, e.getZ());
                e.setDeltaMovement(Vec3.ZERO);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ стены

    private static void enforceWalls(Domain d) {
        AABB box = new AABB(d.center, d.center).inflate(OUTER_RADIUS + 6.0);
        for (Entity e : d.level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
            Vec3 c = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
            if (e instanceof Projectile) {
                Vec3 old = new Vec3(e.xo, e.yo + e.getBbHeight() * 0.5, e.zo);
                if (d.contains(old, RADIUS + 0.5) != d.contains(c, RADIUS + 0.5)
                        || (c.distanceTo(d.center) > RADIUS - 0.5 && c.distanceTo(d.center) < OUTER_RADIUS + 0.5)) {
                    e.discard();
                }
                continue;
            }
            boolean insider;
            if (e instanceof LivingEntity && !(e instanceof ArmorStand)) {
                insider = e.getUUID().equals(d.ownerId) || d.captured.contains(e.getUUID());
                if (!insider && d.phase < PHASE_COLLAPSE && d.contains(c, RADIUS - 1.0)) {
                    // Появился внутри (призвали, заспавнили) — он тоже в территории.
                    d.captured.add(e.getUUID());
                    freeze(d, (LivingEntity) e);
                    if (e instanceof ServerPlayer sp) {
                        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new StunPacket(d.ownerId, -1, d.remainingEstimate()));
                    }
                    insider = true;
                }
            } else {
                insider = d.contains(c, RADIUS);
            }
            Vec3 off = c.subtract(d.center);
            double dist = off.length();
            double extent = Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5;
            Vec3 n = dist < 1.0E-4 ? new Vec3(0.0, 1.0, 0.0) : off.scale(1.0 / dist);
            if (insider) {
                double limit = RADIUS - extent - 0.05;
                if (dist > limit) pushTo(e, d.center.add(n.scale(limit)), n, true, dist - limit);
            } else {
                double limit = OUTER_RADIUS + extent + 0.05;
                if (dist < limit) pushTo(e, d.center.add(n.scale(limit)), n, false, limit - dist);
            }
        }
    }

    private static void pushTo(Entity e, Vec3 centerTarget, Vec3 normal, boolean inward, double depth) {
        Vec3 feet = centerTarget.subtract(0.0, e.getBbHeight() * 0.5, 0.0);
        Vec3 v = e.getDeltaMovement();
        double radial = v.dot(normal);
        if (inward ? radial > 0 : radial < 0) e.setDeltaMovement(v.subtract(normal.scale(radial)));
        if (e instanceof ServerPlayer sp) {
            // Клиент сам держит игрока у стены; сервер вмешивается, только если ушли заметно.
            if (depth > 0.8) sp.connection.teleport(feet.x, feet.y, feet.z, sp.getYRot(), sp.getXRot());
        } else {
            e.setPos(feet.x, feet.y, feet.z);
        }
    }

    // ------------------------------------------------------------------ обездвиживание

    private static void freeze(Domain d, LivingEntity e) {
        Stun s = STUNS.get(e.getUUID());
        if (s == null) {
            s = new Stun(e.getUUID(), d.level, d.ownerId);
            STUNS.put(e.getUUID(), s);
        }
        s.until = Long.MAX_VALUE;
        if (e instanceof Mob mob) {
            if (!mob.getPersistentData().contains(TAG_PREV_NOAI)) {
                mob.getPersistentData().putBoolean(TAG_PREV_NOAI, mob.isNoAi());
            }
            mob.getPersistentData().putLong(TAG_STUN_UNTIL, d.level.getGameTime() + d.remainingEstimate() + STUN_AFTER_TICKS + 40);
            mob.setNoAi(true);
            mob.getNavigation().stop();
            mob.setTarget(null);
        }
        if (e instanceof ServerPlayer sp) {
            sp.stopUsingItem();
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new StunPacket(d.ownerId, -1, d.remainingEstimate()));
        }
    }

    private static void unfreeze(Stun s, Entity e) {
        if (e instanceof Mob mob) {
            boolean prev = mob.getPersistentData().getBoolean(TAG_PREV_NOAI);
            mob.setNoAi(prev);
            mob.getPersistentData().remove(TAG_PREV_NOAI);
            mob.getPersistentData().remove(TAG_STUN_UNTIL);
        }
        if (e instanceof ServerPlayer sp) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new StunPacket(s.domainOwner, 0, 0));
        }
    }

    private static void tickStuns() {
        Iterator<Map.Entry<UUID, Stun>> it = STUNS.entrySet().iterator();
        while (it.hasNext()) {
            Stun s = it.next().getValue();
            long now = s.level.getGameTime();
            Entity e = s.level.getEntity(s.entityId);
            if (e == null) {
                // Игрок вышел из игры — состояние держим до конца срока; моб выгружен — восстановится при загрузке.
                if (now >= s.until) it.remove();
                continue;
            }
            if (!e.isAlive() || now >= s.until) {
                unfreeze(s, e);
                it.remove();
                continue;
            }
            if (e instanceof Mob mob) mobPhysics(mob);
        }
    }

    /** Без ИИ моб сам не двигается; падение на пол и отбрасывание от ударов считаем сами. */
    private static void mobPhysics(Mob mob) {
        Vec3 v = mob.getDeltaMovement();
        if (!mob.isNoGravity()) v = v.add(0.0, -0.08, 0.0);
        mob.setDeltaMovement(v);
        mob.move(MoverType.SELF, v);
        Vec3 after = mob.getDeltaMovement();
        double friction = mob.onGround() ? 0.546 : 0.91;
        double vy = mob.onGround() && after.y < 0.0 ? 0.0 : after.y * 0.98;
        mob.setDeltaMovement(after.x * friction, vy, after.z * friction);
    }

    // ------------------------------------------------------------------ защита

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide) return;
        // Владелец неуязвим, пока кастует.
        if (target instanceof Player p && isCasting(p)) {
            event.setCanceled(true);
            return;
        }
        if (DOMAINS.isEmpty()) return;
        DamageSource source = event.getSource();
        Vec3 from = source.getSourcePosition();
        if (from == null && source.getEntity() != null) from = source.getEntity().position();
        if (from != null && separated(target.level(), from, target.position().add(0.0, target.getBbHeight() * 0.5, 0.0))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && blocksActions(p)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer p && blocksActions(p)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && blocksActions(p)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && blocksActions(p)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer p && blocksActions(p)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseItem(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer p && blocksActions(p)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && blocksActions(p)) {
            event.setCanceled(true);
            return;
        }
        if (event.getLevel() instanceof Level level && insideRegion(level, event.getPos())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level && insideRegion(level, event.getPos())) event.setCanceled(true);
    }

    private static boolean insideRegion(Level level, BlockPos pos) {
        for (Domain d : DOMAINS.values()) {
            if (d.level == level && d.age >= T_FORM && !d.restored && d.containsBlock(pos, REMOVE_RADIUS + 0.5)) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Domain d = DOMAINS.get(p.getUUID());
            if (d != null) beginCollapse(d);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Domain d = DOMAINS.get(p.getUUID());
        if (d != null) {
            if (!d.ownerAbilitiesRestored) restoreOwnerAbilities(d, p);
            beginCollapse(d);
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        for (Domain d : DOMAINS.values()) {
            if (d.level == p.level()) sendStart(d, PacketDistributor.PLAYER.with(() -> p));
        }
        Stun s = STUNS.get(p.getUUID());
        if (s != null) {
            long now = s.level.getGameTime();
            int ticks = s.until == Long.MAX_VALUE ? -1 : (int) Math.max(1, s.until - now);
            Domain d = DOMAINS.get(s.domainOwner);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new StunPacket(s.domainOwner, ticks, d != null ? d.remainingEstimate() : 0));
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Domain own = DOMAINS.get(p.getUUID());
        if (own != null) beginCollapse(own);
        for (Domain d : DOMAINS.values()) {
            if (d.level == p.level()) sendStart(d, PacketDistributor.PLAYER.with(() -> p));
        }
    }

    /** Моб из выгруженного чанка: обездвиживание либо продолжается, либо уже кончилось. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (!mob.getPersistentData().contains(TAG_STUN_UNTIL)) return;
        if (STUNS.containsKey(mob.getUUID())) return;
        long until = mob.getPersistentData().getLong(TAG_STUN_UNTIL);
        ServerLevel level = (ServerLevel) event.getLevel();
        if (level.getGameTime() < until) {
            Stun s = new Stun(mob.getUUID(), level, new UUID(0L, 0L));
            s.until = until;
            STUNS.put(mob.getUUID(), s);
            mob.setNoAi(true);
        } else {
            mob.setNoAi(mob.getPersistentData().getBoolean(TAG_PREV_NOAI));
            mob.getPersistentData().remove(TAG_PREV_NOAI);
            mob.getPersistentData().remove(TAG_STUN_UNTIL);
        }
    }

    // ------------------------------------------------------------------ выключение / вылет сервера

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Domain d : DOMAINS.values()) {
            restoreStep(d, 0L);
            deleteSnapshot(event.getServer(), d.ownerId);
            ServerPlayer owner = event.getServer().getPlayerList().getPlayer(d.ownerId);
            if (owner != null) {
                if (!d.ownerAbilitiesRestored) restoreOwnerAbilities(d, owner);
                JujutsuNeonMod.setEnergy(owner, 0.0);
                owner.getPersistentData().putLong("jn_cd_domain_expansion", d.level.getGameTime() + COOLDOWN_TICKS);
            }
        }
        DOMAINS.clear();
        for (Stun s : STUNS.values()) {
            Entity e = s.level.getEntity(s.entityId);
            if (e != null) unfreeze(s, e);
        }
        STUNS.clear();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Path dir = snapshotDir(server);
        if (!Files.isDirectory(dir)) return;
        File[] files = dir.toFile().listFiles((f, name) -> name.endsWith(".dat"));
        if (files == null) return;
        for (File f : files) {
            try {
                CompoundTag root = NbtIo.readCompressed(f);
                recoverSnapshot(server, root);
            } catch (Exception ex) {
                LOGGER.error("Jujutsu Neon: не удалось восстановить территорию из {}", f, ex);
            }
            if (!f.delete()) f.deleteOnExit();
        }
    }

    private static Path snapshotDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("jujutsu_neon_domains");
    }

    private static void deleteSnapshot(MinecraftServer server, UUID owner) {
        try {
            Files.deleteIfExists(snapshotDir(server).resolve(owner + ".dat"));
        } catch (Exception ignored) {
        }
    }

    /** Снимок убранных блоков на диск — если сервер вылетит, мир восстановится при следующем запуске. */
    private static void writeSnapshot(Domain d) {
        d.snapshotWritten = true;
        CompoundTag root = new CompoundTag();
        root.putString("dim", d.level.dimension().location().toString());
        LongArrayList floor = d.floorPositions;
        root.put("floor", new LongArrayTag(floor.toLongArray()));
        root.put("pos", new LongArrayTag(d.savedPos.toLongArray()));
        ListTag palette = new ListTag();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] idx = new int[d.savedState.size()];
        for (int i = 0; i < idx.length; i++) {
            BlockState st = d.savedState.get(i);
            Integer k = index.get(st);
            if (k == null) {
                k = palette.size();
                index.put(st, k);
                palette.add(NbtUtils.writeBlockState(st));
            }
            idx[i] = k;
        }
        root.put("palette", palette);
        root.putIntArray("idx", idx);
        ListTag bes = new ListTag();
        for (Long2ObjectMap.Entry<CompoundTag> e : d.savedBlockEntities.long2ObjectEntrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("p", e.getLongKey());
            t.put("t", e.getValue());
            bes.add(t);
        }
        root.put("be", bes);
        ListTag ents = new ListTag();
        ents.addAll(d.hiddenEntities);
        root.put("ents", ents);

        // Пишем сразу (а не в фоне): иначе удаление файла в конце может обогнать запись,
        // и при следующем запуске старый снимок перезапишет мир.
        Path dir = snapshotDir(d.level.getServer());
        File file = dir.resolve(d.ownerId + ".dat").toFile();
        try {
            Files.createDirectories(dir);
            NbtIo.writeCompressed(root, file);
        } catch (Exception ex) {
            LOGGER.error("Jujutsu Neon: не удалось сохранить снимок территории", ex);
        }
    }

    private static void recoverSnapshot(MinecraftServer server, CompoundTag root) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(root.getString("dim")));
        ServerLevel level = server.getLevel(key);
        if (level == null) return;
        BlockState air = Blocks.AIR.defaultBlockState();
        MutableBlockPos pos = new MutableBlockPos();
        for (long p : root.getLongArray("floor")) {
            pos.set(p);
            level.setBlock(pos, air, BLOCK_FLAGS);
        }
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> states = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            states.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), palette.getCompound(i)));
        }
        long[] positions = root.getLongArray("pos");
        int[] idx = root.getIntArray("idx");
        for (int i = 0; i < positions.length && i < idx.length; i++) {
            pos.set(positions[i]);
            level.setBlock(pos, states.get(idx[i]), BLOCK_FLAGS);
        }
        ListTag bes = root.getList("be", Tag.TAG_COMPOUND);
        for (int i = 0; i < bes.size(); i++) {
            CompoundTag t = bes.getCompound(i);
            pos.set(t.getLong("p"));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                be.load(t.getCompound("t"));
                be.setChanged();
            }
        }
        ListTag ents = root.getList("ents", Tag.TAG_COMPOUND);
        for (int i = 0; i < ents.size(); i++) {
            Entity e = EntityType.loadEntityRecursive(ents.getCompound(i), level, ent -> ent);
            if (e != null) level.addFreshEntity(e);
        }
        LOGGER.info("Jujutsu Neon: мир внутри прерванной территории восстановлен ({} блоков)", positions.length);
    }
}
