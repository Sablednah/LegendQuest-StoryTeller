package com.sablednah.storyteller.neoforge;

import java.util.Optional;
import java.util.UUID;

import com.sablednah.cast.api.Cast;
import com.sablednah.cast.api.Npc;
import com.sablednah.cast.api.NpcRemovedEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * NPCs, when a server has Cast.
 *
 * <p><b>This is the only class in StoryTeller that imports
 * {@code com.sablednah.cast}</b>, and the {@code ModList.isLoaded} guard lives
 * outside it — in {@link Possession} and {@link STCommands}. Naming a class is
 * what loads it, so an unguarded call here would be a
 * {@code NoClassDefFoundError} on every server without Cast. Same rule as
 * {@link EconomySupport} for Standards and {@link CityWorldSupport} for
 * CityWorld; this is that discipline copied deliberately.</p>
 *
 * <p>Nothing outside this class ever names a Cast type. The {@link Target}
 * record below is the translation layer: callers get an id, a name and a
 * distance in this mod's own vocabulary, which is what keeps the single-import
 * rule true rather than merely intended.</p>
 *
 * <p><b>Why possession needs Cast's help at all.</b> A human NPC is a phantom:
 * a {@code ServerPlayer} that exists only as a source of packets and was never
 * added to any level. It is therefore invisible to {@code level.getEntity} and
 * to StoryTeller's own gaze ray, which filters on {@code Mob}. Cast does the
 * looking ({@link #hitAt}) and Cast keeps the phantom on the possessor's
 * client ({@link #pin}) — without the pin, the camera packet names an entity
 * id that client has never been sent, the vanilla client silently ignores it,
 * and the Storyteller is left staring at their own body while the server
 * believes possession worked.</p>
 */
public final class CastSupport {

    /**
     * A cast NPC in StoryTeller's terms.
     *
     * @param distance how far the sightline hit was, so the caller can weigh
     *                 it against a wild creature found by its own ray and take
     *                 whichever is actually nearer.
     */
    record Target(UUID id, String name, double distance, boolean canPossess, boolean human) {}

    private static Target translate(Npc npc, double distance) {
        return new Target(npc.id(), npc.name(), distance, npc.canPossess(), npc.isHuman());
    }

    /** The NPC in the viewer's sights, with the hit distance. */
    static Optional<Target> hitAt(ServerPlayer viewer, double reach) {
        return Cast.npcHitAt(viewer, reach).map(hit -> translate(hit.npc(), hit.distance()));
    }

    /** @return the NPC by id, distance reported as 0 — it was not found by looking. */
    static Optional<Target> byId(MinecraftServer server, UUID npcId) {
        return Cast.byId(server, npcId).map(npc -> translate(npc, 0.0D));
    }

    /**
     * The entity to bind a camera to. For a human body this is the phantom
     * object itself — a real {@code ServerPlayer} instance that
     * {@code level.getEntity} will never find.
     */
    static Optional<Entity> entityOf(MinecraftServer server, UUID npcId) {
        return Cast.byId(server, npcId).flatMap(Npc::entity);
    }

    /** The mob body behind a cast NPC, if it has one — a human NPC is a
     *  phantom {@code ServerPlayer} and answers empty. */
    static Optional<net.minecraft.world.entity.Mob> bodyOf(MinecraftServer server, UUID npcId) {
        return entityOf(server, npcId)
                .filter(e -> e instanceof net.minecraft.world.entity.Mob)
                .map(e -> (net.minecraft.world.entity.Mob) e);
    }

    static Optional<Vec3> positionOf(MinecraftServer server, UUID npcId) {
        return Cast.byId(server, npcId).map(Npc::pos);
    }

    static Optional<String> nameOf(MinecraftServer server, UUID npcId) {
        return Cast.byId(server, npcId).map(Npc::name);
    }

    /** Is this entity one of Cast's own bodies, and which? Needed because a
     *  Cast MOB body is a real entity in the level, so StoryTeller's own gaze
     *  ray finds it too and would otherwise treat it as a wild creature. */
    static Optional<UUID> npcIdOf(net.minecraft.world.entity.Entity entity) {
        return Cast.npcIdOf(entity);
    }

    /**
     * Suspend or restore Cast's anchor.
     *
     * <p>Cast puts a body back on its spot once a second, which is right for a
     * villager a zombie is shoving and wrong for one a Storyteller is walking
     * across a room. Re-anchoring makes wherever it stands at that moment its
     * new spot, so releasing leaves it where the scene left it rather than
     * snapping it home.</p>
     */
    static void setAnchored(MinecraftServer server, UUID npcId, boolean anchored) {
        Cast.setAnchored(server, npcId, anchored);
    }

    /** Keep this NPC on this player's client whatever the range, for as long
     *  as they are wearing it. */
    static void pin(ServerPlayer viewer, UUID npcId) {
        Cast.pinViewer(viewer, npcId);
    }

    static void unpin(ServerPlayer viewer, UUID npcId) {
        Cast.unpinViewer(viewer, npcId);
    }

    /** Move the body. Cast picks a relative move or a snap by delta size, so
     *  this does not have to care which. */
    static void drive(MinecraftServer server, UUID npcId, Vec3 pos, float yaw, float pitch) {
        Cast.drive(server, npcId, pos, yaw, pitch);
    }

    /** @return how many players were close enough to hear it. */
    static int say(MinecraftServer server, UUID npcId, String text, double radius) {
        return Cast.say(server, npcId, text, radius);
    }

    /**
     * A body Cast has taken away is a body nobody can be wearing.
     *
     * <p>This is the whole safety net for human NPCs, and it has to be:
     * a phantom is never in a level, so {@code LivingDeathEvent} and
     * {@code EntityLeaveLevelEvent} — every hook {@link STServerEvents} uses to
     * hand a camera back — can never fire for one. Cast raises this instead, on
     * every path including server shutdown.</p>
     */
    @SubscribeEvent
    static void onNpcRemoved(NpcRemovedEvent event) {
        Possession.npcWentAway(event.npcId(), reasonWording(event.reason()));
    }

    /** Said to the Storyteller at the moment the body goes, because a view
     *  that changes on its own needs explaining as it happens. */
    private static String reasonWording(NpcRemovedEvent.Reason reason) {
        return switch (reason) {
            case DEATH -> "dies, and you are cast out of it";
            case UNLOAD -> "is no longer loaded, and you are cast out of it";
            case DIMENSION_CHANGE -> "is gone from this world, and you are cast out of it";
            case REBODY -> "was rebuilt and needs wearing again — &f/st possess&c it once more";
            case REMOVED -> "was removed, and you are cast out of it";
        };
    }

    private CastSupport() {}
}
