# Copilot instructions (BoatRacing)

## Project context
- This is a **PaperMC-only** plugin for boat racing. It targets **Java 21 only**. Entry point: [src/main/java/es/jaie55/boatracing/BoatRacingPlugin.java](../src/main/java/es/jaie55/boatracing/BoatRacingPlugin.java).
- Tracks are persisted as YAML under the plugin data folder: `plugins/BoatRacing/tracks/<name>.yml` (see README for schema details).

## Big-picture architecture (how it actually works)
- **Runtime racing is per-track**: multiple races can run concurrently, keyed by track name.
  - Orchestrator: [src/main/java/es/jaie55/boatracing/race/RaceService.java](../src/main/java/es/jaie55/boatracing/race/RaceService.java) maps `trackName -> RaceManager` and `playerId -> trackName`.
  - Single race instance: [src/main/java/es/jaie55/boatracing/race/RaceManager.java](../src/main/java/es/jaie55/boatracing/race/RaceManager.java) owns the entire lifecycle for one track (registration, countdowns, running, finish/standings, cleanup).
- **Track editing is separate from racing**:
  - Admin “current track” editing uses the plugin’s `TrackLibrary` + `TrackConfig` (selected track in GUI/commands).
  - A running race uses a `TrackConfig` loaded from disk when the `RaceManager` is created by `RaceService`. Don’t assume `plugin.getTrackConfig()` represents the race’s track.

## Key behaviors and conventions
- **Tracks are effectively “open by default”**: joining a track implicitly opens registration if the track is idle.
  - Implemented in `RaceService.join(...)`.
- **Registration countdown starts only after the first racer joins**:
  - `RaceManager.openRegistration(...)` does not immediately schedule the waiting countdown.
  - `RaceManager.join(...)` triggers scheduling when `registered` becomes non-empty.
- **Event routing is per player**:
  - The plugin routes movement/vehicle/respawn/quit/kick events to the correct `RaceManager` using `RaceService.findRaceFor(playerId)`.
  - If you add new gameplay listeners, avoid global singleton race state; always resolve the player’s race.

## UI/HUD patterns
- Scoreboard + ActionBar is implemented in [src/main/java/es/jaie55/boatracing/ui/ScoreboardService.java](../src/main/java/es/jaie55/boatracing/ui/ScoreboardService.java).
  - It is **per-player/per-race**: each online player is mapped to a `RaceManager` via `RaceService`; players not in a race have no sidebar/actionbar.
  - Track-dependent counts (starts/checkpoints) must use `rm.getTrackConfig()` rather than `plugin.getTrackConfig()`.
  - ScoreboardLibrary is optional; the plugin logs and continues if it is missing.

## Language / localization (project rule)
- All **player-facing text must be Vietnamese** (chat messages, GUI titles, item names/lore, ActionBar/Title, scoreboard templates).
  - Prefer existing helpers like `Text.msg(...)`, `Text.item(...)`, `Text.title(...)` and the MiniMessage templates in `config.yml`.
  - Console logs can be English, but anything a player can see must not be.

## Developer workflows (Windows)
- Build: `.\gradlew.bat clean build`
- Run tests: `.\gradlew.bat test`
- Useful files:
  - Plugin config: [src/main/resources/config.yml](../src/main/resources/config.yml)
  - Plugin metadata: [src/main/resources/plugin.yml](../src/main/resources/plugin.yml)
  - Tests: [src/test/java/es/jaie55/boatracing](../src/test/java/es/jaie55/boatracing)

## Making changes safely (project-specific)
- When touching race logic, ensure you preserve:
  - Disconnect handling cleanup (`handleRacerDisconnect`) and per-track isolation.
  - Countdown/task scheduling: don’t accidentally start waiting timers when there are 0 registered racers.
- When adding commands or admin UI actions:
  - Commands operating on races should go through `RaceService` (track name is an explicit parameter in `/boatracing race ...`).
  - Admin setup/editing flows still use `TrackLibrary.select(...)` + `plugin.getTrackConfig()`.

## Examples (preferred patterns)
- Resolve a player’s race in event handlers:
  - `RaceManager rm = plugin.getRaceService().findRaceFor(player.getUniqueId());`
  - `if (rm == null) return;`
- Use race track config (not editor config):
  - `rm.getTrackConfig().getStarts().size()` / `rm.getTrackConfig().getCheckpoints().size()`
