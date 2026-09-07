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
                        .executes(ctx -> possess(ctx, false))
                        .then(Commands.literal("eyes").executes(ctx -> possess(ctx, true))))
                .then(Commands.literal("release").executes(STCommands::release))
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
                .then(Commands.literal("money")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                .executes(ctx -> reward(ctx, party,
                                        Rewards.Packet.currency(0, 0, 0, 0, DoubleArgumentType.getDouble(ctx, "amount")), ""))
                                .then(Commands.literal("for")
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> reward(ctx, party,
                                                        Rewards.Packet.currency(0, 0, 0, 0, DoubleArgumentType.getDouble(ctx, "amount")),
                                                        StringArgumentType.getString(ctx, "reason")))))))
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
                .then(Commands.literal("for")
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> reward(ctx, party,
                                        toPacket.apply(LongArgumentType.getLong(ctx, "amount")),
                                        StringArgumentType.getString(ctx, "reason")))));
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
    private static int possess(CommandContext<CommandSourceStack> ctx, boolean throughItsEyes)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        // One gesture, both kinds of body: a wild creature found by our own
        // ray, or a cast NPC found by Cast. Whichever was nearer is the one
        // they were looking at.
        var looked = Possession.lookingAt(player, POSSESS_REACH);
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
        var refusal = sighted.isNpc()
                ? Possession.possessNpc(player, sighted.npcId(), throughItsEyes)
                : Possession.possess(player, sighted.mob(), throughItsEyes);
        switch (refusal) {
            case NONE -> {
                // Say which of the two this is, at the moment it happens. A
                // Storyteller who expected to steer and cannot would otherwise
                // be left pressing keys at a creature that ignores them.
                Feedback.chat(player, throughItsEyes
                        ? "&5You are seeing through &f" + sighted.name()
                                + "&5. &f/st say <words>&5 speaks as it, &f/st release&5 lets it go. "
                                + "&8(you cannot move while wearing its eyes — sneak or "
                                + "&f/st possess&8 without &feyes&8 to steer it instead)"
                        : "&5You are steering &f" + sighted.name()
                                + "&5. Walk, and it walks with you. &f/st say <words>&5 speaks as it, "
                                + "&f/st release&5 lets it go. "
                                + "&8(/st possess eyes to see through it instead — you cannot do both)");
                presenceNote(player).ifPresent(note -> Feedback.chat(player, note));
                return 1;
            }
            case ALREADY_HELD -> Feedback.chat(player,
                    "&7You are already wearing something. &f/st release&7 first.");
            case TAKEN -> Feedback.chat(player, "&7Another Storyteller is already wearing that one.");
            case NOT_LOADED -> Feedback.chat(player,
                    "&7" + sighted.name() + " &7has no body loaded right now — nothing to step into.");
        }
        return 0;
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
        if (!net.neoforged.fml.ModList.get().isLoaded("standards")) return java.util.Optional.empty();
        if (VanishSupport.vanished(player)) return java.util.Optional.empty();
        return java.util.Optional.of("&8Everyone can see you leading it. &f/vanish&8 to work unseen.");
    }

    private static int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Possession.castAvailable()) {
            var npc = Possession.releaseNpc(player);
            if (npc.isPresent()) {
                Feedback.chat(player, "&aYou step out of &f" + npc.get()
                        + "&a. It is itself again. &f/st return&a brings you back to your body.");
                return 1;
            }
        }
        var released = Possession.release(player);
        if (released.isEmpty()) {
            Feedback.chat(player, "&7You are not wearing anything.");
            return 0;
        }
        Feedback.chat(player, "&aYou step out of &f" + released.get().getName().getString()
                + "&a. It is itself again. &f/st return&a brings you back to your body.");
        return 1;
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
            if (mob.isEmpty()) {
                Feedback.chat(player,
                        "&7You are not wearing anything to speak through. &f/st possess&7 first.");
                return 0;
            }
            heard = Possession.speak(player, mob.get(), text, SPEAK_RADIUS);
        }
        // Told how many heard it, because a line delivered to an empty clearing
        // is a beat the Storyteller needs to know landed nowhere.
        if (heard == 0) Feedback.chat(player, "&8(nobody was close enough to hear that)");
        return heard;
    }

    // --- presence ----------------------------------------------------------

    private static int drift(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Presence.drift(player)) {
            Feedback.chat(player, "&7You drift out of your body. &f/st return&7 brings you back.");
            return 1;
        }
        Feedback.chat(player, "&7You are already drifting. &f/st return&7 brings you back.");
        return 0;
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
        var looked = Possession.lookedAt(player, POSSESS_REACH);
        if (looked.isEmpty()) {
            Feedback.chat(player, "&7Nothing in your sights. Look straight at a creature.");
            return 0;
        }
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
        return 1;
    }

    private static int castSave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var looked = Possession.lookedAt(player, POSSESS_REACH);
        if (looked.isEmpty() || !(looked.get() instanceof Mob mob)) {
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
