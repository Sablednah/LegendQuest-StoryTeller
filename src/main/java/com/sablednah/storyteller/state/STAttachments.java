package com.sablednah.storyteller.state;

import java.util.function.Supplier;

import com.sablednah.storyteller.StoryTeller;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Attachment registration, following LegendQuest's own pattern.
 *
 * <p>One attachment so far: the drift {@link Anchor}. It is deliberately
 * <b>not</b> {@code copyOnDeath} — dying while drifting is not a thing that
 * can happen (spectators take no damage), and copying the anchor across a
 * respawn would only matter in a case that means something has already gone
 * wrong.</p>
 */
public final class STAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, StoryTeller.MODID);

    public static final Supplier<AttachmentType<Anchor>> ANCHOR =
            ATTACHMENTS.register("anchor", () -> AttachmentType
                    .builder(Anchor::empty)
                    .serialize(Anchor.MAP_CODEC)
                    .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private STAttachments() {}
}
