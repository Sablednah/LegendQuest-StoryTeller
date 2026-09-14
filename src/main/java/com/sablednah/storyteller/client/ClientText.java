package com.sablednah.storyteller.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Telling the Storyteller something from the client.
 *
 * <p><b>A per-branch file.</b> 1.21.11 has {@code displayClientMessage} and no
 * {@code sendSystemMessage} on {@code LocalPlayer}; 26.x has the reverse and
 * moved chat to {@code Hud.getChat()}. So the call lives here, once, and a
 * version drop edits this file rather than hunting call sites.
 * {@code STKeyMappings.hintOnce} still makes the same call itself.</p>
 */
final class ClientText {

    static void chat(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(message, false);
    }

    /** The action bar. Shown for a few seconds, so callers refresh it. */
    static void overlay(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(message, true);
    }

    private ClientText() {}
}
