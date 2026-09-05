package com.sablednah.storyteller.neoforge;

import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.world.level.GameType;

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
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
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
                                .then(rewardArgs(false)))
                        .then(Commands.literal("party")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(rewardArgs(true)))));

        dispatcher.register(st);
    }

    /**
     * The reward arguments, shared by the single and party forms. XP is
     * required and the rest optional, because "award some XP" is the common
     * case and should not need three zeroes typed after it.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> rewardArgs(boolean party) {
        return Commands.literal("xp")
                .then(Commands.argument("amount", LongArgumentType.longArg())
                        .executes(ctx -> reward(ctx, party, "", 0, 0))
                        .then(Commands.literal("karma")
                                .then(Commands.argument("karma", LongArgumentType.longArg())
                                        .executes(ctx -> reward(ctx, party,
                                                "", LongArgumentType.getLong(ctx, "karma"), 0))))
                        .then(Commands.literal("money")
                                .then(Commands.argument("money", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> reward(ctx, party, "",
                                                0, DoubleArgumentType.getDouble(ctx, "money")))))
                        .then(Commands.literal("for")
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> reward(ctx, party,
                                                StringArgumentType.getString(ctx, "reason"), 0, 0)))));
    }

    // --- presence ---

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
        // No anchor: a crash ate it, or they never drifted. Refusing here is
        // the one answer that leaves someone stuck in spectator with no way
        // out, so put them somewhere playable and say exactly what happened.
        player.setGameMode(GameType.SURVIVAL);
        Feedback.chat(player, "&eNo anchor was stored — the server may have restarted while you "
                + "were drifting. You are back in survival, where you were standing.");
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

    // --- oversight ---

    private static int who(CommandContext<CommandSourceStack> ctx) {
        String rendered = Roster.render(Roster.of(ctx.getSource().getServer()));
        ctx.getSource().sendSuccess(() -> Feedback.colored(rendered), false);
        return 1;
    }

    // --- rewards ---

    private static int reward(CommandContext<CommandSourceStack> ctx, boolean party,
            String reason, long karma, double money) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        long xp = LongArgumentType.getLong(ctx, "amount");
        var packet = new Rewards.Packet(xp, karma, money);
        if (packet.isEmpty()) {
            ctx.getSource().sendFailure(Feedback.colored("&cNothing to award."));
            return 0;
        }

        var results = party ? Rewards.party(target, packet, reason)
                            : java.util.List.of(Rewards.give(target, packet, reason));

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

    private STCommands() {}
}
