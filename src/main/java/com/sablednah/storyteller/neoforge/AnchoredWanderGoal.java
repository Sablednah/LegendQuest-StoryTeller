package com.sablednah.storyteller.neoforge;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * "Stay near this spot" — GUARD with a tight radius, PATROL with a loose one.
 * Vanilla has nothing that keeps a generic mob near a fixed point; its
 * wander goals only avoid the mob's OWN starting position drifting, with no
 * anchor a Storyteller can place deliberately.
 *
 * <p>Added at a normal, low priority alongside a mob's own goals — unlike
 * {@link PossessionGoal}, this is meant to coexist with attacking, fleeing
 * fire and everything else a cast member should still do for itself. Only
 * MOVE is claimed, so a guard interrupted by combat naturally falls back to
 * wandering once the fight ends.</p>
 */
public class AnchoredWanderGoal extends Goal {

    private final PathfinderMob mob;
    private final BlockPos anchor;
    private final double radius;
    private int cooldown;

    /** Exposed so {@code Cast.currentBehaviour} can tell GUARD and PATROL
     *  apart when reading back what is already applied to a mob. */
    public double radius() {
        return radius;
    }

    public AnchoredWanderGoal(PathfinderMob mob, BlockPos anchor, double radius) {
        this.mob = mob;
        this.anchor = anchor;
        this.radius = radius;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (--cooldown > 0) return false;
        cooldown = 60 + mob.getRandom().nextInt(60);
        return mob.getNavigation().isDone();
    }

    @Override
    public boolean canContinueToUse() {
        return false; // one destination per activation; canUse() re-triggers on its own cooldown
    }

    @Override
    public void start() {
        // A point within radius of the ANCHOR, not the mob — DefaultRandomPos
        // only knows how to pick near the mob itself, so drifting far from the
        // anchor would only ever compound. Retried a few times against
        // whatever the pathfinder can actually reach, same tolerance vanilla's
        // own wander goals use.
        for (int tries = 0; tries < 6; tries++) {
            double angle = mob.getRandom().nextDouble() * Mth.TWO_PI;
            double dist = mob.getRandom().nextDouble() * radius;
            Vec3 target = new Vec3(
                    anchor.getX() + 0.5D + Math.cos(angle) * dist,
                    anchor.getY(),
                    anchor.getZ() + 0.5D + Math.sin(angle) * dist);
            Vec3 walkable = DefaultRandomPos.getPosTowards(mob, 10, 3, target, Mth.HALF_PI);
            if (walkable != null) {
                mob.getNavigation().moveTo(walkable.x, walkable.y, walkable.z, 1.0D);
                return;
            }
        }
    }
}
