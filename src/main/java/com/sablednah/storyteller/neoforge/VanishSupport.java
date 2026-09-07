package com.sablednah.storyteller.neoforge;

import com.sablednah.standards.api.vanish.Vanish;

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
 * <p><b>Read-only, for now.</b> Standards exposes vanish as a question, not a
 * state anything else can set, so this can report that a Storyteller is
 * visible but cannot hide them. A settable API has been requested; when it
 * lands, possession will hide them on taking a body and restore whatever they
 * were before — <em>restore</em>, not un-vanish, because a Storyteller who was
 * already vanished by their own choice must not be revealed by releasing a
 * cow.</p>
 */
final class VanishSupport {

    static boolean vanished(ServerPlayer player) {
        return Vanish.isVanished(player);
    }

    private VanishSupport() {}
}
