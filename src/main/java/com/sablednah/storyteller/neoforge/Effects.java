package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.legendquest.neoforge.Parties;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Blessing and cursing a scene.
 *
 * <p>Vanilla already has {@code /effect}, and this is not a reimplementation
 * of it — it is the same capability reached through the Storyteller's own
 * permission rather than through operator. A GM should be able to lay a curse
 * on someone without also being handed {@code /stop}, {@code /ban} and the
 * ability to edit any block on the server, which is what op costs today.</p>
 *
 * <p>The other difference is targeting: {@link #party} exists because scenes
 * happen to groups. Vanilla's answer is {@code @a[...]} selectors, and those
 * require operator level 2 to parse — so for the un-opped Storyteller this is
 * meant to serve, they are not an option at all.</p>
 *
 * <p>Effects land through {@code addEffect} exactly as a potion would, so a
 * vanilla client shows the icon, the particles and the timer with no help.</p>
 */
public final class Effects {

    /** What happened to one target, for a single honest summary line. */
    public record Result(String player, String describe) {}

    /**
     * @param seconds   duration; 0 or less is rejected by the caller
     * @param amplifier 0 = level I, as everywhere else in Minecraft
     * @param ambient   true hides the swirling particles — for a condition the
     *                  story imposes rather than a potion someone drank
     */
    public static Result apply(ServerPlayer target, Holder<MobEffect> effect,
            int seconds, int amplifier, boolean ambient) {
        target.addEffect(new MobEffectInstance(effect, seconds * 20, amplifier, ambient, !ambient, true));
        String name = effect.value().getDisplayName().getString();
        String level = amplifier > 0 ? " " + (amplifier + 1) : "";
        // The target is told. An effect that arrives unexplained is a bug
        // report; an effect that arrives named is a story beat.
        Feedback.chat(target, "&5You feel it: &f" + name + level + "&7 (" + seconds + "s)");
        return new Result(target.getName().getString(), name + level + " " + seconds + "s");
    }

    /** Lift one effect, or everything if {@code effect} is null. */
    public static Result clear(ServerPlayer target, Holder<MobEffect> effect) {
        if (effect == null) {
            target.removeAllEffects();
            Feedback.chat(target, "&aYou are yourself again.");
            return new Result(target.getName().getString(), "all effects cleared");
        }
        boolean had = target.removeEffect(effect);
        String name = effect.value().getDisplayName().getString();
        if (had) Feedback.chat(target, "&a" + name + " lifts.");
        return new Result(target.getName().getString(), had ? "cleared " + name : "had no " + name);
    }

    /**
     * Everyone in that player's party, or just them if they are not in one —
     * the same rule {@link Rewards#party} follows, so "party" means one thing
     * across the whole mod. Offline members are reported, not silently missed.
     */
    public static List<Result> party(ServerPlayer anyMember, Holder<MobEffect> effect,
            int seconds, int amplifier, boolean ambient) {
        var party = Parties.get(anyMember.level().getServer()).partyOf(anyMember.getUUID());
        if (party.isEmpty()) return List.of(apply(anyMember, effect, seconds, amplifier, ambient));

        List<Result> results = new ArrayList<>();
        for (var memberId : party.get().members()) {
            ServerPlayer member = anyMember.level().getServer().getPlayerList().getPlayer(memberId);
            if (member == null) {
                results.add(new Result(memberId.toString().substring(0, 8) + "…", "offline"));
                continue;
            }
            results.add(apply(member, effect, seconds, amplifier, ambient));
        }
        return results;
    }

    private Effects() {}
}
