# LegendQuest StoryTeller

**Run a live story for a server of LegendQuest characters.** One player is the
Storyteller — the Dungeon Master — and gets the tools to stage a session:
drift unseen through a scene, look in on any player, spawn a cast, possess a
mob and speak as it, place a set, and hand out the spoils.

An expansion for [LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged),
not a standalone mod. An NPC here *is* a LegendQuest character sheet; a reward
*is* LegendQuest XP; a party *is* a LegendQuest party.

## Who needs to install what

| | LegendQuest | StoryTeller |
|---|---|---|
| Server | required | required |
| The Storyteller | optional (client sugar) | optional (GUI sugar) |
| Everybody else | **nothing** | **nothing** |

**Only the server truly needs either mod.** That is a hard constraint on the
design, not a nice-to-have: every effect the table perceives has to arrive
through something a vanilla client already understands — real entities, chat,
titles, the action bar, sounds, display entities. A client-side StoryTeller
mod is a *control surface* for the person running the scene, and never a
requirement for the scene to be witnessed.

The corollary is that `/st` is the whole mod. Any GUI added later drives these
same commands rather than bypassing them, so the two can never grow different
rule sets.

## Status: milestone 1

Working today:

- `/st drift` · `/st return` — spectate a scene and get your body back
  (dimension, position, facing and game mode all restored).
- `/st goto <player>` · `/st next` — look in on someone, or cycle the table.
- `/st who` — the roster: race, class, level, karma, health, mana, party and
  where everyone is standing, with health coloured because it is the one
  number you have to react to mid-scene.
- `/st reward <player> xp|levels|sp|karma|money <n> [reason]`
- `/st reward <player> reputation <track> <n> [reason]` — standing with a
  faction or town, via Standards. Not the same thing as karma: karma is
  LegendQuest's own moral axis and drives titles, reputation is standing on a
  named track, so a character can be loved in one place and hated in the next.
- `/st reward party <player> …` — the whole party in one action, because a GM
  awarding four people one at a time will award three.
- `/st effect <player> <effect> <seconds> [level] [hidden]`
- `/st effect party <player> …` · `/st effect clear <player> [<effect>]`

`hidden` suppresses the swirling particles — for a condition the story imposes
rather than a potion someone drank.

Effects are not a reimplementation of vanilla `/effect`. They are the same
capability reached through the Storyteller's own permission instead of
operator, because a GM should be able to lay a curse without also being handed
`/stop`, `/ban` and every block on the server. The party form matters for the
same reason: vanilla's answer is `@a[...]` selectors, and those need operator
level 2 to parse at all.

- `/st possess` — take over the creature you are looking at. Your camera
  becomes its eyes and its own AI stops deciding things.
- `/st say <words>` — speak as it, to anyone close enough to hear.
- `/st release` — give it back to itself, exactly as it was.
- `/st cast spawn <entity> [name]` · `/st cast citizen [race] [class]` — put a
  named mob in the scene; citizen rolls a race and class by weighted
  `frequency` for a Villager's name (flavour, not a character — LegendQuest
  has no NPC of its own).
- `/st cast behave guard|patrol|follow <player>|flee|none` — a movement habit,
  alongside a mob's own goals rather than instead of them.
- `/st cast save|use|list` — a saved cast, kept with the world.
- `/st struct place <template> [rotate ...]` — any vanilla `.nbt` structure a
  loaded datapack declares, undo-able.
- `/st struct library list|place` — CityWorld's schematic library
  (`.schematic`/`.schem`/`.litematic`/`.nbt`) as a second pool, when installed.
- `/st narrate [radius <n>|party <player>] <text>` · `/st title <text>` · `/st
  whisper <player> <text>` — the Storyteller's own voice.
- `/st undo` · `/st scene clear` — take back the last scene action, or every
  one this session (a spawn, a placed structure).

Possession works on a **vanilla Storyteller client**, and comes in two forms
because on a vanilla client you can have a creature's eyes or control over it,
never both.

`/st possess` **steers**. You keep your own body and your own view, and the
creature walks to wherever you walk — led rather than driven, bound by its own
legs, so a possessed cow will not scale a cliff the audience can see it could
not climb.

It deliberately does **not** put you into spectator. A spectator flies, so the
body it leads gets walked into the air; it noclips, so the body follows it into
the ground; and vanilla repurposes a spectator's inputs, so clicking an entity
re-binds your camera out from under the possession. Steering wants a grounded
body. You are hidden automatically while you wear a
body, if Standards 1.6.0 or newer is installed, and given back to the world
when you let it go — unless you had vanished yourself first, in which case you
stay hidden and are told so. On an older Standards the mod still loads and
possession still works; it just cannot hide you, and says so in the log. Spectator keeps its own job, which is `/st drift`: the godlike
survey of a scene, moving through walls and jumping between players.

`/st possess eyes` **rides along**. The camera binds to the creature and you
see what it sees, while it carries on being itself. You can speak as it. You
cannot steer it, and you cannot even look around — sneak to step back out.

That limit is vanilla's, not a shortcut. A client stops sending movement
entirely while spectating an entity (`LocalPlayer.sendPosition` is gated on
`isControlledCamera()`), and the server snaps a spectator onto its camera
entity — rotation included — every tick. Supplying that missing input is
exactly what the Storyteller's own client mod is for.

Nothing is destroyed either way. A steered creature keeps every goal it was
born with; possession just adds one at priority 0 that holds all four AI
flags, and releasing removes exactly that one.

Not built yet: the story planner and the GUI. See `docs/ROADMAP.md` — it also
has two known limitations worth reading before relying on this in a real
session: `behave` may not hold on a brain-driven mob (Villager, Piglin, Warden
and 17 others) because it competes with a Brain rather than replacing it, and
structure undo restores block states only, not block-entity contents.

## Buttons, without asking anyone to install anything

With Standards 1.6.0 or newer, the Storyteller's five most-used tools —
possess, release, drift, return, next — are registered as actions: a drawn bar
for anyone running the Standards client, and a row of clickable chat buttons
for anyone who is not. **A vanilla client gets working buttons.** That is why
they exist here at all; a control surface that required a client mod would be
one this mod could not use, given only the server and the Storyteller are meant
to need anything.

An action carries a **command string**, not a payload — a button sends
`/st possess` exactly as if it had been typed. So the buttons cannot drift ahead
of the commands, and permissions and refusals behave identically whichever way
the command arrives. The commands stay the interface.

They report **state**, not just availability: possess lights up while you are
wearing something and names it, drift lights up while you are out of your body.
A Storyteller who can see *you are wearing a cow* notices a possession that has
silently ended, instead of finding out three commands later.

## Permissions

Nodes go through NeoForge's `PermissionAPI`, LuckPerms-compatible, the same
scheme LegendQuest uses.

| Node | Grants |
|---|---|
| `storyteller.storyteller` | `/st` at all: drift, goto, next, who |
| `storyteller.reward` | handing out XP, karma, reputation and money |

**Both default to false, ops included.** LegendQuest lets op level 2 satisfy
`legendquest.admin`; this does not follow that. An op is someone who can fix
the server, which is not the same as someone who should be able to silently
possess a player's rival mid-session.

## Optional: Standards

With [Standards](https://github.com/Sablednah/SableCraft-Standards) present,
money rewards use its economy API, and reputation its reputation API.
Without it, `money` on a reward reports
plainly that the server has no economy and everything else still lands.
Standards being *installed* and the server *having an economy provider* are
asked as two separate questions, because they are two separate things.

## Building

```bash
export JAVA_HOME=/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew build         # -> build/libs/storyteller-<version>+mc<mcversion>.jar
```

It compiles against the LegendQuest jar for the same Minecraft version, found
in `../LegendQuest-ReForged/build/libs` or dropped into `libs/`. Build the
matching LegendQuest branch first, or the build fails loudly saying so.

## Versions

One branch per Minecraft version, matching LegendQuest exactly.

| Branch | Minecraft | NeoForge | Java |
|---|---|---|---|
| `main` | 1.21.11 | 21.11.42 | 21 |
| `mc26.1` | 26.1.2 | 26.1.2.95 | 25 |
| `mc26.2` | 26.2 | 26.2.0.59 | 25 |
