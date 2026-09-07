package com.sablednah.storyteller.neoforge;

import java.util.List;
import java.util.UUID;

import com.sablednah.standards.api.reputation.Reputation;

/**
 * Standing with a faction, town or whoever else keeps score.
 *
 * <p>One of this mod's Standards support classes, and like the others the
 * {@code ModList.isLoaded("standards")} guard lives outside it — naming a class
 * is what loads it, so an unguarded call would be a
 * {@code NoClassDefFoundError} on every server without Standards. (The list of
 * which classes those are is deliberately not enumerated here; it went stale
 * twice in one day when it was.)</p>
 *
 * <p><b>Reputation is not karma</b>, and StoryTeller offers both. Karma is
 * LegendQuest's own single moral axis, which drives titles and gates feats.
 * Reputation is Standards' ledger of standing on a named track — with a
 * faction, a town, a guild — so a character can be loved in one place and
 * hated in the next. A GM rewarding "the smuggler job" usually means the
 * second one.</p>
 *
 * <p>Standards having the API is not the same as the server having reputation:
 * {@link Reputation#isAvailable()} is false until some provider registers.
 * Both questions are asked before a reward promises anyone standing.</p>
 */
final class ReputationSupport {

    static boolean available() {
        return Reputation.isAvailable();
    }

    /** The tracks this server actually keeps, for tab-completion — a GM should
     *  not have to remember what the provider called things. */
    static List<String> standings() {
        return Reputation.standings();
    }

    /** @return the player's new standing on that track. */
    static int adjust(UUID player, String standing, int delta, String reason) {
        return Reputation.adjust(player, standing, delta, reason);
    }

    private ReputationSupport() {}
}
