package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.sablednah.storyteller.scene.SceneAction;
import com.sablednah.storyteller.scene.SceneLog;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Vanilla structure templates as set dressing — the same {@code .nbt}
 * mechanism vanilla itself ships villages and outposts with, reached through
 * the Storyteller's own permission instead of operator level 2 the way
 * {@code /place template} needs, and with an undo vanilla's own command has
 * never had.
 *
 * <p>Every {@code .nbt} any loaded datapack declares under
 * {@code data/<pack>/structure/} is placeable, discovered the same way
 * vanilla's own template command discovers them — no separate catalogue to
 * maintain, and a datapack of custom buildings works with zero code on
 * either side.</p>
 *
 * <p><b>Undo restores block STATES, not block entity contents.</b> A chest a
 * structure overwrites comes back as an empty chest of the right kind, not
 * with whatever was in it before — capturing and restoring full block-entity
 * NBT (container contents, signs, etc.) needs API that has moved between
 * Minecraft versions in ways not worth chasing for a tool meant to dress
 * mostly-empty ground. Placing over somebody's real base is exactly the case
 * this will not perfectly undo; staging an empty clearing is the case it is
 * built for.</p>
 */
public final class Structures {

    /** One block as it was immediately before a placement overwrote it. Public
     *  so {@link CityWorldSupport} can build the same kind of undo record for
     *  a schematic CityWorld placed, without this class knowing CityWorld
     *  exists. */
    public record ExternalSnap(BlockPos pos, BlockState state) {}

    /**
     * Placing sends to clients and suppresses drops. A plant or bed that stops
     * fitting as a building lands vanishes rather than scattering seeds and
     * items across the scene; vanilla's own template placement drops them.
     */
    private static final int PLACE_FLAGS =
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                    | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS;

    /**
     * Undo puts back exact states, so it must not let one restored block react
     * to a neighbour that is not restored yet. Without KNOWN_SHAPE, half a bed
     * put back beside a gap updates, finds no partner, and breaks, and
     * SUPPRESS_DROPS alone would only hide that. Skipping block-entity side
     * effects stops the structure's own chests spilling their loot as they go.
     */
    private static final int RESTORE_FLAGS =
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                    | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE
                    | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS
                    | net.minecraft.world.level.block.Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /**
     * The blocks a placement could disturb: its box plus one block all round, so
     * a flower or torch on the edge that the landing building knocked off comes
     * back on undo. Public so {@link CityWorldSupport} snapshots the same way.
     */
    public static List<ExternalSnap> snapshot(ServerLevel level, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        List<ExternalSnap> before = new ArrayList<>();
        BlockPos.betweenClosed(
                        new BlockPos(box.minX() - 1, box.minY() - 1, box.minZ() - 1),
                        new BlockPos(box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1))
                .forEach(pos -> before.add(new ExternalSnap(pos.immutable(), level.getBlockState(pos))));
        return before;
    }

    /** A placed structure, and everything needed to take it back off. */
    private record Placement(String label, List<ExternalSnap> before) implements SceneAction {
        @Override
        public boolean undo(ServerLevel level) {
            for (ExternalSnap snap : before) {
                level.setBlock(snap.pos(), snap.state(), RESTORE_FLAGS);
            }
            return !before.isEmpty();
        }

        @Override
        public String describe() {
            return "a structure (" + label + ")";
        }
    }

    /**
     * Log an undo record for a placement this class did not make itself — the
     * CityWorld hook uses this so the same {@link SceneLog} and the same
     * restore logic serve both origins, without {@link CityWorldSupport}
     * needing to know how a {@link SceneAction} is implemented.
     */
    static void recordExternalPlacement(ServerPlayer caster, String label, List<ExternalSnap> before) {
        SceneLog.record(caster, new Placement(label, before));
    }

    /**
     * One box as it was before a placement, as a palette and one index per
     * block rather than an object per block.
     *
     * <p><b>Scale is the whole reason this exists beside {@link ExternalSnap}.</b>
     * A village's pieces cover a few hundred thousand blocks between them, and a
     * record holding a {@link BlockPos} and a state costs some forty times what
     * an index into a palette does. A single building never needed this; a
     * village cannot do without it.</p>
     *
     * <p>Cells run x fastest, then y, then z — {@link #snapshotVolume} writes
     * them in that order and {@code VolumePlacement.undo} reads them back in it.
     * The two loops are the same loop and have to stay that way.</p>
     */
    public record VolumeSnap(net.minecraft.world.level.levelgen.structure.BoundingBox box,
            BlockState[] palette, int[] cells) {

        /** Blocks in this box — what a snapshot costs, before taking it. */
        public static int volume(net.minecraft.world.level.levelgen.structure.BoundingBox box) {
            return box.getXSpan() * box.getYSpan() * box.getZSpan();
        }
    }

    /** Every block in {@code box} as it stands right now. See {@link VolumeSnap}. */
    public static VolumeSnap snapshotVolume(ServerLevel level,
            net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        List<BlockState> palette = new ArrayList<>();
        java.util.Map<BlockState, Integer> seen = new java.util.HashMap<>();
        int[] cells = new int[VolumeSnap.volume(box)];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    cells[i++] = seen.computeIfAbsent(state, fresh -> {
                        palette.add(fresh);
                        return palette.size() - 1;
                    });
                }
            }
        }
        return new VolumeSnap(box, palette.toArray(BlockState[]::new), cells);
    }

    /**
     * A whole generated structure placed, and what stood where it landed.
     *
     * <p><b>It also remembers what arrived with it.</b> A monument brings
     * guardians and a fortress's own spawners fill; vanilla's {@code /place
     * structure} leaves every one of them behind when you clear the blocks by
     * hand. Anything that was not in the footprint before the placement and is
     * there after it goes when the placement goes — which is the difference
     * between "the village is gone" and "the village is gone and forty
     * villagers are standing in a field".</p>
     */
    private record VolumePlacement(String label, List<VolumeSnap> before,
            List<java.util.UUID> arrivals) implements SceneAction {
        @Override
        public boolean undo(ServerLevel level) {
            for (java.util.UUID id : arrivals) {
                net.minecraft.world.entity.Entity entity = level.getEntity(id);
                if (entity != null) entity.discard();
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (VolumeSnap snap : before) {
                var box = snap.box();
                int i = 0;
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        for (int x = box.minX(); x <= box.maxX(); x++) {
                            level.setBlock(pos.set(x, y, z), snap.palette()[snap.cells()[i++]], RESTORE_FLAGS);
                        }
                    }
                }
            }
            return !before.isEmpty() || !arrivals.isEmpty();
        }

        @Override
        public String describe() {
            return "a structure (" + label + ")";
        }
    }

    /** See {@link VolumePlacement}. Used by {@link WholeStructures}, which does its own placing. */
    static void recordWholePlacement(ServerPlayer caster, String label, List<VolumeSnap> before,
            List<java.util.UUID> arrivals) {
        SceneLog.record(caster, new VolumePlacement(label, before, arrivals));
    }

    /** Why a placement did not happen. */
    public enum Refusal { NONE, UNKNOWN_TEMPLATE, EMPTY_TEMPLATE }

    public record Result(Refusal refusal, int blocksChanged) {
        public boolean ok() { return refusal == Refusal.NONE; }
    }

    /**
     * Place a template at the caster's feet — the same corner convention
     * vanilla's own {@code /place template ~ ~ ~} uses, so a Storyteller who
     * already knows that command already knows where this lands one.
     */
    public static Result place(ServerPlayer caster, Identifier templateId, Rotation rotation, Mirror mirror) {
        return placeAt(caster, templateId, caster.blockPosition(), rotation, mirror, false, false);
    }

    /**
     * Place a template with its origin at {@code origin} — what
     * {@code /st struct place <template> at <pos>} types, and what a ghost
     * finally sends. Rotation turns about the origin, as vanilla's
     * {@code /place template} does.
     *
     * <p><b>Air is left out unless {@code withAir}.</b> A structure-block export
     * records the air in its box, and placing that air carves the whole box out
     * of whatever the building lands in, so a house set into a hillside arrived
     * in a cube-shaped hole. Sable asked for no air by default, with air as a
     * flag for the builds that carry their own interior.</p>
     *
     * <p><b>Jigsaw blocks become their final state unless {@code withJigsaw}.</b>
     * Village houses, bastion rooms and outpost parts are single pieces of
     * structures world generation assembles, and a jigsaw block is a connector:
     * "attach a piece from this pool here". Generation swaps each one for its
     * {@code final_state} once the pieces are joined; a lone piece placed on its
     * own has nobody to do that, so Sable found raw jigsaw blocks in every
     * village house. This is vanilla's own swap, {@code JigsawReplacementProcessor}.</p>
     */
    public static Result placeAt(ServerPlayer caster, Identifier templateId, BlockPos origin,
            Rotation rotation, Mirror mirror, boolean withAir, boolean withJigsaw) {
        ServerLevel level = (ServerLevel) caster.level();
        Optional<StructureTemplate> template = template(level, templateId);
        if (template.isEmpty()) return new Result(Refusal.UNKNOWN_TEMPLATE, 0);

        StructureTemplate structure = template.get();
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation).setMirror(mirror)
                // Entities baked into the .nbt (a village's villagers, a
                // spawner's would-be occupants) are deliberately not placed:
                // this keeps a placement's footprint exactly the block volume
                // snapshotted below, with nothing loose to lose track of. Cast
                // an inhabitant on purpose with /st cast instead.
                .setIgnoreEntities(true);
        // Jigsaws first: one whose final state is air then goes out with the rest of the air.
        if (!withJigsaw) {
            settings.addProcessor(
                    net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor.INSTANCE);
        }
        if (!withAir) {
            settings.addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor.AIR);
        }
        var box = structure.getBoundingBox(settings, origin);
        if (box.getXSpan() < 1 || box.getYSpan() < 1 || box.getZSpan() < 1) {
            return new Result(Refusal.EMPTY_TEMPLATE, 0);
        }

        List<ExternalSnap> before = snapshot(level, box);

        boolean placed = structure.placeInWorld(level, origin, origin, settings, level.getRandom(), PLACE_FLAGS);
        if (!placed) return new Result(Refusal.EMPTY_TEMPLATE, 0);

        SceneLog.record(caster, new Placement(templateId.toString(), before));
        return new Result(Refusal.NONE, before.size());
    }

    /** A template by id, or empty for an unknown or malformed one. */
    public static Optional<StructureTemplate> template(ServerLevel level, Identifier templateId) {
        try {
            return level.getStructureManager().get(templateId);
        } catch (net.minecraft.IdentifierException e) {
            return Optional.empty();
        }
    }

    /**
     * A template's blocks as block state ids and template-local cells
     * ({@code x + sizeX * (y + sizeY * z)}), air left out — what a ghost is
     * drawn from.
     *
     * <p>Read through {@link StructureTemplate#save}, the one public view of a
     * template's blocks on both 1.21.11 and 26.x. A template with several
     * palettes (shipwrecks, some ruins) chooses one at random each time it is
     * placed; the preview shows the first, so the ghost of one of those is the
     * right shape and possibly the wrong planks.</p>
     */
    public static Preview preview(StructureTemplate structure, boolean withJigsaw) {
        return preview(structure.save(new net.minecraft.nbt.CompoundTag()), structure.getSize(), withJigsaw);
    }

    /**
     * The same, from a template already saved to a tag — which is how CityWorld
     * hands a library building over ({@code Clipboard.saveTemplate()}).
     */
    public static Preview preview(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.Vec3i size) {
        return preview(tag, size, true);
    }

    /**
     * @param withJigsaw false to show each jigsaw block as the final state it will
     *                   be placed as, exactly as {@link #placeAt} will place it
     */
    public static Preview preview(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.Vec3i size, boolean withJigsaw) {
        net.minecraft.nbt.ListTag palette = tag.getList("palette")
                .orElseGet(() -> tag.getListOrEmpty("palettes").getListOrEmpty(0));
        int[] ids = new int[palette.size()];
        boolean[] jigsaw = new boolean[palette.size()];
        for (int i = 0; i < ids.length; i++) {
            BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i));
            ids[i] = state.isAir() ? -1 : net.minecraft.world.level.block.Block.getId(state);
            jigsaw[i] = state.is(net.minecraft.world.level.block.Blocks.JIGSAW);
        }

        net.minecraft.nbt.ListTag blocks = tag.getListOrEmpty("blocks");
        var states = new it.unimi.dsi.fastutil.ints.IntArrayList(blocks.size());
        var cells = new it.unimi.dsi.fastutil.ints.IntArrayList(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            net.minecraft.nbt.CompoundTag block = blocks.getCompoundOrEmpty(i);
            int index = block.getIntOr("state", -1);
            if (index < 0 || index >= ids.length) continue;
            int id = !withJigsaw && jigsaw[index] ? jigsawFinalState(block) : ids[index];
            if (id < 0) continue;
            net.minecraft.nbt.ListTag pos = block.getListOrEmpty("pos");
            int x = pos.getIntOr(0, 0), y = pos.getIntOr(1, 0), z = pos.getIntOr(2, 0);
            states.add(id);
            cells.add(x + size.getX() * (y + size.getY() * z));
        }
        return new Preview(states.toIntArray(), cells.toIntArray());
    }

    /**
     * The block a jigsaw block is placed as — the reading
     * {@code JigsawReplacementProcessor} does, from the block's own
     * {@code final_state} — or -1 for nothing drawn (air, structure void, or a
     * state that does not parse, which the processor also leaves out).
     */
    private static int jigsawFinalState(net.minecraft.nbt.CompoundTag block) {
        String text = block.getCompoundOrEmpty("nbt").getStringOr("final_state", "minecraft:air");
        try {
            BlockState state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK, text, true).blockState();
            return state.isAir() || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID)
                    ? -1 : net.minecraft.world.level.block.Block.getId(state);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException unparseable) {
            return -1;
        }
    }

    /** See {@link #preview}. */
    public record Preview(int[] states, int[] cells) {}

    /** Every template any loaded datapack declares, for {@code /st struct list}
     *  and tab-completion — the exact set {@code /place template} offers. */
    public static List<String> list(ServerPlayer caster) {
        StructureTemplateManager manager = ((ServerLevel) caster.level()).getStructureManager();
        return manager.listTemplates().map(Identifier::toString).sorted().collect(Collectors.toList());
    }

    private Structures() {}
}
