<!-- Conmutador de idioma con banderas (hatscripts circle-flags) -->
<p align="right">
	<a href="README.md#en" title="English">
		<img src="https://hatscripts.github.io/circle-flags/flags/gb.svg" width="18" height="18" alt="English" /> English
	</a>
	·
	<a href="#es" title="Español">
		<img src="https://hatscripts.github.io/circle-flags/flags/es.svg" width="18" height="18" alt="Español" /> Español
	</a>
</p>

<a id="es"></a>
# BoatRacing

Un plugin de carreras de barcos sobre hielo con estilo F1 para Paper y una GUI limpia tipo vanilla. Gestiona equipos, configura circuitos con la herramienta de selección integrada de BoatRacing, lanza carreras cronometradas con checkpoints, penalizaciones por boxes (pit) y un asistente guiado de configuración.

[![Modrinth](https://img.shields.io/modrinth/v/boatracing?logo=modrinth&label=Modrinth)](https://modrinth.com/plugin/boatracing) [![Descargas](https://img.shields.io/modrinth/dt/boatracing?logo=modrinth&label=Descargas)](https://modrinth.com/plugin/boatracing)

[![bStats](https://bstats.org/signatures/bukkit/BoatRacing.svg)](https://bstats.org/plugin/bukkit/BoatRacing/26881)

> Estado: Public release (1.0.8)

Consulta el changelog en [CHANGELOG.md](https://github.com/Jaie55/BoatRacing/blob/main/CHANGELOG.md).

Así se prueba el plugin para validar su comportamiento tras cada actualización: ver el checklist en [CHECKLIST.md](CHECKLIST.md)

## Novedades (1.0.8)
Mejoras y opciones:
 - HUD personalizable: nuevas flags de config para mostrar/ocultar partes del marcador y del ActionBar.
	 - `racing.ui.scoreboard.show-position|show-lap|show-checkpoints|show-pitstops|show-name`
	 - `racing.ui.actionbar.show-lap|show-checkpoints|show-pitstops|show-time`
 - Pitstops en HUD: cuando `racing.mandatory-pitstops > 0`, se muestra “PIT A/B” en la barra lateral y “Pit A/B” en el ActionBar (gobernado por los toggles anteriores).
 - El anuncio de inscripción ahora incluye el nombre de la pista y el comando exacto para unirse (`racing.registration-announce`).
 - El marcador cambia el orden a “L/CP - Nombre”; sin centrado/espaciado; los nombres se muestran tal cual (conserva el “.” inicial de Bedrock).
 - Mensaje de intento de finalización: al cruzar meta sin tener todos los checkpoints requeridos, ahora se muestra un mensaje claro al jugador (además del sonido de denegación).
 - Asistente: nuevo paso opcional “Paradas obligatorias en pit” con botones rápidos [0] [1] [2] [3].
 - Comando de setup: `/boatracing setup setpitstops <n>` establece y persiste `racing.mandatory-pitstops`.
 - Resultados: se resaltan los tres primeros con medallas 🥇/🥈/🥉 y colores de rango; mantiene sufijo de penalización si aplica; nombres renderizados de forma segura (conserva el “.” inicial de Bedrock).
 - Flujo del asistente: si ya hay pit por defecto en el paso 4, el asistente avanza automáticamente a Checkpoints (los pits por equipo siguen siendo opcionales).
 - Permisos: se introduce el wildcard `boatracing.*`. Los admins siguen teniendo absolutamente todos los permisos, ahora mediante hijos explícitos bajo `boatracing.admin` (evita herencia circular).
 - Autocompletado: los jugadores (no admin) ven `join|leave|status` bajo `/boatracing race`; los verbos solo‑admin (`open|start|force|stop`) se sugieren solo a admins.

## Novedades (1.0.7)
Correcciones y calidad de vida:
 - Ruido en consola por checks de actualización eliminado: solo un WARN único poco después de iniciar cuando estás desactualizado (respetando `updates.console-warn`). Los checks cada 5 minutos siguen pero en silencio.
 
 - Estabilidad: los errores de red durante los checks de update se registran como mucho una vez por ejecución del servidor.
 
 Eliminado:
 - Se ha retirado por completo el ocultado integrado de los números vanilla del marcador (scoreboard). Si quieres ocultar los números de la derecha del sidebar, usa por ahora un plugin externo mientras evaluamos una futura implementación integrada.
 
 UI:
 - Marcador rediseñado: filas centradas, formato compacto “Nombre - L X/Y CP A/B”, colores por posición (1=oro, 2=plateado, 3=bronze-ish) y tu nombre en verde.

## Novedades (1.0.6)
Mejoras y ajustes:
 - Clasificación en barra lateral: ahora muestra las 10 primeras posiciones en tiempo real. Tus estadísticas personales se mueven al ActionBar.
 - HUD personal: tu Vuelta, CP y Tiempo aparecen ahora en el ActionBar, actualizado cada 0,5 s.
 - Gaps por sector y meta: mensajes compactos muestran tu diferencia de tiempo vs el líder de vuelta/meta en cada checkpoint y al final de la vuelta (y vs el ganador al final de carrera).
 - Jitter en luces de salida: jitter aleatorio opcional añadido al retraso de lights‑out vía `racing.lights-out-jitter-seconds`.
 - Clasificación en vivo: la barra lateral muestra el top‑10; tu Lap/CP/Time se muestran en el ActionBar (se crea al iniciar y se limpia en stop/reset).
 - Números vanilla ocultos: los números del lado derecho del sidebar se ocultan de forma nativa cuando el servidor lo soporta (Paper 1.20.5+); no hace falta TAB.
 - Pulido del layout: nombres alineados a la izquierda y el bloque completo " - Lap X/Y [CP]" centrado. Sin separadores decorativos; padding compacto/dinámico según el nombre más largo visible. Nombres largos con "..." sin espacio extra después. Tu propio nombre va en verde (sin negrita).
 - Etiqueta de meta: estandarizada a “FINISHED”.
 - Nombres mostrados: soporta EssentialsX displayName y elimina envoltorios de rango comunes al principio (p. ej. [Admin]) para mejorar la alineación.

## Novedades (1.0.5)
Correcciones y pulido:
 - Persistencia de miembros del equipo: tras actualizaciones/recargas/inicio no se pierden miembros; la carga restaura sin re‑aplicar límites de capacidad.
 - Comando de pit en setup: `/boatracing setup setpit [team]` admite nombres de equipo con espacios usando comillas (p. ej., "/boatracing setup setpit \"Toast Peace\""); el autocompletado sugiere nombres entrecomillados cuando el input empieza con comillas.
 - Valores por defecto de config: al actualizar el plugin o usar `/boatracing reload`, las nuevas claves por defecto se fusionan en tu `config.yml` sin sobrescribir cambios.
 - Tipo de barco/raft: los corredores se montan en la variante de madera elegida (incluidas variantes con cofre y raft) en lugar de OAK siempre; compatible entre versiones con fallback seguro.

## Novedades (1.0.4)
- Pits por equipo: comando unificado `/boatracing setup setpit [team]` que fija el pit por defecto si no se indica equipo, o el pit para un equipo concreto si se indica nombre. Autocompleta nombres de equipo.
- Paradas obligatorias: nueva config `racing.mandatory-pitstops` (por defecto 0). Si es > 0, los corredores deben completar al menos ese número de salidas de pit antes de poder finalizar; las paradas se cuentan al salir del pit y se mantienen toda la carrera.
- Wizard: paso de Pit actualizado para indicar pit por defecto vs por equipo y guiar con acciones clicables.
 - Actualización de config: al actualizar/recargar el plugin, las nuevas claves de `config.yml` se fusionan en tu archivo existente sin sobrescribir tus cambios.
 - Tipo de barco: al colocar a los corredores se usa la variante de madera elegida (incluidas variantes con cofre) en lugar de OAK fijo; compatible entre versiones con fallback seguro.
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
- Configuración de pista: puestos de salida por corredor, meta, zona de pit (opcional), checkpoints ordenados (opcional)
 - Orden de parrilla: prioridad a puestos personalizados; luego por mejor tiempo registrado (más rápido primero); después los que no tienen tiempo.
- Carreras: conteo de vueltas con checkpoints ordenados cuando estén configurados, penalización tipo F1 por entrar a pit (si está configurado), resultados por tiempo total (tiempo de carrera + penalizaciones)
 - Marcador en vivo: sidebar por jugador con Lap, CP y Time (se crea al iniciar y se limpia en stop/reset).
- Registro: el admin abre una ventana de registro temporal; los jugadores se apuntan por comando (deben estar en un equipo); soporta force‑start
- Almacenamiento persistente: teams.yml, racers.yml y archivos por pista en `plugins/BoatRacing/tracks/` (sin `track.yml` central)
- Notificaciones de actualización y métricas bStats (activado por defecto)
 - Admin GUI: gestiona equipos (crear/renombrar/color/añadir/quitar/borrar) y jugadores (asignar equipo, dorsal, barco)
 - Tracks GUI: gestiona pistas con nombre (Create ahora auto‑carga la nueva pista y sugiere iniciar el wizard).
 - Race GUI: controles de un clic para registro y estado de la carrera, más laps.
 - Admin Race GUI: abrir/cerrar registro, start/force/stop, ajustar vueltas y gestionar inscritos.

## Requisitos
- Paper 1.21.8 (api-version: 1.21)
- Java 21

## Instalación
1. Descarga el BoatRacing.jar más reciente desde Modrinth: https://modrinth.com/plugin/boatracing
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
- `/boatracing setup setpit [team]` — establece el pit por defecto desde tu selección, o el pit para un equipo concreto si se indica (autocompleta nombres de equipo)
- `/boatracing setup addcheckpoint` — añade un checkpoint en orden (A → B → C …)
- `/boatracing setup clearcheckpoints` — elimina todos los checkpoints
- `/boatracing setup addlight` — añade la Redstone Lamp a la que miras como luz de salida (exactamente 5; orden izq→der)
- `/boatracing setup clearlights` — elimina todas las luces de salida
- `/boatracing setup addstart` — añade tu posición actual como start (el orden importa)
- `/boatracing setup clearstarts` — elimina todos los starts
- `/boatracing setup setpos <player> <slot|auto>` — asigna a un jugador un puesto de salida (1‑based) o usa `auto` para quitar la asignación
- `/boatracing setup clearpos <player>` — elimina el puesto personalizado de un jugador
 - `/boatracing setup setpitstops <n>` — establece el número de paradas obligatorias en pit (0 desactiva el requisito)
- `/boatracing setup show` — muestra el resumen de la pista actual (incluye pits por equipo y número de puestos personalizados)
	(incluye el nombre de la pista activa si se guardó/cargó desde la Admin Tracks GUI)
 - `/boatracing setup selinfo` — info de depuración de tu selección actual

### Asistente guiado (wizard)
- Inicio: `/boatracing setup wizard` (único punto de entrada)
- Avanza automático cuando se puede. Navegación con emojis clicables en cada paso: ⟵ Atrás, ℹ Estado, ✖ Cancelar.

El asistente proporciona instrucciones concisas, con colores y con acciones clicables. Pasos: Puestos de salida → Meta → Luces de salida (5 requeridas) → Zona de pit (opcional) → Puntos de control (opcional) → Paradas obligatorias en pit (opcional) → Vueltas → Finalizar. En el paso de Puestos de salida también verás botones opcionales para definir puestos personalizados por jugador (setpos/clearpos/auto) y se muestra el número de puestos personalizados configurados. El nuevo paso “Paradas obligatorias en pit” muestra tu valor actual y opciones rápidas [0] [1] [2] [3]. Al finalizar, el asistente imprime un resumen que incluye “Puestos personalizados N”. No inicia carreras automáticamente; el mensaje final sugiere abrir el registro para la pista seleccionada. Usa `/boatracing setup wand` para obtener la herramienta.

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
 - Paradas obligatorias: cuando `racing.mandatory-pitstops > 0`, los corredores deben completar al menos ese número de salidas de pit durante la carrera para poder finalizar.
 - Si un corredor intenta finalizar sin los checkpoints requeridos de la vuelta, se envía un mensaje claro (además del sonido de denegación).
 - Moverse antes de terminar la cuenta atrás (false start) añade una penalización fija.
	- Puedes desactivar penalizaciones de pit y de falsa salida con flags de config.
- Los resultados se anuncian ordenados por tiempo total = tiempo + penalizaciones.
 - El anuncio resalta el podio con 🥇/🥈/🥉 y colores para el top‑3.
- Al iniciar, los corredores se colocan en starts únicos, mirando hacia delante (pitch 0) y auto‑montados en su barco. Prioridad: puestos personalizados primero; luego por mejor tiempo registrado; los que no tienen tiempo van al final.
- Si hay 5 luces configuradas, se ejecuta una cuenta regresiva de lámparas de izq. a der. (1/s) antes de empezar; se encienden por block data (sin redstone).
- El número total de vueltas viene de `racing.laps` y/o de la pista guardada; `open` y `start` no aceptan argumento de vueltas.

### Autocompletado
- Raíz: `teams`, `race`, `setup`, `reload`, `version`, `admin` (filtrado por permisos)
- Teams: `create`, `rename`, `color`, `join`, `leave`, `boat`, `number`, `confirm`, `cancel` (rename/color solo admin por comando; la GUI para miembros puede habilitarse por config). Disband no está expuesto por comando de jugador; es una acción de GUI cuando está habilitado.
- Setup: `help`, `wand`, `wizard`, `addstart`, `clearstarts`, `setfinish`, `setpit`, `addcheckpoint`, `clearcheckpoints`, `addlight`, `clearlights`, `setpos`, `clearpos`, `show`, `selinfo`
 - `setpos` sugiere nombres de jugadores, y también `auto` y números de slot; `clearpos` sugiere nombres de jugadores.
- Race: los no‑admin ven `join`, `leave` y `status`; los admins también ven `open`, `start`, `force`, `stop`. Cuando un subcomando espera `<track>`, se sugieren nombres de pista existentes.
- `color` lista todos los DyeColors
- `boat` lista tipos de barco permitidos (normales primero, luego chest)
- `join` sugiere nombres de equipos existentes

### Comandos y GUI de Admin

Admins (permiso `boatracing.admin`) pueden:

- Abrir Admin GUI: `/boatracing admin`
	- Vista Teams: listar y abrir equipos, y botón “Crear equipo” (entrada con yunque; crea el equipo sin miembros iniciales).
	- Vista Team: Renombrar, Cambiar color (clic en cualquier tinte para abrir el selector), Añadir miembro (por nombre), Quitar miembro (clic en cabeza), Borrar equipo.
 	- Si está habilitado por config, los miembros verán “Disolver” en su vista de equipo; si no, se oculta.
	- Vista Players: Asignar equipo / quitar de equipo, Establecer dorsal (1–99), Establecer tipo de barco.
		- Vista Tracks (requiere `boatracing.setup`): crear pistas con nombre (Crear auto‑carga y sugiere el asistente), cargar una existente, eliminar con confirmación y reaplicar la seleccionada.
		- Vista Race: abrir/cerrar registro, start/force/stop, ajuste rápido de vueltas (incluida personalizada) y quitar inscritos.

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
- `boatracing.*` (por defecto: false) — wildcard que otorga TODOS los permisos de BoatRacing
- `boatracing.use` (por defecto: true) — permiso meta; otorga `boatracing.teams` y `boatracing.version`
- `boatracing.teams` (por defecto: true) — acceso a `/boatracing teams`
- `boatracing.version` (por defecto: true) — acceso a `/boatracing version`
- `boatracing.reload` (por defecto: op) — acceso a `/boatracing reload`
- `boatracing.update` (por defecto: op) — recibir avisos de actualización in‑game
- `boatracing.setup` (por defecto: op) — configurar pistas y selecciones (wizard, luces, starts, meta, pit, checkpoints)
- `boatracing.admin` (por defecto: op) — GUI y comandos de admin (gestión de equipos y jugadores). Otorga todos los permisos del plugin vía hijos explícitos (sin wildcard circular). También habilita autocompletado raíz para `admin`.
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
 - `racing.lights-out-delay-seconds` (desde 1.0.6): retraso entre encender las 5 luces y “apagado de luces”/GO (segundos, por defecto 1.0)

## Actualizaciones y métricas
- Checks de actualización: proyecto en Modrinth; un único WARN en consola tras el arranque si estás desactualizado (respetando `updates.console-warn`) y avisos in‑game a admins (si está habilitado)
- Check silencioso cada 5 minutos mientras el server corre
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
