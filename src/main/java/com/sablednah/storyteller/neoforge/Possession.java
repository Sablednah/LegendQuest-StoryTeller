package com.sablednah.storyteller.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Wearing a mob.
 *
 * <p>Three pieces, all of which a vanilla client already understands: the
 * Storyteller becomes a spectator, their camera is bound to the mob, and the
 * mob gets a {@link PossessionGoal} that starves its own AI of every flag. The
 * audience sees a creature behaving oddly — which is the point — and needs no
 * mod to see it.</p>
 *
 * <p><b>Possession is deliberately not persisted</b>, unlike the drift anchor.
 * The anchor had to survive a crash because losing it strands a player in
 * spectator with no way back. Possession loses nothing: the goal object dies
 * with the server, so a restart hands the mob its own AI back automatically,
 * which is exactly the right outcome. Persisting it would mean reconstructing
 * a puppet nobody is holding.</p>
 */
public final class Possession {

    /** Storyteller → the mob they are wearing. */
    private static final Map<UUID, Mob> HELD = new HashMap<>();
    /** And the goal instance, so release can remove precisely that one. */
    private static final Map<UUID, PossessionGoal> GOALS = new HashMap<>();

    public static Optional<Mob> heldBy(ServerPlayer player) {
        return Optional.ofNullable(HELD.get(player.getUUID()));
    }

    public static boolean isPossessing(ServerPlayer player) {
        return HELD.containsKey(player.getUUID());
    }

    /** Is this mob already being worn by somebody? Two Storytellers fighting
     *  over one goblin is a scene nobody wants to debug. */
    public static Optional<ServerPlayer> possessorOf(Mob mob) {
        return HELD.entrySet().stream()
                .filter(e -> e.getValue() == mob)
                .findFirst()
                .flatMap(e -> Optional.ofNullable(
                        mob.level().getServer().getPlayerList().getPlayer(e.getKey())));
    }

    /**
     * The mob the Storyteller is looking at, within reach.
     *
     * <p>Targeting by gaze rather than by name or selector is the whole
     * gesture: look at the goblin, possess the goblin. Name arguments would
     * mean knowing a UUID, and {@code @e[...]} selectors need operator level 2
     * to parse — which the un-opped Storyteller this mod is built for does not
     * have.</p>
     */
    public static Optional<Mob> lookedAt(ServerPlayer player, double reach) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(reach));
        AABB box = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);

        Mob best = null;
        double bestDistance = reach * reach;
        for (Entity entity : player.level().getEntities(player, box, e -> e instanceof Mob)) {
            Optional<Vec3> hit = entity.getBoundingBox().inflate(0.3D).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceToSqr(hit.get());
            if (d < bestDistance) {
                bestDistance = d;
                best = (Mob) entity;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Why a possession did not happen, so the caller can say which.
     *
     * <p>There is no "that is a player" case: {@code Player} does not extend
     * {@code Mob}, so the signature already makes it unrepresentable and
     * {@link #lookedAt} cannot return one. The type system is a better guard
     * than a runtime check that can never fire.</p>
     */
    public enum Refusal { NONE, ALREADY_HELD, TAKEN }

    /**
     * Take the mob over. The caller is expected to have put the Storyteller
     * into spectator first (drifting) — this does not do it for them, because
     * possessing and drifting are separately undoable and conflating them
     * makes "release" ambiguous.
     */
    public static Refusal possess(ServerPlayer player, Mob mob) {
        if (HELD.containsKey(player.getUUID())) return Refusal.ALREADY_HELD;
        if (possessorOf(mob).isPresent()) return Refusal.TAKEN;

        PossessionGoal goal = new PossessionGoal(mob, player);
        mob.goalSelector.addGoal(0, goal);
        HELD.put(player.getUUID(), mob);
        GOALS.put(player.getUUID(), goal);
        player.setCamera(mob);
        return Refusal.NONE;
    }

    /**
     * Give the mob back to itself. Removes exactly the goal that was added, so
     * nothing the mob was born with is disturbed.
     *
     * @return the mob that was released, if any.
     */
    public static Optional<Mob> release(ServerPlayer player) {
        Mob mob = HELD.remove(player.getUUID());
        PossessionGoal goal = GOALS.remove(player.getUUID());
        if (mob != null && goal != null) {
            mob.goalSelector.removeGoal(goal);
            mob.getNavigation().stop();
        }
        // Always hand the camera back, even if the mob has since died or
        // unloaded: a Storyteller stuck looking through a corpse is the
        // failure this must never leave behind.
        player.setCamera(player);
        return Optional.ofNullable(mob);
    }

    /**
     * Say something as the possessed creature, to everyone who could plausibly
     * hear it.
     *
     * <p>Plain chat, so a vanilla client renders it with no help. The mob's
     * own display name is the speaker, which is what makes an unnamed zombie
     * into a character without an NPC system existing yet.</p>
     *
     * @param radius blocks; anyone further away hears nothing.
     * @return how many players heard it.
     */
    public static int speak(ServerPlayer player, Mob mob, String text, double radius) {
        String name = mob.getDisplayName() != null
                ? mob.getDisplayName().getString()
                : mob.getName().getString();
        Component line = Feedback.colored("&f<&e" + name + "&f> &7" + text);
        int heard = 0;
        for (ServerPlayer listener : player.level().getServer().getPlayerList().getPlayers()) {
            if (listener.level() != mob.level()) continue;
            if (listener.distanceToSqr(mob) > radius * radius) continue;
            listener.sendSystemMessage(line);
            heard++;
        }
        // The Storyteller hears their own line even from outside the radius:
        // they are the one thing in the scene that is not standing there.
        if (player.distanceToSqr(mob) > radius * radius || player.level() != mob.level()) {
            player.sendSystemMessage(line);
        }
        return heard;
    }

    /** Drop a possession without touching the player — for logout, where the
     *  player object is on its way out. */
    public static void forget(ServerPlayer player) {
        Mob mob = HELD.remove(player.getUUID());
        PossessionGoal goal = GOALS.remove(player.getUUID());
        if (mob != null && goal != null) {
            mob.goalSelector.removeGoal(goal);
            mob.getNavigation().stop();
        }
    }

    private Possession() {}
}
