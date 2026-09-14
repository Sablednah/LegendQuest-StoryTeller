package com.sablednah.storyteller.ghost;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Where a ghost stands, worked out the same way on both sides.
 *
 * <p><b>One set of arithmetic for the server's outline and the client's drawn
 * ghost</b>, and for the command either of them finally sends. If the two
 * worked out "centred on where you are looking" separately, a building could
 * be drawn in one place and land a block over, which is the exact failure the
 * preview exists to prevent. So this class names no client type and no server
 * type, and both halves call it.</p>
 *
 * <p>Everything here is in the convention {@code /st struct place ... at}
 * uses, which is vanilla's {@code /place template}: the position is the
 * template's own origin, and rotation turns about that origin
 * ({@code pivot = ZERO}). A quarter turn therefore swings the footprint into
 * negative X or Z, which is why {@link #origin} centres on the <i>rotated</i>
 * box rather than on the raw size.</p>
 */
public final class GhostMath {

    /**
     * How far a ghost follows the Storyteller's gaze, in blocks. Generous on
     * purpose: the natural place to site a building from is a hilltop, and at
     * 64 a Storyteller twenty blocks up looking at a valley floor hit nothing.
     */
    public static final double REACH = 128.0D;

    /**
     * A structure's name as a person would say it — the last part of
     * {@code minecraft:village/plains/houses/plains_small_house_1} — for the
     * action bar, which cuts a full id off at both ends.
     */
    public static String shortName(String label) {
        String name = label.substring(label.lastIndexOf(':') + 1);
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /**
     * The blocks a template of this size covers when placed at the world
     * origin with this rotation.
     */
    public static BoundingBox localBox(Vec3i size, Rotation rotation) {
        BlockPos far = new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        return BoundingBox.fromCorners(
                StructureTemplate.transform(BlockPos.ZERO, Mirror.NONE, rotation, BlockPos.ZERO),
                StructureTemplate.transform(far, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    /**
     * The origin that stands a template's footprint centred on {@code aim},
     * with its bottom layer at {@code aim}'s height, then shifted by
     * {@code offset}.
     *
     * <p>{@code aim} is the block <i>in front of</i> the face being looked at,
     * so looking at the ground puts the building on it rather than in it.</p>
     */
    public static BlockPos origin(BlockPos aim, Vec3i size, Rotation rotation, BlockPos offset) {
        BoundingBox box = localBox(size, rotation);
        int centreX = box.minX() + box.getXSpan() / 2;
        int centreZ = box.minZ() + box.getZSpan() / 2;
        return aim.offset(-centreX, -box.minY(), -centreZ).offset(offset);
    }

    /** The world-space footprint of a template placed at {@code origin}. */
    public static BoundingBox worldBox(BlockPos origin, Vec3i size, Rotation rotation) {
        return localBox(size, rotation).moved(origin.getX(), origin.getY(), origin.getZ());
    }

    /**
     * Which side of the footprint the template's own front ended up on.
     *
     * <p>A square footprint looks identical at every quarter turn, so without
     * this a rotate on the outline would appear to do nothing. The "front" is
     * the template's north side as saved, which is the side a structure block
     * author was facing and usually where the door is.</p>
     */
    public static Direction front(Rotation rotation) {
        return rotation.rotate(Direction.NORTH);
    }

    public static Rotation turn(Rotation rotation, boolean clockwise) {
        return rotation.getRotated(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
    }

    /** The words {@code /st struct place} takes for a rotation, with a leading space; empty for none. */
    public static String rotateSuffix(Rotation rotation) {
        return switch (rotation) {
            case NONE -> "";
            case CLOCKWISE_90 -> " rotate cw90";
            case CLOCKWISE_180 -> " rotate 180";
            case COUNTERCLOCKWISE_90 -> " rotate ccw90";
        };
    }

    /**
     * A shift of the ghost, relative to the way the Storyteller is facing.
     *
     * <p>Relative, because "left" is the word a person uses while standing in
     * front of the thing; "west" makes them stop and work out which way they
     * are looking. It is turned into a world direction at the moment of the
     * nudge, so the offset stays put when they walk round the ghost.</p>
     */
    public enum Nudge {
        UP, DOWN, LEFT, RIGHT, FORWARD, BACK;

        public Direction toWorld(Direction facing) {
            return switch (this) {
                case UP -> Direction.UP;
                case DOWN -> Direction.DOWN;
                case FORWARD -> facing;
                case BACK -> facing.getOpposite();
                case LEFT -> facing.getCounterClockWise();
                case RIGHT -> facing.getClockWise();
            };
        }

        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** An offset as a person would say it: "raised 2, 3 east". Empty for none. */
    public static String describeOffset(BlockPos offset) {
        StringBuilder out = new StringBuilder();
        if (offset.getY() != 0) {
            out.append(offset.getY() > 0 ? "raised " : "lowered ").append(Math.abs(offset.getY()));
        }
        appendAxis(out, offset.getX(), "east", "west");
        appendAxis(out, offset.getZ(), "south", "north");
        return out.toString();
    }

    private static void appendAxis(StringBuilder out, int amount, String positive, String negative) {
        if (amount == 0) return;
        if (out.length() > 0) out.append(", ");
        out.append(Math.abs(amount)).append(' ').append(amount > 0 ? positive : negative);
    }

    private GhostMath() {}
}
