package com.sablednah.storyteller.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Lifecycle handling: making sure no Storyteller is ever left stuck, and no
 * creature is ever left held by nobody.
 */
public final class STServerEvents {

    /**
     * The drift anchor is persisted, so logging out mid-scene and back in
     * leaves a Storyteller still drifting — which is correct, it is the state
     * they chose. What would not be correct is arriving as a spectator with no
     * explanation and no visible way out, so the login says both.
     *
     * <p>There is deliberately no logout handler putting them back in their
     * body. Undoing a state the player deliberately entered, because they
     * happened to disconnect, is a decision the game should not make for
     * them.</p>
     */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Presence.isDrifting(player)) return;
        Feedback.chat(player, "&7You are still drifting out of your body. "
                + "&f/st return&7 brings you back.");
    }

    /**
     * Possession, unlike drifting, <b>must</b> be undone on logout.
     *
     * <p>The goal holds a reference to the Storyteller and steers the mob
     * toward them every tick. Leave it attached when they disconnect and the
     * creature is frozen in place forever, following a player who is not
     * there — with no way for anyone still online to free it. Drifting can
     * safely persist because it costs nobody else anything; this cannot.</p>
     */
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Possession.forget(player);
            Sights.forget(player);
        }
    }

    /**
     * A possessed creature dying hands the Storyteller back their own eyes.
     *
     * <p>Without this they are left watching through a corpse: the camera
     * stays bound to a removed entity, which on a vanilla client is a black
     * screen they cannot fix from inside the game. The story can absolutely
     * kill an NPC mid-scene — that is drama — but it must not take the
     * Storyteller's view with it.</p>
     */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        // Before the possession handling, and unconditionally: a lock on this
        // creature is wrong now whether or not anybody was wearing it, and a
        // lock left pointing at a corpse would silently redirect the next
        // command to whatever the crosshair happened to find.
        Sights.mobWentAway(mob, "dies");
        Possession.possessorOf(mob).ifPresent(possessor -> {
            Possession.release(possessor);
            Feedback.chat(possessor, "&c" + mob.getName().getString()
                    + " dies, and you are cast out of it. &f/st return&c brings you back to your body.");
        });
    }

    /**
     * Walks every worn NPC body to its wearer.
     *
     * <p>Costs nothing when nobody is wearing an NPC — and nobody can be
     * unless Cast is installed, which is what keeps this tick from ever
     * naming a Cast class on a server without one.</p>
     */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        Possession.tick(event.getServer());
    }

    private STServerEvents() {}
}
