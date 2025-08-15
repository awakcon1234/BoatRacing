<!-- Language switcher with flags (hatscripts circle-flags) -->
<p align="right">
	<a href="#en" title="English">
		<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="18" height="18" alt="English" /> English
	</a>
	·
	<a href="README.es.md#es" title="Español">
		<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="18" height="18" alt="Español" /> Español
	</a>
</p>

<a id="en"></a>
# BoatRacing

[![Modrinth](https://img.shields.io/modrinth/v/boatracing?logo=modrinth&label=Modrinth)](https://modrinth.com/plugin/boatracing) [![Downloads](https://img.shields.io/modrinth/dt/boatracing?logo=modrinth&label=Downloads)](https://modrinth.com/plugin/boatracing)

[![bStats](https://bstats.org/signatures/bukkit/BoatRacing.svg)](https://bstats.org/plugin/bukkit/BoatRacing/26881)

An F1‑style ice boat racing plugin for Paper with a clean, vanilla‑like GUI. Manage teams, configure tracks with the built‑in BoatRacing selection tool, run timed races with checkpoints, pit area penalties, and a guided setup wizard.

> Status: Public release (1.0.7)

See the changelog in [CHANGELOG.md](https://github.com/Jaie55/BoatRacing/blob/main/CHANGELOG.md).

This is how to test the plugin to validate its behavior after each update: see the QA checklist in [CHECKLIST.md](CHECKLIST.md)

## What’s new (1.0.7)
Bugfixes and quality-of-life:
 - Console update check noise removed: only a single WARN shortly after startup when you are outdated (respecting `updates.console-warn`). Periodic 5‑minute checks remain but are silent.
 
 - Stability: network errors during update checks are logged at most once per server run.
 
 Removal:
 - The built‑in hiding of vanilla scoreboard numbers has been removed. If you want to hide the sidebar’s right‑side numbers, use an external plugin for now while a future built‑in approach is evaluated.
 
 UI:
 - Scoreboard redesigned: centered rows, compact “Name - L X/Y CP A/B” layout, rank colors (1=gold, 2=silver-ish, 3=bronze-ish), and your own name in green.

## What’s new (1.0.6)
Improvements and tweaks:
 - New sidebar leaderboard: the sidebar now shows the top‑10 positions in real time. Personal stats moved to the ActionBar.
 - Personal HUD: your Lap, CP and Elapsed Time now appear in the ActionBar, updated every 0.5s.
 - Sector and finish gaps: compact messages show your time gap vs the lap/finish leader at each checkpoint and at lap finish (and vs winner at race finish).
 - Start lights jitter: optional random jitter added to the lights‑out delay via `racing.lights-out-jitter-seconds`.
 - Live leaderboard: sidebar shows the top‑10 positions; your Lap/CP/Time are shown in the ActionBar (auto‑created on race start and cleaned up on stop/reset).
 - Vanilla numbers hidden: the sidebar’s right‑side numbers are hidden natively when supported by your server (Paper 1.20.5+); no TAB plugin required.
 - Layout polish: names are left‑aligned and the whole " - Lap X/Y [CP]" block is centered. Removed the decorative separator and arrow prefix, compact/dynamic padding based on the longest visible name. Long names are truncated with "..." and no extra padding is added after the ellipsis. Your own name is highlighted in green (no bold).
 - FIN label: standardized to “FINISHED”.
 - Display names: supports EssentialsX displayName and strips common rank wrappers like [Admin]/(Rank) at the start for cleaner alignment.
## What’s new (1.0.5)
Fixes and polish:
 - Team member persistence: team members are preserved across updates/reloads/startup; loading restores teams without re‑applying capacity limits.
 - Setup pit command: `/boatracing setup setpit [team]` accepts team names with spaces when quoted (e.g., "/boatracing setup setpit \"Toast Peace\""); tab‑completion suggests quoted names when the input starts with a quote.
 - Config defaults: on plugin update or `/boatracing reload`, new default keys are merged into your existing `config.yml` without overwriting your changes.
 - Boat/Raft type: racers are mounted in their selected wood variant (including chest variants and rafts) instead of always OAK; works across API versions with a safe fallback.

## What’s new (1.0.4)
- Team-specific pit areas: new unified command `/boatracing setup setpit [team]` sets the default pit when no team is provided, or the pit for a specific team when a team name is given. Tab‑completion suggests team names.
- Mandatory pitstops: new `racing.mandatory-pitstops` config (default 0). When > 0, racers must complete at least that many pit exits before they are allowed to finish; pitstops are counted on exiting the pit area and persist for the whole race.
- Wizard: Pit step updated to mention default pit vs per‑team pits and to guide the flow with clickable tips.
 - Config updates: on plugin updates/reloads, new `config.yml` keys are merged into your existing file without overwriting your changes.
 - Boat type: racers are mounted in their selected boat/raft wood variant (including chest variants) instead of always OAK; works across API versions with a safe fallback.
- Permissions: players can use `join`, `leave`, and `status` by default; only `open|start|force|stop` remain admin‑only. Removed extra runtime checks that could block players with permissive defaults.
- Boats: spawned boats now respect the player’s selected wood type robustly across API versions; falls back to OAK if the enum value is not available.
- Per‑player start slots and grid ordering: new setup commands `/boatracing setup setpos <player> <slot|auto>` and `/boatracing setup clearpos <player>`. On race start, players bound to a slot are placed there first; remaining racers are ordered by their best recorded race time on that track (fastest first), and racers without a time are placed last.
- Setup show: now also displays the presence of team‑specific pits and the number of custom start positions configured.
 - Wizard (Starts): shows optional buttons for per‑player custom slots — setpos/clearpos/auto — and displays the number of custom slots configured.

## What’s new (1.0.3)
- Admin Tracks GUI: manage multiple named tracks — Create and select, Delete (with confirmation), and Reapply selected. Requires `boatracing.setup`.
- Admin Race GUI: manage race lifecycle from a GUI — open/close registration, start/force/stop, quick-set laps, remove registrants, and handy setup tips.
- Terminology: “loaded” → “selected”; “pit lane” → “pit area”.
- All track configuration lives per‑track under `plugins/BoatRacing/tracks/<name>.yml`.
	- On startup, a legacy `track.yml` (if present) is migrated automatically to `tracks/default.yml` (with in‑game admin notice).
- Setup Wizard UX: concise, colorized, clickable. Adds a Laps step and an explicit Finish button; navigation buttons now use emojis (⟵, ℹ, ✖) and the blank line is placed at the top of the block for readability.
- Selection tool: built‑in wand (Blaze Rod). Left‑click = Corner A, right‑click = Corner B. Richer `/boatracing setup selinfo` diagnostics.
- Race commands now require a track argument: `open|join|leave|force|start|stop|status <track>`.
- Race lifecycle: “race stop” cancels registration and any running race for that track. Starts enforce unique grid slots, face forward (pitch 0), and auto‑mount racers into their selected boat. “force” and “start” use only registered participants.
- Tab‑completion: for race subcommands that take `<track>` (including `status`), it suggests existing track names.
- Admin Tracks GUI: after creating a track, sends a clickable tip to paste `/boatracing setup wizard` in chat.
- Messages remain English‑only; denial texts are hardcoded.

- Start lights + false starts: configure exactly 5 Redstone Lamps and enjoy an F1-style left-to-right light-up countdown (no redstone wiring needed). Moving forward during the countdown (false start) applies a configurable time penalty.
 - Race permissions: split by subcommand. Players can `join`, `leave`, and `status` by default; admin actions `open|start|force|stop` require `boatracing.race.admin` (or `boatracing.setup`).
- Pit area and checkpoints are now optional. Track readiness only requires at least one start slot and a finish line; the wizard labels Pit area and Checkpoints as “(optional)” and lets you skip them.
- Removed “Save as…” from the Tracks GUI (create/select, delete, and reapply remain).
 - New: live in‑race scoreboard per participant showing Lap, Checkpoints, and Elapsed Time.
 - New: crossing the pit area counts as finish for lap counting once all lap checkpoints are completed (still applies pit penalty when enabled).

## Features
- Teams GUI: list, view, member profile, leave confirmations; optional member Rename/Change color/Disband (config‑gated) with team notifications
- Member profile: boat type picker, racer number (1–99)
- Inventory-based UI with drag blocking and sound feedback
- Text input via AnvilGUI where needed (team names, racer number)
- Track setup: per‑racer start slots, finish line region, pit area region (optional), ordered checkpoints (optional)
 - Grid order: custom start slots take priority; remaining racers are placed by best recorded time (fastest first), then racers with no time.
- Racing: lap counting with ordered checkpoints when configured, F1‑style pit area time penalty when configured, results by total time (race time + penalties)
 - Live scoreboard: per-player sidebar tracking Lap, CP and Time (auto‑created on race start and cleaned up on stop/reset).
- Registration: admin opens a timed registration window; players join via command (must be in a team); force‑start supported
- Persistent storage: teams.yml, racers.yml, and per‑track files under `plugins/BoatRacing/tracks/` (no central `track.yml`).
- Update notifications and bStats metrics (enabled by default)
 - Admin GUI: manage teams (create/rename/color/add/remove/delete) and players (assign team, set racer number, set boat)
 - Tracks GUI: manage named tracks (Create now auto-loads the new track and suggests starting the setup wizard).
 - Race GUI: one-click controls for registration and race state, plus laps.
 - Admin Race GUI: open/close registration, start/force/stop the race, adjust laps, and manage registrants.

## Requirements
- Paper 1.21.8 (api-version: 1.21)
- Java 21
 

## Install
1. Download the latest BoatRacing.jar from Modrinth: https://modrinth.com/plugin/boatracing
2. Drop the jar into your `plugins/` folder.
3. Start the server to generate config and data files.

## Usage (overview)
- Use `/boatracing teams` to open the main GUI.
- Create a team and set your racer number and boat type (normal boats listed before chest variants).
- Admins can create teams, rename teams, change team color, and delete teams. Optionally, members can rename and change color from the Team GUI if enabled by config.
- Optionally, members can also disband their own team from the Team GUI if enabled by config.
- Configure a track (finish, starts, and optionally pit area and checkpoints) with the BoatRacing selection tool. Use the Tracks GUI to create/select the active track.
- Run a race with a public registration window or start immediately.

## Track setup (built-in)
Use the BoatRacing selection tool to make cuboid selections (left-click = mark Corner A, right-click = mark Corner B). The tool item is a Blaze Rod named "BoatRacing Selection Tool".

- `/boatracing setup help` — lists setup commands
- `/boatracing setup wand` — gives you the BoatRacing selection tool
- `/boatracing setup setfinish` — set the finish line region from your current selection
- `/boatracing setup setpit [team]` — set the default pit area from your current selection, or the pit area for a specific team when a team is provided (tab‑complete team names)
- `/boatracing setup addcheckpoint` — add a checkpoint in order (A → B → C …)
- `/boatracing setup clearcheckpoints` — remove all checkpoints
- `/boatracing setup addlight` — add the Redstone Lamp you’re looking at as a start light (exactly 5; order left→right)
- `/boatracing setup clearlights` — remove all start lights
- `/boatracing setup addstart` — add your current position as a start slot (order matters)
- `/boatracing setup clearstarts` — remove all start slots
- `/boatracing setup setpos <player> <slot|auto>` — bind a player to a specific start slot (1‑based) or use `auto` to remove the binding
- `/boatracing setup clearpos <player>` — remove a player’s custom start slot
- `/boatracing setup show` — show a summary of the current track config (includes team‑specific pits and count of custom start positions)
	(includes the active track name if saved/loaded from Admin Tracks GUI)
 - `/boatracing setup selinfo` — debug info about your current selection

### Guided setup (wizard)
- Start: `/boatracing setup wizard` (single entrypoint)
- Auto‑advance when possible. Navigation appears as clickable emojis on every step: ⟵ Back, ℹ Status, ✖ Cancel.

The wizard provides concise, colorized instructions with clickable actions. Steps: Starts → Finish → Start lights (5 required) → Pit area (optional) → Checkpoints (optional) → Laps → Done. The Starts step also includes optional buttons to set per‑player custom start slots (setpos/clearpos/auto) and shows the count of custom slots configured. On completion, the wizard prints a Summary that includes “Custom slots N”. It does not auto‑start races; the final prompt suggests opening registration for the currently selected track. Use `/boatracing setup wand` to get the selection tool.

Notes:
- Checkpoints (if configured) must be passed in order every lap before crossing finish; when none are configured, crossing finish counts the lap directly.
- Pit area (if configured) applies a time penalty when entered (configurable).
- Starts are used to place racers before the race (first N registered participants get the first N slots).

## Racing and registration
- `/boatracing race help` — lists race commands
- `/boatracing race open <track>` — open a registration window on the selected track and broadcast it
- `/boatracing race join <track>` — join the registration (you must be in a team)
- `/boatracing race leave <track>` — leave the registration
- `/boatracing race force <track>` — force start immediately with the registered participants (requires at least one registered)
- `/boatracing race start <track>` — start now with the registered participants (requires registration)
- `/boatracing race stop <track>` — stop and announce results; also cancels registration if still open
- `/boatracing race status <track>` — current race/registration status for that track

Race logic highlights:
- With checkpoints configured, laps count only after collecting all checkpoints in order; if no checkpoints are set, crossing finish counts the lap.
 - Entering the pit area (when configured) adds a fixed time penalty to the racer’s total time, and also counts as finish for lap progression when the lap’s checkpoints are done.
 - Moving forward before the countdown ends (false start) adds a fixed time penalty.
	- You can disable pit and false-start penalties via config flags.
- Results are broadcast sorted by total time = elapsed + penalties.
- On start, racers are placed on unique start slots facing forward (pitch 0) and auto‑mounted into their selected boat type. Grid priority: custom slot bindings first; then by best recorded time on the track (fastest first); racers without a recorded time go last.
- If 5 start lights are configured, a left-to-right lamp countdown runs (1 per second) before the race starts; lamps are lit via block data (no redstone power required).
- Total laps come from configuration (`racing.laps`) and/or the track’s saved setting; `open` and `start` don’t accept a laps argument.

### Tab‑completion
- Root: `teams`, `race`, `setup`, `reload`, `version`, `admin` (filtered by permissions)
- Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (rename/color are admin‑only via command; GUI for members can be enabled via config). Disband is not exposed as a player command; it’s a GUI action when enabled.
- Setup: `help`, `wand`, `wizard`, `addstart`, `clearstarts`, `setfinish`, `setpit`, `addcheckpoint`, `clearcheckpoints`, `addlight`, `clearlights`, `setpos`, `clearpos`, `show`, `selinfo`
 - `setpos` suggests player names, plus `auto` and slot numbers; `clearpos` suggests player names.
- Race: `help`, `open`, `join`, `leave`, `force`, `start`, `stop`, `status` — when a subcommand expects `<track>`, tab‑completion lists existing track names.
- `color` lists all DyeColors
- `boat` lists allowed boat types (normal first, then chest variants)
- `join` suggests existing team names

### Admin commands and GUI

Admins (permission `boatracing.admin`) can:

- Open Admin GUI: `/boatracing admin`
	- Teams view: list and open teams, and “Create team” button (Anvil input for name; creates a team without initial members).
	- Team view: Rename, Change color (click any dye to open picker), Add member (by name), Remove member (click head), Delete team.
 	- If enabled by config, team members will also see a Disband button in their Team view; otherwise it’s hidden.
	- Players view: Assign team / remove from team, Set racer number (1–99), Set boat type.
		- Tracks view (requires `boatracing.setup`): create named tracks (Create auto‑loads and suggests the setup wizard), load an existing track, delete with confirmation, and reapply selected.
		- Race view: open/close registration, start/force/stop, quick‑set laps (including custom), and remove registrants.

- Command alternatives:
	- `/boatracing admin team create <name> [color] [firstMember]`
	- `/boatracing admin team delete <name>`
	- `/boatracing admin team rename <old> <new>`
	- `/boatracing admin team color <name> <DyeColor>`
	- `/boatracing admin team add <name> <player>`
	- `/boatracing admin team remove <name> <player>`
	- `/boatracing admin player setteam <player> <team|none>`
	- `/boatracing admin player setnumber <player> <1-99>`
	- `/boatracing admin player setboat <player> <BoatType>`

## Permissions
- `boatracing.use` (default: true) — meta permission; grants `boatracing.teams` and `boatracing.version`
- `boatracing.teams` (default: true) — access to `/boatracing teams`
- `boatracing.version` (default: true) — access to `/boatracing version`
- `boatracing.reload` (default: op) — access to `/boatracing reload`
- `boatracing.update` (default: op) — receive in‑game update notices
- `boatracing.setup` (default: op) — configure tracks and selections (wizard, lights, starts, finish, pit, checkpoints)
- `boatracing.admin` (default: op) — admin GUI and commands (manage teams and players). Also enables root tab‑completion for `admin`.
	- Admin Tracks GUI requires `boatracing.setup` to open from the Admin panel.

Race‑specific permissions:
- `boatracing.race.join` (default: true) — join a registration
- `boatracing.race.leave` (default: true) — leave a registration
- `boatracing.race.status` (default: true) — view race status for a track
- `boatracing.race.admin` (default: op) — manage races: `/boatracing race open|start|force|stop <track>`

Players without `boatracing.setup` can use `/boatracing race join <track>`, `/boatracing race leave <track>`, and `/boatracing race status <track>` during an open registration (granted by the default‑true permissions above).

## Configuration (config.yml)
- `prefix`: message prefix
- `max-members-per-team`: maximum players in a team
- `player-actions.*`: flags to allow/deny non‑admin players to:
	- `allow-team-create` (default true)
	- `allow-team-rename` (default false) — when true, members can rename their team from the Team GUI (commands remain admin‑only)
	- `allow-team-color` (default false) — when true, members can change their team color from the Team GUI (commands remain admin‑only)
 	- `allow-team-disband` (default false) — when true, members can disband their own team from the Team GUI. The Disband icon is hidden when not allowed.
	- `allow-set-boat` (default true)
	- `allow-set-number` (default true)
	Denial messages are hardcoded in English; they are not configurable.
- `bstats.enabled`: true|false (bStats plugin-id is fixed)
- `updates.enabled`: enable update checks
- `updates.console-warn`: WARN in console when outdated
- `updates.notify-admins`: in‑game update notices for admins (`boatracing.update`)
- `racing.laps`: default race laps (int, default 3)
- `racing.pit-penalty-seconds`: time penalty applied on pit entry (double, default 5.0)
- `racing.registration-seconds`: registration window length (seconds, default 300)
 - `racing.false-start-penalty-seconds`: time penalty applied for a false start during the light countdown (double, default 3.0)
 - `racing.enable-pit-penalty`: enable/disable pit area time penalty (boolean, default true)
 - `racing.enable-false-start-penalty`: enable/disable false start penalty (boolean, default true)
 - `racing.lights-out-delay-seconds` (since 1.0.6): delay between all 5 start lights lit and “lights out”/GO (seconds, default 1.0)
 

## Updates & Metrics
- Update checks: Modrinth project; a single console WARN shortly after startup if outdated (respecting `updates.console-warn`) and in‑game admin notices (if enabled)
- Silent periodic check every 5 minutes while the server is running
- bStats: enabled by default; opt‑out via the global bStats config (plugin id hardcoded)

## Storage
- `plugins/BoatRacing/teams.yml`
- `plugins/BoatRacing/racers.yml`
- Per‑track files: `plugins/BoatRacing/tracks/<name>.yml` (managed via Admin Tracks GUI). Includes: starts, finish, pitlane, teamPits, checkpoints, start lights, and now `customStartSlots` and `bestTimes` (per‑player).

Legacy migration: if a legacy `plugins/BoatRacing/track.yml` is found on startup, it is migrated to `plugins/BoatRacing/tracks/default.yml` (or `default_N.yml`) and the old file is removed when possible. Admins with `boatracing.setup` get an in‑game notice.

## Compatibility
- Paper 1.21.8; Java 21
- English‑only messages with vanilla‑styled titles and lore
 

## Notes
- Leaderless teams: players can create and leave teams; admins handle deletion and member management. Team rename/color can optionally be enabled for members via config (GUI only).
- Leaving a team as the last member deletes the team automatically.
- Denial messages for protected actions are hardcoded in English.

## Build (developers)
- Maven project; produces `BoatRacing.jar` shaded. Run `mvn -DskipTests clean package`.

## License
Distributed under the MIT License. See `LICENSE`.
