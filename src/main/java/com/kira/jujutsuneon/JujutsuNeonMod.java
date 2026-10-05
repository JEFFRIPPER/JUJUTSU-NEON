package com.kira.jujutsuneon;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Jujutsu Neon ULTIMATE — Forge 1.20.1.
 * Maximum Blue использует непрерывную процедурную 3D-модель; остальные техники пока сохраняют VFX-слои.
 * Поддерживающие частицы, HD-аудио и 4K-текстуры предметов работают отдельными слоями.
 * VFX-пайплайн усилен многослойными shockwave/star/slash/trail эффектами
 * в яркой action-RPG эстетике, без копирования чужих ассетов.
 *
 * Повязка Годжо:
 * Пока она надета в слот головы, доступны техники и усиленное движение.
 *
 * Клавиши по умолчанию:
 * Q — дэш. Q = длинный front dash, A+Q/D+Q = резкий side dash; в воздухе Q идёт по камере.
 * Space — заряд прыжка: <1с = 2 блока, 1с = 7, 2с = 13, 3с = 18.
 * Без Ctrl с повязкой: ходьба ~3x. Left Ctrl — сверхбег ~8x, 1% CE за 2 сек, бег по воде.
 * R — мгновенный телепорт к загруженному блоку под прицелом, стан 2 сек.
 * Z — Blue; удержание 1 сек запускает Maximum Blue, отпускание начинает рассеивание
 * X — Red: отпусти до 2с для обычного выстрела; удержание 2с превращает его в Maximum Red; авто-выстрел на 5с
 * G — Hollow Purple: 5-секундный каст, затем автоматический дальнобойный выстрел
 * V — Infinity ON/OFF; V+ — Domain Expansion
 * B — RCT/лечение; B+ — Limitless Blink
 *
 * H — показать/скрыть боковую панель техник.
 * Любой бинд можно переназначить прямо в Minecraft:
 * Настройки -> Управление -> Назначение клавиш -> Jujutsu Neon — способности.
 *
 * ВАЖНО:
 * 1) Файл должен лежать по пути:
 *    src/main/java/com/kira/jujutsuneon/JujutsuNeonMod.java
 *
 * 2) В META-INF/mods.toml modId должен быть:
 *    jujutsu_neon
 *
 * 3) Код рассчитан на Minecraft 1.20.1 + Forge 47.x.
 */
@Mod(JujutsuNeonMod.MODID)
public class JujutsuNeonMod {

    /*
     * ============================================================
     * JUJUTSU NEON — ЖЁСТКИЕ ИНВАРИАНТЫ ВИЗУАЛА
     * ============================================================
     * 1) Основное тело техники НИКОГДА не является billboard-PNG.
     * 2) PNG/частицы разрешены только как вторичный VFX-слой.
     * 3) Основная 3D-модель существует непрерывно: без моргания,
     *    пересоздания по тикам, случайных скачков масштаба/позиции.
     * 4) Движение, масштаб и исчезновение интерполируются между тиками.
     * 5) Поворот камеры не имеет права менять ориентацию 3D-модели.
     * 6) Оптимизация не имеет права ухудшать непрерывность геометрии.
     * 7) Если техника вырезает объём, внутри рассчитанного объёма
     *    не должны оставаться случайные разрушаемые блоки.
     * ============================================================
     */

    public static final String MODID = "jujutsu_neon";
    private static final String PROTOCOL = "16";

    private static final double CE_MAX = 100.0;

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID);

    private static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, MODID);

    private static RegistryObject<SimpleParticleType> particle(String name) {
        return PARTICLES.register(name, () -> new SimpleParticleType(true));
    }

    public static final RegistryObject<SimpleParticleType> VFX_BLUE = particle("vfx_blue");
    public static final RegistryObject<SimpleParticleType> VFX_MAX_BLUE = particle("vfx_max_blue");
    public static final RegistryObject<SimpleParticleType> VFX_RED = particle("vfx_red");
    public static final RegistryObject<SimpleParticleType> VFX_MAX_RED = particle("vfx_max_red");
    public static final RegistryObject<SimpleParticleType> VFX_PURPLE = particle("vfx_hollow_purple");
    public static final RegistryObject<SimpleParticleType> VFX_BARRAGE = particle("vfx_cursed_barrage");
    public static final RegistryObject<SimpleParticleType> VFX_INFINITY = particle("vfx_infinity");
    public static final RegistryObject<SimpleParticleType> VFX_DOMAIN = particle("vfx_domain");
    public static final RegistryObject<SimpleParticleType> VFX_RCT = particle("vfx_rct");
    public static final RegistryObject<SimpleParticleType> VFX_TELEPORT = particle("vfx_teleport");
    public static final RegistryObject<SimpleParticleType> VFX_DASH = particle("vfx_dash");

    // Layered anime/action VFX: shockwave, star-burst, slash and speed trail.
    public static final RegistryObject<SimpleParticleType> VFX_SHOCKWAVE = particle("vfx_shockwave");
    public static final RegistryObject<SimpleParticleType> VFX_STAR = particle("vfx_star");
    public static final RegistryObject<SimpleParticleType> VFX_SLASH = particle("vfx_slash");
    public static final RegistryObject<SimpleParticleType> VFX_TRAIL = particle("vfx_trail");

    private static RegistryObject<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MODID, name)));
    }

    public static final RegistryObject<SoundEvent> SFX_BLUE = sound("blue");
    public static final RegistryObject<SoundEvent> SFX_MAX_BLUE = sound("max_blue");
    public static final RegistryObject<SoundEvent> SFX_RED = sound("red");
    public static final RegistryObject<SoundEvent> SFX_PURPLE = sound("hollow_purple");
    public static final RegistryObject<SoundEvent> SFX_BARRAGE = sound("cursed_barrage");
    public static final RegistryObject<SoundEvent> SFX_DOMAIN = sound("domain");
    public static final RegistryObject<SoundEvent> SFX_INFINITY = sound("infinity");
    public static final RegistryObject<SoundEvent> SFX_RCT = sound("rct");
    public static final RegistryObject<SoundEvent> SFX_TELEPORT = sound("teleport");
    public static final RegistryObject<SoundEvent> SFX_DASH = sound("dash");
    public static final RegistryObject<SoundEvent> SFX_MAX_PURPLE_THEME = sound("max_purple_theme");
    public static final RegistryObject<SoundEvent> SFX_MAX_PURPLE_THEME_WORLD = sound("max_purple_theme_world");
    public static final RegistryObject<SoundEvent> SFX_DOMAIN_VOICE = sound("domain_voice");
    public static final RegistryObject<SoundEvent> SFX_DOMAIN_VOICE_WORLD = sound("domain_voice_world");
    public static final RegistryObject<SoundEvent> SFX_DOMAIN_SHATTER = sound("domain_shatter");
    public static final RegistryObject<SoundEvent> SFX_DOMAIN_SHATTER_WORLD = sound("domain_shatter_world");
    // Обычный Синий (свой синтез, tools/gen_lapse_blue_sounds.py)
    public static final RegistryObject<SoundEvent> SFX_LAPSE_CAST = sound("lapse_cast");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_BURST = sound("lapse_burst");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_PULL = sound("lapse_pull");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_KICK = sound("lapse_kick");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_BLINK = sound("lapse_blink");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_SLOWMO = sound("lapse_slowmo");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_CONTACT = sound("lapse_contact");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_SLAM = sound("lapse_slam");
    public static final RegistryObject<SoundEvent> SFX_LAPSE_LAND = sound("lapse_land");
    // Красный / Максимальный Красный — звук из референса
    public static final RegistryObject<SoundEvent> SFX_RED_CAST = sound("red_cast");
    public static final RegistryObject<SoundEvent> SFX_MAX_RED_CAST = sound("max_red_cast");
    // Движение (tools/gen_move_sounds.py)
    public static final RegistryObject<SoundEvent> SFX_MOVE_DASH_FRONT = sound("move_dash_front");
    public static final RegistryObject<SoundEvent> SFX_MOVE_DASH_SIDE = sound("move_dash_side");
    public static final RegistryObject<SoundEvent> SFX_MOVE_CROUCH = sound("move_crouch");
    public static final RegistryObject<SoundEvent> SFX_MOVE_TAKEOFF = sound("move_takeoff");
    public static final RegistryObject<SoundEvent> SFX_MOVE_BOOST = sound("move_boost");
    public static final RegistryObject<SoundEvent> SFX_MOVE_STEP = sound("move_step");
    public static final RegistryObject<SoundEvent> SFX_MOVE_LAND = sound("move_land");

    public static final RegistryObject<Item> GOJO_BLINDFOLD = ITEMS.register(
            "gojo_blindfold",
            () -> new GojoBlindfoldItem(
                    GojoBlindfoldMaterial.INSTANCE,
                    ArmorItem.Type.HELMET,
                    new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
            )
    );

    private static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int packetId = 0;

    public JujutsuNeonMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        PARTICLES.register(modBus);
        DomainExpansion.register(modBus);
        RedTechnique.init();
        MovementFx.init();
        modBus.addListener(this::addToCreativeTab);

        NETWORK.registerMessage(
                packetId++,
                AbilityPacket.class,
                AbilityPacket::encode,
                AbilityPacket::decode,
                AbilityPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                MovementPacket.class,
                MovementPacket::encode,
                MovementPacket::decode,
                MovementPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                HudSyncPacket.class,
                HudSyncPacket::encode,
                HudSyncPacket::decode,
                HudSyncPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                BlueActionPacket.class,
                BlueActionPacket::encode,
                BlueActionPacket::decode,
                BlueActionPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                RedControlPacket.class,
                RedControlPacket::encode,
                RedControlPacket::decode,
                RedControlPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                MaxBlueControlPacket.class,
                MaxBlueControlPacket::encode,
                MaxBlueControlPacket::decode,
                MaxBlueControlPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                MaxBlueVisualPacket.class,
                MaxBlueVisualPacket::encode,
                MaxBlueVisualPacket::decode,
                MaxBlueVisualPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                MaxBlueDebrisBatchPacket.class,
                MaxBlueDebrisBatchPacket::encode,
                MaxBlueDebrisBatchPacket::decode,
                MaxBlueDebrisBatchPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                JumpControlPacket.class,
                JumpControlPacket::encode,
                JumpControlPacket::decode,
                JumpControlPacket::handle
        );

        NETWORK.registerMessage(
                packetId++,
                TeleportPacket.class,
                TeleportPacket::encode,
                TeleportPacket::decode,
                TeleportPacket::handle
        );
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(GOJO_BLINDFOLD);
        }
    }

    private enum Ability {
        BLUE,
        MAX_BLUE,
        RED,
        HOLLOW_PURPLE,
        CURSED_BARRAGE,
        INFINITY_TOGGLE,
        RCT
    }

    private enum MovementAction {
        FRONT_DASH,
        BACK_DASH,
        LEFT_DASH,
        RIGHT_DASH,
        SPEED_ON,
        SPEED_OFF
    }

    private enum RedControlAction {
        START,
        RELEASE,
        CANCEL
    }

    private enum MaxBlueControlAction {
        START,
        RELEASE,
        FARTHER,
        CLOSER
    }

    static boolean hasGojoBlindfold(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).is(GOJO_BLINDFOLD.get());
    }

    static void requireBlindfoldMessage(ServerPlayer player) {
        player.displayClientMessage(
                Component.literal("Надень Повязку Годжо, чтобы использовать технику.")
                        .withStyle(ChatFormatting.LIGHT_PURPLE),
                true
        );
    }

    static double getEnergy(ServerPlayer player) {
        if (!player.getPersistentData().contains("jn_ce")) {
            player.getPersistentData().putDouble("jn_ce", CE_MAX);
        }
        return Mth.clamp(player.getPersistentData().getDouble("jn_ce"), 0.0, CE_MAX);
    }

    static void setEnergy(ServerPlayer player, double value) {
        player.getPersistentData().putDouble("jn_ce", Mth.clamp(value, 0.0, CE_MAX));
    }

    private static boolean consumeEnergy(ServerPlayer player, double amount) {
        double current = getEnergy(player);
        if (current + 1.0E-6 < amount) {
            player.displayClientMessage(
                    Component.literal("Недостаточно проклятой энергии: нужно " + (int) Math.ceil(amount) + "%")
                            .withStyle(ChatFormatting.AQUA),
                    true
            );
            return false;
        }
        setEnergy(player, current - amount);
        return true;
    }

    private static double abilityCost(ServerPlayer player, Ability ability) {
        return switch (ability) {
            case BLUE -> 8.0;
            case MAX_BLUE -> 24.0;
            case RED -> 12.0;
            case HOLLOW_PURPLE -> 45.0;
            case CURSED_BARRAGE -> 22.0;
            case INFINITY_TOGGLE -> player.getPersistentData().getBoolean("jn_infinity") ? 0.0 : 10.0;
            case RCT -> 30.0;
        };
    }

    private static void useAbility(ServerPlayer player, Ability ability) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;

        if (!hasGojoBlindfold(player)) {
            requireBlindfoldMessage(player);
            return;
        }

        // Во время Максимального Фиолетового другие техники недоступны.
        if (MaximumPurple.isActive(player)) return;
        // Обездвижен территорией или сам кастует территорию.
        if (DomainExpansion.blocksActions(player)) return;

        if (isHollowPurpleCasting(player) && ability != Ability.HOLLOW_PURPLE) {
            player.displayClientMessage(
                    Component.literal("HOLLOW PURPLE // CAST IN PROGRESS")
                            .withStyle(ChatFormatting.DARK_PURPLE),
                    true
            );
            return;
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        String cooldownKey = "jn_cd_" + ability.name().toLowerCase();
        long cooldownUntil = player.getPersistentData().getLong(cooldownKey);

        if (now < cooldownUntil) {
            long ticksLeft = cooldownUntil - now;
            double seconds = Math.ceil(ticksLeft / 2.0) / 10.0;
            player.displayClientMessage(
                    Component.literal("Перезарядка: " + seconds + " сек.")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return;
        }

        double cost = abilityCost(player, ability);
        if (!consumeEnergy(player, cost)) return;

        switch (ability) {
            case BLUE -> {
                if (castBlue(player)) {
                    setCooldown(player, ability, 100);
                } else {
                    setEnergy(player, getEnergy(player) + cost);
                }
            }
            case MAX_BLUE -> { castMaxBlue(player); setCooldown(player, ability, 220); }
            case RED -> { castRed(player); setCooldown(player, ability, 140); }
            case HOLLOW_PURPLE -> {
                if (startHollowPurpleCast(player)) {
                    setCooldown(player, ability, 420);
                } else {
                    setEnergy(player, getEnergy(player) + cost);
                }
            }
            case CURSED_BARRAGE -> { castCursedBarrage(player); setCooldown(player, ability, 180); }
            case INFINITY_TOGGLE -> { castInfinityToggle(player); setCooldown(player, ability, 20); }
            case RCT -> { castRCT(player); setCooldown(player, ability, 260); }
        }
    }

    private static void setCooldown(ServerPlayer player, Ability ability, long ticks) {
        player.getPersistentData().putLong(
                "jn_cd_" + ability.name().toLowerCase(),
                player.level().getGameTime() + ticks
        );
    }

    private static void playSfx(ServerLevel level, ServerPlayer player, RegistryObject<SoundEvent> sfx, float volume, float pitch) {
        level.playSound(null, player.blockPosition(), sfx.get(), SoundSource.PLAYERS, volume, pitch);
    }

    private static void handSign(ServerPlayer player) {
        player.swing(InteractionHand.MAIN_HAND, true);
        player.swing(InteractionHand.OFF_HAND, true);
    }


    /**
     * Common safety rule for destructive Limitless techniques.
     * - A real solid block currently supporting the owner is protected.
     * - Any block/fluid cell actually occupied by the owner's hitbox is protected.
     * - In air there is no fake protected floor.
     * - In water/lava only the fluid cell containing the owner is protected; blocks below remain valid targets.
     */
    private static boolean isOwnerSafeBlock(ServerPlayer owner, BlockPos pos) {
        ServerLevel level = owner.serverLevel();

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

    private static Map<BlockPos, BlockState> snapshotOwnerSafeBlocks(ServerPlayer owner) {
        ServerLevel level = owner.serverLevel();
        Map<BlockPos, BlockState> result = new HashMap<>();

        AABB body = owner.getBoundingBox().inflate(0.001);
        int minX = Mth.floor(body.minX);
        int maxX = Mth.floor(body.maxX);
        int minY = Mth.floor(body.minY);
        int maxY = Mth.floor(body.maxY);
        int minZ = Mth.floor(body.minZ);
        int maxZ = Mth.floor(body.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isOwnerSafeBlock(owner, pos)) {
                        result.put(pos.immutable(), level.getBlockState(pos));
                    }
                }
            }
        }

        BlockPos support = BlockPos.containing(owner.getX(), owner.getY() - 0.05, owner.getZ());
        if (isOwnerSafeBlock(owner, support)) {
            result.put(support.immutable(), level.getBlockState(support));
        }

        return result;
    }

    private static void restoreOwnerSafeBlocks(ServerPlayer owner, Map<BlockPos, BlockState> safeStates) {
        ServerLevel level = owner.serverLevel();

        for (Map.Entry<BlockPos, BlockState> entry : safeStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState original = entry.getValue();

            if (!level.getBlockState(pos).equals(original)) {
                level.setBlock(pos, original, 2 | 16 | 32);
            }
        }
    }


    /**
     * Layered action-game VFX. These use only registered Forge particles,
     * so the effects remain multiplayer-safe and do not require shaders.
     */
    private static void playImpactLayer(ServerLevel level, Vec3 at, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                volume, pitch);
        level.playSound(null, at.x, at.y, at.z,
                SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS,
                Math.max(0.25f, volume * 0.55f), Math.min(1.9f, pitch * 1.28f));
    }

    private static void playEnergyLayer(ServerLevel level, Vec3 at, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z,
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS,
                Math.max(0.2f, volume * 0.42f), Math.min(1.9f, pitch * 1.22f));
    }

    private static void spawnStylizedShockwave(
            ServerLevel level,
            Vec3 center,
            double radius,
            Vector3f color
    ) {
        // Big camera-facing ring sprite + concentric 3D rings.
        spawnVfx(level, VFX_SHOCKWAVE, center, 2);

        for (int layer = 0; layer < 3; layer++) {
            double r = radius * (0.70 + layer * 0.25);
            int points = 64 + layer * 16;

            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2.0 * i / points;
                double x = center.x + Math.cos(a) * r;
                double z = center.z + Math.sin(a) * r;
                double y = center.y + Math.sin(a * 3.0) * 0.06 * (layer + 1);
                sendDust(level, new Vec3(x, y, z), color, 1.05f + layer * 0.22f);
            }
        }
    }

    private static void spawnRadialStar(
            ServerLevel level,
            Vec3 center,
            Vector3f colorA,
            Vector3f colorB,
            int rays,
            double length
    ) {
        spawnVfx(level, VFX_STAR, center, 2);

        for (int ray = 0; ray < rays; ray++) {
            double yaw = (Math.PI * 2.0 * ray / rays) + rnd(-0.12, 0.12);
            double rise = rnd(-0.35, 0.55);
            Vec3 dir = new Vec3(Math.cos(yaw), rise, Math.sin(yaw)).normalize();

            for (int p = 1; p <= 7; p++) {
                double d = length * p / 7.0;
                Vec3 pos = center.add(dir.scale(d));
                sendDust(level, pos, (ray + p) % 2 == 0 ? colorA : colorB,
                        1.20f - p * 0.06f);
            }
        }

        level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                center.x, center.y, center.z,
                26,
                length * 0.25, length * 0.18, length * 0.25,
                0.12
        );
    }

    private static void spawnEnergySpiral(
            ServerLevel level,
            Vec3 origin,
            Vec3 direction,
            double length,
            double radius,
            double turns,
            Vector3f colorA,
            Vector3f colorB
    ) {
        Vec3 forward = direction.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.90
                ? new Vec3(1, 0, 0)
                : new Vec3(0, 1, 0);

        Vec3 right = forward.cross(reference).normalize();
        Vec3 up = right.cross(forward).normalize();

        int points = Math.max(36, (int) (length * 7.0));

        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            double angle = t * turns * Math.PI * 2.0;
            double r = radius * (0.45 + 0.55 * Math.sin(Math.PI * t));

            Vec3 ringOffset = right.scale(Math.cos(angle) * r)
                    .add(up.scale(Math.sin(angle) * r));

            Vec3 p = origin.add(forward.scale(length * t)).add(ringOffset);
            sendDust(level, p, i % 2 == 0 ? colorA : colorB, 1.15f);

            if (i % 9 == 0) {
                spawnVfx(level, VFX_TRAIL, p, 1);
            }
        }
    }

    private static void spawnSlashFan(
            ServerLevel level,
            Vec3 center,
            Vector3f colorA,
            Vector3f colorB
    ) {
        spawnVfx(level, VFX_SLASH, center, 2);

        for (int slash = 0; slash < 5; slash++) {
            double baseAngle = -0.9 + slash * 0.45;
            for (int i = 0; i < 20; i++) {
                double t = i / 19.0;
                double r = 0.35 + t * 2.4;
                double a = baseAngle + (t - 0.5) * 0.65;
                Vec3 p = center.add(
                        Math.cos(a) * r,
                        (t - 0.5) * 1.3 + (slash - 2) * 0.12,
                        Math.sin(a) * r
                );
                sendDust(level, p, (slash + i) % 2 == 0 ? colorA : colorB, 1.15f);
            }
        }
    }

    private static void spawnDome(
            ServerLevel level,
            Vec3 center,
            double radius,
            Vector3f colorA,
            Vector3f colorB
    ) {
        // Sparse hemisphere lattice: looks large but keeps particle count sane.
        for (int lat = 1; lat <= 8; lat++) {
            double phi = (Math.PI / 2.0) * lat / 8.0;
            double ringRadius = Math.cos(phi) * radius;
            double y = Math.sin(phi) * radius;
            int points = 24 + lat * 4;

            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2.0 * i / points;
                Vec3 p = center.add(Math.cos(a) * ringRadius, y, Math.sin(a) * ringRadius);
                if ((i + lat) % 2 == 0) {
                    sendDust(level, p, lat % 2 == 0 ? colorA : colorB, 0.95f);
                }
            }
        }
    }

    private static final int BLUE_MODE_NONE = 0;
    private static final int BLUE_MODE_BLOCKS = 1;
    private static final int BLUE_MODE_ENTITY = 2;
    private static final int BLUE_MODE_PROJECTILE = 3;
    private static final double BLUE_RANGE = 20.0;
    /** Автоаим Синего: цель берётся, если прицел хотя бы примерно рядом (в этом конусе от края хитбокса). */
    private static final double BLUE_AIM_CONE_DEG = 10.0;

    private static boolean isBlueInteractionActive(ServerPlayer player) {
        int mode = player.getPersistentData().getInt("jn_blue_mode");
        return mode == BLUE_MODE_BLOCKS || mode == BLUE_MODE_ENTITY;
    }

    /**
     * Цель Синего с жёстким автоаимом: любое живое существо до 20 блоков, если луч прицела прошёл рядом
     * с его хитбоксом или оно в конусе ~10° от прицела; из таких — ближайшее к прицелу и видимое (не за стеной).
     */
    private static LivingEntity findBlueLivingTarget(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 end = eye.add(look.scale(BLUE_RANGE));
        AABB area = player.getBoundingBox().inflate(BLUE_RANGE + 2.0);

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e.isAlive() && e != player && !e.isSpectator() && !(e instanceof ArmorStand))) {
            if (e instanceof TamableAnimal pet && player.getUUID().equals(pet.getOwnerUUID())) continue;
            if (e.isPassengerOfSameVehicle(player) || player.hasPassenger(e) || e.hasPassenger(player)) continue;
            AABB box = e.getBoundingBox();
            Vec3 center = box.getCenter();
            Vec3 to = center.subtract(eye);
            double dist = to.length();
            if (dist > BLUE_RANGE + Math.max(box.getXsize(), box.getYsize()) * 0.5 || dist < 1.0E-3) continue;

            // Луч прошёл рядом с хитбоксом (с запасом, растущим с дистанцией)?
            boolean rayHit = box.inflate(0.6 + dist * 0.035).clip(eye, end).isPresent();
            // Насколько прицел мимо края хитбокса, в градусах.
            double cos = Mth.clamp(to.scale(1.0 / dist).dot(look), -1.0, 1.0);
            double angle = Math.toDegrees(Math.acos(cos));
            double halfSize = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) * 0.5;
            double edge = Math.max(0.0, angle - Math.toDegrees(Math.atan2(halfSize, dist)));
            if (!rayHit && edge > BLUE_AIM_CONE_DEG) continue;
            if (cos < 0.2) continue;
            if (!blueCanSee(level, player, eye, e)) continue;

            double score = (rayHit ? 0.0 : edge) * 4.0 + dist * 0.12;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /** Цель видно: до центра, до головы или до ног нет стены. */
    private static boolean blueCanSee(ServerLevel level, ServerPlayer player, Vec3 eye, LivingEntity e) {
        AABB box = e.getBoundingBox();
        Vec3[] points = {
                box.getCenter(),
                new Vec3(box.getCenter().x, box.maxY - 0.1, box.getCenter().z),
                new Vec3(box.getCenter().x, box.minY + 0.1, box.getCenter().z)
        };
        for (Vec3 p : points) {
            BlockHitResult hit = level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(eye) >= eye.distanceToSqr(p) - 0.25) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> findBlueBlocks(ServerLevel level, ServerPlayer owner, BlockPos center) {
        List<BlockPos> candidates = new ArrayList<>();

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);

                    if (state.isAir()) continue;
                    if (isOwnerSafeBlock(owner, pos)) continue;
                    if (state.hasBlockEntity()) continue;
                    if (!state.getFluidState().isEmpty()) continue;
                    if (state.getDestroySpeed(level, pos) < 0.0F) continue;
                    if (state.is(Blocks.MOVING_PISTON) || state.is(Blocks.END_PORTAL) || state.is(Blocks.NETHER_PORTAL)) continue;

                    candidates.add(pos.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        if (candidates.size() > 5) {
            return new ArrayList<>(candidates.subList(0, 5));
        }
        return candidates;
    }

    private static Vec3 horizontalLook(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            double yaw = Math.toRadians(player.getYRot());
            horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        return horizontal.normalize();
    }

    private static void startBlueEntityHold(ServerPlayer player, LivingEntity target) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();

        Vec3 forward = horizontalLook(player);
        Vec3 targetLock = player.position().add(forward.scale(2.35));
        targetLock = new Vec3(targetLock.x, player.getY() + 0.10, targetLock.z);

        target.teleportTo(targetLock.x, targetLock.y, targetLock.z);
        target.setDeltaMovement(Vec3.ZERO);
        target.hurtMarked = true;

        player.getPersistentData().putInt("jn_blue_mode", BLUE_MODE_ENTITY);
        player.getPersistentData().putString("jn_blue_target_uuid", target.getStringUUID());
        player.getPersistentData().putLong("jn_blue_until", now + 40);

        player.getPersistentData().putDouble("jn_blue_owner_x", player.getX());
        player.getPersistentData().putDouble("jn_blue_owner_y", player.getY());
        player.getPersistentData().putDouble("jn_blue_owner_z", player.getZ());

        player.getPersistentData().putDouble("jn_blue_target_x", targetLock.x);
        player.getPersistentData().putDouble("jn_blue_target_y", targetLock.y);
        player.getPersistentData().putDouble("jn_blue_target_z", targetLock.z);

        for (int i = 0; i < 18; i++) {
            double t = i / 17.0;
            Vec3 p = player.getEyePosition().lerp(
                    target.position().add(0.0, target.getBbHeight() * 0.55, 0.0),
                    t
            );
            sendDust(level, p, new Vector3f(0.18f, 0.72f, 1.0f), 0.72f);
        }

        playSfx(level, player, SFX_BLUE, 0.72f, 1.12f);
        player.displayClientMessage(
                Component.literal("BLUE // ЦЕЛЬ ЗАФИКСИРОВАНА // ЛКМ: ОТБРОСИТЬ")
                        .withStyle(ChatFormatting.AQUA),
                true
        );
    }

    private static boolean startBlueBlockHold(ServerPlayer player, BlockHitResult hit) {
        ServerLevel level = player.serverLevel();
        List<BlockPos> blocks = findBlueBlocks(level, player, hit.getBlockPos());

        if (blocks.size() < 5) {
            player.displayClientMessage(
                    Component.literal("BLUE // рядом с курсором нужно 5 подходящих блоков")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return false;
        }

        int[] ids = new int[5];
        List<Vec3> origins = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            BlockPos pos = blocks.get(i);
            BlockState state = level.getBlockState(pos);
            FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, state);
            falling.setNoGravity(true);
            falling.noPhysics = true;
            falling.setDeltaMovement(Vec3.ZERO);
            falling.fallDistance = 0.0F;
            ids[i] = falling.getId();
            origins.add(falling.position());
        }

        player.getPersistentData().putInt("jn_blue_mode", BLUE_MODE_BLOCKS);
        player.getPersistentData().putIntArray("jn_blue_block_ids", ids);
        player.getPersistentData().putLong("jn_blue_until", level.getGameTime() + 200 + LapseBlue.G_PULL_END);

        // Рука к блокам с синим, блоки притягиваются; клиенты ведут их сами — плавно.
        LapseBlue.startGrab(player, ids, origins);
        player.displayClientMessage(
                Component.literal("BLUE // 5 БЛОКОВ ЗАХВАЧЕНО // ЛКМ: БРОСИТЬ")
                        .withStyle(ChatFormatting.AQUA),
                true
        );
        return true;
    }

    private static boolean castBlue(ServerPlayer player) {
        if (isHollowPurpleCasting(player)) return false;
        if (isBlueInteractionActive(player) ||
                player.getPersistentData().getInt("jn_blue_mode") == BLUE_MODE_PROJECTILE) {
            player.displayClientMessage(
                    Component.literal("BLUE // предыдущий захват ещё активен")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return false;
        }

        ServerLevel level = player.serverLevel();
        handSign(player);

        LivingEntity target = findBlueLivingTarget(level, player);
        if (target != null && LapseBlue.tryStart(player, target)) {
            return true;
        }

        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().normalize().scale(BLUE_RANGE));
        BlockHitResult blockHit = level.clip(new ClipContext(
                start, end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        if (blockHit.getType() == HitResult.Type.MISS) {
            player.displayClientMessage(
                    Component.literal("BLUE // нет цели под курсором")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return false;
        }

        return startBlueBlockHold(player, blockHit);
    }

    private static LivingEntity getBlueTarget(ServerLevel level, ServerPlayer player) {
        String raw = player.getPersistentData().getString("jn_blue_target_uuid");
        if (raw == null || raw.isEmpty()) return null;

        try {
            Entity e = level.getEntity(UUID.fromString(raw));
            return e instanceof LivingEntity living && living.isAlive() ? living : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void clearBlueState(ServerPlayer player) {
        LapseBlue.endGrab(player, false);
        player.getPersistentData().putInt("jn_blue_mode", BLUE_MODE_NONE);
        player.getPersistentData().remove("jn_blue_target_uuid");
        player.getPersistentData().remove("jn_blue_block_ids");
        player.getPersistentData().remove("jn_blue_until");
        player.getPersistentData().remove("jn_blue_projectile_until");
        player.getPersistentData().remove("jn_blue_owner_x");
        player.getPersistentData().remove("jn_blue_owner_y");
        player.getPersistentData().remove("jn_blue_owner_z");
        player.getPersistentData().remove("jn_blue_target_x");
        player.getPersistentData().remove("jn_blue_target_y");
        player.getPersistentData().remove("jn_blue_target_z");
    }

    private static void releaseBlueBlocks(ServerPlayer player, boolean launch) {
        ServerLevel level = player.serverLevel();
        int[] ids = player.getPersistentData().getIntArray("jn_blue_block_ids");

        if (!launch) {
            for (int id : ids) {
                Entity e = level.getEntity(id);
                if (e instanceof FallingBlockEntity falling) {
                    falling.noPhysics = false;
                    falling.setNoGravity(false);
                    falling.setDeltaMovement(new Vec3(rnd(-0.08, 0.08), 0.03, rnd(-0.08, 0.08)));
                    falling.fallDistance = 0.0F;
                }
            }
            clearBlueState(player);
            return;
        }

        Vec3 dir = player.getLookAngle().normalize();
        for (int i = 0; i < ids.length; i++) {
            Entity e = level.getEntity(ids[i]);
            if (e instanceof FallingBlockEntity falling) {
                falling.noPhysics = false;
                falling.setNoGravity(false);
                Vec3 spread = new Vec3(
                        rnd(-0.035, 0.035),
                        rnd(-0.020, 0.040),
                        rnd(-0.035, 0.035)
                );
                falling.setDeltaMovement(dir.scale(2.85).add(spread));
                falling.hurtMarked = true;
                falling.fallDistance = 0.0F;
            }
        }

        LapseBlue.endGrab(player, true);
        player.getPersistentData().putInt("jn_blue_mode", BLUE_MODE_PROJECTILE);
        player.getPersistentData().putLong("jn_blue_projectile_until", level.getGameTime() + 70);
        playSfx(level, player, SFX_BLUE, 0.82f, 1.32f);
    }

    private static void releaseBlueEntity(ServerPlayer player, boolean throwTarget) {
        ServerLevel level = player.serverLevel();
        LivingEntity target = getBlueTarget(level, player);

        if (target != null) {
            target.setDeltaMovement(Vec3.ZERO);

            if (throwTarget) {
                Vec3 dir = player.getLookAngle().normalize();

                player.getPersistentData().putBoolean("jn_blue_custom_damage", true);
                target.hurt(level.damageSources().playerAttack(player), 40.0F); // 20 сердец
                player.getPersistentData().putBoolean("jn_blue_custom_damage", false);

                target.setDeltaMovement(dir.scale(3.25).add(0.0, 0.48, 0.0));
                target.hurtMarked = true;

                Vec3 impact = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
                for (int i = 0; i < 28; i++) {
                    sendDust(level,
                            impact.add(rnd(-0.45, 0.45), rnd(-0.45, 0.45), rnd(-0.45, 0.45)),
                            new Vector3f(0.15f, 0.74f, 1.0f),
                            0.90f);
                }

                playImpactLayer(level, impact, 0.72f, 1.35f);
            }
        }

        player.setDeltaMovement(Vec3.ZERO);
        clearBlueState(player);
    }

    private static void bluePrimaryAction(ServerPlayer player) {
        int mode = player.getPersistentData().getInt("jn_blue_mode");

        if (mode == BLUE_MODE_BLOCKS) {
            if (LapseBlue.grabAge(player) >= 0 && LapseBlue.grabAge(player) < LapseBlue.G_PULL_END) return;
            releaseBlueBlocks(player, true);
        } else if (mode == BLUE_MODE_ENTITY) {
            releaseBlueEntity(player, true);
        }
    }

    private static void tickBlueState(ServerPlayer player, ServerLevel level, long now) {
        int mode = player.getPersistentData().getInt("jn_blue_mode");
        if (mode == BLUE_MODE_NONE) return;

        if (!hasGojoBlindfold(player) || !player.isAlive()) {
            if (mode == BLUE_MODE_BLOCKS) releaseBlueBlocks(player, false);
            else if (mode == BLUE_MODE_ENTITY) releaseBlueEntity(player, false);
            else clearBlueState(player);
            return;
        }

        if (mode == BLUE_MODE_BLOCKS) {
            player.setSprinting(false);

            if (now >= player.getPersistentData().getLong("jn_blue_until")) {
                releaseBlueBlocks(player, false);
                return;
            }

            int[] ids = player.getPersistentData().getIntArray("jn_blue_block_ids");
            for (int i = 0; i < ids.length && i < LapseBlue.GRAB_OFFSETS.length; i++) {
                Entity e = level.getEntity(ids[i]);
                if (!(e instanceof FallingBlockEntity falling)) continue;

                Vec3 pos = LapseBlue.grabTarget(player, i);
                falling.setPos(pos.x, pos.y, pos.z);
                falling.setDeltaMovement(Vec3.ZERO);
                falling.setNoGravity(true);
                falling.noPhysics = true;
                falling.fallDistance = 0.0F;
            }
            return;
        }

        if (mode == BLUE_MODE_ENTITY) {
            LivingEntity target = getBlueTarget(level, player);

            if (target == null || now >= player.getPersistentData().getLong("jn_blue_until")) {
                releaseBlueEntity(player, false);
                return;
            }

            double ox = player.getPersistentData().getDouble("jn_blue_owner_x");
            double oy = player.getPersistentData().getDouble("jn_blue_owner_y");
            double oz = player.getPersistentData().getDouble("jn_blue_owner_z");

            double tx = player.getPersistentData().getDouble("jn_blue_target_x");
            double ty = player.getPersistentData().getDouble("jn_blue_target_y");
            double tz = player.getPersistentData().getDouble("jn_blue_target_z");

            player.teleportTo(ox, oy, oz);
            player.setDeltaMovement(Vec3.ZERO);
            player.setSprinting(false);
            player.fallDistance = 0.0F;

            target.teleportTo(tx, ty, tz);
            target.setDeltaMovement(Vec3.ZERO);
            target.setSprinting(false);
            target.fallDistance = 0.0F;
            target.hurtMarked = true;

            Vec3 blueMid = player.getEyePosition().lerp(
                    target.position().add(0.0, target.getBbHeight() * 0.55, 0.0),
                    0.55
            );
            if (now % 2 == 0) {
                sendDust(level, blueMid, new Vector3f(0.16f, 0.70f, 1.0f), 0.66f);
            }
            if (now % 4 == 0) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, blueMid.x, blueMid.y, blueMid.z, 1, 0.2, 0.2, 0.2, 0.02);
            }
            return;
        }

        if (mode == BLUE_MODE_PROJECTILE) {
            int[] ids = player.getPersistentData().getIntArray("jn_blue_block_ids");
            LivingEntity hit = null;

            // Вокруг летящих блоков — только мелкие синие искры и шлейф, без старого PNG-шара.
            for (int id : ids) {
                Entity visualEntity = level.getEntity(id);
                if (!(visualEntity instanceof FallingBlockEntity falling) || !falling.isAlive()) continue;
                Vec3 c = falling.position().add(0.0, 0.5, 0.0);
                Vec3 v = falling.getDeltaMovement();
                for (int k = 0; k < 2; k++) {
                    Vec3 p = c.add(rnd(-0.55, 0.55), rnd(-0.55, 0.55), rnd(-0.55, 0.55));
                    sendDust(level, p, k == 0 ? new Vector3f(0.20f, 0.78f, 1.0f) : new Vector3f(0.55f, 0.92f, 1.0f),
                            (float) rnd(0.32, 0.55));
                }
                Vec3 tail = c.subtract(v.scale(0.35));
                sendDust(level, tail, new Vector3f(0.10f, 0.55f, 1.0f), 0.45f);
                if (now % 3 == 0) {
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 1, 0.35, 0.35, 0.35, 0.02);
                }
            }

            for (int id : ids) {
                Entity e = level.getEntity(id);
                if (!(e instanceof FallingBlockEntity falling) || !falling.isAlive()) continue;

                List<LivingEntity> hits = level.getEntitiesOfClass(
                        LivingEntity.class,
                        falling.getBoundingBox().inflate(0.65),
                        living -> living.isAlive() && living != player
                );

                if (!hits.isEmpty()) {
                    hit = hits.get(0);
                    break;
                }
            }

            if (hit != null) {
                player.getPersistentData().putBoolean("jn_blue_custom_damage", true);
                hit.hurt(level.damageSources().playerAttack(player), 20.0F); // 10 сердец
                player.getPersistentData().putBoolean("jn_blue_custom_damage", false);

                Vec3 dir = player.getLookAngle().normalize();
                hit.setDeltaMovement(hit.getDeltaMovement().add(dir.scale(1.25)).add(0.0, 0.28, 0.0));
                hit.hurtMarked = true;

                Vec3 impact = hit.position().add(0.0, hit.getBbHeight() * 0.5, 0.0);
                // Удар блоками: синие искры и вспышка (без старых квадратных частиц).
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, impact.x, impact.y, impact.z, 22, 0.35, 0.35, 0.35, 0.28);
                level.sendParticles(ParticleTypes.FLASH, impact.x, impact.y, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
                playImpactLayer(level, impact, 0.62f, 1.42f);

                for (int id : ids) {
                    Entity e = level.getEntity(id);
                    if (e instanceof FallingBlockEntity falling) {
                        falling.noPhysics = false;
                        falling.setNoGravity(false);
                        falling.setDeltaMovement(falling.getDeltaMovement().scale(0.38)
                                .add(rnd(-0.12, 0.12), 0.10, rnd(-0.12, 0.12)));
                    }
                }

                clearBlueState(player);
                return;
            }

            if (now >= player.getPersistentData().getLong("jn_blue_projectile_until")) {
                clearBlueState(player);
            }
        }
    }


    private static final int MAX_BLUE_PHASE_NONE = 0;
    private static final int MAX_BLUE_PHASE_FORMING = 1;
    private static final int MAX_BLUE_PHASE_ACTIVE = 2;
    private static final int MAX_BLUE_PHASE_FADING = 3;

    /** Появление — 2 секунды; затягивать начинает уже во время появления. */
    private static final long MAX_BLUE_FORM_TICKS = 40L;
    /** С какой доли появления шар уже тянет блоки и мобов. */
    private static final double MAX_BLUE_SUCK_FROM = 0.22;
    /** Радиус, с которого Максимальный Синий затягивает живых. */
    private static final double MAX_BLUE_PULL_RADIUS = 16.0;
    private static final long MAX_BLUE_ACTIVE_TICKS = 160L;
    private static final long MAX_BLUE_FADE_TICKS = 20L;
    // Reference scale: roughly twice the previous visual diameter.
    private static final double MAX_BLUE_RADIUS = 4.0;
    private static final double MAX_BLUE_ZONE_HALF = 4.5;
    private static final double MAX_BLUE_MIN_DISTANCE = 3.0;
    private static final double MAX_BLUE_MAX_DISTANCE = 20.0;
    /** 15 сердец за каждую секунду внутри. */
    private static final float MAX_BLUE_DAMAGE = 30.0F;

    private static final Map<UUID, List<MaxBlueSuctionBlock>> MAX_BLUE_SUCTION = new HashMap<>();

    // Swept-volume block capture. Fast aim changes cannot tunnel between ticks.
    private static final Map<UUID, ArrayDeque<BlockPos>> MAX_BLUE_PENDING_BLOCKS = new HashMap<>();
    private static final Map<UUID, Set<Long>> MAX_BLUE_PENDING_KEYS = new HashMap<>();
    private static final int MAX_BLUE_SWEEP_BUDGET = 512;
    private static final int MAX_BLUE_PENDING_LIMIT = 12000;
    private static final double MAX_BLUE_SWEEP_RADIUS = 5.50;
    private static final double MAX_BLUE_SWEEP_STEP = 0.90;

    private static class MaxBlueSuctionBlock {
        final int entityId;
        final Vec3 start;
        final long startTick;
        final double phaseOffset;

        MaxBlueSuctionBlock(int entityId, Vec3 start, long startTick, double phaseOffset) {
            this.entityId = entityId;
            this.start = start;
            this.startTick = startTick;
            this.phaseOffset = phaseOffset;
        }
    }

    static boolean isMaximumBlueActive(ServerPlayer player) {
        return player.getPersistentData().getInt("jn_max_blue_phase") != MAX_BLUE_PHASE_NONE;
    }

    private static double maxBlueSmooth(double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static Vec3 maxBlueFormationCenter(ServerPlayer player, double progress, double desiredDistance) {
        // Reference timing: a tiny seed is born next to the caster, then the
        // anomaly tears forward and expands instead of orbiting like a planet.
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-6) horizontal = new Vec3(0.0, 0.0, 1.0);
        horizontal = horizontal.normalize();
        Vec3 right = new Vec3(-horizontal.z, 0.0, horizontal.x);

        Vec3 seed = eye.add(look.scale(0.90)).add(right.scale(0.55)).add(0.0, -0.38, 0.0);
        Vec3 finalPos = eye.add(look.scale(desiredDistance));

        double travel = maxBlueSmooth((progress - 0.18) / 0.50);
        double handPulse = Math.sin(progress * Math.PI * 5.0) * 0.045 * (1.0 - travel);
        seed = seed.add(0.0, handPulse, 0.0);
        return seed.lerp(finalPos, travel);
    }

    private static double currentMaximumBlueRadius(ServerPlayer player, long now) {
        int phase = player.getPersistentData().getInt("jn_max_blue_phase");

        if (phase == MAX_BLUE_PHASE_FORMING) {
            long start = player.getPersistentData().getLong("jn_max_blue_phase_start");
            double p = Mth.clamp((now - start) / (double) MAX_BLUE_FORM_TICKS, 0.0, 1.0);

            // Reference appearance: tiny white-blue seed -> violent bloom ->
            // final large anomaly. The burst happens early instead of a boring
            // linear balloon growth.
            if (p < 0.18) {
                return 0.10 + 0.38 * maxBlueSmooth(p / 0.18);
            }

            double eruption = maxBlueSmooth((p - 0.18) / 0.32);
            double settle = maxBlueSmooth((p - 0.50) / 0.50);
            double earlyTarget = MAX_BLUE_RADIUS * 0.82;
            return 0.48
                    + (earlyTarget - 0.48) * eruption
                    + (MAX_BLUE_RADIUS - earlyTarget) * settle;
        }

        if (phase == MAX_BLUE_PHASE_ACTIVE) return MAX_BLUE_RADIUS;

        if (phase == MAX_BLUE_PHASE_FADING) {
            long fadeStart = player.getPersistentData().getLong("jn_max_blue_phase_start");
            double p = Mth.clamp((now - fadeStart) / (double) MAX_BLUE_FADE_TICKS, 0.0, 1.0);
            double startRadius = player.getPersistentData().getDouble("jn_max_blue_fade_radius");
            return Math.max(0.0, startRadius * (1.0 - maxBlueSmooth(p)));
        }

        return 0.0;
    }

    private static Vec3 currentMaximumBlueCenter(ServerPlayer player, long now) {
        int phase = player.getPersistentData().getInt("jn_max_blue_phase");

        if (phase == MAX_BLUE_PHASE_FADING) {
            return new Vec3(
                    player.getPersistentData().getDouble("jn_max_blue_fade_x"),
                    player.getPersistentData().getDouble("jn_max_blue_fade_y"),
                    player.getPersistentData().getDouble("jn_max_blue_fade_z")
            );
        }

        double distance = Mth.clamp(
                player.getPersistentData().getDouble("jn_max_blue_distance"),
                MAX_BLUE_MIN_DISTANCE,
                MAX_BLUE_MAX_DISTANCE
        );

        if (phase == MAX_BLUE_PHASE_FORMING) {
            long start = player.getPersistentData().getLong("jn_max_blue_phase_start");
            double p = Mth.clamp((now - start) / (double) MAX_BLUE_FORM_TICKS, 0.0, 1.0);
            return maxBlueFormationCenter(player, p, distance);
        }

        return player.getEyePosition().add(player.getLookAngle().normalize().scale(distance));
    }

    private static boolean startMaximumBlue(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return false;
        if (isHollowPurpleCasting(player)) return false;
        if (!hasGojoBlindfold(player)) {
            requireBlindfoldMessage(player);
            return false;
        }
        if (isMaximumBlueActive(player)) return false;

        long now = player.level().getGameTime();
        long cooldownUntil = player.getPersistentData().getLong("jn_cd_max_blue");
        if (now < cooldownUntil) {
            long ticksLeft = cooldownUntil - now;
            double seconds = Math.ceil(ticksLeft / 2.0) / 10.0;
            player.displayClientMessage(
                    Component.literal("Maximum Blue: перезарядка " + seconds + " сек.")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return false;
        }

        double cost = abilityCost(player, Ability.MAX_BLUE);
        if (!consumeEnergy(player, cost)) return false;

        player.getPersistentData().putInt("jn_max_blue_phase", MAX_BLUE_PHASE_FORMING);
        player.getPersistentData().putLong("jn_max_blue_phase_start", now);
        player.getPersistentData().putDouble("jn_max_blue_distance", 6.0);

        player.getPersistentData().putDouble("jn_max_blue_lock_x", player.getX());
        player.getPersistentData().putDouble("jn_max_blue_lock_y", player.getY());
        player.getPersistentData().putDouble("jn_max_blue_lock_z", player.getZ());

        Vec3 center = currentMaximumBlueCenter(player, now);
        player.getPersistentData().putDouble("jn_max_blue_x", center.x);
        player.getPersistentData().putDouble("jn_max_blue_y", center.y);
        player.getPersistentData().putDouble("jn_max_blue_z", center.z);

        setCooldown(player, Ability.MAX_BLUE, 220);
        playSfx(player.serverLevel(), player, SFX_MAX_BLUE, 1.15f, 0.84f);
        handSign(player);

        player.displayClientMessage(
                Component.literal("MAXIMUM BLUE // ФОРМИРОВАНИЕ")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                true
        );
        return true;
    }

    private static void adjustMaximumBlueDistance(ServerPlayer player, double delta) {
        if (!isMaximumBlueActive(player)) return;
        if (player.getPersistentData().getInt("jn_max_blue_phase") == MAX_BLUE_PHASE_FADING) return;

        double distance = player.getPersistentData().getDouble("jn_max_blue_distance");
        player.getPersistentData().putDouble(
                "jn_max_blue_distance",
                Mth.clamp(distance + delta, MAX_BLUE_MIN_DISTANCE, MAX_BLUE_MAX_DISTANCE)
        );
    }

    private static void beginMaximumBlueFade(ServerPlayer player) {
        int phase = player.getPersistentData().getInt("jn_max_blue_phase");
        if (phase == MAX_BLUE_PHASE_NONE || phase == MAX_BLUE_PHASE_FADING) return;

        long now = player.level().getGameTime();
        Vec3 center = currentMaximumBlueCenter(player, now);
        double radius = currentMaximumBlueRadius(player, now);

        player.getPersistentData().putInt("jn_max_blue_phase", MAX_BLUE_PHASE_FADING);
        player.getPersistentData().putLong("jn_max_blue_phase_start", now);
        player.getPersistentData().putDouble("jn_max_blue_fade_radius", Math.max(0.08, radius));
        player.getPersistentData().putDouble("jn_max_blue_fade_x", center.x);
        player.getPersistentData().putDouble("jn_max_blue_fade_y", center.y);
        player.getPersistentData().putDouble("jn_max_blue_fade_z", center.z);
    }

    private static void clearMaximumBlue(ServerPlayer player) {
        double lastX = player.getPersistentData().getDouble("jn_max_blue_x");
        double lastY = player.getPersistentData().getDouble("jn_max_blue_y");
        double lastZ = player.getPersistentData().getDouble("jn_max_blue_z");

        NETWORK.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new MaxBlueVisualPacket(
                        player.getUUID(),
                        false,
                        lastX, lastY, lastZ,
                        0.0f,
                        MAX_BLUE_PHASE_NONE
                )
        );

        List<MaxBlueSuctionBlock> visuals = MAX_BLUE_SUCTION.remove(player.getUUID());
        if (visuals != null) {
            for (MaxBlueSuctionBlock entry : visuals) {
                Entity e = player.serverLevel().getEntity(entry.entityId);
                if (e != null) e.discard();
            }
        }

        MAX_BLUE_PENDING_BLOCKS.remove(player.getUUID());
        MAX_BLUE_PENDING_KEYS.remove(player.getUUID());

        player.getPersistentData().putInt("jn_max_blue_phase", MAX_BLUE_PHASE_NONE);
        for (String key : new String[]{
                "jn_max_blue_phase_start","jn_max_blue_distance",
                "jn_max_blue_lock_x","jn_max_blue_lock_y","jn_max_blue_lock_z",
                "jn_max_blue_fade_radius","jn_max_blue_fade_x","jn_max_blue_fade_y","jn_max_blue_fade_z",
                "jn_max_blue_x","jn_max_blue_y","jn_max_blue_z",
                "jn_max_blue_sweep_ready","jn_max_blue_sweep_x","jn_max_blue_sweep_y","jn_max_blue_sweep_z"}) {
            player.getPersistentData().remove(key);
        }
    }

    private static void spawnMaximumBlueVisual(ServerLevel level, Vec3 center, double radius, long now, double fadeFactor) {
        // Максимальный Синий целиком рисует клиент (объёмная модель, молнии, обломки).
        // Старые квадратные частицы-пыль и искры вокруг шара убраны.
    }

    private static boolean canMaximumBlueConsume(ServerLevel level, ServerPlayer owner, BlockPos pos) {
        if (DomainExpansion.denyTechniqueEdit(level, pos, owner)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.hasBlockEntity()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getDestroySpeed(level, pos) < 0.0F) return false;
        if (state.is(Blocks.MOVING_PISTON) || state.is(Blocks.END_PORTAL) || state.is(Blocks.NETHER_PORTAL)) return false;

        return !isOwnerSafeBlock(owner, pos);
    }

    private static void consumeMaximumBlueBlocks(ServerPlayer owner, ServerLevel level, Vec3 center, long now) {
        var data = owner.getPersistentData();

        Vec3 previous = center;
        if (data.getBoolean("jn_max_blue_sweep_ready")) {
            previous = new Vec3(
                    data.getDouble("jn_max_blue_sweep_x"),
                    data.getDouble("jn_max_blue_sweep_y"),
                    data.getDouble("jn_max_blue_sweep_z")
            );
        }

        data.putBoolean("jn_max_blue_sweep_ready", true);
        data.putDouble("jn_max_blue_sweep_x", center.x);
        data.putDouble("jn_max_blue_sweep_y", center.y);
        data.putDouble("jn_max_blue_sweep_z", center.z);

        UUID ownerId = owner.getUUID();
        ArrayDeque<BlockPos> pending = MAX_BLUE_PENDING_BLOCKS.computeIfAbsent(ownerId, id -> new ArrayDeque<>());
        Set<Long> pendingKeys = MAX_BLUE_PENDING_KEYS.computeIfAbsent(ownerId, id -> new HashSet<>());

        // Capsule/swept sphere between the previous and current center. This is
        // the anti-tunnelling fix: a fast camera flick cannot jump over blocks.
        double travel = previous.distanceTo(center);
        int samples = Mth.clamp((int) Math.ceil(travel / MAX_BLUE_SWEEP_STEP), 1, 48);
        int range = (int) Math.ceil(MAX_BLUE_SWEEP_RADIUS);
        double radiusSqr = MAX_BLUE_SWEEP_RADIUS * MAX_BLUE_SWEEP_RADIUS;

        for (int sampleIndex = 0; sampleIndex <= samples && pending.size() < MAX_BLUE_PENDING_LIMIT; sampleIndex++) {
            double t = sampleIndex / (double) samples;
            // Current position first, then the swept history behind it.
            Vec3 sample = center.lerp(previous, t);
            int cx = Mth.floor(sample.x);
            int cy = Mth.floor(sample.y);
            int cz = Mth.floor(sample.z);

            for (int x = cx - range; x <= cx + range && pending.size() < MAX_BLUE_PENDING_LIMIT; x++) {
                for (int y = cy - range; y <= cy + range && pending.size() < MAX_BLUE_PENDING_LIMIT; y++) {
                    for (int z = cz - range; z <= cz + range && pending.size() < MAX_BLUE_PENDING_LIMIT; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        Vec3 blockCenter = Vec3.atCenterOf(pos);
                        if (blockCenter.distanceToSqr(sample) > radiusSqr) continue;

                        long key = pos.asLong();
                        if (pendingKeys.add(key)) pending.addLast(pos);
                    }
                }
            }
        }

        List<Long> visualPositions = new ArrayList<>();
        List<Integer> visualStates = new ArrayList<>();
        int budget = MAX_BLUE_SWEEP_BUDGET;

        while (budget-- > 0 && !pending.isEmpty()) {
            BlockPos pos = pending.removeFirst();
            pendingKeys.remove(pos.asLong());
            if (!canMaximumBlueConsume(level, owner, pos)) continue;

            BlockState state = level.getBlockState(pos);
            int stateId = net.minecraft.world.level.block.Block.getId(state);

            // Real world block disappears immediately, without drops. The client
            // renders a temporary copy spiralling into the white core.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
            visualPositions.add(pos.asLong());
            visualStates.add(stateId);
        }

        if (!visualPositions.isEmpty()) {
            NETWORK.send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> owner),
                    new MaxBlueDebrisBatchPacket(
                            ownerId, center.x, center.y, center.z,
                            visualPositions, visualStates
                    )
            );
        }
    }

    private static void tickMaximumBlueSuction(ServerPlayer owner, ServerLevel level, Vec3 center, long now) {
        List<MaxBlueSuctionBlock> active = MAX_BLUE_SUCTION.remove(owner.getUUID());
        if (active == null) return;

        for (MaxBlueSuctionBlock entry : active) {
            Entity entity = level.getEntity(entry.entityId);
            if (entity != null) entity.discard();
        }
    }

    private static void damageMaximumBlueZone(ServerPlayer owner, ServerLevel level, Vec3 center, long now) {
        AABB zone = new AABB(
                center.x - MAX_BLUE_ZONE_HALF, center.y - MAX_BLUE_ZONE_HALF, center.z - MAX_BLUE_ZONE_HALF,
                center.x + MAX_BLUE_ZONE_HALF, center.y + MAX_BLUE_ZONE_HALF, center.z + MAX_BLUE_ZONE_HALF);

        String suffix = owner.getUUID().toString().replace("-", "");
        String seenKey = "jn_mb_seen_" + suffix;
        String hitKey = "jn_mb_hit_" + suffix;

        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class, zone,
                e -> e.isAlive() && e != owner && !e.isSpectator());

        for (LivingEntity target : targets) {
            long lastSeen = target.getPersistentData().getLong(seenKey);
            long lastHit = target.getPersistentData().getLong(hitKey);

            if (DomainExpansion.separated(level, center, target.position().add(0.0, target.getBbHeight() * 0.5, 0.0))) continue;
            if (lastSeen < now - 1 || now - lastHit >= 20) {
                target.invulnerableTime = 0;
                owner.getPersistentData().putBoolean("jn_blue_custom_damage", true);
                try {
                    target.hurt(level.damageSources().playerAttack(owner), MAX_BLUE_DAMAGE);
                } finally {
                    owner.getPersistentData().putBoolean("jn_blue_custom_damage", false);
                }
                target.getPersistentData().putLong(hitKey, now);

            }

            target.getPersistentData().putLong(seenKey, now);
        }
    }

    /**
     * Максимальный Синий затягивает живых: чем ближе к шару, тем сильнее тянет; внутри шара
     * цель крутит вокруг ядра и держит в нём.
     */
    private static void pullMaximumBlueEntities(ServerPlayer owner, ServerLevel level, Vec3 center, double radius, double strength) {
        if (strength <= 0.0) return;
        AABB box = new AABB(center, center).inflate(MAX_BLUE_PULL_RADIUS);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != owner && !e.isSpectator()
                        && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand));
        for (LivingEntity e : list) {
            if (e instanceof Player p && (p.isCreative() && p.getAbilities().flying)) continue;
            if (LapseBlue.isControlled(e)) continue;
            Vec3 c = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
            Vec3 to = center.subtract(c);
            double dist = to.length();
            if (dist > MAX_BLUE_PULL_RADIUS) continue;
            if (DomainExpansion.separated(level, center, c)) continue;
            Vec3 dir = dist < 1.0E-4 ? Vec3.ZERO : to.scale(1.0 / dist);
            Vec3 v = e.getDeltaMovement();
            Vec3 want;
            double inner = Math.max(0.6, radius * 0.55);
            if (dist <= inner) {
                // В ядре: медленное вращение вокруг центра, без вылета наружу.
                Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
                want = to.scale(0.45).add(side.scale(0.18));
                v = v.lerp(want, 0.75);
            } else {
                double k = 1.0 - Mth.clamp((dist - inner) / (MAX_BLUE_PULL_RADIUS - inner), 0.0, 1.0);
                double speed = (0.22 + 1.05 * k * k) * strength;
                speed = Math.min(speed, dist * 0.5);
                Vec3 side = new Vec3(-dir.z, 0.0, dir.x).scale(0.10 * k * strength);
                want = dir.scale(speed).add(side);
                v = v.lerp(want, 0.28 + 0.42 * k);
            }
            // Гравитацию тяга перебивает: снизу вверх тоже тянет.
            if (!e.isNoGravity()) v = v.add(0.0, 0.08, 0.0);
            e.setDeltaMovement(v);
            e.hurtMarked = true;
            e.fallDistance = 0.0F;
            if (e instanceof Mob mob) mob.getNavigation().stop();
        }
    }

    private static void freezeMaximumBlueOwner(ServerPlayer player) {
        double x = player.getPersistentData().getDouble("jn_max_blue_lock_x");
        double y = player.getPersistentData().getDouble("jn_max_blue_lock_y");
        double z = player.getPersistentData().getDouble("jn_max_blue_lock_z");

        player.teleportTo(x, y, z);
        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
        player.fallDistance = 0.0F;
    }

    private static void tickMaximumBlueState(ServerPlayer player, ServerLevel level, long now) {
        int phase = player.getPersistentData().getInt("jn_max_blue_phase");
        if (phase == MAX_BLUE_PHASE_NONE) return;

        if (!player.isAlive() || !hasGojoBlindfold(player)) {
            clearMaximumBlue(player);
            return;
        }

        freezeMaximumBlueOwner(player);

        Vec3 center = currentMaximumBlueCenter(player, now);
        double radius = currentMaximumBlueRadius(player, now);
        player.getPersistentData().putDouble("jn_max_blue_x", center.x);
        player.getPersistentData().putDouble("jn_max_blue_y", center.y);
        player.getPersistentData().putDouble("jn_max_blue_z", center.z);

        double fadeFactor = 1.0;
        if (phase == MAX_BLUE_PHASE_FADING) {
            long fadeStart = player.getPersistentData().getLong("jn_max_blue_phase_start");
            fadeFactor = 1.0 - Mth.clamp((now - fadeStart) / (double) MAX_BLUE_FADE_TICKS, 0.0, 1.0);
        }

        NETWORK.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new MaxBlueVisualPacket(
                        player.getUUID(),
                        true,
                        center.x, center.y, center.z,
                        (float) radius,
                        phase
                )
        );

        spawnMaximumBlueVisual(level, center, radius, now, fadeFactor);
        tickMaximumBlueSuction(player, level, center, now);

        if (phase == MAX_BLUE_PHASE_FORMING) {
            long start = player.getPersistentData().getLong("jn_max_blue_phase_start");
            double formP = (now - start) / (double) MAX_BLUE_FORM_TICKS;
            // Шар не висит без дела: как только вырвался из руки — уже тянет блоки и живых.
            if (formP >= MAX_BLUE_SUCK_FROM) {
                double strength = maxBlueSmooth((formP - MAX_BLUE_SUCK_FROM) / 0.35);
                pullMaximumBlueEntities(player, level, center, radius, strength);
                damageMaximumBlueZone(player, level, center, now);
                consumeMaximumBlueBlocks(player, level, center, now);
            }
            if (now - start >= MAX_BLUE_FORM_TICKS) {
                player.getPersistentData().putInt("jn_max_blue_phase", MAX_BLUE_PHASE_ACTIVE);
                player.getPersistentData().putLong("jn_max_blue_phase_start", now);
                playSfx(level, player, SFX_MAX_BLUE, 1.30f, 0.76f);
                player.displayClientMessage(
                        Component.literal("MAXIMUM BLUE // ACTIVE")
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
            }
            return;
        }

        if (phase == MAX_BLUE_PHASE_ACTIVE) {
            pullMaximumBlueEntities(player, level, center, radius, 1.0);
            damageMaximumBlueZone(player, level, center, now);
            consumeMaximumBlueBlocks(player, level, center, now);

            long activeStart = player.getPersistentData().getLong("jn_max_blue_phase_start");
            if (now - activeStart >= MAX_BLUE_ACTIVE_TICKS) beginMaximumBlueFade(player);
            else if (now % 40 == 0) playSfx(level, player, SFX_MAX_BLUE, 0.40f, 0.70f);
            return;
        }

        if (phase == MAX_BLUE_PHASE_FADING) {
            long fadeStart = player.getPersistentData().getLong("jn_max_blue_phase_start");
            if (now - fadeStart >= MAX_BLUE_FADE_TICKS) clearMaximumBlue(player);
        }
    }

    private static void castMaxBlue(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        handSign(player);
        playSfx(level, player, SFX_MAX_BLUE, 1.35f, 0.82f);

        Vec3 look = player.getLookAngle().normalize();
        Vec3 center = player.getEyePosition().add(look.scale(10.0));
        spawnEnergySpiral(level, player.getEyePosition(), look, 10.0, 1.05, 4.2,
                new Vector3f(0.02f, 0.92f, 1.0f),
                new Vector3f(0.30f, 0.02f, 1.0f));
        spawnStylizedShockwave(level, center, 4.0, new Vector3f(0.02f, 0.78f, 1.0f));
        spawnRadialStar(level, center,
                new Vector3f(0.02f, 0.95f, 1.0f),
                new Vector3f(0.40f, 0.05f, 1.0f),
                18, 4.8);
        playEnergyLayer(level, center, 1.15f, 0.90f);
        spawnVfx(level, VFX_MAX_BLUE, center, 1);

        for (int r = 1; r <= 4; r++) {
            spawnNeonRing(level, center, r * 1.25, new Vector3f(0.02f, 0.65f, 1.0f));
        }
        spawnNeonSphere(level, center, 4.2,
                new Vector3f(0.02f, 0.85f, 1.0f),
                new Vector3f(0.35f, 0.02f, 1.0f));

        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(center, center).inflate(9.0),
                e -> e.isAlive() && e != player
        );

        for (LivingEntity target : targets) {
            Vec3 delta = center.subtract(target.position());
            if (delta.lengthSqr() < 0.01) continue;
            double strength = Mth.clamp(1.65 - delta.length() * 0.05, 0.65, 1.65);
            Vec3 pull = delta.normalize().scale(strength);
            target.setDeltaMovement(target.getDeltaMovement().add(pull.x, pull.y * 0.45 + 0.18, pull.z));
            target.hurtMarked = true;
            target.hurt(level.damageSources().playerAttack(player), 10.0F);
        }

        player.displayClientMessage(
                Component.literal("MAXIMUM BLUE").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                true
        );
    }

    private static final int PURPLE_MODE_NONE = 0;
    private static final int PURPLE_MODE_CASTING = 1;
    private static final int PURPLE_MODE_PROJECTILE = 2;

    private static final long PURPLE_CAST_TICKS = 100L; // 5 секунд
    private static final double PURPLE_SPEED = 160.0 / 80.0; // 160 блоков за 4 секунды при 20 TPS
    private static final double PURPLE_FULL_DISTANCE = 150.0;
    private static final double PURPLE_FADE_DISTANCE = 10.0;
    private static final double PURPLE_END_DISTANCE = PURPLE_FULL_DISTANCE + PURPLE_FADE_DISTANCE;
    private static final double PURPLE_TUNNEL_RADIUS = 6.0;
    private static final double PURPLE_PROJECTILE_RADIUS = 2.0; // 4x4x4
    private static final float PURPLE_DAMAGE = 400.0F; // 200 сердец

    // High speed creates many candidate blocks. Updates are intentionally budgeted.
    private static final int PURPLE_BLOCK_BUDGET_PER_TICK = 480;
    private static final double PURPLE_SLICE_STEP = 0.82;
    private static final double PURPLE_DISK_STEP = 0.82;
    private static final double PURPLE_TRAIL_NODE_STEP = 4.0;
    private static final long PURPLE_TRAIL_LIFETIME = 100L; // 5 секунд
    private static final int PURPLE_BLOCK_UPDATE_FLAGS = 2 | 16 | 32;

    private static final Map<UUID, HollowPurpleRuntime> PURPLE_RUNTIMES = new HashMap<>();

    private static class PurpleTrailNode {
        final Vec3 center;
        final double radius;
        final long bornTick;

        PurpleTrailNode(Vec3 center, double radius, long bornTick) {
            this.center = center;
            this.radius = radius;
            this.bornTick = bornTick;
        }
    }

    private static class HollowPurpleRuntime {
        final ArrayDeque<BlockPos> destructionQueue = new ArrayDeque<>();
        final Set<Long> visitedBlocks = new HashSet<>();
        final Set<UUID> damagedEntities = new HashSet<>();
        final List<PurpleTrailNode> trailNodes = new ArrayList<>();

        final Vec3 direction;
        final Vec3 right;
        final Vec3 up;

        double nextSliceDistance = 0.0;
        double nextTrailDistance = 0.0;
        int destroyedBlocks = 0;
        boolean projectileActive = true;

        HollowPurpleRuntime(Vec3 direction) {
            this.direction = direction.normalize();

            Vec3 reference = Math.abs(this.direction.y) > 0.92
                    ? new Vec3(1.0, 0.0, 0.0)
                    : new Vec3(0.0, 1.0, 0.0);

            Vec3 computedRight = this.direction.cross(reference);
            if (computedRight.lengthSqr() < 1.0E-6) {
                computedRight = new Vec3(1.0, 0.0, 0.0);
            }

            this.right = computedRight.normalize();
            this.up = this.right.cross(this.direction).normalize();
        }
    }

    static boolean isHollowPurpleCasting(ServerPlayer player) {
        return player.getPersistentData().getInt("jn_purple_mode") == PURPLE_MODE_CASTING;
    }

    private static double purpleTunnelRadius(double travelled) {
        if (travelled <= PURPLE_FULL_DISTANCE) return PURPLE_TUNNEL_RADIUS;
        if (travelled >= PURPLE_END_DISTANCE) return 0.0;

        double t = Mth.clamp(
                (travelled - PURPLE_FULL_DISTANCE) / PURPLE_FADE_DISTANCE,
                0.0,
                1.0
        );

        t = t * t * (3.0 - 2.0 * t);
        return PURPLE_TUNNEL_RADIUS * (1.0 - t);
    }

    private static double purpleBallRadius(double travelled) {
        if (travelled <= PURPLE_FULL_DISTANCE) return PURPLE_PROJECTILE_RADIUS;
        if (travelled >= PURPLE_END_DISTANCE) return 0.08;

        double t = Mth.clamp(
                (travelled - PURPLE_FULL_DISTANCE) / PURPLE_FADE_DISTANCE,
                0.0,
                1.0
        );

        t = t * t * (3.0 - 2.0 * t);
        return Math.max(0.08, PURPLE_PROJECTILE_RADIUS * (1.0 - t));
    }

    private static Vec3 purpleViewRight(ServerPlayer player) {
        Vec3 forward = player.getLookAngle().normalize();
        Vec3 reference = Math.abs(forward.y) > 0.92
                ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(0.0, 1.0, 0.0);

        Vec3 right = forward.cross(reference);
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1.0, 0.0, 0.0);
        return right.normalize();
    }

    private static void spawnPurpleCastOrb(
            ServerLevel level,
            Vec3 center,
            double radius,
            Vector3f color,
            long now
    ) {
        int points = Math.max(18, (int) (26 + radius * 12.0));
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));

        for (int i = 0; i < points; i++) {
            double y = 1.0 - (i / (double) Math.max(1, points - 1)) * 2.0;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = golden * i + now * 0.055;

            Vec3 p = center.add(
                    Math.cos(theta) * ring * radius,
                    y * radius,
                    Math.sin(theta) * ring * radius
            );

            sendDust(level, p, color, (float) Mth.clamp(0.42 + radius * 0.20, 0.42, 1.10));
        }
    }

    private static void spawnPurpleMergedSphere(
            ServerLevel level,
            Vec3 center,
            Vec3 direction,
            double radius,
            long now,
            boolean release
    ) {
        if (radius <= 0.02) return;

        int shellPoints = release ? 44 : 36;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));

        for (int i = 0; i < shellPoints; i++) {
            double y = 1.0 - (i / (double) (shellPoints - 1)) * 2.0;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = golden * i + now * 0.085;

            Vec3 p = center.add(
                    Math.cos(theta) * ring * radius,
                    y * radius,
                    Math.sin(theta) * ring * radius
            );

            Vector3f color;
            if (i % 7 == 0) color = new Vector3f(0.96f, 0.84f, 1.0f); // бело-фиолетовое ядро
            else if (i % 2 == 0) color = new Vector3f(0.64f, 0.02f, 1.0f);
            else color = new Vector3f(1.0f, 0.03f, 0.55f);

            sendDust(level, p, color, i % 7 == 0 ? 1.05f : 0.78f);
        }

        Vec3 reference = Math.abs(direction.y) > 0.92
                ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = direction.cross(reference);
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1.0, 0.0, 0.0);
        right = right.normalize();
        Vec3 up = right.cross(direction).normalize();

        // Distortion-like orbit rings around the sphere.
        for (int ringIndex = 0; ringIndex < 3; ringIndex++) {
            double orbit = radius * (1.12 + ringIndex * 0.16);
            int points = release ? 18 : 14;

            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2.0 * i / points +
                        now * (0.12 + ringIndex * 0.035) +
                        ringIndex * 1.75;

                Vec3 p = center
                        .add(right.scale(Math.cos(a) * orbit))
                        .add(up.scale(Math.sin(a) * orbit))
                        .add(direction.scale(Math.sin(a * 2.0 + ringIndex) * radius * 0.13));

                sendDust(
                        level,
                        p,
                        ringIndex == 1
                                ? new Vector3f(1.0f, 0.04f, 0.62f)
                                : new Vector3f(0.58f, 0.04f, 1.0f),
                        0.60f
                );
            }
        }

        if (now % 2 == 0) {
            spawnVfx(level, VFX_PURPLE, center, 1);
        }
    }

    private static boolean startHollowPurpleCast(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return false;
        if (!hasGojoBlindfold(player)) return false;
        if (player.getPersistentData().getInt("jn_purple_mode") != PURPLE_MODE_NONE) return false;

        // Long stationary casts cannot overlap other player-locking techniques.
        if (isMaximumBlueActive(player) || isBlueInteractionActive(player) ||
                player.getPersistentData().getInt("jn_red_mode") != RED_MODE_NONE) {
            player.displayClientMessage(
                    Component.literal("HOLLOW PURPLE // сначала заверши текущую технику")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return false;
        }

        long now = player.level().getGameTime();

        // Stop movement immediately, even if Purple is started during an aerial dash.
        if (player.getPersistentData().getInt("jn_dash_mode") != DASH_NONE) {
            finishDash(player, false);
        }
        player.getPersistentData().putBoolean("jn_super_speed", false);

        player.getPersistentData().putInt("jn_purple_mode", PURPLE_MODE_CASTING);
        player.getPersistentData().putLong("jn_purple_started", now);
        player.getPersistentData().putBoolean("jn_purple_energy_reserved", true);

        player.getPersistentData().putDouble("jn_purple_lock_x", player.getX());
        player.getPersistentData().putDouble("jn_purple_lock_y", player.getY());
        player.getPersistentData().putDouble("jn_purple_lock_z", player.getZ());

        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
        player.fallDistance = 0.0F;

        handSign(player);
        playSfx(player.serverLevel(), player, SFX_PURPLE, 1.10f, 0.72f);
        playEnergyLayer(player.serverLevel(), player.getEyePosition(), 0.90f, 0.78f);

        player.displayClientMessage(
                Component.literal("HOLLOW PURPLE // CAST")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD),
                true
        );

        return true;
    }

    private static void cancelHollowPurpleCast(ServerPlayer player, boolean refundEnergy) {
        if (player.getPersistentData().getInt("jn_purple_mode") != PURPLE_MODE_CASTING) return;

        if (refundEnergy && player.getPersistentData().getBoolean("jn_purple_energy_reserved")) {
            setEnergy(player, getEnergy(player) + abilityCost(player, Ability.HOLLOW_PURPLE));
        }

        player.getPersistentData().putInt("jn_purple_mode", PURPLE_MODE_NONE);
        player.getPersistentData().remove("jn_purple_started");
        player.getPersistentData().remove("jn_purple_energy_reserved");
        player.getPersistentData().remove("jn_purple_lock_x");
        player.getPersistentData().remove("jn_purple_lock_y");
        player.getPersistentData().remove("jn_purple_lock_z");
    }

    /**
     * Игрока поймала территория: всё, что он заряжал или держал, обрывается
     * (без возврата энергии), полёт и рывок снимаются — висящие падают на пол.
     */
    static void interruptForStun(ServerPlayer player) {
        cancelRedCharge(player, false);
        cancelHollowPurpleCast(player, false);
        if (isMaximumBlueActive(player)) beginMaximumBlueFade(player);

        int blueMode = player.getPersistentData().getInt("jn_blue_mode");
        if (blueMode == BLUE_MODE_BLOCKS) releaseBlueBlocks(player, false);
        else if (blueMode == BLUE_MODE_ENTITY) releaseBlueEntity(player, false);

        if (player.getPersistentData().getInt("jn_dash_mode") != DASH_NONE) finishDash(player, false);
        player.getPersistentData().putBoolean("jn_super_speed", false);
        player.setSprinting(false);

        MaximumPurple.interrupt(player);
        LapseBlue.interrupt(player);
        JujutsuNeonFlightPatch.stopForTechnique(player);
        if (player.getAbilities().flying && !player.isSpectator()) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        player.fallDistance = 0.0F;
    }

    private static void freezeHollowPurpleOwner(ServerPlayer player) {
        double x = player.getPersistentData().getDouble("jn_purple_lock_x");
        double y = player.getPersistentData().getDouble("jn_purple_lock_y");
        double z = player.getPersistentData().getDouble("jn_purple_lock_z");

        player.teleportTo(x, y, z);
        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
        player.fallDistance = 0.0F;
        player.hurtMarked = true;
    }

    private static void spawnHollowPurpleCastScene(
            ServerPlayer player,
            ServerLevel level,
            long now,
            long elapsed
    ) {
        Vec3 eye = player.getEyePosition();
        Vec3 forward = player.getLookAngle().normalize();
        Vec3 right = purpleViewRight(player);
        Vec3 up = right.cross(forward).normalize();

        Vec3 backCenter = eye.subtract(forward.scale(1.30)).add(0.0, -0.18, 0.0);
        Vec3 redBack = backCenter.add(right.scale(-2.10));
        Vec3 blueBack = backCenter.add(right.scale(2.10));
        Vec3 mergePoint = eye.add(forward.scale(2.35)).add(0.0, -0.12, 0.0);

        if (elapsed < 38) {
            double grow = maxBlueSmooth(elapsed / 38.0);
            double orbRadius = 0.18 + grow * 1.05;

            spawnPurpleCastOrb(level, redBack, orbRadius, new Vector3f(1.0f, 0.01f, 0.05f), now);
            spawnPurpleCastOrb(level, blueBack, orbRadius, new Vector3f(0.02f, 0.72f, 1.0f), now);

            // Purple arcs between the two Limitless poles and the hands.
            for (int i = 0; i < 10; i++) {
                double t = rnd(0.0, 1.0);
                Vec3 p = redBack.lerp(blueBack, t)
                        .add(up.scale(rnd(-0.18, 0.18)))
                        .add(forward.scale(rnd(-0.12, 0.12)));
                sendDust(level, p, new Vector3f(0.72f, 0.02f, 1.0f), 0.52f);
            }
            return;
        }

        if (elapsed < 72) {
            double merge = maxBlueSmooth((elapsed - 38.0) / 34.0);

            Vec3 red = redBack.lerp(mergePoint, merge);
            Vec3 blue = blueBack.lerp(mergePoint, merge);
            double orbRadius = 1.22 - merge * 0.62;

            spawnPurpleCastOrb(level, red, orbRadius, new Vector3f(1.0f, 0.01f, 0.05f), now);
            spawnPurpleCastOrb(level, blue, orbRadius, new Vector3f(0.02f, 0.72f, 1.0f), now);

            Vec3 midpoint = red.lerp(blue, 0.5);
            double purpleRadius = 0.18 + merge * 0.70;
            spawnPurpleMergedSphere(level, midpoint, forward, purpleRadius, now, false);

            for (int i = 0; i < 12; i++) {
                Vec3 p = red.lerp(blue, rnd(0.0, 1.0))
                        .add(rnd(-0.16, 0.16), rnd(-0.16, 0.16), rnd(-0.16, 0.16));
                sendDust(level, p, new Vector3f(0.78f, 0.03f, 1.0f), 0.58f);
            }
            return;
        }

        double growth = maxBlueSmooth((elapsed - 72.0) / 28.0);
        double radius = 0.82 + growth * (PURPLE_PROJECTILE_RADIUS - 0.82);

        spawnPurpleMergedSphere(level, mergePoint, forward, radius, now, true);

        // Strong central white-purple glow as the sphere reaches 4x4x4.
        for (int i = 0; i < 8; i++) {
            Vec3 p = mergePoint.add(
                    rnd(-0.32, 0.32),
                    rnd(-0.32, 0.32),
                    rnd(-0.32, 0.32)
            );
            sendDust(level, p, new Vector3f(0.95f, 0.82f, 1.0f), 0.82f);
        }
    }

    private static void launchHollowPurple(ServerPlayer player) {
        if (player.getPersistentData().getInt("jn_purple_mode") != PURPLE_MODE_CASTING) return;

        ServerLevel level = player.serverLevel();
        Vec3 direction = player.getLookAngle().normalize();
        Vec3 origin = player.getEyePosition().add(direction.scale(2.35));
        Vec3 velocity = direction.scale(PURPLE_SPEED);

        player.getPersistentData().putInt("jn_purple_mode", PURPLE_MODE_PROJECTILE);
        player.getPersistentData().putDouble("jn_purple_x", origin.x);
        player.getPersistentData().putDouble("jn_purple_y", origin.y);
        player.getPersistentData().putDouble("jn_purple_z", origin.z);
        player.getPersistentData().putDouble("jn_purple_vx", velocity.x);
        player.getPersistentData().putDouble("jn_purple_vy", velocity.y);
        player.getPersistentData().putDouble("jn_purple_vz", velocity.z);
        player.getPersistentData().putDouble("jn_purple_distance", 0.0);

        player.getPersistentData().remove("jn_purple_started");
        player.getPersistentData().remove("jn_purple_energy_reserved");
        player.getPersistentData().remove("jn_purple_lock_x");
        player.getPersistentData().remove("jn_purple_lock_y");
        player.getPersistentData().remove("jn_purple_lock_z");

        PURPLE_RUNTIMES.put(player.getUUID(), new HollowPurpleRuntime(direction));

        // Unlock instantly on release. Air movement/dashes can be used again immediately.
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.hurtMarked = true;

        playSfx(level, player, SFX_PURPLE, 1.75f, 0.60f);
        // Spatial shock/rings are rendered by HollowPurpleReferenceClient.
        playImpactLayer(level, origin, 1.20f, 0.72f);

        player.displayClientMessage(
                Component.literal("HOLLOW PURPLE // RELEASE")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD),
                true
        );
    }

    private static void clearHollowPurpleProjectile(ServerPlayer player) {
        HollowPurpleRuntime runtime = PURPLE_RUNTIMES.get(player.getUUID());
        if (runtime != null) runtime.projectileActive = false;

        player.getPersistentData().putInt("jn_purple_mode", PURPLE_MODE_NONE);
        player.getPersistentData().remove("jn_purple_x");
        player.getPersistentData().remove("jn_purple_y");
        player.getPersistentData().remove("jn_purple_z");
        player.getPersistentData().remove("jn_purple_vx");
        player.getPersistentData().remove("jn_purple_vy");
        player.getPersistentData().remove("jn_purple_vz");
        player.getPersistentData().remove("jn_purple_distance");
    }

    private static boolean isPurpleProtectedTechnicalBlock(BlockState state) {
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

    private static boolean canPurpleAnnihilate(
            ServerPlayer owner,
            ServerLevel level,
            BlockPos pos,
            BlockState state
    ) {
        if (DomainExpansion.denyTechniqueEdit(level, pos, owner)) return false;
        if (state.isAir() && state.getFluidState().isEmpty()) return false;
        if (isOwnerSafeBlock(owner, pos)) return false;
        if (isPurpleProtectedTechnicalBlock(state)) return false;

        // Purple explicitly erases bedrock, fluids, containers and all ordinary world blocks.
        if (state.is(Blocks.BEDROCK)) return true;
        if (!state.getFluidState().isEmpty()) return true;

        return true;
    }

    private static void enqueuePurpleSlice(
            ServerPlayer owner,
            ServerLevel level,
            HollowPurpleRuntime runtime,
            Vec3 center,
            double radius
    ) {
        if (radius <= 0.05) return;

        double padded = radius + 0.28;
        double radiusSq = padded * padded;

        for (double a = -padded; a <= padded + 1.0E-6; a += PURPLE_DISK_STEP) {
            for (double b = -padded; b <= padded + 1.0E-6; b += PURPLE_DISK_STEP) {
                if (a * a + b * b > radiusSq) continue;

                Vec3 sample = center
                        .add(runtime.right.scale(a))
                        .add(runtime.up.scale(b));

                BlockPos pos = BlockPos.containing(sample);
                if (!level.hasChunkAt(pos)) continue;

                long key = pos.asLong();
                if (!runtime.visitedBlocks.add(key)) continue;

                BlockState state = level.getBlockState(pos);
                if (!canPurpleAnnihilate(owner, level, pos, state)) continue;

                runtime.destructionQueue.addLast(pos.immutable());
            }
        }
    }

    private static void schedulePurpleTunnel(
            ServerPlayer owner,
            ServerLevel level,
            HollowPurpleRuntime runtime,
            Vec3 segmentStart,
            Vec3 segmentEnd,
            double startDistance,
            double endDistance
    ) {
        double segmentLength = segmentStart.distanceTo(segmentEnd);
        if (segmentLength < 1.0E-5) return;

        while (runtime.nextSliceDistance <= endDistance + 1.0E-6 &&
                runtime.nextSliceDistance <= PURPLE_END_DISTANCE + 1.0E-6) {

            if (runtime.nextSliceDistance + 1.0E-6 < startDistance) {
                runtime.nextSliceDistance += PURPLE_SLICE_STEP;
                continue;
            }

            double local = (runtime.nextSliceDistance - startDistance) / segmentLength;
            local = Mth.clamp(local, 0.0, 1.0);

            Vec3 center = segmentStart.lerp(segmentEnd, local);
            double radius = purpleTunnelRadius(runtime.nextSliceDistance);

            enqueuePurpleSlice(owner, level, runtime, center, radius);
            runtime.nextSliceDistance += PURPLE_SLICE_STEP;
        }
    }

    private static void schedulePurpleTrail(
            HollowPurpleRuntime runtime,
            Vec3 segmentStart,
            Vec3 segmentEnd,
            double startDistance,
            double endDistance,
            long now
    ) {
        double segmentLength = segmentStart.distanceTo(segmentEnd);
        if (segmentLength < 1.0E-5) return;

        while (runtime.nextTrailDistance <= endDistance + 1.0E-6 &&
                runtime.nextTrailDistance <= PURPLE_END_DISTANCE + 1.0E-6) {

            if (runtime.nextTrailDistance + 1.0E-6 < startDistance) {
                runtime.nextTrailDistance += PURPLE_TRAIL_NODE_STEP;
                continue;
            }

            double local = (runtime.nextTrailDistance - startDistance) / segmentLength;
            local = Mth.clamp(local, 0.0, 1.0);

            Vec3 center = segmentStart.lerp(segmentEnd, local);
            double radius = Math.max(0.35, purpleTunnelRadius(runtime.nextTrailDistance));

            runtime.trailNodes.add(new PurpleTrailNode(center, radius, now));
            runtime.nextTrailDistance += PURPLE_TRAIL_NODE_STEP;
        }
    }

    private static void damagePurpleSegment(
            ServerPlayer owner,
            ServerLevel level,
            HollowPurpleRuntime runtime,
            Vec3 from,
            Vec3 to,
            double radius
    ) {
        if (radius <= 0.0) return;

        AABB search = new AABB(from, to).inflate(radius + 1.0);
        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                search,
                e -> e.isAlive() && e != owner && !e.isSpectator()
        );

        for (LivingEntity target : targets) {
            if (runtime.damagedEntities.contains(target.getUUID())) continue;

            Vec3 targetCenter = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
            double hitRadius = radius + Math.max(0.25, target.getBbWidth() * 0.5);

            if (pointSegmentDistanceSqr(targetCenter, from, to) > hitRadius * hitRadius) continue;

            runtime.damagedEntities.add(target.getUUID());

            owner.getPersistentData().putBoolean("jn_purple_custom_damage", true);
            try {
                target.hurt(level.damageSources().playerAttack(owner), PURPLE_DAMAGE);
            } finally {
                owner.getPersistentData().putBoolean("jn_purple_custom_damage", false);
            }

            for (int i = 0; i < 22; i++) {
                Vec3 p = targetCenter.add(
                        rnd(-0.65, 0.65),
                        rnd(-0.65, 0.65),
                        rnd(-0.65, 0.65)
                );
                sendDust(
                        level,
                        p,
                        i % 2 == 0
                                ? new Vector3f(0.66f, 0.02f, 1.0f)
                                : new Vector3f(1.0f, 0.03f, 0.58f),
                        0.82f
                );
            }
        }
    }

    private static void processPurpleDestructionQueue(
            ServerPlayer owner,
            ServerLevel level
    ) {
        HollowPurpleRuntime runtime = PURPLE_RUNTIMES.get(owner.getUUID());
        if (runtime == null) return;

        int budget = PURPLE_BLOCK_BUDGET_PER_TICK;

        while (budget-- > 0 && !runtime.destructionQueue.isEmpty()) {
            BlockPos pos = runtime.destructionQueue.pollFirst();
            if (pos == null || !level.hasChunkAt(pos)) continue;

            BlockState state = level.getBlockState(pos);
            if (!canPurpleAnnihilate(owner, level, pos, state)) continue;

            // Direct replacement = no item drops, no XP, container contents disappear too.
            level.setBlock(
                    pos,
                    Blocks.AIR.defaultBlockState(),
                    PURPLE_BLOCK_UPDATE_FLAGS
            );

            runtime.destroyedBlocks++;

            if ((runtime.destroyedBlocks & 31) == 0) {
                Vec3 p = Vec3.atCenterOf(pos);
                sendDust(
                        level,
                        p.add(rnd(-0.25, 0.25), rnd(-0.25, 0.25), rnd(-0.25, 0.25)),
                        new Vector3f(0.68f, 0.02f, 1.0f),
                        0.50f
                );
            }
        }
    }

    private static void tickPurpleResidualTrail(
            ServerPlayer owner,
            ServerLevel level,
            HollowPurpleRuntime runtime,
            long now
    ) {
        int index = 0;

        for (PurpleTrailNode node : runtime.trailNodes) {
            long age = now - node.bornTick;
            if (age < 0 || age >= PURPLE_TRAIL_LIFETIME) {
                index++;
                continue;
            }

            // Spread the rendering across four ticks instead of redrawing every trail node every tick.
            if (((now + index) & 3L) == 0L) {
                double fade = 1.0 - age / (double) PURPLE_TRAIL_LIFETIME;
                double edgeRadius = Math.max(0.25, node.radius * (0.88 + 0.10 * fade));

                for (int i = 0; i < 3; i++) {
                    double a = rnd(0.0, Math.PI * 2.0);
                    Vec3 p = node.center
                            .add(runtime.right.scale(Math.cos(a) * edgeRadius))
                            .add(runtime.up.scale(Math.sin(a) * edgeRadius))
                            .add(runtime.direction.scale(rnd(-0.35, 0.35)));

                    sendDust(
                            level,
                            p,
                            i % 2 == 0
                                    ? new Vector3f(0.56f, 0.02f, 1.0f)
                                    : new Vector3f(0.95f, 0.03f, 0.66f),
                            (float) (0.30 + fade * 0.42)
                    );
                }

                if ((now + index) % 20 == 0) {
                    level.sendParticles(
                            ParticleTypes.ELECTRIC_SPARK,
                            node.center.x, node.center.y, node.center.z,
                            1,
                            edgeRadius * 0.20, edgeRadius * 0.20, edgeRadius * 0.20,
                            0.03
                    );
                }
            }
            index++;
        }

        runtime.trailNodes.removeIf(node -> now - node.bornTick >= PURPLE_TRAIL_LIFETIME);
    }

    private static void dissolveHollowPurple(
            ServerPlayer owner,
            ServerLevel level,
            Vec3 center
    ) {
        HollowPurpleRuntime runtime = PURPLE_RUNTIMES.get(owner.getUUID());
        if (runtime != null) runtime.projectileActive = false;

        for (int i = 0; i < 62; i++) {
            double theta = rnd(0.0, Math.PI * 2.0);
            double phi = Math.acos(rnd(-1.0, 1.0));
            double radius = rnd(0.15, 2.3);

            Vec3 p = center.add(
                    Math.sin(phi) * Math.cos(theta) * radius,
                    Math.cos(phi) * radius,
                    Math.sin(phi) * Math.sin(theta) * radius
            );

            sendDust(
                    level,
                    p,
                    i % 2 == 0
                            ? new Vector3f(0.60f, 0.02f, 1.0f)
                            : new Vector3f(1.0f, 0.03f, 0.58f),
                    0.65f
            );
        }

        spawnStylizedShockwave(level, center, 2.6, new Vector3f(0.72f, 0.02f, 1.0f));
        clearHollowPurpleProjectile(owner);
    }

    private static void tickHollowPurpleState(
            ServerPlayer player,
            ServerLevel level,
            long now
    ) {
        HollowPurpleRuntime runtime = PURPLE_RUNTIMES.get(player.getUUID());

        if (runtime != null) {
            processPurpleDestructionQueue(player, level);
            tickPurpleResidualTrail(player, level, runtime, now);

            if (!runtime.projectileActive &&
                    runtime.destructionQueue.isEmpty() &&
                    runtime.trailNodes.isEmpty()) {
                PURPLE_RUNTIMES.remove(player.getUUID());
                runtime = null;
            }
        }

        int mode = player.getPersistentData().getInt("jn_purple_mode");
        if (mode == PURPLE_MODE_NONE) return;

        if (mode == PURPLE_MODE_CASTING) {
            if (!player.isAlive() || !hasGojoBlindfold(player)) {
                cancelHollowPurpleCast(player, true);
                return;
            }

            freezeHollowPurpleOwner(player);

            long started = player.getPersistentData().getLong("jn_purple_started");
            long elapsed = Math.max(0L, now - started);

            // Reference renderer owns the complete cast scene client-side.

            if (elapsed >= PURPLE_CAST_TICKS) {
                launchHollowPurple(player);
            }
            return;
        }

        if (mode != PURPLE_MODE_PROJECTILE) return;

        Vec3 pos = new Vec3(
                player.getPersistentData().getDouble("jn_purple_x"),
                player.getPersistentData().getDouble("jn_purple_y"),
                player.getPersistentData().getDouble("jn_purple_z")
        );

        Vec3 velocity = new Vec3(
                player.getPersistentData().getDouble("jn_purple_vx"),
                player.getPersistentData().getDouble("jn_purple_vy"),
                player.getPersistentData().getDouble("jn_purple_vz")
        );

        Vec3 next = pos.add(velocity);

        // Стена территории непроницаема: Фиолетовый рассеивается о неё.
        if (DomainExpansion.separated(level, pos, next)) {
            dissolveHollowPurple(player, level, pos);
            return;
        }

        runtime = PURPLE_RUNTIMES.get(player.getUUID());
        if (runtime == null) {
            runtime = new HollowPurpleRuntime(velocity);
            PURPLE_RUNTIMES.put(player.getUUID(), runtime);
        }

        double startDistance = player.getPersistentData().getDouble("jn_purple_distance");
        double endDistance = startDistance + velocity.length();

        // Swept circular tunnel: high projectile speed cannot skip blocks between ticks.
        schedulePurpleTunnel(
                player,
                level,
                runtime,
                pos,
                next,
                startDistance,
                endDistance
        );

        schedulePurpleTrail(
                runtime,
                pos,
                next,
                startDistance,
                endDistance,
                now
        );

        double sampleDistance = Math.min(
                PURPLE_END_DISTANCE,
                Math.max(0.0, (startDistance + endDistance) * 0.5)
        );
        double damageRadius = purpleTunnelRadius(sampleDistance);

        if (damageRadius > 0.0) {
            damagePurpleSegment(
                    player,
                    level,
                    runtime,
                    pos,
                    next,
                    damageRadius
            );
        }

        // Projectile body/trail are rendered as procedural 3D geometry on the client.

        if (endDistance >= PURPLE_END_DISTANCE) {
            dissolveHollowPurple(player, level, next);
            return;
        }

        player.getPersistentData().putDouble("jn_purple_x", next.x);
        player.getPersistentData().putDouble("jn_purple_y", next.y);
        player.getPersistentData().putDouble("jn_purple_z", next.z);
        player.getPersistentData().putDouble("jn_purple_distance", endDistance);
    }


    // ======================================================================= Красный / Максимальный Красный
    // Таймлайн и сеть — RedTechnique, картинка — RedTechniqueClient.

    private static final int RED_MODE_NONE = 0;
    private static final int RED_MODE_CHARGING = 1;
    /** Анимация обычного Красного: шарик ещё в руке. */
    private static final int RED_MODE_CAST = 2;
    /** Анимация Максимального Красного: заряд, до выстрела. */
    private static final int RED_MODE_MAX_CAST = 3;

    private static final float RED_DAMAGE = 50.0F; // 25 сердец
    /** Вдвое мощнее прежнего (6). */
    private static final float RED_EXPLOSION_POWER = 12.0F;
    /** Радиус урона от взрыва — тоже вдвое больше. */
    private static final double RED_EXPLOSION_DAMAGE_RADIUS = 12.5;

    private static final float MAX_RED_DAMAGE = 100.0F; // 50 сердец

    // Ограничиваем количество world-updates за тик, чтобы вырезание тоннеля не роняло TPS.
    private static final int MAX_RED_BLOCK_BUDGET_PER_TICK = 220;
    private static final double MAX_RED_SLICE_STEP = 0.72;
    private static final double MAX_RED_DISK_STEP = 0.78;
    private static final int MAX_RED_BLOCK_UPDATE_FLAGS = 2 | 16 | 32;

    private static final Map<UUID, MaximumRedRuntime> MAX_RED_RUNTIMES = new HashMap<>();

    /** Летящие шарики обычного Красного (не мешают кастовать другие техники). */
    private static final List<RedShot> RED_SHOTS = new ArrayList<>();
    private static int redShotIds = 0;

    private static final class RedShot {
        final int id;
        final UUID ownerId;
        Vec3 pos;
        final Vec3 velocity;
        double travelled;

        RedShot(int id, UUID ownerId, Vec3 pos, Vec3 velocity) {
            this.id = id;
            this.ownerId = ownerId;
            this.pos = pos;
            this.velocity = velocity;
        }
    }

    private static class MaximumRedRuntime {
        final ArrayDeque<BlockPos> destructionQueue = new ArrayDeque<>();
        final Set<Long> visitedBlocks = new HashSet<>();
        final Set<UUID> damagedEntities = new HashSet<>();
        final Vec3 origin;
        final Vec3 direction;
        final Vec3 right;
        final Vec3 up;

        double front = 0.0;
        double nextSliceDistance = 1.0;
        int destroyedBlocks = 0;
        boolean beamActive = true;

        MaximumRedRuntime(Vec3 origin, Vec3 direction) {
            this.origin = origin;
            this.direction = direction.normalize();

            Vec3 reference = Math.abs(this.direction.y) > 0.92
                    ? new Vec3(1.0, 0.0, 0.0)
                    : new Vec3(0.0, 1.0, 0.0);

            Vec3 computedRight = this.direction.cross(reference);
            if (computedRight.lengthSqr() < 1.0E-6) computedRight = new Vec3(1.0, 0.0, 0.0);

            this.right = computedRight.normalize();
            this.up = this.right.cross(this.direction).normalize();
        }
    }

    /** Пакет с клиента: X нажата/отпущена. */
    static void redControl(ServerPlayer player, int action) {
        if (action != RedTechnique.ACTION_CANCEL && DomainExpansion.blocksActions(player)) {
            // Обездвиженный не стреляет: заряд просто гаснет.
            if (player.getPersistentData().getInt("jn_red_mode") == RED_MODE_CHARGING) cancelRedCharge(player, false);
            return;
        }
        switch (action) {
            case RedTechnique.ACTION_START -> startRedCharge(player);
            case RedTechnique.ACTION_RELEASE -> releaseRedCharge(player);
            default -> cancelRedCharge(player, true);
        }
    }

    private static boolean startRedCharge(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return false;
        if (isHollowPurpleCasting(player)) return false;

        if (!hasGojoBlindfold(player)) {
            requireBlindfoldMessage(player);
            return false;
        }

        if (player.getPersistentData().getInt("jn_red_mode") != RED_MODE_NONE) {
            return false;
        }

        long now = player.level().getGameTime();
        long cooldownUntil = player.getPersistentData().getLong("jn_cd_red");

        if (now < cooldownUntil) {
            long ticksLeft = cooldownUntil - now;
            double seconds = Math.ceil(ticksLeft / 2.0) / 10.0;
            player.displayClientMessage(
                    Component.literal("Перезарядка Red: " + seconds + " сек.")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return false;
        }

        double cost = abilityCost(player, Ability.RED);
        if (!consumeEnergy(player, cost)) return false;

        player.getPersistentData().putInt("jn_red_mode", RED_MODE_CHARGING);
        player.getPersistentData().putBoolean("jn_red_energy_reserved", true);
        player.getPersistentData().putLong("jn_red_started", now);
        RedTechnique.sendCharge(player, true);
        return true;
    }

    /** Заряд (до каста) гаснет. Начатый каст не трогаем. */
    private static void cancelRedCharge(ServerPlayer player, boolean refundEnergy) {
        int mode = player.getPersistentData().getInt("jn_red_mode");
        if (mode == RED_MODE_NONE) return;
        if (mode != RED_MODE_CHARGING && refundEnergy) return;

        if (refundEnergy && player.getPersistentData().getBoolean("jn_red_energy_reserved")) {
            setEnergy(player, getEnergy(player) + abilityCost(player, Ability.RED));
        }
        if (mode == RED_MODE_CHARGING) RedTechnique.sendCharge(player, false);
        clearRedCast(player);
    }

    private static void clearRedCast(ServerPlayer player) {
        player.getPersistentData().putInt("jn_red_mode", RED_MODE_NONE);
        player.getPersistentData().remove("jn_red_energy_reserved");
        player.getPersistentData().remove("jn_red_started");
        player.getPersistentData().remove("jn_red_cast_start");
        player.getPersistentData().remove("jn_red_lock_x");
        player.getPersistentData().remove("jn_red_lock_y");
        player.getPersistentData().remove("jn_red_lock_z");
    }

    private static void releaseRedCharge(ServerPlayer player) {
        if (player.getPersistentData().getInt("jn_red_mode") != RED_MODE_CHARGING) return;
        beginRedCast(player);
    }

    /** Отпустил раньше 2 секунд — анимация обычного Красного, бросок на R_FIRE. */
    private static void beginRedCast(ServerPlayer player) {
        long now = player.level().getGameTime();
        player.getPersistentData().putInt("jn_red_mode", RED_MODE_CAST);
        player.getPersistentData().putLong("jn_red_cast_start", now);
        player.getPersistentData().remove("jn_red_energy_reserved");
        setCooldown(player, Ability.RED, 140);
        playSfx(player.serverLevel(), player, SFX_RED_CAST, 1.6f, 1.0f);
        RedTechnique.sendCharge(player, false);
        RedTechnique.sendCast(player, false);
    }

    /** Держал 2 секунды — Максимальный Красный начинается сам. */
    private static void beginMaxRedCast(ServerPlayer player) {
        long now = player.level().getGameTime();
        player.getPersistentData().putInt("jn_red_mode", RED_MODE_MAX_CAST);
        player.getPersistentData().putLong("jn_red_cast_start", now);
        player.getPersistentData().remove("jn_red_energy_reserved");
        player.getPersistentData().putDouble("jn_red_lock_x", player.getX());
        player.getPersistentData().putDouble("jn_red_lock_y", player.getY());
        player.getPersistentData().putDouble("jn_red_lock_z", player.getZ());
        setCooldown(player, Ability.RED, 140 + RedTechnique.M_END);
        if (player.getPersistentData().getInt("jn_dash_mode") != DASH_NONE) finishDash(player, false);
        player.getPersistentData().putBoolean("jn_super_speed", false);
        player.setSprinting(false);
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        playSfx(player.serverLevel(), player, SFX_MAX_RED_CAST, 2.4f, 1.0f);
        RedTechnique.sendCharge(player, false);
        RedTechnique.sendCast(player, true);
    }

    /** Точка вылета шарика: от глаз вперёд (клиент сводит его с ладони). */
    private static Vec3 redLaunchPosition(ServerPlayer player) {
        return player.getEyePosition()
                .add(player.getLookAngle().normalize().scale(1.2))
                .add(0.0, -0.18, 0.0);
    }

    private static void launchNormalRed(ServerPlayer player) {
        Vec3 pos = redLaunchPosition(player);
        Vec3 velocity = player.getLookAngle().normalize().scale(RedTechnique.RED_SPEED);
        RedShot shot = new RedShot(++redShotIds, player.getUUID(), pos, velocity);
        RED_SHOTS.add(shot);
        RedTechnique.sendShot(player, shot.id, pos, velocity);
    }

    private static void fireMaximumRed(ServerPlayer player) {
        Vec3 dir = player.getLookAngle().normalize();
        Vec3 origin = player.getEyePosition().add(0.0, -0.25, 0.0);
        MaximumRedRuntime runtime = new MaximumRedRuntime(origin, dir);
        MAX_RED_RUNTIMES.put(player.getUUID(), runtime);
        RedTechnique.sendBeam(player, origin, dir);
        player.displayClientMessage(
                Component.literal("MAXIMUM RED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
    }

    private static LivingEntity findRedEntityHit(
            ServerLevel level,
            ServerPlayer owner,
            Vec3 from,
            Vec3 to
    ) {
        AABB sweep = new AABB(from, to).inflate(RedTechnique.RED_HIT_RADIUS + 0.55);

        return level.getEntitiesOfClass(
                        LivingEntity.class,
                        sweep,
                        e -> e.isAlive() && e != owner && !e.isSpectator()
                )
                .stream()
                .filter(e -> e.getBoundingBox()
                        .inflate(RedTechnique.RED_HIT_RADIUS + 0.25)
                        .clip(from, to)
                        .isPresent())
                .min(Comparator.comparingDouble(e -> from.distanceToSqr(e.getBoundingBox().getCenter())))
                .orElse(null);
    }

    private static void explodeRed(ServerPlayer owner, ServerLevel level, Vec3 center) {
        List<LivingEntity> victims = level.getEntitiesOfClass(
                LivingEntity.class,
                new AABB(center, center).inflate(RED_EXPLOSION_DAMAGE_RADIUS),
                e -> e.isAlive() && e != owner
                        && e.position().add(0.0, e.getBbHeight() * 0.5, 0.0).distanceTo(center) <= RED_EXPLOSION_DAMAGE_RADIUS
        );

        Map<BlockPos, BlockState> redOwnerSafe = snapshotOwnerSafeBlocks(owner);

        owner.getPersistentData().putBoolean("jn_red_explosion_blocks_only", true);
        try {
            level.explode(
                    owner,
                    center.x, center.y, center.z,
                    RED_EXPLOSION_POWER,
                    false,
                    Level.ExplosionInteraction.TNT
            );
        } finally {
            owner.getPersistentData().putBoolean("jn_red_explosion_blocks_only", false);
            restoreOwnerSafeBlocks(owner, redOwnerSafe);
        }

        owner.getPersistentData().putBoolean("jn_red_custom_damage", true);
        try {
            for (LivingEntity victim : victims) {
                if (!victim.isAlive()) continue;
                if (DomainExpansion.separated(level, center, victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0))) continue;
                victim.hurt(level.damageSources().playerAttack(owner), RED_DAMAGE);
                // Отталкивание от центра взрыва.
                Vec3 away = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0).subtract(center);
                double d = Math.max(0.6, away.length());
                Vec3 push = away.scale(1.0 / d).scale(1.8 * (1.0 - Math.min(1.0, d / RED_EXPLOSION_DAMAGE_RADIUS)) + 0.4);
                victim.setDeltaMovement(victim.getDeltaMovement().add(push.x, push.y * 0.6 + 0.35, push.z));
                victim.hurtMarked = true;
            }
        } finally {
            owner.getPersistentData().putBoolean("jn_red_custom_damage", false);
        }

        level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.2f, 0.62f);
        level.playSound(null, center.x, center.y, center.z, SFX_RED.get(), SoundSource.PLAYERS, 2.0f, 0.72f);
    }

    /** Летящие шарики Красного (у всех игроков). */
    private static void tickRedShots(MinecraftServer server) {
        if (RED_SHOTS.isEmpty()) return;
        Iterator<RedShot> it = RED_SHOTS.iterator();
        while (it.hasNext()) {
            RedShot shot = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(shot.ownerId);
            if (owner == null || !owner.isAlive()) {
                it.remove();
                continue;
            }
            ServerLevel level = owner.serverLevel();
            Vec3 pos = shot.pos;
            Vec3 next = pos.add(shot.velocity);

            // Стена территории непроницаема: Красный гаснет о неё.
            if (DomainExpansion.separated(level, pos, next)) {
                RedTechnique.sendShotEnd(level, shot.id, pos, false);
                it.remove();
                continue;
            }

            BlockHitResult blockHit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
            LivingEntity entityHit = findRedEntityHit(level, owner, pos, next);
            double blockDist = blockHit.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : pos.distanceToSqr(blockHit.getLocation());
            double entityDist = entityHit == null ? Double.POSITIVE_INFINITY : pos.distanceToSqr(entityHit.getBoundingBox().getCenter());

            if (blockDist != Double.POSITIVE_INFINITY || entityDist != Double.POSITIVE_INFINITY) {
                Vec3 impact = entityDist < blockDist && entityHit != null
                        ? entityHit.getBoundingBox().getCenter()
                        : blockHit.getLocation();
                it.remove();
                RedTechnique.sendShotEnd(level, shot.id, impact, true);
                explodeRed(owner, level, impact);
                continue;
            }

            shot.travelled += shot.velocity.length();
            shot.pos = next;
            if (shot.travelled >= RedTechnique.RED_MAX_DISTANCE || !level.isInWorldBounds(BlockPos.containing(next))) {
                // 150 блоков — просто исчезает.
                RedTechnique.sendShotEnd(level, shot.id, next, false);
                it.remove();
            }
        }
    }

    private static boolean canMaximumRedAnnihilate(
            ServerPlayer owner,
            ServerLevel level,
            BlockPos pos,
            BlockState state
    ) {
        if (DomainExpansion.denyTechniqueEdit(level, pos, owner)) return false;
        if (state.isAir() && state.getFluidState().isEmpty()) return false;
        if (isOwnerSafeBlock(owner, pos)) return false;

        // Воду/лаву удаляем независимо от твёрдости их BlockState.
        if (!state.getFluidState().isEmpty()) return true;

        // Bedrock, barrier, portal frame и остальные технически неразрушимые блоки пропускаем.
        return state.getDestroySpeed(level, pos) >= 0.0F;
    }

    private static void enqueueMaximumRedSlice(
            ServerPlayer owner,
            ServerLevel level,
            MaximumRedRuntime runtime,
            Vec3 center,
            double radius
    ) {
        if (radius <= 0.0) return;

        double paddedRadius = radius + 0.32;
        double radiusSq = paddedRadius * paddedRadius;

        for (double a = -paddedRadius; a <= paddedRadius + 1.0E-6; a += MAX_RED_DISK_STEP) {
            for (double b = -paddedRadius; b <= paddedRadius + 1.0E-6; b += MAX_RED_DISK_STEP) {
                if (a * a + b * b > radiusSq) continue;

                Vec3 sample = center
                        .add(runtime.right.scale(a))
                        .add(runtime.up.scale(b));

                BlockPos pos = BlockPos.containing(sample);

                if (!level.hasChunkAt(pos)) continue;

                long key = pos.asLong();
                if (!runtime.visitedBlocks.add(key)) continue;

                BlockState state = level.getBlockState(pos);
                if (!canMaximumRedAnnihilate(owner, level, pos, state)) continue;

                runtime.destructionQueue.addLast(pos.immutable());
            }
        }
    }

    private static double pointSegmentDistanceSqr(Vec3 point, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lenSq = ab.lengthSqr();

        if (lenSq < 1.0E-8) return point.distanceToSqr(a);

        double t = point.subtract(a).dot(ab) / lenSq;
        t = Mth.clamp(t, 0.0, 1.0);

        return point.distanceToSqr(a.add(ab.scale(t)));
    }

    /** Урон и отбрасывание всем, кого задел конус луча на участке [d0, d1]. */
    private static void damageMaximumRedBeam(ServerPlayer owner, ServerLevel level, MaximumRedRuntime runtime, double d0, double d1) {
        Vec3 from = runtime.origin.add(runtime.direction.scale(d0));
        Vec3 to = runtime.origin.add(runtime.direction.scale(d1));
        AABB search = new AABB(from, to).inflate(RedTechnique.BEAM_MAX_RADIUS + 1.5);

        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                search,
                e -> e.isAlive() && e != owner && !e.isSpectator()
        );

        for (LivingEntity target : targets) {
            if (runtime.damagedEntities.contains(target.getUUID())) continue;

            Vec3 targetCenter = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
            double along = targetCenter.subtract(runtime.origin).dot(runtime.direction);
            if (along < d0 - 1.0 || along > d1 + 1.0) continue;
            double radius = RedTechnique.beamRadius(Mth.clamp(along, 1.0, RedTechnique.BEAM_LENGTH));
            double allowance = Math.max(0.3, target.getBbWidth() * 0.5);
            double hitRadius = radius + allowance;

            if (pointSegmentDistanceSqr(targetCenter, from, to) > hitRadius * hitRadius) continue;
            if (DomainExpansion.separated(level, runtime.origin, targetCenter)) continue;

            runtime.damagedEntities.add(target.getUUID());

            owner.getPersistentData().putBoolean("jn_red_custom_damage", true);
            try {
                target.invulnerableTime = 0;
                target.hurt(level.damageSources().playerAttack(owner), MAX_RED_DAMAGE);
            } finally {
                owner.getPersistentData().putBoolean("jn_red_custom_damage", false);
            }
            // Луч уносит цель вперёд.
            target.setDeltaMovement(runtime.direction.scale(2.6).add(0.0, 0.45, 0.0));
            target.hurtMarked = true;
        }
    }

    private static void processMaximumRedDestructionQueue(
            ServerPlayer owner,
            ServerLevel level
    ) {
        MaximumRedRuntime runtime = MAX_RED_RUNTIMES.get(owner.getUUID());
        if (runtime == null) return;

        int budget = MAX_RED_BLOCK_BUDGET_PER_TICK;

        while (budget-- > 0 && !runtime.destructionQueue.isEmpty()) {
            BlockPos pos = runtime.destructionQueue.pollFirst();
            if (pos == null || !level.hasChunkAt(pos)) continue;

            BlockState state = level.getBlockState(pos);
            if (!canMaximumRedAnnihilate(owner, level, pos, state)) continue;

            // UPDATE_CLIENTS + UPDATE_KNOWN_SHAPE + UPDATE_SUPPRESS_DROPS.
            level.setBlock(
                    pos,
                    Blocks.AIR.defaultBlockState(),
                    MAX_RED_BLOCK_UPDATE_FLAGS
            );

            runtime.destroyedBlocks++;
        }

        if (!runtime.beamActive && runtime.destructionQueue.isEmpty()) {
            MAX_RED_RUNTIMES.remove(owner.getUUID());
        }
    }

    /** Луч Максимального Красного: фронт идёт вперёд BEAM_SPEED блоков за тик, за ним — тоннель и урон. */
    private static void tickMaximumRedBeam(ServerPlayer owner, ServerLevel level) {
        MaximumRedRuntime runtime = MAX_RED_RUNTIMES.get(owner.getUUID());
        if (runtime == null || !runtime.beamActive) return;

        double d0 = runtime.front;
        double d1 = Math.min(RedTechnique.BEAM_LENGTH, d0 + RedTechnique.BEAM_SPEED);

        // Луч не проходит сквозь стену территории.
        Vec3 a = runtime.origin.add(runtime.direction.scale(d0));
        Vec3 b = runtime.origin.add(runtime.direction.scale(d1));
        if (DomainExpansion.separated(level, a, b)) {
            runtime.beamActive = false;
            return;
        }

        while (runtime.nextSliceDistance <= d1 + 1.0E-6) {
            double d = runtime.nextSliceDistance;
            Vec3 center = runtime.origin.add(runtime.direction.scale(d));
            enqueueMaximumRedSlice(owner, level, runtime, center, RedTechnique.beamRadius(d));
            runtime.nextSliceDistance += MAX_RED_SLICE_STEP;
        }

        damageMaximumRedBeam(owner, level, runtime, d0, d1);
        runtime.front = d1;
        if (d1 >= RedTechnique.BEAM_LENGTH - 1.0E-6) runtime.beamActive = false;
    }

    private static void tickRedState(ServerPlayer player, ServerLevel level, long now) {
        // Луч и очередь разрушения продолжают работать независимо от состояния каста.
        tickMaximumRedBeam(player, level);
        processMaximumRedDestructionQueue(player, level);

        int mode = player.getPersistentData().getInt("jn_red_mode");
        if (mode == RED_MODE_NONE) return;

        if (!player.isAlive() || !hasGojoBlindfold(player)) {
            if (mode == RED_MODE_CHARGING) cancelRedCharge(player, true);
            else clearRedCast(player);
            return;
        }

        if (mode == RED_MODE_CHARGING) {
            long started = player.getPersistentData().getLong("jn_red_started");
            // Держал 2 секунды — Максимальный Красный сам, без отпускания.
            if (now - started >= RedTechnique.CHARGE_MAX_TICKS) beginMaxRedCast(player);
            return;
        }

        long age = now - player.getPersistentData().getLong("jn_red_cast_start");

        if (mode == RED_MODE_CAST) {
            if (age >= RedTechnique.R_FIRE) {
                launchNormalRed(player);
                clearRedCast(player);
            }
            return;
        }

        if (mode == RED_MODE_MAX_CAST) {
            // Стоит на месте, пока идёт техника (поворачивать голову можно).
            double x = player.getPersistentData().getDouble("jn_red_lock_x");
            double y = player.getPersistentData().getDouble("jn_red_lock_y");
            double z = player.getPersistentData().getDouble("jn_red_lock_z");
            if (player.position().distanceToSqr(x, y, z) > 0.36) {
                player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.setSprinting(false);
            player.fallDistance = 0.0F;

            if (age == RedTechnique.M_FIRE) fireMaximumRed(player);
            if (age >= RedTechnique.M_END) clearRedCast(player);
        }
    }

    /** Игрок сейчас в анимации Максимального Красного (стоит на месте). */
    static boolean isMaxRedCasting(ServerPlayer player) {
        return player.getPersistentData().getInt("jn_red_mode") == RED_MODE_MAX_CAST;
    }

    private static void castRed(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        handSign(player);
        playSfx(level, player, SFX_RED, 1.25f, 0.95f);
        Vec3 center = player.position().add(0, 1.0, 0);
        spawnStylizedShockwave(level, center, 5.6, new Vector3f(1.0f, 0.03f, 0.10f));
        spawnRadialStar(level, center,
                new Vector3f(1.0f, 0.02f, 0.08f),
                new Vector3f(1.0f, 0.36f, 0.02f),
                20, 5.5);
        playImpactLayer(level, center, 1.10f, 1.03f);
        spawnVfx(level, VFX_RED, center, 2);

        spawnNeonRing(
                level,
                center,
                6.0,
                new Vector3f(1.0f, 0.03f, 0.12f)
        );

        level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                center.x, center.y, center.z,
                70,
                1.0, 1.0, 1.0,
                0.35
        );

        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(6.5),
                e -> e.isAlive() && e != player
        );

        for (LivingEntity target : targets) {
            Vec3 away = target.position().subtract(player.position());
            if (away.lengthSqr() < 0.01) away = new Vec3(0.01, 0, 0);
            away = away.normalize();

            target.hurt(level.damageSources().playerAttack(player), 8.0F);
            target.setDeltaMovement(
                    target.getDeltaMovement().add(
                            away.x * 1.75,
                            0.55,
                            away.z * 1.75
                    )
            );
            target.hurtMarked = true;
        }

        player.displayClientMessage(
                Component.literal("RED — отталкивающий импульс").withStyle(ChatFormatting.RED),
                true
        );
    }

    private static void castCursedBarrage(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        player.swing(InteractionHand.MAIN_HAND, true);
        playSfx(level, player, SFX_BARRAGE, 1.25f, 1.05f);

        LivingEntity target = level.getEntitiesOfClass(
                        LivingEntity.class,
                        player.getBoundingBox().inflate(6.0),
                        e -> e.isAlive() && e != player && isInFront(player, e, 0.55)
                )
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);

        if (target != null) {
            Vec3 impact = target.position().add(0, target.getBbHeight() * 0.55, 0);
            spawnVfx(level, VFX_BARRAGE, impact, 3);
            for (int i = 0; i < 6; i++) {
                target.hurt(level.damageSources().playerAttack(player), 3.0F);
                for (int p = 0; p < 18; p++) {
                    sendDust(level,
                            impact.add(rnd(-0.75, 0.75), rnd(-0.8, 0.8), rnd(-0.75, 0.75)),
                            i % 2 == 0 ? new Vector3f(0.95f, 0.05f, 0.25f) : new Vector3f(0.55f, 0.0f, 1.0f),
                            1.25f);
                }
            }
            Vec3 knock = target.position().subtract(player.position()).normalize().scale(1.4);
            target.setDeltaMovement(target.getDeltaMovement().add(knock.x, 0.38, knock.z));
            target.hurtMarked = true;
        }

        player.displayClientMessage(
                Component.literal("CURSED BARRAGE").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                true
        );
    }

    private static void castInfinityToggle(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        boolean enabled = !player.getPersistentData().getBoolean("jn_infinity");
        player.getPersistentData().putBoolean("jn_infinity", enabled);
        playSfx(level, player, SFX_INFINITY, 1.15f, enabled ? 1.08f : 0.82f);
        if (enabled) spawnVfx(level, VFX_INFINITY, player.position().add(0, 1.0, 0), 1);
        if (enabled) {
            spawnStylizedShockwave(level, player.position().add(0, 1.0, 0), 2.0,
                    new Vector3f(0.10f, 0.82f, 1.0f));
            playEnergyLayer(level, player.position().add(0, 1.0, 0), 0.62f, 1.35f);
        }

        for (int r = 0; r < 3; r++) {
            spawnNeonRing(level, player.position().add(0, 1.0, 0), 1.15 + r * 0.32,
                    new Vector3f(0.15f, 0.8f, 1.0f));
        }

        player.displayClientMessage(
                Component.literal(enabled ? "INFINITY: ON" : "INFINITY: OFF")
                        .withStyle(enabled ? ChatFormatting.AQUA : ChatFormatting.GRAY, ChatFormatting.BOLD),
                true
        );
    }

    private static void castRCT(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        handSign(player);
        playSfx(level, player, SFX_RCT, 1.05f, 1.1f);
        player.heal(10.0F);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1, false, false, true));
        spawnVfx(level, VFX_RCT, player.position().add(0, 1.0, 0), 1);
        spawnStylizedShockwave(level, player.position().add(0, 0.4, 0), 1.8,
                new Vector3f(0.25f, 1.0f, 0.70f));
        spawnEnergySpiral(level, player.position().add(0, 0.15, 0), new Vec3(0, 1, 0),
                2.8, 0.95, 3.0,
                new Vector3f(0.25f, 1.0f, 0.70f),
                new Vector3f(1.0f, 0.30f, 0.68f));
        level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.70f, 1.55f);

        for (int i = 0; i < 70; i++) {
            sendDust(level,
                    player.position().add(rnd(-0.8, 0.8), rnd(0.05, 1.9), rnd(-0.8, 0.8)),
                    i % 2 == 0 ? new Vector3f(0.35f, 1.0f, 0.8f) : new Vector3f(1.0f, 0.35f, 0.65f),
                    1.0f);
        }

        player.displayClientMessage(
                Component.literal("REVERSED CURSED TECHNIQUE").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                true
        );
    }

    private static final int DASH_NONE = 0;
    private static final int DASH_FRONT = 1;
    private static final int DASH_SIDE = 2;

    private static final long FRONT_DASH_TICKS = 14L;
    private static final long SIDE_DASH_TICKS = 4L;
    private static final float FRONT_DASH_DAMAGE = 16.0F; // 8 сердец

    private static void handleMovement(ServerPlayer player, MovementAction action) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        if (action != MovementAction.SPEED_OFF && DomainExpansion.blocksActions(player)) return;

        if (isHollowPurpleCasting(player)) {
            if (action == MovementAction.SPEED_OFF) {
                player.getPersistentData().putBoolean("jn_super_speed", false);
            }
            return;
        }

        if (action == MovementAction.SPEED_OFF) {
            player.getPersistentData().putBoolean("jn_super_speed", false);
            player.getPersistentData().remove("jn_speed_next_cost");
            player.getPersistentData().remove("jn_speed_last_x");
            player.getPersistentData().remove("jn_speed_last_y");
            player.getPersistentData().remove("jn_speed_last_z");
            return;
        }

        if (!hasGojoBlindfold(player)) {
            player.getPersistentData().putBoolean("jn_super_speed", false);
            if (action != MovementAction.SPEED_ON) requireBlindfoldMessage(player);
            return;
        }

        switch (action) {
            case FRONT_DASH -> startDash(player, 0);
            case BACK_DASH -> startDash(player, 2);
            case LEFT_DASH -> startDash(player, -1);
            case RIGHT_DASH -> startDash(player, 1);
            case SPEED_ON -> {
                player.getPersistentData().putBoolean("jn_super_speed", true);
                player.getPersistentData().putLong("jn_speed_next_cost", player.level().getGameTime() + 40);
                player.getPersistentData().putDouble("jn_speed_last_x", player.getX());
                player.getPersistentData().putDouble("jn_speed_last_y", player.getY());
                player.getPersistentData().putDouble("jn_speed_last_z", player.getZ());
            }
            case SPEED_OFF -> {
                player.getPersistentData().putBoolean("jn_super_speed", false);
                player.getPersistentData().remove("jn_speed_next_cost");
            }
        }
    }

    private static boolean dashHasGroundSupport(ServerPlayer player) {
        if (player.onGround()) return true;
        Vec3 start = player.position().add(0.0, 0.08, 0.0);
        Vec3 end = start.add(0.0, -0.36, 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        return hit.getType() != HitResult.Type.MISS;
    }

    private static void startDash(ServerPlayer player, int side) {
        if (player.getPersistentData().getInt("jn_dash_mode") != DASH_NONE) return;

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();

        // Air Dash does not exist. Every dash requires ground support.
        if (!dashHasGroundSupport(player)) return;

        long cooldown = player.getPersistentData().getLong("jn_dash_cd");
        if (now < cooldown) return;

        boolean longitudinalDash = side == 0 || side == 2;
        if (!consumeEnergy(player, longitudinalDash ? 3.0 : 4.0)) return;

        player.getPersistentData().putLong("jn_dash_cd", now + 10);
        player.getPersistentData().putLong("jn_dash_started", now);
        player.getPersistentData().putBoolean("jn_dash_hit", false);

        Vec3 look = player.getLookAngle().normalize();
        Vec3 dir;

        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            double yaw = Math.toRadians(player.getYRot());
            horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        horizontal = horizontal.normalize();

        if (side == 0 || side == 2) {
            // Longitudinal dash is horizontal and camera-relative. side=2 is reverse.
            dir = side == 2 ? horizontal.scale(-1.0) : horizontal;
            player.getPersistentData().putInt("jn_dash_mode", DASH_FRONT);
        } else {
            Vec3 right = new Vec3(-horizontal.z, 0.0, horizontal.x);
            dir = side < 0 ? right.scale(-1.0) : right;
            player.getPersistentData().putInt("jn_dash_mode", DASH_SIDE);
        }

        player.getPersistentData().putDouble("jn_dash_dx", dir.x);
        player.getPersistentData().putDouble("jn_dash_dy", dir.y);
        player.getPersistentData().putDouble("jn_dash_dz", dir.z);

        // Звук, анимация тела и эффекты рисуют клиенты (MovementFxClient); остальным — событие.
        MovementFx.broadcastDash(player, side);
    }

    private static boolean tryDashMove(ServerPlayer player, Vec3 delta, boolean allowStepUp) {
        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox();

        if (level.noCollision(player, box.move(delta))) {
            player.move(MoverType.SELF, delta);
            return true;
        }

        if (allowStepUp) {
            for (int h = 1; h <= 2; h++) {
                Vec3 stepped = delta.add(0.0, h, 0.0);
                if (level.noCollision(player, box.move(stepped))) {
                    player.move(MoverType.SELF, stepped);
                    return true;
                }
            }
        }

        return false;
    }

    private static LivingEntity findDashVictim(ServerPlayer player, Vec3 from, Vec3 to) {
        ServerLevel level = player.serverLevel();
        AABB sweep = new AABB(from, to).inflate(0.85);

        return level.getEntitiesOfClass(
                        LivingEntity.class,
                        sweep,
                        e -> e.isAlive() && e != player && !e.isSpectator()
                )
                .stream()
                .filter(e -> e.getBoundingBox().inflate(0.35).clip(from, to).isPresent())
                .min(Comparator.comparingDouble(e -> from.distanceToSqr(e.getBoundingBox().getCenter())))
                .orElse(null);
    }

    private static void spawnFrontDashImpact(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 origin = player.position().add(0.0, 0.35, 0.0);

        BlockHitResult hit = level.clip(new ClipContext(
                origin,
                origin.add(look.scale(2.4)),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));

        Vec3 center = hit.getType() == HitResult.Type.MISS
                ? player.position().add(look.scale(0.9)).add(0.0, 0.1, 0.0)
                : hit.getLocation();

        BlockPos bp = hit.getType() == HitResult.Type.MISS
                ? BlockPos.containing(center.x, player.getY() - 0.1, center.z)
                : hit.getBlockPos();

        BlockState state = level.getBlockState(bp);

        // Визуальная "царапина/удар": блоки не ломаются.
        for (int ring = 0; ring < 3; ring++) {
            double radius = 0.45 + ring * 0.42;
            int points = 18 + ring * 8;
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2.0 * i / points;
                Vec3 p = center.add(
                        Math.cos(a) * radius,
                        rnd(-0.04, 0.10),
                        Math.sin(a) * radius
                );
                sendDust(level, p, new Vector3f(0.72f, 0.88f, 1.0f), 0.50f);
            }
        }

        if (!state.isAir()) {
            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    center.x, center.y, center.z,
                    36,
                    0.55, 0.16, 0.55,
                    0.12
            );
        }

        level.playSound(
                null,
                center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS,
                0.55f,
                1.55f
        );
    }

    private static void finishDash(ServerPlayer player, boolean impactEffect) {
        // Торможение (пыль, комья) рисуют клиенты по таймингу дэша.

        player.getPersistentData().putInt("jn_dash_mode", DASH_NONE);
        player.getPersistentData().remove("jn_dash_started");
        player.getPersistentData().remove("jn_dash_dx");
        player.getPersistentData().remove("jn_dash_dy");
        player.getPersistentData().remove("jn_dash_dz");
        player.getPersistentData().remove("jn_dash_hit");
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
    }

    private static void tickDashState(ServerPlayer player, ServerLevel level, long now) {
        int mode = player.getPersistentData().getInt("jn_dash_mode");
        if (mode == DASH_NONE) return;

        if (!player.isAlive() || !hasGojoBlindfold(player)) {
            finishDash(player, false);
            return;
        }

        long start = player.getPersistentData().getLong("jn_dash_started");
        long elapsed = now - start;

        Vec3 dir = new Vec3(
                player.getPersistentData().getDouble("jn_dash_dx"),
                player.getPersistentData().getDouble("jn_dash_dy"),
                player.getPersistentData().getDouble("jn_dash_dz")
        );
        if (dir.lengthSqr() < 1.0E-5) {
            finishDash(player, false);
            return;
        }
        dir = dir.normalize();

        // Позицию двигает клиентский принудительный dash controller.
        // Сервер здесь только валидирует время и рассчитывает hit/damage.
        if (mode == DASH_FRONT) {
            if (elapsed >= FRONT_DASH_TICKS) {
                finishDash(player, !player.getPersistentData().getBoolean("jn_dash_hit"));
                return;
            }

            double t = elapsed / (double) FRONT_DASH_TICKS;
            double speed = 1.68 - 1.30 * t;
            Vec3 from = player.position();
            LivingEntity victim = findDashVictim(player, from, from.add(dir.scale(speed + 0.90)));

            if (victim != null) {
                victim.hurt(level.damageSources().playerAttack(player), FRONT_DASH_DAMAGE);
                Vec3 knock = dir.scale(1.35).add(0.0, 0.22, 0.0);
                victim.setDeltaMovement(victim.getDeltaMovement().add(knock));
                victim.hurtMarked = true;
                player.getPersistentData().putBoolean("jn_dash_hit", true);

                Vec3 hitPos = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
                spawnRadialStar(
                        level,
                        hitPos,
                        new Vector3f(0.65f, 0.90f, 1.0f),
                        new Vector3f(0.12f, 0.60f, 1.0f),
                        10,
                        2.2
                );
                finishDash(player, false);
                return;
            }

            return;
        }

        if (mode == DASH_SIDE) {
            if (elapsed >= SIDE_DASH_TICKS) {
                finishDash(player, false);
                return;
            }

            return;
        }

    }

    // Прыжки: обычный ванильный и заряженный на 10 блоков (заряд 0.75 с).
    private static final double NORMAL_JUMP_VELOCITY = 0.42;     // ~1.25 блока
    private static final double CHARGED_JUMP_VELOCITY = 1.55;    // ~13 блоков — взлёт
    private static final int JUMP_CHARGE_TICKS = 15;             // 0.75 секунды
    private static final int MAX_JUMPS = 4;                      // включая прыжок с земли

    private static void performChargedJump(ServerPlayer player, int tier) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        if (!hasGojoBlindfold(player)) {
            requireBlindfoldMessage(player);
            return;
        }

        int clamped = Mth.clamp(tier, 0, 1);
        boolean grounded = player.onGround();

        // ПРАВИЛО ПРЫЖКОВ:
        // - tier 0: обычный ванильный прыжок (~1.25 блока), на земле и в воздухе;
        // - tier 1: заряд 0.75 с, прыжок на 10 блоков, только с земли;
        // - не больше 4 прыжков подряд (включая прыжок с земли), сброс на земле/в воде.
        //   Лимит считает клиент; сервер ведёт счётчик для HUD.
        if (!grounded && clamped > 0) return;

        // Creative flight принадлежит Minecraft и не смешивается с нашими прыжками.
        if (player.getAbilities().flying) return;

        double yVelocity = clamped == 1 ? CHARGED_JUMP_VELOCITY : NORMAL_JUMP_VELOCITY;

        if (grounded || player.isInWater()) player.getPersistentData().putInt("jn_air_jumps", 0);
        player.getPersistentData().putInt("jn_air_jumps", player.getPersistentData().getInt("jn_air_jumps") + 1);

        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(
                velocity.x,
                yVelocity,
                velocity.z
        );
        player.hurtMarked = true;
        player.fallDistance = 0.0F;

        // Взлёт (присед, прыжок, пыль, вихрь, звук) рисуют клиенты — MovementFxClient.
    }

    private static void executeLongRangeTeleport(ServerPlayer player, BlockPos targetBlock, Direction face) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        if (isHollowPurpleCasting(player)) return;
        if (!hasGojoBlindfold(player)) {
            requireBlindfoldMessage(player);
            return;
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long cooldown = player.getPersistentData().getLong("jn_r_tp_cd");

        if (now < cooldown) {
            long ticksLeft = cooldown - now;
            double seconds = Math.ceil(ticksLeft / 2.0) / 10.0;
            player.displayClientMessage(
                    Component.literal("Телепорт: " + seconds + " сек.")
                            .withStyle(ChatFormatting.GRAY),
                    true
            );
            return;
        }

        if (!level.hasChunkAt(targetBlock)) return;

        BlockPos initial = targetBlock.relative(face);
        BlockPos safe = findSafeTeleportPosition(level, initial);
        if (safe == null) return;

        Vec3 oldPos = player.position();
        Vec3 destination = new Vec3(
                safe.getX() + 0.5,
                safe.getY(),
                safe.getZ() + 0.5
        );

        spawnTeleportBurst(level, oldPos.add(0.0, 0.9, 0.0));
        player.teleportTo(destination.x, destination.y, destination.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.hurtMarked = true;
        player.getPersistentData().putLong("jn_r_tp_cd", now + 80); // 4 секунды
        spawnTeleportBurst(level, destination.add(0.0, 0.9, 0.0));

        level.playSound(
                null,
                destination.x, destination.y, destination.z,
                SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                1.0f,
                1.05f
        );

        // Зона стана 4x4 вокруг точки приземления, владелец иммунен.
        AABB stunZone = new AABB(
                destination.x - 2.0, destination.y - 2.0, destination.z - 2.0,
                destination.x + 2.0, destination.y + 2.0, destination.z + 2.0
        );

        List<LivingEntity> targets = level.getEntitiesOfClass(
                LivingEntity.class,
                stunZone,
                e -> e.isAlive() && e != player && !e.isSpectator()
        );

        for (LivingEntity target : targets) {
            applyTeleportStun(target, now + 40); // 2 секунды
        }
    }

    private static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos preferred) {
        // Сначала точная клетка рядом с гранью попадания, затем небольшой безопасный поиск.
        int[][] offsets = {
                {0,0,0},
                {0,1,0},
                {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                {1,1,0},{-1,1,0},{0,1,1},{0,1,-1},
                {1,0,1},{1,0,-1},{-1,0,1},{-1,0,-1}
        };

        for (int[] o : offsets) {
            BlockPos feet = preferred.offset(o[0], o[1], o[2]);
            BlockPos head = feet.above();

            if (!level.hasChunkAt(feet) || !level.hasChunkAt(head)) continue;

            boolean feetPassable = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
            boolean headPassable = level.getBlockState(head).getCollisionShape(level, head).isEmpty();

            if (feetPassable && headPassable) return feet;
        }

        return null;
    }

    private static void spawnTeleportBurst(ServerLevel level, Vec3 center) {
        for (int ring = 0; ring < 3; ring++) {
            double radius = 0.75 + ring * 0.48;
            int points = 30 + ring * 10;

            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2.0 * i / points;
                Vec3 p = center.add(
                        Math.cos(a) * radius,
                        Math.sin(a * 2.0) * 0.15,
                        Math.sin(a) * radius
                );
                sendDust(
                        level,
                        p,
                        ring == 1
                                ? new Vector3f(0.52f, 0.08f, 1.0f)
                                : new Vector3f(0.08f, 0.78f, 1.0f),
                        0.72f
                );
            }
        }

        spawnVfx(level, VFX_TELEPORT, center, 1);
    }

    private static void applyTeleportStun(LivingEntity target, long until) {
        target.getPersistentData().putLong("jn_stun_until", until);
        target.getPersistentData().putDouble("jn_stun_x", target.getX());
        target.getPersistentData().putDouble("jn_stun_y", target.getY());
        target.getPersistentData().putDouble("jn_stun_z", target.getZ());
        target.setDeltaMovement(Vec3.ZERO);
        target.hurtMarked = true;
    }

    // Собственная физика перемещения. Никаких Potion MOVEMENT_SPEED.
    private static final double CUSTOM_WALK_BLOCKS_PER_TICK = 0.30; // ~3x vanilla walk
    private static final double CUSTOM_RUN_BLOCKS_PER_TICK = 1.20;  // сверхбег на Ctrl (было 2.40, /2)
    private static final long SUPER_RUN_COST_INTERVAL = 40L;        // 2 секунды
    private static final double SUPER_RUN_COST = 1.0;               // 1% CE

    private static Vec3 runInputDirection(ServerPlayer player) {
        float forwardInput = player.zza;
        float strafeInput = player.xxa;

        if (Math.abs(forwardInput) < 0.01F && Math.abs(strafeInput) < 0.01F) {
            Vec3 velocity = player.getDeltaMovement();
            Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
            return horizontal.lengthSqr() > 1.0E-4 ? horizontal.normalize() : Vec3.ZERO;
        }

        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        Vec3 dir = forward.scale(forwardInput).add(right.scale(strafeInput));
        return dir.lengthSqr() > 1.0E-4 ? dir.normalize() : Vec3.ZERO;
    }

    private static void trySuperRunStepUp(ServerPlayer player) {
        if (!player.horizontalCollision) return;

        Vec3 dir = runInputDirection(player);
        if (dir.lengthSqr() < 1.0E-4) return;

        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox();

        // Перепады в 1-2 блока автоматически преодолеваются.
        for (int h = 1; h <= 2; h++) {
            Vec3 step = new Vec3(dir.x * 0.58, h, dir.z * 0.58);
            if (level.noCollision(player, box.move(step))) {
                player.move(MoverType.SELF, step);
                player.setDeltaMovement(
                        player.getDeltaMovement().x,
                        Math.max(0.0, player.getDeltaMovement().y),
                        player.getDeltaMovement().z
                );
                player.hurtMarked = true;
                return;
            }
        }
        // Стена 3+ блока просто останавливает движение; режим сверхбега остаётся включён.
    }

    private static boolean isNaturalTreeLog(ServerLevel level, BlockPos start) {
        if (!level.getBlockState(start).is(BlockTags.LOGS)) return false;

        BlockState below = level.getBlockState(start.below());
        if (below.is(Blocks.DIRT) || below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.PODZOL) ||
                below.is(Blocks.COARSE_DIRT) || below.is(Blocks.ROOTED_DIRT) ||
                below.is(Blocks.MUD) || below.is(Blocks.MYCELIUM)) {
            return true;
        }

        for (int dy = 0; dy <= 6; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 5) continue;
                    if (level.getBlockState(start.offset(dx, dy, dz)).is(BlockTags.LEAVES)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isSuperRunPlant(ServerLevel level, BlockPos pos, BlockState state) {
        if (DomainExpansion.denyTechniqueEdit(level, pos, null)) return false;
        if (state.isAir()) return false;

        if (state.is(BlockTags.LEAVES) ||
                state.is(BlockTags.FLOWERS) ||
                state.is(BlockTags.SAPLINGS)) {
            return true;
        }

        if (state.is(BlockTags.LOGS)) {
            return isNaturalTreeLog(level, pos);
        }

        return state.is(Blocks.GRASS) ||
                state.is(Blocks.TALL_GRASS) ||
                state.is(Blocks.FERN) ||
                state.is(Blocks.LARGE_FERN) ||
                state.is(Blocks.VINE) ||
                state.is(Blocks.DEAD_BUSH) ||
                state.is(Blocks.SWEET_BERRY_BUSH) ||
                state.is(Blocks.AZALEA) ||
                state.is(Blocks.FLOWERING_AZALEA) ||
                state.is(Blocks.BAMBOO) ||
                state.is(Blocks.SUGAR_CANE);
    }

    private static void vaporizeSuperRunPlants(ServerPlayer player, ServerLevel level) {
        Vec3 current = player.position();

        boolean hasLast = player.getPersistentData().contains("jn_speed_last_x") &&
                player.getPersistentData().contains("jn_speed_last_y") &&
                player.getPersistentData().contains("jn_speed_last_z");

        if (!hasLast) {
            player.getPersistentData().putDouble("jn_speed_last_x", current.x);
            player.getPersistentData().putDouble("jn_speed_last_y", current.y);
            player.getPersistentData().putDouble("jn_speed_last_z", current.z);
            return;
        }

        Vec3 previous = new Vec3(
                player.getPersistentData().getDouble("jn_speed_last_x"),
                player.getPersistentData().getDouble("jn_speed_last_y"),
                player.getPersistentData().getDouble("jn_speed_last_z")
        );

        player.getPersistentData().putDouble("jn_speed_last_x", current.x);
        player.getPersistentData().putDouble("jn_speed_last_y", current.y);
        player.getPersistentData().putDouble("jn_speed_last_z", current.z);

        // Не сносим мир после телепорта или иных резких перемещений.
        if (previous.distanceToSqr(current) > 16.0) return;

        double minX = Math.min(previous.x, current.x) - 0.55;
        double maxX = Math.max(previous.x, current.x) + 0.55;
        double minY = Math.min(previous.y, current.y);
        double maxY = Math.max(previous.y, current.y) + player.getBbHeight() + 0.15;
        double minZ = Math.min(previous.z, current.z) - 0.55;
        double maxZ = Math.max(previous.z, current.z) + 0.55;

        List<BlockPos> toRemove = new ArrayList<>();

        for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
            for (int y = Mth.floor(minY); y <= Mth.floor(maxY); y++) {
                for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (isSuperRunPlant(level, pos, state)) {
                        toRemove.add(pos.immutable());
                    }
                }
            }
        }

        for (BlockPos pos : toRemove) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            // Полное испарение без drop/item entities.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

            level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    7,
                    0.32, 0.32, 0.32,
                    0.08
            );
        }
    }

    private static void supportSuperRunOnWater(ServerPlayer player, ServerLevel level) {
        BlockPos below = BlockPos.containing(
                player.getX(),
                player.getY() - 0.18,
                player.getZ()
        );

        var fluid = level.getFluidState(below);
        if (!fluid.is(FluidTags.WATER)) return;

        double surfaceY = below.getY() + fluid.getHeight(level, below);

        // Работает именно как бег по поверхности, а не как полёт из глубины воды.
        if (player.getY() < surfaceY - 0.42 || player.getY() > surfaceY + 0.50) return;

        // Бегом по воде управляет клиент (WATER_RUN). Сервер больше не переставляет
        // игрока и не отправляет ему скорость: это гасило разгон каждый тик.
        player.fallDistance = 0.0F;

        // Брызги и круги на воде рисуют клиенты (MovementFxClient).
    }

    private static void spawnSuperRunEffects(ServerPlayer player, ServerLevel level, long now) {
        // Пыль, полосы ветра, шлейф и шаги сверхбега рисуют клиенты (MovementFxClient) —
        // по фактической скорости игрока, так их видят все.
    }

    private static void tickBlindfoldRun(ServerPlayer player, ServerLevel level, long now) {
        if (isHollowPurpleCasting(player)) {
            player.getPersistentData().putBoolean("jn_super_speed", false);
            player.setSprinting(false);
            return;
        }

        boolean speed = player.getPersistentData().getBoolean("jn_super_speed");

        // Ванильный sprint не участвует в физике Jujutsu Neon.
        // Фактическую скорость локального игрока задаёт наш client movement controller.
        player.setSprinting(false);

        if (!speed) {
            player.getPersistentData().remove("jn_speed_last_x");
            player.getPersistentData().remove("jn_speed_last_y");
            player.getPersistentData().remove("jn_speed_last_z");
            return;
        }

        long nextCost = player.getPersistentData().getLong("jn_speed_next_cost");
        if (nextCost <= 0) {
            nextCost = now + SUPER_RUN_COST_INTERVAL;
            player.getPersistentData().putLong("jn_speed_next_cost", nextCost);
        }

        if (now >= nextCost) {
            if (getEnergy(player) + 1.0E-6 < SUPER_RUN_COST) {
                player.getPersistentData().putBoolean("jn_super_speed", false);
                player.getPersistentData().remove("jn_speed_next_cost");
                return;
            }

            setEnergy(player, getEnergy(player) - SUPER_RUN_COST);
            player.getPersistentData().putLong("jn_speed_next_cost", now + SUPER_RUN_COST_INTERVAL);
        }

        // Мир взаимодействует уже с фактической позицией игрока, пришедшей с клиента:
        // деревья/листва испаряются, вода получает только вторичный VFX.
        vaporizeSuperRunPlants(player, level);
        supportSuperRunOnWater(player, level);
        spawnSuperRunEffects(player, level, now);
    }

    private static boolean isInFront(ServerPlayer player, LivingEntity target, double minDot) {
        Vec3 look = player.getLookAngle().normalize();
        Vec3 toTarget = target.position()
                .add(0, target.getBbHeight() * 0.5, 0)
                .subtract(player.getEyePosition());

        if (toTarget.lengthSqr() < 0.01) return true;
        return look.dot(toTarget.normalize()) >= minDot;
    }

    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static class ForgeEvents {

        @SubscribeEvent
        public static void onLivingAttack(LivingAttackEvent event) {
            // Максимальный Фиолетовый бьёт всех, кроме владельца, в том числе сквозь Бесконечность.
            if (event.getSource().getEntity() instanceof ServerPlayer maxPurpleOwner &&
                    maxPurpleOwner.getPersistentData().getBoolean("jn_max_purple_damage")) {
                return;
            }

            if (event.getSource().getEntity() instanceof LivingEntity stunnedAttacker &&
                    stunnedAttacker.getPersistentData().getLong("jn_stun_until") > stunnedAttacker.level().getGameTime()) {
                event.setCanceled(true);
                return;
            }

            if (event.getSource().getEntity() instanceof ServerPlayer redOwner &&
                    redOwner.getPersistentData().getBoolean("jn_red_explosion_blocks_only")) {
                event.setCanceled(true);
                return;
            }

            if (event.getSource().getEntity() instanceof ServerPlayer attacker &&
                    isBlueInteractionActive(attacker) &&
                    !attacker.getPersistentData().getBoolean("jn_blue_custom_damage") &&
                    !attacker.getPersistentData().getBoolean("jn_red_custom_damage") &&
                    !attacker.getPersistentData().getBoolean("jn_purple_custom_damage")) {
                event.setCanceled(true);
                return;
            }
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            if (!hasGojoBlindfold(player)) return;
            if (!player.getPersistentData().getBoolean("jn_infinity")) return;

            // Infinity блокирует внешние прямые атаки, но не отменяет падение/голод/огонь.
            if (event.getSource().getEntity() != null || event.getSource().getDirectEntity() != null) {
                if (!consumeEnergy(player, 1.5)) {
                    player.getPersistentData().putBoolean("jn_infinity", false);
                    return;
                }
                event.setCanceled(true);
                ServerLevel level = player.serverLevel();
                for (int r = 0; r < 3; r++) {
                    spawnNeonRing(level, player.position().add(0, 1.0, 0), 1.05 + r * 0.24,
                            new Vector3f(0.08f, 0.82f, 1.0f));
                }
            }
        }

        @SubscribeEvent
        public static void onBlindfoldFall(LivingFallEvent event) {
            if (event.getEntity() instanceof ServerPlayer player && hasGojoBlindfold(player)) {
                event.setCanceled(true);
                player.fallDistance = 0.0F;
            }
        }

        @SubscribeEvent
        public static void onLivingTick(LivingEvent.LivingTickEvent event) {
            LivingEntity entity = event.getEntity();
            if (entity.level().isClientSide()) return;

            long until = entity.getPersistentData().getLong("jn_stun_until");
            if (until <= 0) return;

            long now = entity.level().getGameTime();
            if (now >= until) {
                entity.getPersistentData().remove("jn_stun_until");
                entity.getPersistentData().remove("jn_stun_x");
                entity.getPersistentData().remove("jn_stun_y");
                entity.getPersistentData().remove("jn_stun_z");
                return;
            }

            double x = entity.getPersistentData().getDouble("jn_stun_x");
            double y = entity.getPersistentData().getDouble("jn_stun_y");
            double z = entity.getPersistentData().getDouble("jn_stun_z");

            entity.teleportTo(x, y, z);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setSprinting(false);
            entity.fallDistance = 0.0F;
            entity.hurtMarked = true;
        }

        /** Летящие шарики Красного — раз в тик сервера. */
        @SubscribeEvent
        public static void onServerTickRed(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) tickRedShots(server);
        }

        @SubscribeEvent
        public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (!(event.player instanceof ServerPlayer player)) return;

            ServerLevel level = player.serverLevel();
            long now = level.getGameTime();
            boolean equipped = hasGojoBlindfold(player);

            tickBlueState(player, level, now);
            tickRedState(player, level, now);
            tickMaximumBlueState(player, level, now);
            tickHollowPurpleState(player, level, now);
            tickDashState(player, level, now);

            if (!equipped) {
                player.getPersistentData().putBoolean("jn_super_speed", false);
                player.getPersistentData().remove("jn_speed_next_cost");
                player.getPersistentData().remove("jn_speed_last_x");
                player.getPersistentData().remove("jn_speed_last_y");
                player.getPersistentData().remove("jn_speed_last_z");
                player.getPersistentData().putBoolean("jn_infinity", false);
                player.getPersistentData().putInt("jn_air_jumps", 0);
            } else {
                if (player.onGround() || player.isInWater()) {
                    player.getPersistentData().putInt("jn_air_jumps", 0);
                }

                boolean speed = player.getPersistentData().getBoolean("jn_super_speed");
                boolean infinity = player.getPersistentData().getBoolean("jn_infinity");

                // В режиме сверхбега энергия не регенерирует: расход строго 1% за 2 секунды.
                double regen = speed ? 0.0 : 0.28;
                if (infinity) regen *= 0.45;
                setEnergy(player, getEnergy(player) + regen);

                tickBlindfoldRun(player, level, now);

                if (infinity && now % 3 == 0) {
                    double a = now * 0.25;
                    Vec3 p = player.position().add(Math.cos(a) * 1.25, 1.0 + Math.sin(a * 0.5) * 0.35, Math.sin(a) * 1.25);
                    sendDust(level, p, new Vector3f(0.08f, 0.82f, 1.0f), 0.9f);
                }

            }

            // Синхронизация HUD 4 раза в секунду.
            if (now % 5 == 0) {
                NETWORK.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new HudSyncPacket(
                                getEnergy(player),
                                player.getPersistentData().getInt("jn_air_jumps"),
                                player.getPersistentData().getBoolean("jn_infinity"),
                                equipped,
                                isBlueInteractionActive(player),
                                isMaximumBlueActive(player),
                                isHollowPurpleCasting(player)
                        )
                );
            }
        }
    }

    private static void spawnVfx(
            ServerLevel level,
            RegistryObject<SimpleParticleType> type,
            Vec3 pos,
            int count
    ) {
        level.sendParticles(
                type.get(),
                pos.x, pos.y, pos.z,
                count,
                0.08, 0.08, 0.08,
                0.0
        );
    }

    private static void spawnNeonSphere(
            ServerLevel level,
            Vec3 center,
            double radius,
            Vector3f colorA,
            Vector3f colorB
    ) {
        for (int i = 0; i < 180; i++) {
            double theta = rnd(0, Math.PI * 2);
            double phi = Math.acos(rnd(-1, 1));

            double x = center.x + radius * Math.sin(phi) * Math.cos(theta);
            double y = center.y + radius * Math.cos(phi);
            double z = center.z + radius * Math.sin(phi) * Math.sin(theta);

            sendDust(
                    level,
                    new Vec3(x, y, z),
                    i % 2 == 0 ? colorA : colorB,
                    1.20f
            );
        }

        level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                center.x, center.y, center.z,
                55,
                1.3, 1.3, 1.3,
                0.18
        );
    }

    private static void spawnNeonRing(
            ServerLevel level,
            Vec3 center,
            double radius,
            Vector3f color
    ) {
        int points = 72;

        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0 * i) / points;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;

            sendDust(
                    level,
                    new Vec3(x, center.y, z),
                    color,
                    1.25f
            );
        }
    }

    private static void sendDust(
            ServerLevel level,
            Vec3 pos,
            Vector3f color,
            float scale
    ) {
        level.sendParticles(
                new DustParticleOptions(color, scale),
                pos.x, pos.y, pos.z,
                1,
                0, 0, 0,
                0
        );
    }

    private static double rnd(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    private record AbilityPacket(Ability ability) {

        static void encode(AbilityPacket msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.ability);
        }

        static AbilityPacket decode(FriendlyByteBuf buf) {
            return new AbilityPacket(buf.readEnum(Ability.class));
        }

        static void handle(
                AbilityPacket msg,
                Supplier<NetworkEvent.Context> contextSupplier
        ) {
            NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) useAbility(player, msg.ability);
            });

            context.setPacketHandled(true);
        }
    }


    private record MovementPacket(MovementAction action) {

        static void encode(MovementPacket msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
        }

        static MovementPacket decode(FriendlyByteBuf buf) {
            return new MovementPacket(buf.readEnum(MovementAction.class));
        }

        static void handle(
                MovementPacket msg,
                Supplier<NetworkEvent.Context> contextSupplier
        ) {
            NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    handleMovement(player, msg.action);
                }
            });

            context.setPacketHandled(true);
        }
    }

    private record BlueActionPacket() {

        static void encode(BlueActionPacket msg, FriendlyByteBuf buf) {
        }

        static BlueActionPacket decode(FriendlyByteBuf buf) {
            return new BlueActionPacket();
        }

        static void handle(BlueActionPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && !DomainExpansion.blocksActions(player)) bluePrimaryAction(player);
            });
            context.setPacketHandled(true);
        }
    }

    private record RedControlPacket(RedControlAction action) {

        static void encode(RedControlPacket msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
        }

        static RedControlPacket decode(FriendlyByteBuf buf) {
            return new RedControlPacket(buf.readEnum(RedControlAction.class));
        }

        static void handle(RedControlPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;

                if (msg.action != RedControlAction.CANCEL && DomainExpansion.blocksActions(player)) {
                    // Обездвиженный не стреляет: заряд просто гаснет.
                    if (msg.action == RedControlAction.RELEASE) cancelRedCharge(player, false);
                    return;
                }
                switch (msg.action) {
                    case START -> startRedCharge(player);
                    case RELEASE -> releaseRedCharge(player);
                    case CANCEL -> cancelRedCharge(player, true);
                }
            });

            context.setPacketHandled(true);
        }
    }

    private record MaxBlueControlPacket(MaxBlueControlAction action) {
        static void encode(MaxBlueControlPacket msg, FriendlyByteBuf buf) {
            buf.writeEnum(msg.action);
        }

        static MaxBlueControlPacket decode(FriendlyByteBuf buf) {
            return new MaxBlueControlPacket(buf.readEnum(MaxBlueControlAction.class));
        }

        static void handle(MaxBlueControlPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                if (msg.action == MaxBlueControlAction.START && DomainExpansion.blocksActions(player)) return;

                switch (msg.action) {
                    case START -> startMaximumBlue(player);
                    case RELEASE -> beginMaximumBlueFade(player);
                    case FARTHER -> adjustMaximumBlueDistance(player, 0.35);
                    case CLOSER -> adjustMaximumBlueDistance(player, -0.35);
                }
            });
            context.setPacketHandled(true);
        }
    }

    private record MaxBlueVisualPacket(
            UUID ownerId,
            boolean active,
            double x,
            double y,
            double z,
            float radius,
            int phase
    ) {
        static void encode(MaxBlueVisualPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
            buf.writeBoolean(msg.active);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeFloat(msg.radius);
            buf.writeVarInt(msg.phase);
        }

        static MaxBlueVisualPacket decode(FriendlyByteBuf buf) {
            return new MaxBlueVisualPacket(
                    buf.readUUID(),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readVarInt()
            );
        }

        static void handle(MaxBlueVisualPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> MaximumBlueReferenceClient.accept(
                            msg.ownerId(), msg.active(), msg.x(), msg.y(), msg.z(), msg.radius(), msg.phase()
                    )
            ));
            context.setPacketHandled(true);
        }
    }


    private record MaxBlueDebrisBatchPacket(
            UUID ownerId,
            double targetX,
            double targetY,
            double targetZ,
            List<Long> packedPositions,
            List<Integer> stateIds
    ) {
        static void encode(MaxBlueDebrisBatchPacket msg, FriendlyByteBuf buf) {
            buf.writeUUID(msg.ownerId);
            buf.writeDouble(msg.targetX);
            buf.writeDouble(msg.targetY);
            buf.writeDouble(msg.targetZ);
            int count = Math.min(msg.packedPositions.size(), msg.stateIds.size());
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeLong(msg.packedPositions.get(i));
                buf.writeVarInt(msg.stateIds.get(i));
            }
        }

        static MaxBlueDebrisBatchPacket decode(FriendlyByteBuf buf) {
            UUID ownerId = buf.readUUID();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            int count = Mth.clamp(buf.readVarInt(), 0, 1024);
            List<Long> positions = new ArrayList<>(count);
            List<Integer> states = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                positions.add(buf.readLong());
                states.add(buf.readVarInt());
            }
            return new MaxBlueDebrisBatchPacket(ownerId, x, y, z, positions, states);
        }

        static void handle(MaxBlueDebrisBatchPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> MaximumBlueReferenceClient.acceptDebrisBatch(
                            msg.ownerId(), msg.targetX(), msg.targetY(), msg.targetZ(),
                            msg.packedPositions(), msg.stateIds()
                    )
            ));
            context.setPacketHandled(true);
        }
    }

    private record JumpControlPacket(int tier) {
        static void encode(JumpControlPacket msg, FriendlyByteBuf buf) {
            buf.writeByte(msg.tier);
        }

        static JumpControlPacket decode(FriendlyByteBuf buf) {
            return new JumpControlPacket(buf.readByte());
        }

        static void handle(JumpControlPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) performChargedJump(player, msg.tier);
            });
            context.setPacketHandled(true);
        }
    }

    private record TeleportPacket(BlockPos target, Direction face) {
        static void encode(TeleportPacket msg, FriendlyByteBuf buf) {
            buf.writeBlockPos(msg.target);
            buf.writeEnum(msg.face);
        }

        static TeleportPacket decode(FriendlyByteBuf buf) {
            return new TeleportPacket(buf.readBlockPos(), buf.readEnum(Direction.class));
        }

        static void handle(TeleportPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || DomainExpansion.blocksActions(player)) return;
                // Через стену территории телепортом не пройти.
                if (DomainExpansion.separated(player.level(), player.position(), Vec3.atCenterOf(msg.target))) return;
                executeLongRangeTeleport(player, msg.target, msg.face);
            });
            context.setPacketHandled(true);
        }
    }

    private record HudSyncPacket(double energy, int airJumps, boolean infinity, boolean blindfold, boolean blueActive, boolean maxBlueActive, boolean purpleCasting) {

        static void encode(HudSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeDouble(msg.energy);
            buf.writeVarInt(msg.airJumps);
            buf.writeBoolean(msg.infinity);
            buf.writeBoolean(msg.blindfold);
            buf.writeBoolean(msg.blueActive);
            buf.writeBoolean(msg.maxBlueActive);
            buf.writeBoolean(msg.purpleCasting);
        }

        static HudSyncPacket decode(FriendlyByteBuf buf) {
            return new HudSyncPacket(
                    buf.readDouble(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean()
            );
        }

        static void handle(HudSyncPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> ClientForgeEvents.applyHudSync(msg)
            ));
            context.setPacketHandled(true);
        }
    }


    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.MOD,
            value = Dist.CLIENT
    )
    public static class ClientModEvents {

        // Эти бинды автоматически появляются в:
        // Настройки -> Управление -> Назначение клавиш.
        // Их можно менять на любые клавиши или кнопки мыши через обычное меню Minecraft.
        private static final String CATEGORY = "Jujutsu Neon — способности";

        public static final KeyMapping DASH_KEY = new KeyMapping(
                "Дэш вперёд / вбок",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Q,
                CATEGORY
        );

        public static final KeyMapping SUPER_SPEED_KEY = new KeyMapping(
                "Сверхбег 8x",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_CONTROL,
                CATEGORY
        );

        public static final KeyMapping BLUE_KEY = new KeyMapping(
                "Z: Blue / удержание: Maximum Blue (W/S дистанция)",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                CATEGORY
        );

        public static final KeyMapping RED_KEY = new KeyMapping(
                "X: Red / держать 2с: Maximum Red",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                CATEGORY
        );

        public static final KeyMapping PURPLE_KEY = new KeyMapping(
                "G: Hollow Purple",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY
        );

        public static final KeyMapping DOMAIN_KEY = new KeyMapping(
                "V: Infinity",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY
        );

        public static final KeyMapping UTILITY_KEY = new KeyMapping(
                "B: RCT",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                CATEGORY
        );

        public static final KeyMapping HUD_KEY = new KeyMapping(
                "Показать/скрыть панель способностей",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                CATEGORY
        );

        public static final KeyMapping DOMAIN_EXPANSION_KEY = new KeyMapping(
                "T: Расширение территории (повторно — разрушить)",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_T,
                CATEGORY
        );

        public static final KeyMapping DOUBLE_JUMP_KEY = new KeyMapping(
                "Двойные прыжки: вкл/выкл",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY
        );

        public static final KeyMapping TELEPORT_KEY = new KeyMapping(
                "R: Мгновенный телепорт к блоку",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                CATEGORY
        );

        @SubscribeEvent
        public static void registerParticles(RegisterParticleProvidersEvent event) {
            // Native 4096x4096 primary technique sprites.
            // One texture is shared by all instances of the same technique in the atlas,
            // so visual fidelity rises without loading a new 4K image per particle.
            event.registerSpriteSet(VFX_BLUE.get(), sprites -> new Technique4KProvider(sprites, 2.25f, 12, 0.010f, 0.030f));
            event.registerSpriteSet(VFX_MAX_BLUE.get(), sprites -> new Technique4KProvider(sprites, 5.1f, 18, 0.014f, -0.018f));
            event.registerSpriteSet(VFX_RED.get(), sprites -> new Technique4KProvider(sprites, 1.45f, 10, 0.018f, 0.038f));
            event.registerSpriteSet(VFX_MAX_RED.get(), sprites -> new Technique4KProvider(sprites, 3.15f, 16, 0.015f, -0.026f));
            event.registerSpriteSet(VFX_PURPLE.get(), sprites -> new Technique4KProvider(sprites, 4.9f, 20, 0.010f, 0.020f));
            event.registerSpriteSet(VFX_BARRAGE.get(), sprites -> new UltraVfxProvider(sprites, 4.4f, 18, 0.045f));
            event.registerSpriteSet(VFX_INFINITY.get(), sprites -> new UltraVfxProvider(sprites, 4.0f, 28, 0.008f));
            event.registerSpriteSet(VFX_DOMAIN.get(), sprites -> new UltraVfxProvider(sprites, 10.0f, 48, 0.010f));
            event.registerSpriteSet(VFX_RCT.get(), sprites -> new UltraVfxProvider(sprites, 4.5f, 28, 0.018f));
            event.registerSpriteSet(VFX_TELEPORT.get(), sprites -> new UltraVfxProvider(sprites, 4.0f, 14, 0.075f));
            event.registerSpriteSet(VFX_DASH.get(), sprites -> new UltraVfxProvider(sprites, 3.0f, 10, 0.100f));
            event.registerSpriteSet(VFX_SHOCKWAVE.get(), sprites -> new UltraVfxProvider(sprites, 5.8f, 14, 0.090f));
            event.registerSpriteSet(VFX_STAR.get(), sprites -> new UltraVfxProvider(sprites, 4.8f, 10, 0.055f));
            event.registerSpriteSet(VFX_SLASH.get(), sprites -> new UltraVfxProvider(sprites, 4.2f, 12, 0.030f));
            event.registerSpriteSet(VFX_TRAIL.get(), sprites -> new UltraVfxProvider(sprites, 2.2f, 9, 0.030f));
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(DASH_KEY);
            event.register(SUPER_SPEED_KEY);
            event.register(BLUE_KEY);
            event.register(RED_KEY);
            event.register(PURPLE_KEY);
            event.register(DOMAIN_KEY);
            event.register(UTILITY_KEY);
            event.register(HUD_KEY);
            event.register(TELEPORT_KEY);
            event.register(DOUBLE_JUMP_KEY);
            event.register(DOMAIN_EXPANSION_KEY);
        }
    }

    @Mod.EventBusSubscriber(
            modid = MODID,
            bus = Mod.EventBusSubscriber.Bus.FORGE,
            value = Dist.CLIENT
    )
    public static class ClientForgeEvents {

        private static final int HOLD_TICKS = 20; // 1 секунда
        private static boolean lastSpeedHeld = false;
        private static boolean jumpChargeWasDown = false;
        private static int jumpChargeTicks = 0;
        private static boolean jumpPressStartedInAir = false;
        private static int jumpsUsed = 0; // прыжки подряд, включая прыжок с земли (максимум MAX_JUMPS)

        // Полностью собственный movement controller.
        private static int clientDashMode = DASH_NONE;
        private static int clientDashAge = 0;
        private static Vec3 clientDashDirection = Vec3.ZERO;

        // Creative: первый короткий tap ждёт несколько тиков, чтобы второй tap
        // мог включить обычный creative flight без случайного прыжка.
        private static long creativeLastSpacePressTick = -1000L;
        private static int pendingCreativeShortJumpTicks = -1;

        private static final HoldKeyState BLUE_STATE = new HoldKeyState();
        private static final HoldKeyState RED_STATE = new HoldKeyState();

        private static String activeAnim = "NONE";
        private static int activeAnimTicks = 0;
        private static int activeAnimLength = 1;
        private static String chargingAnim = "NONE";
        private static float chargingProgress = 0.0f;

        private static boolean hudVisible = true;
        private static double hudEnergy = CE_MAX;
        private static int hudAirJumps = 0;
        private static boolean hudInfinity = false;
        private static boolean hudBlindfold = false;
        private static boolean hudBlueActive = false;
        private static boolean hudMaxBlueActive = false;
        private static boolean hudPurpleCasting = false;

        private static final Map<UUID, MaxBlueClientVisual> MAX_BLUE_VISUALS = new HashMap<>();

        private static class MaxBlueClientVisual {
            Vec3 previousPos;
            Vec3 currentPos;
            Vec3 targetPos;

            float previousRadius;
            float currentRadius;
            float targetRadius;

            float previousAlpha;
            float currentAlpha;
            float targetAlpha;

            float rotation;
            int phase;
            int staleTicks;

            MaxBlueClientVisual(Vec3 pos, float radius, int phase) {
                this.previousPos = pos;
                this.currentPos = pos;
                this.targetPos = pos;
                this.previousRadius = radius;
                this.currentRadius = radius;
                this.targetRadius = radius;
                this.previousAlpha = 0.0f;
                this.currentAlpha = 0.0f;
                this.targetAlpha = 1.0f;
                this.rotation = 0.0f;
                this.phase = phase;
                this.staleTicks = 0;
            }
        }

        private static void applyMaximumBlueVisual(MaxBlueVisualPacket msg) {
            Vec3 target = new Vec3(msg.x(), msg.y(), msg.z());

            if (!msg.active()) {
                MaxBlueClientVisual visual = MAX_BLUE_VISUALS.get(msg.ownerId());
                if (visual != null) {
                    visual.targetPos = target;
                    visual.targetRadius = 0.0f;
                    visual.targetAlpha = 0.0f;
                    visual.phase = MAX_BLUE_PHASE_FADING;
                    visual.staleTicks = 0;
                }
                return;
            }

            MaxBlueClientVisual visual = MAX_BLUE_VISUALS.computeIfAbsent(
                    msg.ownerId(),
                    id -> new MaxBlueClientVisual(target, msg.radius(), msg.phase())
            );

            visual.targetPos = target;
            visual.targetRadius = Math.max(0.0f, msg.radius());
            visual.targetAlpha = 1.0f;
            visual.phase = msg.phase();
            visual.staleTicks = 0;
        }

        private static void tickMaximumBlueClientVisuals() {
            MAX_BLUE_VISUALS.entrySet().removeIf(entry -> {
                MaxBlueClientVisual visual = entry.getValue();

                visual.previousPos = visual.currentPos;
                visual.previousRadius = visual.currentRadius;
                visual.previousAlpha = visual.currentAlpha;

                visual.currentPos = visual.currentPos.lerp(visual.targetPos, 0.58);
                visual.currentRadius += (visual.targetRadius - visual.currentRadius) * 0.58f;
                visual.currentAlpha += (visual.targetAlpha - visual.currentAlpha) * 0.34f;
                visual.rotation = (visual.rotation + 1.35f) % 360.0f;
                visual.staleTicks++;

                if (visual.staleTicks > 12) {
                    visual.targetAlpha = 0.0f;
                    visual.targetRadius = 0.0f;
                }

                return visual.targetAlpha <= 0.001f &&
                        visual.currentAlpha <= 0.015f &&
                        visual.currentRadius <= 0.02f;
            });
        }

        private static void applyHudSync(HudSyncPacket msg) {
            hudEnergy = msg.energy();
            hudAirJumps = msg.airJumps();
            hudInfinity = msg.infinity();
            hudBlindfold = msg.blindfold();
            hudBlueActive = msg.blueActive();
            hudMaxBlueActive = msg.maxBlueActive();
            hudPurpleCasting = msg.purpleCasting();
        }

        private static String keyName(KeyMapping mapping) {
            return mapping.getTranslatedKeyMessage().getString().toUpperCase();
        }

        private static class HoldKeyState {
            boolean wasDown;
            int ticks;
            boolean holdTriggered;
        }

        // G: отпустил раньше 3 секунд — обычный Фиолетовый, держишь 3 секунды — Максимальный.
        private static final int MAX_PURPLE_HOLD_TICKS = 60;
        private static boolean purpleKeyWasDown = false;
        private static boolean purpleHoldValid = false;
        private static boolean purpleMaxSent = false;
        private static int purpleHoldTicks = 0;

        private static void resetPurpleHold() {
            if ("CHARGE_MAX_PURPLE".equals(chargingAnim)) {
                chargingAnim = "NONE";
                chargingProgress = 0.0f;
            }
            purpleKeyWasDown = false;
            purpleHoldValid = false;
            purpleMaxSent = false;
            purpleHoldTicks = 0;
        }

        private static void processPurpleKey() {
            boolean down = ClientModEvents.PURPLE_KEY.isDown();

            if (down && !purpleKeyWasDown) {
                purpleHoldTicks = 0;
                purpleMaxSent = false;
                purpleHoldValid = hudBlindfold && !hudPurpleCasting && !hudMaxBlueActive;
            }

            if (down) {
                if (purpleHoldValid && !purpleMaxSent) {
                    purpleHoldTicks++;
                    chargingAnim = "CHARGE_MAX_PURPLE";
                    chargingProgress = Mth.clamp(purpleHoldTicks / (float) MAX_PURPLE_HOLD_TICKS, 0.0f, 1.0f);

                    if (purpleHoldTicks >= MAX_PURPLE_HOLD_TICKS) {
                        purpleMaxSent = true;
                        chargingAnim = "NONE";
                        chargingProgress = 0.0f;
                        MaximumPurple.requestStart();
                    }
                }
            } else if (purpleKeyWasDown) {
                if (purpleHoldValid && !purpleMaxSent) {
                    if ("CHARGE_MAX_PURPLE".equals(chargingAnim)) {
                        chargingAnim = "NONE";
                        chargingProgress = 0.0f;
                    }
                    NETWORK.sendToServer(new AbilityPacket(Ability.HOLLOW_PURPLE));
                    startAnim("PURPLE_CAST", 100);
                }
                purpleHoldValid = false;
                purpleMaxSent = false;
                purpleHoldTicks = 0;
            }

            purpleKeyWasDown = down;
        }

        static void startAnim(String type, int ticks) {
            activeAnim = type;
            activeAnimTicks = ticks;
            activeAnimLength = Math.max(1, ticks);
        }

        /**
         * X: нажал — заряд; отпустил раньше 2 секунд — обычный Красный; держишь 2 секунды —
         * сервер сам начинает Максимальный Красный (отпускать не нужно).
         */
        private static void processRedKey(KeyMapping key, HoldKeyState state) {
            boolean down = key.isDown();

            if (down) {
                if (!state.wasDown) {
                    state.ticks = 0;
                    state.holdTriggered = false;
                    RedTechnique.request(RedTechnique.ACTION_START);
                }
                state.ticks++;
            } else if (state.wasDown) {
                // Сервер по фактическому времени удержания решит: обычный Красный или уже идёт Максимальный.
                RedTechnique.request(RedTechnique.ACTION_RELEASE);
                state.ticks = 0;
                state.holdTriggered = false;
            }

            state.wasDown = down;
        }

        private static void processBlueKey(KeyMapping key, HoldKeyState state) {
            boolean down = key.isDown();

            if (down) {
                if (!state.wasDown) {
                    state.ticks = 0;
                    state.holdTriggered = false;
                }

                state.ticks++;

                if (!state.holdTriggered) {
                    chargingAnim = "CHARGE_BLUE";
                    chargingProgress = Mth.clamp(state.ticks / (float) HOLD_TICKS, 0.0f, 1.0f);
                }

                if (!state.holdTriggered && state.ticks >= HOLD_TICKS) {
                    state.holdTriggered = true;
                    chargingAnim = "NONE";
                    chargingProgress = 0.0f;
                    NETWORK.sendToServer(new MaxBlueControlPacket(MaxBlueControlAction.START));
                    startAnim("MAX_BLUE", 40);
                }
            } else if (state.wasDown) {
                chargingAnim = "NONE";
                chargingProgress = 0.0f;

                if (state.holdTriggered) {
                    NETWORK.sendToServer(new MaxBlueControlPacket(MaxBlueControlAction.RELEASE));
                } else {
                    NETWORK.sendToServer(new AbilityPacket(Ability.BLUE));
                }

                state.ticks = 0;
                state.holdTriggered = false;
            }

            state.wasDown = down;
        }

        /** Только нажатие (без удержания): V — Infinity, B — RCT. */
        private static void processTapKey(KeyMapping key, Ability ability) {
            while (key.consumeClick()) {
                NETWORK.sendToServer(new AbilityPacket(ability));
                startAnim(ability.name(), 12);
            }
        }

        private static void processHoldKey(
                KeyMapping key,
                Ability tapAbility,
                Ability holdAbility,
                HoldKeyState state,
                String chargeType
        ) {
            boolean down = key.isDown();

            if (down) {
                if (!state.wasDown) {
                    state.ticks = 0;
                    state.holdTriggered = false;
                }

                state.ticks++;

                if (!state.holdTriggered) {
                    chargingAnim = chargeType;
                    chargingProgress = Mth.clamp(state.ticks / (float) HOLD_TICKS, 0.0f, 1.0f);
                }

                if (!state.holdTriggered && state.ticks >= HOLD_TICKS) {
                    state.holdTriggered = true;
                    chargingAnim = "NONE";
                    chargingProgress = 0.0f;
                    NETWORK.sendToServer(new AbilityPacket(holdAbility));
                    startAnim(holdAbility.name(), 18);
                }
            } else if (state.wasDown) {
                chargingAnim = "NONE";
                chargingProgress = 0.0f;

                if (!state.holdTriggered) {
                    NETWORK.sendToServer(new AbilityPacket(tapAbility));
                    startAnim(tapAbility.name(), 12);
                }

                state.ticks = 0;
                state.holdTriggered = false;
            }

            state.wasDown = down;
        }

        private static Vec3 clientHorizontalDirection(Minecraft mc) {
            if (mc.player == null) return Vec3.ZERO;

            double forwardInput =
                    (mc.options.keyUp.isDown() ? 1.0 : 0.0) -
                    (mc.options.keyDown.isDown() ? 1.0 : 0.0);
            double strafeInput =
                    (mc.options.keyRight.isDown() ? 1.0 : 0.0) -
                    (mc.options.keyLeft.isDown() ? 1.0 : 0.0);

            if (Math.abs(forwardInput) < 1.0E-4 && Math.abs(strafeInput) < 1.0E-4) {
                return Vec3.ZERO;
            }

            double yaw = Math.toRadians(mc.player.getYRot());
            Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
            Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

            Vec3 result = forward.scale(forwardInput).add(right.scale(strafeInput));
            return result.lengthSqr() > 1.0E-6 ? result.normalize() : Vec3.ZERO;
        }

        private static boolean clientHasGroundSupport(Minecraft mc, double distance) {
            if (mc.player == null || mc.level == null) return false;
            if (mc.player.onGround()) return true;

            Vec3 start = mc.player.position().add(0.0, 0.08, 0.0);
            Vec3 end = start.add(0.0, -Math.max(0.10, distance), 0.0);

            BlockHitResult hit = mc.level.clip(new ClipContext(
                    start,
                    end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    mc.player
            ));

            return hit.getType() != HitResult.Type.MISS;
        }

        /**
         * Принудительный movement solver с "горным скольжением".
         *
         * ЖЁСТКОЕ ПРАВИЛО:
         * - препятствие 1-2 блока = автоматически скользим/поднимаемся;
         * - 3+ блока = упираемся, сквозь стену не телепортируемся;
         * - движение разбито на маленькие swept-шаги, поэтому быстрый dash
         *   не перескакивает тонкие препятствия и не туннелит через блоки.
         */
        private static boolean tryClientForcedMove(Minecraft mc, Vec3 delta, boolean allowStepUp) {
            if (mc.player == null || mc.level == null) return false;
            if (delta.lengthSqr() < 1.0E-8) return true;

            double horizontalLength = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            int slices = Math.max(
                    1,
                    (int) Math.ceil(Math.max(horizontalLength, Math.abs(delta.y)) / 0.18)
            );

            Vec3 slice = delta.scale(1.0 / slices);
            boolean movedAnything = false;

            for (int part = 0; part < slices; part++) {
                AABB box = mc.player.getBoundingBox();

                if (mc.level.noCollision(mc.player, box.move(slice))) {
                    mc.player.move(MoverType.SELF, slice);
                    movedAnything = true;
                    continue;
                }

                if (!allowStepUp) {
                    if (!slideAlongWall(mc, slice)) return movedAnything;
                    movedAnything = true;
                    continue;
                }

                // Ищем МИНИМАЛЬНУЮ высоту, на которой следующий кусок пути свободен.
                // 0.125 даёт достаточно плотный поиск для ступеней, плит и неровностей.
                boolean climbed = false;
                double naturalY = Math.max(0.0, slice.y);

                for (double lift = 0.125; lift <= 2.0001; lift += 0.125) {
                    Vec3 candidate = new Vec3(
                            slice.x,
                            naturalY + lift,
                            slice.z
                    );

                    if (!mc.level.noCollision(mc.player, box.move(candidate))) continue;

                    // Не "прыжок" и не velocity: реально проводим игрока вверх
                    // по геометрии препятствия. Рендер интерполирует это как скольжение.
                    mc.player.move(MoverType.SELF, candidate);
                    mc.player.fallDistance = 0.0F;
                    movedAnything = true;
                    climbed = true;
                    break;
                }

                if (!climbed) {
                    // Свободного прохода в пределах 2 блоков нет: стена 3+ блока или потолок.
                    // Раньше движение здесь полностью останавливалось, и у высокой стены
                    // W+A / W+D не давали сдвинуться. Теперь скользим вдоль стены, как в ванилле.
                    if (!slideAlongWall(mc, slice)) return movedAnything;
                    movedAnything = true;
                }
            }

            return movedAnything;
        }

        /** Ванильное скольжение: move() сам срезает компоненту, упирающуюся в стену. */
        private static boolean slideAlongWall(Minecraft mc, Vec3 slice) {
            Vec3 before = mc.player.position();
            mc.player.move(MoverType.SELF, slice);
            Vec3 after = mc.player.position();
            double movedX = after.x - before.x;
            double movedZ = after.z - before.z;
            return movedX * movedX + movedZ * movedZ >= 1.0E-6;
        }

        private static void startClientDash(Minecraft mc, MovementAction requestedAction) {
            if (!hudBlindfold || mc.player == null || mc.level == null) return;
            if (hudMaxBlueActive || hudPurpleCasting || clientDashMode != DASH_NONE) return;
            if (!clientHasGroundSupport(mc, 0.36)) return;

            Vec3 look = mc.player.getLookAngle();
            Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
            if (horizontal.lengthSqr() < 1.0E-4) {
                double yaw = Math.toRadians(mc.player.getYRot());
                horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
            }
            horizontal = horizontal.normalize();
            Vec3 right = new Vec3(-horizontal.z, 0.0, horizontal.x);

            if (requestedAction == MovementAction.LEFT_DASH || requestedAction == MovementAction.RIGHT_DASH) {
                clientDashDirection = requestedAction == MovementAction.LEFT_DASH
                        ? right.scale(-1.0) : right;
                clientDashMode = DASH_SIDE;
            } else {
                clientDashDirection = requestedAction == MovementAction.BACK_DASH
                        ? horizontal.scale(-1.0) : horizontal;
                clientDashMode = DASH_FRONT;
            }

            clientDashAge = 0;
            mc.player.setDeltaMovement(Vec3.ZERO);
        }

        private static void tickClientDash(Minecraft mc) {
            if (clientDashMode == DASH_NONE || mc.player == null) return;

            if (!hudBlindfold || hudMaxBlueActive || hudPurpleCasting) {
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                return;
            }

            Vec3 dir = clientDashDirection;
            if (dir.lengthSqr() < 1.0E-6) {
                clientDashMode = DASH_NONE;
                return;
            }

            double speed;
            int lifetime;
            if (clientDashMode == DASH_FRONT) {
                lifetime = (int) FRONT_DASH_TICKS;
                double t = Mth.clamp(clientDashAge / (double) FRONT_DASH_TICKS, 0.0, 1.0);
                speed = 1.68 - 1.30 * t;
            } else if (clientDashMode == DASH_SIDE) {
                lifetime = (int) SIDE_DASH_TICKS;
                speed = 1.28;
            } else {
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                return;
            }

            if (clientDashAge >= lifetime ||
                    !tryClientForcedMove(mc, dir.normalize().scale(speed), true)) {
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                mc.player.setDeltaMovement(Vec3.ZERO);
                return;
            }

            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.fallDistance = 0.0F;
            clientDashAge++;
        }

        /**
         * Высота поверхности воды под игроком, если он у поверхности
         * (до 0.48 под ней или до 0.65 над ней), иначе NaN.
         */
        private static double waterRunSurfaceY(Minecraft mc) {
            if (mc.player == null || mc.level == null) return Double.NaN;

            BlockPos below = BlockPos.containing(
                    mc.player.getX(),
                    mc.player.getY() - 0.18,
                    mc.player.getZ()
            );

            var fluid = mc.level.getFluidState(below);
            if (!fluid.is(FluidTags.WATER)) return Double.NaN;

            double surfaceY = below.getY() + fluid.getHeight(mc.level, below);
            if (mc.player.getY() < surfaceY - 0.48 || mc.player.getY() > surfaceY + 0.65) {
                return Double.NaN;
            }
            return surfaceY;
        }

        private static void updateCustomLocomotionAnimation(
                Minecraft mc,
                boolean moving,
                boolean superRun
        ) {
            if (mc.player == null) return;
            float target = moving ? (superRun ? 1.0F : 0.68F) : 0.0F;
            // Physics remain custom; only the vanilla limb-cycle is fed explicitly.
            mc.player.walkAnimation.update(target, moving ? 0.55F : 0.35F);
        }

        private enum ClientMovementState {
            LOCKED,
            DASH,
            FLIGHT,
            WATER_RUN,
            WATER,
            GROUND,
            AIR
        }

        private static ClientMovementState resolveMovementState(Minecraft mc, boolean superRun) {
            if (hudMaxBlueActive || hudPurpleCasting || MaximumPurpleClient.isLocalActive()
                    || DomainExpansionClient.locksLocalPlayer() || LapseBlueClient.locksLocalPlayer() || RedTechniqueClient.locksLocalPlayer()) return ClientMovementState.LOCKED;
            if (clientDashMode != DASH_NONE) return ClientMovementState.DASH;
            if (JujutsuNeonFlightClient.isCustomFlightActive()) return ClientMovementState.FLIGHT;
            // С Ctrl поверхность воды — опора: WATER_RUN, если игрок у поверхности
            // (чуть под ней или чуть над ней). Глубже — обычное плавание.
            if (superRun && !Double.isNaN(waterRunSurfaceY(mc))) {
                return ClientMovementState.WATER_RUN;
            }
            if (mc.player != null && mc.player.isInWaterOrBubble()) {
                return ClientMovementState.WATER;
            }
            if (clientHasGroundSupport(mc, 0.34)) return ClientMovementState.GROUND;
            return ClientMovementState.AIR;
        }

        private static void smoothGroundDescent(Minecraft mc) {
            if (mc.player == null || mc.level == null || mc.player.onGround()) return;

            Vec3 start = mc.player.position().add(0.0, 0.06, 0.0);
            Vec3 end = start.add(0.0, -1.35, 0.0);
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
            ));
            if (hit.getType() == HitResult.Type.MISS) return;

            double drop = mc.player.getY() - hit.getLocation().y;
            if (drop <= 0.02 || drop > 1.25) return;

            double step = Math.min(drop, 0.34);
            Vec3 down = new Vec3(0.0, -step, 0.0);
            if (mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(down))) {
                mc.player.move(MoverType.SELF, down);
                mc.player.fallDistance = 0.0F;
            }
        }

        private static void tickCustomWaterMovement(Minecraft mc, Vec3 dir) {
            if (mc.player == null) return;

            boolean moving = dir.lengthSqr() >= 1.0E-6;
            Vec3 old = mc.player.getDeltaMovement();
            double speed = 0.12;
            double x = moving ? dir.x * speed : old.x * 0.72;
            double z = moving ? dir.z * speed : old.z * 0.72;
            double y = old.y * 0.72;

            if (mc.options.keyJump.isDown() && !mc.options.keyShift.isDown()) {
                y = Math.min(0.18, y + 0.075);
            } else if (mc.options.keyShift.isDown() && !mc.options.keyJump.isDown()) {
                y = Math.max(-0.18, y - 0.075);
            } else if (mc.player.isUnderWater()) {
                y += 0.012;
            }

            mc.player.setDeltaMovement(x, y, z);
            mc.player.setSwimming(mc.player.isUnderWater() && moving);
            mc.player.fallDistance = 0.0F;
        }

        private static void tickCustomMovement(Minecraft mc) {
            if (!hudBlindfold || mc.player == null || mc.level == null) return;

            // Во время сверхбега (Ctrl) убираем ванильное покачивание камеры:
            // на такой скорости оно превращается в тряску.
            if (ClientModEvents.SUPER_SPEED_KEY.isDown()) {
                mc.player.oBob = 0.0F;
                mc.player.bob = 0.0F;
            }

            Vec3 dir = clientHorizontalDirection(mc);
            boolean moving = dir.lengthSqr() >= 1.0E-6;
            boolean superRun = ClientModEvents.SUPER_SPEED_KEY.isDown();
            double speed = superRun ? CUSTOM_RUN_BLOCKS_PER_TICK : CUSTOM_WALK_BLOCKS_PER_TICK;
            ClientMovementState state = resolveMovementState(mc, superRun);

            switch (state) {
                case LOCKED -> updateCustomLocomotionAnimation(mc, false, false);
                case DASH, FLIGHT -> {
                    // Explicit ownership handoff: another state already owns this tick.
                }
                case WATER_RUN -> {
                    // Скорость и удержание на поверхности заданы в начале тика
                    // (applyGroundRunVelocity), двигает обычная физика Minecraft.
                    mc.player.setSwimming(false);
                    updateCustomLocomotionAnimation(mc, moving, true);
                }
                case WATER -> {
                    tickCustomWaterMovement(mc, dir);
                    updateCustomLocomotionAnimation(mc, moving, false);
                }
                case GROUND -> {
                    mc.player.setSwimming(false);
                    if (moving) {
                        // Скорость уже задана в начале тика (applyGroundRunVelocity),
                        // игрока двигает обычная физика Minecraft.
                        smoothGroundDescent(mc);
                    } else {
                        Vec3 v = mc.player.getDeltaMovement();
                        mc.player.setDeltaMovement(0.0, v.y, 0.0);
                    }
                    updateCustomLocomotionAnimation(mc, moving, superRun);
                }
                case AIR -> {
                    mc.player.setSwimming(false);
                    if (moving) {
                        Vec3 velocity = mc.player.getDeltaMovement();
                        mc.player.setDeltaMovement(dir.x * speed, velocity.y, dir.z * speed);
                    }
                    updateCustomLocomotionAnimation(mc, moving, superRun);
                }
            }
        }

        private static boolean runStepBoosted = false;
        private static float savedStepHeight = 0.6F;

        /**
         * Бег по земле: в НАЧАЛЕ тика задаём скорость, а двигает игрока ванильная физика.
         *
         * Раньше GROUND двигал игрока принудительным move() уже ПОСЛЕ тика игрока.
         * Minecraft не видел этого движения, поэтому:
         *  - тело не поворачивалось по направлению бега (бежал «боком», до 75° от головы);
         *  - тело и камера дёргались от остаточной скорости, были микрофризы;
         *  - каждый уступ в 1/16 блока перепрыгивался подъёмом на 0.125 блока.
         * Теперь ванильная физика сама поворачивает тело, скользит вдоль стен и
         * поднимается на уступы (высота шага временно 2 блока, как и раньше).
         */
        private static void applyGroundRunVelocity(Minecraft mc) {
            if (mc.player == null) {
                runStepBoosted = false;
                return;
            }

            boolean superRun = ClientModEvents.SUPER_SPEED_KEY.isDown();
            ClientMovementState state = hudBlindfold && mc.level != null && mc.screen == null
                    ? resolveMovementState(mc, superRun)
                    : ClientMovementState.LOCKED;
            boolean waterRun = state == ClientMovementState.WATER_RUN;
            boolean ground = state == ClientMovementState.GROUND || waterRun;

            if (ground) {
                if (!runStepBoosted) {
                    savedStepHeight = mc.player.maxUpStep();
                    runStepBoosted = true;
                }
                mc.player.setMaxUpStep(2.0F);

                // В полуприседе перед взлётом стоит на месте.
                Vec3 dir = takeoffCrouch ? Vec3.ZERO : clientHorizontalDirection(mc);
                Vec3 v = mc.player.getDeltaMovement();
                double vy = v.y;
                if (waterRun) {
                    // Поверхность воды держит игрока: подтягиваем к ней ступни и считаем
                    // это опорой (шаг вверх на берег, сброс прыжков, без урона от падения).
                    double target = waterRunSurfaceY(mc) + 0.03;
                    vy = Mth.clamp(target - mc.player.getY(), -0.30, 0.45);
                    mc.player.setOnGround(true);
                    mc.player.fallDistance = 0.0F;
                }
                if (dir.lengthSqr() >= 1.0E-6) {
                    double speed = superRun ? CUSTOM_RUN_BLOCKS_PER_TICK : CUSTOM_WALK_BLOCKS_PER_TICK;
                    mc.player.setDeltaMovement(dir.x * speed, vy, dir.z * speed);
                } else {
                    mc.player.setDeltaMovement(0.0, vy, 0.0);
                }
            } else if (runStepBoosted) {
                mc.player.setMaxUpStep(savedStepHeight);
                runStepBoosted = false;
            }
        }

        private static void performClientChargedJump(Minecraft mc, int tier) {
            if (mc.player == null || !hudBlindfold) return;
            if (mc.player.getAbilities().flying) return;

            int clamped = Mth.clamp(tier, 0, 1);
            boolean grounded = mc.player.onGround();

            // В воздухе никакой зарядки: только обычный прыжок.
            if (!grounded && clamped > 0) return;

            double yVelocity = clamped == 1 ? CHARGED_JUMP_VELOCITY : NORMAL_JUMP_VELOCITY;

            Vec3 old = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(old.x, yVelocity, old.z);
            mc.player.fallDistance = 0.0F;
        }

        private static void fireChargedJump(Minecraft mc, int tier) {
            // Не больше MAX_JUMPS прыжков подряд, включая прыжок с земли.
            if (jumpsUsed >= MAX_JUMPS) return;
            boolean grounded = mc.player != null && mc.player.onGround();
            jumpsUsed++;
            performClientChargedJump(mc, tier);
            NETWORK.sendToServer(new JumpControlPacket(tier));
            if (tier >= 1 && grounded) {
                // Взлёт: прыжок с эффектами, в верхней точке — зависание и полёт.
                MovementFxClient.localLeap();
                JujutsuNeonFlightClient.onTakeoff();
            }
        }

        // ---- двойные прыжки: клавиша J включает/выключает (по умолчанию выключены)
        private static boolean doubleJumpsEnabled = false;
        private static boolean doubleJumpsLoaded = false;
        private static boolean takeoffCrouch = false;

        private static java.nio.file.Path clientConfig(Minecraft mc) {
            return mc.gameDirectory.toPath().resolve("config").resolve("jujutsu_neon-client.properties");
        }

        private static void loadClientSettings(Minecraft mc) {
            if (doubleJumpsLoaded) return;
            doubleJumpsLoaded = true;
            try {
                java.nio.file.Path file = clientConfig(mc);
                if (java.nio.file.Files.exists(file)) {
                    java.util.Properties props = new java.util.Properties();
                    try (var in = java.nio.file.Files.newInputStream(file)) {
                        props.load(in);
                    }
                    doubleJumpsEnabled = Boolean.parseBoolean(props.getProperty("doubleJumps", "false"));
                }
            } catch (Exception ignored) {
            }
        }

        private static void saveClientSettings(Minecraft mc) {
            try {
                java.nio.file.Path file = clientConfig(mc);
                java.nio.file.Files.createDirectories(file.getParent());
                java.util.Properties props = new java.util.Properties();
                props.setProperty("doubleJumps", Boolean.toString(doubleJumpsEnabled));
                try (var out = java.nio.file.Files.newOutputStream(file)) {
                    props.store(out, "Jujutsu Neon client settings");
                }
            } catch (Exception ignored) {
            }
        }

        private static void processDoubleJumpToggle(Minecraft mc) {
            loadClientSettings(mc);
            while (ClientModEvents.DOUBLE_JUMP_KEY.consumeClick()) {
                doubleJumpsEnabled = !doubleJumpsEnabled;
                saveClientSettings(mc);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(doubleJumpsEnabled
                                    ? "Двойные прыжки: включены"
                                    : "Двойные прыжки: выключены")
                            .withStyle(ChatFormatting.AQUA), true);
                }
            }
        }

        @SubscribeEvent
        public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (!hudBlindfold || mc.player == null || event.getEntity() != mc.player) return;

            // Ordinary creative flight remains vanilla. Custom blindfold flight is still
            // owned by our controller and receives no vanilla motion impulses.
            if (mc.player.getAbilities().flying && !JujutsuNeonFlightClient.isCustomFlightActive()) return;

            // Пока надета повязка, vanilla jump полностью подавлен.
            // Space читается отдельно нашим state machine: поэтому удерживание
            // никогда не вызывает повторные ванильные подпрыгивания.
            event.getInput().forwardImpulse = 0.0F;
            event.getInput().leftImpulse = 0.0F;
            event.getInput().jumping = false;
        }

        @SubscribeEvent
        public static void onBlueAttackClick(InputEvent.InteractionKeyMappingTriggered event) {
            if (!hudBlueActive || !event.isAttack()) return;

            event.setCanceled(true);
            event.setSwingHand(true);
            NETWORK.sendToServer(new BlueActionPacket());
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            if (event.phase == TickEvent.Phase.START) {
                if (MaximumPurpleClient.isLocalActive() || DomainExpansionClient.locksLocalPlayer() || LapseBlueClient.locksLocalPlayer() || RedTechniqueClient.locksLocalPlayer()) {
                    mc.player.input.jumping = false;
                    return;
                }
                if (hudBlindfold && mc.screen == null) {
                    // Подавляем ванильный прыжок: с повязкой прыжками управляет наша зарядка.
                    mc.player.input.jumping = false;
                }
                applyGroundRunVelocity(mc);
                return;
            }

            if (event.phase != TickEvent.Phase.END) return;

            tickMaximumBlueClientVisuals();

            if (activeAnimTicks > 0) {
                activeAnimTicks--;
                if (activeAnimTicks <= 0) {
                    activeAnim = "NONE";
                    activeAnimLength = 1;
                }
            }

            if (mc.screen != null) {
                if (lastSpeedHeld) {
                    NETWORK.sendToServer(new MovementPacket(MovementAction.SPEED_OFF));
                    lastSpeedHeld = false;
                }
                jumpChargeWasDown = false;
                jumpChargeTicks = 0;
                jumpPressStartedInAir = false;
                pendingCreativeShortJumpTicks = -1;
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                chargingAnim = "NONE";
                resetPurpleHold();
                return;
            }

            // Кат-сцена Максимального Фиолетового или территории, обездвиживание: техники и движение недоступны.
            if (MaximumPurpleClient.isLocalActive() || DomainExpansionClient.locksLocalPlayer() || LapseBlueClient.locksLocalPlayer() || RedTechniqueClient.locksLocalPlayer()) {
                if (lastSpeedHeld) {
                    NETWORK.sendToServer(new MovementPacket(MovementAction.SPEED_OFF));
                    lastSpeedHeld = false;
                }
                while (ClientModEvents.HUD_KEY.consumeClick()) { }
                while (ClientModEvents.TELEPORT_KEY.consumeClick()) { }
                while (ClientModEvents.PURPLE_KEY.consumeClick()) { }
                jumpChargeWasDown = false;
                jumpChargeTicks = 0;
                jumpPressStartedInAir = false;
                pendingCreativeShortJumpTicks = -1;
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                chargingAnim = "NONE";
                chargingProgress = 0.0f;
                resetPurpleHold();
                return;
            }

            while (ClientModEvents.HUD_KEY.consumeClick()) {
                hudVisible = !hudVisible;
            }

            while (ClientModEvents.TELEPORT_KEY.consumeClick()) {
                if (hudBlindfold && mc.level != null) {
                    double maxDistance = Math.max(16.0, mc.options.renderDistance().get() * 16.0);
                    Vec3 start = mc.player.getEyePosition();
                    Vec3 end = start.add(mc.player.getLookAngle().normalize().scale(maxDistance));

                    BlockHitResult hit = mc.level.clip(new ClipContext(
                            start,
                            end,
                            ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.ANY,
                            mc.player
                    ));

                    if (hit.getType() != HitResult.Type.MISS) {
                        NETWORK.sendToServer(new TeleportPacket(hit.getBlockPos(), hit.getDirection()));
                        startAnim("TELEPORT", 8);
                    }
                }
            }

            while (ClientModEvents.PURPLE_KEY.consumeClick()) {
                // G обрабатывается по удержанию: см. processPurpleKey().
            }
            processPurpleKey();


            processDoubleJumpToggle(mc);
            boolean jumpHeldNow = mc.options.keyJump.isDown();

            // Счётчик прыжков сбрасывается на земле и в воде.
            if (mc.player.onGround() || mc.player.isInWaterOrBubble()) {
                jumpsUsed = 0;
            }

            if (pendingCreativeShortJumpTicks >= 0) {
                pendingCreativeShortJumpTicks--;

                if (pendingCreativeShortJumpTicks < 0 &&
                        hudBlindfold &&
                        !mc.player.getAbilities().flying) {
                    fireChargedJump(mc, 0);
                }
            }

            if (hudBlindfold && !mc.player.getAbilities().flying && !mc.player.isInWaterOrBubble()) {
                boolean pressedNow = jumpHeldNow && !jumpChargeWasDown;
                boolean releasedNow = !jumpHeldNow && jumpChargeWasDown;

                if (pressedNow) {
                    jumpChargeTicks = 0;
                    jumpPressStartedInAir = !mc.player.onGround();

                    // Creative double-Space остаётся доступным.
                    // Первый tap может быть обычным/воздушным прыжком,
                    // второй tap в окне включает vanilla creative flight.
                    if (mc.player.getAbilities().mayfly && mc.level != null) {
                        long nowClient = mc.level.getGameTime();

                        if (nowClient - creativeLastSpacePressTick <= 7L) {
                            pendingCreativeShortJumpTicks = -1;
                            creativeLastSpacePressTick = -1000L;
                            jumpChargeTicks = 0;
                            jumpPressStartedInAir = false;

                            mc.player.getAbilities().flying = true;
                            mc.player.onUpdateAbilities();
                        } else {
                            creativeLastSpacePressTick = nowClient;
                        }
                    }

                    // Воздух: прыжок только если двойные прыжки включены (клавиша J).
                    // Выключены (по умолчанию) — в воздухе Space ничего не делает, как в ванилле.
                    if (!mc.player.getAbilities().flying && jumpPressStartedInAir && doubleJumpsEnabled) {
                        fireChargedJump(mc, 0);
                    }
                }

                if (!mc.player.getAbilities().flying) {
                    if (jumpHeldNow && !jumpPressStartedInAir) {
                        // Зарядка существует ТОЛЬКО у нажатия, начавшегося на земле.
                        jumpChargeTicks = Math.min(JUMP_CHARGE_TICKS, jumpChargeTicks + 1);
                        // Держит Space — персонаж уходит в полуприсед, руки к телу (подготовка к взлёту).
                        if (jumpChargeTicks == 4 && !takeoffCrouch && mc.player.onGround()) {
                            takeoffCrouch = true;
                            MovementFxClient.localCrouch(true);
                        }
                    } else if (releasedNow) {
                        if (jumpPressStartedInAir) {
                            // Воздушный прыжок уже был выполнен на press.
                            // Release ничего больше не делает.
                            jumpChargeTicks = 0;
                        } else {
                            // 0.75 с и дольше — взлёт на ~13 блоков (дальше полёт), иначе обычный прыжок.
                            int tier = jumpChargeTicks >= JUMP_CHARGE_TICKS ? 1 : 0;
                            if (takeoffCrouch && tier == 0) MovementFxClient.localCrouch(false);
                            takeoffCrouch = false;

                            if (tier == 0 && mc.player.getAbilities().mayfly) {
                                // На земле оставляем короткое окно второго tap
                                // для стандартного creative flight.
                                pendingCreativeShortJumpTicks = 7;
                            } else {
                                fireChargedJump(mc, tier);
                            }

                            jumpChargeTicks = 0;
                        }

                        jumpPressStartedInAir = false;
                    }
                }

                jumpChargeWasDown = jumpHeldNow;
            } else {
                jumpChargeWasDown = false;
                jumpChargeTicks = 0;
                jumpPressStartedInAir = false;
                if (takeoffCrouch) {
                    takeoffCrouch = false;
                    MovementFxClient.localCrouch(false);
                }
            }

            // Dash Q is consumed exclusively by JujutsuNeonMovementPatchClient.

            boolean speedHeld = ClientModEvents.SUPER_SPEED_KEY.isDown();
            if (speedHeld != lastSpeedHeld) {
                NETWORK.sendToServer(new MovementPacket(speedHeld ? MovementAction.SPEED_ON : MovementAction.SPEED_OFF));
                lastSpeedHeld = speedHeld;
            }

            tickClientDash(mc);
            tickCustomMovement(mc);

            if (hudMaxBlueActive) {
                if (mc.options.keyUp.isDown() && !mc.options.keyDown.isDown()) {
                    NETWORK.sendToServer(new MaxBlueControlPacket(MaxBlueControlAction.FARTHER));
                } else if (mc.options.keyDown.isDown() && !mc.options.keyUp.isDown()) {
                    NETWORK.sendToServer(new MaxBlueControlPacket(MaxBlueControlAction.CLOSER));
                }

                mc.player.input.forwardImpulse = 0.0F;
                mc.player.input.leftImpulse = 0.0F;
                mc.player.input.jumping = false;
                mc.player.setSprinting(false);
            }

            if (hudPurpleCasting) {
                mc.player.input.forwardImpulse = 0.0F;
                mc.player.input.leftImpulse = 0.0F;
                mc.player.input.jumping = false;
                mc.player.setSprinting(false);
            }

            // Короткое нажатие и удержание 1 сек — разные способности.
            // Все базовые кнопки переназначаются через меню управления Minecraft.
            processBlueKey(ClientModEvents.BLUE_KEY, BLUE_STATE);
            processRedKey(ClientModEvents.RED_KEY, RED_STATE);
            processTapKey(ClientModEvents.DOMAIN_KEY, Ability.INFINITY_TOGGLE);
            processTapKey(ClientModEvents.UTILITY_KEY, Ability.RCT);
        }

        @SubscribeEvent
        public static void onRenderMaximumBlue(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            if (MAX_BLUE_VISUALS.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            float partialTick = event.getPartialTick();
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            PoseStack pose = event.getPoseStack();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            for (MaxBlueClientVisual visual : MAX_BLUE_VISUALS.values()) {
                float alpha = Mth.lerp(partialTick, visual.previousAlpha, visual.currentAlpha);
                float radius = Mth.lerp(partialTick, visual.previousRadius, visual.currentRadius);
                Vec3 pos = visual.previousPos.lerp(visual.currentPos, partialTick);

                if (alpha <= 0.01f || radius <= 0.02f) continue;

                pose.pushPose();
                pose.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);

                // Стабильное почти чёрное ядро.
                drawMaxBlueSphere(
                        pose,
                        radius * 0.68f,
                        18,
                        26,
                        0.003f, 0.010f, 0.030f,
                        Math.min(0.98f, alpha)
                );

                // Глубокая синяя оболочка.
                drawMaxBlueSphere(
                        pose,
                        radius * 0.90f,
                        20,
                        30,
                        0.00f, 0.08f, 0.22f,
                        alpha * 0.54f
                );

                // Внешняя плазменная сфера вращается независимо от камеры.
                float pulse = 1.0f + 0.025f * (float) Math.sin(
                        (mc.level.getGameTime() + partialTick) * 0.16
                );
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees(visual.rotation + partialTick * 1.35f));
                pose.mulPose(Axis.XP.rotationDegrees(visual.rotation * 0.37f));
                drawMaxBlueSphere(
                        pose,
                        radius * 1.03f * pulse,
                        22,
                        32,
                        0.00f, 0.30f, 0.94f,
                        alpha * 0.24f
                );
                pose.popPose();

                // Объёмные энергетические орбиты вокруг ядра.
                drawMaxBlueOrbit(
                        pose,
                        radius * 1.16f,
                        radius * 0.055f,
                        visual.rotation + partialTick * 1.35f,
                        0.12f, 0.70f, 1.00f,
                        alpha * 0.78f,
                        0
                );
                drawMaxBlueOrbit(
                        pose,
                        radius * 1.22f,
                        radius * 0.042f,
                        -visual.rotation * 0.82f,
                        0.02f, 0.42f, 1.00f,
                        alpha * 0.62f,
                        1
                );
                drawMaxBlueOrbit(
                        pose,
                        radius * 1.28f,
                        radius * 0.036f,
                        visual.rotation * 0.58f + 90.0f,
                        0.36f, 0.90f, 1.00f,
                        alpha * 0.55f,
                        2
                );

                pose.popPose();
            }

            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }

        private static void drawMaxBlueSphere(
                PoseStack pose,
                float radius,
                int latSegments,
                int lonSegments,
                float red,
                float green,
                float blue,
                float alpha
        ) {
            if (radius <= 0.0f || alpha <= 0.0f) return;

            Matrix4f matrix = pose.last().pose();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            for (int lat = 0; lat < latSegments; lat++) {
                double v0 = lat / (double) latSegments;
                double v1 = (lat + 1) / (double) latSegments;

                double phi0 = -Math.PI / 2.0 + Math.PI * v0;
                double phi1 = -Math.PI / 2.0 + Math.PI * v1;

                float y0 = (float) (Math.sin(phi0) * radius);
                float y1 = (float) (Math.sin(phi1) * radius);
                float ring0 = (float) (Math.cos(phi0) * radius);
                float ring1 = (float) (Math.cos(phi1) * radius);

                for (int lon = 0; lon < lonSegments; lon++) {
                    double u0 = lon / (double) lonSegments;
                    double u1 = (lon + 1) / (double) lonSegments;
                    double a0 = Math.PI * 2.0 * u0;
                    double a1 = Math.PI * 2.0 * u1;

                    float x00 = (float) (Math.cos(a0) * ring0);
                    float z00 = (float) (Math.sin(a0) * ring0);
                    float x01 = (float) (Math.cos(a1) * ring0);
                    float z01 = (float) (Math.sin(a1) * ring0);
                    float x10 = (float) (Math.cos(a0) * ring1);
                    float z10 = (float) (Math.sin(a0) * ring1);
                    float x11 = (float) (Math.cos(a1) * ring1);
                    float z11 = (float) (Math.sin(a1) * ring1);

                    maxBlueVertex(buffer, matrix, x00, y0, z00, red, green, blue, alpha);
                    maxBlueVertex(buffer, matrix, x10, y1, z10, red, green, blue, alpha);
                    maxBlueVertex(buffer, matrix, x11, y1, z11, red, green, blue, alpha);

                    maxBlueVertex(buffer, matrix, x00, y0, z00, red, green, blue, alpha);
                    maxBlueVertex(buffer, matrix, x11, y1, z11, red, green, blue, alpha);
                    maxBlueVertex(buffer, matrix, x01, y0, z01, red, green, blue, alpha);
                }
            }

            BufferUploader.drawWithShader(buffer.end());
        }

        private static void drawMaxBlueOrbit(
                PoseStack pose,
                float orbitRadius,
                float thickness,
                float rotationDeg,
                float red,
                float green,
                float blue,
                float alpha,
                int plane
        ) {
            Matrix4f matrix = pose.last().pose();
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            int segments = 72;
            float half = Math.max(0.018f, thickness);

            for (int i = 0; i < segments; i++) {
                double a0 = Math.PI * 2.0 * i / segments + Math.toRadians(rotationDeg);
                double a1 = Math.PI * 2.0 * (i + 1) / segments + Math.toRadians(rotationDeg);

                Vec3 q0 = maxBlueOrbitPoint(a0, orbitRadius + half, plane);
                Vec3 q1 = maxBlueOrbitPoint(a1, orbitRadius + half, plane);
                Vec3 r0 = maxBlueOrbitPoint(a0, orbitRadius - half, plane);
                Vec3 r1 = maxBlueOrbitPoint(a1, orbitRadius - half, plane);

                maxBlueVertex(buffer, matrix, (float) q0.x, (float) q0.y, (float) q0.z, red, green, blue, alpha);
                maxBlueVertex(buffer, matrix, (float) q1.x, (float) q1.y, (float) q1.z, red, green, blue, alpha);
                maxBlueVertex(buffer, matrix, (float) r1.x, (float) r1.y, (float) r1.z, red, green, blue, alpha);

                maxBlueVertex(buffer, matrix, (float) q0.x, (float) q0.y, (float) q0.z, red, green, blue, alpha);
                maxBlueVertex(buffer, matrix, (float) r1.x, (float) r1.y, (float) r1.z, red, green, blue, alpha);
                maxBlueVertex(buffer, matrix, (float) r0.x, (float) r0.y, (float) r0.z, red, green, blue, alpha);
            }

            BufferUploader.drawWithShader(buffer.end());
        }

        private static Vec3 maxBlueOrbitPoint(double angle, double radius, int plane) {
            double c = Math.cos(angle) * radius;
            double s = Math.sin(angle) * radius;

            return switch (plane) {
                case 1 -> new Vec3(c, s * 0.72, s * 0.34);
                case 2 -> new Vec3(c * 0.35, s, c);
                default -> new Vec3(c, s * 0.28, s);
            };
        }

        private static void maxBlueVertex(
                BufferBuilder buffer,
                Matrix4f matrix,
                float x, float y, float z,
                float red, float green, float blue, float alpha
        ) {
            buffer.vertex(matrix, x, y, z)
                    .color(red, green, blue, alpha)
                    .endVertex();
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            // Без повязки мод не рисует вообще ничего поверх обычного Minecraft.
            if (!hudVisible || !hudBlindfold) return;
            // Во время кат-сцены Максимального Фиолетового HUD скрыт.
            if (MaximumPurpleClient.isLocalActive()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;

            GuiGraphics g = event.getGuiGraphics();
            int sw = event.getWindow().getGuiScaledWidth();
            int sh = event.getWindow().getGuiScaledHeight();

            /*
             * Компактный HUD в стиле референса:
             * - только правый нижний угол;
             * - никаких больших полноэкранных карточек;
             * - короткие названия + клавиши;
             * - CE и зарядка отдельными тонкими полосами над биндами.
             */
            int panelW = 118;
            int rowH = 14;
            int rows = 9;
            int panelH = rows * rowH;
            int right = sw - 8;
            int x = right - panelW;
            int bottom = sh - 42;
            int y = bottom - panelH;

            // Едва заметная общая подложка — панель не должна мешать обзору.
            g.fill(x - 3, y - 3, right + 1, bottom + 3, 0x5205090F);

            int sy = y;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.DASH_KEY), "Dash", false);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.SUPER_SPEED_KEY), "Run", false);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.BLUE_KEY), "Blue", hudBlueActive || hudMaxBlueActive);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.RED_KEY), "Red", false);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.PURPLE_KEY), "Purple", hudPurpleCasting);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.DOMAIN_KEY), "Infinity", hudInfinity);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.UTILITY_KEY), "RCT", false);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.TELEPORT_KEY), "Teleport", false);
            sy += rowH;
            drawCompactSkillRow(g, mc, x, sy, panelW, keyName(ClientModEvents.DOMAIN_EXPANSION_KEY), "Domain", DomainExpansionClient.isLocalCutscene());

            // Проклятая энергия — тонкая полоска прямо над биндами.
            int ceY = y - 13;
            int ceW = panelW;
            int energyW = (int) Math.round(ceW * Mth.clamp(hudEnergy / CE_MAX, 0.0, 1.0));

            g.fill(x, ceY, x + ceW, ceY + 7, 0xA0151820);
            g.fill(x, ceY, x + energyW, ceY + 7, 0xD925D9FF);
            g.fill(x, ceY + 5, x + energyW, ceY + 7, 0xD98B3DFF);

            String ceText = "CE " + (int) Math.round(hudEnergy) + "%";
            g.drawString(
                    mc.font,
                    ceText,
                    x + ceW - mc.font.width(ceText),
                    ceY - 9,
                    0xDDE8FAFF,
                    false
            );

            // Контекст техники — одна короткая строка, без отдельной большой карточки.
            String status = null;
            int statusColor = 0xFFEAF8FF;

            if (hudPurpleCasting) {
                status = "PURPLE // CAST";
                statusColor = 0xFFC67BFF;
            } else if (hudMaxBlueActive) {
                status = "MAX BLUE // W/S";
                statusColor = 0xFF66DFFF;
            } else if (hudBlueActive) {
                status = "BLUE // ЛКМ";
                statusColor = 0xFF66DFFF;
            } else if (hudInfinity) {
                status = "INFINITY // ON";
                statusColor = 0xFF6CEBFF;
            }

            if (status != null) {
                g.drawString(
                        mc.font,
                        status,
                        right - mc.font.width(status),
                        ceY - 20,
                        statusColor,
                        false
                );
            }

            // Зарядка техники — маленькая полоска над CE.
            if (!"NONE".equals(chargingAnim)) {
                int chargeY = ceY - 28;
                int cw = (int) ((ceW - 2) * Mth.clamp(chargingProgress, 0.0f, 1.0f));

                g.fill(x, chargeY, x + ceW, chargeY + 6, 0x8A10131A);
                g.fill(x + 1, chargeY + 1, x + 1 + cw, chargeY + 5, 0xE69A49FF);

                if ("CHARGE_MAX_PURPLE".equals(chargingAnim)) {
                    String label = chargingProgress >= 1.0f ? "MAX PURPLE" : "MAX PURPLE " + (int) (chargingProgress * 100.0f) + "%";
                    g.drawString(mc.font, label, right - mc.font.width(label), chargeY - 10, 0xFFC58BFF, false);
                }
            }

            // Шкала заряженного прыжка остаётся снизу по центру, но тоже компактная.
            if (jumpChargeTicks >= 3) {
                int jw = 72;
                int jx = sw / 2 - jw / 2;
                int jy = sh - 49;
                int fill = jw * Math.min(jumpChargeTicks, JUMP_CHARGE_TICKS) / JUMP_CHARGE_TICKS;

                g.fill(jx - 1, jy - 1, jx + jw + 1, jy + 6, 0x88070A10);
                g.fill(jx, jy, jx + jw, jy + 5, 0xCC151B26);
                g.fill(jx, jy, jx + fill, jy + 5, 0xEE29D8FF);
            }
        }

        private static void drawCompactSkillRow(
                GuiGraphics g,
                Minecraft mc,
                int x,
                int y,
                int width,
                String key,
                String skill,
                boolean active
        ) {
            int keyW = Math.max(18, mc.font.width(key) + 6);
            int bg = active ? 0xA51A2733 : 0x72101419;
            int accent = active ? 0xFF72E9FF : 0xBB28D9FF;
            int text = active ? 0xFFFFFFFF : 0xDDE8F4F8;

            g.fill(x, y, x + width, y + 12, bg);
            g.fill(x, y, x + 2, y + 12, accent);

            g.fill(x + 4, y + 2, x + 4 + keyW, y + 10, 0xA0182530);
            g.drawCenteredString(
                    mc.font,
                    key,
                    x + 4 + keyW / 2,
                    y + 2,
                    0xFFFFFFFF
            );

            g.drawString(
                    mc.font,
                    skill,
                    x + 9 + keyW,
                    y + 2,
                    text,
                    false
            );
        }

        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            // Без повязки руки рендерятся полностью ванильно.
            if (!hudBlindfold) return;

            PoseStack pose = event.getPoseStack();
            boolean main = event.getHand() == InteractionHand.MAIN_HAND;
            float side = main ? 1.0f : -1.0f;

            if (!"NONE".equals(chargingAnim)) {
                float p = chargingProgress;
                float pulse = 0.75f + 0.25f * (float) Math.sin(p * Math.PI * 6.0);

                // Поза зарядки: руки сходятся к центру, напоминает ручные печати,
                // но не копирует конкретную анимацию из аниме.
                pose.translate(-side * 0.22 * p, -0.12 * p, -0.30 * p);
                pose.mulPose(Axis.XP.rotationDegrees(-42.0f * p));
                pose.mulPose(Axis.YP.rotationDegrees(side * (48.0f * p + 8.0f * pulse)));
                pose.mulPose(Axis.ZP.rotationDegrees(-side * 18.0f * p));
                return;
            }

            if (activeAnimTicks <= 0) return;

            float t = 1.0f - activeAnimTicks / (float) activeAnimLength;

            if ("PURPLE_CAST".equals(activeAnim)) {
                float gather = Mth.clamp(t / 0.70f, 0.0f, 1.0f);
                float release = Mth.clamp((t - 0.70f) / 0.30f, 0.0f, 1.0f);
                float pulse = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 10.0);

                // Crossed-hands / finger-sign approximation for first person.
                pose.translate(
                        -side * (0.34f * gather - 0.14f * release),
                        -0.18f * gather + 0.06f * release,
                        -0.40f * gather - 0.18f * release
                );
                pose.mulPose(Axis.XP.rotationDegrees(-58.0f * gather - 18.0f * release));
                pose.mulPose(Axis.YP.rotationDegrees(side * (68.0f * gather - 22.0f * release)));
                pose.mulPose(Axis.ZP.rotationDegrees(-side * (34.0f * gather + 10.0f * pulse)));
                return;
            }

            float wave = (float) Math.sin(t * Math.PI);

            switch (activeAnim) {
                case "BLUE", "MAX_BLUE" -> {
                    pose.translate(-side * 0.16 * wave, -0.10 * wave, -0.18 * wave);
                    pose.mulPose(Axis.YP.rotationDegrees(side * 36.0f * wave));
                    pose.mulPose(Axis.XP.rotationDegrees(-28.0f * wave));
                }
                case "RED", "HOLLOW_PURPLE" -> {
                    pose.translate(side * 0.10 * wave, -0.06 * wave, -0.32 * wave);
                    pose.mulPose(Axis.XP.rotationDegrees(-58.0f * wave));
                    pose.mulPose(Axis.ZP.rotationDegrees(side * 22.0f * wave));
                }
                case "MAX_RED" -> {
                    pose.translate(side * 0.14 * wave, -0.11 * wave, -0.46 * wave);
                    pose.mulPose(Axis.XP.rotationDegrees(-68.0f * wave));
                    pose.mulPose(Axis.YP.rotationDegrees(side * 16.0f * wave));
                    pose.mulPose(Axis.ZP.rotationDegrees(side * 28.0f * wave));
                }
                case "CURSED_BARRAGE" -> {
                    pose.translate(0, 0, -0.48 * wave);
                    pose.mulPose(Axis.XP.rotationDegrees(-22.0f * wave));
                    pose.mulPose(Axis.ZP.rotationDegrees(side * 10.0f * wave));
                }
                case "DOMAIN", "INFINITY_TOGGLE", "RCT", "TELEPORT" -> {
                    pose.translate(-side * 0.25 * wave, -0.17 * wave, -0.22 * wave);
                    pose.mulPose(Axis.XP.rotationDegrees(-48.0f * wave));
                    pose.mulPose(Axis.YP.rotationDegrees(side * 55.0f * wave));
                }
                case "FRONT_DASH" -> {
                    pose.translate(0.0, -0.03 * wave, -0.38 * wave);
                    pose.mulPose(Axis.XP.rotationDegrees(-28.0f * wave));
                }
                case "SIDE_DASH_LEFT" -> {
                    pose.translate(0.22 * wave, -0.02 * wave, -0.10 * wave);
                    pose.mulPose(Axis.ZP.rotationDegrees(-side * 24.0f * wave));
                }
                case "SIDE_DASH_RIGHT" -> {
                    pose.translate(-0.22 * wave, -0.02 * wave, -0.10 * wave);
                    pose.mulPose(Axis.ZP.rotationDegrees(side * 24.0f * wave));
                }
            }
        }
    }

    /**
     * Full-bright particle renderer for the five primary native-4K techniques.
     *
     * The 4096 sprite carries micro-detail while the existing DustParticleOptions
     * continue to provide true 3D volume. The billboard slowly rotates/pulses,
     * which prevents the high-resolution art from looking like a static flat PNG.
     */
    private static class Technique4KParticle extends TextureSheetParticle {
        private final SpriteSet sprites;
        private final float baseSize;
        private final float growth;
        private final float spinSpeed;

        protected Technique4KParticle(
                ClientLevel level,
                double x, double y, double z,
                double xd, double yd, double zd,
                SpriteSet sprites,
                float size,
                int lifetime,
                float growth,
                float spinSpeed
        ) {
            super(level, x, y, z, xd, yd, zd);
            this.sprites = sprites;
            this.baseSize = size;
            this.growth = growth;
            this.spinSpeed = spinSpeed;
            this.lifetime = lifetime;
            this.quadSize = size;
            this.hasPhysics = false;
            this.gravity = 0.0f;
            this.friction = 1.0f;
            this.alpha = 0.0f;
            this.roll = this.random.nextFloat() * ((float) Math.PI * 2.0f);
            this.oRoll = this.roll;
            this.setSpriteFromAge(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (this.removed) return;

            this.setSpriteFromAge(this.sprites);

            float life = this.age / (float) Math.max(1, this.lifetime);
            float fadeIn = Mth.clamp(life / 0.16f, 0.0f, 1.0f);
            float fadeOut = Mth.clamp((1.0f - life) / 0.30f, 0.0f, 1.0f);
            float envelope = Math.min(fadeIn, fadeOut);
            float pulse = 1.0f + 0.045f * (float) Math.sin(life * Math.PI * 6.0);

            this.alpha = Mth.clamp(envelope * 0.92f, 0.0f, 0.92f);
            this.quadSize = this.baseSize *
                    (1.0f + life * this.growth * 20.0f) *
                    pulse;

            this.oRoll = this.roll;
            this.roll += this.spinSpeed;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public int getLightColor(float partialTick) {
            return 0xF000F0;
        }
    }

    private static class Technique4KProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final float size;
        private final int lifetime;
        private final float growth;
        private final float spinSpeed;

        Technique4KProvider(
                SpriteSet sprites,
                float size,
                int lifetime,
                float growth,
                float spinSpeed
        ) {
            this.sprites = sprites;
            this.size = size;
            this.lifetime = lifetime;
            this.growth = growth;
            this.spinSpeed = spinSpeed;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel level,
                double x, double y, double z,
                double xd, double yd, double zd
        ) {
            return new Technique4KParticle(
                    level,
                    x, y, z,
                    xd, yd, zd,
                    sprites,
                    size,
                    lifetime,
                    growth,
                    spinSpeed
            );
        }
    }

    private static class UltraVfxParticle extends TextureSheetParticle {
        private final SpriteSet sprites;
        private final float baseSize;
        private final float growth;

        protected UltraVfxParticle(
                ClientLevel level,
                double x, double y, double z,
                double xd, double yd, double zd,
                SpriteSet sprites,
                float size,
                int lifetime,
                float growth
        ) {
            super(level, x, y, z, xd, yd, zd);
            this.sprites = sprites;
            this.baseSize = size;
            this.growth = growth;
            this.lifetime = lifetime;
            this.quadSize = size;
            this.hasPhysics = false;
            this.friction = 0.92f;
            this.gravity = 0.0f;
            this.alpha = 1.0f;
            this.setSpriteFromAge(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (!this.removed) {
                this.setSpriteFromAge(this.sprites);
                float life = this.age / (float) Math.max(1, this.lifetime);
                this.alpha = Mth.clamp((1.0f - life) * 1.25f, 0.0f, 1.0f);
                this.quadSize = this.baseSize * (1.0f + life * this.growth * 20.0f);
            }
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override
        public int getLightColor(float partialTick) {
            return 0xF000F0;
        }
    }

    private static class UltraVfxProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final float size;
        private final int lifetime;
        private final float growth;

        UltraVfxProvider(SpriteSet sprites, float size, int lifetime, float growth) {
            this.sprites = sprites;
            this.size = size;
            this.lifetime = lifetime;
            this.growth = growth;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel level,
                double x, double y, double z,
                double xd, double yd, double zd
        ) {
            return new UltraVfxParticle(level, x, y, z, xd, yd, zd, sprites, size, lifetime, growth);
        }
    }

    /**
     * Предмет-активатор способностей.
     *
     * Материал возвращает имя "leather", поэтому без отдельной модели
     * Minecraft использует совместимое поведение шлема. Позже можно
     * подложить собственную модель/текстуру повязки через resources.
     */
    private static class GojoBlindfoldItem extends ArmorItem {

        public GojoBlindfoldItem(ArmorMaterial material, Type type, Properties properties) {
            super(material, type, properties);
        }

        @Override
        public Component getName(ItemStack stack) {
            return Component.literal("Повязка Годжо")
                    .withStyle(ChatFormatting.LIGHT_PURPLE);
        }

        @Override
        public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
            return MODID + ":textures/models/armor/gojo_layer_1.png";
        }
    }

    private enum GojoBlindfoldMaterial implements ArmorMaterial {
        INSTANCE;

        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            return 275;
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return type == ArmorItem.Type.HELMET ? 2 : 0;
        }

        @Override
        public int getEnchantmentValue() {
            return 18;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_LEATHER;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }

        @Override
        public String getName() {
            // Используем ванильную leather-текстуру брони как fallback.
            return "leather";
        }

        @Override
        public float getToughness() {
            return 0.0F;
        }

        @Override
        public float getKnockbackResistance() {
            return 0.0F;
        }
    }
}