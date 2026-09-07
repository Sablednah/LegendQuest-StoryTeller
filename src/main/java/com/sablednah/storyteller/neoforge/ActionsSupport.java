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
    private static final int POSSESS = 50, RELEASE = 49, DRIFT = 45, RETURN = 44, NEXT = 43;

    public static void register() {
        // Wearing a face is the gesture, so the icon is the thing you wear.
        Actions.register(new Action("storyteller:possess", POSSESS,
                Identifier.parse("minecraft:carved_pumpkin"),
                "action.storyteller.possess", "st possess",
                STPermissions::isStoryteller,
                Possession::isPossessing,
                ActionsSupport::wornName));

        Actions.register(new Action("storyteller:release", RELEASE,
                Identifier.parse("minecraft:feather"),
                "action.storyteller.release", "st release",
                // Only offered when there is something to let go of: a release
                // with nothing worn is a button that can only ever say no.
                Possession::isPossessing));

        Actions.register(new Action("storyteller:drift", DRIFT,
                Identifier.parse("minecraft:elytra"),
                "action.storyteller.drift", "st drift",
                STPermissions::isStoryteller,
                Presence::isDrifting,
                player -> Presence.isDrifting(player) ? "out of your body" : null));

        Actions.register(new Action("storyteller:return", RETURN,
                Identifier.parse("minecraft:compass"),
                "action.storyteller.return", "st return",
                Presence::isDrifting));

        Actions.register(new Action("storyteller:next", NEXT,
                Identifier.parse("minecraft:spyglass"),
                "action.storyteller.next", "st next",
                Presence::isDrifting));

        StoryTeller.LOGGER.info("Registered 5 Storyteller actions with Standards");
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
