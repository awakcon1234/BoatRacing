package dev.belikhun.boatracing;

import org.bukkit.Bukkit;
// No TabExecutor needed: JavaPlugin already handles CommandExecutor and TabCompleter when overriding methods
import org.bukkit.plugin.java.JavaPlugin;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.track.TrackLibrary;
import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.setup.SetupWizard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class BoatRacingPlugin extends JavaPlugin {
	private static BoatRacingPlugin instance;
	private dev.belikhun.boatracing.ui.AdminGUI adminGUI;
	private dev.belikhun.boatracing.ui.AdminRaceGUI adminRaceGUI;
	private dev.belikhun.boatracing.ui.AdminEventGUI adminEventGUI;
	private dev.belikhun.boatracing.profile.PlayerProfileManager profileManager;
	private dev.belikhun.boatracing.ui.ProfileGUI profileGUI;
	private dev.belikhun.boatracing.ui.TrackSelectGUI trackSelectGUI;
	private dev.belikhun.boatracing.ui.HotbarService hotbarService;
	private dev.belikhun.boatracing.ui.ScoreboardService scoreboardService;
	private String prefix;
	private TrackConfig trackConfig;
	private TrackLibrary trackLibrary;
	private dev.belikhun.boatracing.race.RaceService raceService;
	private dev.belikhun.boatracing.event.EventService eventService;
	private SetupWizard setupWizard;
	private dev.belikhun.boatracing.ui.AdminTracksGUI tracksGUI;
	private dev.belikhun.boatracing.track.TrackRecordManager trackRecordManager;
	private dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService lobbyBoardService;
	private dev.belikhun.boatracing.cinematic.CinematicCameraService cinematicCameraService;
	// Plugin metadata (avoid deprecated getDescription())
	private String pluginVersion = "unknown";
	private java.util.List<String> pluginAuthors = java.util.Collections.emptyList();
	// Team and pit features removed

	public static BoatRacingPlugin getInstance() {
		return instance;
	}

	public String pref() {
		return prefix;
	}

	public dev.belikhun.boatracing.ui.AdminGUI getAdminGUI() {
		return adminGUI;
	}

	public dev.belikhun.boatracing.ui.AdminRaceGUI getAdminRaceGUI() {
		return adminRaceGUI;
	}

	public dev.belikhun.boatracing.ui.AdminEventGUI getAdminEventGUI() {
		return adminEventGUI;
	}

	public dev.belikhun.boatracing.profile.PlayerProfileManager getProfileManager() {
		return profileManager;
	}

	public dev.belikhun.boatracing.ui.ProfileGUI getProfileGUI() {
		return profileGUI;
	}

	public dev.belikhun.boatracing.ui.TrackSelectGUI getTrackSelectGUI() {
		return trackSelectGUI;
	}

	public dev.belikhun.boatracing.ui.HotbarService getHotbarService() {
		return hotbarService;
	}

	public dev.belikhun.boatracing.ui.ScoreboardService getScoreboardService() {
		return scoreboardService;
	}

	public dev.belikhun.boatracing.race.RaceService getRaceService() {
		return raceService;
	}

	public dev.belikhun.boatracing.event.EventService getEventService() {
		return eventService;
	}

	public TrackConfig getTrackConfig() {
		return trackConfig;
	}

	public TrackLibrary getTrackLibrary() {
		return trackLibrary;
	}

	public dev.belikhun.boatracing.ui.AdminTracksGUI getTracksGUI() {
		return tracksGUI;
	}

	public dev.belikhun.boatracing.track.TrackRecordManager getTrackRecordManager() {
		return trackRecordManager;
	}

	public dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService getLobbyBoardService() {
		return lobbyBoardService;
	}

	public dev.belikhun.boatracing.cinematic.CinematicCameraService getCinematicCameraService() {
		return cinematicCameraService;
	}

	@Override
	public void onEnable() {
		instance = this;
		saveDefaultConfig();
		// Ensure new default keys are merged into existing config.yml on updates
		try {
			mergeConfigDefaults();
		} catch (Throwable t) {
			getLogger().warning("Failed to merge default config values: " + t.getMessage());
		}
		this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
		// Load plugin metadata (version/authors) from plugin.yml to avoid deprecated
		// API
		loadPluginMeta();
		// Team and pit features removed
		this.adminGUI = new dev.belikhun.boatracing.ui.AdminGUI(this);
		this.adminRaceGUI = new dev.belikhun.boatracing.ui.AdminRaceGUI(this);
		this.adminEventGUI = new dev.belikhun.boatracing.ui.AdminEventGUI(this);
		this.profileManager = new dev.belikhun.boatracing.profile.PlayerProfileManager(getDataFolder());
		this.trackRecordManager = new dev.belikhun.boatracing.track.TrackRecordManager(getDataFolder());
		this.profileGUI = new dev.belikhun.boatracing.ui.ProfileGUI(this);
		this.trackSelectGUI = new dev.belikhun.boatracing.ui.TrackSelectGUI(this);
		this.trackConfig = new TrackConfig(this, getDataFolder());
		this.trackLibrary = new TrackLibrary(getDataFolder(), trackConfig);
		this.raceService = new dev.belikhun.boatracing.race.RaceService(this);
		this.eventService = new dev.belikhun.boatracing.event.EventService(this);
		this.scoreboardService = new dev.belikhun.boatracing.ui.ScoreboardService(this);
		this.hotbarService = new dev.belikhun.boatracing.ui.HotbarService(this);
		this.setupWizard = new SetupWizard(this);
		this.tracksGUI = new dev.belikhun.boatracing.ui.AdminTracksGUI(this, trackLibrary);
		this.lobbyBoardService = new dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService(this);
		this.cinematicCameraService = new dev.belikhun.boatracing.cinematic.CinematicCameraService(this);
		// Team GUI removed
		Bukkit.getPluginManager().registerEvents(adminGUI, this);
		Bukkit.getPluginManager().registerEvents(tracksGUI, this);
		Bukkit.getPluginManager().registerEvents(adminRaceGUI, this);
		Bukkit.getPluginManager().registerEvents(adminEventGUI, this);
		Bukkit.getPluginManager().registerEvents(profileGUI, this);
		Bukkit.getPluginManager().registerEvents(trackSelectGUI, this);
		Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.ui.HotbarListener(this, hotbarService),
				this);
		try {
			if (scoreboardService != null) {
				scoreboardService.start();
				boolean sbDebug = getConfig().getBoolean("scoreboard.debug", false);
				scoreboardService.setDebug(sbDebug);
				if (sbDebug)
					getLogger().info("[SB] Debug enabled via config");
			}
		} catch (Throwable ignored) {
		}

		// Hotbar UX items
		try {
			if (hotbarService != null)
				hotbarService.start();
		} catch (Throwable ignored) {
		}

		dev.belikhun.boatracing.track.SelectionManager.init(this);
		Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.track.WandListener(this), this);
		// Movement listener for race tracking
		Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
			private final java.util.Map<java.util.UUID, Long> lastCpDbg = new java.util.HashMap<>();

			@org.bukkit.event.EventHandler
			public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
				if (e.getTo() == null)
					return;
				if (raceService == null)
					return;
				var rm = raceService.findRaceFor(e.getPlayer().getUniqueId());
				if (rm == null || !rm.isRunning())
					return;
				rm.tickPlayer(e.getPlayer(), e.getFrom(), e.getTo());
			}

			@org.bukkit.event.EventHandler(ignoreCancelled = true)
			public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent e) {
				if (raceService == null)
					return;
				org.bukkit.entity.Entity vehicle = e.getVehicle();
				boolean boatLike = (vehicle instanceof org.bukkit.entity.Boat)
						|| (vehicle instanceof org.bukkit.entity.ChestBoat);
				if (!boatLike) {
					try {
						String t = vehicle.getType() != null ? vehicle.getType().name() : null;
						boatLike = t != null && (t.endsWith("_BOAT") || t.endsWith("_CHEST_BOAT") || t.endsWith("_RAFT")
								|| t.endsWith("_CHEST_RAFT")
								|| t.equals("BOAT") || t.equals("CHEST_BOAT"));
					} catch (Throwable ignored) {
						boatLike = false;
					}
				}
				if (!boatLike)
					return;
				org.bukkit.Location to = e.getTo();
				org.bukkit.Location from = e.getFrom();
				if (to == null || from == null)
					return;

				boolean cpDbg = false;
				try {
					cpDbg = getConfig().getBoolean("racing.debug.checkpoints", false);
				} catch (Throwable ignored) {
				}

				// Tick checkpoints using the vehicle position (players in boats may not fire
				// PlayerMoveEvent reliably)
				// Tick checkpoints using the vehicle position (players in boats may not fire
				// PlayerMoveEvent reliably)
				// Route to the correct race manager per player (supports multiple concurrent
				// races).
				for (org.bukkit.entity.Entity passenger : vehicle.getPassengers()) {
					if (passenger instanceof org.bukkit.entity.Player p) {
						RaceManager raceManager = raceService.findRaceFor(p.getUniqueId());
						if (raceManager == null || !raceManager.isRunning())
							continue;
						if (cpDbg) {
							long now = System.currentTimeMillis();
							Long prev = lastCpDbg.get(p.getUniqueId());
							if (prev == null || (now - prev) >= 1000L) {
								lastCpDbg.put(p.getUniqueId(), now);
								getLogger().info("[CPDBG] VehicleMoveEvent tick for " + p.getName()
										+ " to=" + dev.belikhun.boatracing.util.Text.fmtPos(to)
										+ " checkpoints="
										+ (raceManager.getTrackConfig() == null ? 0
												: raceManager.getTrackConfig().getCheckpoints().size())
										+ " expectedNext="
										+ (raceManager.getParticipantState(p.getUniqueId()) == null ? "?"
												: (raceManager.getParticipantState(p.getUniqueId()).nextCheckpointIndex
														+ 1)));
							}
						}
						raceManager.tickPlayer(p, from, to);
					}
				}

				// Freeze boats during the start countdown so racers can't move before GO
				// (only if at least one passenger is in countdown in their race).

				boolean hasCountdownRacer = false;
				org.bukkit.Location lock = null;
				for (org.bukkit.entity.Entity passenger : vehicle.getPassengers()) {
					if (passenger instanceof org.bukkit.entity.Player p) {
						RaceManager raceManager = raceService.findRaceFor(p.getUniqueId());
						if (raceManager != null && raceManager.isCountdownActiveFor(p.getUniqueId())) {
							hasCountdownRacer = true;
							lock = raceManager.getCountdownLockLocation(p.getUniqueId());
							break;
						}
					}
				}
				if (!hasCountdownRacer)
					return;

				try {
					org.bukkit.Location target = (lock != null ? lock : from);
					if (target != null) {
						// Ensure lock has a world; Bukkit teleport returns false if world is null.
						try {
							if (target.getWorld() == null)
								target.setWorld(vehicle.getWorld());
						} catch (Throwable ignored) {
						}

						vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

						boolean tpOk;
						try {
							tpOk = vehicle.teleport(target,
									io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
						} catch (Throwable t) {
							try {
								tpOk = vehicle.teleport(target);
							} catch (Throwable ignored) {
								tpOk = false;
							}
						}
						if (!tpOk) {
							try {
								dev.belikhun.boatracing.util.EntityForceTeleport.nms(vehicle, target);
							} catch (Throwable ignored) {
							}
						}

						vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
						try {
							vehicle.setRotation(target.getYaw(), target.getPitch());
						} catch (Throwable ignored) {
						}
					}
				} catch (Throwable ignored) {
				}
			}
		}, this);

		// MapEngine lobby board (optional)
		try {
			if (lobbyBoardService != null) {
				lobbyBoardService.reloadFromConfig();
			}
		} catch (Throwable ignored) {
		}

		// Prevent racers from leaving their boat during countdown/race
		Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
			@org.bukkit.event.EventHandler(ignoreCancelled = true)
			public void onVehicleExit(org.bukkit.event.vehicle.VehicleExitEvent e) {
				if (raceService == null)
					return;
				if (!(e.getExited() instanceof org.bukkit.entity.Player p))
					return;
				RaceManager rm = raceService.findRaceFor(p.getUniqueId());
				if (rm == null)
					return;
				if (!rm.shouldPreventBoatExit(p.getUniqueId()))
					return;
				e.setCancelled(true);
			}
		}, this);

		// Respawn racers at their last checkpoint, or at start if all checkpoints were
		// reached.
		Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
			@org.bukkit.event.EventHandler
			public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
				if (raceService == null)
					return;
				org.bukkit.entity.Player p = e.getPlayer();
				if (p == null)
					return;
				RaceManager raceManager = raceService.findRaceFor(p.getUniqueId());
				if (raceManager == null)
					return;
				org.bukkit.Location target = raceManager.getRaceRespawnLocation(p.getUniqueId(), p.getLocation());
				if (target != null && target.getWorld() != null) {
					e.setRespawnLocation(target);

					// Play respawn cue after the respawn has applied.
					try {
						Bukkit.getScheduler().runTaskLater(BoatRacingPlugin.this, () -> {
							try {
								if (!p.isOnline())
									return;
								try {
									raceManager.ensureRacerHasBoat(p);
								} catch (Throwable ignored) {
								}
								p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
							} catch (Throwable ignored) {
							}
						}, 2L);
					} catch (Throwable ignored) {
					}
				}
			}
		}, this);

		// Disqualify racers who disconnect mid-race and clean up their state/boat.
		Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
			@org.bukkit.event.EventHandler
			public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
				if (raceService == null)
					return;
				org.bukkit.entity.Player p = e.getPlayer();
				if (p == null)
					return;
				try {
					raceService.handleDisconnect(p.getUniqueId());
				} catch (Throwable ignored) {
				}
			}

			@org.bukkit.event.EventHandler
			public void onKick(org.bukkit.event.player.PlayerKickEvent e) {
				if (raceService == null)
					return;
				org.bukkit.entity.Player p = e.getPlayer();
				if (p == null)
					return;
				try {
					raceService.handleDisconnect(p.getUniqueId());
				} catch (Throwable ignored) {
				}
			}
		}, this);

		// Prevent our race fireworks from damaging racers.
		Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.race.RaceFireworkListener(this), this);

		// Removed bStats metrics and the external update checker per configuration.
		// If you need to re-enable update checking or metrics, re-add a custom
		// implementation and config keys.

		// ViaVersion integration and internal scoreboard number hiding removed by
		// request

		if (getCommand("boatracing") != null) {
			BoatRacingCommandHandler handler = new BoatRacingCommandHandler(this);
			getCommand("boatracing").setExecutor(handler);
			getCommand("boatracing").setTabCompleter(handler);
		}

		// Start event service (single-active-event orchestrator)
		try {
			if (eventService != null)
				eventService.start();
		} catch (Throwable ignored) {
		}
		getLogger().info("BoatRacing enabled");
	}

	// Merge default config.yml values into the existing config without overwriting
	// user changes
	private void mergeConfigDefaults() {
		InputStream is = getResource("config.yml");
		if (is == null)
			return;
		YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
		FileConfiguration cfg = getConfig();
		cfg.addDefaults(def);
		cfg.options().copyDefaults(true);
		saveConfig();
	}

	// Exposed for command handler to reuse reload flow.
	public void mergeConfigDefaultsExternal() {
		mergeConfigDefaults();
	}

	// Load plugin metadata (version/authors) from plugin.yml to avoid calling
	// deprecated getDescription()
	private void loadPluginMeta() {
		InputStream is = getResource("plugin.yml");
		if (is == null)
			return;
		YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
		this.pluginVersion = y.getString("version", this.pluginVersion);
		java.util.List<String> authors = y.getStringList("authors");
		if (authors != null && !authors.isEmpty())
			this.pluginAuthors = authors;
	}

	public String getPluginVersion() {
		return pluginVersion;
	}

	public java.util.List<String> getPluginAuthors() {
		return pluginAuthors;
	}

	public SetupWizard getSetupWizard() {
		return setupWizard;
	}

	public void reloadPrefixFromConfig() {
		this.prefix = Text.colorize(getConfig().getString("prefix", "&6[BoatRacing] "));
	}

}
