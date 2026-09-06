package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.daddychurchill.CityWorld.Clipboard.Clipboard;
import me.daddychurchill.CityWorld.Clipboard.SchematicLibrary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * CityWorld's schematic library as a second pool of set dressing —
 * {@code .schematic} (legacy MCEdit), {@code .schem} (WorldEdit),
 * {@code .litematic} (Litematica) and {@code .nbt}, whatever a server has
 * dropped in {@code config/cityworld/schematics/}.
 *
 * <p><b>This is the only class in StoryTeller that imports
 * {@code me.daddychurchill.CityWorld}</b>, and the {@code ModList.isLoaded}
 * guard sits outside it in {@link Structures}/{@link STCommands} — naming the
 * class is what loads it, so an unguarded call here would be a
 * {@code NoClassDefFoundError} on every server without CityWorld. The same
 * discipline {@link EconomySupport} keeps for Standards, for the same
 * reason.</p>
 *
 * <p>CityWorld itself has no undo for what it places — Sable's own words on
 * it. This class supplies one anyway, on the same terms {@link Structures}
 * does for vanilla templates: snapshot the footprint, hand placement to
 * CityWorld's own {@code Clipboard.paste}, log the snapshot for {@code /st
 * undo}. One undo mechanism serves both origins.</p>
 */
final class CityWorldSupport {

    static List<String> names() {
        return SchematicLibrary.names();
    }

    /** One line of {@code /st struct library list <family>}, family names as
     *  CityWorld itself spells them (ROUNDABOUT, PARK, HIGHRISE, ...). */
    static List<String> namesInFamily(String family) {
        try {
            var kind = me.daddychurchill.CityWorld.Clipboard.PasteProvider.SchematicFamily
                    .valueOf(family.toUpperCase(java.util.Locale.ROOT));
            return SchematicLibrary.names(kind);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    record Placed(int blocksChanged, String family) {}

    /**
     * Snapshot the footprint, then let CityWorld place it. Mirrors {@link
     * Structures#place} exactly: {@code Clipboard.paste} is
     * {@code StructureTemplate.placeInWorld} underneath, with a fixed,
     * predictable origin — {@code (x, groundY - groundLevelY, z)} — and no
     * rotation, so the same before/after block-state snapshot works
     * unmodified.
     */
    static Optional<Placed> place(ServerPlayer caster, String name) {
        Clipboard clip = SchematicLibrary.get(name);
        if (clip == null) return Optional.empty();

        ServerLevel level = (ServerLevel) caster.level();
        BlockPos feet = caster.blockPosition();
        BlockPos origin = new BlockPos(feet.getX(), feet.getY() - clip.groundLevelY, feet.getZ());

        List<Structures.ExternalSnap> before = new ArrayList<>();
        BlockPos.betweenClosed(origin,
                        origin.offset(clip.sizeX - 1, clip.sizeY - 1, clip.sizeZ - 1))
                .forEach(pos -> before.add(new Structures.ExternalSnap(pos.immutable(), level.getBlockState(pos))));

        clip.paste(level, feet.getX(), feet.getY(), feet.getZ(), level.getRandom());
        Structures.recordExternalPlacement(caster, "cityworld:" + clip.name, before);
        return Optional.of(new Placed(before.size(), clip.family.name()));
    }

    private CityWorldSupport() {}
}
