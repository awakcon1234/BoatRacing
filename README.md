# BoatRacing

An F1‑style ice boat racing plugin for Paper with a clean, vanilla‑like GUI. Manage teams, configure tracks with the built‑in BoatRacing selection tool, run timed races with checkpoints, pit lane penalties, and an optional registration phase.

> Status: Public release (1.0.3)

See `CHANGELOG.md` for the latest changes.

## What’s new (Unreleased)
- Admin Tracks GUI: manage multiple named tracks (create, load, save as, delete with confirmation) — `boatracing.setup` required.
- Active track shown in: Setup Wizard prompts, `/boatracing setup show`, and `/boatracing race status`.
- Tooltips (lore) for “Admin panel”, “Player view”, and “Refresh” buttons in GUIs.
- Quick navigation: from Teams GUI to Admin panel (admins only), and from Admin GUI back to player view.
- Refresh buttons in Teams and Admin GUIs.
- Darker footer fillers: GRAY_STAINED_GLASS_PANE.
- Denial messages for protected actions are hardcoded in English (no longer configurable).
- Configuration cleanup: removed obsolete `teams:` section and `messages.disallowed` from `config.yml`; clarified that persistence is in `teams.yml`, `racers.yml`, and `track.yml`.
- Guided setup wizard with clear English prompts that auto-advances: `/boatracing setup wizard start|back|status|cancel`.
- Convenience selector: `/boatracing setup wand` gives the built-in selector item.
- Selection handling strengthened: built-in left/right click sets points. Improved `/boatracing setup selinfo` diagnostics.
- Tab‑completion updated for setup: includes `wizard` and `wand`.

## Features
- Teams GUI: list, view, member profile, leave confirmations
- Member profile: boat type picker, racer number (1–99)
- Inventory-based UI with drag blocking and sound feedback
- Text input via AnvilGUI where needed (team names, racer number)
- Track setup: per‑racer start slots, finish line region, pit lane region, ordered checkpoints
- Racing: lap counting with ordered checkpoints, F1‑style pit lane time penalty, results by total time (race time + penalties)
- Registration: admin opens a timed registration window; players join via command (must be in a team); force‑start supported
- Persistent storage (teams.yml, racers.yml, track.yml; multi-track files live under `plugins/BoatRacing/tracks/`)
- Update notifications and bStats metrics (enabled by default)
 - Admin GUI: manage teams (create/rename/color/add/remove/delete) and players (assign team, set racer number, set boat)
 - Tracks GUI: manage named tracks (Create now auto-loads the new track and suggests starting the setup wizard).

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
- Admins can create teams (via Admin GUI), rename teams, change team color, and delete teams (player actions are leaderless).
- Configure a track (finish, pit, checkpoints, starts) with the BoatRacing selection tool.
- Run a race with a public registration window or start immediately.

## Track setup (built-in)
Use the BoatRacing selection tool to make cuboid selections (left-click = mark Corner A, right-click = mark Corner B). The tool item is a Blaze Rod named "BoatRacing Selection Tool".

- `/boatracing setup help` — lists setup commands
- `/boatracing setup wand` — gives you the BoatRacing selection tool
- `/boatracing setup setfinish` — set the finish line region from your current selection
- `/boatracing setup setpit` — set the pit lane region from your current selection
- `/boatracing setup addcheckpoint` — add a checkpoint in order (A → B → C …)
- `/boatracing setup clearcheckpoints` — remove all checkpoints
- `/boatracing setup addstart` — add your current position as a start slot (order matters)
- `/boatracing setup clearstarts` — remove all start slots
- `/boatracing setup show` — show a summary of the current track config
	(includes the active track name if saved/loaded from Admin Tracks GUI)
 - `/boatracing setup selinfo` — debug info about your current selection

### Guided setup (wizard)
- Start: `/boatracing setup wizard start`
- The wizard auto-advances when each step is completed; use `/boatracing setup wizard back` if you need to return to the previous step.
- Status/cancel: `/boatracing setup wizard status` and `/boatracing setup wizard cancel`

The wizard gives concise English instructions with the exact next command to type. After each setup action (e.g., `addstart`, `setfinish`, `setpit`, `addcheckpoint`) it re‑prompts you with what to do next. Use `/boatracing setup wand` if you need the selection tool.

Notes:
- Checkpoints must be passed in order every lap before crossing finish.
- Pit lane applies a time penalty when entered (configurable).
- Starts are used to place racers before the race (first N participants get the first N slots).

## Racing and registration
- `/boatracing race help` — lists race commands
- `/boatracing race open [laps]` — open a registration window and broadcast it (default laps from config)
- `/boatracing race join` — join the registration (you must be in a team)
- `/boatracing race leave` — leave the registration
- `/boatracing race force` — force start immediately with the registered participants
- `/boatracing race start [laps]` — start now with eligible players: if a registration is open it uses the registered participants; otherwise it uses all online players who are in a team (bypasses registration)
- `/boatracing race stop` — stop and announce results
- `/boatracing race status` — current race/registration status (includes active track name)

Race logic highlights:
- Laps count only when all checkpoints in order have been collected for the lap.
- Entering the pit lane adds a fixed time penalty to the racer’s total time.
- Results are broadcast sorted by total time = elapsed + penalties.
- On start, racers are placed on start slots facing forward (pitch 0) and mounted into their selected boat type.

### Tab‑completion
- Root: `teams`, `race`, `setup`, `reload`, `version`, `admin` (filtered by permissions)
- Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (note: rename/color are admin-only via command)
- Setup: `help`, `wand`, `wizard`, `addstart`, `clearstarts`, `setfinish`, `setpit`, `addcheckpoint`, `clearcheckpoints`, `show`, `selinfo`
- Race: `help`, `open`, `join`, `leave`, `force`, `start`, `stop`, `status`
- `color` lists all DyeColors
- `boat` lists allowed boat types (normal first, then chest variants)
- `join` suggests existing team names

### Admin commands and GUI

Admins (permission `boatracing.admin`) can:

- Open Admin GUI: `/boatracing admin`
	- Teams view: list and open teams, and “Create team” button (Anvil input for name; creates a team without initial members).
	- Team view: Rename, Change color, Add member (by name), Remove member (click head), Delete team.
	- Players view: Assign team / remove from team, Set racer number (1–99), Set boat type.
		- Tracks view (requires `boatracing.setup`): create named tracks (Create auto-loads and suggests the setup wizard), load an existing track, save current track as a new name, delete with confirmation.

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
- `boatracing.setup` (default: op) — configure track and manage races (`/boatracing setup`, `/boatracing race open/force/start/stop`)
- `boatracing.admin` (default: op) — admin GUI and commands (manage teams and players). Also enables root tab‑completion for `admin`.
	- Admin Tracks GUI requires `boatracing.setup` to open from the Admin panel.

Players without `boatracing.setup` can still use `/boatracing race join`, `/boatracing race leave`, and `/boatracing race status` during an open registration.

## Configuration (config.yml)
- `prefix`: message prefix
- `max-members-per-team`: maximum players in a team
- `player-actions.*`: flags to allow/deny non‑admin players to:
	- `allow-team-create` (default true)
	- `allow-team-rename` (default false)
	- `allow-team-color` (default false)
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

## Updates & Metrics
- Update checks: GitHub Releases; console WARNs and in‑game admin notices
- Periodic update check every 5 minutes while the server is running
- bStats: enabled by default; opt‑out via the global bStats config (plugin id hardcoded)

## Storage
- `plugins/BoatRacing/teams.yml`
- `plugins/BoatRacing/racers.yml`
- `plugins/BoatRacing/track.yml`
- Multi-track files: `plugins/BoatRacing/tracks/<name>.yml` (managed via Admin Tracks GUI)

## Compatibility
- Paper 1.21.8; Java 21
- English‑only messages with vanilla‑styled titles and lore
 

## Notes
- Leaderless teams: players can create and leave teams; admins handle rename/color/deletion and member management via `/boatracing admin`.
- Leaving a team as the last member deletes the team automatically.
 - Denial messages for protected actions are hardcoded in English.

## Build (developers)
- Maven project; produces `BoatRacing.jar` shaded. Run `mvn -DskipTests clean package`.

## License
Distributed under the MIT License. See `LICENSE`.
