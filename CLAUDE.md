# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

**LegendQuest StoryTeller** — a Dungeon Master's toolkit for a server running
[LegendQuest ReForged](https://github.com/Sablednah/LegendQuest-ReForged). One
player is the Storyteller and gets the tools to run a live session: drift unseen
through a scene, look in on the table, spawn and dress a cast, possess a creature
and speak as it, place a set, narrate, and hand out the spoils.

**It is an expansion, not a standalone mod.** LegendQuest is a hard dependency
and always will be: a reward *is* LegendQuest XP, a party *is* a LegendQuest
party, a citizen's race and class *are* LegendQuest registry entries. There is
no version of this mod that stands on its own, and proposals to make one are
proposals for a different mod.

**`/st` is the whole mod.** There is no client half yet. Any GUI built later
drives these same commands rather than bypassing them, so the two can never grow
different rule sets.

## Versions — one branch per Minecraft version

Matching LegendQuest exactly, for the same reason it does it: Minecraft is on
quarterly calendar versioning, so this is a treadmill rather than a port.

| Branch | Minecraft | NeoForge | Java |
|---|---|---|---|
| `main` | 1.21.11 | 21.11.42 | 21 |
| `mc26.1` | 26.1.2 | 26.1.2.95 | **25** |
| `mc26.2` | 26.2 | 26.2.0.59 | **25** |

- **Port forwards, not sideways:** `main` → `mc26.1` → `mc26.2`. Cherry-pick;
  both 26.x branches take main's commits cleanly in the normal case, because
  this mod is server-side and 26.x's churn is concentrated in rendering.
- Jars carry the target (`storyteller-0.1.0+mc26.2.jar`); the version inside
  `neoforge.mods.toml` stays a plain `0.1.0`. Three identically-named files in a
  mods folder are indistinguishable.
- Docs live on `main`; version branches carry code. `docs/ROADMAP.md` is the
  design record and is worth reading before adding a tool — it says what was
  tried and rejected, which is the expensive half.

## Build & run

**There is no system Java.** Set this every time, *before* the gradle command —
Java 21 on `main`, Java 25 on both 26.x branches:

```bash
export JAVA_HOME=/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2   # main
export JAVA_HOME=/home/sable/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2   # mc26.1, mc26.2

./gradlew build     # -> build/libs/storyteller-<version>+mc<mcversion>.jar
```

- Never report success from a command that prints it unconditionally. Grep the
  log for `error:|BUILD FAILED` and report from that, not from an `echo` placed
  after a build that may have failed.
- Versions and metadata live in `gradle.properties` and expand into
  `src/main/templates/META-INF/neoforge.mods.toml`. **Edit the template, never a
  generated `mods.toml`.**

### It compiles against jars, not projects

`build.gradle` finds each dependency's jar by scanning a sibling repo's
`build/libs` for a filename matching **this branch's** `minecraft_version`:

| Dependency | Looked for in | Missing? |
|---|---|---|
| LegendQuest | `libs/`, `../LegendQuest-ReForged/build/libs` | **fails the build loudly** |
| Standards | `../SableCraft-Standards/build/libs` | builds without the API |
| CityWorld | `../CityWorld-ReForged/build/libs` | builds without the API |
| Cast | `../Cast/build/libs` | builds without the API |

Version-matched rather than a plain `*.jar` wildcard, and that is load-bearing:
`libs/*.jar` is gitignored, and **an untracked file does not follow a branch
switch**, so a wildcard silently compiles the 1.21.11 branch against whichever
jar the last build left behind. It also takes exactly one jar, newest-first —
handing javac two versions of an API puts the older one first and fails pointing
at *your* code rather than at the stale jar actually causing it.

So: **build the matching LegendQuest branch first.** A red build here is usually
a missing or stale sibling jar, not a code error.

## Worktrees and the test loop

| Path | Purpose |
|---|---|
| `LegendQuest-StoryTeller` | jar builds; holds `main` |
| `LegendQuest-StoryTeller-srv` | WSL dev server (`./gradlew runServer`) |
| `LegendQuest-StoryTeller-buddy` | Windows TestBuddy client (`TestClient.cmd`) |

Worktrees are **detached** — advance with `git checkout --detach <sha>`, never
`git checkout main` (main is held by the primary worktree).

`run/` and `runBuddy/` are gitignored, so they do not follow a branch switch: a
26.x session needs its own mods folder, its own world, and Java 25 for the buddy
(`TestClient.cmd` hardcodes CurseForge's JDK 21 `java-runtime-delta`).

### Ports — this project owns 25570 / 25580

Sable has six mods with a dev server and they were nearly all on the vanilla
defaults. The game-port collision fails loudly; the RCON one just answers "auth
failed" forever, which reads like a wrong password.

| Project | game | RCON |
|---|---|---|
| Standards | 25565 | 25575 |
| LegendQuest | 25566 | 25576 |
| ZombieMod | 25567 | 25577 |
| CityWorld | 25568 | 25578 |
| MobHealth | 25569 | 25579 |
| **StoryTeller** | **25570** | **25580** |

RCON password is **`stdev`** (LegendQuest's is `lqdev` — they differ). The buddy
client's connect address is baked into `build.gradle`, so it and
`run/server.properties` have to agree, and `run/` being gitignored means a fresh
checkout needs the port applied by hand.

**Before assuming a port is yours**, check who holds it — and never kill the
process that does without finding out whose it is:

```bash
ss -ltnp | grep -E '2556|2557|2558'
tr '\0' ' ' < /proc/<pid>/cmdline | grep -oE 'modFolders=[^ ]*'
```

`fml.modFolders` names the repo, so it says whether the server on your port is
yours or another session's.

### Driving a real test

The server needs a mods folder containing **LegendQuest and Standards jars for
this branch's Minecraft version**. Mind the pairing: LegendQuest 2.5.0+ declares
`standards [1.8.0,)` and the server refuses to boot on anything older, so a
half-updated `run/mods` fails at load with a dependency error rather than in a
way that looks like this mod's fault.

Launch the buddy with `TestClient.cmd < nul` (the trailing `pause` blocks
otherwise); it auto-joins. Then drive commands over RCON.

**A player command needs a player source.** `/st` refuses a console source, so
run it as the buddy:

```
execute as TestBuddy at TestBuddy run st lock
```

**RCON does not show you the answer.** `Feedback.chat` sends to the *player*, so
RCON returns an empty string even on success. Read the client's log instead —
the vanilla client writes every chat line to `runBuddy/logs/latest.log` prefixed
`[CHAT]`, which is a complete, scriptable record of what the Storyteller was
told:

```bash
grep -o "\[CHAT\].*" runBuddy/logs/latest.log | tail -20
```

**The action bar is not in that log**, and neither is anything drawn. For those,
focus the client window (`SetForegroundWindow`), `SendKeys('{F2}')`, and read the
PNG out of `runBuddy/screenshots/`. "The code ran" and "the pixels are there" are
different questions, and only the second one is the bug report.

- Positioning is exact and repeatable: `tp TestBuddy <x> <y> <z> <yaw> <pitch>`
  then `summon` at a known offset. Yaw 0 faces +Z. Aim **pitch ~20 down** for a
  mob 3 blocks away — at pitch 0 the gaze ray passes over a cow's back and the
  test reads as "nothing in your sights" when the code is fine.
- `pauseOnLostFocus:false` must stay in `runBuddy/options.txt`, or an unattended
  buddy sits on `PauseScreen` the moment the window loses focus and renders no
  in-game frame at all.
- The tutorial toast ("Move with W, A, S and D") sits top-right and **does not
  expire**. Crop around it rather than waiting it out.
- **Kill the buddy on `*TestClient*`, `*runClientBuddy*` and `*StoryTeller-buddy*`
  — not on `*runBuddy*`.** LegendQuest's CLAUDE.md says `*runBuddy*` and that is
  right *there*, because its game directory is literally `runBuddy`; here the
  launcher is `TestClient.cmd`, the Gradle task is `runClientBuddy` and the
  worktree is `LegendQuest-StoryTeller-buddy`, so the borrowed filter matches
  nothing. It fails silently — `Stop-Process` on an empty pipeline is not an
  error — so "cleaned up" reads exactly like success. Nine orphaned processes
  and three cmd windows accumulated across one session that way, over the top
  of whatever the owner was doing, and each new client then kicked the last one
  off the server with "You logged in from another location", which looks like a
  networking fault rather than a stale process. **Verify the kill by listing
  survivors**, never by the absence of an error. Plain `pgrep` cannot see any
  of them.
- The Gradle daemon is a separate process and outlives the client. The buddy's
  is identifiable by `.gradle-win` in its command line (`TestClient.cmd` passes
  `--project-cache-dir .gradle-win`); daemons without it belong to another
  project, and the standing rule applies — never kill a process without finding
  out whose it is.
- `stop` over RCON shuts the server down cleanly.

### NEVER copy a jar into a running instance

Windows does not lock it, so the copy silently succeeds — and the live JVM dies
the moment it lazily loads a class it had not already touched
(`NoClassDefFoundError` ← `ZipException: invalid LOC header`, with a clean jar on
disk). Confirm nothing is running first:

```bash
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process | \
  Where-Object { \$_.Name -like 'java*' } | ForEach-Object { \
  [regex]::Match(\$_.CommandLine,'Instances\\\\([^\\\\\"]+)').Groups[1].Value }"
```

There is no `deploy.sh` here yet; instances are updated by hand. Overwrite the
same filename in place, and update the whole family together — this mod,
LegendQuest, Standards and Cast move as a set.

## Design principles

Inherited from LegendQuest, and they are constraints rather than preferences:

**Only the server needs a mod.** Every effect the table perceives arrives
through something a vanilla client already understands — real entities, chat,
titles, the action bar, sounds, display entities. A client-side StoryTeller mod
is a *control surface* for the person running the scene, never a requirement for
the scene to be witnessed. This is what settles build-vs-adopt arguments: the
Standards action bar was adoptable because it renders as clickable chat for a
vanilla client.

**"Don't make me think."** The Storyteller cannot read the source; everything
they know, they know from what the game told them at that moment.

- **Say what happened and name the way back**, in the same breath. Alarming-and-
  harmless is the worst combination.
- **Invisible sticky state is the enemy.** A held lock, a bound camera, a
  possession that ended silently — each makes the *next* command inexplicable.
  Every such state either reports itself (an action bar line, a lit button) or
  announces its own end.
- **A visible correction is itself a defect.** Repair before the client is told.
- Colour codes go through `Feedback`, which delegates to LegendQuest's — it
  turns `&` codes into real component styles. A second implementation here would
  be a second chance to ship `&7CHR: &f14` to a console reader.

## One class per optional dependency

The single most important structural rule in this repo. Naming a class is what
loads it, so **the `ModList.isLoaded(...)` guard always sits outside the support
class** — an unguarded call is a `NoClassDefFoundError` on every server without
that mod.

"Outside" means at whatever reaches for it, not one fixed place:
`StoryTeller.java` guards the event-bus registrations, `Possession.castAvailable()`
and `Possession.vanishAvailable()` guard the possession paths, and `STCommands`
guards the CityWorld library. What must never happen is the guard living *inside*
the support class, because reaching the guard would already have loaded it.

| Class | Owns | Guard |
|---|---|---|
| `Feedback` | LegendQuest's `Feedback` | none — LegendQuest is required |
| `Rewards` | LegendQuest character/party API | none — required |
| `EconomySupport` | Standards economy | `standards` |
| `ReputationSupport` | Standards reputation | `standards` |
| `VanishSupport` | Standards vanish (keyed holds) | `standards` |
| `ActionsSupport` | Standards actions (the buttons) | `standards` |
| `CastSupport` | Cast NPC bodies | `cast` |
| `CityWorldSupport` | CityWorld schematic library | `cityworld` |

Adding a seam means adding a class, not adding an import to an existing one.

Two details that surprise people: **CityWorld's API package is
`me.daddychurchill.CityWorld`**, not `com.sablednah.cityworld` — it carries the
original plugin's namespace, so a grep for `com.sablednah` will tell you nothing
imports CityWorld when one class does. And `Rewards` asks
`isLoaded("standards")` directly for the money and reputation paths rather than
going through a support class, because it needs the answer to *refuse politely*
rather than to call anything.

## Permissions

Two nodes, through NeoForge's `PermissionAPI`, LuckPerms-compatible — the same
scheme LegendQuest uses, so a server already granting `legendquest.*` needs no
new tooling.

| Node | Grants |
|---|---|
| `storyteller.storyteller` | `/st` at all: drift, possess, cast, struct, narrate |
| `storyteller.reward` | handing out XP, levels, skill points, karma, reputation, money |

**Both default to false, ops included** — and this deliberately does *not*
follow LegendQuest, where op level 2 satisfies `legendquest.admin`. An op is
someone who can fix the server, which is not the same as someone who should be
able to silently possess a player's rival mid-session.

Split rather than one master switch, because "can hand out levels and money" and
"can drift through walls unseen" are different amounts of trust.

`STPermissions.gate` lets a **console or command-block** source through on
operator level alone, so automation keeps working; a player source must hold the
node.

## Known traps

- **Eyes or control, never both.** A vanilla client stops sending movement
  entirely while spectating an entity (`LocalPlayer.sendPosition` is gated on
  `isControlledCamera()`), and `ServerPlayer` snaps a spectator onto its camera
  entity — rotation included — every tick. So `/st possess` steers with your own
  body and `/st possess eyes` binds the camera and gives up control. The first
  version asserted confidently in three places that it could do both, and the
  first attempt to steer a cow did nothing at all.
- **Spectator is for surveying, not for steering.** A spectator flies, so a led
  body gets walked into the air; it noclips, so the body follows it underground;
  and vanilla repurposes a spectator's inputs, so clicking an entity re-binds the
  camera out from under the possession. Being *unseen* is vanish's job, not
  spectator's.
- **Goal flags do not reach a brain-driven mob.** `Villager.java` contains no
  references to `goalSelector` at all and ticks its `Brain` in
  `customServerAiStep()`; MOVE/LOOK/JUMP/TARGET only arbitrate *between goals*.
  Our goal is not outranked on those mobs, it is irrelevant, and raising its
  priority changes nothing. 20 such classes in 21.11 and **the list grows every
  few versions** — detect at runtime, never hardcode it.
- **Warn and apply; do not refuse.** `behave` briefly refused brain-driven mobs
  and a play test showed that was too coarse — goats and frogs flee well enough
  to read as fleeing. The defect was never that it ran, it was that it claimed a
  success it had not earned.
- **Never put a version floor on an optional dependency.** FML refuses to load
  the mod outright when an older one is present, turning a feature that should
  quietly degrade into a server that will not start. `VanishSupport` catches the
  `LinkageError` and says so once in the log instead.
- **…and a version number lies while the other mod is still moving.** Standards
  grew `Action`'s constructor without bumping its version, so an already-shipped
  StoryTeller hit `NoSuchMethodError` against a jar its range called fine.
  `ActionsSupport.hintSupported()` therefore *probes* by building one throwaway
  `Action` and catching `LinkageError` — the only question with a true answer.
  Keep that shape; do not "simplify" it to a version check.
- **A state seam must be pure.** Standards asks an action's state and hint
  predicates every time it draws. `Sights.lockedName` returns the remembered
  name rather than re-resolving the lock, because resolving one has consequences
  — it clears a dead lock and says so — and a hint that resolved would make the
  button bar's own repaint emit chat.
- **A slime cannot be led** and says so. It does not walk: it jumps, through a
  move control that is package-private and cannot be steered from outside.
  Possession starves its goals of their flags, which stops the jumping without
  replacing it, so the slime just sits. Eyes and voice still work — only the
  leading is refused, so only the leading is mentioned.
- **Gravity on a Cast body belongs to Cast.** Never set or clear `noGravity`
  from this side: two mods deciding whether an entity falls is a fight with no
  visible cause. Releasing re-anchors wherever the body ended up, mid-air
  included, so never wait for a body to be grounded before letting go.
- **Undo restores block states only, not block-entity contents.** A chest a
  structure overwrote comes back as an empty chest of the right kind. Fine for
  dressing empty ground; not a promise for placing over someone's base. Stated
  in the README rather than hidden.
- **After a rewrite, grep for the name of the thing you removed**, not the thing
  you added. `build.gradle` carried a comment naming a `StructureSupport` class
  that has never existed under that name.

## Releasing

**Not released yet.** `mod_version` is `0.1.0`, there is no CurseForge project,
and three things LegendQuest has do not exist here yet: **`CHANGELOG.md`**, the
`.github/workflows` that publish on a GitHub release, and the artwork. Copy
LegendQuest's rather than inventing new ones — its `curseforge.yml`,
`modrinth.yml` and `store-copy` job are the house pattern, and its
`scripts/curseforge-changelog.py` already strips the Markdown their sanitiser
500s on.

The store copy is written and ready to paste: [`CURSEFORGE.md`](CURSEFORGE.md)
is the description, [`docs/store-copy.md`](docs/store-copy.md) is everything
around it — summary, categories, tags, relations, artwork sizes and the shot
list. Keep both in step with the README when features change; LegendQuest's had
drifted three releases before anyone noticed, which is why its `store-copy` job
now fails a release when `CURSEFORGE.md` has not changed since the last tag.

When there is a project:

1. `mod_version` on all three branches, `CHANGELOG.md`, **`CURSEFORGE.md`**,
   tag, then a GitHub release.
2. **Artwork is a prerequisite, and the keys are already written and commented
   out** in `src/main/templates/META-INF/neoforge.mods.toml`. Three keys, all
   declared on every branch; older loaders ignore what they do not know.
   `iconFile` is the small **square** beside the name (26.2+; without it our row
   is the only one with no icon), `bannerFile` the wide info-panel image (26.2+),
   `logoFile` what 1.21.x and 26.1 show. Drop `storyteller-icon.png` and
   `storyteller.png` into `src/main/resources/` and uncomment. Declaring
   `logoFile` alone raises a dev-only deprecation warning on 26.2.
3. **CurseForge rejects non-jar files** *after* returning HTTP 200, and a 200 is
   acceptance rather than publication — check the file is approved.
4. Its changelog sanitiser 500s on some Markdown: blockquotes, indented code
   blocks with pipes, angle-bracket autolinks. Keep release notes to plain
   paragraphs, lists and simple tables.
5. Screenshots stay out of git.

Every jar carries a build stamp (`BuildInfo`, from
`src/main/templates/storyteller/build.properties`) and names it in the startup
line, so a log says which build reported the issue:

```
LegendQuest StoryTeller 0.1.0+mc1.21.11 (build bb358c6f on main, 2026-09-10T12:57:21Z) initialising
```

`version` in the stamp carries the `+mc` tag, so the stamp and the jar filename
answer "which build is this" with the same token. `branch` is a poor proxy —
"main" says nothing about 1.21.11, and a detached checkout has no branch at all.
