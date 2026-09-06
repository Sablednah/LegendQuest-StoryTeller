package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.legendquest.character.PlayerCharacter;
import com.sablednah.legendquest.neoforge.CharacterService;
import com.sablednah.legendquest.neoforge.Parties;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Handing out the spoils.
 *
 * <p>Two rules shape this whole class.</p>
 *
 * <p><b>The recipient is told, always.</b> A GM awarding 500 XP in a panel and
 * a player noticing their level changed twenty minutes later is a reward that
 * did not land — the moment of being rewarded <em>is</em> the reward. So every
 * grant here says so to the player, in chat, immediately.</p>
 *
 * <p><b>A party is the natural unit.</b> LegendQuest already knows who adventured
 * together, and a GM who has to award four people one at a time will award
 * three and miss one. {@link #party} exists so the common case is the easy
 * one.</p>
 */
public final class Rewards {

    /** What a single grant did, so the Storyteller gets one honest summary. */
    public record Result(String player, List<String> granted, List<String> refused) {}

    /**
     * One reward, applied as a unit. Any field left at zero/empty is skipped,
     * so "just money" and "the full quest payout" are the same call.
     */
    public record Packet(long xp, int levels, int skillPoints, long karma, double money) {
        public boolean isEmpty() {
            return xp == 0 && levels == 0 && skillPoints == 0 && karma == 0 && money == 0;
        }
    }

    public static Result give(ServerPlayer player, Packet packet, String reason) {
        List<String> granted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        PlayerCharacter pc = CharacterService.data(player);

        if (packet.xp() != 0) {
            // XP is banked per class, so a character with no class has nowhere
            // to put it. Saying so beats silently swallowing the award.
            pc.mainClassId().ifPresentOrElse(cls -> {
                int before = CharacterService.level(player);
                pc.addXp(cls, packet.xp());
                CharacterService.afterXpChange(player, before);
                granted.add(packet.xp() + " XP");
            }, () -> refused.add("XP (no class yet)"));
        }

        if (packet.levels() != 0) {
            // addLevels rather than setLevel: a level awarded should not cost
            // the character the progress they had made towards the next one.
            if (CharacterService.addLevels(player, packet.levels())) {
                granted.add((packet.levels() > 0 ? "+" : "") + packet.levels() + " level"
                        + (Math.abs(packet.levels()) == 1 ? "" : "s"));
            } else {
                refused.add("levels (no class yet)");
            }
        }

        if (packet.skillPoints() != 0) {
            pc.grantSkillPoints(packet.skillPoints());
            granted.add((packet.skillPoints() > 0 ? "+" : "") + packet.skillPoints()
                    + " skill point" + (Math.abs(packet.skillPoints()) == 1 ? "" : "s"));
        }

        if (packet.karma() != 0) {
            pc.addKarma(packet.karma());
            granted.add((packet.karma() > 0 ? "+" : "") + packet.karma() + " karma");
        }

        if (packet.money() != 0) {
            // Two questions, not one: Standards being installed is not the same
            // as the server having an economy provider registered.
            if (!ModList.get().isLoaded("standards")) {
                refused.add("money (no Standards on this server)");
            } else if (!EconomySupport.available()) {
                refused.add("money (Standards has no economy provider)");
            } else if (EconomySupport.deposit(player.getUUID(), packet.money(), reason)) {
                granted.add(EconomySupport.format(packet.money()));
            } else {
                refused.add("money (the transaction was refused)");
            }
        }

        if (!granted.isEmpty()) {
            Feedback.chat(player, "&6You are rewarded: &f" + String.join("&7, &f", granted)
                    + (reason.isBlank() ? "" : " &7— " + reason));
        }
        return new Result(player.getName().getString(), granted, refused);
    }

    /**
     * Reward everyone in that player's party, or just them if they are not in
     * one. Offline members are skipped and reported rather than silently
     * dropped — "the whole party got it" has to be true or it is worse than
     * awarding one at a time.
     */
    public static List<Result> party(ServerPlayer anyMember, Packet packet, String reason) {
        var party = Parties.get(anyMember.level().getServer()).partyOf(anyMember.getUUID());
        if (party.isEmpty()) return List.of(give(anyMember, packet, reason));

        List<Result> results = new ArrayList<>();
        for (var memberId : party.get().members()) {
            ServerPlayer member = anyMember.level().getServer().getPlayerList().getPlayer(memberId);
            if (member == null) {
                results.add(new Result(memberId.toString().substring(0, 8) + "…",
                        List.of(), List.of("offline")));
                continue;
            }
            results.add(give(member, packet, reason));
        }
        return results;
    }

    private Rewards() {}
}
