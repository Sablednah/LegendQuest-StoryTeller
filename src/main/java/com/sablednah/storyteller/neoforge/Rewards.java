package com.sablednah.storyteller.neoforge;

import java.util.ArrayList;
import java.util.List;

import java.util.Optional;

import com.sablednah.legendquest.character.PlayerCharacter;
import com.sablednah.legendquest.neoforge.CharacterService;
import com.sablednah.legendquest.neoforge.Parties;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
    public record Packet(long xp, int levels, int skillPoints, long karma, double money,
            Optional<Holder<Item>> item, int itemCount,
            Optional<String> standing, int reputation) {
        public boolean isEmpty() {
            return xp == 0 && levels == 0 && skillPoints == 0 && karma == 0 && money == 0
                    && item.isEmpty() && standing.isEmpty();
        }

        /** The five-currency shorthand, for callers with nothing to give but
         *  numbers — every reward command but {@code item} builds one this way. */
        public static Packet currency(long xp, int levels, int skillPoints, long karma, double money) {
            return new Packet(xp, levels, skillPoints, karma, money, Optional.empty(), 0,
                    Optional.empty(), 0);
        }

        public static Packet of(Holder<Item> item, int count) {
            return new Packet(0, 0, 0, 0, 0, Optional.of(item), count, Optional.empty(), 0);
        }

        /** Standing on one named track. Kept apart from {@link #currency}
         *  because it needs a track name as well as a number, and because
         *  reputation is Standards' ledger rather than LegendQuest's karma. */
        public static Packet standing(String track, int delta) {
            return new Packet(0, 0, 0, 0, 0, Optional.empty(), 0, Optional.of(track), delta);
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

        if (packet.standing().isPresent() && packet.reputation() != 0) {
            String track = packet.standing().get();
            // Two questions, not one: Standards being installed is not the same
            // as the server having a reputation provider registered.
            if (!ModList.get().isLoaded("standards")) {
                refused.add("reputation (no Standards on this server)");
            } else if (!ReputationSupport.available()) {
                refused.add("reputation (Standards has no reputation provider)");
            } else {
                int now = ReputationSupport.adjust(player.getUUID(), track, packet.reputation(),
                        reason.isBlank() ? "storyteller" : reason);
                granted.add((packet.reputation() > 0 ? "+" : "") + packet.reputation()
                        + " " + track + " reputation (now " + now + ")");
            }
        }

        if (packet.item().isPresent()) {
            ItemStack stack = new ItemStack(packet.item().get(), Math.max(1, packet.itemCount()));
            String label = packet.itemCount() + "x " + stack.getHoverName().getString();
            boolean fit = player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                // Inventory.add drains the stack as it fills slots; whatever is
                // left over goes at their feet rather than vanishing, so a full
                // inventory costs a pickup, not the reward itself.
                player.drop(stack, false);
            }
            granted.add(label + (fit ? "" : " (dropped — inventory was full)"));
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
