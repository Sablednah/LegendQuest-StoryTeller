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

**Two forms, because a vanilla client cannot give both.** `/st possess` steers
— you keep your own grounded body and the creature walks to where you walk.
`/st possess eyes` binds
the camera and you ride along seeing what it sees, while it lives its own
life. You cannot have eyes and control at once: a client stops sending
movement entirely while spectating an entity (`LocalPlayer.sendPosition` is
gated on `isControlledCamera()`), and `ServerPlayer` snaps a spectator onto
its camera entity — rotation included — every tick, so a bound camera costs
the Storyteller both walking and looking.

This was found the hard way. The first version bound the camera *and* expected
the drifting body to steer, which the code asserted confidently in three
places and which was never possible; the first attempt to steer a cow did
nothing at all. Vanilla also ends a bound camera silently when the player
sneaks (`wantsToStopRiding` → `setCamera(this)`), so possession now watches
for that and releases cleanly rather than leaving a Storyteller speaking
through a creature they can no longer see.

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

**Known limitation:** `behave` does nothing on a *brain-driven* mob. It held a
plain Pig inside its radius every time tested; a Villager wandered off on its
own schedule.

The reason is not priority. `Villager.java` contains **no references to
`goalSelector` at all** and ticks its `Brain` in `customServerAiStep()`. Goal
flags (MOVE/LOOK/JUMP/TARGET) only arbitrate *between goals* — a Brain is not a
goal and never asks the flag system for permission. So on these mobs our goal
is not outranked, it is irrelevant, and raising its priority would change
nothing.

The 20 brain-driven classes in 21.11 are Allay, Armadillo, Axolotl, Breeze,
Camel, CopperGolem, Creaking, Frog, Goat, HappyGhast, Hoglin, Nautilus, Piglin,
PiglinBrute, Sniffer, Tadpole, Villager, Warden, Zoglin and ZombieNautilus.
**That list grows every few versions** — five of those are recent arrivals — so
a hardcoded exclusion list would rot. Detect at runtime instead.

This also makes `/st cast citizen` the awkward case: a Villager is the obvious
body for a person and the one body `behave` cannot hold. Reserve GUARD/PATROL
for non-villager cast members until the Brain is handled, and note that parking
a Brain is *harder to undo* than parking goals — a `Brain` is built by
`brainProvider()` at construction, so gutting one has the same one-way problem
as `removeAllGoals`.

**Untested, predicted from the above:** possessing a Villager should fight
itself — `PossessionGoal`'s `navigation.moveTo` against the brain's own
movement, and our rotation mirroring against the brain's look behaviour. Not
yet observed; flagged rather than assumed.

**Spectator is for surveying, not for steering.** Possession used to drop the
Storyteller into spectator; a play test showed that is the wrong tool. A
spectator flies, so the led body gets walked into the air and bounces on the
way; it noclips, so the body follows it underground; and vanilla repurposes a
spectator's own inputs — clicking an entity re-binds the camera, sneaking
unbinds it — so the mode fights the feature continuously.

The division now is that spectator (`/st drift`) keeps the godlike survey it is
good at — through walls, over rooftops, jumping between players — and
possession leaves the Storyteller grounded so the creature is following
somewhere it can actually go. Being *unseen* is vanish's job rather than
spectator's, and possession now hides the Storyteller through Standards for as
long as they wear a body.

It takes a **keyed hold** (`storyteller:possess`) rather than setting a
boolean. A player stays hidden while any hold stands and each caller releases
only its own key, so this never reads the state first and never reasons about
who else is involved — and a Storyteller who typed `/vanish` before the scene
is still hidden after it, because the command is itself a holder under its own
key. The read-then-set version would have revealed them; the shape removes that
bug rather than avoiding it. Releasing reports whether they are genuinely back
in view, so "you are still hidden — that is your own /vanish, not this" is said
when it is true rather than assumed either way.

Vanish guarantees, asked rather than assumed: hidden from players, not
pushable, no item pickup, not targeted by mobs, still solid against blocks and
still subject to gravity. Mob targeting was **not** covered until this was
asked, and clearing a target still cannot un-anger something already hunting
you — vanishing is walking away from a fight, not undoing one.

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

## The NPC mod — proposed, awaiting Sable's decision

**Nothing here is agreed work.** Chronicler's session and this one converged on
a design for a third mod owning NPC entities; the name, and who builds it, are
Sable's call. Recorded so both repos say the same thing. Working name **Cast**,
mod id `cast`, MIT, its own repo, **depending on nothing** — so Chronicler can
have quest-giver NPCs with no LegendQuest installed, and StoryTeller can drive
the same NPCs when it is.

**Why a third mod rather than growing this one.** It is the same discipline
already in force here: exactly one class imports each optional dependency,
behind a `ModList.isLoaded` guard sitting *outside* it. `EconomySupport` and
`CityWorldSupport` are the existing examples. An NPC that neither mod is
required to own fits that shape.

### Bodies

- **HUMAN** is a *real* server entity, not a packet-only phantom — so it is
  visible to a ray, bindable as a camera target, and hit by interaction events.
  Profile UUID derived from the `npcId`; the signed skin textures property
  copied from whichever account the skin names, since the signature covers the
  value rather than the wearer.
- **MOB** includes brain-driven bodies — **Villager NPCs are a requirement**,
  so Cast neutralises a Brain rather than refusing one. The classifier is
  vanilla's own `Brain.isBrainDead()` (memories, sensors and behaviours all
  empty), so nothing hand-rolled can drift; the 20-class list above is the
  self-test fixture. Note it is a *spawn-time* classifier — it still reads
  false after the behaviours are stripped.
- Neutralising is one of two, chosen per NPC: `setNoAi(true)` cuts the branch
  above the brain tick (`isEffectiveAi()` gates `serverAiStep`, which is what
  calls `customServerAiStep` and therefore `Brain.tick`) and persists as the
  `NoAI` save tag — but it stops `navigation` and `moveControl` too, so the
  body only moves when Cast moves it. For a body that must walk, strip the
  behaviours instead (`removeAllBehaviors`, `clearMemories`, `setSchedule`)
  and leave navigation alive.
- Cast owns MOB bodies **from spawn**, so it builds their goals from its own
  spec outright. That is ownership, and it is why it may be one-way — it is
  explicitly *not* the reversible parking that possession needs.

### Possession stays here

It has to work on wild mobs with Cast absent, so it cannot move out of this
mod. Cast never parks anything it does not own. What Cast provides instead:
`Npc.canPossess()`, `Npc.entity()` (the real entity to bind a camera to),
`Npc.drive(...)` for a HUMAN body with no navigation, and an
`NpcRemovedEvent(npcId, reason)` covering **death, unload and removal** — so
the camera goes home on every path, not only the one this mod already handles.
`Cast.isBrainDriven(Mob)` is exposed as a static so both refusals are one line;
when Cast is absent this mod keeps its own copy. **The check may live twice;
the parking never does.**

### Identity

Every NPC has an `npcId`, in a `SavedData` store (`Identifier` id on 26.x).
`Cast.byId`, `Npc.isLoaded()`, `Cast.isNpc(Entity)`, `Cast.npcAt(ray)` covering
both kinds, and a `remove(npcId)` that is idempotent and works while the NPC is
unloaded. Entities materialise on chunk load and are never saved as entities.

### Roles

`Cast.registerRole(Identifier, handler)`; an NPC carries roles, right-click
dispatches in order. **Cast NPCs have no LegendQuest character** — the
`/st cast citizen` boundary holds, and Cast never imports LegendQuest. If a
cast member ever needs a sheet, that is an attachment on this side, decided
deliberately.

### Open questions this mod should insist on before depending on it

1. **A real `ServerPlayer` added to the player list is counted as a player.**
   Sleep percentage, mob-spawning anchors and chunk loading, difficulty
   scaling, `/list` and the server player count are all driven by that list. A
   village of ten human NPCs that quietly makes it impossible to skip night is
   the exact "alarming and harmless" failure this project tries not to ship.
   Needs testing before the design is committed to, not after.
2. **A rejected or rotated skin signature must degrade to a default skin**, not
   fail the spawn.
3. **`Npc.drive` should move with collision, not teleport** — or say plainly
   that it teleports. Possession here paths deliberately, so that a possessed
   cow cannot scale a cliff the audience can see it could not climb.
4. **One canonical marker for "this is a cast NPC"**, readable by other mods.
   A mob's entity UUID does not survive rematerialising, and ZombieMod needs to
   read the same marker to know not to re-genus one.
5. **Role dispatch needs suppressing for a Storyteller**, who right-clicks NPCs
   to work on them rather than to talk to them.

### Noted for whoever builds it

This mod's `Possession.java` and `PossessionGoal.java` are the starting point
for anything that binds a camera — live-tested, including the death-release
path. The rotation mirroring there is deliberate: an earlier draft used the
look control, which aims the mob at its possessor and therefore points the
camera back at your own drifting body, fighting itself every tick.
