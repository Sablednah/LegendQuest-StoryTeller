package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.sablednah.storyteller.ghost.GhostMath;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Whole generated structures — a village, a bastion, a fortress — placed as
 * world generation would assemble them, seen first, and taken back off again.
 *
 * <p>Vanilla already assembles one for an operator with {@code /place
 * structure}, so the tool is that, reached through the Storyteller's own
 * permission, with the two things it has never had: <b>a preview of the
 * assembly that will actually land</b>, and <b>an undo</b>.</p>
 *
 * <p><b>Deciding and building are separate, and that is what makes both
 * possible.</b> {@link Structure#generate} works out every piece and its box
 * without writing a block. So a roll can be shown as a ghost, held while the
 * Storyteller walks round it, and then built — and the footprint is known
 * before anything is overwritten, which is what undo snapshots.</p>
 *
 * <p><b>A roll is a layout, and it is pinned by a number.</b> Assembly is
 * random, so a preview that re-rolled when it was placed would be showing a
 * different village from the one that landed — the exact failure a preview
 * exists to prevent. A layout is a function of the world seed, a
 * {@link Roll#index} and the chunk it was rolled in, so the ghost sends all
 * three back in the command and the server regenerates the identical assembly.
 * {@code [Reroll]} asks for the next index at the same spot.</p>
 *
 * <p><b>Why the chunk is part of it.</b> Generation anchors an assembly on the
 * terrain of the chunk it rolls in — a village's houses each sit on the ground
 * height where they land. Rolling at the chunk the Storyteller is looking at is
 * therefore the difference between houses on the ground and houses in the air,
 * and it is why the roll is not simply a number on its own.</p>
 *
 * <p><b>It is not turned.</b> A start's pieces carry their own final rotations
 * with no setter, so turning the assembly would mean rebuilding every piece,
 * and vanilla has never offered it either. The ghost says "as generated" where
 * a single building says which way its front faces, rather than leaving a
 * scroll wheel that looks broken.</p>
 */
public final class WholeStructures {

    /**
     * How far the snapshot will go before undo is given up on: about 24 MB of
     * indices. Reached only by something like an ancient city; a village is two
     * orders of magnitude inside it.
     */
    private static final int MAX_SNAPSHOT = 6_000_000;

    /** Pieces drawn as outline boxes for a vanilla client before it stops being a help. */
    private static final int MAX_OUTLINE_PARTS = 96;

    /**
     * One layout of one structure. The world seed is the third term and comes
     * from the level, so these two identify it inside a world.
     *
     * @param index  which roll: 0 is the one a bare command gets, {@code [Reroll]} counts up
     * @param chunk  the chunk it was rolled in, whose terrain the pieces were fitted to
     */
    public record Roll(int index, int chunkX, int chunkZ) {

        /**
         * Two ints rather than a {@link ChunkPos}, and that is a portability
         * decision: 26.x made ChunkPos a record whose {@code x} and {@code z}
         * are private, so {@code chunk.x} compiles on 1.21.11 and on no branch
         * after it. The pair travels as ints and becomes a ChunkPos only where
         * one is needed, through the constructor every line still has.
         */
        public ChunkPos chunk() {
            return new ChunkPos(chunkX, chunkZ);
        }

        /** The roll for the chunk containing {@code pos}. */
        public static Roll at(int index, BlockPos pos) {
            return new Roll(index, SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ()));
        }

        /** The tail a place command carries so it rebuilds this exact assembly. */
        public String command() {
            return " roll " + index + " " + chunkX + " " + chunkZ;
        }
    }

    /** The whole-structure ghost a Storyteller has up, so {@code [Reroll]} knows what to roll again. */
    private record Pending(Holder<Structure> structure, Identifier id, Roll roll) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /**
     * What a placement did, or why it did not — in the words the Storyteller
     * needs, since {@code problem} is printed to them rather than logged.
     */
    public record Result(boolean ok, String problem, int pieces, boolean undoable) {
        static Result refused(String problem) {
            return new Result(false, problem, 0, false);
        }
    }

    // --- rolling ------------------------------------------------------------

    /**
     * Work out an assembly without writing anything: every piece, where it
     * stands and which way round it is.
     *
     * <p>The biome check is deliberately open ({@code holder -> true}), exactly
     * as {@code /place structure} does it: a Storyteller asking for a desert
     * village in a snowfield is staging a scene, not generating a world.</p>
     */
    static StructureStart generate(ServerLevel level, Holder<Structure> structure, Roll roll) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return structure.value().generate(
                structure, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
                level.getChunkSource().randomState(), level.getStructureManager(),
                level.getSeed() + roll.index(), roll.chunk(), 0, level, holder -> true);
    }

    /**
     * The assembly's own box, worked out from its pieces.
     *
     * <p>Not {@link StructureStart#getBoundingBox()}, for two reasons: it caches
     * the answer, which would go stale the moment the pieces are moved, and it
     * inflates the box by twelve blocks for a structure that adapts terrain —
     * margin that belongs to world generation, not to what a Storyteller is
     * looking at.</p>
     */
    static BoundingBox box(StructureStart start) {
        return StructurePiece.createBoundingBox(start.getPieces().stream());
    }

    /**
     * Shift the whole assembly so its lowest north-west corner lands on
     * {@code target}, keeping the layout exactly as it was rolled.
     *
     * <p>Every piece carries its own template position, and each kind overrides
     * {@code move} to take it along — so this moves the blocks that will be
     * placed, not merely the boxes that describe them.</p>
     */
    static void moveTo(StructureStart start, BoundingBox from, BlockPos target) {
        int dx = target.getX() - from.minX();
        int dy = target.getY() - from.minY();
        int dz = target.getZ() - from.minZ();
        if (dx == 0 && dy == 0 && dz == 0) return;
        for (StructurePiece piece : start.getPieces()) {
            piece.move(dx, dy, dz);
        }
    }

    // --- the ghost ----------------------------------------------------------

    /** {@code /st struct whole ghost <structure>} — roll one and show it where they are looking. */
    static int ghost(ServerPlayer player, Holder<Structure> structure, Identifier id, int index) {
        ServerLevel level = (ServerLevel) player.level();
        Roll roll = Roll.at(index, aim(player));
        StructureStart start = generate(level, structure, roll);
        if (!start.isValid()) {
            Feedback.chat(player, "&c'" + id + "' would not generate here. Some structures only assemble in "
                    + "the dimension they belong to — try it in the Nether or the End if it lives there.");
            return 0;
        }

        BoundingBox box = box(start);
        Vec3i size = new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan());
        List<BoundingBox> parts = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox part = piece.getBoundingBox();
            parts.add(part.moved(-box.minX(), -box.minY(), -box.minZ()));
            if (parts.size() >= MAX_OUTLINE_PARTS) break;
        }

        PENDING.put(player.getUUID(), new Pending(structure, id, roll));
        int shown = Ghosts.show(player, id.toString(), "st struct whole place " + id + roll.command(),
                size, () -> preview(level, start, box), 0, false, parts);
        if (shown != 0) {
            Feedback.chat(player, "&7" + start.getPieces().size() + " pieces, as generation would assemble them. "
                    + "&f/st struct ghost reroll&7 deals a different one.");
            rerollButton(player);
        }
        return shown;
    }

    /** {@code /st struct ghost reroll} — the same structure, the same spot, a different assembly. */
    static int reroll(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            Feedback.chat(player, "&7Nothing to reroll — only a whole structure has other layouts, and "
                    + "&f/st struct whole ghost <structure>&7 starts one.");
            return 0;
        }
        return ghost(player, pending.structure(), pending.id(), pending.roll().index() + 1);
    }

    static void forget(ServerPlayer player) {
        PENDING.remove(player.getUUID());
    }

    private static void rerollButton(ServerPlayer player) {
        player.sendSystemMessage(Feedback.colored("&7Layout:").copy()
                .append(net.minecraft.network.chat.Component.literal(" "))
                .append(Feedback.colored("&e[Reroll]").copy().withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/st struct ghost reroll"))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                                Feedback.colored("&7Another assembly of the same structure, on the same spot"))))));
    }

    /**
     * Every piece's blocks, composed into one volume in the orientation they
     * will land in.
     *
     * <p>A piece of a jigsaw structure knows its template, where it stands and
     * which way it is turned, so the same reading that draws a single building
     * draws all forty of a village's — turned and offset into the assembly's own
     * box. Pieces built by code rather than from a template (a fortress
     * corridor, a stronghold room) have no blocks to read and are left to the
     * outline, so a mixed structure draws what it can and outlines the rest
     * rather than showing nothing.</p>
     *
     * @return the blocks; an <i>empty</i> preview when no piece has a template to
     *         read, and <i>null</i> when there are more than a payload may carry.
     *         The caller falls back to the outline either way and says which,
     *         because "too big to draw" and "nothing to draw" are different
     *         answers and a Storyteller should not have to guess which they got.
     */
    static Structures.Preview preview(ServerLevel level, StructureStart start, BoundingBox box) {
        int sizeX = box.getXSpan();
        int sizeY = box.getYSpan();
        var states = new it.unimi.dsi.fastutil.ints.IntArrayList();
        var cells = new it.unimi.dsi.fastutil.ints.IntArrayList();

        for (StructurePiece piece : start.getPieces()) {
            Optional<Placed> placed = templateOf(level, piece);
            if (placed.isEmpty()) continue;
            Placed part = placed.get();
            Optional<StructureTemplate> template = Structures.template(level, part.template());
            if (template.isEmpty()) continue;

            Vec3i templateSize = template.get().getSize();
            if (templateSize.getX() < 1 || templateSize.getY() < 1 || templateSize.getZ() < 1) continue;
            CompoundTag saved = template.get().save(new CompoundTag());
            // Jigsaw blocks as their final state: this is the assembled form,
            // so every connector has been joined and swapped by definition.
            Structures.Preview raw = Structures.preview(saved, templateSize, false);

            for (int i = 0; i < raw.states().length; i++) {
                int cell = raw.cells()[i];
                BlockPos local = new BlockPos(cell % templateSize.getX(),
                        (cell / templateSize.getX()) % templateSize.getY(),
                        cell / (templateSize.getX() * templateSize.getY()));
                BlockPos world = part.at().offset(
                        StructureTemplate.transform(local, part.mirror(), part.rotation(), BlockPos.ZERO));
                if (!box.isInside(world)) continue;
                BlockState state = Block.stateById(raw.states()[i]).mirror(part.mirror()).rotate(part.rotation());
                states.add(Block.getId(state));
                cells.add((world.getX() - box.minX())
                        + sizeX * ((world.getY() - box.minY()) + sizeY * (world.getZ() - box.minZ())));
                if (states.size() > com.sablednah.storyteller.network.GhostPayload.MAX_BLOCKS) {
                    return null;   // over the payload's ceiling; the outline is the honest answer
                }
            }
        }
        return new Structures.Preview(states.toIntArray(), cells.toIntArray());
    }

    /** Where one piece's template stands, for reading its blocks. */
    private record Placed(Identifier template, BlockPos at, Rotation rotation, Mirror mirror) {}

    /**
     * The template a piece is stamped from, if it has one.
     *
     * <p>Two shapes and no more. A jigsaw piece names its element, which names
     * its template. A template piece keeps the same three facts in the tag it
     * saves to the region file, which is the only public view of them. Anything
     * else is built block by block in code and has no template at all.</p>
     */
    private static Optional<Placed> templateOf(ServerLevel level, StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece pool) {
            if (!(pool.getElement() instanceof SinglePoolElement single)) return Optional.empty();
            try {
                return Optional.of(new Placed(single.getTemplateLocation(), pool.getPosition(),
                        pool.getRotation(), Mirror.NONE));
            } catch (RuntimeException notAnIdentifier) {
                return Optional.empty();   // an element holding a template directly, not by name
            }
        }
        if (piece instanceof TemplateStructurePiece template) {
            CompoundTag tag = template.createTag(StructurePieceSerializationContext.fromLevel(level));
            String name = tag.getStringOr("Template", "");
            if (name.isEmpty()) return Optional.empty();
            try {
                return Optional.of(new Placed(Identifier.parse(name),
                        new BlockPos(tag.getIntOr("TPX", 0), tag.getIntOr("TPY", 0), tag.getIntOr("TPZ", 0)),
                        template.getRotation(), template.getMirror()));
            } catch (net.minecraft.IdentifierException unparseable) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // --- placing ------------------------------------------------------------

    /**
     * Build one. {@code target} is where the assembly's lowest north-west
     * corner goes, or null to leave it where generation put it — which is
     * exactly what {@code /place structure} does, so the bare command behaves
     * the way an operator already expects.
     */
    static Result place(ServerPlayer player, Holder<Structure> structure, Identifier id, Roll roll, BlockPos target) {
        ServerLevel level = (ServerLevel) player.level();
        PENDING.remove(player.getUUID());

        StructureStart start = generate(level, structure, roll);
        if (!start.isValid()) {
            return Result.refused("'" + id + "' would not generate here. Some structures only assemble in the "
                    + "dimension they belong to.");
        }

        BoundingBox box = box(start);
        if (target != null) {
            moveTo(start, box, target);
            box = box(start);
        }

        ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
        Optional<ChunkPos> missing = ChunkPos.rangeClosed(min, max)
                .filter(chunk -> !level.isLoaded(chunk.getWorldPosition())).findAny();
        if (missing.isPresent()) {
            // Block coordinates, not chunk ones: a Storyteller reads the world
            // in the numbers F3 shows them, and this is a message they act on.
            return Result.refused("it reaches ground nobody has loaded yet, around x "
                    + missing.get().getMinBlockX() + ", z " + missing.get().getMinBlockZ()
                    + ". Move closer to where it should stand and try again.");
        }

        // Snapshot the pieces, not the whole box: most of a village is the
        // untouched ground between its houses, and the difference is the
        // difference between undo working and undo being abandoned.
        List<Structures.VolumeSnap> before = new ArrayList<>();
        int cost = 0;
        for (StructurePiece piece : start.getPieces()) {
            cost += Structures.VolumeSnap.volume(piece.getBoundingBox().inflatedBy(1));
        }
        boolean undoable = cost <= MAX_SNAPSHOT;
        if (undoable) {
            for (StructurePiece piece : start.getPieces()) {
                before.add(Structures.snapshotVolume(level, piece.getBoundingBox().inflatedBy(1)));
            }
        }

        Set<UUID> standing = new HashSet<>();
        AABB footprint = AABB.of(box);
        for (Entity entity : level.getEntities((Entity) null, footprint, any -> true)) {
            standing.add(entity.getUUID());
        }

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos.rangeClosed(min, max).forEach(chunk -> start.placeInChunk(level, level.structureManager(), generator,
                level.getRandom(),
                new BoundingBox(chunk.getMinBlockX(), level.getMinY(), chunk.getMinBlockZ(),
                        chunk.getMaxBlockX(), level.getMaxY() + 1, chunk.getMaxBlockZ()),
                chunk));

        List<UUID> arrivals = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, footprint, any -> true)) {
            if (!standing.contains(entity.getUUID())) arrivals.add(entity.getUUID());
        }

        if (undoable) {
            Structures.recordWholePlacement(player, GhostMath.shortName(id.toString()), before, arrivals);
        }
        return new Result(true, "", start.getPieces().size(), undoable);
    }

    /** Where the Storyteller is looking, or where they stand if that is nothing. */
    private static BlockPos aim(ServerPlayer player) {
        HitResult hit = player.pick(GhostMath.REACH, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            return block.getBlockPos();
        }
        return player.blockPosition();
    }

    private WholeStructures() {}
}
