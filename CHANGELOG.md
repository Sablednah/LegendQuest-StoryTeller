# Changelog

All notable changes to LegendQuest StoryTeller are documented here.
This project follows [Semantic Versioning](https://semver.org/).

## Unreleased

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
