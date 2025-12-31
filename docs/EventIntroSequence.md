# Event intro sequence (F1-style opening titles) — plan

## 1) Goals / scope
Implement a new **Event opening intro sequence** that plays when an event transitions into RUNNING, inspired by F1 opening titles.

Sequence has 3 parts:

1. **Welcome fly-by (≈ 6s)**
   - Show a welcome Title + Subtitle (Vietnamese).
   - Cinematic camera flies around lobby using **2 random camera targets**.

2. **Racers opening titles (per-racer)**
   - Admin defines:
     - A **stage spawn location** (where the featured racer appears)
     - A **MapEngine board placement behind/near the stage**
   - Everyone’s camera is **locked** to a fixed viewing position looking at the stage + board.
   - Before the first racer, show an introduction Title + Subtitle.
   - Board initially shows **server favicon**.
   - Then, for each racer (one-by-one):
     - Featured racer is teleported to the stage, gamemode set to **ADVENTURE**.
     - They can control normally.
     - Board displays that racer’s introduction card (name, number, icon, stats, etc.).

3. **Transition back to lobby + start first track countdown**
   - After the opening titles, return all participants to lobby.
   - Proceed to EventService’s “start first race track countdown”.

Non-goals (for this iteration):
- No new GUI screens. Admin setup is config/command-driven.
- No persistent replay system.
- No multi-stage / multiple camera rigs.

---

## 2) Existing code we will leverage
- Cinematic camera playback (per tick spectator teleport):
  - `dev.belikhun.boatracing.cinematic.CinematicCameraService`
- Event orchestration:
  - `dev.belikhun.boatracing.event.EventService`
- MapEngine board rendering patterns:
  - `dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService`
  - `dev.belikhun.boatracing.integrations.mapengine.EventBoardService`

Key constraints from repo rules:
- Player-facing text must be **Vietnamese**.
- Event routing should remain per-player/per-race.
- Keep race cleanup/disconnect safety.

---

## 3) Core design decisions (needs confirmation)
## 3) Confirmed decisions
This section reflects the decisions you confirmed.

### 3.1 Camera lock vs “featured racer can control”
Minecraft can’t truly lock a player’s camera while they freely move unless:
- they are in spectator and we teleport them every tick, OR
- we add extra mechanics (e.g., constantly forcing rotation), which fights player input and feels bad.

Recommended approach (clean & matches your intent):
- During the opening titles, **all participants are in a camera-locked cinematic** (spectator teleport) **except the currently featured racer**.
- The featured racer is temporarily **removed** from the cinematic, teleported to the stage in **ADVENTURE**, and can move.
- Everyone else continues watching from the locked camera viewpoint.

This preserves “featured racer can control normally” and keeps “camera locked” for the audience.

Confirmed: ✅ This is exactly the behavior we will implement.

Also confirmed: ✅ The **camera viewing position is set manually by admin** (fixed location + yaw/pitch).

### 3.2 “30fps” MapEngine animation
Minecraft server ticks are 20Hz. A Bukkit task can’t reliably run at 30Hz.

Options:
- **Option A (recommended):** run board updates at **20fps** (every tick). Animation still looks smooth.
- **Option B:** attempt async rendering at 30fps but only **send** at 20fps (wasted frames, limited benefit).

I suggest we implement **Option A** with `updateTicks: 1` for this board.

Confirmed: ✅ Lock to **20fps** (update every tick).

### 3.3 Audience definition
Confirmed: ✅ Audience is **everyone in the lobby** (not just event participants).

Working definition for “in lobby” (to implement):
- Player is online
- Player is not currently in any race (`RaceService.findRaceFor(uuid) == null`)
- (Optional, if you want) Player is in the configured lobby world/region; otherwise, treat “not in a race” as lobby.

### 3.4 Racer ordering
Confirmed: ✅ Order is a **random shuffle** of the participating racers (stable for the duration of the sequence).

---

## 4) Configuration & admin setup
Add a new config block (name suggestion):

```yml
mapengine:
  opening-titles:
    enabled: false

    # Stage spawn for featured racer
    stage:
      world: world
      x: 0
      y: 100
      z: 0
      yaw: 180
      pitch: 0

    # Camera viewing position for the audience (fixed)
    camera:
      world: world
      x: 0
      y: 102
      z: -8
      yaw: 0
      pitch: 10

    # Board placement behind/near the stage
    board:
      # same structure as mapengine.event-board.placement
      placement: {}

    # Board update rate. For “20fps”, set 1.
    update-ticks: 1

    # Optional font file + sizes (same as event-board)
    font-file: ""
    font:
      title-size: 18
      body-size: 14

    pipeline:
      buffering: true
      bundling: false

    debug: false

event:
  opening-titles:
    # Welcome fly-by length (seconds)
    welcome-seconds: 6

    # How long each racer stays featured (seconds)
    per-racer-seconds: 3

    # Delay before first racer after the intro title (seconds)
    intro-gap-seconds: 2

    # Lobby fly-by camera targets. If empty, derive from lobby spawn + radius.
    lobby-camera:
      radius: 16.0
      height: 12.0
      points: []

    # Title/subtitle copy (Vietnamese). If omitted, plugin defaults are used.
    text:
      welcome_title: "<gold>Chào mừng!"
      # Event name (suggested placeholder). If the active event has no title, fall back to a generic label.
      welcome_subtitle: "<yellow>%event_title%"
      # Intro title is intentionally empty.
      racers_intro_title: ""
      racers_intro_subtitle: "<gray>Đây là những gương mặt sẽ tranh tài hôm nay</gray>"
      # Racer card title is intentionally empty; subtitle shows racer display.
      racer_card_title: ""
      racer_card_subtitle: "%racer_display%"
      outro_title: "<gold>Bắt đầu thôi!"
      outro_subtitle: "<gray>Đang chuẩn bị chặng đua đầu tiên…</gray>"
```

Admin workflow:
1. Configure stage + camera locations.
2. Configure MapEngine board placement (can be manual in config like event-board, or we add an admin command to set it via selection, mirroring EventBoardService).

Planned admin commands (optional but high-value):
- `/boatracing openingtitles setStage` (uses player location)
- `/boatracing openingtitles setCamera` (uses player location)
- `/boatracing openingtitles setBoard [facing]` (uses wand selection bounding box + facing)
- `/boatracing openingtitles preview` (runs just the board render + static camera lock for the admin)

---

## 5) Runtime flow (detailed timeline)
### 5.1 Entry point
When the active event transitions into `RUNNING` (start event), we start the intro sequence **once**.

We will add a runtime state machine inside `EventService`:
- `OpeningTitlesState` (new, runtime-only):
  - phase: WELCOME / RACER_INTRO / RACER_N / DONE
  - startMillis
  - featuredIndex
  - audienceSet (UUIDs)
  - originalGameModes (UUID->GameMode) for restoring

EventService already has `introEndMillis` for a simple intro timer; we will replace or supersede it with the opening-titles state.

### 5.2 Phase A — Welcome fly-by (≈ 6s)
- Determine lobby world + base location:
  - Use configured lobby location if exists; otherwise use world spawn.
- Pick 2 random camera targets:
  - If `event.opening-titles.lobby-camera.points` has ≥2, pick 2 distinct.
  - Else pick 2 random polar points around base (radius/height).
- Build a `CinematicSequence`:
  - keyframe 0 → keyframe 1 (≈ 3s)
  - keyframe 1 → keyframe 2 (≈ 3s)
- Start `CinematicCameraService.start("event-opening-welcome", audiencePlayers, seq, onComplete)`
- Send Title/Subtitle in Vietnamese to all participants:
- Send Title/Subtitle in Vietnamese to all viewers (everyone in lobby):
  - Title (draft): `Chào mừng!`
  - Subtitle (draft): `%event_title%` (tên sự kiện)

### 5.3 Phase B — Racer opening titles
Setup:
- Spawn/show the MapEngine opening-titles board for watchers.
- Lock camera for all watchers:
  - Use `CinematicCameraService` with a 2-keyframe “static hold” loop (or a sequence long enough to cover whole segment).
  - Camera points to stage using `CinematicCameraService.lookAt(from, stage)` yaw/pitch.

Before first racer:
- Board shows server favicon.
- Show intro Title/Subtitle:
  - Title (draft): *(trống)*
  - Subtitle (draft): `Đây là những gương mặt sẽ tranh tài hôm nay`

Per racer i:
- Select ordering: random shuffle of participants (stable for the sequence).
- Remove this player from the cinematic lock.
- Teleport to stage; set `ADVENTURE`.
- Board switches to racer card (image + text layout):
  - Main: racer display format (use PlayerProfileManager formatting)
  - Secondary: number, icon
  - Stats: wins, completed races, personal record(s), event points/position if relevant

Title/subtitle rule for racer card (confirmed):
- Title: *(trống)*
- Subtitle: `%racer_display%`
- After `per-racer-seconds`, return racer to audience lock (spectator).

### 5.4 Exit to lobby + start first track countdown
- Stop opening-titles board service.
- Stop cinematic for everyone and restore previous gamemodes.
- Teleport all participants to lobby.
- Immediately start the first track countdown via existing event start flow.

Outro Title/Subtitle (draft):
- Title: `Bắt đầu thôi!`
- Subtitle: `Đang chuẩn bị chặng đua đầu tiên…`

---

## 6) MapEngine opening titles board
We will implement a new service similar to EventBoardService:
- `OpeningTitlesBoardService` (new)
  - Supports two screens:
    - `FAVICON` (server icon)
    - `RACER_CARD` (per racer)
  - Runs at `updateTicks=1` by default (≈20fps)
  - Uses the same font loader + RenderBuffers + MapEngineBoardDisplay

Favicon source:
- Prefer `server-icon.png` in server root if present.
- Fallback to `imgs/logo.png` bundled (already used by EventBoardService).

Layout idea:
- Fullscreen image background (favicon or gradient)
- Text overlay (name + number + icon + stats)

All on-board text should be Vietnamese labels.

---

## 7) Safety & edge cases
- Player disconnects mid-intro:
  - CinematicCameraService already records pending gamemode restores.
  - OpeningTitles state prunes offline players.
- Player joins late:
  - If they are an event participant, decide whether to include them mid-sequence or queue them for lobby (question).
- MapEngine missing/unavailable:
  - Fallback: still run camera/title sequence, but skip board rendering.
- Config missing stage/camera/board placement:
  - Fail safe: skip the racer titles phase and proceed to lobby + first track countdown.

---

## 8) Implementation steps (what I will code)
1. Add config keys to `src/main/resources/config.yml` (with safe defaults, disabled by default).
2. Add new MapEngine service `OpeningTitlesBoardService` under `dev.belikhun.boatracing.integrations.mapengine`.
3. Add a runtime controller inside `EventService`:
   - start/stop opening sequence
   - integrate with event RUNNING transition
4. Add minimal admin commands (if you want them): setStage/setCamera/setBoard/preview.
5. Add Vietnamese titles/messages via `Text.title` / `Text.msg` helpers.
6. Add unit tests where feasible (logic-level: state transitions and timing), avoid trying to test MapEngine rendering.

---

## 9) Remaining questions (small but important)
1. “Lobby” exact definition: should we treat “lobby” as **anyone not in a race**, or only players within a specific world/area?
2. If a non-event lobby viewer is currently in **creative/survival**, are you OK with temporarily switching them to spectator for the intro and restoring afterward?
3. If MapEngine is not installed/disabled, should we:
  - A) still run the titles + camera (no board), or
  - B) skip the entire opening titles sequence?
