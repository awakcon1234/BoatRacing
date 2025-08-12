# BoatRacing

An F1‑style ice boat racing plugin for Paper with a clean, vanilla‑like GUI. Manage teams, configure tracks with the built‑in BoatRacing selection tool, run timed races with checkpoints, pit area penalties, and a guided setup wizard.

> Status: Public release (1.0.3)

See `CHANGELOG.md` for the latest changes.

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

## Features
- Teams GUI: list, view, member profile, leave confirmations; optional member Rename/Change color/Disband (config‑gated) with team notifications
- Member profile: boat type picker, racer number (1–99)
- Inventory-based UI with drag blocking and sound feedback
- Text input via AnvilGUI where needed (team names, racer number)
- Track setup: per‑racer start slots, finish line region, pit area region (optional), ordered checkpoints (optional)
- Racing: lap counting with ordered checkpoints when configured, F1‑style pit area time penalty when configured, results by total time (race time + penalties)
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
1. Download the latest BoatRacing.jar from Releases.
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
- `/boatracing setup setpit` — set the pit area region from your current selection
- `/boatracing setup addcheckpoint` — add a checkpoint in order (A → B → C …)
- `/boatracing setup clearcheckpoints` — remove all checkpoints
- `/boatracing setup addlight` — add the Redstone Lamp you’re looking at as a start light (exactly 5; order left→right)
- `/boatracing setup clearlights` — remove all start lights
- `/boatracing setup addstart` — add your current position as a start slot (order matters)
- `/boatracing setup clearstarts` — remove all start slots
- `/boatracing setup show` — show a summary of the current track config
	(includes the active track name if saved/loaded from Admin Tracks GUI)
 - `/boatracing setup selinfo` — debug info about your current selection

### Guided setup (wizard)
- Start: `/boatracing setup wizard` (single entrypoint)
- Auto‑advance when possible. Navigation appears as clickable emojis on every step: ⟵ Back, ℹ Status, ✖ Cancel.

The wizard provides concise, colorized instructions with clickable actions. Steps: Starts → Finish → Start lights (5 required) → Pit area (optional) → Checkpoints (optional) → Laps → Done. It does not auto‑start races; the final prompt suggests opening registration for the currently selected track. Use `/boatracing setup wand` to get the selection tool.

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
- Entering the pit area (when configured) adds a fixed time penalty to the racer’s total time.
 - Moving forward before the countdown ends (false start) adds a fixed time penalty.
	- You can disable pit and false-start penalties via config flags.
- Results are broadcast sorted by total time = elapsed + penalties.
- On start, racers are placed on unique start slots facing forward (pitch 0) and auto‑mounted into their selected boat type.
- If 5 start lights are configured, a left-to-right lamp countdown runs (1 per second) before the race starts; lamps are lit via block data (no redstone power required).
- Total laps come from configuration (`racing.laps`) and/or the track’s saved setting; `open` and `start` don’t accept a laps argument.

### Tab‑completion
- Root: `teams`, `race`, `setup`, `reload`, `version`, `admin` (filtered by permissions)
- Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (rename/color are admin‑only via command; GUI for members can be enabled via config)
 - Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (rename/color are admin‑only via command; GUI for members can be enabled via config). Disband is not exposed as a player command; it’s a GUI action when enabled.
- Setup: `help`, `wand`, `wizard`, `addstart`, `clearstarts`, `setfinish`, `setpit`, `addcheckpoint`, `clearcheckpoints`, `addlight`, `clearlights`, `show`, `selinfo`
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
		- Tracks view (requires `boatracing.setup`): create named tracks (Create auto-loads and shows a clickable tip to paste the setup wizard command), load an existing track, delete with confirmation, and reapply selected.
		- Race view: open/close registration, start/force/stop, quick-set laps (plus custom), and remove registrants.

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
- `boatracing.teams` (default: true) — access to `/boatracing teams` (and subcommands)
- `boatracing.version` (default: true) — access to `/boatracing version`
- `boatracing.reload` (default: op) — access to `/boatracing reload`
- `boatracing.update` (default: op) — receive in‑game update notices
- `boatracing.setup` (default: op) — configure tracks and selections (wizard, lights, starts, finish, pit, checkpoints)
- `boatracing.admin` (default: op) — admin GUI and commands (manage teams and players). Also enables root tab‑completion for `admin`.
	- Admin Tracks GUI requires `boatracing.setup` to open from the Admin panel.

Race-specific permissions:
- `boatracing.race.join` (default: true) — join a registration
- `boatracing.race.leave` (default: true) — leave a registration
- `boatracing.race.status` (default: true) — view race status for a track
- `boatracing.race.admin` (default: op) — manage races: `/boatracing race open|start|force|stop <track>`

Players without `boatracing.setup` can use `/boatracing race join <track>`, `/boatracing race leave <track>`, and `/boatracing race status <track>` during an open registration (granted by the default-true permissions above).

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

## Updates & Metrics
- Update checks: GitHub Releases; console WARNs and in‑game admin notices
- Periodic update check every 5 minutes while the server is running
- bStats: enabled by default; opt‑out via the global bStats config (plugin id hardcoded)

## Storage
- `plugins/BoatRacing/teams.yml`
- `plugins/BoatRacing/racers.yml`
- Per‑track files: `plugins/BoatRacing/tracks/<name>.yml` (managed via Admin Tracks GUI)

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
