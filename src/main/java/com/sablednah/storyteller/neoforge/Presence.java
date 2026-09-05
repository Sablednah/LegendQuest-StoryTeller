package com.sablednah.storyteller.neoforge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Where the Storyteller is standing, and getting them somewhere else.
 *
 * <p>Drifting through a scene is vanilla spectator mode: no new rendering, no
 * client mod, and every other player already knows how to not see a spectator.
 * The tool the GM actually wants is not "become a spectator" — they can type
 * that — it is <b>getting their body back</b>, which is why every entry point
 * here records an anchor first.</p>
 *
 * <p><b>The anchor is in memory only.</b> A clean disconnect restores it (see
 * {@link STServerEvents}), so the bad case is a server crash mid-scene, which
 * leaves a Storyteller in spectator where they were standing. {@code /st
 * return} is written to cope with a missing anchor rather than refuse, so the
 * way out never depends on the thing that was lost.</p>
 */
public final class Presence {

    /** Where a Storyteller left their body, and what they were doing with it. */
    private record Anchor(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot, GameType mode) {}

    private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();

    /** Is this player currently out of body? */
    public static boolean isDrifting(ServerPlayer player) {
        return ANCHORS.containsKey(player.getUUID());
    }

    /**
     * Drop into spectator, remembering where the body was. Idempotent: calling
     * it twice must not overwrite the original anchor with the spectating
     * position, or "return" would bring them back to the middle of the scene.
     */
    public static boolean drift(ServerPlayer player) {
        if (ANCHORS.containsKey(player.getUUID())) return false;
        ANCHORS.put(player.getUUID(), new Anchor(
                player.level().dimension(), player.position(),
                player.getYRot(), player.getXRot(), player.gameMode.getGameModeForPlayer()));
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
        Anchor anchor = ANCHORS.remove(player.getUUID());
        if (anchor == null) return false;
        ServerLevel level = player.level().getServer().getLevel(anchor.dimension());
        if (level == null) level = player.level().getServer().overworld();
        player.setGameMode(anchor.mode());
        player.teleportTo(level, anchor.pos().x, anchor.pos().y, anchor.pos().z,
                java.util.Set.of(), anchor.yRot(), anchor.xRot(), false);
        return true;
    }

    /** Forget an anchor without acting on it — for logout, where the player
     *  object is about to stop being useful. */
    public static Optional<GameType> takeAnchorMode(ServerPlayer player) {
        Anchor anchor = ANCHORS.remove(player.getUUID());
        return Optional.ofNullable(anchor).map(Anchor::mode);
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
