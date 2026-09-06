package com.sablednah.storyteller.neoforge;

import java.util.EnumSet;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A companion, not a puppet: keeps a cast member near a named player without
 * touching LOOK or TARGET, so it can still fight back, flinch from fire and
 * look around on its own — the opposite trade-off from {@link
 * PossessionGoal}, which deliberately claims everything.
 *
 * <p>The player is looked up by UUID on every check rather than held as a
 * live reference, so a follow target who logs out simply pauses the goal
 * (nothing to path toward) instead of leaving a stale reference in a mob
 * that outlives the session.</p>
 */
public class FollowPlayerGoal extends Goal {

    private static final double START_FOLLOWING_AT = 8.0D;
    private static final double STOP_FOLLOWING_AT = 2.5D;

    private final PathfinderMob mob;
    private final UUID target;

    public FollowPlayerGoal(PathfinderMob mob, UUID target) {
        this.mob = mob;
        this.target = target;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private ServerPlayer player() {
        return mob.level().getServer() == null ? null
                : mob.level().getServer().getPlayerList().getPlayer(target);
    }

    @Override
    public boolean canUse() {
        ServerPlayer player = player();
        return player != null && player.level() == mob.level()
                && mob.distanceToSqr(player) > START_FOLLOWING_AT * START_FOLLOWING_AT;
    }

    @Override
    public boolean canContinueToUse() {
        ServerPlayer player = player();
        return player != null && player.level() == mob.level()
                && mob.distanceToSqr(player) > STOP_FOLLOWING_AT * STOP_FOLLOWING_AT;
    }

    @Override
    public void tick() {
        ServerPlayer player = player();
        if (player != null) mob.getNavigation().moveTo(player, 1.0D);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
