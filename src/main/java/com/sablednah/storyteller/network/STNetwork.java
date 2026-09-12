package com.sablednah.storyteller.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * StoryTeller's network, which is one packet wide and should stay that way.
 *
 * <p>Every tool in this mod is a command, and a button or a key carries a
 * command string rather than a payload — so the commands cannot drift ahead of
 * the buttons and permissions behave identically whichever way a thing was
 * asked for. That property is worth protecting, so a packet here needs to earn
 * its place by carrying something a command genuinely cannot.</p>
 *
 * <p>{@link DrivenPayload} earns it: only the client can decide not to draw an
 * entity, and only the server knows which entity that is.</p>
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
    }

    /** Tell one player which entity they are driving, or {@code NONE} to stop. */
    public static void sendDriven(ServerPlayer player, int entityId) {
        PacketDistributor.sendToPlayer(player, new DrivenPayload(entityId));
    }

    private STNetwork() {}
}
