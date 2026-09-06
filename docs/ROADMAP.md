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

## 3. The cast

- Spawn palette: plain mobs, or full LegendQuest characters (race × class ×
  level gets stats, skills, gear rules and a nameplate free from the
  registries).
- Preset behaviours: guard, patrol, follow, flee, merchant, quest-giver,
  ambusher.
- Save an NPC as a reusable cast member.
- **`frequency` finally gets its job.** The field is parsed by LegendQuest
  today and consumed by nothing; it was always meant for weighting a random
  population. A city district rolls its inhabitants against it — humans
  common, tieflings rare, one gnome if you are lucky.

## 4. Set dressing

- A structure library placed live from vanilla `.nbt` templates — no
  proprietary format, so anything that can export a structure block can dress
  a scene.
- **Clean removal.** Record what was overwritten so a set can be *struck* as
  well as placed. A library you can only add from fills a world with
  abandoned scenery.
- CityWorld-aware placement, since that sibling mod knows what a plot is.

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
