package com.sablednah.storyteller.network;

import com.sablednah.storyteller.StoryTeller;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "You are driving this entity" — the one thing the client cannot work out for
 * itself.
 *
 * <p>StoryTeller's first packet, and deliberately its smallest. Driving needs
 * no input plumbing at all: the player moves natively and the server drags the
 * creature to them, so movement, mouse-look, sprinting and jumping are all
 * vanilla and all correct without a single byte crossing the wire. The only
 * thing the client genuinely cannot know is <b>which entity to stop drawing</b>
 * — the creature is standing exactly where the camera is, so without this it
 * fills the screen with the inside of a cow.</p>
 *
 * <p>An id of {@code -1} means "stopped driving", so release needs no second
 * payload type. The whole protocol is one int.</p>
 *
 * <p><b>A vanilla client is unaffected and still works.</b> The registrar is
 * {@code optional()}, so an unmodded client simply never receives this and
 * drives with the creature visible — degraded, not broken, which is the rule
 * the rest of this mod follows.</p>
 */
public record DrivenPayload(int entityId) implements CustomPacketPayload {

    /** Sent on release: stop hiding anything. */
    public static final int NONE = -1;

    public static final Type<DrivenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(StoryTeller.MODID, "driven"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DrivenPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeVarInt(p.entityId),
                    buf -> new DrivenPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
