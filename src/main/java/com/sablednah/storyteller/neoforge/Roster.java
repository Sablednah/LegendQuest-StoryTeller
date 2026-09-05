package com.sablednah.storyteller.neoforge;

import java.util.List;

import com.sablednah.legendquest.character.PlayerCharacter;
import com.sablednah.legendquest.data.CharClass;
import com.sablednah.legendquest.data.Race;
import com.sablednah.legendquest.neoforge.CharacterService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is in the scene, and what shape they are in.
 *
 * <p>A GM's most-used tool is not a spawner, it is knowing where everybody is
 * and whether anyone is about to die. All of this comes from LegendQuest's own
 * character service, so the roster can never disagree with the character
 * sheet — there is no second copy of the truth here.</p>
 */
public final class Roster {

    /** One line of the roster, already formatted for chat or a panel row. */
    public record Entry(String name, String race, String charClass, int level, String karma,
            int health, int maxHealth, int mana, int maxMana,
            String dimension, int x, int y, int z, String party) {}

    public static List<Entry> of(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream().map(Roster::entry).toList();
    }

    public static Entry entry(ServerPlayer player) {
        PlayerCharacter pc = CharacterService.data(player);
        String party = com.sablednah.legendquest.neoforge.Parties.get(player.level().getServer())
                .partyOf(player.getUUID())
                .map(p -> p.name())
                .orElse("");
        return new Entry(
                player.getName().getString(),
                CharacterService.race(player).map(Race::name).orElse("—"),
                CharacterService.mainClass(player).map(CharClass::name).orElse("—"),
                CharacterService.level(player),
                CharacterService.karmaName(pc.karma()),
                (int) Math.ceil(player.getHealth()), (int) Math.ceil(player.getMaxHealth()),
                (int) pc.mana(), (int) CharacterService.maxMana(player),
                player.level().dimension().identifier().getPath(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                party);
    }

    /**
     * The roster as coloured chat, for a vanilla client. Health is the only
     * thing coloured by value: it is the one number a GM has to react to
     * mid-scene, and scanning six identical lines for it is exactly the
     * "make me think" this is supposed to prevent.
     */
    public static String render(List<Entry> entries) {
        if (entries.isEmpty()) return "&7Nobody is online.";
        StringBuilder sb = new StringBuilder("&6Roster &8(" + entries.size() + ")");
        for (Entry e : entries) {
            String hp = e.health() * 4 <= e.maxHealth() ? "&c"
                    : e.health() * 2 <= e.maxHealth() ? "&e" : "&a";
            sb.append("\n &7-&r &f").append(e.name())
              .append(" &7").append(e.race()).append(' ').append(e.charClass())
              .append(" &8L").append(e.level())
              .append(' ').append(hp).append(e.health()).append('/').append(e.maxHealth()).append("&8hp");
            if (e.maxMana() > 0) {
                sb.append(" &9").append(e.mana()).append('/').append(e.maxMana()).append("&8mp");
            }
            if (!e.party().isEmpty()) sb.append(" &d[").append(e.party()).append(']');
            sb.append(" &8@ ").append(e.dimension())
              .append(' ').append(e.x()).append(' ').append(e.y()).append(' ').append(e.z());
        }
        return sb.toString();
    }

    private Roster() {}
}
