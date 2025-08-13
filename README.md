# BoatRacing

An F1‑style ice boat racing plugin for Paper with a clean, vanilla‑like GUI. Manage teams, configure tracks with the built‑in BoatRacing selection tool, run timed races with checkpoints, pit area penalties, and a guided setup wizard.

> Status: Public release (1.0.5)

See the changelog in [CHANGELOG.md](https://github.com/Jaie55/BoatRacing/blob/main/CHANGELOG.md).

This is how to test the plugin to validate its behavior after each update: see the QA checklist in [CHECKLIST.md](CHECKLIST.md)

## What’s new (1.0.5)
Fixes and polish:
- Team member persistence: no members are lost after updates/reloads/startup; loading restores members without enforcing capacity checks.
- Setup pit command: `/boatracing setup setpit [team]` now supports team names with spaces by quoting them (e.g., "/boatracing setup setpit \"Toast Peace\""); tab‑completion suggests quoted names when the input starts with a quote.
- Config defaults: on plugin update or `/boatracing reload`, new default keys are merged into your existing `config.yml` without overwriting your changes.
- Boat/raft type: racers are mounted in their selected wood variant (including chest variants) instead of always OAK; compatible across API versions with safe fallbacks.

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
- Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (rename/color are admin‑only via command; GUI for members can be enabled via config)
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

---

# BoatRacing

Un plugin de carreras de barcos sobre hielo con estilo F1 para Paper y una GUI limpia tipo vanilla. Gestiona equipos, configura circuitos con la herramienta de selección integrada de BoatRacing, lanza carreras cronometradas con checkpoints, penalizaciones por boxes (pit) y un asistente guiado de configuración.

> Estado: Public release (1.0.5)

Consulta [CHANGELOG.md](https://github.com/Jaie55/BoatRacing/blob/main/CHANGELOG.md) para ver los últimos cambios.

Así es como se prueba el plugin para validar su funcionamiento en cada actualización: ver el checklist en [CHECKLIST.md](CHECKLIST.md)

## Novedades (1.0.5)
Correcciones y pulido:
- Persistencia de miembros del equipo: tras actualizaciones/recargas/inicio no se pierden miembros; la carga restaura sin aplicar límites de capacidad.
- Comando de pit en setup: `/boatracing setup setpit [team]` admite nombres de equipo con espacios usando comillas (p. ej., "/boatracing setup setpit \"Toast Peace\""); el autocompletado sugiere nombres entrecomillados cuando el input empieza con comillas.
- Valores por defecto de config: al actualizar el plugin o usar `/boatracing reload`, las nuevas claves por defecto se fusionan en tu `config.yml` sin sobrescribir cambios.
- Tipo de barco/raft: los corredores se montan en la variante de madera elegida (incluidas variantes con cofre) en lugar de OAK siempre; compatible entre versiones con fallback seguro.

## Novedades (1.0.4)
- Pits por equipo: comando unificado `/boatracing setup setpit [team]` que fija el pit por defecto si no se indica equipo, o el pit para un equipo concreto si se indica nombre. Autocompleta nombres de equipo.
- Paradas obligatorias: nueva config `racing.mandatory-pitstops` (por defecto 0). Si es > 0, los corredores deben completar al menos ese número de salidas de pit antes de poder finalizar; las paradas se cuentan al salir del pit y se mantienen toda la carrera.
- Wizard: paso de Pit actualizado para indicar pit por defecto vs por equipo y guiar con acciones clicables.
 - Actualización de config: al actualizar/recargar el plugin, las nuevas claves de `config.yml` se fusionan en tu archivo existente sin sobrescribir tus cambios.
 - Tipo de barco: al colocar a los corredores se usa la variante de madera elegida (incluidas variantes con cofre y raft) en lugar de OAK fijo; compatible entre versiones con fallback seguro.
- Permisos: los jugadores pueden usar `join`, `leave` y `status` por defecto; solo `open|start|force|stop` siguen siendo solo admin. Se eliminaron comprobaciones extra en runtime que podían bloquear con defaults permisivos.
- Barcos: ahora respetan de forma robusta la madera seleccionada por el jugador entre versiones del API; fallback a OAK si la enum no existe.
- Puestos de salida por jugador y orden de parrilla: nuevos comandos `/boatracing setup setpos <player> <slot|auto>` y `/boatracing setup clearpos <player>`. Al empezar, los jugadores con puesto personalizado se colocan primero; el resto se ordena por su mejor tiempo registrado en esa pista (más rápido primero), y los que no tienen tiempo van al final.
- Setup show: ahora también muestra si hay pits por equipo y el número de puestos personalizados configurados.
 - Wizard (Starts): muestra botones opcionales para puestos personalizados por jugador — setpos/clearpos/auto — y el conteo de puestos personalizados configurados.

## Novedades (1.0.3)
- Admin Tracks GUI: gestiona múltiples pistas con nombre — Crear y seleccionar, Eliminar (con confirmación) y Reaplicar la seleccionada. Requiere `boatracing.setup`.
- Admin Race GUI: gestiona el ciclo de carrera desde una GUI — abrir/cerrar registro, start/force/stop, ajuste rápido de vueltas, quitar inscritos y consejos útiles.
- Terminología: “loaded” → “selected”; “pit lane” → “pit area”.
- Toda la configuración de pista vive por pista en `plugins/BoatRacing/tracks/<name>.yml`.
	- Al iniciar, un `track.yml` heredado (si existe) se migra automáticamente a `tracks/default.yml` (con aviso in‑game a admin).
- UX del Wizard: conciso, con colores y clicable. Añade el paso de Laps y un botón Finish explícito; la navegación usa emojis (⟵, ℹ, ✖) y la línea en blanco va arriba del bloque para legibilidad.
- Herramienta de selección: varita integrada (Blaze Rod). Clic izq. = Corner A, clic der. = Corner B. Diagnóstico más rico con `/boatracing setup selinfo`.
- Los comandos de carrera ahora requieren argumento de pista: `open|join|leave|force|start|stop|status <track>`.
- Ciclo de carrera: “race stop” cancela el registro y cualquier carrera en curso para esa pista. El start impone posiciones únicas, mira hacia delante (pitch 0) y auto‑monta a los corredores en su barco seleccionado. “force” y “start” usan solo inscritos.
- Autocompletado: para subcomandos de carrera con `<track>` (incluye `status`), sugiere nombres de pista existentes.
- Admin Tracks GUI: tras crear una pista, envía un tip clicable para pegar `/boatracing setup wizard` en el chat.
- Mensajes en inglés únicamente; textos de denegación codificados.

- Luces de salida + falsas salidas: configura exactamente 5 Redstone Lamps y disfruta una cuenta regresiva estilo F1 de izquierda a derecha (sin redstone). Moverse hacia delante durante la cuenta atrás (false start) aplica una penalización de tiempo configurable.
 - Permisos de carrera: separados por subcomando. Los jugadores pueden `join`, `leave` y `status` por defecto; acciones admin `open|start|force|stop` requieren `boatracing.race.admin` (o `boatracing.setup`).
- El área de pit y los checkpoints son ahora opcionales. La preparación de pista solo requiere al menos un start y una meta; el wizard etiqueta Pit y Checkpoints como “(optional)” y permite omitir.
- Se eliminó “Save as…” de la Tracks GUI (crear/seleccionar, borrar y reaplicar permanecen).
 - Nuevo: marcador (scoreboard) en vivo por participante mostrando Lap, Checkpoints y Elapsed Time.
 - Nuevo: cruzar por el área de pit cuenta como meta para el conteo de vueltas cuando los checkpoints de la vuelta están completados (sigue aplicando penalización de pit si está activada).

## Funcionalidades
- Teams GUI: lista, ver, perfil de miembro, confirmaciones de salida; renombrar/cambiar color/disolver por miembro opcional (gobernado por config) con notificaciones al equipo
- Perfil de miembro: selector de tipo de barco, dorsal (1–99)
- UI basada en inventario con bloqueo de arrastre y feedback sonoro
- Entrada de texto vía AnvilGUI donde se necesite (nombres de equipo, dorsal)
- Configuración de pista: puestos de salida por corredor, región de meta, región de pit (opcional), checkpoints ordenados (opcional)
 - Orden de parrilla: prioridad a puestos personalizados; luego por mejor tiempo registrado (más rápido primero); después los que no tienen tiempo.
- Carreras: conteo de vueltas con checkpoints ordenados cuando estén configurados, penalización tipo F1 por entrar a pit (si está configurado), resultados por tiempo total (tiempo de carrera + penalizaciones)
- Registro: el admin abre una ventana de registro temporal; los jugadores se apuntan por comando (deben estar en un equipo); soporta force‑start
- Almacenamiento persistente: teams.yml, racers.yml y archivos por pista en `plugins/BoatRacing/tracks/` (sin `track.yml` central)
- Notificaciones de actualización y métricas bStats (activado por defecto)
 - Admin GUI: gestiona equipos (crear/renombrar/color/añadir/quitar/borrar) y jugadores (asignar equipo, dorsal, barco)
 - Tracks GUI: gestiona pistas con nombre (Create ahora auto‑carga la nueva pista y sugiere iniciar el wizard).
 - Race GUI: controles de un clic para registro y estado de la carrera, más laps.
 - Admin Race GUI: abrir/cerrar registro, start/force/stop, ajustar vueltas y gestionar inscritos.
 - Marcador en vivo: sidebar por jugador con Lap, CP y Time (se crea al iniciar y se limpia en stop/reset).

## Requisitos
- Paper 1.21.8 (api-version: 1.21)
- Java 21
 

## Instalación
1. Descarga el BoatRacing.jar más reciente desde Releases.
2. Pon el jar en la carpeta `plugins/`.
3. Arranca el servidor para generar config y datos.

## Uso (resumen)
- Usa `/boatracing teams` para abrir la GUI principal.
- Crea un equipo y establece dorsal y tipo de barco (los barcos normales aparecen antes que las variantes con cofre).
- Los admins pueden crear equipos, renombrar, cambiar color y borrar. Opcionalmente, los miembros pueden renombrar y cambiar color desde la Team GUI si está habilitado por config.
 - Opcionalmente, los miembros también pueden disolver su propio equipo desde la Team GUI si está habilitado.
- Configura una pista (meta, starts y opcionalmente pit y checkpoints) con la herramienta de selección. Usa la Tracks GUI para crear/seleccionar la pista activa.
- Ejecuta una carrera con ventana de registro pública o empieza al momento.

## Configuración de pista (integrada)
Usa la herramienta de selección para hacer selecciones cúbicas (clic izq. = Corner A, clic der. = Corner B). El ítem es una Blaze Rod llamada "BoatRacing Selection Tool".

- `/boatracing setup help` — lista comandos de setup
- `/boatracing setup wand` — te da la herramienta
- `/boatracing setup setfinish` — establece la meta desde tu selección
- `/boatracing setup setpit` — establece la región de pit desde tu selección
- `/boatracing setup addcheckpoint` — añade un checkpoint en orden (A → B → C …)
- `/boatracing setup clearcheckpoints` — elimina todos los checkpoints
- `/boatracing setup addlight` — añade la Redstone Lamp a la que miras como luz de salida (exactamente 5; orden izq→der)
- `/boatracing setup clearlights` — elimina todas las luces de salida
- `/boatracing setup addstart` — añade tu posición actual como start (el orden importa)
- `/boatracing setup clearstarts` — elimina todos los starts
- `/boatracing setup setpos <player> <slot|auto>` — asigna a un jugador un puesto de salida (1‑based) o usa `auto` para quitar la asignación
- `/boatracing setup clearpos <player>` — elimina el puesto personalizado de un jugador
- `/boatracing setup show` — muestra el resumen de la pista actual (incluye pits por equipo y número de puestos personalizados)
	(incluye el nombre de la pista activa si se guardó/cargó desde la Admin Tracks GUI)
 - `/boatracing setup selinfo` — info de depuración de tu selección actual

### Asistente guiado (wizard)
- Inicio: `/boatracing setup wizard` (único punto de entrada)
- Avanza automático cuando se puede. Navegación con emojis clicables en cada paso: ⟵ Back, ℹ Status, ✖ Cancel.

El wizard da instrucciones concisas, con colores y clicables. Pasos: Starts → Finish → Start lights (5 requeridas) → Pit area (opcional) → Checkpoints (opcional) → Laps → Done. En el paso Starts también verás botones opcionales para definir puestos personalizados por jugador (setpos/clearpos/auto) y el número de puestos personalizados configurados. Al finalizar, el wizard imprime un resumen que incluye “Custom slots N”. No inicia carreras automáticamente; el final sugiere abrir el registro para la pista seleccionada. Usa `/boatracing setup wand` para obtener la herramienta.

Notas:
- Si hay checkpoints, deben pasarse en orden cada vuelta antes de cruzar meta; si no hay checkpoints, cruzar meta cuenta la vuelta directamente.
- El área de pit (si está configurada) aplica una penalización de tiempo al entrar (configurable).
- Los starts se usan para colocar a los corredores antes de la carrera (los primeros N inscritos ocupan los primeros N puestos).

## Carreras y registro
- `/boatracing race help` — lista comandos de carrera
- `/boatracing race open <track>` — abre una ventana de registro en la pista seleccionada y lo anuncia
- `/boatracing race join <track>` — se apunta al registro (debes estar en un equipo)
- `/boatracing race leave <track>` — se sale del registro
- `/boatracing race force <track>` — arranca inmediatamente con los inscritos (requiere al menos uno)
- `/boatracing race start <track>` — empieza ahora con los inscritos (requiere registro)
- `/boatracing race stop <track>` — detiene y anuncia resultados; también cancela el registro si seguía abierto
- `/boatracing race status <track>` — estado actual de registro/carrera para esa pista

Aspectos clave de la lógica de carrera:
- Con checkpoints, las vueltas cuentan solo tras recogerlos en orden; sin checkpoints, cruzar meta cuenta la vuelta.
 - Entrar al área de pit (cuando está configurada) añade una penalización fija y también cuenta como meta para progresar la vuelta cuando ya se completaron los checkpoints de esa vuelta.
 - Moverse antes de terminar la cuenta atrás (false start) añade una penalización fija.
	- Puedes desactivar penalizaciones de pit y de falsa salida con flags de config.
- Los resultados se anuncian ordenados por tiempo total = tiempo + penalizaciones.
- Al iniciar, los corredores se colocan en starts únicos, mirando hacia delante (pitch 0) y auto‑montados en su barco. Prioridad: puestos personalizados primero; luego por mejor tiempo registrado; los que no tienen tiempo van al final.
- Si hay 5 luces configuradas, se ejecuta una cuenta regresiva de lámparas de izq. a der. (1/s) antes de empezar; se encienden por block data (sin redstone).
- El número total de vueltas viene de `racing.laps` y/o de la pista guardada; `open` y `start` no aceptan argumento de vueltas.

### Autocompletado
- Raíz: `teams`, `race`, `setup`, `reload`, `version`, `admin` (filtrado por permisos)
- Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (rename/color solo admin por comando; GUI para miembros opcional por config)
 - Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (disband no expuesto por comando jugador; es GUI cuando esté habilitado).
- Setup: `help`, `wand`, `wizard`, `addstart`, `clearstarts`, `setfinish`, `setpit`, `addcheckpoint`, `clearcheckpoints`, `addlight`, `clearlights`, `setpos`, `clearpos`, `show`, `selinfo`
 - `setpos` sugiere nombres de jugadores, y también `auto` y números de slot; `clearpos` sugiere nombres de jugadores.
- Race: `help`, `open`, `join`, `leave`, `force`, `start`, `stop`, `status` — cuando un subcomando espera `<track>`, sugiere nombres de pista.
- `color` lista todos los DyeColors
- `boat` lista tipos de barco permitidos (normales primero, luego chest)
- `join` sugiere nombres de equipos existentes

### Comandos y GUI de Admin

Admins (permiso `boatracing.admin`) pueden:

- Abrir Admin GUI: `/boatracing admin`
	- Vista Teams: listar y abrir equipos, y botón “Create team” (Anvil para nombre; crea el equipo sin miembros iniciales).
	- Vista Team: Rename, Change color (clic en cualquier tinte para abrir el picker), Add member (por nombre), Remove member (clic en cabeza), Delete team.
 	- Si está habilitado por config, los miembros verán Disband en su vista de equipo; si no, oculto.
	- Vista Players: Assign team / remove from team, Set racer number (1–99), Set boat type.
		- Vista Tracks (requiere `boatracing.setup`): crear pistas con nombre (Create auto‑carga y sugiere el wizard), cargar una existente, eliminar con confirmación y reaplicar la seleccionada.
		- Vista Race: open/close registration, start/force/stop, ajuste rápido de laps (incluida personalizada) y quitar inscritos.

- Alternativas por comando:
	- `/boatracing admin team create <name> [color] [firstMember]`
	- `/boatracing admin team delete <name>`
	- `/boatracing admin team rename <old> <new>`
	- `/boatracing admin team color <name> <DyeColor>`
	- `/boatracing admin team add <name> <player>`
	- `/boatracing admin team remove <name> <player>`
	- `/boatracing admin player setteam <player> <team|none>`
	- `/boatracing admin player setnumber <player> <1-99>`
	- `/boatracing admin player setboat <player> <BoatType>`

## Permisos
- `boatracing.use` (por defecto: true) — permiso meta; otorga `boatracing.teams` y `boatracing.version`
- `boatracing.teams` (por defecto: true) — acceso a `/boatracing teams`
- `boatracing.version` (por defecto: true) — acceso a `/boatracing version`
- `boatracing.reload` (por defecto: op) — acceso a `/boatracing reload`
- `boatracing.update` (por defecto: op) — recibir avisos de actualización in‑game
- `boatracing.setup` (por defecto: op) — configurar pistas y selecciones (wizard, luces, starts, meta, pit, checkpoints)
- `boatracing.admin` (por defecto: op) — GUI y comandos de admin (gestión de equipos y jugadores). También habilita autocompletado raíz para `admin`.
	- Admin Tracks GUI requiere `boatracing.setup` para abrir desde el panel Admin.

Permisos específicos de carrera:
- `boatracing.race.join` (por defecto: true) — unirse al registro
- `boatracing.race.leave` (por defecto: true) — salir del registro
- `boatracing.race.status` (por defecto: true) — ver estado de carrera para una pista
- `boatracing.race.admin` (por defecto: op) — gestionar carreras: `/boatracing race open|start|force|stop <track>`

Los jugadores sin `boatracing.setup` pueden usar `/boatracing race join <track>`, `/boatracing race leave <track>` y `/boatracing race status <track>` durante un registro abierto (permisos true por defecto arriba).

## Configuración (config.yml)
- `prefix`: prefijo de mensajes
- `max-members-per-team`: máximo de jugadores por equipo
- `player-actions.*`: flags para permitir/denegar a no‑admins:
	- `allow-team-create` (true por defecto)
	- `allow-team-rename` (false por defecto) — si true, los miembros pueden renombrar desde la GUI (los comandos siguen siendo solo admin)
	- `allow-team-color` (false por defecto) — si true, los miembros pueden cambiar color desde la GUI (comandos solo admin)
 	- `allow-team-disband` (false por defecto) — si true, miembros pueden disolver su equipo desde la GUI. El icono se oculta cuando no está permitido.
	- `allow-set-boat` (true por defecto)
	- `allow-set-number` (true por defecto)
	 Mensajes de denegación codificados en inglés; no configurables.
- `bstats.enabled`: true|false (plugin-id fijo)
- `updates.enabled`: habilitar checks de actualización
- `updates.console-warn`: WARN en consola si hay versión nueva
- `updates.notify-admins`: avisos in‑game para admins (`boatracing.update`)
- `racing.laps`: vueltas por defecto (int, por defecto 3)
- `racing.pit-penalty-seconds`: penalización por entrar a pit (double, por defecto 5.0)
- `racing.registration-seconds`: duración del registro (segundos, por defecto 300)
 - `racing.false-start-penalty-seconds`: penalización por falsa salida durante la cuenta atrás (double, por defecto 3.0)
 - `racing.enable-pit-penalty`: habilitar/deshabilitar penalización de pit (boolean, por defecto true)
 - `racing.enable-false-start-penalty`: habilitar/deshabilitar penalización por falsa salida (boolean, por defecto true)

## Actualizaciones y métricas
- Checks de actualización: GitHub Releases; WARN en consola y avisos in‑game a admins
- Check periódico cada 5 minutos mientras el server corre
- bStats: activado por defecto; opt‑out vía config global de bStats (plugin id fijo)

## Almacenamiento
- `plugins/BoatRacing/teams.yml`
- `plugins/BoatRacing/racers.yml`
- Archivos por pista: `plugins/BoatRacing/tracks/<name>.yml` (gestionadas vía Admin Tracks GUI). Incluye: starts, finish, pitlane, teamPits, checkpoints, start lights, y ahora `customStartSlots` y `bestTimes` (por jugador).

Migración heredada: si se encuentra `plugins/BoatRacing/track.yml` al iniciar, se migra a `plugins/BoatRacing/tracks/default.yml` (o `default_N.yml`) y se intenta borrar el legacy. Admins con `boatracing.setup` reciben aviso in‑game.

## Compatibilidad
- Paper 1.21.8; Java 21
- Mensajes solo en inglés con títulos y lore estilo vanilla
 

## Notas
- Equipos sin líder: los jugadores pueden crear y salir; los admins gestionan borrado y miembros. Rename/color para miembros puede habilitarse vía config (solo GUI).
- Salir de un equipo como último miembro lo borra automáticamente.
- Mensajes de denegación para acciones protegidas codificados en inglés.

## Build (desarrolladores)
- Proyecto Maven; produce `BoatRacing.jar` sombreado. Ejecuta `mvn -DskipTests clean package`.

## Licencia
Distribuido bajo MIT. Ver `LICENSE`.
