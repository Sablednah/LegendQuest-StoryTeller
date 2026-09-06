package com.sablednah.storyteller.neoforge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.storyteller.state.Anchor;
import com.sablednah.storyteller.state.STAttachments;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Where the Storyteller is standing, and getting them somewhere else.
 *
 * <p>Drifting through a scene is vanilla spectator mode: no new rendering, no
 * client mod, and every other player already knows how to not see a spectator.
 * The tool the GM actually wants is not "become a spectator" — they can type
 * that — it is <b>getting their body back</b>, which is why every entry point
 * here records an anchor first.</p>
 *
 * <p><b>The anchor is persisted</b>, on the player, as an attachment. It has
 * to be: drifting puts someone in spectator, and the anchor is the only record
 * of what they were before. Held in memory, a server crash mid-scene would
 * turn into a Storyteller who logs back in as a spectator with nowhere to
 * return to — a state they cannot leave without an operator. {@code /st
 * return} still copes with a missing anchor rather than refusing, because the
 * way out should never depend on the thing that was lost.</p>
 */
public final class Presence {

    /** Is this player currently out of body? */
    public static boolean isDrifting(ServerPlayer player) {
        return player.getData(STAttachments.ANCHOR).isSet();
    }

    /**
     * Drop into spectator, remembering where the body was. Idempotent: calling
     * it twice must not overwrite the original anchor with the spectating
     * position, or "return" would bring them back to the middle of the scene.
     */
    public static boolean drift(ServerPlayer player) {
        if (player.getData(STAttachments.ANCHOR).isSet()) return false;
        player.setData(STAttachments.ANCHOR, new Anchor(
                Optional.of(player.level().dimension()),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer()));
        player.setGameMode(GameType.SPECTATOR);
        return true;
    }

    /**
     * Back to the body: original dimension, position, facing and game mode.
     *
     * @return false when there was no anchor — the caller should still put the
     *         player back into a playable mode and say so, because refusing to
     *         act is the one response that leaves them stuck.
     */
    public static boolean returnToBody(ServerPlayer player) {
        Anchor anchor = player.getData(STAttachments.ANCHOR);
        if (!anchor.isSet()) return false;
        player.setData(STAttachments.ANCHOR, Anchor.empty());
        ServerLevel level = player.level().getServer().getLevel(anchor.dimension().get());
        // The dimension could be gone -- a datapack removed between sessions.
        // The overworld is a worse landing than the anchor, and a far better
        // one than staying a spectator forever.
        if (level == null) level = player.level().getServer().overworld();
        player.setGameMode(anchor.mode());
        player.teleportTo(level, anchor.x(), anchor.y(), anchor.z(),
                java.util.Set.of(), anchor.yRot(), anchor.xRot(), false);
        return true;
    }

    /** Stand where that player is standing, facing the way they face. */
    public static void jumpTo(ServerPlayer mover, ServerPlayer target) {
        mover.teleportTo((ServerLevel) target.level(),
                target.getX(), target.getY(), target.getZ(),
                java.util.Set.of(), target.getYRot(), target.getXRot(), false);
    }

    /**
     * The next player to look in on, wrapping around, skipping the Storyteller
     * themselves. Ordered by name so "next" means the same thing twice running
     * — player list order shifts as people join and leave, and a cycle that
     * reshuffles under you is worse than no cycle.
     */
    public static Optional<ServerPlayer> nextAudience(MinecraftServer server, ServerPlayer self, UUID after) {
        List<ServerPlayer> others = server.getPlayerList().getPlayers().stream()
                .filter(p -> !p.getUUID().equals(self.getUUID()))
                .sorted(java.util.Comparator.comparing(p -> p.getName().getString()))
                .toList();
        if (others.isEmpty()) return Optional.empty();
        if (after == null) return Optional.of(others.getFirst());
        for (int i = 0; i < others.size(); i++) {
            if (others.get(i).getUUID().equals(after)) {
                return Optional.of(others.get((i + 1) % others.size()));
            }
        }
        return Optional.of(others.getFirst());
    }

    private Presence() {}
}
