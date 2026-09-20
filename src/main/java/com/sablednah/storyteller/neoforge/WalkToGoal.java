package com.sablednah.storyteller.neoforge;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * "Go there" — one destination, then it is done and takes itself off.
 *
 * <p>A goal rather than a bare {@code navigation.moveTo} from the command
 * handler, and that is the whole reason this class exists. Every other mover in
 * this mod is a goal ({@link AnchoredWanderGoal}, {@link FollowPlayerGoal},
 * {@link PossessionGoal}) because a path issued from outside the goal system is
 * re-pathed over by whatever goal is already running — a guard's wander goal
 * re-targets its anchor on a 60-120 tick cooldown, so a mob walked across a room
 * turns round and strolls home a few seconds later. Reported live as exactly
 * that, in the Cast case: "it ran. then bounced back to anchor, repeat".</p>
 *
 * <p>Priority sits <i>above</i> the behaviour goals rather than replacing them,
 * so a guard walking to a new post is still interruptible by the things a cast
 * member should always do for itself — fighting back, fleeing fire. Only
 * {@code MOVE} is claimed for the same reason: it can still look where it likes
 * on the way.</p>
 *
 * <p><b>On arrival it hands the mob back.</b> {@code onArrive} is what
 * re-anchors a guard or a patrol at the place it was sent to, which is the
 * chosen meaning of the order: the behaviour is kept and its post moves, rather
 * than the behaviour being cleared or the mob wandering home. A behaviour with
 * no anchor — follow, flee — simply resumes, and the command says so instead of
 * implying a post was moved.</p>
 */
public class WalkToGoal extends Goal {

    /** Close enough to call it arrived. A path ends on the block, not in its
     *  centre, and insisting on the exact position never completes. */
    private static final double ARRIVED_WITHIN = 1.75D;

    /** A walk that cannot finish must not hold the mob for ever: a destination
     *  across a ravine leaves the navigation idle and the goal would otherwise
     *  sit claiming MOVE until something else took it. Twenty seconds is long
     *  enough for a long walk and short enough not to strand a scene. */
    private static final int GIVE_UP_AFTER = 400;

    private final PathfinderMob mob;
    private final BlockPos target;
    private final Runnable onArrive;

    private boolean done;
    private int ticks;

    public WalkToGoal(PathfinderMob mob, BlockPos target, Runnable onArrive) {
        this.mob = mob;
        this.target = target;
        this.onArrive = onArrive;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Where it was sent, so {@code Cast} can tell one order from another when
     *  a second {@code /st move} replaces the first. */
    public BlockPos target() {
        return target;
    }

    /** Still walking — false once it has arrived or given up. Read by
     *  {@code Cast.isWalking} so feedback does not claim a walk that has
     *  already ended. */
    public boolean isRunning() {
        return !done;
    }

    @Override
    public boolean canUse() {
        return !done;
    }

    @Override
    public boolean canContinueToUse() {
        return !done;
    }

    @Override
    public void start() {
        ticks = 0;
        mob.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
    }

    @Override
    public void tick() {
        ticks++;
        if (mob.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D)
                <= ARRIVED_WITHIN * ARRIVED_WITHIN) {
            finish();
            return;
        }
        // Idle navigation means the path ended or was never found. Re-issue
        // once in case the destination has only just loaded, then give up on
        // the timer rather than re-pathing for ever at a place it cannot reach.
        if (mob.getNavigation().isDone()) {
            if (ticks < GIVE_UP_AFTER) {
                mob.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
            } else {
                finish();
            }
        } else if (ticks >= GIVE_UP_AFTER) {
            finish();
        }
    }

    /**
     * Arrived, or given up — both hand the mob back the same way.
     *
     * <p>Deliberately not two outcomes. A guard that could not quite reach the
     * spot should still guard where it got to, rather than being left with no
     * behaviour at all because its walk timed out a block short.</p>
     */
    private void finish() {
        done = true;
        mob.getNavigation().stop();
        if (onArrive != null) onArrive.run();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
