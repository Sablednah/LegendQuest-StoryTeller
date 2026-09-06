package com.sablednah.storyteller.neoforge;

import java.util.List;

import com.sablednah.legendquest.neoforge.Parties;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Storyteller's own voice — narration that is not coming from a
 * possessed mob, for the omniscient-narrator lines a scene needs alongside
 * whatever the cast is saying.
 *
 * <p>All three deliveries are plain vanilla packets — chat, a title card, a
 * private system message — so a vanilla client renders every one of them
 * with no help.</p>
 */
public final class Narration {

    private static Component styled(String text) {
        return Feedback.colored("&d&o" + text);
    }

    public static void toServer(ServerPlayer narrator, String text) {
        Component line = styled(text);
        for (ServerPlayer p : narrator.level().getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
    }

    /** @return how many players were within radius to hear it. */
    public static int toRadius(ServerPlayer narrator, double radius, String text) {
        Component line = styled(text);
        int heard = 0;
        for (ServerPlayer p : narrator.level().getServer().getPlayerList().getPlayers()) {
            if (p.level() != narrator.level()) continue;
            if (p.distanceToSqr(narrator) > radius * radius) continue;
            p.sendSystemMessage(line);
            heard++;
        }
        return heard;
    }

    /** To one player's whole party — mirrors {@link Rewards#party} and {@link
     *  Effects#party} so "party" targeting means the same thing everywhere in
     *  this mod. @return how many received it. */
    public static int toParty(ServerPlayer anyMember, String text) {
        Component line = styled(text);
        var party = Parties.get(anyMember.level().getServer()).partyOf(anyMember.getUUID());
        List<java.util.UUID> members = party.map(p -> p.members()).orElse(List.of(anyMember.getUUID()));
        int sent = 0;
        for (var id : members) {
            ServerPlayer p = anyMember.level().getServer().getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.sendSystemMessage(line);
            sent++;
        }
        return sent;
    }

    public static void whisper(ServerPlayer to, String text) {
        to.sendSystemMessage(Feedback.colored("&5&o(a voice) &r&7" + text));
    }

    /** A title card, server-wide — for the one-line dramatic announcement a
     *  chat message would get lost among a dozen others. */
    public static void title(ServerPlayer narrator, String text) {
        Component styled = styled(text);
        for (ServerPlayer p : narrator.level().getServer().getPlayerList().getPlayers()) {
            p.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundSetTitlesAnimationPacket(5, 60, 15));
            p.connection.send(new net.minecraft.network.protocol.game
                    .ClientboundSetTitleTextPacket(styled));
        }
    }

    private Narration() {}
}
