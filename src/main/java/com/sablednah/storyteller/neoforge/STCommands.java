package com.sablednah.storyteller.neoforge;

import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Optional;

import com.sablednah.legendquest.LQRegistries;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The {@code /st} command tree — the whole of StoryTeller for a vanilla
 * client, and the thing any future GUI will drive rather than bypass.
 *
 * <p>Commands first is not a staging decision, it is the architecture. A
 * Storyteller running the session from a laptop with no mods installed should
 * lose the convenience and none of the capability, and keeping the GUI a
 * caller of these same entry points is what stops the two drifting into
 * different rule sets — LegendQuest learned that with its hotkey path routing
 * through the same engine as its commands.</p>
 */
public final class STCommands {

    /** Who each Storyteller last looked in on, so `next` means next. */
    private static final java.util.Map<UUID, UUID> LAST_VISITED = new java.util.HashMap<>();

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext build) {
        LiteralArgumentBuilder<CommandSourceStack> st = Commands.literal("st")
                .requires(src -> STPermissions.gate(src, STPermissions::isStoryteller))

                // --- presence ---
                .then(Commands.literal("drift").executes(STCommands::drift))
                .then(Commands.literal("return").executes(STCommands::returnToBody))
                .then(Commands.literal("goto")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(STCommands::gotoPlayer)))
                .then(Commands.literal("next").executes(STCommands::nextPlayer))
                // Bring the table to the scene. No player argument needed when
                // the Storyteller is in the party themselves, which is the
                // common case -- they joined it to be summonable in the first
                // place. Naming a member covers the other case: a GM who runs
                // scenes from outside the party.
                .then(Commands.literal("summon")
                        .executes(ctx -> summonParty(ctx, null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> summonParty(ctx, EntityArgument.getPlayer(ctx, "player")))))

                // --- oversight ---
                .then(Commands.literal("who").executes(STCommands::who))

                // --- rewards ---
                .then(Commands.literal("reward")
                        .requires(src -> STPermissions.gate(src, STPermissions::canReward))
                        .then(attachCurrencies(
                                Commands.argument("player", EntityArgument.player()), false, build))
                        .then(Commands.literal("party")
                                .then(attachCurrencies(
                                        Commands.argument("player", EntityArgument.player()), true, build))))

                // --- possession ---
                .then(Commands.literal("possess")
                        // Bare /st possess asks the CONNECTION what it can do
                        // rather than asking the Storyteller to remember. See
                        // Mode.AUTO.
                        .executes(ctx -> possess(ctx, Mode.AUTO))
                        .then(Commands.literal("eyes").executes(ctx -> possess(ctx, Mode.EYES)))
                        // Both halves of the automatic choice stay reachable by
                        // name -- a Storyteller who wants the other one should
                        // not have to uninstall something to get it.
                        .then(Commands.literal("drive").executes(ctx -> possess(ctx, Mode.DRIVE)))
                        .then(Commands.literal("steer").executes(ctx -> possess(ctx, Mode.STEER))))
                .then(Commands.literal("release").executes(STCommands::release))
                .then(Commands.literal("lock").executes(STCommands::lock))
                .then(Commands.literal("unlock").executes(STCommands::unlock))
                .then(Commands.literal("say")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(STCommands::sayAs)))

                // --- effects ---
                .then(effects(build))

                // --- cast ---
                .then(castCommands(build))

                // --- set dressing ---
                .then(structCommands(build))

                // --- narration ---
                .then(Commands.literal("narrate")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(STCommands::narrateServer))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 500))
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(STCommands::narrateRadius))))
                        .then(Commands.literal("party")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(STCommands::narrateParty)))))
                .then(Commands.literal("title")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(STCommands::title)))
                .then(Commands.literal("whisper")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(STCommands::whisper))))

                // --- undo ---
                .then(Commands.literal("undo").executes(STCommands::undo))
                .then(Commands.literal("scene")
                        .then(Commands.literal("clear").executes(STCommands::sceneClear)));

        dispatcher.register(st);
    }

    // --- rewards -----------------------------------------------------------

    /**
     * One leaf per currency rather than one command taking five optional
     * amounts. Brigadier can express the latter, but only as a nest of
     * optional branches that tab-completes into a maze — and a GM mid-scene
     * wants {@code /st reward Bob xp 500} to be four words, not a form.
     *
     * <p>Combining several into one packet is what a saved reward preset is
     * for, and that belongs in the GUI rather than in a command line.</p>
     *
     * <p>Takes the parent builder and returns it with all five attached as
     * SIBLINGS — {@code parent.then(a).then(b)} adds both as children of
     * {@code parent}, which is not the same as {@code a.then(b)}, that nests
     * {@code b} under {@code a} instead and makes {@code sp} reachable only as
     * {@code xp sp <n>}. That was the shape of a real bug here: every leaf but
     * {@code xp} was unreachable by its own name until this was fixed.</p>
     */
    private static ArgumentBuilder<CommandSourceStack, ?> attachCurrencies(
            ArgumentBuilder<CommandSourceStack, ?> parent, boolean party, CommandBuildContext build) {
        parent.then(Commands.literal("xp")
                        .then(amount(party, n -> Rewards.Packet.currency(n, 0, 0, 0, 0))))
                .then(Commands.literal("levels")
                        .then(amount(party, n -> Rewards.Packet.currency(0, (int) n, 0, 0, 0))))
                .then(Commands.literal("sp")
                        .then(amount(party, n -> Rewards.Packet.currency(0, 0, (int) n, 0, 0))))
                .then(Commands.literal("karma")
                        .then(amount(party, n -> Rewards.Packet.currency(0, 0, 0, n, 0))))
                // Reputation is NOT karma: karma is LegendQuest's own moral
                // axis, reputation is Standards' ledger of standing on a named
                // track, so a character can be loved in one town and hated in
                // the next. A GM paying out "the smuggler job" usually means
                // this one. Tracks are suggested from the server's own list --
                // nobody should have to remember what the provider called them.
                .then(Commands.literal("reputation")
                        .then(Commands.argument("standing", StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        net.neoforged.fml.ModList.get().isLoaded("standards")
                                                ? ReputationSupport.standings() : List.of(), b))
                                .then(Commands.argument("amount", IntegerArgumentType.integer())
                                        .executes(ctx -> reward(ctx, party, Rewards.Packet.standing(
                                                StringArgumentType.getString(ctx, "standing"),
                                                IntegerArgumentType.getInteger(ctx, "amount")), ""))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> reward(ctx, party, Rewards.Packet.standing(
                                                        StringArgumentType.getString(ctx, "standing"),
                                                        IntegerArgumentType.getInteger(ctx, "amount")),
                                                        StringArgumentType.getString(ctx, "reason")))))))
                .then(Commands.literal("money")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                .executes(ctx -> reward(ctx, party,
                                        Rewards.Packet.currency(0, 0, 0, 0, DoubleArgumentType.getDouble(ctx, "amount")), ""))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> reward(ctx, party,
                                                Rewards.Packet.currency(0, 0, 0, 0, DoubleArgumentType.getDouble(ctx, "amount")),
                                                StringArgumentType.getString(ctx, "reason"))))))
                .then(Commands.literal("item")
                        .then(Commands.argument("item", ResourceArgument.resource(build, Registries.ITEM))
                                .executes(ctx -> reward(ctx, party,
                                        Rewards.Packet.of(ResourceArgument.getResource(ctx, "item", Registries.ITEM), 1), ""))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                        .executes(ctx -> reward(ctx, party,
                                                Rewards.Packet.of(ResourceArgument.getResource(ctx, "item", Registries.ITEM),
                                                        IntegerArgumentType.getInteger(ctx, "count")), "")))));
        return parent;
    }

    /** A whole-number amount, optionally followed by {@code for <reason>}. */
    private static ArgumentBuilder<CommandSourceStack, ?> amount(boolean party, LongFunction<Rewards.Packet> toPacket) {
        return Commands.argument("amount", LongArgumentType.longArg())
                .executes(ctx -> reward(ctx, party, toPacket.apply(LongArgumentType.getLong(ctx, "amount")), ""))
                // The reason follows the number directly. It used to require a
                // literal "for", which a GM had to actually type -- reported as
                // "weird i had to literally type for". A greedy string takes
                // the rest of the line, so the word is unnecessary, and anyone
                // who writes it anyway just gets it in their reason.
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> reward(ctx, party,
                                toPacket.apply(LongArgumentType.getLong(ctx, "amount")),
                                StringArgumentType.getString(ctx, "reason"))));
    }

    private static int reward(CommandContext<CommandSourceStack> ctx, boolean party,
            Rewards.Packet packet, String reason) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (packet.isEmpty()) {
            ctx.getSource().sendFailure(Feedback.colored("&cNothing to award."));
            return 0;
        }
        var results = party ? Rewards.party(target, packet, reason)
                            : List.of(Rewards.give(target, packet, reason));

        // One summary line per recipient, refusals included. A GM who awarded
        // four people and reached three needs to know which one missed out at
        // the moment it happens, not when that player complains later.
        StringBuilder sb = new StringBuilder("&6Rewarded &f" + results.size() + "&6:");
        for (var r : results) {
            sb.append("\n &7-&r &f").append(r.player());
            if (!r.granted().isEmpty()) sb.append(" &a").append(String.join(", ", r.granted()));
            if (!r.refused().isEmpty()) sb.append(" &c(missed: ").append(String.join(", ", r.refused())).append(')');
        }
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Feedback.colored(out), false);
        return results.size();
    }

    // --- effects -----------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> effects(CommandBuildContext build) {
        return Commands.literal("effect")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(effectArgs(build, false)))
                .then(Commands.literal("party")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(effectArgs(build, true))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> clear(ctx, null))
                                .then(Commands.argument("effect", ResourceArgument.resource(build, Registries.MOB_EFFECT))
                                        .executes(ctx -> clear(ctx,
                                                ResourceArgument.getMobEffect(ctx, "effect"))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> effectArgs(CommandBuildContext build, boolean party) {
        return Commands.argument("effect", ResourceArgument.resource(build, Registries.MOB_EFFECT))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 1_000_000))
                        .executes(ctx -> effect(ctx, party, 0, false))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> effect(ctx, party,
                                        IntegerArgumentType.getInteger(ctx, "level") - 1, false))
                                // "hidden" is for a condition the story imposes
                                // rather than a potion someone drank: no
                                // swirling particles giving the game away.
                                .then(Commands.literal("hidden")
                                        .executes(ctx -> effect(ctx, party,
                                                IntegerArgumentType.getInteger(ctx, "level") - 1, true)))));
    }

    private static int effect(CommandContext<CommandSourceStack> ctx, boolean party,
            int amplifier, boolean hidden) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        Holder<MobEffect> effect = ResourceArgument.getMobEffect(ctx, "effect");
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        var results = party ? Effects.party(target, effect, seconds, amplifier, hidden)
                            : List.of(Effects.apply(target, effect, seconds, amplifier, hidden));
        summarise(ctx, "Applied", results);
        return results.size();
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, Holder<MobEffect> effect)
            throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        summarise(ctx, "Cleared", List.of(Effects.clear(target, effect)));
        return 1;
    }

    private static void summarise(CommandContext<CommandSourceStack> ctx, String verb,
            List<Effects.Result> results) {
        StringBuilder sb = new StringBuilder("&5" + verb + " &f" + results.size() + "&5:");
        for (var r : results) {
            sb.append("\n &7-&r &f").append(r.player()).append(" &7").append(r.describe());
        }
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Feedback.colored(out), false);
    }

    // --- possession --------------------------------------------------------

    /** How far a Storyteller can reach to take something over. Generous: they
     *  are usually drifting above the scene rather than standing in it. */
    private static final double POSSESS_REACH = 24.0D;

    /**
     * @param throughItsEyes bind the camera to the body, seeing what it sees —
     *        at the cost of every control the Storyteller has. That is not a
     *        design choice: a vanilla client stops sending movement entirely
     *        while spectating an entity ({@code sendPosition} is gated on
     *        {@code isControlledCamera}), and the server snaps the spectator
     *        onto the camera entity every tick regardless. Eyes or control,
     *        never both, until a client mod supplies the input.
     */
    /**
     * The bargains possession can strike, plus the one that picks for you.
     *
     * <p><b>{@link #AUTO} is what bare {@code /st possess} means</b>, and it
     * resolves to {@link #DRIVE} when the Storyteller's client can render it and
     * {@link #STEER} when it cannot. Driving is the better experience by a long
     * way — you <i>are</i> the creature rather than towing it — but it needs the
     * client half to stop drawing the body the camera is inside, and without
     * that the Storyteller spends the scene looking at the inside of a cow.</p>
     *
     * <p>The question is asked of the <em>connection</em>, not of a setting and
     * not of the player: our payload channel is optional, so NeoForge already
     * knows whether the client negotiated it. Nobody has to remember which
     * client they are on, which is the only version of this that is actually
     * "don't make me think".</p>
     */
    private enum Mode { AUTO, STEER, EYES, DRIVE }

    private static int possess(CommandContext<CommandSourceStack> ctx, Mode mode)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean chosenForThem = mode == Mode.AUTO;
        if (chosenForThem) {
            mode = Possession.canDrive(player) ? Mode.DRIVE : Mode.STEER;
        }
        boolean throughItsEyes = mode == Mode.EYES;
        // One gesture, both kinds of body: a wild creature found by our own
        // ray, or a cast NPC found by Cast. Whichever was nearer is the one
        // they were looking at.
        // Already wearing something? Then this is the way out of it. Same
        // reasoning as /st drift: one button, and the command knows which
        // direction it means. /st release remains the unambiguous form.
        if (Possession.isPossessing(player)) return release(ctx);

        var looked = Sights.target(player, POSSESS_REACH);
        if (looked.isEmpty()) {
            Feedback.chat(player, "&7Nothing in your sights to take over. Look straight at a creature.");
            return 0;
        }
        var sighted = looked.get();
        if (!sighted.canPossess()) {
            Feedback.chat(player, "&7" + sighted.name() + " &7cannot be worn.");
            return 0;
        }
        // Deliberately does NOT put them into spectator any more.
        //
        // Possession used to drift the Storyteller first, and a play test
        // showed that is the wrong tool: a spectator flies, so the body it is
        // leading gets walked into the air and bounces; it noclips, so the body
        // follows it into the ground; and vanilla repurposes a spectator's own
        // inputs -- clicking an entity re-binds the camera, sneaking unbinds
        // it -- which fights the feature the whole time.
        //
        // Steering wants a grounded body, so the creature is following
        // somewhere it can actually go. Spectator keeps its own job: the
        // godlike survey of a scene, which is what /st drift is for.
        // Driving a cast NPC used to be refused here, on the grounds that it
        // works by snapping the body every tick and Cast owns its own bodies.
        // That was a boundary, not a limit: Cast exposes drive(), which takes a
        // position and decides for itself whether to step or snap -- so the two
        // kinds of body differ only in who does the moving, and this side does
        // not need to know which.
        var refusal = sighted.isNpc()
                ? (mode == Mode.DRIVE
                        ? Possession.driveNpc(player, sighted.npcId())
                        : Possession.possessNpc(player, sighted.npcId(), throughItsEyes))
                : (mode == Mode.DRIVE
                        ? Possession.drive(player, sighted.mob())
                        : Possession.possess(player, sighted.mob(), false));
        switch (refusal) {
            case NONE -> {
                // Say which of the two this is, at the moment it happens. A
                // Storyteller who expected to steer and cannot would otherwise
                // be left pressing keys at a creature that ignores them.
                if (mode == Mode.DRIVE) {
                    Feedback.chat(player, "&5You &lare&r&5 &f" + sighted.name()
                            + "&5. Move as you always do — it goes where you go, and the room "
                            + "sees only it. &f/st say <words>&5 speaks as it, &f/st release&5 "
                            + "gives it back.");
                    // Only warn about the view when it is actually going to be
                    // wrong. Chosen automatically, drive is only ever picked
                    // when the client half is present, so the warning would be
                    // both wrong and the only thing they were told to worry
                    // about. Asked for by name on a vanilla client, it is the
                    // single most useful sentence on screen.
                    if (!chosenForThem && !Possession.canDrive(player)) {
                        Feedback.chat(player, "&8Your client has no StoryTeller half, so you will "
                                + "see this creature from the inside. It still works — &f/st "
                                + "possess steer&8 tows it from outside instead.");
                    }
                    if (sighted.isNpc() && !castBodyVisible(player, sighted.npcId())) {
                        Feedback.chat(player, "&8This one has no creature body of its own, so it "
                                + "cannot be hidden from your view — you may see it around you.");
                    }
                    return 1;
                }
                Feedback.chat(player, throughItsEyes
                        ? "&5You are seeing through &f" + sighted.name()
                                + "&5. &f/st say <words>&5 speaks as it, &f/st release&5 lets it go. "
                                + "&8(you cannot move while wearing its eyes — sneak or "
                                + "&f/st possess&8 without &feyes&8 to steer it instead)"
                        : "&5You are steering &f" + sighted.name()
                                + "&5. Walk, and it walks with you. &f/st say <words>&5 speaks as it, "
                                + "&f/st release&5 lets it go. "
                                + "&8(/st possess eyes to see through it instead — you cannot do both)");
                // Steering was CHOSEN for them only when the client cannot
                // render driving. Naming the mod is the remedy, and a remedy
                // beats a symptom.
                if (chosenForThem) {
                    Feedback.chat(player, "&8Install the StoryTeller mod on your client and "
                            + "&f/st possess&8 becomes the creature outright, instead of leading it.");
                }
                if (!throughItsEyes && !sighted.isNpc() && Possession.cannotBeLed(sighted.mob())) {
                    Feedback.chat(player, "&7It will not follow you — a slime moves by jumping, "
                            + "and that cannot be steered. &f/st say&7 still speaks as it, and "
                            + "&f/st possess eyes&7 still rides along.");
                }
                presenceNote(player).ifPresent(note -> Feedback.chat(player, note));
                return 1;
            }
            case ALREADY_HELD -> Feedback.chat(player,
                    "&7You are already wearing something. &f/st release&7 first.");
            // (unreachable from /st possess, which now releases instead --
            //  kept because possessNpc is callable from elsewhere)
            case TAKEN -> Feedback.chat(player, "&7Another Storyteller is already wearing that one.");
            case NOT_LOADED -> Feedback.chat(player,
                    "&7" + sighted.name() + " &7has no body loaded right now — nothing to step into.");
        }
        return 0;
    }

    /**
     * Whether a cast NPC has a real entity we can ask the client not to draw.
     *
     * <p>A MOB-bodied NPC does; a human phantom may not, and then the
     * Storyteller drives it with it still on screen around them. Alarming and
     * harmless is the worst combination, so it is said out loud at the moment
     * it happens rather than discovered mid-scene.</p>
     */
    private static boolean castBodyVisible(ServerPlayer player, java.util.UUID npcId) {
        var server = player.level().getServer();
        return server != null && CastSupport.entityOf(server, npcId).isPresent();
    }

    /**
     * What the Storyteller should know about being *seen*, now that possession
     * no longer hides them.
     *
     * <p>Silence when there is nothing to say: already unseen, or on a server
     * with no vanish at all, where telling them to run a command that does not
     * exist would be worse than saying nothing.</p>
     */
    private static java.util.Optional<String> presenceNote(ServerPlayer player) {
        if (Presence.isDrifting(player)) {
            return java.util.Optional.of("&8You are drifting, so it will follow you into the air. "
                    + "&f/st return&8 first to walk it on the ground.");
        }
        // Possession has already tried to hide them. ASK whether it worked
        // rather than assuming it did: an older Standards has no holds, and
        // telling somebody they are hidden when they are not is a lie they
        // find out by walking in front of a player.
        if (Possession.vanishAvailable() && VanishSupport.vanished(player)) {
            return java.util.Optional.of("&8You are hidden while you lead it.");
        }
        return java.util.Optional.of("&8Everyone can see you leading it.");
    }

    private static int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Possession.castAvailable()) {
            var npc = Possession.releaseNpc(player);
            if (npc.isPresent()) {
                Feedback.chat(player, "&aYou step out of &f" + npc.get()
                        + "&a. It is itself again." + seenAgain(player));
                return 1;
            }
        }
        var released = Possession.release(player);
        if (released.isEmpty()) {
            Feedback.chat(player, "&7You are not wearing anything.");
            return 0;
        }
        Feedback.chat(player, "&aYou step out of &f" + released.get().getName().getString()
                + "&a. It is itself again." + seenAgain(player));
        return 1;
    }

    /**
     * Whether stepping out has actually put them back in view.
     *
     * <p>It has not, if they had vanished themselves before the scene: their
     * own hold still stands and they are still invisible. Saying "you are
     * visible again" there would be a lie the player only discovers by
     * walking in front of somebody.</p>
     */
    private static String seenAgain(ServerPlayer player) {
        if (Presence.isDrifting(player)) {
            return " &f/st return&a brings you back to your body.";
        }
        if (Possession.vanishAvailable() && VanishSupport.vanished(player)) {
            return " &8You are still hidden — that is your own &f/vanish&8, not this.";
        }
        return "";
    }

    /** How far an NPC's voice carries. Roughly vanilla chat range for a scene. */
    private static final double SPEAK_RADIUS = 48.0D;

    private static int sayAs(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String text = StringArgumentType.getString(ctx, "text");
        var npc = Possession.heldNpcBy(player);
        int heard;
        if (npc.isPresent()) {
            heard = Possession.speakAsNpc(player, npc.get(), text, SPEAK_RADIUS);
        } else {
            var mob = Possession.heldBy(player);
            if (mob.isPresent()) {
                heard = Possession.speak(player, mob.get(), text, SPEAK_RADIUS);
            } else {
                // Nothing worn, but the sights may be locked -- and lending a
                // creature your voice is a far smaller thing than wearing it.
                // A whole conversation can be run this way without ever taking
                // an NPC's own behaviour away from it, which for a shopkeeper
                // standing at their stall is exactly what you want.
                //
                // Worn beats locked, deliberately: you are inside one of them,
                // and a Storyteller wearing a body means that body's voice.
                var locked = Sights.locked(player);
                if (locked.isEmpty()) {
                    Feedback.chat(player, "&7You are not wearing anything to speak through. "
                            + "&f/st possess&7 it, or &f/st lock&7 on to it to lend it your voice.");
                    return 0;
                }
                heard = locked.get().isNpc()
                        ? Possession.speakAsNpc(player, locked.get().npcId(), text, SPEAK_RADIUS)
                        : Possession.speak(player, locked.get().mob(), text, SPEAK_RADIUS);
            }
        }
        // Told how many heard it, because a line delivered to an empty clearing
        // is a beat the Storyteller needs to know landed nowhere.
        if (heard == 0) Feedback.chat(player, "&8(nobody was close enough to hear that)");
        return heard;
    }

    // --- sights ------------------------------------------------------------

    /**
     * Lock the sights on what is in them, or let go if they are already locked.
     *
     * <p>Toggles, like {@code /st drift} and {@code /st possess}, and for the
     * same reason: the state is already on the screen, so the thing that put you
     * in it takes you out of it. {@code /st unlock} is the unambiguous form.</p>
     */
    private static int lock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Sights.isLocked(player)) return unlock(ctx);
        var locked = Sights.lock(player, POSSESS_REACH);
        if (locked.isEmpty()) {
            Feedback.chat(player, "&7Nothing in your sights to lock on to. Look straight at a creature.");
            return 0;
        }
        Feedback.chat(player, "&bLocked on &f" + locked.get()
                + "&b. &7Possess, dress, behave and save all mean it now, wherever you look. "
                + "&f/st lock&7 again lets go.");
        return 1;
    }

    private static int unlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var name = Sights.lockedName(player);
        if (name.isEmpty()) {
            Feedback.chat(player, "&7Your sights are not locked on anything.");
            return 0;
        }
        Sights.unlock(player);
        Feedback.chat(player, "&7You let &f" + name.get()
                + "&7 go. &8Back to whatever you are looking at.");
        return 1;
    }

    /**
     * {@code /st summon} — the party arrives where the Storyteller stands.
     *
     * <p>Reports every member, including the ones it could not reach. "Three of
     * four arrived" is something the person about to start talking needs to
     * know, and an offline member reported as a silence is how a scene gets run
     * at somebody who is not there.</p>
     */
    private static int summonParty(CommandContext<CommandSourceStack> ctx, ServerPlayer named)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer anchor = named != null ? named : player;

        var arrivals = Summons.summonParty(player, anchor);
        long came = arrivals.stream().filter(a -> "arrived".equals(a.what())).count();
        long missing = arrivals.stream().filter(a -> "offline".equals(a.what())).count();

        if (came == 0 && missing == 0) {
            Feedback.chat(player, "&7Nobody to bring — "
                    + (named != null ? named.getName().getString() + " is" : "you are")
                    + " not in a party with anyone else.");
            return 0;
        }
        Feedback.chat(player, "&d" + came + " &7" + (came == 1 ? "player" : "players")
                + " brought to you."
                + (missing > 0 ? " &8(" + missing + " offline)" : ""));
        // Only the ones worth reading about. "already here" is the Storyteller
        // themselves and says nothing; an offline member is the whole reason
        // this report exists.
        for (var a : arrivals) {
            if ("offline".equals(a.what())) {
                Feedback.chat(player, "  &8" + a.who() + ": could not be reached");
            }
        }
        return (int) came;
    }

    // --- presence ----------------------------------------------------------

    /**
     * Drift out, or come back if already out — one word, both directions.
     *
     * <p>It used to refuse when already drifting and point at {@code /st return}.
     * Two commands for one idea meant two buttons for one idea, which Sable
     * called clunky and was right about: the state is on the screen, so the
     * thing that put you in it should take you out of it.</p>
     *
     * <p>Done in the command rather than by teaching the button seam about
     * pairs. A button carries one command string and that stays true — the
     * command is what knows. {@code /st return} still exists for anyone who
     * wants to say it unambiguously, and for a macro that must not toggle.</p>
     */
    private static int drift(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Presence.isDrifting(player)) return returnToBody(ctx);
        Presence.drift(player);
        Feedback.chat(player, "&7You drift out of your body. &f/st drift&7 again brings you back.");
        return 1;
    }

    private static int returnToBody(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Presence.returnToBody(player)) {
            Feedback.chat(player, "&aYou are back in your body.");
            return 1;
        }
        // No anchor at all. Refusing here is the one answer that leaves someone
        // stuck in spectator with no way out, so put them somewhere playable
        // and say exactly what happened.
        player.setGameMode(GameType.SURVIVAL);
        Feedback.chat(player, "&eNo anchor was stored, so there was nowhere to send you back to. "
                + "You are in survival, where you were standing.");
        return 0;
    }

    private static int gotoPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        Presence.jumpTo(self, target);
        LAST_VISITED.put(self.getUUID(), target.getUUID());
        Feedback.chat(self, "&7You look in on &f" + target.getName().getString() + "&7.");
        return 1;
    }

    private static int nextPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer self = ctx.getSource().getPlayerOrException();
        var next = Presence.nextAudience(ctx.getSource().getServer(), self, LAST_VISITED.get(self.getUUID()));
        if (next.isEmpty()) {
            Feedback.chat(self, "&7Nobody else is online.");
            return 0;
        }
        Presence.jumpTo(self, next.get());
        LAST_VISITED.put(self.getUUID(), next.get().getUUID());
        Feedback.chat(self, "&7You look in on &f" + next.get().getName().getString() + "&7.");
        return 1;
    }

    // --- oversight ---------------------------------------------------------

    private static int who(CommandContext<CommandSourceStack> ctx) {
        String rendered = Roster.render(Roster.of(ctx.getSource().getServer()));
        ctx.getSource().sendSuccess(() -> Feedback.colored(rendered), false);
        return 1;
    }

    // --- cast -----------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> castCommands(CommandBuildContext build) {
        return Commands.literal("cast")
                .then(Commands.literal("spawn")
                        .then(Commands.argument("entity", ResourceArgument.resource(build, Registries.ENTITY_TYPE))
                                .executes(ctx -> castSpawn(ctx, Optional.empty()))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> castSpawn(ctx,
                                                Optional.of(StringArgumentType.getString(ctx, "name")))))))
                .then(Commands.literal("citizen")
                        .executes(ctx -> castCitizen(ctx, Optional.empty(), Optional.empty()))
                        .then(Commands.argument("race", IdentifierArgument.id())
                                .executes(ctx -> castCitizen(ctx,
                                        Optional.of(IdentifierArgument.getId(ctx, "race")), Optional.empty()))
                                .then(Commands.argument("class", IdentifierArgument.id())
                                        .executes(ctx -> castCitizen(ctx,
                                                Optional.of(IdentifierArgument.getId(ctx, "race")),
                                                Optional.of(IdentifierArgument.getId(ctx, "class")))))))
                .then(Commands.literal("behave")
                        .then(Commands.literal("guard").executes(ctx -> castBehave(ctx, Cast.Behaviour.GUARD, null)))
                        .then(Commands.literal("patrol").executes(ctx -> castBehave(ctx, Cast.Behaviour.PATROL, null)))
                        .then(Commands.literal("flee").executes(ctx -> castBehave(ctx, Cast.Behaviour.FLEE, null)))
                        .then(Commands.literal("none").executes(ctx -> castBehave(ctx, Cast.Behaviour.NONE, null)))
                        .then(Commands.literal("follow")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> castBehave(ctx, Cast.Behaviour.FOLLOW,
                                                EntityArgument.getPlayer(ctx, "player"))))))
                // Costume. The item is a greedy string rather than an item
                // argument because Cast takes it exactly as /give writes it,
                // components and all -- and a component blob contains the
                // brackets and quotes an item argument would eat.
                .then(Commands.literal("equip")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        EQUIP_SLOTS, b))
                                .executes(ctx -> castEquip(ctx, ""))
                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                        .executes(ctx -> castEquip(ctx,
                                                StringArgumentType.getString(ctx, "item"))))))
                .then(Commands.literal("worn").executes(STCommands::castWorn))
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(STCommands::castSave)))
                .then(Commands.literal("use")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(STCommands::suggestCastNames)
                                .executes(STCommands::castUse)))
                .then(Commands.literal("list").executes(STCommands::castList));
    }

    private static int castSpawn(CommandContext<CommandSourceStack> ctx, Optional<String> name)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Holder<EntityType<?>> type = ResourceArgument.getResource(ctx, "entity", Registries.ENTITY_TYPE);
        var spawned = Cast.spawn(player, type, name);
        if (spawned.isEmpty()) {
            Feedback.chat(player, "&c" + type.value().getDescription().getString()
                    + " refused to spawn — is it a real, spawnable mob?");
            return 0;
        }
        Feedback.chat(player, "&aCast: &f" + spawned.get().getName().getString());
        return 1;
    }

    private static int castCitizen(CommandContext<CommandSourceStack> ctx,
            Optional<Identifier> race, Optional<Identifier> charClass) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var result = Cast.citizen(player, race, charClass);
        if (result.isEmpty()) {
            Feedback.chat(player, "&cNo races or classes are loaded to draw a citizen from.");
            return 0;
        }
        Feedback.chat(player, "&aCast: &f" + result.get().raceName() + " " + result.get().className());
        return 1;
    }

    private static int castBehave(CommandContext<CommandSourceStack> ctx, Cast.Behaviour behaviour,
            ServerPlayer followTarget) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        // Sights.target, not lookedAt: a Cast NPC with a human body is a phantom —
        // not a Mob, and in no level — so the plain gaze ray cannot see one and
        // reported "nothing in your sights" at something standing in front of
        // the Storyteller. Seeing it is the first half; the second is saying
        // something truer than "no target".
        var sighted = Sights.target(player, POSSESS_REACH);
        if (sighted.isEmpty()) {
            Feedback.chat(player, "&7Nothing in your sights. Look straight at a creature.");
            return 0;
        }
        if (behaviour == Cast.Behaviour.FOLLOW && followTarget == null) {
            Feedback.chat(player, "&cFollow needs a player: /st cast behave follow <player>.");
            return 0;
        }
        if (sighted.get().isNpc()) {
            return castNpcBehave(ctx, player, sighted.get(), behaviour, followTarget);
        }
        var looked = java.util.Optional.of(sighted.get().mob());
        if (behaviour == Cast.Behaviour.FOLLOW && followTarget == null) {
            Feedback.chat(player, "&cFollow needs a player: /st cast behave follow <player>.");
            return 0;
        }
        var refusal = Cast.behave(looked.get(), behaviour, followTarget);
        if (refusal == Cast.BehaviourRefusal.NOT_A_PATHFINDER) {
            Feedback.chat(player, "&c" + looked.get().getName().getString()
                    + " cannot be given a movement behaviour (it does not path).");
            return 0;
        }
        Feedback.chat(player, "&a" + looked.get().getName().getString() + " now: &f"
                + behaviour.name().toLowerCase(java.util.Locale.ROOT));
        // Say when it may not stick, without pretending to know whether it
        // will. A Brain issues movement of its own and this goal competes with
        // it rather than replacing it, so how well it holds depends on how busy
        // that Brain is: villagers ignore a post outright, goats and frogs flee
        // convincingly, camels do not care. "It may not hold" is the honest
        // claim; "it will not work" would have been wrong.
        if (Cast.brainDriven(looked.get())) {
            Feedback.chat(player, "&8It has a mind of its own — villagers, goats, camels and "
                    + "their like run on a Brain rather than goals, so this competes with what "
                    + "it already wants and may not hold. Watch it before you rely on it.");
        }
        return 1;
    }

    /**
     * A behaviour on one of Cast's own bodies.
     *
     * <p>Cast pulls its bodies back to their spot once a second, so a movement
     * goal added on top of that runs, gets dragged home, and runs again —
     * reported live as "it ran. then bounced back to anchor, repeat". The goal
     * was not wrong and the anchor was not wrong; having both was.</p>
     *
     * <p>So a behaviour <b>suspends the anchor</b> for as long as it stands,
     * exactly as possession does, and {@code none} gives the body back to
     * Cast — re-anchoring it wherever it has ended up, so a fled villager
     * stays where it fled to.</p>
     */
    private static int castNpcBehave(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
            Possession.Sighted sighted, Cast.Behaviour behaviour, ServerPlayer followTarget) {
        var server = ctx.getSource().getServer();
        var body = CastSupport.bodyOf(server, sighted.npcId());
        if (body.isEmpty()) {
            // A human NPC is a phantom with no goals to give: Cast drives it.
            Feedback.chat(player, "&7" + sighted.name() + " &7has no creature body to steer — "
                    + "a person is driven by Cast, not by movement goals.");
            return 0;
        }
        var refusal = Cast.behave(body.get(), behaviour, followTarget);
        if (refusal == Cast.BehaviourRefusal.NOT_A_PATHFINDER) {
            Feedback.chat(player, "&c" + sighted.name() + " cannot be given a movement behaviour.");
            return 0;
        }
        boolean standing = behaviour != Cast.Behaviour.NONE;
        // Suspended while it moves, restored when it stops -- and restoring
        // anchors it where it now IS, not where it began.
        CastSupport.setAnchored(server, sighted.npcId(), !standing);
        Feedback.chat(player, "&a" + sighted.name() + " now: &f"
                + behaviour.name().toLowerCase(java.util.Locale.ROOT)
                + (standing
                        ? "&a. &8Cast will not hold it on its spot while this stands."
                        : "&a. &8Cast holds it where it stands now."));
        if (standing && Cast.brainDriven(body.get())) {
            Feedback.chat(player, "&8It has a mind of its own — villagers and their like run on a "
                    + "Brain rather than goals, so this competes with what it already wants "
                    + "and may not hold.");
        }
        return 1;
    }

    /** Cast's slot names, for completion. Not an enum on their side, so this is
     *  the one place the list is written down here. */
    private static final List<String> EQUIP_SLOTS =
            List.of("mainhand", "offhand", "head", "chest", "legs", "feet");

    /**
     * Dress the cast NPC in the Storyteller's sights.
     *
     * <p>Only cast NPCs: a wild creature's gear is its own, and putting a helmet
     * on a passing zombie would be a change nothing in this mod could undo or
     * even remember afterwards.</p>
     *
     * @param item blank to strip the slot, which is Cast's own convention.
     */
    private static int castEquip(CommandContext<CommandSourceStack> ctx, String item)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String slot = StringArgumentType.getString(ctx, "slot").toLowerCase(java.util.Locale.ROOT);
        var sighted = Sights.target(player, POSSESS_REACH);
        if (sighted.isEmpty() || !sighted.get().isNpc()) {
            Feedback.chat(player, "&7Look straight at a cast NPC to dress it. "
                    + "&8Wild creatures keep their own gear.");
            return 0;
        }
        if (!EQUIP_SLOTS.contains(slot)) {
            Feedback.chat(player, "&cNo such slot. &7Slots are &f"
                    + String.join("&7, &f", EQUIP_SLOTS) + "&7.");
            return 0;
        }
        var server = ctx.getSource().getServer();
        if (!CastSupport.equip(server, sighted.get().npcId(), slot, item)) {
            // Cast refuses a slot it cannot parse or an item it cannot read, and
            // only it knows which -- so say what was rejected rather than guess.
            Feedback.chat(player, "&cCast would not take that: &f" + slot + "&c = &f"
                    + (item.isBlank() ? "(nothing)" : item)
                    + "&c. &7Write the item as &f/give&7 takes it.");
            return 0;
        }
        Feedback.chat(player, item.isBlank()
                ? "&a" + sighted.get().name() + "&a's &f" + slot + "&a is empty now."
                : "&a" + sighted.get().name() + "&a wears &f" + item + "&a on its &f" + slot + "&a.");
        return 1;
    }

    /** What the NPC in the Storyteller's sights has on. */
    private static int castWorn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var sighted = Sights.target(player, POSSESS_REACH);
        if (sighted.isEmpty() || !sighted.get().isNpc()) {
            Feedback.chat(player, "&7Look straight at a cast NPC to see what it is wearing.");
            return 0;
        }
        var worn = CastSupport.equipment(ctx.getSource().getServer(), sighted.get().npcId());
        if (worn.isEmpty()) {
            Feedback.chat(player, "&7" + sighted.get().name() + " &7is wearing nothing of its own.");
            return 1;
        }
        // Cast's own slot order, not the map's, so two NPCs read the same way.
        Feedback.chat(player, "&6" + sighted.get().name() + " &6wears:");
        EQUIP_SLOTS.stream().filter(worn::containsKey).forEach(slot ->
                Feedback.chat(player, "  &7" + slot + ": &f" + worn.get(slot)));
        return worn.size();
    }

    private static int castSave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var sighted = Sights.target(player, POSSESS_REACH);
        if (sighted.isEmpty()) {
            Feedback.chat(player, "&7Nothing in your sights to save. Look straight at a creature.");
            return 0;
        }
        if (sighted.get().isNpc()) {
            // A preset here records an entity type and a name. Saving a cast
            // NPC through it would quietly throw away everything Cast gives the
            // body -- its skin, roles and anchoring -- and hand back a plain
            // creature wearing the same name.
            Feedback.chat(player, "&7" + sighted.get().name() + " &7is a cast NPC, and this would "
                    + "save only a plain creature with its name. &8Cast keeps its own.");
            return 0;
        }
        if (!(sighted.get().mob() instanceof Mob mob)) {
            Feedback.chat(player, "&7Nothing in your sights to save. Look straight at a creature.");
            return 0;
        }
        var preset = Cast.presetOf(mob);
        if (preset.isEmpty()) {
            Feedback.chat(player, "&cCould not identify that creature's type.");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        com.sablednah.storyteller.state.CastPresets.get(ctx.getSource().getServer()).save(name, preset.get());
        Feedback.chat(player, "&aSaved as &f" + name + "&a. &f/st cast use " + name + "&a brings one to life.");
        return 1;
    }

    private static int castUse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        var preset = com.sablednah.storyteller.state.CastPresets.get(ctx.getSource().getServer()).get(name);
        if (preset.isEmpty()) {
            Feedback.chat(player, "&cNo saved cast member named '" + name + "'. /st cast list to see what you have.");
            return 0;
        }
        var spawned = Cast.spawnFromPreset(player, preset.get());
        if (spawned.isEmpty()) {
            Feedback.chat(player, "&cThat preset's entity type no longer exists.");
            return 0;
        }
        Feedback.chat(player, "&aCast: &f" + spawned.get().getName().getString());
        return 1;
    }

    private static int castList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var names = com.sablednah.storyteller.state.CastPresets.get(ctx.getSource().getServer()).names();
        ctx.getSource().sendSuccess(() -> Feedback.colored(names.isEmpty()
                ? "&7No saved cast members yet. /st cast save <name> while looking at one."
                : "&6Saved cast &8(" + names.size() + ")&6: &f" + String.join("&7, &f", names)), false);
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestCastNames(CommandContext<CommandSourceStack> ctx,
                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                com.sablednah.storyteller.state.CastPresets.get(ctx.getSource().getServer()).names(), builder);
    }

    // --- set dressing -----------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> structCommands(CommandBuildContext build) {
        return Commands.literal("struct")
                .then(Commands.literal("list").executes(STCommands::structList))
                .then(Commands.literal("place")
                        .then(Commands.argument("template", IdentifierArgument.id())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        ctx.getSource().getPlayer() != null
                                                ? Structures.list(ctx.getSource().getPlayer()) : List.of(),
                                        builder))
                                .executes(ctx -> structPlace(ctx, Rotation.NONE, Mirror.NONE))
                                .then(Commands.literal("rotate")
                                        .then(Commands.literal("cw90").executes(ctx ->
                                                structPlace(ctx, Rotation.CLOCKWISE_90, Mirror.NONE)))
                                        .then(Commands.literal("180").executes(ctx ->
                                                structPlace(ctx, Rotation.CLOCKWISE_180, Mirror.NONE)))
                                        .then(Commands.literal("ccw90").executes(ctx ->
                                                structPlace(ctx, Rotation.COUNTERCLOCKWISE_90, Mirror.NONE))))))
                .then(Commands.literal("library")
                        .then(Commands.literal("list")
                                .executes(STCommands::libraryList)
                                .then(Commands.argument("family", StringArgumentType.word())
                                        .executes(STCommands::libraryListFamily)))
                        .then(Commands.literal("place")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(STCommands::libraryPlace))));
    }

    private static int structPlace(CommandContext<CommandSourceStack> ctx, Rotation rotation, Mirror mirror)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Identifier templateId = IdentifierArgument.getId(ctx, "template");
        var result = Structures.place(player, templateId, rotation, mirror);
        if (!result.ok()) {
            Feedback.chat(player, "&cCould not place '" + templateId + "' — "
                    + (result.refusal() == Structures.Refusal.UNKNOWN_TEMPLATE
                            ? "no such structure is loaded." : "it placed nothing."));
            return 0;
        }
        Feedback.chat(player, "&aPlaced &f" + templateId + "&a. &f/st undo&a takes it back off.");
        return 1;
    }

    private static int structList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var names = Structures.list(ctx.getSource().getPlayerOrException());
        String shown = names.size() > 40
                ? String.join(", ", names.subList(0, 40)) + " &7(+" + (names.size() - 40) + " more)"
                : String.join(", ", names);
        ctx.getSource().sendSuccess(() -> Feedback.colored(
                "&6Structures &8(" + names.size() + ")&6: &f" + shown), false);
        return 1;
    }

    private static int libraryList(CommandContext<CommandSourceStack> ctx) {
        if (!requireCityWorld(ctx)) return 0;
        var names = CityWorldSupport.names();
        ctx.getSource().sendSuccess(() -> Feedback.colored(
                "&6CityWorld library &8(" + names.size() + ")&6: &f" + String.join("&7, &f", names)), false);
        return 1;
    }

    private static int libraryListFamily(CommandContext<CommandSourceStack> ctx) {
        if (!requireCityWorld(ctx)) return 0;
        String family = StringArgumentType.getString(ctx, "family");
        var names = CityWorldSupport.namesInFamily(family);
        ctx.getSource().sendSuccess(() -> Feedback.colored(names.isEmpty()
                ? "&7No schematics in family '" + family + "'."
                : "&6" + family + " &8(" + names.size() + ")&6: &f" + String.join("&7, &f", names)), false);
        return 1;
    }

    private static int libraryPlace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!requireCityWorld(ctx)) return 0;
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        var placed = CityWorldSupport.place(player, name);
        if (placed.isEmpty()) {
            Feedback.chat(player, "&cNo classic schematic named '" + name + "'. /st struct library list.");
            return 0;
        }
        Feedback.chat(player, "&aPlaced &f" + name + " &7[" + placed.get().family()
                + "]&a. &f/st undo&a takes it back off.");
        return 1;
    }

    private static boolean requireCityWorld(CommandContext<CommandSourceStack> ctx) {
        if (net.neoforged.fml.ModList.get().isLoaded("cityworld")) return true;
        ctx.getSource().sendFailure(Feedback.colored(
                "&7This server does not have CityWorld — its schematic library is not available. "
                        + "&f/st struct list&7 still has every vanilla structure."));
        return false;
    }

    // --- narration ----------------------------------------------------------

    private static int narrateServer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Narration.toServer(player, StringArgumentType.getString(ctx, "text"));
        return 1;
    }

    private static int narrateRadius(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int blocks = IntegerArgumentType.getInteger(ctx, "blocks");
        int heard = Narration.toRadius(player, blocks, StringArgumentType.getString(ctx, "text"));
        Feedback.chat(player, "&8(heard by " + heard + ")");
        return heard;
    }

    private static int narrateParty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int sent = Narration.toParty(target, StringArgumentType.getString(ctx, "text"));
        return sent;
    }

    private static int title(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Narration.title(player, StringArgumentType.getString(ctx, "text"));
        return 1;
    }

    private static int whisper(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        Narration.whisper(target, StringArgumentType.getString(ctx, "text"));
        return 1;
    }

    // --- undo ---------------------------------------------------------------

    private static int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var undone = com.sablednah.storyteller.scene.SceneLog.undoLast(player, (ServerLevel) player.level());
        if (undone.isEmpty()) {
            Feedback.chat(player, "&7Nothing to undo.");
            return 0;
        }
        Feedback.chat(player, "&aUndone: &f" + undone.get());
        return 1;
    }

    private static int sceneClear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int count = com.sablednah.storyteller.scene.SceneLog.undoAll(player, (ServerLevel) player.level());
        Feedback.chat(player, count == 0 ? "&7Nothing to clear." : "&aCleared &f" + count + "&a action(s) from this scene.");
        return count;
    }

    private STCommands() {}
}
