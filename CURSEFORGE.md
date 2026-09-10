<!-- Header image: upload the banner to the CurseForge description gallery,
     then paste its media.forgecdn.net URL here AS THE FIRST LINE of this file,
     the way LegendQuest's CURSEFORGE.md does. It has to live in the pasted text
     rather than be uploaded separately: CurseForge's editor does not keep an
     uploaded image across edits, so a banner that is not part of the copy goes
     missing every time the description changes. Left blank rather than guessed
     — a broken image is worse than none on a store page. -->

# LegendQuest StoryTeller

**Run a live story for a server of LegendQuest characters.** One player is the
Storyteller — the Dungeon Master — and gets the tools to stage a session: drift
unseen through a scene, look in on any player, spawn and dress a cast, take over
a creature and speak as it, place a building, narrate, and hand out the spoils.

An expansion for
[LegendQuest ReForged](https://www.curseforge.com/minecraft/mc-mods/legendquest-reforged),
not a standalone mod. An NPC here *is* a LegendQuest character sheet; a reward
*is* LegendQuest XP; a party *is* a LegendQuest party.

Built for **Minecraft 1.21.11, 26.1.2 and 26.2**. Each download names the version
it is for, and refuses to load on the wrong one rather than misbehaving quietly.

---

## Only the server needs it. Your players install nothing.

|  | LegendQuest | StoryTeller |
|---|---|---|
| Server | required | required |
| The Storyteller | optional | optional |
| Everybody else | **nothing** | **nothing** |

That is a hard constraint on the design, not a nice-to-have. Every effect the
table perceives arrives through something a vanilla client already understands —
real entities, chat, titles, the action bar, sounds. **Your players see the whole
scene from an unmodded client.** Nobody has to install a modpack to be in your
story.

It also means the Storyteller can run a session from a vanilla client. Even the
buttons work — see below.

---

## Wear a creature and speak as it

`/st possess` takes over the creature in your sights. Its own AI stops deciding
things, it walks where you walk, and `/st say <words>` gives it your voice to
anyone close enough to hear. Say `/st possess` again and it is itself, exactly as
it was — nothing is destroyed, nothing is rebuilt, a released goblin is still a
goblin.

`/st possess eyes` does the opposite trade: the camera binds to the creature and
you see what it sees while it carries on living its own life. You cannot have
both — that is Minecraft's limit rather than a shortcut, and the mod says so
plainly instead of pretending.

You are **hidden automatically** while you wear a body (with Standards
installed), and given back to the world when you let go — unless you had hidden
yourself first, in which case you stay hidden and are told why.

## Lock on, then look wherever you like

`/st lock` makes the creature in your sights *the* target until you say it again.
Possess, speak, dress, direct and save all mean that one afterwards, wherever you
happen to be looking.

Pointing at a thing is the right default — no names, no ids, no selectors — but
it wants your head still, and walking a possessed cow across a room means looking
where it should go, which is not at it. A locked shopkeeper can even hold a whole
conversation through `/st say` **without being possessed at all**, so it keeps its
own behaviour while you speak for it.

It never fails quietly: if the creature dies, unloads or leaves the world, the
lock ends and says so, rather than silently pointing your next command at
whatever happened to be under the crosshair.

## Cast a scene

- `/st cast spawn <entity> [name]` — any mob, named or not.
- `/st cast citizen [race] [class]` — a named villager with a LegendQuest race
  and class rolled by weighted frequency, or pinned to the ones you want.
- `/st cast behave guard | patrol | follow <player> | flee | none` — a movement
  habit added *alongside* a creature's own instincts, so a guard still fights
  back and still flinches from fire.
- `/st cast save | use | list` — keep a cast member as a preset, stored with the
  world.
- `/st cast equip <slot> <item>` · `/st cast worn` — dress an NPC, with the item
  written exactly as `/give` takes it, so a named sword or a tipped arrow works.
  (Needs Cast, below.)

## Dress the set

- `/st struct place <template> [rotate cw90|180|ccw90]` — any structure any
  loaded datapack declares: over 1,200 of them in vanilla alone, villages,
  temples, ruins and fortress pieces.
- `/st undo` · `/st scene clear` — take back the last thing you placed or
  spawned, or everything from this session. **Every placement snapshots the
  ground first**, so a building can be taken back off cleanly.

## Reward the table

- `/st reward <player> xp | levels | sp | karma | money <n> [reason]`
- `/st reward <player> reputation <track> <n> [reason]` — standing with a
  faction or a town, which is not the same thing as karma: karma is LegendQuest's
  moral axis and drives your titles, reputation is standing on a named track, so
  a character can be loved in one place and hated in the next.
- `/st reward <player> item <item> [count]` — hand over the actual thing, from
  the item registry, so modded loot works as well as vanilla.
- `/st reward party <player> …` — the whole party in one action, because a GM
  awarding four people one at a time will award three.
- `/st effect <player> <effect> <seconds> [level] [hidden]`, and the party and
  clear forms.

`hidden` suppresses the swirling particles — for a condition the story imposes
rather than a potion someone drank.

These are not a reimplementation of vanilla `/effect` and `/xp`. They are the
same capabilities reached through **the Storyteller's own permission instead of
operator**, so a GM can lay a curse without also being handed `/stop`, `/ban` and
every block on the server.

## See the table, and speak to it

- `/st who` — the roster: race, class, level, karma, health, mana, party and
  where everyone is standing, with health coloured because it is the one number
  you have to react to mid-scene.
- `/st goto <player>` · `/st next` — look in on someone, or cycle the table.
- `/st drift` — leave your body and move through the scene unseen; say it again
  to come back, with dimension, position, facing and game mode all restored.
- `/st narrate [radius <n>| party <player>] <text>` · `/st title <text>` ·
  `/st whisper <player> <text>` — a god-voice, a title card, an omen meant for
  one person.

---

## Buttons, without asking anyone to install anything

With [Standards](https://www.curseforge.com/minecraft/mc-mods/sablecraft-standards)
installed, the Storyteller's most-used tools appear as buttons — a drawn bar for
anyone running the Standards client, **and clickable chat buttons for anyone who
is not.** A vanilla Storyteller gets working buttons.

They report state rather than just availability: Possess lights up while you are
wearing something *and names it*, Lock lights up naming what it holds. A
Storyteller who can see "you are wearing a cow" notices a possession that has
quietly ended, instead of finding out three commands later.

---

## Permissions

Two nodes, through NeoForge's permission API, LuckPerms-compatible — the same
scheme LegendQuest uses, so a server already granting `legendquest.*` needs no
new tooling.

| Node | Grants |
|---|---|
| `storyteller.storyteller` | `/st` at all |
| `storyteller.reward` | handing out XP, levels, skill points, karma, reputation and money |

**Both default to false, operators included.** An op is someone who can fix the
server, which is not the same as someone who should be able to silently possess
a player's rival mid-session. They are split for the same reason: "can hand out
levels and money" and "can drift through walls unseen" are different amounts of
trust, and you should be able to grant one without the other.

---

## Required: LegendQuest

[LegendQuest ReForged](https://www.curseforge.com/minecraft/mc-mods/legendquest-reforged)
is a hard dependency, matching Minecraft version for Minecraft version.

## Optional, and each one adds something specific

| Mod | Adds |
|---|---|
| [Standards](https://www.curseforge.com/minecraft/mc-mods/sablecraft-standards) | the buttons, money rewards, reputation rewards, and hiding you while you wear a body |
| Cast | NPC bodies — human and mob — to possess, dress and speak through |
| CityWorld | its schematic library (`.schematic`, `.schem`, `.litematic`, `.nbt`) as a second pool for `/st struct` |

Every one of these degrades to a plain "not available on this server" rather
than breaking. Standards being *installed* and the server *having an economy*
are asked as two separate questions, because they are two separate things.

---

## Installing

1. Put the LegendQuest jar and the StoryTeller jar in your server's `mods/`
   folder — both must be for the same Minecraft version.
2. Start the server.
3. Grant `storyteller.storyteller` to whoever is running the story. Nothing works
   until you do, deliberately.

**Pick the download matching your server:**

| File | Minecraft | NeoForge |
|---|---|---|
| `storyteller-<version>+mc1.21.11.jar` | 1.21.11 | any 21.11 build |
| `storyteller-<version>+mc26.1.2.jar` | 26.1.2 | any 26.1 build |
| `storyteller-<version>+mc26.2.jar` | 26.2 | any 26.2 build |

Any build within the series will do — updating NeoForge does not mean waiting
for a StoryTeller release that changes nothing else.

---

## Honest about what it does not do yet

There is **no GUI and no story planner yet** — `/st` is the whole mod today, and
anything built later drives these same commands rather than bypassing them.

Two limits worth knowing before a real session, stated here rather than
discovered mid-scene:

- **`behave` may not hold on a brain-driven creature** — villagers, piglins,
  wardens and seventeen others run on a different AI system that our instruction
  competes with rather than replaces. It applies the behaviour and *tells you* it
  may not hold, rather than claiming a success it has not earned.
- **Undo restores blocks, not container contents.** A chest a structure
  overwrote comes back as an empty chest of the right kind. Fine for dressing
  empty ground; do not place over someone's base and rely on taking it back.

---

## Source, issues, licence

MIT licensed and developed in the open at
[GitHub](https://github.com/Sablednah/LegendQuest-StoryTeller). Bug reports and
suggestions are welcome on the
[issue tracker](https://github.com/Sablednah/LegendQuest-StoryTeller/issues).
