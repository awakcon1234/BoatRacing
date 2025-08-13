# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project adheres to Semantic Versioning.

## [1.0.5] - 2025-08-13
### Fixed
- Team member persistence: no members are lost after updates/reloads/startup; loader now restores members without enforcing capacity constraints.
- Setup pit command: `/boatracing setup setpit [team]` now supports team names with spaces by quoting them (e.g., "/boatracing setup setpit \"Toast Peace\""); tab‑completion suggests quoted names when the input starts with a quote.
- Config defaults: `config.yml` now merges new default keys on update/reload without overwriting user changes.
- Boat/raft type: placed vehicles now match the racer’s selected wood variant (including chest variants); no longer always spawns OAK. Compatible across API versions with safe fallbacks.

### Corregido (ES)
- Persistencia de miembros: no se pierden miembros tras actualizar/recargar/arrancar; la carga restaura sin imponer límites de capacidad.
- Setup pit: `/boatracing setup setpit [team]` admite nombres de equipo con espacios entre comillas (p. ej., "/boatracing setup setpit \"Toast Peace\""); el autocompletado sugiere nombres entrecomillados cuando el input empieza con comillas.
- Defaults de config: `config.yml` fusiona nuevas claves por defecto al actualizar/recargar sin sobrescribir cambios del usuario.
- Tipo de barco/raft: ahora coincide con la variante elegida por el jugador (incluidas variantes con cofre); ya no aparece siempre OAK. Compatible entre versiones con fallback seguro.

## [1.0.4] - 2025-08-13
### Added
- Team-specific pit areas via unified command `/boatracing setup setpit [team]` (tab‑completion for team names). Wizard updated accordingly.
- Mandatory pitstops via config `racing.mandatory-pitstops` (default 0). Pitstops increment on pit exit and are required to finish when > 0.
 - Config defaults: `config.yml` now merges new default keys on update/reload without overwriting user changes.
 - Boat/raft type: placed vehicles now match the racer’s selected wood variant (including chest variants); no longer always spawns OAK. Compatible across API versions with safe fallbacks.
 - Per-player custom start slots with `/boatracing setup setpos <player> <slot|auto>` and `/boatracing setup clearpos <player>`; tab completion for player names, `auto`, and slot numbers. Slots are 1-based in the command and stored 0-based.
 - Grid ordering by best recorded time per track (fastest first); racers without a time are placed after those with times.
 - Setup show now includes the presence of team-specific pits and the number of custom start positions configured.
 - Wizard (Starts): added optional clickable actions for custom start slots (setpos/clearpos/auto) and a counter of configured custom slots.

### Changed
- Permissions: players can use `join`, `leave`, and `status` by default; only `open|start|force|stop` remain admin‑only. Removed extra runtime permission checks for join/leave.

### Fixed
- Boats now spawn with the player’s selected wood type using a resilient enum mapping with safe fallback to OAK across API versions.

## [Unreleased]
### Added
- Live scoreboard: per-player sidebar showing Lap, Checkpoints, and Elapsed Time with periodic updates; created on race start and cleared on stop/reset/cancel.

### Fixed
- Pitstop as finish: crossing the configured pit area now counts as finish for lap progression once all checkpoints for the lap have been collected (pit time penalty still applies when enabled).

### Añadido (ES)
- Marcador en vivo: panel lateral por jugador con Vuelta, Checkpoints y Tiempo transcurrido, con actualizaciones periódicas; se crea al iniciar la carrera y se limpia al parar/reiniciar/cancelar.

### Corregido (ES)
- “Pit como meta”: cruzar por el área de boxes ahora cuenta como línea de meta para la progresión de vuelta cuando se han completado los checkpoints de esa vuelta (la penalización de boxes se mantiene si está habilitada).

## [1.0.3]
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
 - Live scoreboard: per-player sidebar showing Lap, Checkpoints, and Elapsed Time with periodic updates; created on race start and cleared on stop/reset/cancel.
 - Pitstop as finish: crossing the configured pit area now counts as finish for lap progression once all checkpoints for the lap have been collected (pit time penalty still applies when enabled).

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

 [Unreleased]: https://github.com/Jaie55/BoatRacing/compare/v1.0.5...HEAD

[1.0.5]: https://github.com/Jaie55/BoatRacing/releases/tag/v1.0.5

[1.0.4]: https://github.com/Jaie55/BoatRacing/releases/tag/v1.0.4

[1.0.3]: https://github.com/Jaie55/BoatRacing/releases/tag/v1.0.3
