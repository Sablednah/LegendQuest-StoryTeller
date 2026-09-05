package com.sablednah.storyteller.neoforge;

import com.sablednah.storyteller.StoryTeller;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Permission nodes, LuckPerms-compatible via NeoForge's PermissionAPI — the
 * same scheme LegendQuest uses, so a server already granting
 * {@code legendquest.*} needs no new tooling.
 *
 * <p>The nodes are deliberately split rather than one master switch. Running a
 * story is a role you hand to a person you trust, but "can hand out levels and
 * money" and "can drift through walls unseen" are different amounts of trust,
 * and a server owner should be able to grant the second without the first.</p>
 *
 * <p><b>Every node defaults to false, ops included.</b> LegendQuest lets op
 * level 2 satisfy {@code legendquest.admin}; this does not follow that. An op
 * is someone who can fix the server, which is not the same as someone who
 * should be able to silently possess a player's rival mid-session.</p>
 */
public final class STPermissions {

    /** The role itself: use /st at all, spectate, jump, read the roster. */
    public static final PermissionNode<Boolean> STORYTELLER = new PermissionNode<>(
            StoryTeller.MODID, "storyteller", PermissionTypes.BOOLEAN,
            (player, uuid, context) -> false);

    /** Hand out XP, levels, skill points, karma, items and money. */
    public static final PermissionNode<Boolean> REWARD = new PermissionNode<>(
            StoryTeller.MODID, "reward", PermissionTypes.BOOLEAN,
            (player, uuid, context) -> false);

    @SubscribeEvent
    static void onGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(STORYTELLER, REWARD);
    }

    /** May this player run a story at all? */
    public static boolean isStoryteller(ServerPlayer player) {
        return PermissionAPI.getPermission(player, STORYTELLER);
    }

    /** May this player hand out rewards? */
    public static boolean canReward(ServerPlayer player) {
        return PermissionAPI.getPermission(player, REWARD);
    }

    /**
     * The command-tree gate. A console or command-block source has no player
     * and passes on operator level alone, so automation keeps working; a
     * player source must hold the node.
     */
    public static boolean gate(net.minecraft.commands.CommandSourceStack src,
            java.util.function.Predicate<ServerPlayer> node) {
        if (src.getEntity() instanceof ServerPlayer player) return node.test(player);
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src);
    }

    private STPermissions() {}
}
