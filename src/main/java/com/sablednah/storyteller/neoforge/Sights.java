package com.sablednah.storyteller.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * What the Storyteller means by "that one".
 *
 * <p>Every creature command in this mod resolves its target the same way:
 * whatever is under the crosshair. That is the right default — it needs no
 * names, no ids and no selectors, and a GM pointing at a thing is the most
 * natural gesture there is. It is also fragile in exactly the moment a scene
 * gets busy. Dressing an NPC is four commands, and each one wants the head
 * still. Walking a possessed cow across a room means looking where it should
 * go, which is by definition not at it. Two mobs a block apart trade places
 * under the crosshair as they breathe.</p>
 *
 * <p>So a lock: {@code /st lock} takes what is in the sights <i>now</i> and
 * makes it the answer to "that one" until it is let go. Nothing else in the mod
 * changes — the commands still ask one question, they just ask it here instead
 * of asking the crosshair directly.</p>
 *
 * <p><b>The lock stores an id, never the entity.</b> Holding the {@code Mob}
 * would keep a removed one reachable long after the level let go of it, and
 * hand back a corpse as a target; resolving from the id each time means a
 * target that has gone is <i>discovered</i> to have gone, at which point the
 * lock can say so and clear itself. Every level is searched rather than the one
 * it was locked in, so a creature that walks through a portal stays locked —
 * which is what someone running a scene across two dimensions means.</p>
 *
 * <p><b>Not persisted</b>, for the same reason possession is not: a lock is a
 * convenience for a scene in progress, and sticky invisible state surviving a
 * restart is the kind of thing that makes the next command inexplicable.</p>
 */
public final class Sights {

    /** Storyteller → the wild creature they locked. */
    private static final Map<UUID, UUID> LOCKED_MOB = new HashMap<>();
    /** Storyteller → the Cast NPC they locked. Separate for the usual reason:
     *  an NPC id is Cast's, not an entity's, and a human body has no entity at
     *  all for {@code getEntity} to find. */
    private static final Map<UUID, UUID> LOCKED_NPC = new HashMap<>();
    /** What it was called when it was locked, so a target that has vanished can
     *  still be named in the sentence saying it vanished. */
    private static final Map<UUID, String> LOCKED_NAME = new HashMap<>();

    public static boolean isLocked(ServerPlayer player) {
        UUID id = player.getUUID();
        return LOCKED_MOB.containsKey(id) || LOCKED_NPC.containsKey(id);
    }

    /** What the lock was called, for a message. Empty when nothing is locked. */
    public static Optional<String> lockedName(ServerPlayer player) {
        return Optional.ofNullable(LOCKED_NAME.get(player.getUUID()));
    }

    /**
     * Lock whatever is in the sights now.
     *
     * @return the name locked on, or empty if there was nothing to lock.
     */
    public static Optional<String> lock(ServerPlayer player, double reach) {
        Optional<Possession.Sighted> sighted = Possession.lookingAt(player, reach);
        if (sighted.isEmpty()) return Optional.empty();
        Possession.Sighted target = sighted.get();
        unlock(player);
        UUID id = player.getUUID();
        if (target.isNpc()) {
            LOCKED_NPC.put(id, target.npcId());
        } else {
            LOCKED_MOB.put(id, target.mob().getUUID());
        }
        LOCKED_NAME.put(id, target.name());
        return Optional.of(target.name());
    }

    public static void unlock(ServerPlayer player) {
        UUID id = player.getUUID();
        LOCKED_MOB.remove(id);
        LOCKED_NPC.remove(id);
        LOCKED_NAME.remove(id);
    }

    /**
     * The one question every creature command asks: which one do you mean?
     *
     * <p>The lock wins when it is held and still real. When it is held and the
     * thing is gone, the lock is cleared and the Storyteller is told before the
     * command proceeds on their gaze — a target that silently changed from the
     * locked NPC to whatever happens to be under the crosshair is the worst
     * possible outcome, and it is the one that happens if this returns quietly.</p>
     */
    public static Optional<Possession.Sighted> target(ServerPlayer player, double reach) {
        Optional<Possession.Sighted> locked = resolveLock(player);
        if (locked.isPresent()) {
            // Only ever on a command they just typed, so it cannot stomp on
            // something they were reading. Action bar rather than chat: this is
            // a reminder of state, not a line worth keeping in the log.
            Feedback.actionBar(player, "&8Locked on &7" + locked.get().name());
            return locked;
        }
        return Possession.lookingAt(player, reach);
    }

    /**
     * The lock alone, with no fall-back to the crosshair.
     *
     * <p>{@code /st say} uses this rather than {@link #target}: lending your
     * voice to whatever you happen to be looking at would put words in the mouth
     * of a creature the Storyteller never chose, and that line cannot be taken
     * back once the table has read it. Speaking is the one thing here that must
     * be aimed deliberately.</p>
     */
    public static Optional<Possession.Sighted> locked(ServerPlayer player) {
        return resolveLock(player);
    }

    /** The locked target if it is still there, clearing and explaining if not. */
    private static Optional<Possession.Sighted> resolveLock(ServerPlayer player) {
        UUID id = player.getUUID();
        MinecraftServer server = player.level().getServer();
        if (server == null) return Optional.empty();

        UUID npcId = LOCKED_NPC.get(id);
        if (npcId != null) {
            Optional<CastSupport.Target> npc = Possession.castAvailable()
                    ? CastSupport.byId(server, npcId)
                    : Optional.empty();
            if (npc.isEmpty()) return lostIt(player, "is no longer there");
            return Optional.of(new Possession.Sighted(
                    null, npcId, npc.get().name(), npc.get().canPossess()));
        }

        UUID mobId = LOCKED_MOB.get(id);
        if (mobId == null) return Optional.empty();
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(mobId);
            if (entity instanceof Mob mob && mob.isAlive()) {
                return Optional.of(Possession.sightedOf(mob));
            }
        }
        return lostIt(player, "is gone");
    }

    private static Optional<Possession.Sighted> lostIt(ServerPlayer player, String what) {
        String name = LOCKED_NAME.getOrDefault(player.getUUID(), "Your lock");
        unlock(player);
        Feedback.chat(player, "&7" + name + " &7" + what + ", so the lock is off. "
                + "&8Back to whatever you are looking at.");
        return Optional.empty();
    }

    /** A creature dying takes any lock on it with it. Told, not silent: the
     *  next command would otherwise act on something else entirely. */
    static void mobWentAway(Mob mob, String wording) {
        clearWhere(LOCKED_MOB, mob.getUUID(), wording);
    }

    /** Cast's own removal path, which is the only one a human NPC has — a
     *  phantom is in no level, so no vanilla hook can ever fire for it. */
    static void npcWentAway(UUID npcId, String wording) {
        clearWhere(LOCKED_NPC, npcId, wording);
    }

    private static void clearWhere(Map<UUID, UUID> held, UUID targetId, String wording) {
        MinecraftServer server = Possession.server();
        if (server == null) return;
        // Copied first: unlock writes to the same map we are walking.
        for (Map.Entry<UUID, UUID> entry : Map.copyOf(held).entrySet()) {
            if (!entry.getValue().equals(targetId)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            String name = LOCKED_NAME.getOrDefault(entry.getKey(), "Your lock");
            if (player == null) {
                unlockById(entry.getKey());
                continue;
            }
            unlock(player);
            Feedback.chat(player, "&7" + name + " &7" + wording + ", so the lock is off.");
        }
    }

    private static void unlockById(UUID playerId) {
        LOCKED_MOB.remove(playerId);
        LOCKED_NPC.remove(playerId);
        LOCKED_NAME.remove(playerId);
    }

    /** Logging out drops the lock. Unlike the drift anchor there is nothing to
     *  come back to: the scene they locked for is over. */
    static void forget(ServerPlayer player) {
        unlock(player);
    }

    private Sights() {}
}
