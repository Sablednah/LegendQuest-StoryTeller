package com.sablednah.storyteller.neoforge;

import java.util.UUID;

import com.sablednah.standards.api.economy.Economy;

/**
 * Money, when a server has any.
 *
 * <p><b>One of two classes in StoryTeller that import
 * {@code com.sablednah.standards}</b> — {@link VanishSupport} is the other —
 * and the {@code ModList.isLoaded} guard lives outside it in {@link Rewards};
 * naming a class is what loads it, so an unguarded call here would be a
 * {@code NoClassDefFoundError} on every server without Standards. LegendQuest keeps {@code ChatSupport} to the same rule
 * for the same reason; this is that discipline copied deliberately, not by
 * habit.</p>
 *
 * <p>Standards having an economy <em>API</em> is not the same as the server
 * having an economy: {@link Economy#isAvailable()} is false until some provider
 * registers. Both questions are asked before a reward promises anyone money.</p>
 */
final class EconomySupport {

    static boolean available() {
        return Economy.isAvailable();
    }

    /** @return true if the money actually landed. */
    static boolean deposit(UUID player, double amount, String reason) {
        return Economy.deposit(player, amount, reason).success();
    }

    static String format(double amount) {
        return Economy.format(amount);
    }

    private EconomySupport() {}
}
