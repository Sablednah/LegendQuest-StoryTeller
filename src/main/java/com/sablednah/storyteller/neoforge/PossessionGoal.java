package com.sablednah.storyteller.neoforge;

import java.util.EnumSet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.Mob;

/**
 * The goal that holds a possessed mob still, and walks it where the
 * Storyteller goes.
 *
 * <p><b>Why a goal rather than clearing the AI.</b> ZombieMod's
 * {@code GenusApplier} clears a mob's goals with
 * {@code goalSelector.removeAllGoals(g -> true)}, which is right for its job —
 * it is rebuilding the mob permanently. It is wrong for this one: vanilla
 * registers a mob's goals in {@code registerGoals()} at construction and never
 * again, so a cleared goal list cannot be put back. A possessed goblin has to
 * become a goblin again on release, and this is the only way to guarantee
 * that.</p>
 *
 * <p>A goal at priority 0 holding MOVE, LOOK, JUMP and TARGET starves every
 * other goal of the flags it needs, so the mob's own AI stops deciding things
 * without a single goal being removed. Releasing is one
 * {@code removeGoal} call and the mob is exactly what it was.</p>
 *
 * <p><b>Driving, on a vanilla client.</b> While possessing, the Storyteller is
 * a spectator with their camera bound to the mob — a vanilla client honours
 * both. Their own invisible body still flies on WASD, so the mob is steered by
 * walking it toward wherever that body has drifted to. It is leading rather
 * than driving, and it is deliberately the version that needs no client mod at
 * all; one-to-one input control is what the Storyteller's own mod adds later.</p>
 */
public class PossessionGoal extends Goal {

    /** Close enough that following would only jitter the mob on the spot. */
    private static final double ARRIVED = 1.6D;

    private final Mob mob;
    private final ServerPlayer possessor;

    public PossessionGoal(Mob mob, ServerPlayer possessor) {
        this.mob = mob;
        this.possessor = possessor;
        // Every flag, so nothing else in the mob's list can claim one.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET));
    }

    public ServerPlayer possessor() {
        return possessor;
    }

    @Override
    public boolean canUse() {
        // Holds for as long as the possession does. Possession.release removes
        // the goal outright, so there is no "should I stop?" to get wrong.
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        mob.setTarget(null);
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    @Override
    public void tick() {
        // Mirror the Storyteller's rotation onto the creature, every tick.
        //
        // This is what gives mouse-look while possessing, and it works on a
        // vanilla client: binding the camera to an entity renders from that
        // entity's eyes AND its orientation, so the only way to look around is
        // to turn the thing you are wearing.
        //
        // Deliberately NOT the look control. setLookAt(possessor) aims the
        // creature at whoever is possessing it -- which, with the camera in its
        // head, points the view straight back at your own drifting body. The
        // two together fight each other every tick, which is what the first
        // draft of this did.
        float yaw = possessor.getYRot();
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.yBodyRot = yaw;
        mob.setXRot(possessor.getXRot());

        if (possessor.level() != mob.level()) return; // mid-teleport; wait

        double distance = mob.distanceToSqr(possessor);
        if (distance <= ARRIVED * ARRIVED) {
            mob.getNavigation().stop();
            return;
        }
        // Path rather than teleport, so the mob is bound by its own legs: a
        // possessed cow should not scale a cliff the audience can see it
        // could not climb. When the path is impossible the mob simply stops,
        // which reads correctly as the creature refusing.
        mob.getNavigation().moveTo(possessor.getX(), possessor.getY(), possessor.getZ(), 1.0D);
    }
}
