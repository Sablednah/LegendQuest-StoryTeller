package com.sablednah.storyteller.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sablednah.storyteller.StoryTeller;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Keybinds for the four tools a Storyteller reaches for with a mob moving.
 *
 * <p><b>Why keys exist at all, given the commands and the buttons.</b> Both of
 * those need your hands off the mouse. Typing {@code /st lock} at a wandering
 * cow means hoping it is still under the crosshair when you finish the word,
 * and clicking a button means opening the inventory, which puts a screen
 * between you and the thing you were aiming at. A key is the only form that
 * works while you are still <i>tracking</i>. That is the whole reason for this
 * class, and it is why these four and not others: possess, lock, drift and
 * next are the ones aimed at something.</p>
 *
 * <p><b>A key sends a command string, exactly like a button does.</b>
 * {@code sendCommand} is the same path the chat box uses, so the server cannot
 * tell a key from typing and there is no packet, no payload and no handler of
 * our own to keep in step. It also means the keys inherit the toggles for
 * free: one key possesses and releases, one drifts and returns, one locks and
 * lets go.</p>
 *
 * <p><b>Unbound by default, deliberately.</b> A mod that claims keys on install
 * is how conflicts start, and the obvious letters are taken — LegendQuest alone
 * holds K, R, G, H and B. Exactly one player on a server is the Storyteller;
 * everyone else installing this client would be donating four keys to nothing.
 * So they arrive empty and the mod <i>tells</i> the Storyteller they are there,
 * which is the trade that costs nobody anything.</p>
 *
 * <p><b>Silence is the right no-op.</b> The server sends each client a command
 * tree already filtered by {@code requires}, so {@code /st} is simply absent
 * for anyone without the permission node — and absent entirely on a server with
 * no StoryTeller. A key press checks the tree and does nothing when the command
 * is not offered. A key brushed by someone who does not have the tool should
 * not produce an error about a command they never typed.</p>
 */
public final class STKeyMappings {

    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(StoryTeller.MODID, "main"));

    /** Take over what you are aiming at — or let go, since the command toggles. */
    public static final KeyMapping POSSESS = new KeyMapping(
            "key.storyteller.possess", InputConstants.UNKNOWN.getValue(), CATEGORY);
    /** Hold the sights on what you are aiming at — or let it go. */
    public static final KeyMapping LOCK = new KeyMapping(
            "key.storyteller.lock", InputConstants.UNKNOWN.getValue(), CATEGORY);
    /** Leave your body — or come back to it. */
    public static final KeyMapping DRIFT = new KeyMapping(
            "key.storyteller.drift", InputConstants.UNKNOWN.getValue(), CATEGORY);
    /** Jump to the next player at the table. */
    public static final KeyMapping NEXT = new KeyMapping(
            "key.storyteller.next", InputConstants.UNKNOWN.getValue(), CATEGORY);
    /** Bring the party to where you are standing. */
    public static final KeyMapping SUMMON = new KeyMapping(
            "key.storyteller.summon", InputConstants.UNKNOWN.getValue(), CATEGORY);

    private static final KeyMapping[] ALL = { POSSESS, LOCK, DRIFT, NEXT, SUMMON };

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        for (KeyMapping key : ALL) event.register(key);
    }

    /** Called on ClientTickEvent.Post from the client entrypoint. */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        hintOnce(mc);

        drain(mc, POSSESS, "st possess");
        drain(mc, LOCK, "st lock");
        drain(mc, DRIFT, "st drift");
        drain(mc, NEXT, "st next");
        drain(mc, SUMMON, "st summon");
    }

    /**
     * One command per queued press.
     *
     * <p><b>There is deliberately no "is a screen open" check.</b> The first
     * draft had one, to stop presses queueing behind an open inventory and all
     * firing at once when it closed — which for a toggle is worse than losing
     * them, since an odd number lands somewhere the Storyteller was not
     * looking. It cannot happen: {@code KeyboardHandler} only calls
     * {@code KeyMapping.click} when the game itself is handling input, so a
     * press during a screen never reaches the queue at all. The check was
     * guarding against something vanilla already prevents — and it was the one
     * line in this class that did not survive 26.2, which moved the screen to
     * {@code minecraft.gui.screen()}. Removing it is both simpler and the
     * reason this file ports untouched.</p>
     */
    private static void drain(Minecraft mc, KeyMapping key, String command) {
        while (key.consumeClick()) {
            send(mc, command);
        }
    }

    /**
     * Send it as if typed, or do nothing at all.
     *
     * <p>The check is on the client's own copy of the command tree, which the
     * server built for this player: a node missing there means either no
     * StoryTeller on the server or no permission for this player, and both
     * answers are "nothing happens".</p>
     */
    private static void send(Minecraft mc, String command) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        if (connection.getCommands().findNode(nodePath(command)) == null) return;
        connection.sendCommand(command);
    }

    private static java.util.List<String> nodePath(String command) {
        return java.util.List.of(command.split(" "));
    }

    /** Whether this player is offered {@code /st} at all, by the server's own
     *  filtered tree — the only permission answer a client can have. */
    private static boolean storytellerHere(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        return connection != null
                && connection.getCommands().findNode(java.util.List.of("st")) != null;
    }

    /**
     * Say once, per connection, that the keys exist and are empty.
     *
     * <p>Unbound-by-default is the right call and an invisible one: a
     * Storyteller has no way to discover four keys that do nothing. So the
     * moment the server confirms they <i>are</i> a Storyteller — by sending a
     * tree with {@code /st} in it — say so, and only then. Someone without the
     * permission never hears about a tool they cannot use, and a Storyteller
     * who has already bound one is not told again.</p>
     */
    private static void hintOnce(Minecraft mc) {
        if (hinted || mc.player == null || !storytellerHere(mc)) return;
        hinted = true;
        for (KeyMapping key : ALL) {
            if (!key.isUnbound()) return;
        }
        // THE one line in this class that differs per branch. 1.21.11 has
        // displayClientMessage and no sendSystemMessage on LocalPlayer; 26.x
        // has the reverse, and moved chat to Hud.getChat() as well, so there
        // is no single call that compiles on both. Left as a plain per-branch
        // delta rather than reflected around: it is one line, in the one file
        // that names client types, which is the arrangement that makes a
        // version drop cheap.
        mc.player.displayClientMessage(Component.literal(
                "§7StoryTeller keys are unbound. Bind possess, lock, drift and next in "
                + "§fOptions → Controls → StoryTeller§7 to use them while aiming."), false);
    }

    /** Reset per connection, so switching servers says it again where it applies. */
    private static boolean hinted;

    static void onDisconnect() {
        hinted = false;
    }

    private STKeyMappings() {}
}
