# StoryTeller — the toolset, and the order it gets built

The whole design answers one question: **what does a live GM actually reach
for mid-session?** Not "what could a mod do", but what a person running a table
needs within three seconds of needing it.

Everything is constrained by the rule in the README: only the server needs a
mod. A tool that cannot be perceived by a vanilla client is not a tool.

## 1. Presence, oversight, rewards — DONE (milestone 1)

- Drift (spectator) with an anchor, and a return that works even when the
  anchor is gone.
- Goto / next: look in on the table.
- Roster: who, what, how hurt, where, and in whose party.
- Rewards: XP, karma, money — singly or party-wide, with the recipient always
  told at the moment it lands.

Closed since: the drift anchor is now a persisted attachment, so a crash
mid-scene no longer strands anyone; levels and skill points are back in the
reward packet, on the back of new LegendQuest API
(`CharacterService.addLevels`, `PlayerCharacter.grantSkillPoints`); and
effects can be applied to a player or a party.

**Still open here:** item and loot-table rewards, and reward *packets* as
saved presets rather than one currency per command.

## 2. Possession and voice — DONE (the core of it)

Take over a creature, wear it, speak as it, give it back. Works on a vanilla
Storyteller client: camera binding and spectator mode are both server-driven.

**The prior art turned out to be a warning, not a template.** ZombieMod clears
a mob's goals outright (`removeAllGoals(g -> true)`), which is right for
rebuilding a mob permanently and wrong here — vanilla registers goals in
`registerGoals()` at construction and never again, so a cleared list cannot be
put back and a released goblin would not be a goblin. Possession instead adds
one goal at priority 0 holding MOVE, LOOK, JUMP and TARGET, starving the
mob's own goals of the flags they need without removing any. Release is one
`removeGoal`.

Two failure modes are closed deliberately: a Storyteller who logs out mid-
possession releases the creature (otherwise it is frozen forever following
somebody who is not there), and a possessed creature that dies hands back the
camera (otherwise they are watching through a corpse, which on a vanilla
client is a black screen they cannot escape from inside the game).

**Still to do here:**

- One-to-one *movement* via the Storyteller's client mod — strafe, jump and
  attack on their keypress rather than the creature pathing after them.
  Looking is already one-to-one and needs no client mod: the camera renders
  from the creature's orientation, so mirroring the possessor's rotation onto
  it every tick is mouse-look. Worth testing how that interacts with pathing,
  which turns a mob toward its own route as it walks.
- Narration that is not a possessed voice: title cards, scene text to a
  radius/party/server, a whisper to one player.
- Ambience: weather, time of day, a sound cue on the room.
- Narrate: title cards, scene text to a radius/party/server, a whisper to one
  player — a god-voice, a dream, an omen.
- Ambience: weather, time of day, a sound cue on the room.

Voice is worth building before spawning. A GM can already `/summon` a zombie;
what they cannot do is make it *say something*.

## 3. The cast — DONE (the honest version)

- `/st cast spawn <entity> [name]` — any mob, named or not.
- `/st cast citizen [race] [class]` — a named Villager, race and class rolled
  by weighted `frequency`, or pinned to a specific one. **This is flavour, not
  a character**: LegendQuest has no NPC entity of its own, so a citizen has no
  stats, skills or inventory rules behind its name. Stated plainly rather than
  overclaimed.
- `frequency` finally has a job — parsed by LegendQuest since day one, consumed
  by nothing until this. Verified live: three rolls produced Human Rogue,
  Human Mage and Elf Barbarian.
- `/st cast behave guard|patrol|follow <player>|flee|none` — a goal added
  *alongside* a mob's own, not instead of them, so a cast member still fights
  back or flinches from fire. Re-applying replaces rather than layers.
- `/st cast save|use|list` — a `SavedData` store, one per world save, mirroring
  LegendQuest's own `Parties`.

**Known limitation, found live-testing:** a plain Villager's own vanilla
schedule AI (seeking a bed, a workstation, socialising) can out-prioritise the
GUARD/PATROL goal here, since those run at a middling priority alongside a
mob's existing goals rather than replacing them. It stayed inside its radius
against a plain Pig every time tested; a Villager wandered further on its own
schedule in one observed run. Worth revisiting if citizens are meant to hold a
post reliably — either a higher priority for this mod's goal, or accepting
that citizens roam and reserving GUARD for non-villager cast members.

**Merchant/quest-giver/ambusher presets** are not built — they would need
actual interaction (trading, dialogue, an aggro trigger) beyond a movement
goal, which is GUI/story-planner territory more than a command-line preset.

## 4. Set dressing — DONE (vanilla + CityWorld, both with undo)

- `/st struct place <template> [rotate cw90|180|ccw90]` — any vanilla `.nbt`
  structure any loaded datapack declares, the same 1202-entry catalogue
  `/place template` draws from (counted live on the vanilla catalogue alone),
  reached through this mod's own permission instead of operator level 2.
- `/st struct library list|place` — CityWorld's `SchematicLibrary` as a second
  pool (`.schematic`/`.schem`/`.litematic`/`.nbt`) when CityWorld is installed;
  a plain, clear refusal when it is not.
- **Both give `/st undo` something CityWorld's own paste never had.** Every
  placement snapshots its block volume first and restores it on undo — proven
  live on a full building (`village/plains/houses/plains_small_house_1`),
  placed and taken back off cleanly.
- **Known limitation, stated rather than hidden:** undo restores block STATES
  only, not block-entity contents. A chest a structure overwrites comes back
  as an empty chest of the right kind, not with what was in it. Fine for
  dressing empty ground; not a promise for placing over someone's base.
- CityWorld-aware placement is done for its schematic library; plot-aware
  placement (asking CityWorld where a plot's boundary is) is not attempted.

## 5. The GUI, and the planner

Last, deliberately. Everything above is usable from a chat box first.

- Storyteller screen: roster, spawn palette, reward packets, scene cues.
- Story planner: ordered beats with triggers (location entered, mob slain,
  item obtained, time elapsed), fired manually or automatically.
- Private GM notes per scene, and a session log for the recap.

## Control and safety, threaded throughout

Not a milestone; each of these lands with the tool it protects.

- Freeze/thaw mob AI in an area, to hold a tableau.
- Mark a plot NPC invulnerable, so the story is not ended by one critical hit.
- Undo the last spawn, structure or grant.
- A panic button that removes everything spawned this scene.

## Reward packets

The unit a GM thinks in is not "500 XP", it is "they finished the smuggler
job". A packet — XP + money + karma + items under one name, applied to a party
in one action — is the shape the tool should take, and the command form should
stay the fallback rather than the primary.
