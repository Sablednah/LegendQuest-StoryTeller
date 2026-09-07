package com.sablednah.storyteller.neoforge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.legendquest.data.CharClass;
import com.sablednah.legendquest.data.Race;
import com.sablednah.storyteller.scene.SceneAction;
import com.sablednah.storyteller.scene.SceneLog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The spawn palette — plain mobs, and a first pass at LegendQuest-flavoured
 * townsfolk.
 *
 * <p><b>A "citizen" is a named villager, not a character.</b> LegendQuest has
 * no NPC entity of its own — races and classes are a system for player
 * characters — so {@link #citizen} borrows a vanilla Villager as the body and
 * gives it a race-and-class name drawn the way the game was always meant to
 * draw one: weighted by each race/class's own {@code frequency}, a field
 * LegendQuest has parsed since day one and nothing had consumed until now. It
 * is flavour, not a character sheet — no stats, no skills, no inventory rules
 * — and that boundary is worth stating plainly rather than overclaiming a
 * cast member that can be fought or traded with as if it were a real
 * character.</p>
 */
public final class Cast {

    // --- plain spawn ---

    private record Spawned(UUID id) implements SceneAction {
        @Override
        public boolean undo(ServerLevel level) {
            Entity e = level.getEntity(id);
            if (e == null) return false;
            e.discard();
            return true;
        }

        @Override
        public String describe() {
            return "a cast member";
        }
    }

    /** Three blocks ahead of the caster, at eye height — the same spot a
     *  Storyteller would reach for with a vanilla {@code /summon ^ ^ ^3}. */
    private static BlockPos spawnPoint(ServerPlayer caster) {
        var pos = caster.position().add(caster.getLookAngle().scale(3.0D));
        return BlockPos.containing(pos.x, caster.getEyeY(), pos.z);
    }

    /** @return the spawned entity, or empty if this entity type refused to create one. */
    public static Optional<Mob> spawn(ServerPlayer caster, Holder<EntityType<?>> type, Optional<String> name) {
        ServerLevel level = (ServerLevel) caster.level();
        Entity spawned = type.value().create(level, EntitySpawnReason.COMMAND);
        if (!(spawned instanceof Mob mob)) return Optional.empty();

        BlockPos at = spawnPoint(caster);
        mob.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, caster.getYRot() + 180.0F, 0.0F);
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(at), EntitySpawnReason.COMMAND, null);
        name.ifPresent(n -> {
            mob.setCustomName(Component.literal(n));
            mob.setCustomNameVisible(true);
        });
        level.addFreshEntity(mob);
        SceneLog.record(caster, new Spawned(mob.getUUID()));
        return Optional.of(mob);
    }

    // --- weighted citizen population ---

    /**
     * Pick one entry from a datapack registry, weighted by {@code frequency},
     * skipping anything marked {@code isDefault} — "Undecided"/"Citizen" are
     * placeholders for a player who has not chosen yet, not something a
     * random townsfolk would ever BE.
     */
    private static <T> Optional<Holder.Reference<T>> weightedPick(ServerLevel level,
            ResourceKey<net.minecraft.core.Registry<T>> registryKey,
            java.util.function.ToIntFunction<T> frequency,
            java.util.function.Predicate<T> isDefault, net.minecraft.util.RandomSource random) {
        var entries = level.registryAccess().lookupOrThrow(registryKey).listElements()
                .filter(ref -> !isDefault.test(ref.value()))
                .toList();
        int total = entries.stream().mapToInt(ref -> Math.max(0, frequency.applyAsInt(ref.value()))).sum();
        if (total <= 0) return entries.stream().findFirst();
        int roll = random.nextInt(total);
        for (var ref : entries) {
            roll -= Math.max(0, frequency.applyAsInt(ref.value()));
            if (roll < 0) return Optional.of(ref);
        }
        return entries.stream().findFirst();
    }

    public record CitizenResult(Mob mob, String raceName, String className) {}

    /** A named villager, its name drawn by weighted race/class roll. Either
     *  can be pinned instead of rolled, for a Storyteller casting a SPECIFIC
     *  kind of townsfolk rather than a random one. */
    public static Optional<CitizenResult> citizen(ServerPlayer caster,
            Optional<net.minecraft.resources.Identifier> raceId,
            Optional<net.minecraft.resources.Identifier> classId) {
        ServerLevel level = (ServerLevel) caster.level();
        var random = level.getRandom();

        Optional<Holder.Reference<Race>> race = raceId
                .flatMap(id -> level.registryAccess().lookupOrThrow(com.sablednah.legendquest.LQRegistries.RACE)
                        .get(ResourceKey.create(com.sablednah.legendquest.LQRegistries.RACE, id)))
                .or(() -> weightedPick(level, com.sablednah.legendquest.LQRegistries.RACE,
                        Race::frequency, Race::isDefault, random));
        Optional<Holder.Reference<CharClass>> charClass = classId
                .flatMap(id -> level.registryAccess().lookupOrThrow(com.sablednah.legendquest.LQRegistries.CHAR_CLASS)
                        .get(ResourceKey.create(com.sablednah.legendquest.LQRegistries.CHAR_CLASS, id)))
                .or(() -> weightedPick(level, com.sablednah.legendquest.LQRegistries.CHAR_CLASS,
                        CharClass::frequency, CharClass::isDefault, random));

        if (race.isEmpty() || charClass.isEmpty()) return Optional.empty();
        String raceName = race.get().value().name();
        String className = charClass.get().value().name();
        String fullName = raceName + " " + className;

        Entity spawned = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        if (!(spawned instanceof Villager villager)) return Optional.empty();
        BlockPos at = spawnPoint(caster);
        villager.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, caster.getYRot() + 180.0F, 0.0F);
        EventHooks.finalizeMobSpawn(villager, level, level.getCurrentDifficultyAt(at), EntitySpawnReason.COMMAND, null);
        villager.setCustomName(Component.literal(fullName));
        villager.setCustomNameVisible(true);
        level.addFreshEntity(villager);
        SceneLog.record(caster, new Spawned(villager.getUUID()));
        return Optional.of(new CitizenResult(villager, raceName, className));
    }

    // --- behaviour presets ---

    public enum Behaviour { GUARD, PATROL, FOLLOW, FLEE, NONE }

    private static final double GUARD_RADIUS = 6.0D;
    private static final double PATROL_RADIUS = 24.0D;

    /** What a behaviour needs the target to be. Guard/patrol/flee only need a
     *  navigating mob; follow additionally needs a living player to follow. */
    public enum BehaviourRefusal { NONE, NOT_A_PATHFINDER }

    /**
     * Is this creature steered by a Brain rather than by goals?
     *
     * <p>Vanilla's own test, so it cannot drift as more mobs are converted:
     * {@code isBrainDead()} is true when a Brain has no memories, sensors or
     * behaviours, which is the state every goal-driven mob's inherited Brain is
     * in. Twenty classes fail it in 21.11 — Villager, Piglin, Warden and the
     * rest — and the list grows every few versions, which is exactly why this
     * asks the mob instead of consulting a list.</p>
     *
     * <p>Deliberately not Cast's {@code isBrainDriven}: this has to answer for
     * wild creatures on servers with no Cast at all.</p>
     */
    public static boolean brainDriven(Mob mob) {
        return !mob.getBrain().isBrainDead();
    }

    /**
     * Apply a preset behaviour to a mob, replacing any this mod applied
     * before it — re-issuing {@code /st cast behave} switches a cast member's
     * role rather than layering a second goal underneath the first.
     */
    public static BehaviourRefusal behave(Mob mob, Behaviour behaviour, ServerPlayer follow) {
        if (!(mob instanceof PathfinderMob pathfinder)) return BehaviourRefusal.NOT_A_PATHFINDER;
        // NOT refused, though an earlier version of this refused it.
        //
        // A brain-driven mob still ticks its goalSelector and targetSelector,
        // so a goal added here does run -- it just competes with a Brain that
        // is issuing movement of its own, and who wins depends on how busy that
        // Brain is. Tested live: a Villager ignores GUARD entirely, but goats
        // and frogs flee well enough to read as fleeing, and a camel does not
        // care. Refusing all of them would have taken away something that
        // demonstrably works on some of them.
        //
        // So it is applied and the caller warns instead. The defect was never
        // that this ran; it was that it claimed to have worked when it had not.

        mob.goalSelector.getAvailableGoals().stream()
                .filter(w -> w.getGoal() instanceof AnchoredWanderGoal || w.getGoal() instanceof FollowPlayerGoal)
                .toList()
                .forEach(w -> mob.goalSelector.removeGoal(w.getGoal()));
        mob.targetSelector.getAvailableGoals().stream()
                .filter(w -> w.getGoal() instanceof net.minecraft.world.entity.ai.goal.AvoidEntityGoal)
                .toList()
                .forEach(w -> mob.targetSelector.removeGoal(w.getGoal()));

        // A middling priority: low enough that a cast member still flinches
        // from fire or fights back if attacked (those goals typically sit
        // near 0-2), high enough to beat idle wandering (usually 6-8+).
        //
        // This only steers a mob that actually uses goalSelector. On a
        // brain-driven mob it is a no-op -- not a losing priority, a no-op:
        // Villager references goalSelector nowhere at all and ticks its Brain
        // from customServerAiStep, and goal flags arbitrate only between
        // goals. Raising the number here would change nothing. See
        // docs/ROADMAP.md for the full list of affected mobs.
        switch (behaviour) {
            case GUARD -> mob.goalSelector.addGoal(3,
                    new AnchoredWanderGoal(pathfinder, mob.blockPosition(), GUARD_RADIUS));
            case PATROL -> mob.goalSelector.addGoal(3,
                    new AnchoredWanderGoal(pathfinder, mob.blockPosition(), PATROL_RADIUS));
            case FOLLOW -> {
                if (follow != null) mob.goalSelector.addGoal(3, new FollowPlayerGoal(pathfinder, follow.getUUID()));
            }
            case FLEE -> mob.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.AvoidEntityGoal<>(
                    pathfinder, net.minecraft.world.entity.player.Player.class, 10.0F, 1.2D, 1.4D));
            case NONE -> { /* goals already cleared above */ }
        }
        return BehaviourRefusal.NONE;
    }

    // --- saved presets ---

    /** What behaviour a mob is currently running, for {@code /st cast save} to
     *  capture — the inverse of {@link #behave}. */
    private static Optional<Behaviour> currentBehaviour(Mob mob) {
        boolean guard = false, follow = false;
        double radius = -1;
        for (var w : mob.goalSelector.getAvailableGoals()) {
            if (w.getGoal() instanceof AnchoredWanderGoal wander) radius = wander.radius();
            if (w.getGoal() instanceof FollowPlayerGoal) follow = true;
        }
        boolean flee = mob.targetSelector.getAvailableGoals().stream()
                .anyMatch(w -> w.getGoal() instanceof net.minecraft.world.entity.ai.goal.AvoidEntityGoal);
        if (follow) return Optional.of(Behaviour.FOLLOW);
        if (flee) return Optional.of(Behaviour.FLEE);
        if (radius >= 0) return Optional.of(radius <= GUARD_RADIUS ? Behaviour.GUARD : Behaviour.PATROL);
        return Optional.empty();
    }

    /** Capture the mob the Storyteller is looking at as a reusable preset. */
    public static Optional<com.sablednah.storyteller.state.CastPresets.Preset> presetOf(Mob mob) {
        var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (type == null) return Optional.empty();
        Optional<String> name = mob.hasCustomName()
                ? Optional.of(mob.getCustomName().getString()) : Optional.empty();
        Optional<String> behaviour = currentBehaviour(mob).map(Enum::name);
        return Optional.of(new com.sablednah.storyteller.state.CastPresets.Preset(type, name, behaviour));
    }

    /** Bring a saved cast member to life at the caster's position. */
    public static Optional<Mob> spawnFromPreset(ServerPlayer caster,
            com.sablednah.storyteller.state.CastPresets.Preset preset) {
        var type = preset.resolveType();
        if (type.isEmpty()) return Optional.empty();
        var holder = type.get().builtInRegistryHolder();
        var spawned = spawn(caster, holder, preset.name());
        spawned.ifPresent(mob -> preset.behaviour().ifPresent(b -> {
            try {
                behave(mob, Behaviour.valueOf(b), caster);
            } catch (IllegalArgumentException ignored) {
                // A behaviour name from a future version this build does not
                // know: the mob still spawns, just without that preset habit.
            }
        }));
        return spawned;
    }

    private Cast() {}
}
