package com.sablednah.storyteller.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player-facing text, delegated to LegendQuest's own {@code Feedback}.
 *
 * <p>Deliberately a thin wrapper rather than a copy. LegendQuest's version
 * turns {@code &} codes into real component styles instead of leaving section
 * signs in the string — a distinction that only shows up from outside the
 * game, where {@code getString()} hands the codes back verbatim and a console
 * or RCON reader gets {@code &7CHR: &f14} instead of a sentence. That was
 * learned there the hard way; a second implementation here would be a second
 * chance to get it wrong.</p>
 *
 * <p>The wrapper exists so that if LegendQuest ever moves the class, one file
 * changes rather than every call site in this mod.</p>
 */
public final class Feedback {

    public static void chat(ServerPlayer player, String text) {
        com.sablednah.legendquest.neoforge.Feedback.chat(player, text);
    }

    public static void actionBar(ServerPlayer player, String text) {
        com.sablednah.legendquest.neoforge.Feedback.actionBar(player, text);
    }

    /** A notice that survives an open GUI on a modded client, plain chat otherwise. */
    public static void notify(ServerPlayer player, String text) {
        com.sablednah.legendquest.neoforge.Feedback.notify(player, text);
    }

    /** For command sources, which may be the console rather than a player. */
    public static Component colored(String text) {
        return com.sablednah.legendquest.neoforge.Feedback.colored(text);
    }

    private Feedback() {}
}
