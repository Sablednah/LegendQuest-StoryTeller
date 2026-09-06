package com.sablednah.storyteller.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Lifecycle handling: one job, which is that nobody is ever confused about
 * being a spectator.
 *
 * <p>The drift anchor is persisted, so logging out mid-scene and back in
 * leaves a Storyteller still drifting — which is correct, it is the state they
 * chose. What would not be correct is arriving as a spectator with no
 * explanation and no visible way out, so the login says both.</p>
 *
 * <p>There is deliberately no logout handler putting them back in their body.
 * Undoing a state the player deliberately entered, because they happened to
 * disconnect, is a decision the game should not make for them.</p>
 */
public final class STServerEvents {

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Presence.isDrifting(player)) return;
        Feedback.chat(player, "&7You are still drifting out of your body. "
                + "&f/st return&7 brings you back.");
    }

    private STServerEvents() {}
}
