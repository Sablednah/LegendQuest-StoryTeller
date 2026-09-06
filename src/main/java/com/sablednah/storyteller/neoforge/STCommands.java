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

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.GameType;
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
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(currencies(false)))
                        .then(Commands.literal("party")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(currencies(true)))))

                // --- possession ---
                .then(Commands.literal("possess").executes(STCommands::possess))
                .then(Commands.literal("release").executes(STCommands::release))
                .then(Commands.literal("say")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(STCommands::sayAs)))

                // --- effects ---
                .then(effects(build));

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
     */
    private static ArgumentBuilder<CommandSourceStack, ?> currencies(boolean party) {
        LiteralArgumentBuilder<CommandSourceStack> xp = Commands.literal("xp")
                .then(amount(party, n -> new Rewards.Packet(n, 0, 0, 0, 0)));
        LiteralArgumentBuilder<CommandSourceStack> levels = Commands.literal("levels")
                .then(amount(party, n -> new Rewards.Packet(0, (int) n, 0, 0, 0)));
        LiteralArgumentBuilder<CommandSourceStack> sp = Commands.literal("sp")
                .then(amount(party, n -> new Rewards.Packet(0, 0, (int) n, 0, 0)));
        LiteralArgumentBuilder<CommandSourceStack> karma = Commands.literal("karma")
                .then(amount(party, n -> new Rewards.Packet(0, 0, 0, n, 0)));
        LiteralArgumentBuilder<CommandSourceStack> money = Commands.literal("money")
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                        .executes(ctx -> reward(ctx, party,
                                new Rewards.Packet(0, 0, 0, 0, DoubleArgumentType.getDouble(ctx, "amount")), ""))
                        .then(Commands.literal("for")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> reward(ctx, party,
                                                new Rewards.Packet(0, 0, 0, 0, DoubleArgumentType.getDouble(ctx, "amount")),
                                                StringArgumentType.getString(ctx, "reason"))))));
        // Chained onto the first, so one call attaches all five to the player
        // argument above.
        return xp.then(levels).then(sp).then(karma).then(money);
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

    private static int possess(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var looked = Possession.lookedAt(player, POSSESS_REACH);
        if (looked.isEmpty()) {
            Feedback.chat(player, "&7Nothing in your sights to take over. Look straight at a creature.");
            return 0;
        }
        var mob = looked.get();
        // Drifting first, so `release` and `return` stay separately meaningful:
        // one gives the creature back, the other gives you your body back.
        boolean startedDrifting = Presence.drift(player);
        switch (Possession.possess(player, mob)) {
            case NONE -> {
                Feedback.chat(player, "&5You are wearing &f" + mob.getName().getString()
                        + "&5. &f/st say <words>&5 speaks as it, &f/st release&5 lets it go."
                        + (startedDrifting ? " &8(your body is anchored where you left it)" : ""));
                return 1;
            }
            case ALREADY_HELD -> Feedback.chat(player,
                    "&7You are already wearing something. &f/st release&7 first.");
            case TAKEN -> Feedback.chat(player, "&7Another Storyteller is already wearing that one.");
        }
        return 0;
    }

    private static int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
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
        var mob = Possession.heldBy(player);
        if (mob.isEmpty()) {
            Feedback.chat(player, "&7You are not wearing anything to speak through. &f/st possess&7 first.");
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        int heard = Possession.speak(player, mob.get(), text, SPEAK_RADIUS);
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

    private STCommands() {}
}
