package com.sablednah.storyteller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sablednah.storyteller.neoforge.ActionsSupport;
import com.sablednah.storyteller.neoforge.CastSupport;
import com.sablednah.storyteller.neoforge.STCommands;
import com.sablednah.storyteller.neoforge.STPermissions;
import com.sablednah.storyteller.neoforge.STServerEvents;
import com.sablednah.storyteller.state.STAttachments;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * A live GM's toolkit for a server of LegendQuest characters.
 *
 * <p><b>Only the server and the Storyteller need this mod.</b> Everyone else
 * plays with LegendQuest alone — or with nothing at all, since LegendQuest is
 * server-authoritative. That is a hard design constraint, not an aspiration:
 * every effect an audience perceives has to arrive through something a vanilla
 * client already understands — real entities, chat, titles, the action bar,
 * sounds, display entities. A client-side StoryTeller mod is a control surface
 * for the person running the scene, and never a requirement for the scene to
 * be witnessed.</p>
 *
 * <p>LegendQuest is a hard dependency and this mod is deliberately thin on top
 * of it: an NPC is a LegendQuest character sheet, a reward is LegendQuest XP,
 * a party is a LegendQuest party. Standards, when present, adds the economy
 * and vanish; without it those two tools say so plainly and everything else
 * carries on.</p>
 */
@Mod(StoryTeller.MODID)
public class StoryTeller {

    public static final String MODID = "storyteller";
    public static final Logger LOGGER = LoggerFactory.getLogger("StoryTeller");

    public StoryTeller(IEventBus modEventBus, ModContainer container) {
        STAttachments.register(modEventBus);
        NeoForge.EVENT_BUS.register(STCommands.class);
        NeoForge.EVENT_BUS.register(STPermissions.class);
        NeoForge.EVENT_BUS.register(STServerEvents.class);
        // The guard has to sit out here, not inside CastSupport: naming a
        // class is what loads it, so registering it unguarded would be a
        // NoClassDefFoundError on every server without Cast. Same rule
        // LegendQuest keeps for ChatSupport, and this mod for EconomySupport.
        // Standards renders these as a drawn bar for a modded client and as
        // clickable chat for a vanilla one, so this costs the audience nothing.
        // Wrapped as well as guarded: an older Standards has no actions API,
        // and losing the buttons must not cost anyone the whole mod.
        if (ModList.get().isLoaded("standards")) {
            try {
                ActionsSupport.register();
            } catch (LinkageError older) {
                LOGGER.info("Standards on this server has no actions API, so there are no "
                        + "Storyteller buttons. Every command still works; update Standards for the bar.");
            }
        }
        if (ModList.get().isLoaded("cast")) {
            NeoForge.EVENT_BUS.register(CastSupport.class);
            LOGGER.info("Cast found — NPC bodies can be possessed and spoken through");
        }
        // The build, not just the version: says what RAN when somebody reports
        // a bug. Shared format across Sable's mods.
        LOGGER.info("LegendQuest StoryTeller {} initialising", BuildInfo.describe());
    }
}
