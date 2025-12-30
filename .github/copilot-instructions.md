
# Copilot instructions (BoatRacing)

## Project context
- PaperMC plugin, Java 21 only. Entry: [BoatRacingPlugin.java](../src/main/java/dev/belikhun/boatracing/BoatRacingPlugin.java)
- Tracks: YAML, `plugins/BoatRacing/tracks/<name>.yml` (see README for schema)

## Formatting
- Java/Gradle/Groovy: **tab indent** (tab width=4)
- YAML: **spaces only** (see .editorconfig)

## Architecture
- **Per-track runtime racing**: multiple races, keyed by track name
  - Orchestrator: `RaceService.java`
  - Single race: `RaceManager.java`
- **Track editing ≠ racing**: Admin editing uses `TrackLibrary`/`TrackConfig`; running race uses config loaded at `RaceManager` creation

## Key behaviors
- **Tracks open by default**: joining auto-opens registration if idle (`RaceService.join`)
- **Registration countdown**: starts only after first racer joins (`RaceManager.join`)
- **Event routing is per player**: always resolve player’s race via `RaceService.findRaceFor(playerId)`

## UI/HUD
- Scoreboard + ActionBar: `ScoreboardService.java`
  - Per-player/per-race; use `rm.getTrackConfig()` for track-dependent counts
  - ScoreboardLibrary is optional

## GUI UX rules
- **Scannable UI text**: add blank spacer lines between logical sections in GUI item lore
- **Track Select GUI**: one blank line between status/info and action hints; group requirements with status

## Language/localization
- All player-facing text **must be Vietnamese** (chat, GUI, HUD, templates)
- Text must look professional and consistent; formatting/icons allowed (see below)
- Use helpers: `Text.msg`, `Text.item`, `Text.title`, MiniMessage in config.yml
- Console logs may be English; player-facing must not

## UI text consistency
- **Minecraft-safe symbols only** (do not use lookalikes):
  - Bullet: `●` | Check: `✔` | Cross: `❌` | Info: `ℹ` | Time: `⌚` | Wait: `⌛ ⏳`
  - Arrows: `🡠 🡢 🡡 🡣 🡤 🡥 🡦 🡧 ⮎ ⮌ ⮏ ⮍` | Refresh: `🔁 🔂 🔃 🔄 🗘`
- **MapEngine board exception**: any Unicode supported by font (see config), but only for MapEngine boards
- **Racer display format**: `<color>[<icon> <number>] <name>`
  - Use `PlayerProfileManager.formatRacerMini` (MiniMessage) or `formatRacerLegacy` (legacy)
  - In templates, use `%racer_display%`

## Developer workflow (Windows)
- Build: `./gradlew.bat build`
- Test: `./gradlew.bat test`
- Key files: config.yml, plugin.yml, tests in `src/test/java/dev/belikhun/boatracing`
- Always build before finishing work

## Safe changes
- Race logic: preserve disconnect cleanup, per-track isolation, correct countdown/task scheduling
- Admin/command changes: go through `RaceService` for races; admin editing uses `TrackLibrary.select` + `plugin.getTrackConfig()`

## Examples
- Resolve player’s race: `RaceManager rm = plugin.getRaceService().findRaceFor(player.getUniqueId()); if (rm == null) return;`
- Use race config: `rm.getTrackConfig().getStarts().size()`
