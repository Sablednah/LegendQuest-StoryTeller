package com.sablednah.storyteller.client;

import com.sablednah.storyteller.network.DrivenPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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

    /**
     * The client-side body we switched physics off on, and what it was before,
     * so release gives back exactly what it took. See {@link #onClientTick}.
     */
    private static net.minecraft.world.entity.Entity body;
    private static boolean bodyHadNoPhysics;

    public static void accept(DrivenPayload payload) {
        if (payload.entityId() != driven) letGo();
        driven = payload.entityId();
    }

    /** Hand the body its own physics back. Safe to call with nothing held. */
    private static void letGo() {
        if (body != null) body.noPhysics = bodyHadNoPhysics;
        body = null;
    }

    public static boolean driving() {
        return driven != DrivenPayload.NONE;
    }

    static void forget() {
        driven = DrivenPayload.NONE;
        // Not letGo(): on logout the level and every entity in it are being
        // thrown away, so there is nothing worth restoring and no promise that
        // the object is still in a sane state to write to.
        body = null;
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
        // Avatars carry an id, so they are decided by identity in
        // onRenderPlayer rather than guessed at here. RenderPlayerEvent is a
        // subclass of this event, so without this line both handlers would
        // have an opinion about the same render.
        if (event.getRenderState() instanceof AvatarRenderState) return;
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
     * Avatars, decided by id: hide the driver, never the body.
     *
     * <p><b>Why this is not position matching any more.</b> A Cast human body
     * is itself a player-shaped entity, and driving stands it in exactly the
     * spot the driver occupies — so "hide the avatar at my position" hid the
     * driver <em>and</em> the character they had become, and third person
     * showed an empty world with a shadow in it. Two things in one place
     * cannot be told apart by where they are.</p>
     *
     * <p>{@link AvatarRenderState} carries {@code id}, set from
     * {@code entity.getId()}, which is the identity the mob states simply do
     * not have. So this asks the only question that has a right answer: is
     * this render me, or is it the body I am wearing?</p>
     *
     * <p>The two directions are what makes driving read as a takeover. First
     * person hides the body you are standing inside, because otherwise it fills
     * the screen. Third person hides <em>you</em>, so what stands where your
     * body would be is the character — which is also what the room sees.</p>
     */
    @SubscribeEvent
    static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        if (driven == DrivenPayload.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int id = event.getRenderState().id;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();

        if (id == driven) {
            // The body being driven. Hidden from inside, drawn from outside.
            if (firstPerson) event.setCanceled(true);
            return;
        }
        // The driver's own body, and only in third person -- vanilla does not
        // draw your avatar in first person anyway, and hiding any OTHER player
        // would be a plain bug rather than a cosmetic one.
        if (id == mc.player.getId() && !firstPerson) event.setCanceled(true);
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

        // ⚠ THE ICE. The body's own CLIENT tick was shoving the driver.
        //
        // Vanilla runs pushEntities() in every living entity's aiStep, on the
        // client too, and on the client EntitySelector.pushableBy admits exactly
        // one candidate: the local player. That is deliberate -- the client is
        // authoritative for its own player, so a mob has to push you from your
        // side or it cannot push you at all. Entity.push skips a pair only when
        // either has noPhysics, and the server's `mob.noPhysics = true` is never
        // synced, so this copy of the body went on pushing.
        //
        // It explains all four things Sable saw, which is how it was found --
        // by him, from the symptoms, before anyone read the code:
        //  - Standing still, nothing. This method pins the body exactly onto
        //    you, and push ignores offsets under 0.01.
        //  - Move a hair and you slide. The body is where you were at the last
        //    pin, so there is an offset; push normalises it to a fixed shove
        //    however small it was; you move; the pin lags again. A loop.
        //  - Release and you shoot off the way you were drifting: the body is
        //    behind you, and the shove is always away from it.
        //  - Leave while still and you are nudged out -- the pin stops, so the
        //    next offset finally resolves.
        //
        // The earlier test that ruled push out was right about what it tested:
        // a SERVER-side push on a player really does nothing. It could not see
        // this one, because an undriven cow walks out of your block and is
        // never pinned back into it.
        //
        // Every tick rather than once: a body that leaves tracking and comes
        // back is a new object under the same id, and it would arrive with its
        // physics on.
        if (entity != body) {
            letGo();
            body = entity;
            bodyHadNoPhysics = entity.noPhysics;
        }
        entity.noPhysics = true;

        // Cancel any interpolation FIRST, or it undoes this every tick.
        //
        // A move or teleport packet does not set a position, it starts a lerp:
        // InterpolationHandler.interpolate() runs on the entity's own tick and
        // calls setPos() itself, stepping toward a target. Worse for us, it
        // measures how far the entity moved by other means since last tick and
        // ADDS that delta to its own target -- so puppeting the entity feeds
        // the thing that is fighting the puppet, and the body chases the camera
        // at a fixed lag instead of being pinned to it. That is a slide.
        //
        // It matters most for a cast body: Cast moves its NPCs with snapTo on
        // every tick of a drive, so there is always an interpolation in flight.
        // Nullable, and vanilla null-checks it too.
        var interpolation = entity.getInterpolation();
        if (interpolation != null) interpolation.cancel();

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
