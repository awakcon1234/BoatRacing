# BoatRacing

An F1‑style ice boat racing plugin for Paper with a clean, vanilla‑like GUI. Manage teams, colors, boats, and racer numbers fully in‑game.

> Status: Public release (1.0.2)

## Features
- Teams GUI: list, view, member actions, disband/leave confirmations
- Member profile: dye color picker, boat type picker, racer number (1–99)
- Inventory-based UI with drag blocking and sound feedback
- Text input via AnvilGUI where needed (team names, racer number)
- Persistent storage (teams.yml, racers.yml)
- Update notifications and bStats metrics (enabled by default)

## Requirements
- Paper 1.21.8 (api-version: 1.21)
- Java 21

## Install
1. Download the latest BoatRacing.jar from Releases.
2. Drop the jar into your `plugins/` folder.
3. Start the server to generate config and data files.

## Usage (overview)
- Use `/boatracing teams` to open the main GUI.
- Create a team, pick a color, set your racer number and boat type.
- Leaders can rename the team, change color, transfer leadership, and disband.
- Non‑leaders can leave the team via confirmation menus.

## Commands
- `/boatracing teams`
	- `create <name>` — create a team (name: <= 20 chars, letters/numbers/spaces/-/_)
	- `rename <new name>` — leader only
	- `color <dyeColor>` — leader only (autocomplete provided)
	- `join <team>` — join a team if there’s a slot (autocomplete team names)
	- `leave` — leave if not leader and team wouldn’t be left empty
	- `kick <player>` — leader only, requires `/boatracing teams confirm`
	- `transfer <player>` — leader only, requires `/boatracing teams confirm`
	- `boat <type>` — set your boat (normal boats first, then chest boats)
	- `number <1-99>` — set your racer number
	- `disband` — leader only, requires `/boatracing teams confirm`
	- `confirm` / `cancel` — confirm or cancel pending actions
- `/boatracing version`
	- Shows plugin name and version
- `/boatracing reload`
	- Safely reloads config and data

### Tab‑completion
- Root: `teams`, `reload`, `version` (filtered by permissions)
- Teams: `create`, `rename`, `color`, `join`, `leave`, `kick`, `boat`, `number`, `transfer`, `disband`, `confirm`, `cancel`
- `color` lists all DyeColors
- `boat` lists allowed boat types (normal first, then chest variants)
- `join` suggests existing team names
- `kick` / `transfer` suggest your current team members (excluding yourself)

## Permissions
- `boatracing.teams` (default: true) — access to `/boatracing teams` (and subcommands)
- `boatracing.version` (default: true) — access to `/boatracing version`
- `boatracing.reload` (default: op) — access to `/boatracing reload`
- `boatracing.update` (default: op) — receive in‑game update notices

## Configuration (config.yml)
- `prefix`: message prefix
- `max-members-per-team`: maximum players in a team
- `bstats.enabled`: true|false (bStats plugin-id is fixed)
- `updates.enabled`: enable update checks
- `updates.console-warn`: WARN in console when outdated
- `updates.notify-admins`: in‑game update notices for admins (`boatracing.update`)

## Updates & Metrics
- Update checks: GitHub Releases; console WARNs and in‑game admin notices
- Periodic update check every 5 minutes while the server is running
- bStats: enabled by default; opt‑out via the global bStats config (plugin id hardcoded)

## Storage
- `plugins/BoatRacing/teams.yml`
- `plugins/BoatRacing/racers.yml`

## Compatibility
- Paper 1.21.8; Java 21
- English‑only messages with vanilla‑styled titles and lore

## Build (developers)
- Maven project; produces `BoatRacing.jar` shaded. Run `mvn -DskipTests clean package`.

## License
Distributed under the MIT License. See `LICENSE`.
