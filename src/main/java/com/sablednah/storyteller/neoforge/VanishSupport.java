package com.sablednah.storyteller.neoforge;

import com.sablednah.standards.api.vanish.Vanish;

import com.sablednah.storyteller.StoryTeller;

import net.minecraft.server.level.ServerPlayer;

/**
 * Whether the Storyteller is currently unseen.
 *
 * <p>One of two classes here that import {@code com.sablednah.standards} (the
 * other is {@link EconomySupport}), and like it, the
 * {@code ModList.isLoaded("standards")} guard lives outside — naming a class is
 * what loads it, so an unguarded call would be a {@code NoClassDefFoundError}
 * on every server without Standards.</p>
 *
 * <p><b>A keyed hold, not a boolean.</b> A player stays hidden while any hold
 * stands and each caller releases only its own key, so this never has to read
 * the state first and never has to reason about who else is involved: hold on
 * taking a body, release on giving it back, unconditionally. A Storyteller who
 * typed {@code /vanish} before the scene is still hidden after it, because
 * {@code /vanish} is itself a holder under a different key — and the naive
 * read-then-set version would have revealed them, which is precisely the bug
 * this shape removes rather than merely avoids.</p>
 *
 * <p><b>Holds do not survive a logout</b> (only the command's own does), so a
 * possession that outlived a disconnect would need the hold re-placed. This
 * mod never has that problem: logging out releases the possession itself.</p>
 *
 * <p>What vanish does and does not promise, asked rather than assumed: hidden
 * from other players, not pushable, no item pickup, and — since this was
 * asked — not targeted by mobs. It stays solid against blocks and subject to
 * gravity, which is the whole reason possession uses it instead of spectator.
 * The one honest limit is that clearing a mob's target cannot un-anger
 * something already hunting you: vanishing is walking away from a fight, not
 * undoing one.</p>
 */
final class VanishSupport {

    /** Our key in Standards' hold set. Namespaced so it can never collide with
     *  another mod's, or with the /vanish command's own. */
    private static final String HOLD = "storyteller:possess";

    /**
     * Set once {@code hold} turns out not to exist, so the failure is reported
     * once rather than every time anybody possesses anything.
     *
     * <p><b>Why a runtime guard and not a version check.</b>
     * {@code ModList.isLoaded("standards")} answers whether the mod is there,
     * not whether it is new enough, and the version string cannot answer it
     * either: the build carrying {@code hold} is still called 1.5.0, because a
     * version does not bump until release — so a server can run a 1.5.0 that
     * has it and a 1.5.0 that does not. Asking the method itself is the only
     * question with a true answer, and it is asked by calling it.</p>
     */
    private static boolean holdMissing = false;

    /** @return true if the player actually became hidden — false when somebody
     *          else was already holding them and nothing went on the wire, or
     *          when this Standards is too old to have holds at all. */
    static boolean hide(ServerPlayer player) {
        return hold(player, true);
    }

    /** @return true if the player actually became visible again. False when
     *          another hold still stands, which is the case worth reporting
     *          differently: they are not back in view yet. */
    static boolean reveal(ServerPlayer player) {
        return hold(player, false);
    }

    private static boolean hold(ServerPlayer player, boolean hidden) {
        if (holdMissing) return false;
        try {
            return Vanish.hold(player, HOLD, hidden);
        } catch (LinkageError missing) {
            // NoSuchMethodError and friends. An older Standards that predates
            // holds: everything else this mod does still works, so it says so
            // once and carries on visible rather than failing a possession.
            holdMissing = true;
            StoryTeller.LOGGER.warn("Standards on this server has no Vanish.hold, so the "
                    + "Storyteller stays visible while possessing. Update Standards to hide them.");
            return false;
        }
    }

    static boolean vanished(ServerPlayer player) {
        return Vanish.isVanished(player);
    }

    private VanishSupport() {}
}
