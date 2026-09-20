# Changelog

All notable changes to LegendQuest StoryTeller are documented here.
This project follows [Semantic Versioning](https://semver.org/).

## 1.3.0 — 2026-09-20

Requires **LegendQuest ReForged 2.5.0 or newer**, as before.

### Tell a locked creature what to do

- **`/st move`** sends whatever you have locked to where you are looking. It
  walks there — and **keeps doing what it was doing**, at the new spot: a guard
  sent across a courtyard guards there from then on, rather than strolling back
  to where it started a few seconds later.
- **`/st swing`** makes it take a swing. A blacksmith strikes the anvil, a
  guard warns somebody off — a gesture for a scene, with nothing to undo.
- Both need a **lock**, and that is deliberate rather than a shortcut: your
  crosshair is pointing at the *destination*, so the thing being aimed at is
  not the thing being ordered. `/st lock` on to it first.
- Both are on a **button and a keybind** as well as the command, like every
  other tool you aim. The keys arrive unbound, as they all do.
- They say what will happen rather than leaving you to find out: whether it
  will guard or patrol at the new spot, whether it is still following somebody
  and will head back, and — for one of Cast's own people — that anything which
  cannot find a way there is simply put there after a minute.
- **People swing too, with Cast 1.1.0 or newer.** A human body is drawn by Cast
  rather than being an ordinary creature, so it has nothing of its own to play
  an animation — Cast gained a swing for this and sends it to whoever is
  watching. On an older Cast, StoryTeller says so at start-up rather than
  failing the first time somebody swings.
- Honest about what it cannot do. A slime cannot be walked anywhere at all, and
  a creature somebody is wearing or driving goes where its driver goes. Each one
  says which, instead of accepting the order and quietly doing nothing.

## 1.2.0 — 2026-09-19

Requires **LegendQuest ReForged 2.5.0 or newer**, as before.

### Whole villages, bastions and fortresses — seen first, and undoable

- `/st struct whole place <structure>` builds a whole generated structure the
  way world generation assembles it: villages, bastions, outposts, ancient
  cities, monuments, fortresses, strongholds, mansions — anything in the
  structure registry. Typed on its own it is vanilla's `/place structure`,
  reached through the Storyteller permission rather than operator level 2.
- **`/st undo` takes it back off**, which vanilla's own command has never
  offered — and it takes back anything that arrived with it, so undoing a
  monument does not leave its guardians swimming about. A structure too large to
  remember says so as it lands, instead of leaving you to find out at undo.
- `/st struct whole ghost <structure>` shows the assembly where it would stand
  before anything is built. On a modded client it is drawn block by block; on a
  vanilla client you get an outline of **every piece** — the shape of the
  village, not a rectangle around it.
- **What you see is what lands.** Assembly is random, so the ghost pins its
  layout and the placement rebuilds that exact one rather than rolling again.
- `/st struct ghost reroll`, or the `[Reroll]` button, deals a different
  assembly of the same structure on the same spot.
- A whole structure is placed as generation assembled it, so it does not turn.
  The ghost says "as generated" and puts `[Reroll]` where the turn buttons are;
  on a modded client the scroll wheel raises and lowers it instead.
- **Every structure draws, not just the ones made from saved templates.** A
  fortress, a stronghold or a mansion is built in code rather than stamped from a
  saved template, and those used to show only an outline. They are now asked to
  build themselves into a recording level and the ghost shows what they laid
  down, so what you see is what lands however the structure is made.

## 1.1.0 — 2026-09-15

Requires **LegendQuest ReForged 2.5.0 or newer**, as before. Showing and turning
CityWorld library buildings needs **CityWorld 5.8.0 or newer**; an older
CityWorld still places them, unturned.

### See a building before you place it

- `/st struct ghost <structure>` shows a structure where it would stand, centred
  on where you are looking. Nothing is built until you say so.
- With the StoryTeller mod on your client it is drawn in the world as a
  translucent ghost. Scroll turns it; Shift+scroll or Page Up/Down raise and
  lower it; the arrow keys shift it left, right, away and back, for fitting it
  to uneven ground; left-click locks it where it is so you can walk round it
  and nudge it (click again to pick it back up); right-click builds it; Esc
  puts it away. The six ghost keys only do anything
  while a ghost is up, and rebind under Options → Controls → StoryTeller.
- On a vanilla client you get its outline instead, drawn in particles only you
  can see, with its front edge in gold so you can tell which way it faces. A row
  of chat buttons turns, raises, shifts, locks, places and cancels it — or type
  `/st struct ghost rotate|nudge|hold|place|cancel`.
- Placing no longer carves a hole: a structure's air blocks are left out, so a
  house set into a hillside keeps the hill around it. Add `withair` after the
  name to keep them — `/st struct place <structure> withair`, or
  `/st struct ghost <structure> withair`.
- Village houses, bastion rooms and other pieces of assembled structures no
  longer leave jigsaw blocks behind. A jigsaw block is a connector world
  generation uses to join pieces and then swaps for its final state; a piece
  placed on its own now gets the same swap. Add `withjigsaw` to keep them, in
  either order with `withair`.
- Placing and undoing are tidy. Plants and beds that stop fitting as a building
  lands disappear instead of scattering seeds and items, undo puts every block
  back exactly without beds breaking or chests spilling, and it restores one
  block of ground around the footprint so edge flowers come back too.
- `/st struct place <structure> at <x y z> [rotate ...]` places at a position
  instead of at your feet. It is what a ghost sends, so `/st undo` takes a ghost
  placement back off like any other.
- CityWorld's schematic library works the same way:
  `/st struct library ghost <name>` shows a CityWorld building as a ghost,
  starting with its foundation buried as CityWorld buries it, and
  `/st struct library place <name> [at <x y z>] [rotate ...]` can now turn one.
  Showing and turning need **CityWorld 5.8.0 or newer**; an older CityWorld
  still places buildings unturned, and says why when asked for more.
- Library names are now a single word or a quoted string, so that `at` and
  `rotate` can follow them. None of CityWorld's own buildings have spaces in
  their names; a dropped-in file that does needs quotes.

### Fixed

- Another mod's fake player no longer makes `/st possess` or a ghost throw an
  error when StoryTeller asks what client it is on.

## 1.0.0 — 2026-09-13

The first release. Requires **LegendQuest ReForged 2.5.0 or newer** on the
server. Built for Minecraft 1.21.11, 26.1.2 and 26.2 — each jar names the version
it is for.

StoryTeller gives one player on a LegendQuest server the tools to run a live
story. Only the server needs it installed: every effect your players see arrives
through things a vanilla client already understands, so nobody at the table
installs anything.

### Running the scene

- `/st drift` — spectate a scene unseen, and say it again to get your body back
  exactly where and how you left it.
- `/st goto <player>` · `/st next` — look in on someone, or cycle the table.
- `/st summon [player]` — bring a whole party to where you stand.
- `/st who` — the roster: race, class, level, karma, health, mana, party and
  location for everyone.
- `/st narrate`, `/st title`, `/st whisper` — the Storyteller's own voice, to a
  radius, a party, the whole server or one person.

### Becoming a creature

- `/st possess` takes over the creature in your sights, and again gives it back
  exactly as it was. With the StoryTeller mod on your client you *drive* it —
  you are the creature, on your own controls, and the room sees only it. On a
  vanilla client it *steers* instead, and says why. It works on Cast NPCs, human
  bodies included.
- `/st possess eyes` rides along, seeing what the creature sees.
- `/st lock` makes a creature *the* target, so you can look elsewhere while you
  possess, dress or speak through it.
- `/st say <words>` speaks as it — possessed or merely locked.

### Casting and dressing

- `/st cast spawn` and `/st cast citizen` put named NPCs in the scene;
  `/st cast behave` gives them a habit (guard, patrol, follow, flee).
- `/st cast equip` and `/st cast worn` dress a Cast NPC with items written
  exactly as `/give` takes them.
- `/st cast save`, `use` and `list` keep a cast with the world.
- `/st struct place` places any datapack structure, and `/st struct library`
  CityWorld's schematic library when installed. `/st undo` and
  `/st scene clear` take them back.

### Rewards

- `/st reward` hands out XP, levels, skill points, karma, items, money and
  reputation — to one player, or to a whole party in one action.
- `/st effect` applies a status effect through the Storyteller's own
  permission rather than operator, optionally without particles.

### Controls

- Buttons for possess, lock, drift, next and summon, with Standards installed —
  a drawn bar on the Standards client and clickable chat buttons on a vanilla
  one. They show state, not just availability.
- Five keybinds on the StoryTeller client, unbound by default.

### Permissions

- `storyteller.storyteller` for `/st` at all, and `storyteller.reward` for
  handing out rewards. Both default to false, operators included.

### Optional companions

- **Standards** — the buttons, money and reputation rewards, and hiding you
  while you wear a body.
- **Cast** — NPC bodies to possess, dress and speak through.
- **CityWorld** — its schematic library for `/st struct`.
