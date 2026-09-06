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
- `/st reward <player> xp|levels|sp|karma|money <n> [for <reason>]`
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

Possession works on a **vanilla Storyteller client**. Steering is by leading:
your drifting body still flies on WASD and the creature walks toward it, bound
by its own legs — a possessed cow will not scale a cliff the audience can see
it could not climb. One-to-one input control is what the Storyteller's own
client mod adds later.

Nothing is destroyed to do it. The creature keeps every goal it was born with;
possession just adds one at priority 0 that holds all four AI flags, and
releasing removes exactly that one.

Not built yet: NPC spawning, the structure library, the story planner and the
GUI. See `docs/ROADMAP.md`.

## Permissions

Nodes go through NeoForge's `PermissionAPI`, LuckPerms-compatible, the same
scheme LegendQuest uses.

| Node | Grants |
|---|---|
| `storyteller.storyteller` | `/st` at all: drift, goto, next, who |
| `storyteller.reward` | handing out XP, karma and money |

**Both default to false, ops included.** LegendQuest lets op level 2 satisfy
`legendquest.admin`; this does not follow that. An op is someone who can fix
the server, which is not the same as someone who should be able to silently
possess a player's rival mid-session.

## Optional: Standards

With [Standards](https://github.com/Sablednah/SableCraft-Standards) present,
money rewards use its economy API. Without it, `money` on a reward reports
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
