package com.kira.jujutsuneon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.function.Predicate;

/**
 * Костюм Годжо из 4 частей (причёска, костюм, штаны, ботинки).
 * Только полный комплект даёт техники, HUD и модель R15 со скином Годжо; без любой части — ничего не работает.
 */
public final class GojoSuit {

    public static final String SKIN = JujutsuNeonMod.MODID + ":textures/suit/gojo_suit_skin.png";
    private static final String EMPTY = JujutsuNeonMod.MODID + ":textures/suit/empty.png";

    /** Клиент ставит сюда «игрок сейчас рисуется моделью R15 со скином костюма» — тогда броня-одежда не нужна. */
    public static volatile Predicate<Entity> skinDrawn = e -> false;

    private GojoSuit() {
    }

    public static boolean isPiece(ItemStack stack) {
        return stack.getItem() instanceof Piece;
    }

    /** Надеты все 4 части. */
    public static boolean isFull(LivingEntity e) {
        if (e == null) return false;
        return isPiece(e.getItemBySlot(EquipmentSlot.HEAD)) && isPiece(e.getItemBySlot(EquipmentSlot.CHEST))
                && isPiece(e.getItemBySlot(EquipmentSlot.LEGS)) && isPiece(e.getItemBySlot(EquipmentSlot.FEET));
    }

    static Item create(ArmorItem.Type type) {
        return new Piece(type, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    static final class Piece extends ArmorItem {
        Piece(Type type, Properties properties) {
            super(Material.INSTANCE, type, properties);
        }

        private String part() {
            return switch (getType()) {
                case HELMET -> "head";
                case CHESTPLATE -> "chest";
                case LEGGINGS -> "legs";
                case BOOTS -> "feet";
            };
        }

        @Override
        public Component getName(ItemStack stack) {
            String name = switch (getType()) {
                case HELMET -> "Причёска Годжо";
                case CHESTPLATE -> "Костюм Годжо";
                case LEGGINGS -> "Штаны Годжо";
                case BOOTS -> "Ботинки Годжо";
            };
            return Component.literal(name).withStyle(ChatFormatting.AQUA);
        }

        @Override
        public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
            if (entity instanceof LivingEntity le && isFull(le) && skinDrawn.test(entity)) return EMPTY;
            return JujutsuNeonMod.MODID + ":textures/suit/armor_" + part() + ".png";
        }
    }

    private enum Material implements ArmorMaterial {
        INSTANCE;

        @Override
        public int getDurabilityForType(ArmorItem.Type type) {
            return 0;
        }

        @Override
        public int getDefenseForType(ArmorItem.Type type) {
            return 0;
        }

        @Override
        public int getEnchantmentValue() {
            return 0;
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
            return "jujutsu_neon:gojo_suit";
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
