package com.sablednah.storyteller.neoforge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.storyteller.ghost.GhostMath;
import com.sablednah.storyteller.network.GhostPayload;
import com.sablednah.storyteller.network.STNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * See a building where it will stand before it is placed.
 *
 * <p><b>Two ghosts, one command at the end of both.</b> A Storyteller with the
 * StoryTeller client gets the structure's blocks in a {@link GhostPayload} and
 * their client draws it, steered by scroll and keys. A Storyteller without it
 * gets the footprint drawn as a particle box only they can see, steered by the
 * {@code /st struct ghost} subcommands and a row of chat buttons. Either way
 * the building is finally placed by {@code /st struct place <template> at
 * <pos> [rotate ...]}, so undo, permissions and feedback are the ones a typed
 * placement already has.</p>
 *
 * <p>The outline's position is worked out here from the server's own view of
 * where the Storyteller is looking, and the drawn ghost's on the client; both
 * go through {@link GhostMath}, so they agree about what "centred on my aim"
 * means.</p>
 *
 * <p><b>The gold edge is not decoration.</b> A square footprint looks identical
 * at every quarter turn, so without a marked front a rotate on the outline
 * would appear to have done nothing — the "did that work?" moment this project
 * tries never to create.</p>
 */
public final class Ghosts {

    private static final DustParticleOptions EDGE = new DustParticleOptions(0x9FD8FF, 1.0F);
    private static final DustParticleOptions FRONT = new DustParticleOptions(0xFFC83D, 1.5F);

    /** Ticks between outline redraws. Dust lives 8-40 ticks, so this reads as solid. */
    private static final int DRAW_EVERY = 4;
    private static final int STATUS_EVERY = 20;

    private static final class Session {
        final String label;
        /** The command a placement finishes, without the slash or the position. */
        final String placeCommand;
        final Vec3i size;
        /**
         * What the outline draws, relative to the ghost's origin: null for a
         * single building, which is its own box, or one box per piece for a
         * whole generated structure — a village's houses rather than the empty
         * rectangle that would contain them.
         */
        final java.util.List<BoundingBox> parts;
        /** False for a whole generated structure; see {@code GhostPayload.rotatable}. */
        final boolean rotatable;
        final ResourceKey<Level> dimension;
        Rotation rotation = Rotation.NONE;
        BlockPos offset = BlockPos.ZERO;
        /** Where the Storyteller pinned it, or null while it follows their aim. */
        BlockPos held;
        /** The last block the gaze landed on, kept so glancing at the sky does not lose the ghost. */
        BlockPos lastAim;

        Session(String label, String placeCommand, Vec3i size, java.util.List<BoundingBox> parts,
                boolean rotatable, ResourceKey<Level> dimension) {
            this.label = label;
            this.placeCommand = placeCommand;
            this.size = size;
            this.parts = parts;
            this.rotatable = rotatable;
            this.dimension = dimension;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** Whether this player's client negotiated the ghost channel, and so can draw one. */
    public static boolean clientDraws(ServerPlayer player) {
        return STNetwork.listening(player, GhostPayload.TYPE.id());
    }

    /** {@code /st struct ghost <template>}. */
    static int start(ServerPlayer player, Identifier templateId, boolean withAir, boolean withJigsaw) {
        Optional<StructureTemplate> template = Structures.template((ServerLevel) player.level(), templateId);
        if (template.isEmpty()) {
            Feedback.chat(player, "&cNo structure called '" + templateId + "' is loaded. "
                    + "&f/st struct list&c shows what is.");
            return 0;
        }
        Vec3i size = template.get().getSize();
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) {
            Feedback.chat(player, "&c'" + templateId + "' is empty, so there is nothing to show.");
            return 0;
        }
        String flags = (withAir ? " withair" : "") + (withJigsaw ? " withjigsaw" : "");
        return show(player, templateId.toString(), "st struct place " + templateId + flags, size,
                () -> Structures.preview(template.get(), withJigsaw), 0);
    }

    /**
     * Put a ghost up from either pool: blocks for a client that can draw them,
     * the outline for one that cannot.
     *
     * @param placeCommand finished with {@code " at <pos> [rotate ...]"} when placed
     * @param blocks       asked only when a client will draw them, since reading them costs
     * @param sinkY        where it starts vertically — minus a CityWorld building's
     *                     {@code GroundLevelY}, so its foundation starts buried
     */
    static int show(ServerPlayer player, String label, String placeCommand, Vec3i size,
            java.util.function.Supplier<Structures.Preview> blocks, int sinkY) {
        return show(player, label, placeCommand, size, blocks, sinkY, true, null);
    }

    /**
     * The same, for a ghost that does not turn and whose outline is many boxes
     * rather than one — a whole generated structure.
     *
     * @param rotatable false to leave the rotation alone and say so; a generated
     *                  assembly's pieces carry their own final rotations
     * @param parts     one box per piece for the outline, or null for a single box
     */
    static int show(ServerPlayer player, String label, String placeCommand, Vec3i size,
            java.util.function.Supplier<Structures.Preview> blocks, int sinkY,
            boolean rotatable, java.util.List<BoundingBox> parts) {
        SESSIONS.remove(player.getUUID());

        if (clientDraws(player)) {
            Structures.Preview preview = blocks.get();
            if (preview != null && preview.states().length > 0
                    && preview.states().length <= GhostPayload.MAX_BLOCKS) {
                // The client says what the controls are, because only the
                // client knows which keys they are bound to.
                STNetwork.sendGhost(player, new GhostPayload(label, placeCommand,
                        size.getX(), size.getY(), size.getZ(), sinkY, rotatable,
                        preview.states(), preview.cells()));
                return 1;
            }
            STNetwork.sendGhost(player, GhostPayload.clear());
            // Two different answers, said differently: one is a size and the
            // other is what the structure is made of.
            Feedback.chat(player, preview == null
                    ? "&7'" + label + "' has more blocks than a ghost can carry, so you get its outline instead."
                    : "&7'" + label + "' is built piece by piece in code rather than from saved templates, "
                            + "so there are no blocks to draw — you get its outline instead.");
        }

        Session session = new Session(label, placeCommand, size, parts, rotatable, player.level().dimension());
        session.offset = new BlockPos(0, sinkY, 0);
        SESSIONS.put(player.getUUID(), session);
        Feedback.chat(player, "&aGhost of &f" + label + "&a: its outline follows where you look"
                + (rotatable ? ", and the &6gold edge&a is its front." : "."));
        return controls(player);
    }

    /** {@code /st struct ghost} on its own: the buttons again, since chat scrolls them away. */
    static int controls(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return noGhost(player);
        MutableComponent line = Feedback.colored("&7Ghost:").copy();
        if (session.rotatable) {
            button(line, "&e[↺]", "st struct ghost rotate ccw", "&7Turn it a quarter anticlockwise");
            button(line, "&e[↻]", "st struct ghost rotate cw", "&7Turn it a quarter clockwise");
        } else {
            // A generated assembly does not turn, so the button in that slot is
            // the one that does change it: another layout.
            button(line, "&e[Reroll]", "st struct ghost reroll",
                    "&7Another assembly of the same structure, on the same spot");
        }
        button(line, "&b[▲]", "st struct ghost nudge up", "&7Raise it one block");
        button(line, "&b[▼]", "st struct ghost nudge down", "&7Lower it one block");
        button(line, "&b[◀]", "st struct ghost nudge left", "&7One block to your left");
        button(line, "&b[▶]", "st struct ghost nudge right", "&7One block to your right");
        button(line, "&b[Fwd]", "st struct ghost nudge forward", "&7One block away from you");
        button(line, "&b[Back]", "st struct ghost nudge back", "&7One block towards you");
        button(line, "&f[Lock]", "st struct ghost hold",
                "&7Lock it where it is so you can walk round it. Again to let it follow your aim.");
        button(line, "&a[Place]", "st struct ghost place", "&7Build it here. &f/st undo&7 takes it back off.");
        button(line, "&c[Cancel]", "st struct ghost cancel", "&7Put the ghost away");
        player.sendSystemMessage(line);
        return 1;
    }

    static int rotate(ServerPlayer player, boolean clockwise) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return noGhost(player);
        if (!session.rotatable) {
            Feedback.chat(player, "&7A whole structure lands the way generation assembled it, so it does not "
                    + "turn. &f/st struct ghost reroll&7 deals a different layout instead.");
            return 0;
        }
        session.rotation = GhostMath.turn(session.rotation, clockwise);
        status(player, session);
        return 1;
    }

    static int nudge(ServerPlayer player, GhostMath.Nudge way, int blocks) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return noGhost(player);
        session.offset = session.offset.relative(way.toWorld(player.getDirection()), blocks);
        status(player, session);
        return 1;
    }

    static int hold(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return noGhost(player);
        if (session.held != null) {
            session.held = null;
            Feedback.actionBar(player, "&7The ghost follows your aim again.");
            return 1;
        }
        BlockPos aim = aim(player, session);
        if (aim == null) {
            Feedback.chat(player, "&7Look at the ground where it should stand, then lock it.");
            return 0;
        }
        session.held = aim;
        Feedback.chat(player, "&7Locked where it is. Walk round it; &f[Lock]&7 again lets it follow your aim.");
        return 1;
    }

    static int place(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return noGhost(player);
        BlockPos aim = aim(player, session);
        if (aim == null) {
            Feedback.chat(player, "&cAim at the ground where it should go first.");
            return 0;
        }
        BlockPos origin = GhostMath.origin(aim, session.size, session.rotation, session.offset);
        SESSIONS.remove(player.getUUID());
        // Through the dispatcher rather than calling Structures directly, so a
        // ghost placement is not merely equivalent to the typed command — it
        // IS the typed command, feedback and all.
        String command = session.placeCommand + " at " + origin.getX() + " " + origin.getY() + " " + origin.getZ()
                + GhostMath.rotateSuffix(session.rotation);
        ((ServerLevel) player.level()).getServer().getCommands()
                .performPrefixedCommand(player.createCommandSourceStack(), command);
        return 1;
    }

    static int cancel(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
        WholeStructures.forget(player);
        if (clientDraws(player)) STNetwork.sendGhost(player, GhostPayload.clear());
        Feedback.actionBar(player, "&7Ghost put away.");
        return 1;
    }

    static void forget(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
        WholeStructures.forget(player);
    }

    /** Redraw every outline. Costs nothing while nobody has one up. */
    static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        int now = server.getTickCount();
        if (now % DRAW_EVERY != 0) return;

        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.level().dimension() != session.dimension) {
                it.remove();
                Feedback.chat(player, "&7The ghost of &f" + GhostMath.shortName(session.label)
                        + "&7 stayed behind when you changed dimension. Start it again here to bring it.");
                continue;
            }
            BlockPos aim = aim(player, session);
            if (aim == null) {
                if (now % STATUS_EVERY == 0) {
                    Feedback.actionBar(player, "&7Look at the ground to stand the ghost of &f"
                            + GhostMath.shortName(session.label) + "&7 on it.");
                }
                continue;
            }
            BlockPos origin = GhostMath.origin(aim, session.size, session.rotation, session.offset);
            if (session.parts == null) {
                BoundingBox box = GhostMath.worldBox(origin, session.size, session.rotation);
                draw(player, box, GhostMath.front(session.rotation), step(session.size));
            } else {
                // Spacing comes from the whole assembly, not each piece: forty
                // house-sized boxes drawn at a house's spacing is forty times
                // the particles, four times a second, at one player.
                double step = Math.max(1.5D, step(session.size) * 2);
                for (BoundingBox part : session.parts) {
                    draw(player, part.moved(origin.getX(), origin.getY(), origin.getZ()), null, step);
                }
            }
            if (now % STATUS_EVERY == 0) status(player, session);
        }
    }

    private static BlockPos aim(ServerPlayer player, Session session) {
        if (session.held != null) return session.held;
        HitResult hit = player.pick(GhostMath.REACH, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            session.lastAim = block.getBlockPos().relative(block.getDirection());
        }
        return session.lastAim;
    }

    private static void status(ServerPlayer player, Session session) {
        String offset = GhostMath.describeOffset(session.offset);
        Feedback.actionBar(player, "&7Ghost &f" + GhostMath.shortName(session.label)
                + " &8· " + (session.rotatable
                        ? "&7front faces &f" + GhostMath.front(session.rotation).getName()
                        : "&7as generated")
                + (offset.isEmpty() ? "" : " &8· &f" + offset)
                + (session.held != null ? " &8· &elocked" : ""));
    }

    private static int noGhost(ServerPlayer player) {
        Feedback.chat(player, clientDraws(player)
                ? "&7No outline ghost is up. A drawn ghost is steered from your client: scroll turns it and "
                        + "right-click places it. &f/st struct ghost <structure>&7 starts one."
                : "&7No ghost is up. &f/st struct ghost <structure>&7 starts one.");
        return 0;
    }

    private static void button(MutableComponent line, String label, String command, String tooltip) {
        line.append(Component.literal(" ")).append(Feedback.colored(label).copy().withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/" + command))
                .withHoverEvent(new HoverEvent.ShowText(Feedback.colored(tooltip)))));
    }

    /**
     * Spacing for a footprint of this size: it grows with the building, so a big
     * one costs no more packets per edge than a 48-block one.
     */
    private static double step(Vec3i size) {
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        return Math.max(0.5D, longest / 48.0D);
    }

    /**
     * The footprint's twelve edges, and its front's bottom edge again in gold —
     * or no gold edge at all when {@code front} is null, which is a ghost that
     * cannot be turned and so has no ambiguity to resolve.
     */
    private static void draw(ServerPlayer player, BoundingBox box, Direction front, double step) {
        ServerLevel level = (ServerLevel) player.level();
        double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ();
        double x1 = box.maxX() + 1, y1 = box.maxY() + 1, z1 = box.maxZ() + 1;

        for (double y : new double[] { y0, y1 }) {
            line(level, player, EDGE, x0, y, z0, x1, y, z0, step);
            line(level, player, EDGE, x0, y, z1, x1, y, z1, step);
            line(level, player, EDGE, x0, y, z0, x0, y, z1, step);
            line(level, player, EDGE, x1, y, z0, x1, y, z1, step);
        }
        for (double[] corner : new double[][] { { x0, z0 }, { x1, z0 }, { x0, z1 }, { x1, z1 } }) {
            line(level, player, EDGE, corner[0], y0, corner[1], corner[0], y1, corner[1], step);
        }
        if (front == null) return;
        switch (front) {
            case NORTH -> line(level, player, FRONT, x0, y0, z0, x1, y0, z0, 0.5D);
            case SOUTH -> line(level, player, FRONT, x0, y0, z1, x1, y0, z1, 0.5D);
            case WEST -> line(level, player, FRONT, x0, y0, z0, x0, y0, z1, 0.5D);
            default -> line(level, player, FRONT, x1, y0, z0, x1, y0, z1, 0.5D);
        }
    }

    private static void line(ServerLevel level, ServerPlayer player, DustParticleOptions dust,
            double ax, double ay, double az, double bx, double by, double bz, double step) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int points = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / step));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            // Sent to this player alone, with the distance limiter overridden:
            // the outline is the Storyteller's, and a building is often further
            // than the 32 blocks ordinary particles reach.
            level.sendParticles(player, dust, true, true, ax + dx * t, ay + dy * t, az + dz * t, 1, 0, 0, 0, 0);
        }
    }

    private Ghosts() {}
}
