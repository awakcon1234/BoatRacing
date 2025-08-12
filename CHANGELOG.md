# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project adheres to Semantic Versioning.

## [Unreleased]
### Added
- Admin Tracks GUI: create, select, save as, delete named tracks (with confirmation). Requires `boatracing.setup`.
- Admin Race GUI: manage race lifecycle (open/close registration, start/force/stop), quick-set/custom laps, and registrant removal.
- Active (selected) track name is displayed in Setup Wizard prompts, `/boatracing setup show`, and `/boatracing race status`.
- Tooltips (lore) for “Admin panel”, “Player view”, and “Refresh” buttons in GUIs.
- Quick navigation: from Teams GUI to Admin panel (admins only), and from Admin GUI back to player view.
- Refresh buttons in Teams and Admin GUIs.
 - Guided setup wizard with concise, colorized prompts and clickable actions. Adds a Laps step and an explicit Finish button; navigation buttons now use emojis (⟵, ℹ, ✖) and spacing puts a blank line at the top of the block.
 - Convenience selector: `/boatracing setup wand` to give the built-in selection tool.
- Team GUI: members can rename the team and change the team color when enabled via config (`player-actions.allow-team-rename` / `allow-team-color`). These actions notify all teammates.
 - Team GUI: optional member disband via config (`player-actions.allow-team-disband`). Disband uses a confirmation screen and notifies all teammates.
 - Per‑track storage: all track configuration is saved under `plugins/BoatRacing/tracks/<name>.yml`. On startup, a legacy `track.yml` is migrated to `tracks/default.yml` (or `default_N.yml`) with an in‑game admin notice.
 - Race commands now require a track argument: `open|join|leave|force|start|stop|status <track>`. Tab‑completion suggests existing track names for these.
 - Admin Tracks GUI: after creating a track, sends a clickable tip to paste `/boatracing setup wizard`.
 - Wizard labels Pit area and Checkpoints as “(optional)” and allows skipping them. Readiness requires only Starts and Finish.
 - Start lights: configure exactly 5 Redstone Lamps; race start uses an F1-style left-to-right countdown that lights lamps via block data (no redstone). New setup commands: `addlight` and `clearlights`. Wizard adds a dedicated “Start lights” step.
 - Registration: server-wide broadcast when a player joins or leaves registration.
 - False start penalties: moving forward during the start-light countdown applies a configurable time penalty (`racing.false-start-penalty-seconds`, default 3.0). Messages are in English.
 - New config flags: `racing.enable-pit-penalty` and `racing.enable-false-start-penalty` to toggle pit and false-start penalties.
 - Race permissions split: default-true for `boatracing.race.join`, `boatracing.race.leave`, and `boatracing.race.status`; admin actions require `boatracing.race.admin` (or `boatracing.setup`).

### Changed
- Footer fillers switched from LIGHT_GRAY_STAINED_GLASS_PANE to GRAY_STAINED_GLASS_PANE for a darker look.
- Denial messages for protected actions are now hardcoded in English (no longer read from config).
- Configuration cleanup: removed obsolete `teams:` section and `messages.disallowed` from `config.yml`; documentation now reflects per‑track storage only.
- README updated to document `player-actions.*` flags and storage files.
 - Setup help and tab-completion updated to include `wizard` and `wand`.
 - Admin/user notifications for team deletion/removal now use neutral phrasing (no “by an admin”).
 - Tracks GUI: terminology switched to “selected” for the active track; "Create and select" loads the newly created track and suggests starting the setup wizard.
 - Disband button is hidden for members when `player-actions.allow-team-disband` is false.
- Race lifecycle: stop cancels registration and running race; start/force operate only on registered participants. Placement enforces unique starts, pitch=0, and auto‑mounts the selected boat.
- Terminology: “loaded” → “selected”; “pit lane” → “pit area”.
 - Race commands `open` and `start` no longer accept a laps argument; laps come from configuration/track.
 - Pit area and checkpoints made optional across setup, readiness checks, and runtime logic/documentation.
 - Tracks GUI: removed “Save as…”.
 - Setup help and tab-completion now include `addlight` and `clearlights`. `/boatracing race status` and `setup show` display the number of start lights.

### Fixed
- Removed the last references to `messages.disallowed.*` in `TeamGUI` that could cause confusion.
 - Selection handling improved: built-in selection tool (left/right click); `/boatracing setup selinfo` shows richer diagnostics.
- Admin GUI: clicking any dye item in the team view now opens the color picker (previously only LIME_DYE was handled).
 - Crash on boat spawn fixed by spawning BOAT/CHEST_BOAT directly and mounting players; removed unsupported boat wood-type setter.
 - Checkpoints persistence: saving as a list and loading from both list and legacy section formats; wizard and readiness checks now correctly detect added checkpoints. Fixed a false “missing checkpoint” on `race open` after setup.
 - Players wrongly blocked from `/boatracing race join` due to a global permission gate. Now join/leave/status are allowed by default and only admin actions are gated.

## [1.0.3] - 2025-08-12
- Public release noted in README. Core gameplay, teams, GUIs, WE/FAWE setup, racing, and update checks.

[Unreleased]: https://github.com/Jaie55/BoatRacing/compare/v1.0.3...HEAD
[1.0.3]: https://github.com/Jaie55/BoatRacing/releases/tag/v1.0.3