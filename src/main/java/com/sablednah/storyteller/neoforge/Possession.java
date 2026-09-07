package com.sablednah.storyteller.neoforge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

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
    /** Storyteller -> the Cast NPC they are wearing. A separate map because an
     *  NPC body is driven rather than steered: it has no goalSelector to hold
     *  a goal, and a human body has no navigation at all. */
    private static final Map<UUID, UUID> HELD_NPC = new HashMap<>();
    /** Possessors who chose the mob's eyes over control of it. Kept apart
     *  because a bound camera is the thing that costs them their input, and
     *  because vanilla lets them leave it by sneaking without telling us. */
    private static final java.util.Set<UUID> EYES = new java.util.HashSet<>();
    /** What we last drove each body to, so an unmoving scene sends no packets. */
    private static final Map<UUID, Vec3> DRIVEN_TO = new HashMap<>();
    private static final Map<UUID, Float> DRIVEN_YAW = new HashMap<>();

    /** Whether Cast is on this server at all. Every NPC path below is behind
     *  this, so that naming {@link CastSupport} never loads it on a server
     *  without Cast. */
    public static boolean castAvailable() {
        return ModList.get().isLoaded("cast");
    }

    /** Standards owns vanish, and possession hides the Storyteller while they
     *  wear a body. Guard outside {@link VanishSupport} for the usual reason. */
    public static boolean vanishAvailable() {
        return ModList.get().isLoaded("standards");
    }

    /** Hide the wearer, so the audience sees the creature and not the person
     *  walking it. Safe to call twice; the hold is keyed. */
    private static void hideWearer(ServerPlayer player) {
        if (vanishAvailable()) VanishSupport.hide(player);
    }

    /**
     * Give the wearer back to the world.
     *
     * @return true when they are actually visible again — false when they were
     *         already vanished by their own hand and still are, which is the
     *         difference worth telling them about.
     */
    private static boolean revealWearer(ServerPlayer player) {
        return vanishAvailable() && VanishSupport.reveal(player);
    }

    public static Optional<Mob> heldBy(ServerPlayer player) {
        return Optional.ofNullable(HELD.get(player.getUUID()));
    }

    public static boolean isPossessing(ServerPlayer player) {
        return HELD.containsKey(player.getUUID()) || HELD_NPC.containsKey(player.getUUID());
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
        return lookedAtHit(player, reach).map(MobHit::mob);
    }

    /** A creature in the sights, and how far away it was hit.
     *
     *  <p>The distance matters once Cast is present: an NPC found by
     *  {@code Cast.npcHitAt} and a wild creature found by this ray are two
     *  separate searches, and the Storyteller means whichever is actually
     *  nearer. Without a distance on both, possessing "what you are looking at"
     *  would silently prefer one kind over the other.</p> */
    public record MobHit(Mob mob, double distance) {}

    public static Optional<MobHit> lookedAtHit(ServerPlayer player, double reach) {
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
        return best == null
                ? Optional.empty()
                : Optional.of(new MobHit(best, Math.sqrt(bestDistance)));
    }

    /**
     * Why a possession did not happen, so the caller can say which.
     *
     * <p>There is no "that is a player" case: {@code Player} does not extend
     * {@code Mob}, so the signature already makes it unrepresentable and
     * {@link #lookedAt} cannot return one. The type system is a better guard
     * than a runtime check that can never fire.</p>
     */
    public enum Refusal { NONE, ALREADY_HELD, TAKEN, NOT_LOADED }

    /**
     * Take the mob over. The caller is expected to have put the Storyteller
     * into spectator first (drifting) — this does not do it for them, because
     * possessing and drifting are separately undoable and conflating them
     * makes "release" ambiguous.
     */
    public static Refusal possess(ServerPlayer player, Mob mob, boolean throughItsEyes) {
        if (HELD.containsKey(player.getUUID()) || HELD_NPC.containsKey(player.getUUID())) {
            return Refusal.ALREADY_HELD;
        }
        if (possessorOf(mob).isPresent()) return Refusal.TAKEN;

        HELD.put(player.getUUID(), mob);
        if (throughItsEyes) {
            // No goal at all. Holding the creature still would give a frozen
            // stare: the possessor's rotation is snapped from the mob every
            // tick, so mirroring it back is a closed loop and the view never
            // turns. Riding a creature that is living its own life is both the
            // better scene and the simpler code.
            EYES.add(player.getUUID());
            player.setCamera(mob);
        } else {
            PossessionGoal goal = new PossessionGoal(mob, player);
            mob.goalSelector.addGoal(0, goal);
            GOALS.put(player.getUUID(), goal);
        }
        hideWearer(player);
        return Refusal.NONE;
    }

    /**
     * Give the mob back to itself. Removes exactly the goal that was added, so
     * nothing the mob was born with is disturbed.
     *
     * @return the mob that was released, if any.
     */
    public static Optional<Mob> release(ServerPlayer player) {
        EYES.remove(player.getUUID());
        revealWearer(player);
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
        EYES.remove(player.getUUID());
        // Standards drops our hold on logout by itself, but releasing is
        // harmless and keeps every exit from possession identical.
        revealWearer(player);
        Mob mob = HELD.remove(player.getUUID());
        PossessionGoal goal = GOALS.remove(player.getUUID());
        if (mob != null && goal != null) {
            mob.goalSelector.removeGoal(goal);
            mob.getNavigation().stop();
        }
        UUID npc = HELD_NPC.remove(player.getUUID());
        DRIVEN_TO.remove(player.getUUID());
        DRIVEN_YAW.remove(player.getUUID());
        // Unpin even on the way out: a phantom kept visible for a player who
        // is no longer here is a viewer Cast would go on serving forever.
        if (npc != null && castAvailable()) {
            MinecraftServer server = player.level().getServer();
            if (server != null) CastSupport.setAnchored(server, npc, true);
            CastSupport.unpin(player, npc);
        }
    }

    // --- NPC bodies (Cast) -------------------------------------------------
    //
    // Everything below is reached only when castAvailable() is true. That is
    // what keeps CastSupport -- and therefore com.sablednah.cast -- unloaded on
    // a server that has no Cast.

    /**
     * Whatever the Storyteller is looking at, wild creature or cast NPC.
     *
     * <p>Two searches, one answer. A phantom NPC is in no level and is not a
     * {@code Mob}, so {@link #lookedAtHit} can never find one and Cast has to
     * be asked separately — but the Storyteller just means "that, there", so
     * whichever was hit nearer wins. Callers never learn which kind it was
     * unless they ask, and never name a Cast type to find out.</p>
     */
    public record Sighted(Mob mob, UUID npcId, String name, boolean canPossess) {
        public boolean isNpc() {
            return npcId != null;
        }
    }

    public static Optional<Sighted> lookingAt(ServerPlayer player, double reach) {
        Optional<MobHit> mob = lookedAtHit(player, reach);
        Optional<Sighted> wild = mob.map(h ->
                new Sighted(h.mob(), null, h.mob().getName().getString(), true));
        if (!castAvailable()) return wild;

        Optional<CastSupport.Target> npc = CastSupport.hitAt(player, reach);
        if (npc.isEmpty()) return asNpcIfCastOwnsIt(wild, mob);
        CastSupport.Target target = npc.get();
        // Both hit: the nearer one is the one they meant.
        if (mob.isPresent() && mob.get().distance() < target.distance()) {
            return asNpcIfCastOwnsIt(wild, mob);
        }
        return Optional.of(new Sighted(null, target.id(), target.name(), target.canPossess()));
    }

    /**
     * A Cast MOB body is a real entity in the level, so our own ray finds it
     * just as Cast's does — and the two distances are measured differently, so
     * either can come out nearer for the same creature.
     *
     * <p>Taking the wild-creature path for one of Cast's bodies would attach a
     * goal to something Cast owns and, worse, skip suspending its anchor — so
     * Cast would put the body back on its spot once a second while the
     * Storyteller walked it across the room. Whoever owns the body decides the
     * path, not whichever search happened to measure a shorter hit.</p>
     */
    private static Optional<Sighted> asNpcIfCastOwnsIt(Optional<Sighted> wild, Optional<MobHit> mob) {
        if (mob.isEmpty()) return wild;
        Optional<UUID> owned = CastSupport.npcIdOf(mob.get().mob());
        if (owned.isEmpty()) return wild;
        return Optional.of(new Sighted(null, owned.get(),
                mob.get().mob().getName().getString(), true));
    }

    public static Optional<UUID> heldNpcBy(ServerPlayer player) {
        return Optional.ofNullable(HELD_NPC.get(player.getUUID()));
    }

    public static Optional<ServerPlayer> possessorOfNpc(MinecraftServer server, UUID npcId) {
        return HELD_NPC.entrySet().stream()
                .filter(e -> e.getValue().equals(npcId))
                .findFirst()
                .flatMap(e -> Optional.ofNullable(server.getPlayerList().getPlayer(e.getKey())));
    }

    /**
     * Wear a cast NPC.
     *
     * <p>Two things have to happen in this order. The body is <b>pinned</b> to
     * the possessor's client first, because the camera packet carries only an
     * entity id and a vanilla client silently ignores an id it has never been
     * sent — an unpinned phantom means possession that reports success and
     * does nothing visible. Only then is the camera bound.</p>
     */
    public static Refusal possessNpc(ServerPlayer player, UUID npcId, boolean throughItsEyes) {
        if (HELD.containsKey(player.getUUID()) || HELD_NPC.containsKey(player.getUUID())) {
            return Refusal.ALREADY_HELD;
        }
        MinecraftServer server = player.level().getServer();
        if (possessorOfNpc(server, npcId).isPresent()) return Refusal.TAKEN;

        Optional<Entity> body = CastSupport.entityOf(server, npcId);
        if (body.isEmpty()) return Refusal.NOT_LOADED;

        HELD_NPC.put(player.getUUID(), npcId);
        // Suspended BEFORE anything moves the body, so it cannot be snapped
        // home between taking it and driving it.
        CastSupport.setAnchored(server, npcId, false);
        if (throughItsEyes) {
            EYES.add(player.getUUID());
            // Pin BEFORE binding: the camera packet carries only an entity id,
            // and a vanilla client silently ignores an id it was never sent.
            CastSupport.pin(player, npcId);
            player.setCamera(body.get());
        }
        hideWearer(player);
        return Refusal.NONE;
    }

    /** @return the name of the NPC that was released, if any. */
    public static Optional<String> releaseNpc(ServerPlayer player) {
        EYES.remove(player.getUUID());
        revealWearer(player);
        UUID npcId = HELD_NPC.remove(player.getUUID());
        DRIVEN_TO.remove(player.getUUID());
        DRIVEN_YAW.remove(player.getUUID());
        if (npcId == null) return Optional.empty();
        MinecraftServer server = player.level().getServer();
        Optional<String> name = CastSupport.nameOf(server, npcId);
        // Re-anchor where it now stands, so the scene keeps where it was left.
        CastSupport.setAnchored(server, npcId, true);
        CastSupport.unpin(player, npcId);
        player.setCamera(player);
        return Optional.of(name.orElse("the body"));
    }

    /**
     * Cast has taken a body away — hand its wearer back their own eyes.
     *
     * <p>Called from {@link CastSupport}'s event listener, which is the only
     * thing that ever hears about a phantom being removed.</p>
     */
    static void npcWentAway(UUID npcId, String wording) {
        HELD_NPC.entrySet().stream()
                .filter(e -> e.getValue().equals(npcId))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(possessor -> {
                    HELD_NPC.remove(possessor);
                    EYES.remove(possessor);
                    DRIVEN_TO.remove(possessor);
                    DRIVEN_YAW.remove(possessor);
                    // Re-anchor first, and regardless of whether the wearer
                    // is still online. For most reasons the body is gone and
                    // this is a no-op, but REBODY keeps the npcId and builds a
                    // new body under it -- that body would otherwise inherit an
                    // anchor this mod switched off and nothing switched back
                    // on, leaving it free to be shoved around forever.
                    if (SERVER != null) CastSupport.setAnchored(SERVER, npcId, true);

                    ServerPlayer player = SERVER == null ? null
                            : SERVER.getPlayerList().getPlayer(possessor);
                    if (player == null) return;
                    CastSupport.unpin(player, npcId);
                    player.setCamera(player);
                    revealWearer(player);
                    Feedback.chat(player, "&cThe body you were wearing " + wording
                            + ". &f/st return&c brings you back to your body.");
                });
    }

    /** Set once the server is up, so a removal event can find a player without
     *  a level to ask. */
    private static MinecraftServer SERVER;



    /**
     * Walk every worn NPC body to its wearer, once a tick.
     *
     * <p>The mob path does this with a goal and real pathfinding, so a
     * possessed cow is bound by its own legs. An NPC body is <b>driven</b>
     * instead — Cast teleports it — because a human body is a phantom with no
     * navigation and no physics of any kind: it moves only when told to. The
     * asymmetry is deliberate and worth knowing, since a worn human can cross
     * ground a worn animal cannot.</p>
     */
    public static void tick(MinecraftServer server) {
        SERVER = server;
        if (!HELD.isEmpty() || !HELD_NPC.isEmpty()) noticeCameraDrift(server);
        if (HELD_NPC.isEmpty()) return;
        HELD_NPC.forEach((possessorId, npcId) -> {
            ServerPlayer possessor = server.getPlayerList().getPlayer(possessorId);
            if (possessor == null) return;
            // Stop SHORT of the Storyteller, by the same margin the mob path
            // uses. Driving a body onto their exact position puts it inside
            // the camera -- reported live as "follows so well my camera is
            // inside its head" -- and makes it copy every spectator flight and
            // every step into the ground, because a phantom has no physics to
            // refuse with.
            Vec3 here = CastSupport.positionOf(server, npcId).orElse(null);
            if (here == null) return;
            Vec3 gap = possessor.position().subtract(here);
            double away = gap.length();
            if (away <= PossessionGoal.ARRIVED) return; // close enough; standing still reads better than jitter
            Vec3 to = possessor.position().subtract(gap.scale(PossessionGoal.ARRIVED / away));
            float yaw = possessor.getYRot();
            // Nothing moved: send nothing. A held tableau should not be a
            // packet every tick to everyone watching the scene.
            Vec3 last = DRIVEN_TO.get(possessorId);
            Float lastYaw = DRIVEN_YAW.get(possessorId);
            if (last != null && lastYaw != null
                    && last.distanceToSqr(to) < 1.0E-4D && Math.abs(lastYaw - yaw) < 0.05F) {
                return;
            }
            DRIVEN_TO.put(possessorId, to);
            DRIVEN_YAW.put(possessorId, yaw);
            CastSupport.drive(server, npcId, to, yaw, possessor.getXRot());
        });
    }

    /**
     * Notice when the camera has gone somewhere this mod did not put it.
     *
     * <p>Vanilla repurposes a spectator's inputs, and every one of them can
     * move the camera out from under a possession without firing anything:
     * sneaking ends a bound camera ({@code wantsToStopRiding} →
     * {@code setCamera(this)}), and <b>clicking an entity binds the camera to
     * that entity instead</b>. Reported live: "if i click on a different mob i
     * switch to that - but am still weirdly stuck to the old one ... and im
     * possessing but broken".</p>
     *
     * <p>So the rule is not "did they sneak" but "is the camera still where we
     * put it". Anything else ends the possession cleanly and says so, which is
     * the only way the Storyteller is never left speaking through a creature
     * they cannot see.</p>
     */
    private static void noticeCameraDrift(MinecraftServer server) {
        List<UUID> possessors = new java.util.ArrayList<>(HELD.keySet());
        possessors.addAll(HELD_NPC.keySet());
        for (UUID id : possessors) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;

            // Where the camera is supposed to be: inside the body when they
            // chose its eyes, and in their own head when they are steering.
            Entity expected = player;
            if (EYES.contains(id)) {
                Mob mob = HELD.get(id);
                if (mob != null) {
                    expected = mob;
                } else {
                    UUID npcId = HELD_NPC.get(id);
                    if (npcId == null) continue;
                    Optional<Entity> body = CastSupport.entityOf(server, npcId);
                    if (body.isEmpty()) continue; // being rebuilt; leave it be
                    expected = body.get();
                }
            }
            if (player.getCamera() == expected) continue;

            String name;
            Optional<UUID> npc = heldNpcBy(player);
            if (npc.isPresent()) {
                name = CastSupport.nameOf(server, npc.get()).orElse("it");
                releaseNpc(player);
            } else {
                name = heldBy(player).map(m -> m.getName().getString()).orElse("it");
                release(player);
            }
            Feedback.chat(player, "&7Your view left &f" + name + "&7, so you have let it go. "
                    + "&f/st possess&7 takes something again — add &feyes&7 to see through it.");
        }
    }

    /**
     * Say something as a worn NPC.
     *
     * @return how many players were close enough to hear it — Cast's own
     *         count, so the Storyteller is told a line landed nowhere without
     *         this mod keeping a second copy of the radius rule to drift from.
     */
    public static int speakAsNpc(ServerPlayer player, UUID npcId, String text, double radius) {
        return CastSupport.say(player.level().getServer(), npcId, text, radius);
    }

    private Possession() {}
}
