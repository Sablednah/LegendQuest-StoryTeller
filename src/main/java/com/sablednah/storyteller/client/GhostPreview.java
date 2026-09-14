package com.sablednah.storyteller.client;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.storyteller.ghost.GhostMath;
import com.sablednah.storyteller.network.GhostPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * The drawn ghost's state and controls: which structure, where it stands, which
 * way it faces. {@link GhostRenderer} draws it; this decides everything else.
 *
 * <p><b>Local on purpose.</b> Turning and nudging change nothing on the server,
 * so they cost no round trip and feel immediate. Only the final right-click
 * talks to the server, and it sends exactly the command a vanilla Storyteller
 * would type — {@code /st struct place <template> at <pos> [rotate ...]} — so
 * the ghost adds no rule the command does not already have.</p>
 *
 * <p><b>Nothing version-sensitive draws here.</b> Input events, the key
 * mappings and a ray are stable across 1.21.11 and 26.x; the renderer is the
 * file each branch rewrites.</p>
 *
 * <p>Controls, only while a ghost is up: aim moves it; scroll turns it;
 * Shift+scroll or Page Up/Down raise and lower it; the arrow keys shift it
 * relative to where you face; right-click places; left-click clears;
 * middle-click holds it still so you can walk round it.</p>
 */
public final class GhostPreview {

    /** A structure as received: unrotated, template-local. */
    record Ghost(String label, String placeCommand, Vec3i size, BlockState[] states, BlockPos[] local) {}

    private static Ghost ghost;
    private static Rotation rotation = Rotation.NONE;
    private static BlockPos offset = BlockPos.ZERO;
    private static BlockPos held;
    private static BlockPos aim;
    /** Bumped whenever a different structure arrives, so the renderer rebuilds. */
    private static int generation;
    private static int statusTicks;

    public static void accept(GhostPayload payload) {
        if (payload.isClear()) {
            forget();
            return;
        }
        int sizeX = payload.sizeX();
        int sizeY = payload.sizeY();
        int count = Math.min(payload.states().length, payload.cells().length);
        List<BlockState> states = new ArrayList<>(count);
        List<BlockPos> local = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockState state = Block.stateById(payload.states()[i]);
            if (state.isAir()) continue;
            int cell = payload.cells()[i];
            local.add(new BlockPos(cell % sizeX, (cell / sizeX) % sizeY, cell / (sizeX * sizeY)));
            states.add(state);
        }
        ghost = new Ghost(payload.label(), payload.placeCommand(),
                new Vec3i(sizeX, sizeY, payload.sizeZ()),
                states.toArray(BlockState[]::new), local.toArray(BlockPos[]::new));
        rotation = Rotation.NONE;
        // A CityWorld building starts with its foundation buried, as CityWorld buries it.
        offset = new BlockPos(0, payload.sinkY(), 0);
        held = null;
        aim = null;
        generation++;
        explain();
    }

    public static boolean showing() {
        return ghost != null;
    }

    static Ghost ghost() {
        return ghost;
    }

    static Rotation rotation() {
        return rotation;
    }

    static int generation() {
        return generation;
    }

    /** Where the template's origin goes right now, or null until the gaze has landed on something. */
    static BlockPos origin() {
        BlockPos at = held != null ? held : aim;
        if (ghost == null || at == null) return null;
        return GhostMath.origin(at, ghost.size(), rotation, offset);
    }

    static void forget() {
        ghost = null;
        held = null;
        aim = null;
    }

    /** Called on ClientTickEvent.Post from the client entrypoint. */
    static void onClientTick() {
        if (ghost == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            forget();
            return;
        }

        Direction facing = mc.player.getDirection();
        drain(STKeyMappings.GHOST_LEFT, GhostMath.Nudge.LEFT, facing);
        drain(STKeyMappings.GHOST_RIGHT, GhostMath.Nudge.RIGHT, facing);
        drain(STKeyMappings.GHOST_FORWARD, GhostMath.Nudge.FORWARD, facing);
        drain(STKeyMappings.GHOST_BACK, GhostMath.Nudge.BACK, facing);
        drain(STKeyMappings.GHOST_RAISE, GhostMath.Nudge.UP, facing);
        drain(STKeyMappings.GHOST_LOWER, GhostMath.Nudge.DOWN, facing);

        if (held == null) {
            HitResult hit = mc.player.pick(GhostMath.REACH, 1.0F, false);
            // A miss keeps the last aim: glancing up at the sky should not
            // make the building vanish.
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
                aim = block.getBlockPos().relative(block.getDirection());
            }
        }

        if (++statusTicks >= 10) {
            statusTicks = 0;
            ClientText.overlay(status());
        }
    }

    private static void drain(KeyMapping key, GhostMath.Nudge way, Direction facing) {
        while (key.consumeClick()) {
            offset = offset.relative(way.toWorld(facing));
        }
    }

    /**
     * Scroll turns the ghost; with Shift held it raises and lowers it.
     *
     * <p>NeoForge only posts this while the game itself has the mouse, so an
     * open screen scrolls as normal and this never needs to ask about one.</p>
     */
    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (ghost == null) return;
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;
        event.setCanceled(true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyShift.isDown()) {
            offset = offset.relative(delta > 0 ? Direction.UP : Direction.DOWN);
        } else {
            // Wheel towards you turns it clockwise, as a dial on a desk would.
            rotation = GhostMath.turn(rotation, delta < 0);
        }
        statusTicks = 10;
    }

    /** Right-click places, left-click clears, middle-click holds. None of them reach the world. */
    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (ghost == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (event.isUseItem()) {
            place(mc);
        } else if (event.isAttack()) {
            forget();
            ClientText.overlay(grey("Ghost put away."));
        } else if (event.isPickBlock()) {
            toggleHold();
        } else {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    private static void toggleHold() {
        if (held != null) {
            held = null;
        } else if (aim != null) {
            held = aim;
        }
        statusTicks = 10;
    }

    private static void place(Minecraft mc) {
        BlockPos origin = origin();
        if (origin == null) {
            ClientText.chat(grey("Aim at the ground where it should go first."));
            return;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        connection.sendCommand(ghost.placeCommand() + " at " + origin.getX() + " " + origin.getY() + " "
                + origin.getZ() + GhostMath.rotateSuffix(rotation));
        forget();
    }

    private static Component status() {
        String name = GhostMath.shortName(ghost.label());
        if (origin() == null) {
            return grey("Look at the ground to stand the ghost of ").append(white(name)).append(grey(" on it."));
        }
        MutableComponent line = grey("Ghost ").append(white(name))
                .append(grey(" · front faces ")).append(white(GhostMath.front(rotation).getName()));
        String moved = GhostMath.describeOffset(offset);
        if (!moved.isEmpty()) line.append(grey(" · ")).append(white(moved));
        if (held != null) line.append(Component.literal(" · held").withStyle(ChatFormatting.YELLOW));
        return line;
    }

    /** Say what the controls are, naming the keys as this player has them bound. */
    private static void explain() {
        ClientText.chat(Component.literal("Ghost of ").withStyle(ChatFormatting.GREEN)
                .append(white(ghost.label()))
                .append(grey(". It follows where you look. "))
                .append(white("Scroll")).append(grey(" turns it; "))
                .append(white("Shift+scroll")).append(grey(" or "))
                .append(white(keyName(STKeyMappings.GHOST_RAISE) + "/" + keyName(STKeyMappings.GHOST_LOWER)))
                .append(grey(" raise and lower it; "))
                .append(white(keyName(STKeyMappings.GHOST_LEFT) + " " + keyName(STKeyMappings.GHOST_RIGHT) + " "
                        + keyName(STKeyMappings.GHOST_FORWARD) + " " + keyName(STKeyMappings.GHOST_BACK)))
                .append(grey(" shift it; "))
                .append(white("right-click")).append(grey(" places, "))
                .append(white("left-click")).append(grey(" clears, "))
                .append(white("middle-click")).append(grey(" holds it still.")));
    }

    private static String keyName(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    private static MutableComponent grey(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent white(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    private GhostPreview() {}
}
