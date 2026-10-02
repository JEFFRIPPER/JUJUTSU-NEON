package com.kira.jujutsuneon;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Hollow Purple visual/destruction overhaul.
 *
 * ЖЁСТКИЕ ПРАВИЛА:
 * 1. Модель Hollow Purple непрерывная и объёмная; billboard не является телом техники.
 * 2. Red + Blue существуют как две 3D-сферы и физически сходятся в Purple.
 * 3. Projectile синхронизируется каждый серверный тик и интерполируется на клиенте.
 * 4. Тоннель считается как swept-cylinder по ВСЕМ блокам-кандидатам.
 * 5. Внутри разрешённого к уничтожению объёма не остаются случайные висящие блоки.
 * 6. Purple удаляет bedrock, жидкости и контейнеры; защищены только технические блоки,
 *    которые способны повредить целостность игры, и фактическая клетка владельца.
 */
@Mod.EventBusSubscriber(
        modid = HollowPurpleOverhaul.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class HollowPurpleOverhaul {

    public static final String MODID = "jujutsu_neon";

    private static final int MODE_NONE = 0;
    private static final int MODE_CASTING = 1;
    private static final int MODE_PROJECTILE = 2;

    private static final double FULL_DISTANCE = 150.0;
    private static final double FADE_DISTANCE = 10.0;
    private static final double END_DISTANCE = FULL_DISTANCE + FADE_DISTANCE;
    private static final double FULL_TUNNEL_RADIUS = 6.0;
    private static final double FULL_BALL_RADIUS = 2.0;
    private static final long CAST_TICKS = 100L;

    // Половина диагонали блока. Добавляем её к радиусу проверки центра блока,
    // чтобы граничные кубы цилиндра тоже гарантированно исчезали без дырок.
    private static final double BLOCK_HALF_DIAGONAL = 0.88;
    private static final int BLOCK_FLAGS = 2 | 16 | 32;

    private static final String VISUAL_PROTOCOL = "2";
    private static final SimpleChannel VISUAL_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "purple_visual_v2"),
            () -> VISUAL_PROTOCOL,
            VISUAL_PROTOCOL::equals,
            VISUAL_PROTOCOL::equals
    );

    private static final Map<UUID, ServerPurpleState> SERVER_STATES = new HashMap<>();

    static {
        VISUAL_CHANNEL.registerMessage(
                0,
                PurpleVisualPacket.class,
                PurpleVisualPacket::encode,
                PurpleVisualPacket::decode,
                PurpleVisualPacket::handle
        );
    }

    private HollowPurpleOverhaul() {
    }

    public record PurpleVisualPacket(
            UUID ownerId,
            boolean active,
            int mode,
            float castProgress,
            double x,
            double y,
            double z,
            double dirX,
            double dirY,
            double dirZ,
            float radius,
            double distance
    ) {
        static void encode(PurpleVisualPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
            buf.writeBoolean(msg.active);
            buf.writeVarInt(msg.mode);
            buf.writeFloat(msg.castProgress);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeDouble(msg.dirX);
            buf.writeDouble(msg.dirY);
            buf.writeDouble(msg.dirZ);
            buf.writeFloat(msg.radius);
            buf.writeDouble(msg.distance);
        }

        static PurpleVisualPacket decode(FriendlyByteBuf buf) {
            return new PurpleVisualPacket(
                    buf.readUUID(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readDouble()
            );
        }

        static void handle(PurpleVisualPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> HollowPurpleClient.accept(msg)
            ));
            context.setPacketHandled(true);
        }
    }

    private static final class ServerPurpleState {
        int lastMode = MODE_NONE;
        Vec3 lastProjectilePos;
        double lastDistance;
        boolean sentActive;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        int mode = player.getPersistentData().getInt("jn_purple_mode");
        UUID id = player.getUUID();
        ServerPurpleState state = SERVER_STATES.computeIfAbsent(id, ignored -> new ServerPurpleState());

        if (mode == MODE_CASTING) {
            tickCasting(player, state);
            return;
        }

        if (mode == MODE_PROJECTILE) {
            tickProjectile(player, state);
            return;
        }

        if (state.sentActive || state.lastMode != MODE_NONE) {
            sendVisual(new PurpleVisualPacket(
                    id,
                    false,
                    MODE_NONE,
                    0.0f,
                    player.getX(),
                    player.getEyeY(),
                    player.getZ(),
                    0.0,
                    0.0,
                    1.0,
                    0.0f,
                    0.0
            ));
        }

        SERVER_STATES.remove(id);
    }

    private static void tickCasting(ServerPlayer player, ServerPurpleState state) {
        long now = player.level().getGameTime();
        long started = player.getPersistentData().getLong("jn_purple_started");
        float progress = (float) Mth.clamp((now - started) / (double) CAST_TICKS, 0.0, 1.0);

        Vec3 eye = player.getEyePosition();
        Vec3 direction = safeDirection(player.getLookAngle());

        sendVisual(new PurpleVisualPacket(
                player.getUUID(),
                true,
                MODE_CASTING,
                progress,
                eye.x,
                eye.y,
                eye.z,
                direction.x,
                direction.y,
                direction.z,
                (float) castPurpleRadius(progress),
                0.0
        ));

        state.lastMode = MODE_CASTING;
        state.lastProjectilePos = null;
        state.lastDistance = 0.0;
        state.sentActive = true;
    }

    private static void tickProjectile(ServerPlayer player, ServerPurpleState state) {
        ServerLevel level = player.serverLevel();

        Vec3 current = new Vec3(
                player.getPersistentData().getDouble("jn_purple_x"),
                player.getPersistentData().getDouble("jn_purple_y"),
                player.getPersistentData().getDouble("jn_purple_z")
        );

        Vec3 velocity = new Vec3(
                player.getPersistentData().getDouble("jn_purple_vx"),
                player.getPersistentData().getDouble("jn_purple_vy"),
                player.getPersistentData().getDouble("jn_purple_vz")
        );

        Vec3 direction = safeDirection(velocity);
        double distance = Mth.clamp(
                player.getPersistentData().getDouble("jn_purple_distance"),
                0.0,
                END_DISTANCE
        );

        Vec3 previous;
        double previousDistance;

        if (state.lastMode == MODE_PROJECTILE && state.lastProjectilePos != null) {
            previous = state.lastProjectilePos;
            previousDistance = state.lastDistance;
        } else {
            double step = Math.max(0.01, velocity.length());
            previous = current.subtract(direction.scale(step));
            previousDistance = Math.max(0.0, distance - step);
        }

        // Второй, точный проход дополняет старую бюджетированную очередь.
        // Он обычно удаляет только то, что пропустила дискретная выборка,
        // поэтому setBlock-вызовов значительно меньше, чем полный повтор тоннеля.
        annihilateSweptCylinder(
                player,
                level,
                previous,
                current,
                previousDistance,
                distance
        );

        float ballRadius = (float) purpleBallRadius(distance);

        sendVisual(new PurpleVisualPacket(
                player.getUUID(),
                true,
                MODE_PROJECTILE,
                1.0f,
                current.x,
                current.y,
                current.z,
                direction.x,
                direction.y,
                direction.z,
                ballRadius,
                distance
        ));

        state.lastMode = MODE_PROJECTILE;
        state.lastProjectilePos = current;
        state.lastDistance = distance;
        state.sentActive = true;
    }

    private static void sendVisual(PurpleVisualPacket packet) {
        // Purple должен быть виден независимо от того, кто является владельцем камеры.
        // Пакет маленький, техника существует лишь несколько секунд.
        VISUAL_CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    private static Vec3 safeDirection(Vec3 vector) {
        if (vector == null || vector.lengthSqr() < 1.0E-8) {
            return new Vec3(0.0, 0.0, 1.0);
        }
        return vector.normalize();
    }

    private static double castPurpleRadius(float progress) {
        if (progress < 0.38f) return 0.0;

        if (progress < 0.72f) {
            double merge = smooth((progress - 0.38) / 0.34);
            return 0.18 + merge * 0.70;
        }

        double growth = smooth((progress - 0.72) / 0.28);
        return 0.82 + growth * (FULL_BALL_RADIUS - 0.82);
    }

    private static double purpleBallRadius(double distance) {
        if (distance <= FULL_DISTANCE) return FULL_BALL_RADIUS;
        double t = Mth.clamp((distance - FULL_DISTANCE) / FADE_DISTANCE, 0.0, 1.0);
        return FULL_BALL_RADIUS * (1.0 - smooth(t));
    }

    private static double purpleTunnelRadius(double distance) {
        if (distance < 0.0 || distance >= END_DISTANCE) return 0.0;
        if (distance <= FULL_DISTANCE) return FULL_TUNNEL_RADIUS;

        double t = Mth.clamp((distance - FULL_DISTANCE) / FADE_DISTANCE, 0.0, 1.0);
        return FULL_TUNNEL_RADIUS * (1.0 - smooth(t));
    }

    private static double smooth(double value) {
        double t = Mth.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static void annihilateSweptCylinder(
            ServerPlayer owner,
            ServerLevel level,
            Vec3 from,
            Vec3 to,
            double startDistance,
            double endDistance
    ) {
        Vec3 segment = to.subtract(from);
        double segmentLengthSq = segment.lengthSqr();
        if (segmentLengthSq < 1.0E-8) return;

        double maxRadius = Math.max(
                purpleTunnelRadius(startDistance),
                purpleTunnelRadius(endDistance)
        );
        if (maxRadius <= 0.0) return;

        double padding = maxRadius + BLOCK_HALF_DIAGONAL + 0.05;

        int minX = Mth.floor(Math.min(from.x, to.x) - padding);
        int maxX = Mth.floor(Math.max(from.x, to.x) + padding);
        int minY = Mth.floor(Math.min(from.y, to.y) - padding);
        int maxY = Mth.floor(Math.max(from.y, to.y) + padding);
        int minZ = Mth.floor(Math.min(from.z, to.z) - padding);
        int maxZ = Mth.floor(Math.max(from.z, to.z) + padding);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) continue;

                    BlockState blockState = level.getBlockState(pos);
                    if (blockState.isAir() && blockState.getFluidState().isEmpty()) continue;
                    if (isProtectedTechnicalBlock(blockState)) continue;
                    if (isOwnerSafeCell(owner, level, pos)) continue;

                    Vec3 center = Vec3.atCenterOf(pos);
                    double t = Mth.clamp(
                            center.subtract(from).dot(segment) / segmentLengthSq,
                            0.0,
                            1.0
                    );

                    double distanceAtPoint = Mth.lerp(t, startDistance, endDistance);
                    double tunnelRadius = purpleTunnelRadius(distanceAtPoint);
                    if (tunnelRadius <= 0.0) continue;

                    Vec3 closest = from.add(segment.scale(t));
                    double effectiveRadius = tunnelRadius + BLOCK_HALF_DIAGONAL;

                    if (center.distanceToSqr(closest) > effectiveRadius * effectiveRadius) continue;

                    // Никакого дропа и никаких FallingBlockEntity.
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), BLOCK_FLAGS);
                }
            }
        }
    }

    private static boolean isOwnerSafeCell(
            ServerPlayer owner,
            ServerLevel level,
            BlockPos pos
    ) {
        AABB body = owner.getBoundingBox().inflate(0.001);
        AABB cell = new AABB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0
        );

        if (body.intersects(cell)) return true;

        BlockPos support = BlockPos.containing(
                owner.getX(),
                owner.getY() - 0.05,
                owner.getZ()
        );

        if (!pos.equals(support)) return false;

        BlockState supportState = level.getBlockState(support);
        return !supportState.isAir() &&
                supportState.getFluidState().isEmpty() &&
                !supportState.getCollisionShape(level, support).isEmpty();
    }

    private static boolean isProtectedTechnicalBlock(BlockState state) {
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
}
