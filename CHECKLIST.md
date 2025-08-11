README — Lista de comprobación (QA) de BoatRacing (pruebas con dos jugadores)
Requisitos previos

Servidor Paper 1.21.8, Java 21.
Archivo del plugin: plugins/BoatRacing.jar (shaded).
Dos cuentas en línea: Jugador A (líder) y Jugador B (miembro).
Permisos: boatracing.use (por defecto: true).
Permiso de administrador para recargar: boatracing.reload (por defecto: op).
Opcional: dar OP a ambos jugadores para facilitar las pruebas.
Puesta en marcha

 Copia BoatRacing.jar en plugins/.
 Inicia el servidor; asegúrate de que no hay errores al arrancar.
 Entra con ambos jugadores.
 Opcional: ajusta el máximo de miembros en config.yml para pruebas de equipo completo; reinicia el servidor.
Comprobación rápida

 Ejecuta /boatracing teams con el Jugador A; se abre la GUI.
 El botón Cerrar funciona; no se pueden mover objetos dentro de las GUIs (arrastre bloqueado).
 El texto es solo en inglés; sin cursiva en nombres/lore de ítems.
Núcleo: creación de equipos y listado

 Desde la GUI principal, el Jugador A pulsa “Create team”:
 Se abre un Anvil con el área de entrada en blanco; introduce un nombre válido (<=20 chars, letras/números/espacios/-/_).
 Se crea el equipo (sonido de toast) y se abre la vista del equipo.
 Intentos de duplicados:
 Jugador A: crear un equipo con nombre existente → “A team with that name already exists.”
 Jugador A ya en un equipo: “You are already in a team. Leave it first.”
 Desde la GUI principal, el banner muestra el conteo/lista de miembros y abre la vista del equipo.
 Actualización de listas:
  Tras crear un equipo nuevo, aparece inmediatamente en la lista de Teams.
  Tras disolver un equipo, desaparece de la lista de Teams sin reiniciar.
Vista de equipo: resumen y navegación

 El banner de cabecera muestra nombre del equipo, líder y miembros con números.
 El botón Back vuelve a la GUI principal; sonido de click.
 El color de fondo coincide con el color del equipo.
 Clicar tu propia cabeza → abre “Your profile”.
 Como no‑líder, clicar otras cabezas no hace nada (sin acciones de miembro).
Unirse/Salir (con confirmación)

 El Jugador B (sin equipo) abre la vista del equipo del Jugador A:
 Aparece “Join team” si no está lleno.
 Clicar Join → “You joined <Team>” + se notifica a compañeros.
 El Jugador B intenta unirse estando ya en un equipo → “You are already in a team…”.
 El Jugador B pulsa “Leave team”:
 Se abre el menú de confirmación “Leave team?”.
 Back vuelve; confirmar te saca del equipo; mensaje al jugador y notificación a compañeros; sonido de XP.
 Borde: si salir dejaría el equipo vacío → “You can’t leave if the team would be empty.”
Acciones solo para líder

Renombrar (GUI + comando):
 El Jugador A pulsa el papel “Rename team” → se abre Anvil; nombre válido → “Team renamed to <name>.” + sonido de level‑up.
 Nombres inválidos (vacío, >20, no coincide regex) muestran error y mantienen el input.
 Nombre duplicado rechazado con mensaje.
 Comando: /boatracing teams rename <new name> funciona; no‑líder denegado.
Cambiar color (GUI):
 El Jugador A pulsa el tinte “Change color” → se abre el selector de color.
 Elige un banner (cualquier DyeColor) → “Team color set to <COLOR>.” y vuelve a la vista del equipo.
 Intento de no‑líder de cambiar color → “Only the team leader can change the color.”
Disolver (GUI + confirmación por chat):
 El Jugador A pulsa TNT → confirmación “Disband team?”; back y confirm funcionan.
 Confirmar disuelve, notifica a miembros (“Your team was disbanded by the leader.”) y reproduce sonido de explosión para el líder; vuelve a la GUI principal.
 Ruta por chat: /boatracing teams disband → muestra instrucciones; /boatracing teams confirm ejecuta disolución; /cancel aborta (click sound). Si no hay pendiente, imprime pista.
Perfil de miembro (ajustes por jugador)

 La pantalla “Your profile” muestra Player name, Team, Racer #, Boat.
Número de corredor (Anvil):
 Válido (1–99) guarda y vuelve con sonido de XP: “Your racer # set to N.”
 Inválido (no dígitos, 0, 100+) muestra mensaje y mantiene entrada.
Selector de barco:
 Abre y elige cualquier barco permitido (oak/spruce/birch/jungle/acacia/dark_oak/mangrove/cherry/pale_oak; incluye variantes con cofre).
 Tras elegir → “Boat type set to <TYPE>.” y vuelve al perfil.
Acciones sobre miembro (líder sobre otro miembro)

 El Jugador A clica la cabeza del Jugador B → se abre “Member actions” con:
 “Transfer leadership”
 “Kick member”
 Botón Back
Transferir liderazgo (confirmación GUI):
 Abre “Transfer leadership?” y confirma.
 Antiguo líder: “You transferred the leadership to <Name>.” + sonido de toast.
 Nuevo líder: “You are now the team leader.” + sonido de level‑up.
 Otros miembros: “<Old> transferred the leadership to <New>.”
 Actualización de botones: el nuevo líder ve los botones solo‑líder.
 Denegaciones: objetivo no es miembro, o te apuntas a ti mismo → mensajes correctos.
Expulsar miembro (confirmación GUI):
 Abre “Kick member?” y confirma.
 Líder: “You kicked <Name>.” + sonido villager_no.
 Expulsado: “You have been kicked from <Team> by the leader.”
 Otros miembros notificados: “<Name> was kicked from the team.”
 Denegaciones: no se puede expulsar al líder; el objetivo debe ser miembro.
Comandos por chat (ruta completa, confirm/cancel)

Abrir GUI:
 /boatracing teams → abre la GUI principal.
Crear:
 /boatracing teams create <name>; validación de duplicados/nombre aplicada; si ya estás en equipo, se deniega.
Unirse:
 /boatracing teams join <team name>; si está lleno se deniega; al éxito se notifica a miembros.
Salir:
 /boatracing teams leave; solo no‑líder; protección contra dejar último miembro.
Número:
 /boatracing teams number <1-99>; errores de validación gestionados.
Barco:
 /boatracing teams boat <MATERIAL>; solo tipos “*_BOAT” aceptados; otros rechazados.
Expulsar (con confirmación):
 /boatracing teams kick <player>
 /boatracing teams confirm para ejecutar; /cancel para abortar (click sound).
 Aplica reglas de membresía y solo‑líder; notifica al expulsado y al equipo.
Transferir (con confirmación):
 /boatracing teams transfer <player>
 /boatracing teams confirm; /cancel para abortar.
 Mismo comportamiento de mensajes/sonidos que la GUI.
Disolver (con confirmación):
 /boatracing teams disband → /confirm ejecuta (sonido de explosión); /cancel aborta.
Uso inválido:
 Subcomando desconocido imprime la línea de uso.
 /confirm o /cancel sin acción pendiente imprime ayuda (menciona disband/transfer/kick).
Autocompletado

 /boatracing → teams, reload
 /boatracing teams → create, rename, color, join, leave, kick, boat, number, transfer, disband, confirm, cancel
 /boatracing teams color → lista de dye colors
 /boatracing teams boat → lista de barcos permitidos
 /boatracing teams kick <prefix> → sugiere miembros de tu equipo actual (te excluye); filtrado por prefijo
 /boatracing teams transfer <prefix> → igual que kick
Persistencia y recarga

 teams.yml y racers.yml se crean y actualizan con los cambios.
 Tras reiniciar el servidor:
 Persisten equipos, colores, líder y membresía.
 Persisten barco y número de corredor por jugador.
 Compatibilidad hacia atrás con config.yml antiguo (si se migra) carga correctamente.
 Migración heredada (si aplica):
  Para el servidor. Coloca un config.yml heredado con equipos y jugadores en el formato antiguo.
  Inicia el servidor. Verifica que equipos y miembros aparecen en la lista de Teams.
  Confirma que teams.yml y racers.yml se crean/rellenan con los datos migrados y los guardados futuros van a estos archivos.
 Comando de recarga (admin):
  Como no‑op (sin boatracing.reload), /boatracing reload → muestra mensaje de sin permisos.
  Como op (con boatracing.reload), /boatracing reload → funciona, imprime "Plugin reloaded." y reproduce sonido de click; sin errores en consola.
  Cambia config.yml (p. ej., max members) → /boatracing reload → los nuevos límites aplican al instante (intenta unirte superando el cupo para verificar denegación y que desaparece el botón).
  Los equipos/datos existentes permanecen tras el reload; puede que haya que reabrir GUIs si estaban abiertas.
 Persistencia de datos en acciones:
  Tras disolver, la entrada del equipo se elimina de teams.yml.
  Tras kick/leave/join, las listas de miembros en teams.yml reflejan los cambios.
  Los ajustes del corredor (boat/number) persisten en racers.yml incluso tras salir y reingresar o crear un nuevo equipo.
Pulido de UI y comportamiento

 Sin cursivas; nombres/lore de ítems con estilo vanilla.
 Títulos: “Teams”, “Team • <name>”, “Your profile”, “Choose team color”, “Choose your boat”, “Disband team?”, “Leave team?”, “Member actions”, “Transfer leadership?”, “Kick member?”
 Sonidos:
Clicks/back: UI_BUTTON_CLICK
Éxito rename: ENTITY_PLAYER_LEVELUP
Éxito join/leave/boat/number: ENTITY_EXPERIENCE_ORB_PICKUP u otros apropiados
Transfer leader (old): UI_TOAST_CHALLENGE_COMPLETE; (new): ENTITY_PLAYER_LEVELUP
Disband: ENTITY_GENERIC_EXPLODE
Denegaciones/errores: BLOCK_NOTE_BLOCK_BASS
Confirmación de kick (líder): ENTITY_VILLAGER_NO
 Arrastre en inventario y movimiento de ítems bloqueados en todas las GUIs del plugin.
Casos límite y denegaciones

 No‑líder intenta rename/color/disband/member actions → “Only the team leader can …”
 Intento de transfer a alguien que no es miembro → “Target is not a member of the team.”
 Intento de kick al líder → “You cannot kick the leader.”
 Intento de confirm sin nada pendiente → “Nothing to confirm. Use … first.”
 Intento de cancel sin nada pendiente → “Nothing to cancel.”
 Intento de unirse a equipo lleno → “This team is full.”
 Intento de crear/unirse estando ya en un equipo → muestra mensaje.
 Las validaciones en Anvil mantienen la entrada y reproducen sonido de error en entradas inválidas.
Verificaciones opcionales

 Botones Back presentes y funcionando en: Color picker, Boat picker, Leave confirm, Disband confirm, Member actions, Transfer confirm, Kick confirm.
 Clicar la cabeza de otro miembro como líder siempre lleva a “Member actions”.
 Tras transfer, el nuevo líder puede acceder a rename/color/disband; el antiguo líder ya no.