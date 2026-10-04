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
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BucketItem;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
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
    /** 2 — клиентам, 16 — без обновления формы соседей, 32 — без дропа, 64 — без цепных реакций редстоуна. */
    private static final int BLOCK_FLAGS = 2 | 16 | 32 | 64;
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

        /** Рамки выделения нет: пол невидим и в прицел не попадает. */
        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return Shapes.empty();
        }

        /** Но стоять на нём можно — столкновение полным блоком. */
        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return Shapes.block();
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
        /** Чанки, просканированные при сохранении (только в них потом что-то трогаем). */
        final LongOpenHashSet scannedChunks = new LongOpenHashSet();
        int[] restoreOrder;
        int removeIndex;
        boolean removed;
        boolean prepared;

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
        UUID domainOwner;
        long until = Long.MAX_VALUE;

        Stun(UUID entityId, ServerLevel level, UUID domainOwner) {
            this.entityId = entityId;
            this.level = level;
            this.domainOwner = domainOwner;
        }
    }

    private static final Map<UUID, Stun> STUNS = new HashMap<>();
    /** Снимки, которые удалим после ближайшего сохранения мира. */
    private static final Map<ServerLevel, List<UUID>> PENDING_DELETE = new HashMap<>();
    private static final String TAG_STUN_UNTIL = "jn_dom_stun_until";
    private static final String TAG_PREV_NOAI = "jn_dom_prev_noai";
    /** Способности владельца до каста — на случай вылета сервера посреди катсцены. */
    private static final String TAG_OWNER_MAYFLY = "jn_dom_owner_mayfly";
    private static final String TAG_OWNER_FLYING = "jn_dom_owner_flying";

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

    /**
     * Подъём от ног на rise блоков вверх упрётся в стену территории: изнутри — выйдет за неё,
     * снаружи — зайдёт в неё.
     */
    static boolean riseHitsWall(Level level, Vec3 feet, double rise) {
        for (Domain d : DOMAINS.values()) {
            if (d.level != level || d.restored) continue;
            Vec3 low = feet.add(0.0, 1.0, 0.0);
            Vec3 high = feet.add(0.0, rise, 0.0);
            if (d.contains(low, RADIUS + 0.5)) {
                if (!d.contains(high, RADIUS - 1.5)) return true;
            } else {
                double y = Mth.clamp(d.center.y, low.y, high.y);
                if (d.contains(new Vec3(feet.x, y, feet.z), OUTER_RADIUS + 1.5)) return true;
            }
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

        // Всё, что владелец заряжал или держал, обрывается; полёт и рывок снимаются.
        JujutsuNeonMod.interruptForStun(player);
        JujutsuNeonMod.setEnergy(player, 0.0);

        // Пол — на высоте блока под ногами (в воздухе — прямо под ногами).
        // Стоя на земле (y = 64.0) — пол на блоке 63, его верх ровно под ногами.
        int floorY = Mth.floor(player.getY() + 1.0E-3) - 1;
        Domain d = new Domain(player, floorY);
        DOMAINS.put(d.ownerId, d);

        // Все живые в радиусе 40 пойманы с момента нажатия; игроки смотрят катсцену вместе с владельцем.
        captureInside(d, true);

        player.getPersistentData().putBoolean(TAG_OWNER_MAYFLY, d.ownerMayfly);
        player.getPersistentData().putBoolean(TAG_OWNER_FLYING, d.ownerFlying);
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

        if (d.prepared && !d.restored) enforceWalls(d);

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
        owner.getPersistentData().remove(TAG_OWNER_MAYFLY);
        owner.getPersistentData().remove(TAG_OWNER_FLYING);
        d.ownerAbilitiesRestored = true;
    }

    private static void beginCollapse(Domain d) {
        if (d.phase >= PHASE_COLLAPSE) return;
        d.phase = PHASE_COLLAPSE;
        d.collapseAge = d.age;
        // То, что попало внутрь за последние тики (например, вылитая лава), — не оставляем в мире.
        if (d.removed) cleanupStep(d);
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
        // Сначала сохраняем весь объём целиком, ничего не трогая: что бы ни случилось при удалении
        // (поршень снесёт основание, редстоун щёлкнет), всё вернётся из этой копии.
        scanAndSave(d);
        // Пол и снимок — в одном тике: на диск мир между ними не попадёт, а в снимке будут
        // и позиции пола (иначе после вылета невидимый пол остался бы в мире).
        placeFloor(d);
        writeSnapshot(d);

        // Кто оказался под полом (в пещере внутри сферы) — на пол.
        for (UUID id : d.captured) liftAboveFloor(d, d.level.getEntity(id));
        ServerPlayer owner = d.level.getServer().getPlayerList().getPlayer(d.ownerId);
        if (owner != null) liftAboveFloor(d, owner);
        d.prepared = true;
    }

    private static void scanAndSave(Domain d) {
        MutableBlockPos pos = new MutableBlockPos();
        LevelChunk chunk = null;
        int lastCX = Integer.MIN_VALUE, lastCZ = Integer.MIN_VALUE;
        for (int i = 0; i < d.columns.length / 2; i++) {
            int x = d.columns[i * 2], z = d.columns[i * 2 + 1];
            if ((x >> 4) != lastCX || (z >> 4) != lastCZ) {
                lastCX = x >> 4;
                lastCZ = z >> 4;
                chunk = d.level.getChunkSource().getChunkNow(lastCX, lastCZ);
                if (chunk != null) d.scannedChunks.add(ChunkPosKey(lastCX, lastCZ));
            }
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
                if (d.containsBlock(pos, REMOVE_RADIUS)) saveBlock(d, pos, chunk.getBlockState(pos));
                y--;
            }
        }
    }

    private static long ChunkPosKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static boolean scanned(Domain d, int x, int z) {
        return d.scannedChunks.contains(ChunkPosKey(x >> 4, z >> 4));
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
            // Летящие снаряды убираем; лежащие стрелы и трезубцы прячем вместе со всем.
            if (e instanceof Projectile && !(e instanceof AbstractArrow arrow && arrow.pickup == AbstractArrow.Pickup.ALLOWED)) {
                e.discard();
                continue;
            }
            if (e.isPassenger()) continue;
            e.ejectPassengers();
            CompoundTag tag = new CompoundTag();
            if (e.saveAsPassenger(tag)) d.hiddenEntities.add(tag);
            // Сундук/воронка в вагонетке или лодке: содержимое уже в копии — не вываливаем его.
            if (e instanceof Clearable c) c.clearContent();
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
            if (!d.containsBlock(pos, REMOVE_RADIUS) || !scanned(d, x, z)) continue;
            BlockState state = d.level.getBlockState(pos);
            if (state.hasBlockEntity()) Clearable.tryClear(d.level.getBlockEntity(pos));
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
            if (be != null) d.savedBlockEntities.put(key, be.saveWithFullMetadata());
        }
    }

    private static void removeStep(Domain d, long budget) {
        long deadline = System.nanoTime() + budget;
        BlockState air = Blocks.AIR.defaultBlockState();
        Block floorBlock = FLOOR.get();
        MutableBlockPos pos = new MutableBlockPos();
        int total = d.savedPos.size();
        // Порядок сохранения — колонками сверху вниз.
        while (d.removeIndex < total) {
            long key = d.savedPos.getLong(d.removeIndex++);
            if (d.floorSet.contains(key)) continue;
            pos.set(key);
            BlockState state = d.level.getBlockState(pos);
            if (!state.isAir() && !state.is(floorBlock)) {
                if (state.hasBlockEntity()) Clearable.tryClear(d.level.getBlockEntity(pos));
                d.level.setBlock(pos, air, BLOCK_FLAGS);
            }
            if ((d.removeIndex & 7) == 0 && System.nanoTime() > deadline) return;
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
            // Только там, где всё сохранено: иначе удалили бы блоки, которых нет в копии.
            if (!scanned(d, x, z)) continue;
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
        if (d.restoreOrder == null) {
            // Строго снизу вверх: песок и гравий ложатся на уже вернувшуюся опору, вода не уходит вниз.
            Integer[] order = new Integer[total];
            for (int i = 0; i < total; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(BlockPos.getY(d.savedPos.getLong(a)), BlockPos.getY(d.savedPos.getLong(b))));
            d.restoreOrder = new int[total];
            for (int i = 0; i < total; i++) d.restoreOrder[i] = order[i];
        }
        while (d.restoreIndex < total) {
            int i = d.restoreOrder[d.restoreIndex];
            long key = d.savedPos.getLong(i);
            pos.set(key);
            BlockState state = d.savedState.get(i);
            d.level.setBlock(pos, state, BLOCK_FLAGS);
            CompoundTag tag = d.savedBlockEntities.get(key);
            if (tag != null) restoreBlockEntity(d.level, pos.immutable(), state, tag);
            d.restoreIndex++;
            if ((d.restoreIndex & 15) == 0 && budget > 0 && System.nanoTime() > deadline) return;
        }
        restoreHiddenEntities(d);
        d.restored = true;
    }

    private static void restoreBlockEntity(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            be.load(tag);
            be.setChanged();
        } else {
            // Например, блок в движении у поршня — блок-сущность создаём сами.
            BlockEntity created = BlockEntity.loadStatic(pos, state, tag);
            if (created != null) level.setBlockEntity(created);
        }
        level.sendBlockUpdated(pos, state, state, 3);
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

        unstickAll(d);

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

        // Снимок удаляем только после того, как восстановленный мир сохранится на диск.
        PENDING_DELETE.computeIfAbsent(d.level, k -> new ArrayList<>()).add(d.ownerId);
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> d.level.dimension()), new EndPacket(d.ownerId));
        if (owner != null && owner.level() != d.level) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), new EndPacket(d.ownerId));
        }
    }

    /** Кого после возвращения блоков зажало — поднимаем на ближайшее свободное место сверху. */
    private static void unstickAll(Domain d) {
        AABB box = new AABB(d.center, d.center).inflate(REMOVE_RADIUS + 2.0);
        for (LivingEntity e : d.level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (!d.contains(e.position(), REMOVE_RADIUS + 2.0)) continue;
            unstick(e);
            e.fallDistance = 0.0f;
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
                double y = e.getY() + up;
                // Встаём ровно на верх блока под ногами, а не висим над ним.
                for (int k = 0; k < 8; k++) {
                    AABB lower = box.move(0.0, y - e.getY() - 0.125, 0.0);
                    if (!level.noCollision(e, lower)) break;
                    y -= 0.125;
                }
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
            // Картины и рамки не двигаются — их не трогаем.
            if (e instanceof HangingEntity) continue;
            Vec3 c = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
            Vec3 old = new Vec3(e.xo, e.yo + e.getBbHeight() * 0.5, e.zo);
            if (e instanceof Projectile) {
                if (d.contains(old, RADIUS + 0.5) != d.contains(c, RADIUS + 0.5)) {
                    if (e instanceof AbstractArrow) {
                        // Стрелы и трезубцы отскакивают от стены и падают на своей стороне.
                        e.setPos(old.x, old.y - e.getBbHeight() * 0.5, old.z);
                        e.setDeltaMovement(Vec3.ZERO);
                    } else {
                        e.discard();
                    }
                }
                continue;
            }
            boolean insider;
            if (e instanceof LivingEntity && !(e instanceof ArmorStand)) {
                insider = e.getUUID().equals(d.ownerId) || d.captured.contains(e.getUUID());
                // Появился внутри (призвали, заспавнили) — он тоже в территории. Быстро влетевших
                // снаружи не ловим: они были снаружи ещё тик назад.
                if (!insider && d.phase < PHASE_COLLAPSE && d.contains(c, RADIUS - 1.0) && d.contains(old, RADIUS - 1.0)) {
                    d.captured.add(e.getUUID());
                    freeze(d, (LivingEntity) e);
                    insider = true;
                }
            } else {
                insider = d.contains(c, RADIUS);
            }
            Vec3 off = c.subtract(d.center);
            double dist = off.length();
            double extent = Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5;
            if (insider) {
                double limit = RADIUS - extent - 0.05;
                if (dist > limit) {
                    Vec3 n = dist < 1.0E-4 ? new Vec3(0.0, 1.0, 0.0) : off.scale(1.0 / dist);
                    pushTo(e, d.center.add(n.scale(limit)), n, true, dist - limit);
                }
            } else {
                double limit = OUTER_RADIUS + extent + 0.05;
                if (dist < limit) {
                    // Снаружи выталкиваем по горизонтали (а не в землю под сферой).
                    double dy = off.y;
                    Vec3 target;
                    Vec3 n;
                    if (Math.abs(dy) < limit - 0.01) {
                        double hx = off.x, hz = off.z;
                        double hl = Math.sqrt(hx * hx + hz * hz);
                        if (hl < 1.0E-4) {
                            hx = 1.0;
                            hz = 0.0;
                            hl = 1.0;
                        }
                        double need = Math.sqrt(limit * limit - dy * dy);
                        n = new Vec3(hx / hl, 0.0, hz / hl);
                        target = new Vec3(d.center.x + n.x * need, c.y, d.center.z + n.z * need);
                    } else {
                        n = new Vec3(0.0, Math.signum(dy), 0.0);
                        target = d.center.add(0.0, Math.signum(dy) * limit, 0.0);
                    }
                    pushTo(e, target, n, false, target.distanceTo(c));
                }
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
        s.domainOwner = d.ownerId;
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
            JujutsuNeonMod.interruptForStun(sp);
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
            // Падение на пол внутри территории — без урона.
            e.fallDistance = 0.0f;
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

    /** Обездвиженные падают на пол без урона. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFall(LivingFallEvent event) {
        if (!event.getEntity().level().isClientSide && STUNS.containsKey(event.getEntity().getUUID())) {
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
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Domain d = DOMAINS.get(p.getUUID());
        if (d != null) beginCollapse(d);
        for (Domain o : DOMAINS.values()) {
            o.captured.remove(p.getUUID());
            o.watchers.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Domain d = DOMAINS.get(p.getUUID());
        if (d != null) {
            if (!d.ownerAbilitiesRestored) restoreOwnerAbilities(d, p);
            JujutsuNeonMod.setEnergy(p, 0.0);
            p.getPersistentData().putLong("jn_cd_domain_expansion", d.level.getGameTime() + COOLDOWN_TICKS);
            beginCollapse(d);
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        // Сервер вылетел посреди катсцены каста — возвращаем способности, как были до неё.
        if (p.getPersistentData().contains(TAG_OWNER_MAYFLY) && !DOMAINS.containsKey(p.getUUID())) {
            boolean mayfly = p.getPersistentData().getBoolean(TAG_OWNER_MAYFLY);
            boolean flying = p.getPersistentData().getBoolean(TAG_OWNER_FLYING);
            p.getPersistentData().remove(TAG_OWNER_MAYFLY);
            p.getPersistentData().remove(TAG_OWNER_FLYING);
            if (!p.isCreative() && !p.isSpectator()) {
                p.getAbilities().mayfly = mayfly;
                p.getAbilities().flying = mayfly && flying;
                p.onUpdateAbilities();
            }
        }
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
            unstickAll(d);
            PENDING_DELETE.computeIfAbsent(d.level, k -> new ArrayList<>()).add(d.ownerId);
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

    /** Мир сохранён после остановки — снимки больше не нужны. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (Map.Entry<ServerLevel, List<UUID>> e : PENDING_DELETE.entrySet()) {
            for (UUID id : e.getValue()) deleteSnapshot(event.getServer(), id);
        }
        PENDING_DELETE.clear();
    }

    /** Восстановленный мир сохранился — снимок можно удалить. */
    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<UUID> ids = PENDING_DELETE.remove(level);
        if (ids == null) return;
        for (UUID id : ids) deleteSnapshot(level.getServer(), id);
    }

    /** Пока блоки убираются и возвращаются, ничего не выпадает (печи, поршни, модовые сундуки). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDropJoin(EntityJoinLevelEvent event) {
        if (DOMAINS.isEmpty() || event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity) && !(event.getEntity() instanceof ExperienceOrb)) return;
        for (Domain d : DOMAINS.values()) {
            if (d.level != event.getLevel() || !d.prepared || d.restored) continue;
            boolean busy = !d.removed || d.phase == PHASE_COLLAPSE;
            if (busy && d.contains(event.getEntity().position(), REMOVE_RADIUS + 1.5)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** Взрыв не выходит за стену и не трогает территорию. */
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (DOMAINS.isEmpty()) return;
        Level level = event.getLevel();
        Vec3 c = event.getExplosion().getPosition();
        event.getAffectedBlocks().removeIf(pos -> insideRegion(level, pos) || separated(level, c, Vec3.atCenterOf(pos)));
        event.getAffectedEntities().removeIf(e -> separated(level, c, e.position().add(0.0, e.getBbHeight() * 0.5, 0.0)));
    }

    /** Ведро внутри территории не выльешь: внутри нет блоков. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBucket(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide() || !(event.getItemStack().getItem() instanceof BucketItem)) return;
        if (isInsideAny(event.getLevel(), event.getEntity().position())) event.setCanceled(true);
    }

    /** Головастик стал лягушкой и т.п. — обездвиживание переходит к новому мобу. */
    @SubscribeEvent
    public static void onConversion(LivingConversionEvent.Post event) {
        if (!(event.getEntity() instanceof Mob from) || !(event.getOutcome() instanceof Mob to)) return;
        if (!from.getPersistentData().contains(TAG_STUN_UNTIL)) return;
        to.getPersistentData().putLong(TAG_STUN_UNTIL, from.getPersistentData().getLong(TAG_STUN_UNTIL));
        to.getPersistentData().putBoolean(TAG_PREV_NOAI, from.getPersistentData().getBoolean(TAG_PREV_NOAI));
        Stun old = STUNS.remove(from.getUUID());
        if (old != null && to.level() instanceof ServerLevel sl) {
            Stun s = new Stun(to.getUUID(), sl, old.domainOwner);
            s.until = old.until;
            STUNS.put(to.getUUID(), s);
            to.setNoAi(true);
        }
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
                ServerLevel level = recoverSnapshot(server, root);
                if (level != null) level.save(null, true, false);
                if (!f.delete()) f.deleteOnExit();
            } catch (Exception ex) {
                LOGGER.error("Jujutsu Neon: не удалось восстановить территорию из {}", f, ex);
                if (!f.renameTo(new File(f.getPath() + ".failed"))) f.deleteOnExit();
            }
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

    private static ServerLevel recoverSnapshot(MinecraftServer server, CompoundTag root) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(root.getString("dim")));
        ServerLevel level = server.getLevel(key);
        if (level == null) return null;
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
        int n = Math.min(positions.length, idx.length);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(BlockPos.getY(positions[a]), BlockPos.getY(positions[b])));
        for (int k = 0; k < n; k++) {
            int i = order[k];
            pos.set(positions[i]);
            level.setBlock(pos, states.get(idx[i]), BLOCK_FLAGS);
        }
        ListTag bes = root.getList("be", Tag.TAG_COMPOUND);
        for (int i = 0; i < bes.size(); i++) {
            CompoundTag t = bes.getCompound(i);
            BlockPos bp = BlockPos.of(t.getLong("p"));
            restoreBlockEntity(level, bp, level.getBlockState(bp), t.getCompound("t"));
        }
        ListTag ents = root.getList("ents", Tag.TAG_COMPOUND);
        for (int i = 0; i < ents.size(); i++) {
            Entity e = EntityType.loadEntityRecursive(ents.getCompound(i), level, ent -> ent);
            if (e != null) level.addFreshEntity(e);
        }
        LOGGER.info("Jujutsu Neon: мир внутри прерванной территории восстановлен ({} блоков)", positions.length);
        return level;
    }
}
