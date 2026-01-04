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
	private dev.belikhun.boatracing.integrations.placeholderapi.PlaceholderApiService placeholderApiService;
	private dev.belikhun.boatracing.profile.PlayerProfileManager profileManager;
	private dev.belikhun.boatracing.ui.ProfileGUI profileGUI;
	private dev.belikhun.boatracing.ui.TrackSelectGUI trackSelectGUI;
	private dev.belikhun.boatracing.ui.EventRegistrationGUI eventRegistrationGUI;
	private dev.belikhun.boatracing.ui.HotbarService hotbarService;
	private dev.belikhun.boatracing.ui.ScoreboardService scoreboardService;
	private dev.belikhun.boatracing.ui.SpawnConfirmGUI spawnConfirmGUI;
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
	private dev.belikhun.boatracing.integrations.discord.DiscordWebhookChatRelayService discordChatRelayService;
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

	public dev.belikhun.boatracing.integrations.placeholderapi.PlaceholderApiService getPlaceholderApiService() {
		return placeholderApiService;
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

	public dev.belikhun.boatracing.ui.EventRegistrationGUI getEventRegistrationGUI() {
		return eventRegistrationGUI;
	}

	public dev.belikhun.boatracing.ui.HotbarService getHotbarService() {
		return hotbarService;
	}

	public dev.belikhun.boatracing.ui.ScoreboardService getScoreboardService() {
		return scoreboardService;
	}

	public dev.belikhun.boatracing.ui.SpawnConfirmGUI getSpawnConfirmGUI() {
		return spawnConfirmGUI;
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

	public dev.belikhun.boatracing.integrations.discord.DiscordWebhookChatRelayService getDiscordChatRelayService() {
		return discordChatRelayService;
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
		this.eventRegistrationGUI = new dev.belikhun.boatracing.ui.EventRegistrationGUI(this);
		this.trackConfig = new TrackConfig(this, getDataFolder());
		this.trackLibrary = new TrackLibrary(getDataFolder(), trackConfig);
		this.raceService = new dev.belikhun.boatracing.race.RaceService(this);
		this.eventService = new dev.belikhun.boatracing.event.EventService(this);
		this.placeholderApiService = new dev.belikhun.boatracing.integrations.placeholderapi.PlaceholderApiService(this);
		this.scoreboardService = new dev.belikhun.boatracing.ui.ScoreboardService(this);
		this.hotbarService = new dev.belikhun.boatracing.ui.HotbarService(this);
		this.setupWizard = new SetupWizard(this);
		this.tracksGUI = new dev.belikhun.boatracing.ui.AdminTracksGUI(this, trackLibrary);
		this.lobbyBoardService = new dev.belikhun.boatracing.integrations.mapengine.LobbyBoardService(this);
		this.cinematicCameraService = new dev.belikhun.boatracing.cinematic.CinematicCameraService(this);
		this.spawnConfirmGUI = new dev.belikhun.boatracing.ui.SpawnConfirmGUI(this);
		this.discordChatRelayService = new dev.belikhun.boatracing.integrations.discord.DiscordWebhookChatRelayService(this);
		// Team GUI removed
		Bukkit.getPluginManager().registerEvents(adminGUI, this);
		Bukkit.getPluginManager().registerEvents(tracksGUI, this);
		Bukkit.getPluginManager().registerEvents(adminRaceGUI, this);
		Bukkit.getPluginManager().registerEvents(adminEventGUI, this);
		Bukkit.getPluginManager().registerEvents(profileGUI, this);
		Bukkit.getPluginManager().registerEvents(trackSelectGUI, this);
		Bukkit.getPluginManager().registerEvents(eventRegistrationGUI, this);
		Bukkit.getPluginManager().registerEvents(spawnConfirmGUI, this);
		Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.event.EventRegistrationNpcListener(this), this);
		Bukkit.getPluginManager().registerEvents(new dev.belikhun.boatracing.ui.HotbarListener(this, hotbarService),
				this);
		try {
			if (placeholderApiService != null)
				placeholderApiService.start();
		} catch (Throwable ignored) {
		}

		// Discord webhook chat relay (optional)
		try {
			if (discordChatRelayService != null)
				discordChatRelayService.start();
		} catch (Throwable t) {
			getLogger().warning("[Discord] Không thể khởi động chat-webhook: " + t.getMessage());
		}

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

		// Restore gamemode if a player disconnected mid-cinematic intro (spectator
		// persists across reconnects).
		Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
			@org.bukkit.event.EventHandler
			public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
				org.bukkit.entity.Player p = e.getPlayer();
				if (p == null)
					return;
				try {
					if (cinematicCameraService != null)
						cinematicCameraService.restorePendingGameMode(p);
				} catch (Throwable ignored) {
				}
				try {
					if (cinematicCameraService != null)
						cinematicCameraService.restorePendingVisibility(p);
				} catch (Throwable ignored) {
				}
				try {
					if (eventService != null)
						eventService.restorePendingOpeningTitles(p);
				} catch (Throwable ignored) {
				}
				try {
					if (raceService != null)
						raceService.restorePendingLobbyTeleport(p);
				} catch (Throwable ignored) {
				}

				// Ensure the event registration NPC initializes correctly after startup.
				// FancyNpcs may not be ready during onEnable; retry on first player join.
				try {
					if (eventService != null) {
						dev.belikhun.boatracing.event.RaceEvent ev = eventService.getActiveEvent();
						if (ev != null && ev.state == dev.belikhun.boatracing.event.EventState.REGISTRATION) {
							dev.belikhun.boatracing.event.EventRegistrationNpcService rs = eventService.getRegistrationNpcService();
							if (rs != null) {
								rs.tick(ev);
							}
						}
					}
				} catch (Throwable ignored) {
				}

				// Ensure non-admin players always spawn at lobby (world spawn) and are in Adventure.
				// Delayed by 1 tick so it doesn't fight with pending restore flows above.
				try {
					boolean isAdmin = p.isOp()
							|| p.hasPermission("boatracing.admin")
							|| p.hasPermission("boatracing.setup")
							|| p.hasPermission("boatracing.event.admin")
							|| p.hasPermission("boatracing.race.admin")
							|| p.hasPermission("boatracing.*");
					if (!isAdmin) {
						Bukkit.getScheduler().runTaskLater(BoatRacingPlugin.this, () -> {
							try {
								if (!p.isOnline())
									return;
								try {
									if (p.isInsideVehicle())
										p.leaveVehicle();
								} catch (Throwable ignored) {
								}
								org.bukkit.Location spawn = BoatRacingPlugin.this.resolveLobbySpawn(p);
								if (spawn != null)
									p.teleport(spawn);
								try {
									p.setGameMode(org.bukkit.GameMode.ADVENTURE);
								} catch (Throwable ignored) {
								}
								try {
									BoatRacingPlugin.this.applyLobbyFlight(p);
								} catch (Throwable ignored) {
								}
								p.setFallDistance(0f);
							} catch (Throwable ignored) {
							}
						}, 1L);
					}
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
			if (getCommand("event") != null) {
				getCommand("event").setExecutor(handler);
				getCommand("event").setTabCompleter(handler);
			}
		}

		if (getCommand("spawn") != null) {
			SpawnCommand handler = new SpawnCommand(this);
			getCommand("spawn").setExecutor(handler);
		}

		if (getCommand("join") != null) {
			JoinCommand handler = new JoinCommand(this);
			getCommand("join").setExecutor(handler);
			getCommand("join").setTabCompleter(handler);
		}

		// Start event service (single-active-event orchestrator)
		try {
			if (eventService != null)
				eventService.start();
		} catch (Throwable ignored) {
		}
		getLogger().info("BoatRacing enabled");
	}

	@Override
	public void onDisable() {
		// Best-effort cleanup so races don't leave runtime artifacts behind (barriers,
		// holograms, spawned boats, start lights, tasks) when the server stops.
		try {
			if (eventService != null)
				eventService.stop();
		} catch (Throwable ignored) {
		}

		try {
			if (raceService != null)
				raceService.stopAll(true);
		} catch (Throwable ignored) {
		}

		try {
			if (cinematicCameraService != null)
				cinematicCameraService.stopAll(true);
		} catch (Throwable ignored) {
		}

		try {
			if (hotbarService != null)
				hotbarService.stop();
		} catch (Throwable ignored) {
		}
		try {
			if (scoreboardService != null)
				scoreboardService.stop();
		} catch (Throwable ignored) {
		}

		try {
			if (lobbyBoardService != null)
				lobbyBoardService.stop();
		} catch (Throwable ignored) {
		}

		try {
			if (placeholderApiService != null)
				placeholderApiService.stop();
		} catch (Throwable ignored) {
		}

		try {
			if (discordChatRelayService != null)
				discordChatRelayService.stop();
		} catch (Throwable ignored) {
		}

		getLogger().info("BoatRacing disabled");
		instance = null;
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

	public boolean deleteTrack(String trackName) {
		if (trackName == null || trackName.isBlank())
			return false;
		String key = trackName.trim();
		boolean touched = false;

		try {
			if (raceService != null)
				touched = raceService.deleteTrack(key) || touched;
		} catch (Throwable ignored) {
		}

		try {
			if (trackRecordManager != null)
				touched = trackRecordManager.remove(key) || touched;
		} catch (Throwable ignored) {
		}

		try {
			if (profileManager != null)
				touched = profileManager.removeTrack(key) || touched;
		} catch (Throwable ignored) {
		}

		boolean fileDeleted = false;
		try {
			if (trackLibrary != null)
				fileDeleted = trackLibrary.delete(key);
		} catch (Throwable ignored) {
			fileDeleted = false;
		}
		if (fileDeleted) {
			touched = true;
			try {
				if (trackConfig != null && key.equalsIgnoreCase(trackConfig.getCurrentName()))
					trackConfig.resetForNewTrack();
			} catch (Throwable ignored) {
			}
		}

		try {
			if (trackSelectGUI != null)
				trackSelectGUI.invalidateCache(key);
		} catch (Throwable ignored) {
		}

		return touched || fileDeleted;
	}

	// --- Lobby spawn (plugin-managed) ---
	/**
	 * Resolve the lobby spawn location.
	 * If configured in config.yml (racing.lobby.spawn.*), that location is used.
	 * Otherwise falls back to the player's world spawn (or first loaded world).
	 */
	public org.bukkit.Location resolveLobbySpawn(org.bukkit.entity.Player p) {
		org.bukkit.Location cfg = getLobbySpawnFromConfig();
		if (cfg != null && cfg.getWorld() != null)
			return cfg;
		try {
			if (p != null && p.getWorld() != null)
				return p.getWorld().getSpawnLocation();
		} catch (Throwable ignored) {
		}
		try {
			org.bukkit.World w = org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0);
			return w != null ? w.getSpawnLocation() : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	public boolean isLobbySpawnConfigured() {
		try {
			return getConfig().getBoolean("racing.lobby.spawn.enabled", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * Lobby UX: allow players to fly even in Adventure mode.
	 * This should be applied when a player is teleported to the lobby.
	 */
	public void applyLobbyFlight(org.bukkit.entity.Player p) {
		if (p == null)
			return;
		try {
			p.setAllowFlight(true);
		} catch (Throwable ignored) {
		}
	}

	public void setLobbySpawn(org.bukkit.Location loc) {
		if (loc == null || loc.getWorld() == null)
			return;
		getConfig().set("racing.lobby.spawn.enabled", true);
		getConfig().set("racing.lobby.spawn.world", loc.getWorld().getName());
		getConfig().set("racing.lobby.spawn.x", loc.getX());
		getConfig().set("racing.lobby.spawn.y", loc.getY());
		getConfig().set("racing.lobby.spawn.z", loc.getZ());
		getConfig().set("racing.lobby.spawn.yaw", (double) loc.getYaw());
		getConfig().set("racing.lobby.spawn.pitch", (double) loc.getPitch());
		saveConfig();
	}

	private org.bukkit.Location getLobbySpawnFromConfig() {
		try {
			if (!getConfig().getBoolean("racing.lobby.spawn.enabled", false))
				return null;
			String wn = getConfig().getString("racing.lobby.spawn.world", "");
			if (wn == null || wn.isBlank())
				return null;
			org.bukkit.World w = org.bukkit.Bukkit.getWorld(wn);
			if (w == null)
				return null;
			double x = getConfig().getDouble("racing.lobby.spawn.x", w.getSpawnLocation().getX());
			double y = getConfig().getDouble("racing.lobby.spawn.y", w.getSpawnLocation().getY());
			double z = getConfig().getDouble("racing.lobby.spawn.z", w.getSpawnLocation().getZ());
			float yaw = (float) getConfig().getDouble("racing.lobby.spawn.yaw", (double) w.getSpawnLocation().getYaw());
			float pitch = (float) getConfig().getDouble("racing.lobby.spawn.pitch", (double) w.getSpawnLocation().getPitch());
			return new org.bukkit.Location(w, x, y, z, yaw, pitch);
		} catch (Throwable ignored) {
			return null;
		}
	}

}
