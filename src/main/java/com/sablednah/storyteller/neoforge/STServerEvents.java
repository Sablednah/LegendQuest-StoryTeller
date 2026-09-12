package com.sablednah.storyteller.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Lifecycle handling: making sure no Storyteller is ever left stuck, and no
 * creature is ever left held by nobody.
 */
public final class STServerEvents {

    /**
     * A body being driven does not tick.
     *
     * <p>Half of the fix for "I keep moving when I stop, like I'm on ice". The
     * creature had {@code noPhysics} set and the player's own delta movement
     * copied into it every tick, so between snaps it <em>coasted</em> under its
     * own {@code travel()} — moved on after the player stopped, and was hauled
     * back the following tick. On screen that is a creature sliding and
     * snapping, which is what ice looks like. With no tick there is nothing to
     * coast with: the position comes from the Storyteller, full stop.</p>
     *
     * <p><b>The obvious explanation was measured and was wrong.</b> Entity
     * push looked like the culprit — a mob in the same block as a player calls
     * {@code pushEntities} at it every tick — so it was tested directly, with
     * an ordinary undriven cow summoned into the player's own block. The player
     * did not move a thousandth of a block in nine seconds. A server-side push
     * on a player sets delta movement and nothing sends it, and the client
     * reports its own position back regardless. Worth keeping written down,
     * because it is a genuinely convincing wrong answer.</p>
     *
     * <p>{@code setNoAi} was never going to be enough on its own: it stops the
     * <em>goals</em>, not the living tick that travels, falls, drowns and
     * burns. Cancelling the tick is the honest statement of what driving
     * already means, and it costs nothing — every one of those side effects
     * would otherwise have to be suppressed one at a time.</p>
     *
     * <p>Server side only. The client must keep ticking it or the creature
     * stops animating — legs frozen mid-stride is exactly the thing driving is
     * for.</p>
     */
    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) return;
        if (Possession.isDrivenBody(event.getEntity())) event.setCanceled(true);
    }

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
            // Only mention /st return if they are actually out of their body.
            // A driver never left it -- they were walking the creature around
            // from inside their own skin -- so telling them how to come back
            // is an instruction to fix something that is not wrong.
            boolean away = Presence.isDrifting(possessor);
            Feedback.chat(possessor, "&c" + mob.getName().getString()
                    + " dies, and you are cast out of it."
                    + (away ? " &f/st return&c brings you back to your body." : ""));
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
