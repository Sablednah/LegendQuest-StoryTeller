package com.sablednah.storyteller.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * StoryTeller's network, which is two packets wide and should stay small.
 *
 * <p>Every tool in this mod is a command, and a button or a key carries a
 * command string rather than a payload — so the commands cannot drift ahead of
 * the buttons and permissions behave identically whichever way a thing was
 * asked for. That property is worth protecting, so a packet here needs to earn
 * its place by carrying something a command genuinely cannot.</p>
 *
 * <p>{@link DrivenPayload} earns it: only the client can decide not to draw an
 * entity, and only the server knows which entity that is.
 * {@link GhostPayload} earns it: a client cannot read a structure that lives in
 * a server's datapack, and the ghost it draws still ends in a typed command.</p>
 *
 * <p><b>Registered {@code optional()}</b>, which is what keeps a vanilla client
 * connectable. StoryTeller's whole premise is that only the server and the
 * Storyteller need it installed; a mandatory channel would refuse every player
 * at the door.</p>
 */
public final class STNetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(DrivenPayload.TYPE, DrivenPayload.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        com.sablednah.storyteller.client.DrivenView.accept(payload)));
        registrar.playToClient(GhostPayload.TYPE, GhostPayload.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        com.sablednah.storyteller.client.GhostPreview.accept(payload)));
    }

    /**
     * Whether this player's client negotiated one of our channels — and whether
     * there is a client there to ask at all.
     *
     * <p>Not just {@code NetworkRegistry.hasChannel(player.connection, ...)}: a
     * NeoForge {@code FakePlayer} has a connection whose netty channel is null,
     * and asking it throws a {@code NullPointerException}. Reported by
     * Chronicler, whose self-test died on the same question in LegendQuest.</p>
     */
    public static boolean listening(ServerPlayer player, net.minecraft.resources.Identifier channel) {
        return player.connection != null
                && !player.isFakePlayer()
                && player.connection.getConnection().isConnected()
                && net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(player.connection, channel);
    }

    /** Tell one player which entity they are driving, or {@code NONE} to stop. */
    public static void sendDriven(ServerPlayer player, int entityId) {
        PacketDistributor.sendToPlayer(player, new DrivenPayload(entityId));
    }

    /** Hand one Storyteller's client a structure to draw, or {@link GhostPayload#clear()} to take it away. */
    public static void sendGhost(ServerPlayer player, GhostPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    private STNetwork() {}
}
