package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.legendquest.neoforge.Parties;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Bringing the table to the scene.
 *
 * <p><b>Why this exists.</b> A Storyteller prepares somewhere — dresses a set,
 * places a cast, hides a thing behind a door — and then has to get four people
 * to it. The alternatives are all bad: reading out coordinates, walking them
 * over, or `/tp`, which needs operator and hands whoever has it the whole
 * server. This is the same capability reached through the Storyteller's own
 * permission, which is the argument every tool in this mod makes.</p>
 *
 * <p><b>It summons a PARTY, not "everyone nearby" and not a list of names.</b>
 * A party is the unit the table already organises itself into, it is
 * LegendQuest's own concept, and it has a member list that does not depend on
 * who happens to be in render distance. One player joins the party, everybody
 * arrives.</p>
 *
 * <p><b>Nobody is moved silently.</b> Being teleported without explanation is
 * disorienting and reads as a bug or a grief — so each arrival is told who did
 * it, and the Storyteller is told who could not be reached. An offline member
 * is reported rather than skipped, because "three of four arrived" is a thing
 * the person running the scene needs to know before they start talking.</p>
 */
public final class Summons {

    /** What happened to one member, for a report the Storyteller can read. */
    public record Arrival(String who, String what) {}

    /**
     * Bring the caller's party — or the party of the player they name — to the
     * caller's feet.
     *
     * <p>Spread in a small ring rather than stacked on one block: four people
     * landing inside each other shove each other apart, which looks like a
     * malfunction at exactly the moment the Storyteller wants the room to feel
     * deliberate.</p>
     *
     * @param storyteller who is summoning, and where to
     * @param anyMember   a member of the party to fetch; the Storyteller
     *                    themselves if they are in it
     */
    public static List<Arrival> summonParty(ServerPlayer storyteller, ServerPlayer anyMember) {
        var party = Parties.get(storyteller.level().getServer()).partyOf(anyMember.getUUID());
        if (party.isEmpty()) {
            // Not an error: one player IS a party of one as far as a GM cares,
            // and refusing would make the button useless for a duet.
            return List.of(bring(storyteller, anyMember, 0, 1));
        }

        List<UUIDName> members = new ArrayList<>();
        for (var id : party.get().members()) {
            ServerPlayer p = storyteller.level().getServer().getPlayerList().getPlayer(id);
            members.add(new UUIDName(id, p));
        }

        List<Arrival> arrivals = new ArrayList<>();
        int placed = 0;
        int toPlace = (int) members.stream().filter(m -> m.player() != null).count();
        for (UUIDName m : members) {
            if (m.player() == null) {
                arrivals.add(new Arrival(shortId(m), "offline"));
                continue;
            }
            arrivals.add(bring(storyteller, m.player(), placed++, Math.max(1, toPlace)));
        }
        return arrivals;
    }

    private record UUIDName(java.util.UUID id, ServerPlayer player) {}

    private static String shortId(UUIDName m) {
        return m.id().toString().substring(0, 8) + "…";
    }

    /**
     * One player, placed on the ring.
     *
     * <p>The Storyteller is not teleported to themselves — that would be a
     * pointless shove, and worse, it would move them off the spot they chose
     * to stand on to run the scene.</p>
     */
    private static Arrival bring(ServerPlayer storyteller, ServerPlayer member, int index, int total) {
        String name = member.getName().getString();
        if (member.getUUID().equals(storyteller.getUUID())) {
            return new Arrival(name, "already here");
        }

        Vec3 at = storyteller.position();
        double radius = total <= 1 ? 0.0D : 1.6D;
        double angle = total <= 1 ? 0.0D : (Math.PI * 2.0D * index) / total;
        double x = at.x + Math.sin(angle) * radius;
        double z = at.z + Math.cos(angle) * radius;

        member.teleportTo(storyteller.level(), x, at.y, z, java.util.Set.of(),
                storyteller.getYRot(), storyteller.getXRot(), false);

        // Facing the same way the Storyteller is, so the whole table is looking
        // at whatever they were looking at. A scene you have to turn round to
        // find has lost its first beat.
        Feedback.chat(member, "&d" + storyteller.getName().getString()
                + " &7brings you to the scene.");
        return new Arrival(name, "arrived");
    }

    private Summons() {}
}
