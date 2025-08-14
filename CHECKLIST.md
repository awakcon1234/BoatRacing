README — BoatRacing QA checklist (teams, admin, tracks; two-player tests)

## What to verify for 1.0.6
- Start lights jitter and delay:
	- Configure 5 start lights and set `racing.lights-out-delay-seconds` (e.g., 1.0) and `racing.lights-out-jitter-seconds` (e.g., 0.5–1.5).
	- Start a race and observe after the 5th light: GO occurs after the fixed delay plus a small random jitter (0..value seconds).
- Sidebar leaderboard (top‑10):
	- During a race, the sidebar shows up to 10 positions sorted by: finished (time), lap desc, checkpoint desc, then total time asc.
	- Finished entries show “FIN <time>”. Unfinished show “L<curr>/<total> CP<done>/<total>”.
- ActionBar HUD:
	- Each player sees “Lap X/Y  CP A/B  Time M:SS.mmm” updating about twice per second.
	- The old per‑player sidebar lines (Lap/CP/Time) are no longer present.
- Sector and finish gaps:
	- At each checkpoint, a compact message announces the gap vs sector leader for the current lap (first crosser sets the reference).
	- At lap finish (except final lap), a gap vs lap leader is broadcast.
	- At final finish, a gap vs race winner is broadcast for non‑winners.
- Config defaults:
	- Ensure `racing.lights-out-jitter-seconds` appears in `config.yml` after update/reload (merge without overwriting custom values).

## What to verify for 1.0.5 (quick bugfix validation)
- Team persistence: after restart/update, no team members are lost; loading does not enforce capacity caps on existing teams.
- Setup pit quoting: `/boatracing setup setpit "Team With Spaces"` works; tab‑completion suggests quoted names when starting with a quote.
- Config defaults merge: delete/comment a known key (e.g., `racing.false-start-penalty-seconds`), then `/boatracing reload` — the key should reappear with default value, and existing custom values remain unchanged.
- Boat/raft type: on start, racers are mounted in their selected wood variant (including chest variants). If RAFT types exist in your server build, they are respected; otherwise BOAT types apply. No forced OAK unless fallback is required.



## Prerequisites

- Paper server 1.21.8, Java 21.
- Plugin file: `plugins/BoatRacing.jar` (shaded).
- Two online accounts: Player A and Player B.
- Minimum permission: `boatracing.use` (default: true).
- Admin permission: `boatracing.admin` (default: op). Optional for track/race setup: `boatracing.setup`. For reload: `boatracing.reload`.
- Extra note: `boatracing.setup` also enables the Tracks GUI.
 - Race permissions by subcommand:
	 - `boatracing.race.join` / `boatracing.race.leave` / `boatracing.race.status` (default: true)
	 - `boatracing.race.admin` (open/start/force/stop) (default: op)
- Optional: temporarily OP both players to speed up testing.

## Getting started

1. Copy `BoatRacing.jar` to `plugins/`.
2. Start the server; verify no startup errors.
3. Join with both players.
4. Optional: tweak `max-members-per-team` in `config.yml` to test limits; restart or run `/boatracing reload`.

## Quick UI sanity

- Run `/boatracing teams` as Player A; the main GUI opens.
- Close button works; inventory drag/move is blocked in all plugin GUIs.
- Interface text is English-only; no italics in names or lore.
- Buttons “Admin panel”, “Player view”, and “Refresh” show a short tooltip (lore) on hover.
- Footer bars use darker gray (GRAY_STAINED_GLASS_PANE).
 - Command `/boatracing setup wand` gives the built-in selection tool (Blaze Rod, “BoatRacing Selection Tool”).
- Setup Wizard shows concise, colorized English prompts (green/red states), clickable actions, and selected track name. Navigation uses emojis (⟵ Back, ℹ Status, ✖ Cancel) on every step; a blank line at the top improves readability.
 - Registration broadcasts: join/leave are announced server-wide in English.

## Core: create teams and list

- From the main GUI, Player A clicks “Create team”:
	- Opens an Anvil with blank input; enter a valid name (<= 20 chars, letters/numbers/spaces/-/_).
	- Team is created (toast sound) and its view opens.
- Duplicate/state cases:
	- Creating with an existing name → “A team with that name already exists.”
	- Already in a team → “You are already in a team. Leave it first.”
- Team list:
	- After creating a team, it appears immediately in Teams.
	- After deleting a team (Admin), it disappears without restart.
	- Team banner shows name, color, and members with racer numbers; clicking opens the team view.

## Team view (player)

- Header shows team name and members with racer #.
- Back button returns to main GUI and plays `UI_BUTTON_CLICK`.
- Background decoration uses team color.
- Clicking your own head opens “Your profile”.
- No actions on other members (leaderless model).
- If `player-actions.allow-team-rename` or `player-actions.allow-team-color` are `true`, buttons appear:
	- Rename team (Anvil): validates length/characters/duplicates; on success notifies teammates.
	- Change color (DyeColor picker): applies color and notifies teammates.
 - If `player-actions.allow-team-disband` is `true`, a “Disband team” (TNT) appears only to team members:
 	- Clicking opens confirmation “Disband team?”
 	- Confirm dissolves the team; all online members see “Your team was disbanded.”
 	- If the flag is `false`, the button must not appear.

## Join / Leave (with confirmation)

- Player B (teamless) opens the team list and enters Player A’s team view:
	- If the team isn’t full, “Join team” appears.
	- Clicking Join → “You joined <Team>”; teammates are notified; appropriate sound.
- If Player B tries to join while already in a team → denial message.
- Player B clicks “Leave team”:
	- Confirmation menu “Leave team?” opens.
	- Back returns; confirm leaves the team; player gets a message and teammates are notified; plays XP sound.
	- If leaving would make the team empty, the team is auto-deleted; the list updates.
	- Confirm/success messages are English and notify the rest of the team.

## Player profile (per-player settings)

- “Your profile” shows Player name, Team, Racer #, and Boat.

### Racer number (Anvil)
- Valid (1–99) saves and returns with XP sound: “Your racer # set to N.”
- Invalid (non-digits, 0, 100+) shows a message and keeps input.

### Boat selector
- Allows picking any allowed boat (`oak/spruce/birch/jungle/acacia/dark_oak/mangrove/cherry/pale_oak` and chest variants).
- After selecting → “Boat type set to <TYPE>.” and returns to profile.

## Admin-only actions

Admins (`boatracing.admin`) get a dedicated GUI and commands.

### Admin GUI — Teams
- Open: `/boatracing admin`.
- “Teams” view:
	- List existing teams (click to open team view).
	- “Create team” button: Anvil for name; creates team without initial member; opens its view.
	- “Player view” button: quick return to player view.
	- UI open sound present on all Admin GUI screens.
- Team view (Admin):
	- Rename team (Anvil, validates duplicates/regex/length) → success message + `ENTITY_PLAYER_LEVELUP`.
	- Change color (click any `*_DYE` to open `DyeColor` picker) → applies to team, reflected in decoration; notifies members.
	- Add member (by name; requires online player) → notifies player and team.
	- Remove member (click head) → notifies affected and team; sound to Admin.
	- Delete team (confirmation) → deletes team and updates lists.
	- “Refresh” button: reloads current list/view.

### Admin GUI — Players
- “Players” view:
	- Assign team / remove from team (“none”).
	- Set racer number (1–99) using Anvil.
	- Set boat type (same as player selector).
- Notifications and sounds are sent to affected players (both from GUI and equivalent commands).

### Admin GUI — Tracks
- Open: `/boatracing admin` → “Manage tracks” (requires `boatracing.setup`).
- Saved tracks list (MAP icon), marking “(selected)” on the active one.
- Actions:
	- Click: select track (`tracks/<name>.yml`) and apply.
	- Shift-right-click: confirmation “Delete track?”; confirm deletes file.
	- Create and select: creates `tracks/<name>.yml`, auto-selects it, and suggests starting the wizard.
	- Reapply selected: reapplies current track.
 - Drag blocking and UI sounds present.

Recommended flow:
- Select or create a track with the Tracks GUI. Configure it with the wizard.

### Admin GUI — Race
- Open from Admin: “Manage race”.
- Status card shows selected track, readiness, laps, registration, and race state.
- Buttons: Open/Close registration, Start, Force start, Stop.
- Laps: quick buttons (1/3/5/10) and custom input (Anvil).
- Registrants list (heads) allows removing a player.

### Admin commands (alternatives)
- Teams:
	- `/boatracing admin team create <name> [color] [firstMember]`
	- `/boatracing admin team delete <name>`
	- `/boatracing admin team rename <old> <new>`
	- `/boatracing admin team color <name> <DyeColor>`
	- `/boatracing admin team add <name> <player>`
	- `/boatracing admin team remove <name> <player>`
- Players:
	- `/boatracing admin player setteam <player> <team|none>`
	- `/boatracing admin player setnumber <player> <1-99>`
	- `/boatracing admin player setboat <player> <BoatType>`

## Chat commands (players)

### Open GUI
- `/boatracing teams` → opens main GUI.

- If you have admin permission, Teams GUI shows an “Admin panel” button that opens the admin panel directly.
- Teams GUI includes a “Refresh” button to reload the list.

### Create / Join / Leave
- `/boatracing teams create <name>`; duplicate/name validation applies; if already in a team, denied.
- `/boatracing teams join <team name>`; denied if full; on success, teammates are notified.
- `/boatracing teams leave` → opens confirmation and performs leave; if the team becomes empty it’s deleted.

### Number and Boat
- `/boatracing teams number <1-99>`; validation errors handled.
 - `/boatracing teams boat <MATERIAL>`; only `*_BOAT` types accepted; others rejected. Chest variants are listed after normal boats.

### Protected actions (Admin only)
- Rename/color/delete are Admin-only; via command use the `boatracing admin ...` namespace above.

## Tab-completion

- `/boatracing` → `teams`, `race`, `setup`, `reload`, `version`, `admin` (filtered by permissions; `admin` visible with permission).
- `/boatracing teams` → `create`, `join`, `leave`, `boat`, `number` (and, when applicable, `rename`, `color` for admins only).
- `/boatracing teams color` → list of dye colors.
- `/boatracing teams boat` → list of allowed boats (normal and chest).
- `/boatracing setup setpos` → suggests online player names; for the 2nd arg suggests `auto` and valid slot numbers (1-based).
- `/boatracing setup clearpos` → suggests online player names.
- `/boatracing admin team ...` and `/boatracing admin player ...` → subcommand/parameter completion (team/player names).

## Persistence and reload

- Files: `teams.yml` and `racers.yml` (team/player data).
- Tracks: per-track files in `tracks/<name>.yml` (managed via Tracks GUI). No central `track.yml`.
- After server restart:
	- Teams, colors, and membership persist.
	- Player boat and racer number persist.
- Legacy migration (when applicable):
	- If `plugins/BoatRacing/track.yml` exists on startup, it’s migrated to `tracks/default.yml` (or `default_N.yml`). Legacy file removal is attempted; in-game notice to admins (`boatracing.setup`).
- Reload (admin):
	- Without `boatracing.reload`, `/boatracing reload` → no permission.
	- With permission, `/boatracing reload` → “Plugin reloaded.” + click sound; no console errors.
	- Change `config.yml` (e.g., max members) → `/boatracing reload` → new limits apply instantly (verify join denial by capacity and button update).
	- Existing data remains after reload; GUIs may need reopening if they were open.

Updated persistence notes:
- `config.yml` no longer contains `teams:` nor messages. Persistent data lives in:
	- `plugins/BoatRacing/teams.yml` → teams { id -> name, color, members }
	- `plugins/BoatRacing/racers.yml` → per-player data (team id, number, boat)
	- `plugins/BoatRacing/tracks/<name>.yml` → per-track configuration (legacy `track.yml` is migrated on startup)

## UI polish and behavior

- No italics; item names/lore use vanilla style.
- Titles: “Teams”, “Team • <name>”, “Your profile”, “Choose team color”, “Choose your boat”, “Delete team?” (Admin), “Remove member?” (Admin), “Leave team?”, “Setup Wizard • Track: <name>”.
- Sounds:
	- Clicks/back: `UI_BUTTON_CLICK`
	- Rename success: `ENTITY_PLAYER_LEVELUP`
	- Join/leave/boat/number success: `ENTITY_EXPERIENCE_ORB_PICKUP` or similar
	- Admin create team / standout actions: `UI_TOAST_CHALLENGE_COMPLETE`
	- Delete team (confirmed): `ENTITY_GENERIC_EXPLODE`
	- Denials/errors: `BLOCK_NOTE_BLOCK_BASS`
- Inventory drag and item movement blocked in all plugin GUIs.
- Open sounds present when entering Admin GUI screens.

## Edge cases and denials

- Trying to create/join while already in a team → denial.
- Trying to join a full team → “This team is full.”
- Anvil validations keep input and play error sound on invalid entries.
- Admin-only actions (rename/color/delete/add/remove/assign) denied to players without permission.
- Admin: adding a player already in the team → proper message; removing a non-member → proper message.
- Admin: creating a team with a duplicate name → rejected with message.

Neutral phrasing:
- If an admin dissolves their own team, the message must not say “by an admin/administrator”. Keep it neutral.

## Optional checks

- Back buttons present and working in: Color picker (Admin), Boat picker, Leave confirm, Delete team confirm (Admin), Remove member confirm (Admin).
- Clicking another member’s head as a player offers no actions (leaderless model).
- After deleting a team as Admin, it disappears from lists and views after reopening.
- After leaving a team that becomes empty, the team is deleted and no longer listed.

## Compatibility notes (short)

- Built-in BoatRacing selection tool (no WorldEdit/FAWE required).
- In-game messaging is English-only. This checklist is now in English.
 - Penalties: disable via `racing.enable-pit-penalty` and/or `racing.enable-false-start-penalty`. False-start penalty seconds via `racing.false-start-penalty-seconds`. Mandatory pitstops via `racing.mandatory-pitstops` (0 = disabled).

## Track setup (built-in tool) and wizard

Goal: make a functional track with starts and finish (required). Pit and checkpoints are optional. Use the built-in selection tool only.

1) Wand and selection
- Run `/boatracing setup wand` → you should receive the tool (“BoatRacing Selection Tool”).
- With the tool: left click = Corner A; right click = Corner B.
- Verify selection with `/boatracing setup selinfo` → should show min/max and world.

2) Start the wizard
- `/boatracing setup wizard` → shows “1/5 Starts” with clickable actions (e.g., `[Add start]`).
- Per-step navigation: clickable emojis ⟵ Back, ℹ Status, ✖ Cancel.
Clickable verifications (all should paste the command to chat with hover “Click to paste: …”):
- Starts: `[Add start]`, `[Clear starts]`
 - Starts (optional custom positions): `[Set custom slot]` → `/boatracing setup setpos <player> <slot>`, `[Clear custom slot]` → `/boatracing setup clearpos <player>`, `[Auto assign]` → `/boatracing setup setpos <player> auto`. The wizard should also display “Custom slots configured: N”.
- Finish: `[Set finish]`, `[Get wand]`
- Pit area (optional): `[Set pit]` (default pit) or `[Set pit <team>]` for team-specific pits (tab‑complete), `[Get wand]`
 - Quoted names: if a team name contains spaces, set a team-specific pit using quotes, e.g., `/boatracing setup setpit "Toast Peace"`. Tab‑completion should suggest quoted names when the input starts with a quote.
- Checkpoints (optional): `[Add checkpoint]`, `[Get wand]`
- Laps: `[1]` `[3]` `[5]` and `[Finish]`
- Done: `[Open registration]`, `[Setup show]` (no “Start now” from the wizard)

3) Starts (grid slots)
- Stand on the exact block for each slot and run `/boatracing setup addstart` as many times as needed.
- After each `addstart`, the wizard returns to what’s next.
- If you make a mistake, run `/boatracing setup clearstarts`.
 - Alternative: use `[Add start]` and `[Clear starts]` clickable buttons from the wizard message.
 - Optional (custom per-player slots): `/boatracing setup setpos <player> <slot|auto>` to bind a player to a specific start slot (1-based) or remove binding with `auto`/`clearpos`. These bindings take priority at race start.

4) Finish
- Make a cuboid selection around the finish line and run `/boatracing setup setfinish`.
- The wizard advances to the next step.
 - Alternative: click `[Set finish]` (and `[Get wand]` if you need the tool).

5) Pit area (optional)
- Select the pit region and run `/boatracing setup setpit`.
 - Alternative: click `[Set pit]`.

6) Checkpoints (optional)
- For each checkpoint in order, select a region and run `/boatracing setup addcheckpoint` (A, then B, then C, ...).
- If you need to reset, run `/boatracing setup clearcheckpoints`.
 - Alternative: click `[Add checkpoint]`.

7) Review
- Run `/boatracing setup show` to see the summary (includes “Track: <name>` if saved/loaded from Admin Tracks GUI). It now also shows if there are team-specific pits and how many custom start positions exist.
- Optional: use the Tracks GUI to create multiple tracks.
 - After creating a track in the Tracks GUI, a clickable tip should appear to paste `/boatracing setup wizard`.
 - On finishing the wizard, ensure the “Summary” line includes “Custom slots N”.

## Quick race test

- With two players in teams, run `/boatracing race open <track>` (laps come from config/track). Choose the track with the Tracks GUI or pass its name.
- Both run `/boatracing race join <track>`.
 - Confirm that a non-OP player can `join` (default-true permission).
 - Verify global broadcast on join: "<Player> has registered for the race (... total)."

- Run `/boatracing race start <track>` to place players on starts and begin (no laps argument).
- Verify on start each racer is:
	- Placed on a unique start.
	- Facing forward (pitch 0).
	- Mounted in their selected boat/raft type (including chest variants). If RAFT types exist on your Paper build, they should be respected; otherwise BOAT types apply. No forced OAK unless fallback is required.
	- Grid priority respected: players with custom start slot bindings are placed on those slots first; remaining racers are ordered by best recorded time on that track (fastest first); racers without time are placed last.
- Verify that with checkpoints configured, passing them in order and crossing finish counts laps; without checkpoints, crossing finish counts laps directly; race ends after configured laps.

3) Pit penalty and pit-as-finish (optional)
- If a pit area is configured, enter during the race and confirm time penalty per `racing.pit-penalty-seconds`.
 - If start lights are configured and `racing.enable-false-start-penalty` is enabled, moving forward during the countdown applies a false-start penalty per `racing.false-start-penalty-seconds`.
 - Crossing the pit area should also count as finish for lap progression once all lap checkpoints are completed.

4) Results
- At the end, results are announced by total time (elapsed + penalties).

- “force” and “start” use only registered participants; if none are registered, the command must warn.
- `/boatracing race status <track>` shows status, selected track, and counts (starts/finish/pit/checkpoints). Tab-completion suggests track names for race subcommands that require `<track>`.
 - With a non-OP player, `status` must work; `open/start/force/stop` must be denied unless they have `boatracing.race.admin` or `boatracing.setup`.

## Selection diagnostics

- With an active selection, run `/boatracing setup selinfo`.
- It should show min/max and the world of the selection.
- If there’s no valid selection, it should show a clear English message on how to set Corner A/B with the tool.
