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
- Rewards: XP, levels, skill points, karma, reputation, money and items —
  singly or party-wide, with the recipient always
  told at the moment it lands.

Closed since: the drift anchor is now a persisted attachment, so a crash
mid-scene no longer strands anyone; levels and skill points are back in the
reward packet, on the back of new LegendQuest API
(`CharacterService.addLevels`, `PlayerCharacter.grantSkillPoints`); and
effects can be applied to a player or a party.

Items landed too: `/st reward <player> item <item> [count]` takes anything in
the item registry, so modded loot works without this mod knowing about it.

**Still open here:** loot-table rewards, and reward *packets* as saved presets
rather than one currency per command.

## 2. Possession and voice — DONE (the core of it)

Take over a creature, wear it, speak as it, give it back. Works on a vanilla
Storyteller client: camera binding and spectator mode are both server-driven.

**Three forms, and `/st possess` picks between the first two itself.** With the
StoryTeller client mod it *drives* — you are the creature, on your own controls,
Cast NPCs included. Without it, it *steers* — you keep your own grounded body and
the creature walks to where you walk. `/st possess drive` and `/st possess steer`
name either one. `/st possess eyes` binds
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

**Targeting — DONE (`/st lock`).** Every creature command resolved its target
from the crosshair, which is the right default — no names, no ids, no selectors,
and pointing at a thing is the most natural gesture a GM has. It is also fragile
exactly when a scene gets busy: a costume is four commands and each wants the
head still, two mobs a block apart trade places under the crosshair as they
breathe, and walking a possessed cow across a room means looking where it should
go, which is by definition not at it. `/st lock` takes what is in the sights and
makes it the answer to "that one" until it is let go; the five places that asked
the crosshair now ask `Sights`, which asks the crosshair when no lock is held.

Three things it is careful about. It stores an **id, never the entity** — holding
the `Mob` would keep a removed one reachable and hand back a corpse as a target,
where resolving from an id means a target that has gone is *discovered* to have
gone. It **never fails silently**: death, unload, dimension change and Cast's own
removal all clear it and say which, because a lock left pointing at something
dead would redirect the next command to whatever the crosshair happened to find.
Cast's `REBODY` deliberately does *not* clear it — that keeps the `npcId` and
builds a new body under it, so the lock is still on the right character.

`/st say` resolves the lock but **does not** fall back to the crosshair: lending
your voice to whatever you happen to be looking at would put words in the mouth
of a creature nobody chose, and a line cannot be unread by the table. It does now
work without possession, so a locked shopkeeper can hold a conversation while
keeping its own behaviour — a whole scene run without taking an NPC's AI away.

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

**`behave` warns on a brain-driven mob** rather than refusing it. It briefly
refused them; a play test showed that was too coarse. A brain-driven mob still
ticks its goalSelector and targetSelector, so the goal does run — it competes
with a Brain issuing movement of its own, and who wins depends on how busy that
Brain is. Observed: a Villager ignores GUARD outright, goats and frogs flee
well enough to read as fleeing, a camel does not care. A plain Pig held its
radius every time.

So it applies the behaviour and says it may not hold. The defect was never that
it ran — it was that it claimed success it had not earned.

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
body for a person and the one that ignores a post most completely. Cast handles it
properly on its own bodies by stripping the behaviours outright, which it can
do because it owns them from spawn; note that parking a Brain is *harder to
undo* than parking goals — a `Brain` is built by
`brainProvider()` at construction, so gutting one has the same one-way problem
as `removeAllGoals`.

**A slime cannot be led**, and says so when you take one. It does not walk: it
moves by jumping, driven by its own goals through a move control that is
package-private and cannot be steered from outside. Possession starves those
goals of their flags, which stops the jumping without replacing it, so the
slime just sits. Magma cubes are the same. Eyes and voice both still work —
only the leading is impossible, so only the leading is refused.

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
asked. Vanishing also clears the target of anything already hunting within 64
blocks, so it is not only new targeting that is refused. What remains is
narrow: a blow already in flight lands, and a lit creeper still goes off.

Hiding needs **Standards 1.6.0 or newer**. It is not declared as a version
floor: Standards is an optional dependency, and a floor on an optional
dependency makes FML refuse to load this mod outright when an older one is
present — turning a feature that should quietly degrade into a server that
will not start. `VanishSupport` catches the `LinkageError` instead, says so
once in the log, and possession carries on without hiding anyone.

**Costume — DONE.** `/st cast equip <slot> <item>` dresses a cast NPC and
`/st cast worn` reads it back. The item is a greedy string rather than an item
argument, because Cast takes it exactly as `/give` writes it and a component
blob contains the brackets and quotes an item argument would eat. Cast NPCs
only: a wild creature's gear is its own, and dressing one would be a change this
mod could neither remember nor undo.

**Merchant/quest-giver/ambusher presets** are not built — they would need
actual interaction (trading, dialogue, an aggro trigger) beyond a movement
goal. The quest-giver half of that now exists in Chronicler — see section 5.

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

## Buttons — DONE (Standards actions)

Four actions registered with Standards 1.6.0: possess, lock, drift, next.
Standards draws them as a bar for its client half and as clickable chat
components for anyone without it, so **a vanilla Storyteller gets working
buttons** — which is the only reason this mod could adopt them.

It was five, paired: possess/release and drift/return, each pair spending two
slots on one idea and each second button only ever clickable when its partner
was not. Sable called that clunky and was right — the lit state already tells
you what the click will do. **So the commands toggle instead**, and the seam
needed nothing for it: an action still carries exactly one command string, and
the command is the thing that knows which direction it means. `/st release` and
`/st return` stay as the unambiguous forms for anyone typing deliberately and
for a macro that must not flip.

Each carries a command string rather than a payload, so a button is
indistinguishable from typing, and each reports *state* as well as
availability. The state half was asked for specifically: most of a day of
play-testing went on this mod and the game disagreeing about what was
happening, and a bar that shows "you are wearing a cow" catches that in the
moment.

Keybinds are the client half's to register and are unambiguously client-side —
a `KeyMapping` is registered before anything knows which server it is talking
to. They do **not** go through Standards' `ClientActions.run`, so they work
without Standards on the client at all: each key checks that the server's
command tree actually offers the command (`findNode`, on the tree the server
sent already filtered by permission) and sends it exactly as typed. Where it is
not offered the key does nothing, silently — a key brushed on a server that does
not offer the command should do nothing, because the player may not know it is
bound.

**The client half exists now**, and the three rules set down before it was
built all held:

- **Keys are registered UNBOUND.** Five of them — possess, lock, drift, next and
  summon — under Options → Controls → StoryTeller, and the Storyteller is told
  once, on joining, that they exist. A mod claiming letters on install is how
  conflicts start.
- **Everything version-sensitive on the client stays in a few small classes.**
  `STClient`, `STKeyMappings` and `DrivenView`, which only declines to draw the
  body you are driving — plus, since the ghost placer, `GhostRenderer` and
  `ClientText`, the two files each line rewrites. That is why each 26.x port of the client
  half has been a copy with one per-branch line rather than a hunt — 26.x reworked
  GUI rendering wholesale (`GuiGraphics` became `GuiGraphicsExtractor`, a screen's
  `render` became `extractRenderState`), and none of that is touched here yet.
- **The drawn bar was not proof until somebody looked.** It has been looked at
  now, in play: the bar renders and the buttons work, and the one layout fault
  found — a hint drawn over the item icon rather than only in the tooltip — was
  Standards' to fix and was reported there.

## 5. The screen, and where the planner went

Everything above is usable from a chat box first, and the Standards buttons and
keybinds (see Buttons, above) already cover the tools a Storyteller aims.

- **Storyteller screen — not built.** Roster, spawn palette, reward packets,
  scene cues. Like the buttons and keys, it must send `/st` commands rather
  than grow its own rules.
- **Story planner — shipped, as a separate mod.** Ordered beats with triggers
  (place entered, mob slain, item obtained, deadlines), choices and endings are
  what [Chronicler](https://github.com/Sablednah/Chronicler) 1.0.0 does. Its own
  design notes say it plainly: a live GM firing cues "is a quest with `on_enter`
  effects", so building a second engine here would be building the same thing
  twice.
- Private GM notes per scene, and a session log for the recap — not built.

### Next wave: fire Chronicler quests from the table

A library of short "mini" quests — an ambush, a lost item, a rival's dare —
kept in Chronicler, that the Storyteller can drop into a live session as it
happens rather than laying out in advance. Chronicler's `api/Quests` is already
the door, written with StoryTeller named as its first customer: `offer`,
`accept`, `isActive` / `isComplete`, and world `flag` / `setFlag`.

Sketch, not a design:

- `/st quest offer <player|party> <quest>` — the giver's offer (Accept/Info in
  chat) without a giver standing there; `accept` for a scene that should not ask.
- `/st quest list [filter]` — browse the library mid-scene; a button later.
- A **cast NPC as the giver**, so the offer comes from whoever the Storyteller is
  wearing, in that NPC's voice.
- Flags as scene state: `/st flag <name> on|off`, so an improvised choice can
  change what an authored quest later does.

Soft dependency, same pattern as Cast: one guarded `ChroniclerSupport` class,
and without Chronicler the subcommand says so and nothing else changes.

## Place a building by seeing it first — the ghost placer BUILT, the browser not

**Asked for by Sable on 2026-09-12**, MineColonies' build tool being the
reference for how it should feel. Two halves; the second is built.

- **A schematic browser — not built.** The structure library listed on the
  left, a rotatable preview on the right, and a **Place** button that hands
  over to the ghost placer rather than placing immediately.
- **A ghost placer — built, vanilla datapack structures.**
  `/st struct ghost <template>` shows the structure where it would stand,
  centred on the Storyteller's aim, and it is placed by
  `/st struct place <template> at <pos> [rotate ...]` — the command a vanilla
  Storyteller could type, so undo, permissions and feedback are unchanged.

Decided with Sable on 2026-09-14:

- **A vanilla Storyteller gets an outline**, not nothing. The server draws the
  footprint as dust particles only that player sees, with the front edge in
  gold, and steers it with `/st struct ghost rotate|nudge|hold|place|cancel` —
  sent by a row of chat buttons. The gold edge exists because a square footprint
  looks identical at every quarter turn, so a rotate would otherwise appear to
  do nothing.
- **Controls on the modded client:** aim moves it, scroll turns it,
  Shift+scroll or Page Up/Down raise and lower it, the arrow keys shift it
  relative to where you face ("offset up/down, side to side, for uneven
  terrain" — Sable's addition), left-click locks it in place so you can walk
  round it nudging it (again to follow the aim), right-click places, Esc puts it
  away. Left-click was "clear" and middle-click "hold" until Sable played it on
  2026-09-14 and asked for this; Esc is caught as the pause screen opens, and
  only while Esc is down, so losing focus does not cost the ghost. These six keys are the one exception to
  "registered unbound": their conflict context is the ghost itself, so outside a
  ghost they take no key from anyone.
- **Both lines from day one**, because drawing is exactly what 26.x rewrote.

How it is built:

- **One payload, and it carries blocks, not decisions.** `GhostPayload` is the
  structure's block-state ids and cells, read server-side through
  `StructureTemplate.save` (the one public view of a template's blocks on both
  lines). The client works out the position; `GhostMath` holds the arithmetic
  both the outline and the drawn ghost use, so "centred on my aim" means the
  same thing on both sides. Over `MAX_BLOCKS` (150,000) the server sends an
  outline instead.
- **Captured once, replayed each frame.** `GhostRenderer` renders every visible
  block into plain vertex arrays when the structure arrives or turns; a frame
  only copies them in at the current position. Blocks buried on all six sides
  are skipped. It is the one client class per line that draws:
  `RenderLevelStageEvent.AfterEntities` and `renderSingleBlock` on 1.21.11;
  `SubmitCustomGeometryEvent` and `ModelBlockRenderer.tesselateBlock` on 26.x,
  where `BlockRenderDispatcher` and `MultiBufferSource` no longer exist.
  `ClientText` is the other per-branch file (chat and action bar from the
  client).

**Seen, not inferred, on the 1.21.11 Vivo rig:** the ghost drawn on the ground
at the crosshair; a scroll turning it (front north to east) and Shift+scroll and
arrows moving it; right-click placing the real building in exactly the ghost's
spot and turn; `/st undo` restoring the snow and ice under it.

**And on the 26.2 Vivo rig, the same sequence, the same result** — drawn
translucent over water facing north, turned east, raised and shifted, placed
where it stood, undone back to water, no client errors. That settles the three
things the 26.2 port could only assert from source: `SubmitCustomGeometryEvent`
is the right door, the item-translucent pipeline really blends, and
`ModelBlockRenderer.tesselateBlock` into a capturing consumer yields the
building. mc26.1 carries the same renderer and compiles; it has not been watched.

Two things the rig caught before anyone played it: the ghost followed the gaze
only 64 blocks, so a Storyteller on a hilltop hit nothing (now 128); and a full
template id overflowed the action bar at both ends (it shows the short name now).
Both fixes compile on all three lines and were made after the rig runs, so
neither has been seen yet.

**What Sable's first real play found, all fixed the same day:**

- **No ghost at all under Sodium** — see StoryTeller CLAUDE.md, Known traps.
- **Placing carved.** A template's recorded air was placed, cutting the
  structure's whole box out of the terrain. Air is skipped now
  (`BlockIgnoreProcessor.AIR`), with `withair` to keep it.
- **Placing and undo were messy** — seeds popping on place, beds dropping on
  undo. Both were shape updates destroying blocks with drops
  (`Block.updateOrDestroy` drops unless `UPDATE_SUPPRESS_DROPS`). Placement now
  suppresses drops; undo restores with `UPDATE_KNOWN_SHAPE` too, so a bed half
  put back before its partner does not update and break, and skips block-entity
  side effects so a structure's own chests do not spill. The undo snapshot takes
  one block of margin so edge plants knocked off come back.
- CityWorld's paste sets its own flags, so a library building can still pop the
  odd seed as it lands; its undo is ours and is tidy.
- **Raw jigsaw blocks left in every village house.** A lone piece of an
  assembled structure keeps its connectors, because nothing assembles it.
  `JigsawReplacementProcessor` now swaps them for their `final_state` as world
  generation does (before the air filter, so an air final state goes too), and
  the ghost preview does the same reading. `withjigsaw` keeps them, in either
  order with `withair`.

**Not yet done here:**

- The **outline path has not been watched** — the rig client is modded, so it
  always gets the drawn ghost. Needs a vanilla client pointed at the rig.
- **CityWorld's library in the ghost — built, compiled, not yet watched.**
  `/st struct library ghost <name>` and `library place <name> [at <pos>]
  [rotate ...]`, on CityWorld 5.8.0's rotated whole-building `paste` and
  `saveTemplate()` (added at this mod's request). `CityWorldSupport.canTurnAndShow`
  asks the class for both methods rather than trusting a version; an unturned
  placement still uses the old paste, so an older CityWorld loses nothing it had.
  The ghost starts `GroundLevelY` blocks down (`GhostPayload.sinkY`), and `at`
  means the same template origin for both pools — CityWorld is handed the turned
  footprint's minimum corner, which is where its paste puts the north-west corner.
  Library names became a quotable string (none of the bundled ones has a space)
  so `at` and `rotate` can follow them.
- **Several palettes:** a template that picks a palette at random when placed
  (shipwrecks, some ruins) previews the first one, so the ghost is the right
  shape and possibly the wrong planks.
- **Block-entity renderers** (chests, beds, signs) draw little or nothing in the
  ghost; the building places them normally.

## Whole structures — BUILT, and watched on the 1.21.11 rig

**Asked for by Sable on 2026-09-14**, prompted by the jigsaw blocks: "spawning a
whole village or bastion or fortress would be useful — especially with undo."
Vanilla already assembles them for an operator — `/place structure <id>` places
a configured structure as world generation would — so the tool is that, reached
through the Storyteller permission, with the two things it has never had: a
preview of the assembly that will actually land, and an undo.

- `/st struct whole place <structure>` — every entry in the structure registry:
  villages, bastions, outposts, ancient cities, monuments, fortresses,
  strongholds, mansions. Bare, it is `/place structure` with an undo.
- `/st struct whole ghost <structure>` — the assembly shown where it would
  stand, steered by the same controls as a single building.
- `/st struct ghost reroll`, and a `[Reroll]` button — another assembly of the
  same structure on the same spot.

**The sketch this replaces was right about the shape and wrong about one
thing**, which is the entry worth keeping: it expected the combined box plus
margin to be what undo snapshots. That box is mostly the *untouched ground
between* a village's houses, so the snapshot is taken per piece instead —
cheaper by an order of magnitude and a better fit to what actually changed.

How it is built:

- **Deciding and building are separate, and that is what makes both possible.**
  `Structure.generate` works out every piece and its box without writing a
  block. So a roll can be drawn, held, walked round, and only then built — and
  the footprint is known before anything is overwritten, which is what undo
  snapshots.
- **A roll is a layout, pinned by a number.** Assembly is random, so a preview
  that re-rolled on placement would show a different village from the one that
  landed — the exact failure a preview exists to prevent. A layout is a function
  of the world seed, a roll index and the chunk it was rolled in, so the ghost
  sends all three back: `/st struct whole place <id> roll <n> <cx> <cz> at <x y
  z>`. The server regenerates the identical assembly and moves it to `at`.
- **The chunk is part of the pin for a reason.** Generation fits an assembly to
  the terrain of the chunk it rolls in — a village's houses each sit on the
  ground height where they land — so rolling at the chunk the Storyteller is
  looking at is the difference between houses on the ground and houses in the
  air.
- **Moving one is rigid.** Every piece carries its own template position and
  each kind overrides `move` to take it along, so the whole assembly shifts with
  the layout untouched. Nudging it never re-rolls it.
- **It does not turn**, and says so. A start's pieces carry their own final
  rotations with no setter, and vanilla has never offered it either. The status
  line reads "as generated" where a single building reads which way its front
  faces, the `[Reroll]` button takes the rotate buttons' slot, and on a modded
  client the scroll wheel raises and lowers instead — rather than leaving a
  control that looks broken.
- **Every structure draws.** `CaptureLevel` records what a structure lays down
  as it builds itself, so a fortress corridor and a village house are read the
  same way; reading the pieces' templates is the fallback for a structure whose
  chunks are not held open. A vanilla client still gets the **piece boxes** as
  its outline — a skeleton of the village rather than one rectangle round it.
- **Two different refusals, said differently.** "Too big to draw" and "nothing
  could be read" are not the same answer, and a Storyteller should not have to
  work out which they got. The second no longer means "built in code": that was
  the pre-recorder cause, and it is gone.
- **Undo snapshots the pieces**, as a palette and one index per block rather
  than an object per block — a village is a few hundred thousand blocks and the
  old per-block record costs some forty times as much each. Past about six
  million blocks the snapshot is given up on, and the placement *says so* rather
  than quietly leaving `/st undo` unable to help.
- **It also takes back what arrived with it.** A monument brings guardians;
  vanilla's own placement leaves every one of them behind. Anything inside the
  footprint after the placement that was not there before goes when the
  placement goes — the difference between "the village is gone" and "the village
  is gone and forty villagers are standing in a field".

**Seen, not inferred, on the 1.21.11 Vivo rig (2026-09-17):** a plains village
drawn as a translucent ghost over the terrain, 148 pieces on one roll and 199 on
another at a different spot; the chat line naming the controls with the
non-turning wording and the `[Reroll]` button beside it; `/st struct whole place
... roll 0 12 7 at 195 77 115` answering "Placed minecraft:village_plains, 91
pieces"; and `/st undo` answering "Undone: a structure (village_plains)".

**The undo measurement, with its control.** Seven villagers stood inside the
placement's box after it landed and none after the undo — while a villager
elsewhere in the world was still there, untouched. That second number is the
one that makes the first mean anything: it says the undo removed *what arrived
with the village*, not every villager it could reach.

**The client kept drawing a village that was already gone**, which reads exactly
like "undo did not restore the blocks" — while the server, asked directly, said
the path block was gone and the ground was back. The first explanation reached
for was software rendering lagging behind, and that was a guess, so it was
tested: a place-then-undo cycle with a screenshot and a block probe at every
step.

**The frames proved it, and named their own moment.** Each screenshot showed the
chat line from the *previous* step — the shot taken after the placement still
read "Ghost put away", and the one taken after the undo still read "Placed
minecraft:village_plains, 91 pieces". The rig renders in software and its frame
trails the server by seconds, world and chat together.

So: **on a slow client a screenshot is evidence about the client, not about the
world** — ask the server for the world. And the useful trick, worth stealing:
**the chat line visible in a frame timestamps that frame**, so a screenshot can
be placed against the sequence of commands instead of assumed current. A ghost
overlay muddies this further — one "village" in these shots turned out to be the
drawn ghost, which the action bar said plainly (`Ghost village_plains · as
generated`) for anyone reading it.

**A probe that lied, and what it cost.** Five points inside the assembly's box
were checked for air before the placement, after it and after the undo, and
read air every time — which looks exactly like "nothing was ever placed". The
points were in the empty ground *between* the houses: a village's bounding box
is mostly gaps, so its corner region says nothing about whether it landed. The
villager count and a screenshot from above settled it in one step each. Before
trusting a probe to report absence, give it a subject you know is present.

**Every structure draws now — parity, asked for by Sable on 2026-09-19.**
`CaptureLevel` is a `WorldGenLevel` that records writes instead of performing
them, so a structure is asked to *build itself* into a map and the ghost draws
what it laid down. That replaced reading templates as the primary path and
covers the five structures with no templates at all — buried treasure,
mineshaft, nether fortress, ocean monument, stronghold — which could previously
only ever be an outline. It is also more faithful than reading templates:
processors are applied, jigsaw blocks are already swapped, and every piece has
already made its own decisions about the terrain it is landing in.

**Measured on the 1.21.11 rig, 2026-09-19**, against a build whose stamp was
checked first: fortress 140 pieces, stronghold 180, village 99, End City 77 —
**all four drawn**, no fallbacks, and not one "captured nothing" diagnostic.
Safety measured with a probe that can only fire on a real leak — nether bricks
near the player, which cannot occur naturally in an overworld — zero before and
zero after raising a fortress ghost, across eight positions including a fence.

Two rules hold it honest, and both were found by needing them:

- **Reads answer from the capture first**, because pieces read back what they
  have just placed; forwarding every read would hand a piece a different world
  from the one it is building, and the preview would diverge from what lands.
- **Only chunks somebody already holds open**, because asking a `ServerLevel`
  for an unloaded chunk *generates* it — an unbounded run would make merely
  looking at a structure produce terrain.

**Not yet done here:**

- **The outline path has not been watched** for a whole structure — the rig
  client is modded, so it always gets the drawn ghost. A vanilla client would
  show the per-piece boxes, which is the half of this feature nobody has seen.
- **Both 26.x branches compile, and neither has been watched.** The ports went
  forwards — main to `mc26.1` to `mc26.2` — and every source file applied
  unchanged both times. Verifying them needed a Standards jar for each, which is
  absent from the sibling feed on this machine and was fetched from the rig
  feeds on Vivo; that gap will bite the next port too.
- **The first cut could not have compiled on either 26.x branch**, and only the
  port said so: 26.x makes `ChunkPos` a record whose `x` and `z` are private and
  drops `new ChunkPos(BlockPos)`. A `Roll` now carries the chunk as two ints and
  builds a `ChunkPos` only where one is needed, so the three branches keep one
  source. **Server-side code is not automatically portable** — this branch
  family's churn is concentrated in rendering, which makes the exceptions easy
  to assume away.
- **Terrain is not adapted.** Generation flattens ground around a village as it
  builds the chunk; a placement into finished terrain cannot, so a village on a
  slope will have houses cut into it and standing proud of it. Vanilla's
  `/place structure` has the same limit. Whether to offer a levelling pass is
  open.
- **A template piece's NAME is not its template's id, and reading the name
  meant End City drew nothing.** Sable's first try, 2026-09-19: the ghost said
  `minecraft:end_city` is "built piece by piece in code", which is false — End
  City is templates all the way down. The first cut read the `Template` string
  out of the tag a piece saves, and `EndCityPiece` stores `"base_floor"`,
  prepending its own folder in `makeTemplateLocation`; Woodland Mansion does the
  same. So the lookup asked for `minecraft:base_floor`, missed, and skipped all
  105 pieces.

  **Matching on the short name would have been the wrong repair**, and the data
  said so before any code was written: 163 of vanilla's 1,202 template names are
  ambiguous across structures — `corner_01` belongs to ten of them. The piece
  hands over the template itself instead (`template()`, `templatePosition()`,
  `placeSettings()` are public on all three Minecraft lines), so there is no name
  to resolve. **That also fixed the mirror gap this entry used to record**: the
  settings carry the mirror and the rotation pivot, and the arithmetic is now
  vanilla's own `calculateRelativePosition` rather than a hand-rolled transform
  that assumed both were absent.

  The lesson generalises: **a saved name is a serialisation detail, not an
  identity.** Ask the object, not its tag.
- **Processors are not applied to the preview**, so a ruin previews unrotted —
  the same family as the several-palettes gap above.
- **Jigsaw pools are not offered.** `/place jigsaw <pool> <target> <depth>`
  grows part of an assembly from a pool; the same machinery would serve it.
  Deliberately left until whole structures have been played.

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

**Gravity on a cast body belongs to Cast, and must not be touched here.** Cast
holds an anchored body up with `setNoGravity(true)`, so that one whose block is
mined out hangs, looks down, and only then drops. Suspending the anchor restores
gravity in the same call, which is why a possessed body walks and falls
normally, and why this mod's once-a-second re-assertion of that suspension also
keeps a worn body's gravity honest for free.

Releasing re-anchors wherever the body ended up, **including in mid-air** — walk
one off a rooftop and let go and it falls the rest of the way, because Cast
leaves gravity on until it lands and adopts the landing as the anchor. So never
wait for a body to be grounded before releasing it, and never set or clear
`noGravity` from this side: two mods deciding whether an entity falls is a fight
with no visible cause.

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
