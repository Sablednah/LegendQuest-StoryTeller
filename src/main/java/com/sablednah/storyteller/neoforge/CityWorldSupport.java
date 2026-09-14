package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.sablednah.storyteller.ghost.GhostMath;

import me.daddychurchill.CityWorld.Clipboard.Clipboard;
import me.daddychurchill.CityWorld.Clipboard.SchematicLibrary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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
 *
 * <p><b>Turning a building and showing it as a ghost need CityWorld 5.8.0</b>,
 * which added a whole-building {@code paste} with a rotation and
 * {@code saveTemplate()} for exactly this. {@link #canTurnAndShow} asks the class
 * rather than a version number, and an unturned placement still uses the old
 * paste, so an older CityWorld keeps everything it could already do.</p>
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

    private static Boolean turnAndShow;

    /**
     * Whether this CityWorld can turn a whole building and hand over its blocks.
     *
     * <p>Asked of the class, not of a version: a range only says what a number
     * promises, and a number lies while the other mod is still moving. A
     * missing method found here is a polite refusal; found at the call, it is a
     * {@code NoSuchMethodError} in the middle of a scene.</p>
     */
    static boolean canTurnAndShow() {
        if (turnAndShow == null) {
            try {
                Clipboard.class.getMethod("saveTemplate");
                Clipboard.class.getMethod("paste", ServerLevelAccessor.class, int.class, int.class, int.class,
                        Rotation.class, Mirror.class, RandomSource.class);
                turnAndShow = true;
            } catch (NoSuchMethodException older) {
                turnAndShow = false;
            }
        }
        return turnAndShow;
    }

    record Placed(int blocksChanged, String family) {}

    /** A library building as a ghost needs it: its size, how deep its foundation goes, and its blocks. */
    record Ghostable(String name, Vec3i size, int groundLevelY, CompoundTag blocks) {}

    /** Needs {@link #canTurnAndShow}. */
    static Optional<Ghostable> ghostable(String name) {
        Clipboard clip = SchematicLibrary.get(name);
        if (clip == null) return Optional.empty();
        return Optional.of(new Ghostable(clip.name, new Vec3i(clip.sizeX, clip.sizeY, clip.sizeZ),
                clip.groundLevelY, clip.saveTemplate()));
    }

    /**
     * Snapshot the footprint, then let CityWorld place it.
     *
     * @param origin the building's origin in the convention
     *               {@code /st struct place ... at} uses — its unturned corner,
     *               bottom layer included — or null for "at my feet", which
     *               stands the ground layer where the caster stands, as this
     *               command always has
     * @param rotation anything but {@code NONE} needs {@link #canTurnAndShow}
     */
    static Optional<Placed> place(ServerPlayer caster, String name, BlockPos origin, Rotation rotation) {
        Clipboard clip = SchematicLibrary.get(name);
        if (clip == null) return Optional.empty();

        ServerLevel level = (ServerLevel) caster.level();
        if (origin == null) origin = caster.blockPosition().below(clip.groundLevelY);
        // The same footprint the ghost showed. CityWorld lands the turned
        // footprint's north-west corner on the coordinates it is given, which is
        // this box's minimum corner.
        BoundingBox box = GhostMath.worldBox(origin, new Vec3i(clip.sizeX, clip.sizeY, clip.sizeZ), rotation);

        // Air follows the building's own KeepAir sidecar: CityWorld strips it at
        // load unless the author asked to keep it, so there is no withair here.
        List<Structures.ExternalSnap> before = Structures.snapshot(level, box);

        int groundY = box.minY() + clip.groundLevelY;
        if (rotation == Rotation.NONE) {
            // The original paste, so an unturned building places on any CityWorld.
            clip.paste(level, box.minX(), groundY, box.minZ(), level.getRandom());
        } else {
            clip.paste(level, box.minX(), groundY, box.minZ(), rotation, Mirror.NONE, level.getRandom());
        }
        Structures.recordExternalPlacement(caster, "cityworld:" + clip.name, before);
        return Optional.of(new Placed(before.size(), clip.family.name()));
    }

    private CityWorldSupport() {}
}
