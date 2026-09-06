package com.sablednah.storyteller.scene;

import net.minecraft.server.level.ServerLevel;

/**
 * One reversible thing a Storyteller did to the world — a structure placed, a
 * mob cast — logged so {@code /st undo} and the panic button have something
 * concrete to take back.
 *
 * <p>Deliberately in-memory only, the same choice {@link
 * com.sablednah.storyteller.neoforge.Possession} made and for the same
 * reason: this is scene bookkeeping, not player data. Losing the undo log to
 * a restart is a shrug; a leftover cast member is one {@code /kill} away, and
 * a leftover structure is exactly what a story is often FOR.</p>
 */
public interface SceneAction {

    /** Put the world back. @return true if it actually changed anything. */
    boolean undo(ServerLevel level);

    /** One clause for the summary line, e.g. "a structure" or "a Zombie". */
    String describe();
}
