package com.sablednah.storyteller.network;

import com.sablednah.storyteller.StoryTeller;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "Draw this building where I am looking" — the blocks of a structure, for the
 * Storyteller's client to show as a ghost.
 *
 * <p><b>Why this earns a packet</b> when every tool here is a command: a client
 * cannot read a datapack structure or a CityWorld schematic. Both live on the
 * server, and on a dedicated server the client has never seen the file. What it
 * gets is the block list and nothing that decides anything — the Storyteller's
 * client works out where the ghost stands and then sends {@link #placeCommand}
 * with a position and rotation added, the same command a vanilla Storyteller
 * could type. Permissions, undo and placement all stay with the command.</p>
 *
 * <p>Compact on purpose: one block state id and one cell index per block, both
 * varints. Block state ids are safe to send because the client's copy of the
 * block registry is the server's, synced at login — it is what chunk packets
 * already rely on. A 30,000-block building is roughly 150 KB, well inside the
 * 1 MiB clientbound payload limit; {@link #MAX_BLOCKS} keeps it there, and a
 * building over it gets the server's outline instead.</p>
 *
 * <p>A size of zero means "clear the ghost", so the server can take one away
 * without a second payload type.</p>
 *
 * @param label        what to call it in feedback, e.g. {@code minecraft:village/plains/houses/plains_small_house_1}
 * @param placeCommand the command to finish, without a leading slash or the position, e.g. {@code st struct place <id>}
 * @param sinkY        where the ghost starts vertically: 0 for a datapack structure, minus a CityWorld
 *                     building's {@code GroundLevelY} so its foundation starts buried as CityWorld buries it
 * @param rotatable    false for a whole generated structure, whose pieces carry their own final rotations:
 *                     the client turns nothing and says "as generated" rather than leaving a dead scroll wheel
 * @param cells        {@code x + sizeX * (y + sizeY * z)} for each block, template-local and unrotated
 */
public record GhostPayload(String label, String placeCommand, int sizeX, int sizeY, int sizeZ, int sinkY,
        boolean rotatable, int[] states, int[] cells) implements CustomPacketPayload {

    /** Blocks per payload before the ghost gives way to an outline. */
    public static final int MAX_BLOCKS = 150_000;

    public static final Type<GhostPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(StoryTeller.MODID, "ghost"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GhostPayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.label);
                        buf.writeUtf(p.placeCommand);
                        buf.writeVarInt(p.sizeX);
                        buf.writeVarInt(p.sizeY);
                        buf.writeVarInt(p.sizeZ);
                        buf.writeVarInt(p.sinkY);
                        buf.writeBoolean(p.rotatable);
                        buf.writeVarIntArray(p.states);
                        buf.writeVarIntArray(p.cells);
                    },
                    buf -> new GhostPayload(buf.readUtf(), buf.readUtf(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readBoolean(),
                            buf.readVarIntArray(MAX_BLOCKS), buf.readVarIntArray(MAX_BLOCKS)));

    /** Take the ghost away. */
    public static GhostPayload clear() {
        return new GhostPayload("", "", 0, 0, 0, 0, true, new int[0], new int[0]);
    }

    public boolean isClear() {
        return sizeX <= 0 || sizeY <= 0 || sizeZ <= 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
