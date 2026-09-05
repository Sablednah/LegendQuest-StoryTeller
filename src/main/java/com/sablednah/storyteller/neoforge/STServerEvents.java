package com.sablednah.storyteller.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Lifecycle handling, which for now is one job: never leave a Storyteller
 * stranded out of their body.
 *
 * <p>Drifting is spectator mode plus a remembered anchor. Log out while
 * drifting and the anchor — held in memory — would go with the session, so the
 * next login is a spectator with nothing to return to. Restoring the game mode
 * on the way out costs one event handler and removes the whole failure.</p>
 *
 * <p>It deliberately restores the <em>mode</em> and not the position. Putting
 * someone back at their anchor as they disconnect risks writing a teleport
 * into a save that is mid-flush; leaving them where they were spectating is
 * harmless, and their mode is what actually decides whether they can play.</p>
 */
public final class STServerEvents {

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Presence.takeAnchorMode(player).ifPresent(player::setGameMode);
    }

    private STServerEvents() {}
}
