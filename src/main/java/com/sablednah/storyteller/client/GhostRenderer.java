package com.sablednah.storyteller.client;

import java.util.Arrays;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws the ghost. <b>The one class on the client that draws anything, and the
 * one each Minecraft line rewrites</b> — this is the 26.2 shape.
 * {@code BlockRenderDispatcher} and {@code MultiBufferSource} are gone, and a
 * level stage event hands out no buffer, so geometry is <i>submitted</i> instead.
 *
 * <p><b>Built once, replayed every frame.</b> Whenever a different structure
 * arrives or it turns, every block is tesselated once into {@link Mesh} — plain
 * arrays of vertices — and each frame submits a replay of those at the ghost's
 * current position. Moving the ghost costs nothing but the copy. The replay half
 * is the same as 1.21.11; the capture and the buffer it feeds are what moved.</p>
 *
 * <p>Blocks buried on all six sides by opaque blocks of the same structure are
 * skipped: they cannot be seen through a solid wall, and drawing them in a
 * translucent ghost would muddy the part that can.</p>
 *
 * <p>Submitted from {@link SubmitCustomGeometryEvent}, with camera position and
 * pose handled exactly as NeoForge's own render-bounds debug overlay does on
 * 26.2. Full bright and washed pale blue, so it never reads as a real building.</p>
 */
public final class GhostRenderer {

    /** Out of 255. Enough to judge a doorway through, faint enough to see the ground. */
    private static final int ALPHA = 110;
    private static final int WASH = ARGB.color(255, 190, 220, 255);
    /** A ceiling on what one ghost may hold, about 50 MB; a building past it is drawn in part. */
    private static final int MAX_VERTICES = 1_500_000;

    private static int builtGeneration = -1;
    private static Rotation builtRotation;
    private static Mesh mesh = Mesh.EMPTY;

    @SubscribeEvent
    static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        GhostPreview.Ghost ghost = GhostPreview.ghost();
        BlockPos origin = GhostPreview.origin();
        if (ghost == null || origin == null) {
            if (ghost == null && mesh != Mesh.EMPTY) mesh = Mesh.EMPTY;   // let the arrays go
            return;
        }
        Rotation rotation = GhostPreview.rotation();
        if (builtGeneration != GhostPreview.generation() || builtRotation != rotation) {
            mesh = build(ghost, rotation);
            builtGeneration = GhostPreview.generation();
            builtRotation = rotation;
        }
        Mesh drawn = mesh;   // the replay runs later in the frame; hold this one
        if (drawn.vertices == 0) return;

        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        // The collector copies the pose now and calls back when it builds the batch.
        event.getSubmitNodeCollector().submitCustomGeometry(poseStack, Sheets.translucentBlockItemSheet(), drawn::replay);
        poseStack.popPose();
    }

    private static Mesh build(GhostPreview.Ghost ghost, Rotation rotation) {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        // No ambient occlusion (full bright anyway), no face culling (burial is decided below).
        ModelBlockRenderer renderer = new ModelBlockRenderer(false, false, mc.getBlockColors());

        int count = ghost.states().length;
        BlockPos[] at = new BlockPos[count];
        BlockState[] states = new BlockState[count];
        LongOpenHashSet opaque = new LongOpenHashSet();
        for (int i = 0; i < count; i++) {
            // Exactly what placeInWorld does with setRotation(rotation) and a
            // ZERO pivot: move the position, turn the state.
            at[i] = StructureTemplate.transform(ghost.local()[i], Mirror.NONE, rotation, BlockPos.ZERO);
            states[i] = ghost.states()[i].rotate(rotation);
            if (states[i].isSolidRender()) opaque.add(at[i].asLong());
        }

        Capture capture = new Capture();
        for (int i = 0; i < count && capture.count < MAX_VERTICES; i++) {
            BlockState state = states[i];
            if (state.getRenderShape() == RenderShape.INVISIBLE) continue;
            if (buried(at[i], opaque)) continue;
            BlockPos pos = at[i];
            try {
                // Quads arrive with the block's position already added, so no pose is needed.
                renderer.tesselateBlock(capture::putBlockBakedQuad, pos.getX(), pos.getY(), pos.getZ(),
                        BlockAndTintGetter.EMPTY, pos, state, models.get(state), state.getSeed(pos));
            } catch (RuntimeException modelTrouble) {
                // One odd modded model should cost its own block, not the ghost.
            }
        }
        return capture.toMesh();
    }

    private static boolean buried(BlockPos pos, LongOpenHashSet opaque) {
        for (Direction side : Direction.values()) {
            if (!opaque.contains(pos.relative(side).asLong())) return false;
        }
        return true;
    }

    /** Vertices as plain arrays: position, colour (wash and alpha already applied), texture, normal. */
    private record Mesh(float[] floats, int[] colors, int vertices) {
        static final Mesh EMPTY = new Mesh(new float[0], new int[0], 0);

        void replay(PoseStack.Pose pose, VertexConsumer out) {
            for (int i = 0; i < vertices; i++) {
                int f = i * 8;
                out.addVertex(pose, floats[f], floats[f + 1], floats[f + 2])
                        .setColor(colors[i])
                        .setUv(floats[f + 3], floats[f + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightCoordsUtil.FULL_BRIGHT)
                        .setNormal(pose, floats[f + 5], floats[f + 6], floats[f + 7]);
            }
        }
    }

    /** A VertexConsumer that keeps what it is given instead of drawing it. */
    private static final class Capture implements VertexConsumer {
        private float[] floats = new float[8 * 4096];
        private int[] colors = new int[4096];
        private int count;
        private int current = -1;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            current = count++;
            if (count > colors.length) {
                colors = Arrays.copyOf(colors, colors.length * 2);
                floats = Arrays.copyOf(floats, floats.length * 2);
            }
            int f = current * 8;
            floats[f] = x;
            floats[f + 1] = y;
            floats[f + 2] = z;
            colors[current] = -1;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return setColor(ARGB.color(alpha, red, green, blue));
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (current >= 0) colors[current] = argb;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (current >= 0) {
                floats[current * 8 + 3] = u;
                floats[current * 8 + 4] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;   // overlay: replaced on replay
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;   // light: the ghost is always full bright
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (current >= 0) {
                int f = current * 8;
                floats[f + 5] = x;
                floats[f + 6] = y;
                floats[f + 7] = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        Mesh toMesh() {
            int[] washed = new int[count];
            for (int i = 0; i < count; i++) {
                int c = ARGB.multiply(colors[i], WASH);
                washed[i] = ARGB.color(ARGB.alpha(c) * ALPHA / 255, ARGB.red(c), ARGB.green(c), ARGB.blue(c));
            }
            return new Mesh(Arrays.copyOf(floats, count * 8), washed, count);
        }
    }

    private GhostRenderer() {}
}
