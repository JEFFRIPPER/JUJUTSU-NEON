package com.kira.jujutsuneon;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
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
 * Кат-сцена ровно 20,4 секунды (408 тиков) под свой саундтрек (24,2 с, синхронно с картинкой):
 * Синий в руке, вихрь и бросок ввысь на 25 блоков; Красный в ладони у лица и выстрел вверх;
 * шары гоняются друг за другом вокруг фиолетовой молнии, игрок поднимается к ним на 24 блока;
 * темнота, слияние в один фиолетовый шар, поза Годжо и взрыв. После белого экрана игрок
 * остаётся в воздухе на той же точке в режиме полёта.
 *
 * Сервер: удерживает владельца на траектории кат-сцены, наносит 2000 урона (1000 сердец)
 * всем живым в радиусе 100 блоков от игрока, кроме владельца, и вырезает идеально
 * круглый кратер без дропа:
 *  - выше земли — шар радиусом 100 вокруг точки старта;
 *  - ниже — чаша глубиной 74 в центре (50 + высота подъёма 24), сходящая на нет к радиусу 100.
 * Подземная часть вырезается заранее, пока идёт кат-сцена, под нетронутой «коркой»
 * в 2 блока, поэтому её не видно. Корка и всё, что над землёй, удаляются во время
 * белого экрана.
 */
@Mod.EventBusSubscriber(modid = JujutsuNeonMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaximumPurple {

    // ---- Таймлайн кат-сцены в тиках (1 тик = 0,05 с звука; общий для сервера и клиента) ----
    static final int T_BLUE_SPAWN = 14;     // 0,70 с — Синий вспыхивает в руке
    static final int T_VORTEX = 16;         // 0,80 с — присед, вихрь
    static final int T_BLUE_THROW = 40;     // 2,00 с — бросок
    static final int T_CALM = 56;           // 2,80 с — спокойный план со спины
    static final int T_RED_GLOW = 58;       // 2,90 с — Красный загорается в ладони
    static final int T_MANGA_A = 66;        // 3,30 с — манга-кадр
    static final int T_RED_FIRE = 69;       // 3,45 с — выстрел Красного вверх
    static final int T_WIDE = 73;           // 3,65 с — общий план, Красный поднимается
    static final int T_SKY = 100;           // 5,00 с — небо: Синий дугой, Красный висит
    static final int T_APPROACH = 128;      // 6,40 с — Синий подлетает к Красному
    static final int T_CHASE = 160;         // 8,00 с — погоня вокруг ядра
    static final int T_RING = 228;          // 11,40 с — кольцо, игрок поднимается
    static final int T_RING_OPEN = 266;     // 13,30 с — кольцо раскрывается
    static final int T_DARK = 283;          // 14,15 с — свет гаснет
    static final int T_BLACK_END = 304;     // 15,20 с — ровно секунда темноты
    static final int T_SPACE = 305;         // 15,25 с — «космос», шары сходятся
    static final int T_INSERTS = 332;       // 16,60 с — четыре аниме-вставки
    static final int T_MERGED = 336;        // 16,80 с — один большой фиолетовый шар
    static final int T_SWIRL = 338;         // 16,90 с — белый закрученный взрыв
    static final int T_BEHIND = 350;        // 17,50 с — камера за головой, огромная сфера
    static final int T_POSE = 360;          // 18,00 с — поза Годжо
    static final int T_EXPLODE = 362;       // 18,10 с — главный удар
    static final int T_MANGA_C = 365;       // 18,25 с — манга-кадр с рукой, потом «X»
    static final int T_WHITE_IN = 369;      // 18,45 с — белый экран
    static final int T_WHITE_FULL = 371;
    static final int T_MIN_END = 408;       // 20,40 с — белый уходит
    static final int ANIM_TICKS = 408;

    /** Подъём игрока к шарам. */
    static final int T_RISE_START = 228;
    static final int T_RISE_END = 282;
    static final double RISE_HEIGHT = 24.0;
    static final double RADIUS = 100.0;
    /** 50 + высота подъёма: кратер углубляется на столько, на сколько игрок поднялся. */
    static final int DEPTH = 50 + (int) RISE_HEIGHT;
    /** Чаша: шар радиуса BOWL_R с центром на BOWL_CENTER выше точки старта. На r=0 глубина DEPTH, на r=100 — 0. */
    private static final double BOWL_CENTER = (RADIUS * RADIUS - DEPTH * DEPTH) / (2.0 * DEPTH);
    private static final double BOWL_R = DEPTH + BOWL_CENTER;
    /** Сколько верхних блоков колонки (и её соседей) остаётся «коркой» до белого экрана. */
    private static final int CRUST = 2;
    private static final int T_HIDDEN_CARVE = 20;

    private static final float DAMAGE = 2000.0f;
    private static final long COOLDOWN_TICKS = 1200L;
    private static final double ENERGY_COST = 100.0;
    private static final long BUDGET_HIDDEN_NANOS = 22_000_000L;
    private static final long BUDGET_WHITE_NANOS = 38_000_000L;
    private static final int BLOCK_FLAGS = 2 | 16 | 32;

    private static final String PROTOCOL = "3";
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

    /** Высота игрока над точкой старта в момент t (тики кат-сцены): плавный подъём к шарам. */
    static double heightAt(double t) {
        if (t <= T_RISE_START) return 0.0;
        if (t >= T_RISE_END) return RISE_HEIGHT;
        double x = (t - T_RISE_START) / (double) (T_RISE_END - T_RISE_START);
        return RISE_HEIGHT * x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
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

    /** S2C: кратер готов (completed) или техника прервана — снимаем белый экран. */
    public record EndPacket(UUID ownerId, boolean completed) {
        static void encode(EndPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
            buf.writeBoolean(msg.completed);
        }

        static EndPacket decode(FriendlyByteBuf buf) {
            return new EndPacket(buf.readUUID(), buf.readBoolean());
        }

        static void handle(EndPacket msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> MaximumPurpleClient.onEnd(msg.ownerId, msg.completed)));
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
        final boolean origMayfly;
        final boolean origFlying;
        int age;
        boolean damageDone;

        /** Колонки кратера, отсортированные по чанкам: пары (x, z). */
        int[] columns;
        /** Для каждой колонки — верхняя граница скрытой вырезки (ниже корки). */
        int[] hiddenTop;
        int hiddenIndex;
        int finalIndex;
        boolean hiddenDone;
        boolean destructionDone;

        Cast(ServerPlayer owner) {
            this.ownerId = owner.getUUID();
            this.level = owner.serverLevel();
            this.feet = owner.position();
            this.feetBlock = owner.blockPosition();
            this.yaw = owner.getYRot();
            this.origMayfly = owner.getAbilities().mayfly;
            this.origFlying = owner.getAbilities().flying;
        }

        Vec3 expectedPos() {
            return feet.add(0.0, heightAt(age), 0.0);
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

        // Если игрок был в нашем полёте — снимаем его, техника сама управляет высотой.
        JujutsuNeonFlightPatch.stopForTechnique(player);

        JujutsuNeonMod.setEnergy(player, JujutsuNeonMod.getEnergy(player) - ENERGY_COST);
        player.getPersistentData().putLong("jn_cd_max_purple", now + COOLDOWN_TICKS);

        Cast cast = new Cast(player);
        CASTS.put(player.getUUID(), cast);
        player.fallDistance = 0.0f;

        // Сначала Start (клиент запомнит свои способности), потом разрешаем висеть в воздухе,
        // чтобы сервер не кикнул за «полёт» в воздухе.
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> cast.level.dimension()),
                new StartPacket(cast.ownerId, cast.feet.x, cast.feet.y, cast.feet.z, cast.yaw));
        player.getAbilities().mayfly = true;
        player.onUpdateAbilities();
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

            // До взрыва техника без владельца не существует.
            if (!ownerHere && !cast.damageDone) {
                finish(cast, owner, false);
                it.remove();
                continue;
            }

            if (ownerHere) holdOwner(cast, owner);

            if (cast.age == T_EXPLODE && ownerHere) {
                dealDamage(cast, owner);
                cast.damageDone = true;
            }

            if (cast.age >= T_HIDDEN_CARVE && !cast.destructionDone) {
                if (cast.columns == null) buildColumns(cast);
                if (cast.age < T_WHITE_FULL) {
                    if (!cast.hiddenDone) carveHidden(cast);
                } else {
                    carveFinal(cast);
                }
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
        Vec3 expected = cast.expectedPos();
        Vec3 pos = owner.position();
        // Клиент сам ведёт игрока по своим часам (синхронно с музыкой); сервер поправляет только
        // явный уход. По высоте допускаем расхождение часов клиента и сервера до 12 тиков.
        double dx = pos.x - expected.x, dz = pos.z - expected.z;
        double minY = cast.feet.y + heightAt(cast.age - 12) - 1.2;
        double maxY = cast.feet.y + heightAt(cast.age + 12) + 1.2;
        if (dx * dx + dz * dz > 1.2 * 1.2 || pos.y < minY || pos.y > maxY) {
            owner.connection.teleport(expected.x, expected.y, expected.z, cast.yaw, 0.0f);
        }
    }

    private static void dealDamage(Cast cast, ServerPlayer owner) {
        Vec3 center = owner.position().add(0.0, 1.0, 0.0);
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

    private static int columnTop(Cast cast, int r2) {
        int top = cast.feetBlock.getY() + (int) Math.floor(Math.sqrt(Math.max(0.0, RADIUS * RADIUS - r2)));
        return Math.min(top, cast.level.getMaxBuildHeight() - 1);
    }

    private static int columnBottom(Cast cast, int r2) {
        int cy = cast.feetBlock.getY();
        int bottom = cy + (int) Math.ceil(BOWL_CENTER - Math.sqrt(Math.max(0.0, BOWL_R * BOWL_R - r2)));
        bottom = Math.max(bottom, cy - DEPTH);
        // нижний слой мира не трогаем — под ним пустота
        return Math.max(bottom, cast.level.getMinBuildHeight() + 1);
    }

    private static void buildColumns(Cast cast) {
        ServerLevel level = cast.level;
        int cx = cast.feetBlock.getX();
        int cz = cast.feetBlock.getZ();
        int r = (int) RADIUS;

        // Карта поверхности (высший непустой блок) с запасом в 1 колонку для соседей.
        int size = 2 * r + 3;
        int[] surface = new int[size * size];
        LevelChunk chunk = null;
        int lastCX = Integer.MIN_VALUE, lastCZ = Integer.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int x = cx - r - 1 + i, z = cz - r - 1 + j;
                if ((x >> 4) != lastCX || (z >> 4) != lastCZ) {
                    lastCX = x >> 4;
                    lastCZ = z >> 4;
                    chunk = level.getChunkSource().getChunkNow(lastCX, lastCZ);
                }
                surface[i * size + j] = chunk == null
                        ? Integer.MIN_VALUE / 2
                        : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
            }
        }

        List<long[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = cx + dx, z = cz + dz;
                int i = dx + r + 1, j = dz + r + 1;
                int minSurface = Integer.MAX_VALUE;
                for (int a = -1; a <= 1; a++) {
                    for (int b = -1; b <= 1; b++) {
                        minSurface = Math.min(minSurface, surface[(i + a) * size + (j + b)]);
                    }
                }
                long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
                list.add(new long[]{chunkKey, x, z, (long) minSurface - CRUST});
            }
        }
        list.sort((a, b) -> Long.compare(a[0], b[0]));

        cast.columns = new int[list.size() * 2];
        cast.hiddenTop = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            long[] e = list.get(i);
            cast.columns[i * 2] = (int) e[1];
            cast.columns[i * 2 + 1] = (int) e[2];
            cast.hiddenTop[i] = (int) Math.max(Integer.MIN_VALUE / 2, e[3]);
        }
    }

    /** Подземная часть под коркой — пока идёт кат-сцена, её не видно. */
    private static void carveHidden(Cast cast) {
        long deadline = System.nanoTime() + BUDGET_HIDDEN_NANOS;
        int total = cast.columns.length / 2;
        Carver carver = new Carver(cast);
        while (cast.hiddenIndex < total) {
            int i = cast.hiddenIndex;
            int x = cast.columns[i * 2], z = cast.columns[i * 2 + 1];
            int dx = x - cast.feetBlock.getX(), dz = z - cast.feetBlock.getZ();
            int r2 = dx * dx + dz * dz;
            int top = Math.min(columnTop(cast, r2), cast.hiddenTop[i]);
            carver.column(x, z, columnBottom(cast, r2), top);
            cast.hiddenIndex++;
            if ((cast.hiddenIndex & 3) == 0 && System.nanoTime() > deadline) return;
        }
        cast.hiddenDone = true;
    }

    /** Остаток (корка и всё над землёй) — во время белого экрана. */
    private static void carveFinal(Cast cast) {
        long deadline = System.nanoTime() + BUDGET_WHITE_NANOS;
        int total = cast.columns.length / 2;
        Carver carver = new Carver(cast);
        while (cast.finalIndex < total) {
            int i = cast.finalIndex;
            int x = cast.columns[i * 2], z = cast.columns[i * 2 + 1];
            int dx = x - cast.feetBlock.getX(), dz = z - cast.feetBlock.getZ();
            int r2 = dx * dx + dz * dz;
            int bottom = columnBottom(cast, r2);
            if (i < cast.hiddenIndex) bottom = Math.max(bottom, cast.hiddenTop[i] + 1);
            carver.column(x, z, bottom, columnTop(cast, r2));
            cast.finalIndex++;
            if ((cast.finalIndex & 3) == 0 && System.nanoTime() > deadline) return;
        }
        cast.destructionDone = true;
    }

    private static final class Carver {
        final ServerLevel level;
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunk chunk;
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;

        Carver(Cast cast) {
            this.level = cast.level;
        }

        void column(int x, int z, int bottom, int top) {
            if (top < bottom) return;
            if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
                chunkX = x >> 4;
                chunkZ = z >> 4;
                chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            }
            if (chunk == null) return;

            int y = top;
            while (y >= bottom) {
                int sectionIndex = chunk.getSectionIndex(y);
                LevelChunkSection section = sectionIndex >= 0 && sectionIndex < chunk.getSectionsCount()
                        ? chunk.getSection(sectionIndex) : null;
                if (section == null || section.hasOnlyAir()) {
                    y = ((y >> 4) << 4) - 1;
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
        if (owner != null) {
            owner.fallDistance = 0.0f;
            owner.getAbilities().mayfly = cast.origMayfly;
            owner.getAbilities().flying = cast.origMayfly && cast.origFlying;
            owner.onUpdateAbilities();
            if (completed) {
                // Игрок остаётся там, куда поднялся, — в режиме полёта.
                if (owner.isAlive()) JujutsuNeonFlightPatch.startForTechnique(owner);
                // Откат 60 секунд отсчитывается от окончания техники.
                owner.getPersistentData().putLong("jn_cd_max_purple", cast.level.getGameTime() + COOLDOWN_TICKS);
            }
        }
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> cast.level.dimension()), new EndPacket(cast.ownerId, completed));
        // Владелец ушёл в другое измерение — сообщаем ему лично.
        if (owner != null && owner.level() != cast.level) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner), new EndPacket(cast.ownerId, completed));
        }
    }

    // ------------------------------------------------------------------ guards

    /** Вышел посреди техники — возвращаем способности до сохранения игрока (иначе останется вечный полёт). */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Cast cast = CASTS.get(player.getUUID());
        if (cast == null) return;
        player.getAbilities().mayfly = cast.origMayfly;
        player.getAbilities().flying = cast.origMayfly && cast.origFlying;
        player.onUpdateAbilities();
        if (!cast.damageDone) {
            CASTS.remove(player.getUUID());
            CHANNEL.send(PacketDistributor.DIMENSION.with(() -> cast.level.dimension()), new EndPacket(cast.ownerId, false));
        }
    }

    /** Сервер останавливается — ничего не тащим в следующий мир. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Cast cast : CASTS.values()) {
            ServerPlayer owner = event.getServer().getPlayerList().getPlayer(cast.ownerId);
            if (owner != null) {
                owner.getAbilities().mayfly = cast.origMayfly;
                owner.getAbilities().flying = cast.origMayfly && cast.origFlying;
                owner.onUpdateAbilities();
            }
        }
        CASTS.clear();
    }

    /** Владелец во время кат-сцены неуязвим. */
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
