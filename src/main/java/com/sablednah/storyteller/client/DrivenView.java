package com.sablednah.storyteller.client;

import com.sablednah.storyteller.network.DrivenPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Not drawing the creature you are standing inside.
 *
 * <p>Driving works by moving the player and dragging the creature onto them,
 * which is why it needs no input plumbing — but it does mean the creature is
 * standing exactly where the camera is. Left alone, a driven wolf fills the
 * screen with the inside of a wolf, and a driven ghast blacks it out entirely.
 * This is the only part of the feature a server cannot do: deciding not to draw
 * something is a client's decision, and the server does not get a say.</p>
 *
 * <p><b>Both directions.</b> First person hides the creature you are inside;
 * third person hides the person inside it. In third person the creature is what
 * you want to see — it is standing where your body would be, so the view reads as
 * playing <i>as</i> the wolf rather than as a floating camera. That is why the
 * mode hides the mob in first person and the player in third, and the two
 * together are what makes it feel like a takeover rather than a puppet show.</p>
 *
 * <p>The id is cleared on disconnect. An entity id is only meaningful within one
 * connection, and a stale one would hide whatever entity inherited the number
 * on the next server — a bug that would look like random invisible mobs.</p>
 */
public final class DrivenView {

    /** The entity this client is driving, or {@link DrivenPayload#NONE}. */
    private static int driven = DrivenPayload.NONE;

    public static void accept(DrivenPayload payload) {
        driven = payload.entityId();
    }

    public static boolean driving() {
        return driven != DrivenPayload.NONE;
    }

    static void forget() {
        driven = DrivenPayload.NONE;
    }

    /**
     * <p><b>Matched by position and type, not by id, and not by choice.</b>
     * 1.21.11 rebuilt entity rendering around render <i>states</i>: the event
     * carries a {@code LivingEntityRenderState} with a type, a position and a
     * bounding box, and no entity and no id at all. So the driven entity is
     * resolved from the level by the id the server sent, and the state is
     * matched against <em>its</em> position and type.</p>
     *
     * <p>The false positive that shape allows is another creature of the same
     * type standing within a third of a block of the one being driven — which
     * is to say, inside it. If that ever happens the wrong one is hidden for a
     * frame, and since the driven mob is where the camera is, the two are
     * indistinguishable on screen anyway.</p>
     */
    @SubscribeEvent
    static void onRenderLiving(RenderLivingEvent.Pre<?, ?, ?> event) {
        if (driven == DrivenPayload.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        // Third person wants the creature drawn: it stands where the player's
        // body would be, which is the whole illusion.
        if (!mc.options.getCameraType().isFirstPerson()) return;
        if (mc.level == null) return;

        var entity = mc.level.getEntity(driven);
        if (entity == null) return;

        var state = event.getRenderState();
        if (state.entityType != entity.getType()) return;
        double dx = state.x - entity.getX();
        double dy = state.y - entity.getY();
        double dz = state.z - entity.getZ();
        if (dx * dx + dy * dy + dz * dz > 0.12D) return;   // ~0.35 blocks

        event.setCanceled(true);
    }

    /**
     * And in third person, hide the DRIVER instead.
     *
     * <p>Without this, third person shows your own body standing exactly where
     * the creature is, so you get a human and a wolf occupying one spot and the
     * illusion collapses — which is what the first live test showed: a player
     * model, no wolf, and no sense of playing as anything.</p>
     *
     * <p>The pair is the whole trick. First person hides the creature you are
     * inside; third person hides the person inside it. Either way what is on
     * screen is the creature, which is what the room sees too.</p>
     *
     * <p>Matched by position against the local player, since an avatar render
     * state carries no identity either — and hiding <em>other</em> players
     * would be a straightforward bug rather than a cosmetic one.</p>
     */
    @SubscribeEvent
    static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        if (driven == DrivenPayload.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.getCameraType().isFirstPerson()) return;
        if (mc.player == null) return;

        var state = event.getRenderState();
        double dx = state.x - mc.player.getX();
        double dy = state.y - mc.player.getY();
        double dz = state.z - mc.player.getZ();
        if (dx * dx + dy * dy + dz * dz > 0.12D) return;

        event.setCanceled(true);
    }

    /**
     * Put the creature exactly where the camera is, every tick, on this client.
     *
     * <p><b>The other half of "I seem to sort of drift".</b> An entity moved by
     * the server is rendered by lerping toward each position update over
     * several ticks, because that is what makes ordinary mobs look smooth over
     * a network. Your own body is not — the client predicts it immediately. So
     * a creature the server is snapping onto you renders a few ticks <em>behind
     * you</em>, and in third person, where the creature is the only thing on
     * screen, that reads as sliding around on ice.</p>
     *
     * <p>No amount of server-side care fixes that: the lag is added after the
     * position arrives, by the client, on purpose. The client is therefore the
     * only place it can be removed — and it can be removed completely, because
     * this client already knows where the creature is going to be. It is going
     * where the player is.</p>
     *
     * <p>The previous positions are copied too, not just the current one. The
     * renderer interpolates between them for sub-tick smoothness, so copying
     * only the current position would leave the creature interpolating from
     * where it used to be — the same smear, one tick long instead of three.</p>
     *
     * <p>Purely local. Nothing is sent, and no other client is affected: to the
     * rest of the room this is an ordinary mob moving at ordinary network
     * smoothness, which is exactly right, because they are not standing in
     * it.</p>
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (driven == DrivenPayload.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var entity = mc.level.getEntity(driven);
        if (entity == null || entity == mc.player) return;

        entity.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        entity.xOld = mc.player.xOld;
        entity.yOld = mc.player.yOld;
        entity.zOld = mc.player.zOld;
        entity.setYRot(mc.player.getYRot());
        entity.setXRot(mc.player.getXRot());
        entity.yRotO = mc.player.yRotO;
        entity.xRotO = mc.player.xRotO;

        // Body and head follow the look direction rather than a steered facing:
        // when you ARE the creature, where you look is where it faces, and a
        // body lagging its own head is the tell that something is driving it.
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            living.setYBodyRot(mc.player.getYRot());
            living.setYHeadRot(mc.player.getYRot());
            living.yBodyRotO = mc.player.yRotO;
            living.yHeadRotO = mc.player.yRotO;
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        forget();
    }

    private DrivenView() {}
}
