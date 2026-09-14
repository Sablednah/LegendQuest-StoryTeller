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

    /** A placed structure, and everything needed to take it back off. */
    private record Placement(String label, List<ExternalSnap> before) implements SceneAction {
        @Override
        public boolean undo(ServerLevel level) {
            for (ExternalSnap snap : before) {
                level.setBlock(snap.pos(), snap.state(), 2);
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
        return placeAt(caster, templateId, caster.blockPosition(), rotation, mirror);
    }

    /**
     * Place a template with its origin at {@code origin} — what
     * {@code /st struct place <template> at <pos>} types, and what a ghost
     * finally sends. Rotation turns about the origin, as vanilla's
     * {@code /place template} does.
     */
    public static Result placeAt(ServerPlayer caster, Identifier templateId, BlockPos origin,
            Rotation rotation, Mirror mirror) {
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
        var box = structure.getBoundingBox(settings, origin);
        if (box.getXSpan() < 1 || box.getYSpan() < 1 || box.getZSpan() < 1) {
            return new Result(Refusal.EMPTY_TEMPLATE, 0);
        }

        List<ExternalSnap> before = new ArrayList<>();
        BlockPos.betweenClosed(
                        new BlockPos(box.minX(), box.minY(), box.minZ()),
                        new BlockPos(box.maxX(), box.maxY(), box.maxZ()))
                .forEach(pos -> before.add(new ExternalSnap(pos.immutable(), level.getBlockState(pos))));

        boolean placed = structure.placeInWorld(level, origin, origin, settings, level.getRandom(), 2);
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
    public static Preview preview(StructureTemplate structure) {
        return preview(structure.save(new net.minecraft.nbt.CompoundTag()), structure.getSize());
    }

    /**
     * The same, from a template already saved to a tag — which is how CityWorld
     * hands a library building over ({@code Clipboard.saveTemplate()}).
     */
    public static Preview preview(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.Vec3i size) {
        net.minecraft.nbt.ListTag palette = tag.getList("palette")
                .orElseGet(() -> tag.getListOrEmpty("palettes").getListOrEmpty(0));
        int[] ids = new int[palette.size()];
        for (int i = 0; i < ids.length; i++) {
            BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i));
            ids[i] = state.isAir() ? -1 : net.minecraft.world.level.block.Block.getId(state);
        }

        net.minecraft.nbt.ListTag blocks = tag.getListOrEmpty("blocks");
        var states = new it.unimi.dsi.fastutil.ints.IntArrayList(blocks.size());
        var cells = new it.unimi.dsi.fastutil.ints.IntArrayList(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            net.minecraft.nbt.CompoundTag block = blocks.getCompoundOrEmpty(i);
            int index = block.getIntOr("state", -1);
            if (index < 0 || index >= ids.length || ids[index] < 0) continue;
            net.minecraft.nbt.ListTag pos = block.getListOrEmpty("pos");
            int x = pos.getIntOr(0, 0), y = pos.getIntOr(1, 0), z = pos.getIntOr(2, 0);
            states.add(ids[index]);
            cells.add(x + size.getX() * (y + size.getY() * z));
        }
        return new Preview(states.toIntArray(), cells.toIntArray());
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
