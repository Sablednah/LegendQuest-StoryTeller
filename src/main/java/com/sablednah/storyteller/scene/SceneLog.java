package com.sablednah.storyteller.scene;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-Storyteller history of reversible actions this scene, oldest first —
 * {@code /st undo} pops the most recent, the panic button empties the whole
 * stack.
 *
 * <p>Undoing in reverse order matters whenever actions overlap: a structure
 * placed on top of an earlier one has to come back off before the first
 * structure's own undo can restore what was really underneath it. LIFO is
 * the only order that guarantees that.</p>
 */
public final class SceneLog {

    private static final Map<UUID, Deque<SceneAction>> LOG = new HashMap<>();

    public static void record(ServerPlayer player, SceneAction action) {
        LOG.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>()).addLast(action);
    }

    /** @return what was undone, if anything. */
    public static java.util.Optional<String> undoLast(ServerPlayer player, ServerLevel level) {
        Deque<SceneAction> deque = LOG.get(player.getUUID());
        if (deque == null || deque.isEmpty()) return java.util.Optional.empty();
        SceneAction action = deque.removeLast();
        action.undo(level);
        return java.util.Optional.of(action.describe());
    }

    /** The panic button: everything this Storyteller has done this scene, gone. */
    public static int undoAll(ServerPlayer player, ServerLevel level) {
        Deque<SceneAction> deque = LOG.remove(player.getUUID());
        if (deque == null) return 0;
        int count = 0;
        while (!deque.isEmpty()) {
            deque.removeLast().undo(level);
            count++;
        }
        return count;
    }

    public static int size(ServerPlayer player) {
        Deque<SceneAction> deque = LOG.get(player.getUUID());
        return deque == null ? 0 : deque.size();
    }

    private SceneLog() {}
}
