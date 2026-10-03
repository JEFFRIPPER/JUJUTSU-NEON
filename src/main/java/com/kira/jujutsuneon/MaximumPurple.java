package com.kira.jujutsuneon;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Максимальный Фиолетовый (удержание G 3 секунды).
 *
 * Сервер: заморозка владельца на время кат-сцены, урон 2000 (1000 сердец) всем живым
 * в радиусе 100 блоков, кроме владельца, и идеально круглый кратер:
 * сверху — шар радиуса 100 вокруг игрока, снизу — чаша глубиной 50
 * (сферический сегмент шара R=125 с центром на 75 блоков выше ног).
 * Блоки удаляются без дропа во время белой засветки, затем владелец
 * оказывается на дне кратера.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaximumPurple {

    // ---- Таймлайн кат-сцены в тиках (общий для сервера и клиента) ----
    static final int T_LETTERBOX = 34;
    static final int T_ORBS = 70;
    static final int T_MERGE = 102;
    static final int T_IMPACT = 114;
    static final int T_COSMOS = 122;
    static final int T_WHITE_IN = 134;
    static final int T_WHITE_FULL = 142;
    static final int T_MIN_END = 160;
    static final int ANIM_TICKS = 150;

    static final double RADIUS = 100.0;
    static final int DEPTH = 50;
    /** Чаша: шар радиуса BOWL_R с центром на BOWL_CENTER выше ног. На r=0 глубина 50, на r=100 — 0. */
    private static final double BOWL_R = 125.0;
    private static final double BOWL_CENTER = 75.0;

    private static final float DAMAGE = 2000.0f;
    private static final long COOLDOWN_TICKS = 1200L;
    private static final double ENERGY_COST = 100.0;
    private static final long BUDGET_NANOS = 32_000_000L;
    private static final int BLOCK_FLAGS = 2 | 16 | 32;

    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JujutsuNeonMod.MODID, "max_purple"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static final Map<UUID, Cast> CASTS = new HashMap<>();

    static {
        int id = 0;
        CHANNEL.registerMessage(id++, RequestPacket.class, RequestPacket::encode, RequestPacket::decode, RequestPacket::handle);
        CHANNEL.registerMessage(id++, StartPacket.class, StartPacket::encode, StartPacket::decode, StartPacket::handle);
        CHANNEL.registerMessage(id++, EndPacket.class, EndPacket::encode, EndPacket::decode, EndPacket::handle);
    }

    private MaximumPurple() {
    }

    // ------------------------------------------------------------------ packets

    /** C2S: G удерживали 3 секунды. */
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

    /** S2C: кат-сцена началась. */
    public record StartPacket(UUID ownerId, double x, double y, double z, float yaw) {
        static void encode(StartPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.yaw);
        }

        static StartPacket decode(FriendlyByteBuf buf) {
            return new StartPacket(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat());
        }

        static void handle(StartPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> MaximumPurpleClient.onStart(msg.ownerId, new Vec3(msg.x, msg.y, msg.z), msg.yaw)));
            context.setPacketHandled(true);
        }
    }

    /** S2C: кратер готов (или техника прервана) — снимаем белый экран. */
    public record EndPacket(UUID ownerId) {
        static void encode(EndPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
        }

        static EndPacket decode(FriendlyByteBuf buf) {
            return new EndPacket(buf.readUUID());
        }

        static void handle(EndPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> MaximumPurpleClient.onEnd(msg.ownerId)));
            context.setPacketHandled(true);
        }
    }

    /** Клиент → сервер. */
    static void requestStart() {
        CHANNEL.sendToServer(new RequestPacket());
    }

    // ------------------------------------------------------------------ state

    private static final class Cast {
        final UUID ownerId;
        final ServerLevel level;
        final Vec3 feet;
        final BlockPos feetBlock;
        final float yaw;
        int age;
        boolean damageDone;
        boolean ownerMissing;

        /** Колонки кратера, отсортированные по чанкам: пары (x, z). */
        int[] columns;
        int columnIndex;
        boolean destructionDone;

        Cast(ServerPlayer owner) {
            this.ownerId = owner.getUUID();
            this.level = owner.serverLevel();
            this.feet = owner.position();
            this.feetBlock = owner.blockPosition();
            this.yaw = owner.getYRot();
        }
    }

    static boolean isActive(ServerPlayer player) {
        return CASTS.containsKey(player.getUUID());
    }

    // ------------------------------------------------------------------ start

    private static void tryStart(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return;
        if (CASTS.containsKey(player.getUUID())) return;

        if (!JujutsuNeonMod.hasGojoBlindfold(player)) {
            JujutsuNeonMod.requireBlindfoldMessage(player);
            return;
        }
        if (JujutsuNeonMod.isHollowPurpleCasting(player) || JujutsuNeonMod.isMaximumBlueActive(player)) {
            player.displayClientMessage(Component.literal("Другая техника ещё активна").withStyle(ChatFormatting.GRAY), true);
            return;
        }

        long now = player.serverLevel().getGameTime();
        long cooldownUntil = player.getPersistentData().getLong("jn_cd_max_purple");
        if (now < cooldownUntil) {
            double seconds = Math.ceil((cooldownUntil - now) / 2.0) / 10.0;
            player.displayClientMessage(
                    Component.literal("Максимальный Фиолетовый: перезарядка " + seconds + " сек.").withStyle(ChatFormatting.GRAY),
                    true);
            return;
        }

        if (JujutsuNeonMod.getEnergy(player) + 1.0E-6 < ENERGY_COST) {
            player.displayClientMessage(
                    Component.literal("Недостаточно проклятой энергии: нужно 100%").withStyle(ChatFormatting.AQUA),
                    true);
            return;
        }

        JujutsuNeonMod.setEnergy(player, JujutsuNeonMod.getEnergy(player) - ENERGY_COST);
        player.getPersistentData().putLong("jn_cd_max_purple", now + COOLDOWN_TICKS);

        Cast cast = new Cast(player);
        CASTS.put(player.getUUID(), cast);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0f;

        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> cast.level.dimension()),
                new StartPacket(cast.ownerId, cast.feet.x, cast.feet.y, cast.feet.z, cast.yaw));
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CASTS.isEmpty()) return;

        Iterator<Map.Entry<UUID, Cast>> it = CASTS.entrySet().iterator();
        while (it.hasNext()) {
            Cast cast = it.next().getValue();
            ServerPlayer owner = cast.level.getServer().getPlayerList().getPlayer(cast.ownerId);
            boolean ownerHere = owner != null && owner.isAlive() && owner.level() == cast.level;

            // До удара техника без владельца не существует.
            if (!ownerHere && !cast.damageDone) {
                finish(cast, owner, false);
                it.remove();
                continue;
            }

            if (ownerHere) holdOwner(cast, owner);
            playCues(cast);

            if (cast.age == MaximumPurple.T_IMPACT && ownerHere) {
                dealDamage(cast, owner);
                cast.damageDone = true;
            }

            if (cast.age >= T_WHITE_FULL && !cast.destructionDone) {
                if (cast.columns == null) cast.columns = buildColumns(cast);
                carve(cast);
            }

            if (cast.destructionDone && cast.age >= T_MIN_END) {
                finish(cast, ownerHere ? owner : null, true);
                it.remove();
                continue;
            }

            cast.age++;
        }
    }

    private static void holdOwner(Cast cast, ServerPlayer owner) {
        owner.fallDistance = 0.0f;
        owner.setDeltaMovement(Vec3.ZERO);
        if (owner.position().distanceToSqr(cast.feet) > 0.0025) {
            owner.connection.teleport(cast.feet.x, cast.feet.y, cast.feet.z, cast.yaw, 0.0f);
        }
    }

    private static void playCues(Cast cast) {
        ServerLevel level = cast.level;
        double x = cast.feet.x, y = cast.feet.y + 1.0, z = cast.feet.z;
        switch (cast.age) {
            case 4 -> level.playSound(null, x, y, z, JujutsuNeonMod.SFX_RED.get(), SoundSource.PLAYERS, 3.0f, 0.55f);
            case 46 -> level.playSound(null, x, y, z, JujutsuNeonMod.SFX_RED.get(), SoundSource.PLAYERS, 4.0f, 0.8f);
            case T_ORBS -> {
                level.playSound(null, x, y, z, JujutsuNeonMod.SFX_RED.get(), SoundSource.PLAYERS, 4.0f, 0.7f);
                level.playSound(null, x, y, z, JujutsuNeonMod.SFX_BLUE.get(), SoundSource.PLAYERS, 4.0f, 0.7f);
            }
            case T_MERGE -> level.playSound(null, x, y, z, JujutsuNeonMod.SFX_PURPLE.get(), SoundSource.PLAYERS, 5.0f, 0.75f);
            case T_IMPACT -> {
                level.playSound(null, x, y, z, JujutsuNeonMod.SFX_PURPLE.get(), SoundSource.PLAYERS, 16.0f, 0.5f);
                level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 16.0f, 0.6f);
            }
            case T_COSMOS -> {
                level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 16.0f, 0.45f);
                level.playSound(null, x, y, z, JujutsuNeonMod.SFX_DOMAIN.get(), SoundSource.PLAYERS, 12.0f, 0.6f);
            }
            case T_WHITE_FULL -> {
                level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 16.0f, 0.3f);
                level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 16.0f, 0.4f);
            }
            default -> {
            }
        }
    }

    private static void dealDamage(Cast cast, ServerPlayer owner) {
        Vec3 center = cast.feet.add(0.0, 1.0, 0.0);
        AABB box = new AABB(center, center).inflate(RADIUS);
        List<LivingEntity> targets = cast.level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != owner && !e.isSpectator());

        owner.getPersistentData().putBoolean("jn_purple_custom_damage", true);
        owner.getPersistentData().putBoolean("jn_max_purple_damage", true);
        try {
            for (LivingEntity target : targets) {
                Vec3 c = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
                if (c.distanceToSqr(center) > RADIUS * RADIUS) continue;
                target.invulnerableTime = 0;
                target.hurt(cast.level.damageSources().playerAttack(owner), DAMAGE);
            }
        } finally {
            owner.getPersistentData().putBoolean("jn_purple_custom_damage", false);
            owner.getPersistentData().putBoolean("jn_max_purple_damage", false);
        }
    }

    // ------------------------------------------------------------------ crater

    private static int[] buildColumns(Cast cast) {
        int cx = cast.feetBlock.getX();
        int cz = cast.feetBlock.getZ();
        int r = (int) RADIUS;
        List<long[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = cx + dx, z = cz + dz;
                long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
                list.add(new long[]{chunkKey, x, z});
            }
        }
        list.sort((a, b) -> Long.compare(a[0], b[0]));
        int[] out = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            out[i * 2] = (int) list.get(i)[1];
            out[i * 2 + 1] = (int) list.get(i)[2];
        }
        return out;
    }

    private static void carve(Cast cast) {
        ServerLevel level = cast.level;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int cx = cast.feetBlock.getX();
        int cy = cast.feetBlock.getY();
        int cz = cast.feetBlock.getZ();
        int minY = level.getMinBuildHeight() + 1; // нижний слой мира не трогаем — под ним пустота
        int maxY = level.getMaxBuildHeight() - 1;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        int total = cast.columns.length / 2;

        while (cast.columnIndex < total) {
            int x = cast.columns[cast.columnIndex * 2];
            int z = cast.columns[cast.columnIndex * 2 + 1];
            int chunkX = x >> 4, chunkZ = z >> 4;
            if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
            }

            if (chunk != null) {
                int dx = x - cx, dz = z - cz;
                double r2 = dx * dx + dz * dz;
                int top = cy + (int) Math.floor(Math.sqrt(Math.max(0.0, RADIUS * RADIUS - r2)));
                int bottom = cy + (int) Math.ceil(BOWL_CENTER - Math.sqrt(Math.max(0.0, BOWL_R * BOWL_R - r2)));
                bottom = Math.max(bottom, cy - DEPTH);
                top = Math.min(top, maxY);
                bottom = Math.max(bottom, minY);

                int y = top;
                while (y >= bottom) {
                    int sectionIndex = chunk.getSectionIndex(y);
                    LevelChunkSection section = sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()
                            ? chunk.getSection(sectionIndex) : null;
                    int sectionBottom = (y >> 4) << 4;
                    if (section == null || section.hasOnlyAir()) {
                        y = sectionBottom - 1;
                        continue;
                    }
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isAir() && !isProtected(state)) {
                        if (state.hasBlockEntity()) Clearable.tryClear(level.getBlockEntity(pos));
                        level.setBlock(pos, air, BLOCK_FLAGS);
                    }
                    y--;
                }
            }

            cast.columnIndex++;
            if ((cast.columnIndex & 3) == 0 && System.nanoTime() > deadline) return;
        }
        cast.destructionDone = true;
    }

    private static boolean isProtected(BlockState state) {
        return state.is(Blocks.END_PORTAL) ||
                state.is(Blocks.END_PORTAL_FRAME) ||
                state.is(Blocks.NETHER_PORTAL) ||
                state.is(Blocks.END_GATEWAY) ||
                state.is(Blocks.COMMAND_BLOCK) ||
                state.is(Blocks.CHAIN_COMMAND_BLOCK) ||
                state.is(Blocks.REPEATING_COMMAND_BLOCK) ||
                state.is(Blocks.STRUCTURE_BLOCK) ||
                state.is(Blocks.STRUCTURE_VOID) ||
                state.is(Blocks.JIGSAW) ||
                state.is(Blocks.BARRIER) ||
                state.is(Blocks.LIGHT) ||
                state.is(Blocks.MOVING_PISTON);
    }

    // ------------------------------------------------------------------ end

    private static void finish(Cast cast, ServerPlayer owner, boolean completed) {
        if (owner != null && completed) {
            int bottomY = Math.max(cast.feetBlock.getY() - DEPTH, cast.level.getMinBuildHeight() + 1);
            owner.fallDistance = 0.0f;
            owner.setDeltaMovement(Vec3.ZERO);
            owner.connection.teleport(cast.feet.x, bottomY, cast.feet.z, cast.yaw, 0.0f);
            // Откат 60 секунд отсчитывается от окончания техники.
            owner.getPersistentData().putLong("jn_cd_max_purple", cast.level.getGameTime() + COOLDOWN_TICKS);
        }
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> cast.level.dimension()), new EndPacket(cast.ownerId));
    }

    // ------------------------------------------------------------------ guards

    /** Владелец во время кат-сцены неуязвим; урон Максимального Фиолетового проходит сквозь Бесконечность. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CASTS.containsKey(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    /** Никакого дропа из кратера, пока он вырезается. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDropSpawn(EntityJoinLevelEvent event) {
        if (CASTS.isEmpty() || event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity) && !(event.getEntity() instanceof ExperienceOrb)) return;
        for (Cast cast : CASTS.values()) {
            if (cast.columns == null || cast.destructionDone) continue;
            if (cast.level != event.getLevel()) continue;
            if (event.getEntity().position().distanceToSqr(cast.feet) > (RADIUS + 12.0) * (RADIUS + 12.0)) continue;
            event.setCanceled(true);
            return;
        }
    }
}
