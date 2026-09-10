package com.sablednah.storyteller.client;

import com.sablednah.storyteller.StoryTeller;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entrypoint — StoryTeller's whole client half.
 *
 * <p>{@code dist = Dist.CLIENT} means this class is never constructed on a
 * dedicated server, so naming client types from here is safe without
 * {@code @OnlyIn} or {@code DistExecutor} anywhere. Same pattern as
 * LegendQuest's own client entrypoint.</p>
 *
 * <p><b>The mod stays server-side by design; this adds convenience, never
 * capability.</b> Everything the keys do can be typed, and everything the table
 * perceives still arrives through things a vanilla client understands. A
 * Storyteller who installs nothing loses four keys and keeps the whole mod —
 * which is the promise this project will not trade away.</p>
 *
 * <p><b>Two small files, and deliberately.</b> 26.x reworked GUI rendering
 * wholesale, so everything that names a client type belongs where a version
 * drop can find it in one place rather than hunting it through the server code.
 * Nothing here draws yet, and when something does, it goes in one class beside
 * these rather than being spread.</p>
 */
@Mod(value = StoryTeller.MODID, dist = Dist.CLIENT)
public class STClient {

    public STClient(ModContainer container, IEventBus modEventBus) {
        modEventBus.addListener(STKeyMappings::register);
        NeoForge.EVENT_BUS.addListener(
                (ClientTickEvent.Post event) -> STKeyMappings.onClientTick());
        // Per-connection state, so the "your keys are unbound" notice belongs to
        // the server that offered the tools rather than to the session.
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> STKeyMappings.onDisconnect());
    }
}
