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
 * <p><b>Steering, on a vanilla client.</b> The Storyteller drifts as a
 * spectator and this goal walks the creature to wherever they have flown, so
 * the mob is led rather than driven. It is deliberately the version that needs
 * no client mod at all; one-to-one input control is what the Storyteller's own
 * mod adds later.
 *
 * <p><b>This goal is used only when the camera is NOT bound to the mob</b>,
 * and that is not a preference. A vanilla client stops sending movement
 * entirely while spectating an entity — {@code LocalPlayer.sendPosition} is
 * gated on {@code isControlledCamera()}, which is
 * {@code getCameraEntity() == this} — and {@code ServerPlayer} snaps the
 * spectator onto its camera entity, rotation included, every single tick.
 * Binding the camera therefore costs the Storyteller every input they have:
 * they cannot walk, and they cannot even look. Eyes or control, never both.
 * An earlier version of this class claimed both worked; it was never true, and
 * the first person to try steering a cow found out.</p>
 */
public class PossessionGoal extends Goal {

    /** Close enough that following would only jitter the mob on the spot. */
    static final double ARRIVED = 1.6D;

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
        // Turn the creature's head to where the Storyteller is looking, so it
        // reads as attending to what they attend to.
        //
        // Deliberately NOT the look control. setLookAt(possessor) aims the
        // creature at whoever is possessing it, so it stares at the person
        // leading it rather than where they are going.
        // HEAD only. Setting the body rotation as well is what made a led cow
        // spin on the spot: pathfinding turns the body toward the next node,
        // and forcing yBodyRot to the Storyteller's facing every tick took
        // that away, so a creature asked to walk backwards could never orient
        // to walk at all. The head follows your gaze; the body follows its
        // feet. Reported live: "it cant walk a direction it is not facing, so
        // it sort of spins".
        mob.setYHeadRot(possessor.getYRot());
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
