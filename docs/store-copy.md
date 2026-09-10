# Store page copy

Everything a store listing needs **except the description itself**, which lives
in [`CURSEFORGE.md`](../CURSEFORGE.md) so it can be pasted without editing. This
file is the surrounding guide: name, summary, categories, tags, links, and the
screenshots to capture.

Same arrangement as LegendQuest, ZombieMod, CityWorld and Standards. Keep both
in step with the README and CHANGELOG when features change, so the store pages
never drift into advertising something the mod no longer does — LegendQuest's had
gone a full three releases out of date before anybody noticed, which is the
argument for checking it at release time rather than when it feels stale.

CurseForge's description editor accepts pasted rich text and has a Markdown
mode; headings, tables and lists survive the paste. Modrinth takes Markdown
directly, so the same file serves both.

---

## Project name

LegendQuest StoryTeller

## Summary (the one-liner under the title)

> A Dungeon Master's toolkit for LegendQuest servers: drift through a scene,
> possess a creature and speak as it, spawn and dress a cast, place a set, and
> reward the table. Server-side — your players install nothing.

## Categories

- Adventure and RPG (primary)
- Server Utility
- Miscellaneous

## Suggested tags / keywords

rpg, dungeon master, game master, roleplay, npc, possession, spectator, server,
storytelling, scenes, rewards, legendquest

## Licence

MIT — link to https://github.com/Sablednah/LegendQuest-StoryTeller/blob/main/LICENSE

## Links

| Field | Value |
|---|---|
| Source | https://github.com/Sablednah/LegendQuest-StoryTeller |
| Issues | https://github.com/Sablednah/LegendQuest-StoryTeller/issues |
| Wiki / docs | https://github.com/Sablednah/LegendQuest-StoryTeller#readme |

## Where it lives

| Platform | URL | State |
|---|---|---|
| GitHub | https://github.com/Sablednah/LegendQuest-StoryTeller | live |
| CurseForge | — | **not created yet** |
| Modrinth | — | not created yet |
| Website | — | not created yet |

Fill the project ID in here once CurseForge issues one; it is what a publish
workflow will need as `CURSEFORGE_PROJECT_ID`.

## Relations to declare on the project page

CurseForge lets a project declare its dependencies, and these are worth setting
because they are the whole shape of the mod:

| Mod | Relation |
|---|---|
| LegendQuest ReForged | **Required dependency** |
| Standards | Optional dependency |
| Cast | Optional dependency (once it has a page) |
| CityWorld ReForged | Optional dependency |

---

## Artwork

**Done** — delivered 2026-09-10 and in the repo. The mods.toml keys are live.

| File | Where it shows | Size |
|---|---|---|
| `src/main/resources/storyteller-icon.png` | the small icon beside the name in the 26.2+ mod list; also the CurseForge project avatar | 512×512, 108KB |
| `src/main/resources/storyteller.png` | the wide info-panel image (26.2+), and `logoFile` for 1.21.x and 26.1 | 1024×512, 160KB |
| `docs/store/storyteller-banner-850.png` | **upload this one to CurseForge**, then paste its forgecdn URL as the first line of `CURSEFORGE.md` | 850×425 |

The 850px width is not arbitrary: CurseForge limits description image width, so
that is the version that renders without being scaled by the browser.

Both jar images were resized from the originals (1254² and 1774×887) and
quantised with `pngquant --quality=70-95`, which took 748KB down to 268KB with
no visible banding on the gold. Worth keeping in mind if the art is ever
replaced: the raw exports would have made the jar six times larger than the
code in it, for two pictures nobody sees at full size.

`logoBlur` and `iconBlur` are both **true**. These are illustrations being
scaled down to a list row, and nearest-neighbour on a gold bevel reads as
jagged rather than as deliberate pixel art.

**One thing to watch.** The square icon is a detailed scene — wizard, DM screen,
castle, two adventurers. At mod-list size only the gold shield outline and the
wordmark will read; the figures will be mush. That is true of most detailed
icons and may be fine. LegendQuest went the other way, padding its wordmark to a
square, so if the row ever looks muddy beside it, a simplified square is the
fix and needs no code change — just replace the file.

---

## Description

**Lives in [`CURSEFORGE.md`](../CURSEFORGE.md) at the repo root**, not here — it
is the only part of this document that gets pasted verbatim, so it is a file you
can select-all and copy without picking the description out of a guide first.

The banner belongs on the first line of that file on purpose: CurseForge's editor
does not keep an uploaded image across edits, so it has to be part of the pasted
text or it goes missing every time the copy changes.

**Keep it free of blockquotes and indented code blocks.** CurseForge renders
descriptions through the same HTML sanitiser as changelogs, and those two
constructs are known to make it fail. Nothing strips them automatically, because
nothing can — the description is pasted by hand.

---

## Screenshots

**None captured yet.** This is the shot list rather than a record, and it is
ordered as an upload order: the first three have to carry the pitch, which for
this mod is *you can run a live D&D session in Minecraft* and *your players need
nothing*.

| # | Shot | Caption |
|---|---|---|
| 1 | The Storyteller wearing a mob mid-scene, speaking as it, with players watching | Take over any creature and give it your voice. Your players just see a talking wolf. |
| 2 | An unmodded vanilla client seeing that same scene, chat and all | An unmodded client, in the story. No mod install, no modpack — it all runs on the server. |
| 3 | The Standards button bar with Possess lit and naming what is worn | The tools on buttons — and they work as clickable chat for a vanilla client too. |
| 4 | `/st who` output: the roster with coloured health | See the whole table at a glance: class, level, karma, health, party, where. |
| 5 | A dressed cast NPC beside the `/st cast equip` commands that dressed it | Spawn a cast and dress it — items written exactly as `/give` takes them. |
| 6 | `/st struct place` mid-placement, and the same ground after `/st undo` | Place a building. Take it back off. Every placement snapshots the ground first. |
| 7 | A `/st title` card over a scene | Narrate: title cards, a god-voice to a radius, a whisper meant for one person. |
| 8 | `/st reward party` landing on four characters at once | Reward the whole party in one action — XP, levels, karma, reputation, money. |
| 9 | Drifting above a scene in spectator | Drift out of your body and move through the scene unseen. |

Shot 2 earns its placement for the same reason LegendQuest's vanilla-client shot
does: "your players install nothing" is the claim a reader will assume is
marketing until they see it, and a plain HUD is only convincing if the caption
says what it is.

Capture them against a dressed world rather than the dev flatland — the point of
this mod is scenes, and a scene shot on bare stone sells nothing. Screenshots
stay out of git; this table is the tracked part.
