package com.kira.jujutsuneon;

import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Живая анимация тела в кат-сцене Максимального Фиолетового через встроенный Player Animator
 * (+ bendy-lib для сгибов локтей, коленей и корпуса).
 * Ключевые кадры: assets/jujutsu_neon/player_animation/max_purple.json (генератор tools/gen_max_purple_anim.py).
 * Только клиент.
 */
final class MaximumPurpleAnimation {

    private static final ResourceLocation ID = new ResourceLocation(JujutsuNeonMod.MODID, "max_purple");
    private static final int LAYER_PRIORITY = 2000;
    private static final Map<AbstractClientPlayer, ModifierLayer<IAnimation>> LAYERS = new WeakHashMap<>();

    private MaximumPurpleAnimation() {
    }

    static void play(AbstractClientPlayer player) {
        if (player == null) return;
        try {
            KeyframeAnimation animation = PlayerAnimationRegistry.getAnimation(ID);
            if (animation == null) return;
            ModifierLayer<IAnimation> layer = layer(player);
            if (layer == null) return;
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(3, Ease.INOUTSINE),
                    new KeyframeAnimationPlayer(animation), true);
        } catch (RuntimeException | LinkageError ignored) {
            // Библиотека не загрузилась — кат-сцена идёт без анимации тела.
        }
    }

    static void stop(AbstractClientPlayer player) {
        if (player == null) return;
        try {
            ModifierLayer<IAnimation> layer = LAYERS.get(player);
            if (layer != null && layer.getAnimation() != null) {
                layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE), null);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    static boolean isPlaying(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = player == null ? null : LAYERS.get(player);
        return layer != null && layer.isActive();
    }

    private static ModifierLayer<IAnimation> layer(AbstractClientPlayer player) {
        ModifierLayer<IAnimation> layer = LAYERS.get(player);
        if (layer != null) return layer;
        try {
            layer = new ModifierLayer<>();
            PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(LAYER_PRIORITY, layer);
            LAYERS.put(player, layer);
            return layer;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
