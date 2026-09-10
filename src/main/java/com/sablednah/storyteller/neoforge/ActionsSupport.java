package com.sablednah.storyteller.neoforge;

import com.sablednah.standards.api.actions.Action;
import com.sablednah.standards.api.actions.Actions;
import com.sablednah.storyteller.StoryTeller;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Storyteller's tools, on buttons.
 *
 * <p>One of this mod's Standards support classes; the
 * {@code ModList.isLoaded("standards")} guard lives outside it, in
 * {@link StoryTeller}.</p>
 *
 * <p><b>This costs a vanilla client nothing.</b> Standards renders this same
 * registry two ways: a drawn bar for anyone running its client half, and a row
 * of clickable chat components for anyone who is not. That matters here more
 * than it does for most mods — StoryTeller's rule is that only the server and
 * the Storyteller need it installed, so a control surface that demanded a
 * client mod would have been a control surface this mod could not use.</p>
 *
 * <p><b>An action carries a command string, not a payload.</b> There is no
 * serverbound message anywhere in this seam: a button sends {@code /st possess}
 * exactly as if the Storyteller had typed it. So the buttons cannot drift ahead
 * of the commands, and permissions, refusals and every message below happen
 * identically whichever way the command arrives. The commands remain the
 * interface; these are a faster way to reach them.</p>
 *
 * <p><b>The hint constructor is a supported overload, not the canonical one.</b>
 * Standards briefly lost it: adding a {@code children} component to the
 * {@code Action} record silently replaced the previous canonical shape, so this
 * file compiled against 1.21.11 and not against 26.x for a day. It is now an
 * explicit overload on all three lines, promised not to be removed, and
 * {@code children} is not required — so reading out what is being worn is safe
 * to rely on again.</p>
 *
 * <p><b>Three buttons, not five, because the commands toggle.</b> There is no
 * separate Release or Return: {@code /st possess} while wearing something lets
 * it go, and {@code /st drift} while drifting brings you back. Two buttons for
 * one idea reads as clunky, and the state is already on the screen — a lit
 * Possess button is telling you what the click will do. The seam needs nothing
 * for this: an action still carries exactly one command string, and the command
 * is what knows which direction it means.</p>
 *
 * <p><b>Why each one reports state and not just availability.</b> Most of a day
 * of play-testing went on the game and this mod disagreeing about what was
 * happening — a camera bound to something that had been released, a possession
 * that had silently ended. A Storyteller who can see <em>you are wearing a
 * cow</em> catches that in the moment rather than three commands later, so
 * every action here answers "are you doing this right now" wherever that
 * question has a meaning.</p>
 */
public final class ActionsSupport {

    /** Higher sits nearer the anchor; possession is what a Storyteller reaches
     *  for most, so it leads. */
    private static final int POSSESS = 50, DRIFT = 45, NEXT = 43;

    /**
     * Whether this Standards has the hint constructor.
     *
     * <p>Asked by building one and throwing it away, because that is the only
     * question with a true answer. The version number cannot answer it: builds
     * of 1.6.0 exist both with and without the overload, which is how an
     * already-shipped StoryTeller lost its buttons to a same-version rebuild
     * once already. Probing costs one object at startup and means the richer
     * form is used wherever it exists and the plainer one wherever it does not,
     * instead of the whole bar disappearing on a server that is behind.</p>
     */
    private static boolean hintSupported() {
        try {
            new Action("storyteller:probe", 0, Identifier.parse("minecraft:stone"),
                    "action.storyteller.possess", "st who",
                    p -> false, p -> false, p -> null);
            return true;
        } catch (LinkageError older) {
            StoryTeller.LOGGER.info("Standards here predates the Action hint, so the buttons will "
                    + "not name what you are wearing. Everything else is unaffected.");
            return false;
        }
    }

    public static void register() {
        boolean hints = hintSupported();
        // Wearing a face is the gesture, so the icon is the thing you wear.
        Actions.register(hints
                ? new Action("storyteller:possess", POSSESS,
                        Identifier.parse("minecraft:carved_pumpkin"),
                        "action.storyteller.possess", "st possess",
                        STPermissions::isStoryteller, Possession::isPossessing,
                        ActionsSupport::wornName)
                : new Action("storyteller:possess", POSSESS,
                        Identifier.parse("minecraft:carved_pumpkin"),
                        "action.storyteller.possess", "st possess",
                        STPermissions::isStoryteller, Possession::isPossessing));

        Actions.register(hints
                ? new Action("storyteller:drift", DRIFT,
                        Identifier.parse("minecraft:elytra"),
                        "action.storyteller.drift", "st drift",
                        STPermissions::isStoryteller, Presence::isDrifting,
                        player -> Presence.isDrifting(player) ? "out of your body" : null)
                : new Action("storyteller:drift", DRIFT,
                        Identifier.parse("minecraft:elytra"),
                        "action.storyteller.drift", "st drift",
                        STPermissions::isStoryteller, Presence::isDrifting));

        Actions.register(new Action("storyteller:next", NEXT,
                Identifier.parse("minecraft:spyglass"),
                "action.storyteller.next", "st next",
                Presence::isDrifting));

        StoryTeller.LOGGER.info("Registered 3 Storyteller actions with Standards");
    }

    /** What they are wearing, for the hint — the whole point of the state seam
     *  is that this reads "a cow" rather than merely "on". */
    private static String wornName(ServerPlayer player) {
        var mob = Possession.heldBy(player);
        if (mob.isPresent()) return mob.get().getName().getString();
        var npc = Possession.heldNpcBy(player);
        if (npc.isEmpty()) return null;
        var server = player.level().getServer();
        if (server == null || !Possession.castAvailable()) return "someone";
        return CastSupport.nameOf(server, npc.get()).orElse("someone");
    }

    private ActionsSupport() {}
}
