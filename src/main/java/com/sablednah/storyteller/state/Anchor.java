package com.sablednah.storyteller.state;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * Where a Storyteller left their body before drifting.
 *
 * <p>Persisted rather than held in memory, and that is the whole point of the
 * class existing. Drifting puts someone in spectator; the anchor is the only
 * record of what they were before. Keep it in a map and a server crash
 * mid-scene turns into a Storyteller who logs back in as a spectator with
 * nowhere to return to — a state they cannot leave without an operator.
 * A stored anchor survives the crash, so the recovery is just
 * {@code /st return}.</p>
 *
 * <p>{@code empty()} is the not-drifting state, so the attachment always has a
 * value and callers never deal with a null. It is distinguishable from a real
 * anchor because no live dimension is stored.</p>
 */
public record Anchor(Optional<ResourceKey<Level>> dimension,
        double x, double y, double z, float yRot, float xRot, GameType mode) {

    private static final Codec<GameType> GAME_TYPE = Codec.STRING.xmap(
            name -> {
                // A mode we no longer recognise must not strand anyone: fall
                // back to survival rather than throwing the attachment away.
                GameType parsed = GameType.byName(name.toLowerCase(java.util.Locale.ROOT), GameType.SURVIVAL);
                return parsed == null ? GameType.SURVIVAL : parsed;
            },
            mode -> mode.getName());

    public static final MapCodec<Anchor> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ResourceKey.codec(net.minecraft.core.registries.Registries.DIMENSION)
                    .optionalFieldOf("dimension").forGetter(Anchor::dimension),
            Codec.DOUBLE.optionalFieldOf("x", 0.0D).forGetter(Anchor::x),
            Codec.DOUBLE.optionalFieldOf("y", 0.0D).forGetter(Anchor::y),
            Codec.DOUBLE.optionalFieldOf("z", 0.0D).forGetter(Anchor::z),
            Codec.FLOAT.optionalFieldOf("yaw", 0.0F).forGetter(Anchor::yRot),
            Codec.FLOAT.optionalFieldOf("pitch", 0.0F).forGetter(Anchor::xRot),
            GAME_TYPE.optionalFieldOf("mode", GameType.SURVIVAL).forGetter(Anchor::mode))
            .apply(i, Anchor::new));

    /** Not drifting. */
    public static Anchor empty() {
        return new Anchor(Optional.empty(), 0, 0, 0, 0, 0, GameType.SURVIVAL);
    }

    /** True when this records a body to go back to. */
    public boolean isSet() {
        return dimension.isPresent();
    }
}
