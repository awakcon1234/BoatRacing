package dev.belikhun.boatracing;

import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import dev.belikhun.boatracing.race.RaceManager;
import dev.belikhun.boatracing.setup.SetupWizard;
import dev.belikhun.boatracing.track.Region;
import dev.belikhun.boatracing.track.SelectionUtils;
import dev.belikhun.boatracing.util.Text;

public class BoatRacingCommandHandler implements CommandExecutor, TabCompleter {
	private final BoatRacingPlugin plugin;

	public BoatRacingCommandHandler(BoatRacingPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player)) {
			Text.msg(sender, "&cChỉ dành cho người chơi.");
			return true;
		}

		Player p = (Player) sender;
		if (!command.getName().equalsIgnoreCase("boatracing"))
			return true;

		if (args.length == 0) {
			Text.msg(p, "&cCách dùng: /" + label + " profile|race|setup|event|reload|version|debug");
			return true;
		}

		// /boatracing debug ...
		if (args[0].equalsIgnoreCase("debug")) {
			if (!p.hasPermission("boatracing.admin")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				return true;
			}
			if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
				Text.msg(p, "&eLệnh debug:");
				Text.tell(p, "&7 - &f/" + label + " debug player <tên> &7(Xem trạng thái runtime của người chơi)");
				return true;
			}
			if (args[1].equalsIgnoreCase("player")) {
				if (args.length < 3) {
					Text.msg(p, "&cCách dùng: /" + label + " debug player <tên>");
					return true;
				}
				String targetName = args[2];
				Player t = Bukkit.getPlayerExact(targetName);
				if (t == null)
					t = Bukkit.getPlayer(targetName);
				if (t == null || !t.isOnline()) {
					Text.msg(p, "&cKhông tìm thấy người chơi đang online: &f" + targetName);
					return true;
				}

				java.util.UUID tid = t.getUniqueId();
				Text.msg(p, "&eDebug người chơi:");
				Text.tell(p, "&7● &fTên: &e" + t.getName() + " &8(&7UUID: &f" + tid + "&8)");
				try {
					String w = (t.getWorld() != null ? t.getWorld().getName() : "(không rõ)");
					Text.tell(p, "&7● &fThế giới: &e" + w + " &8● &7Chế độ: &f" + t.getGameMode().name());
				} catch (Throwable ignored) {
				}
				try {
					Text.tell(p, "&7● &fVị trí: &f" + dev.belikhun.boatracing.util.Text.fmtPos(t.getLocation()));
				} catch (Throwable ignored) {
				}

				// Cinematic state
				try {
					var cam = plugin.getCinematicCameraService();
					boolean camRun = cam != null && cam.isRunningFor(tid);
					Text.tell(p, "&7● &fCinematic: " + (camRun ? "&a✔ Đang chạy" : "&c❌ Không"));
				} catch (Throwable ignored) {
				}

				// Race state
				Text.msg(p, "&eTrạng thái đua:");
				try {
					var rs = plugin.getRaceService();
					boolean pendingLobby = rs != null && rs.isPendingLobbyTeleport(tid);
					Text.tell(p, "&7● &fChờ teleport về sảnh: " + (pendingLobby ? "&a✔" : "&c❌"));
				} catch (Throwable ignored) {
				}

				try {
					var rs = plugin.getRaceService();
					RaceManager rm = (rs != null ? rs.findRaceFor(tid) : null);
					if (rm == null) {
						Text.tell(p, "&7● &fĐang tham gia: &c❌ Không");
						Text.tell(p, "&7● &fGhi chú: &7Nếu bảng sảnh không hiện, kiểm tra MapEngine + vị trí/placement/world.");
						return true;
					}

					String trackName = null;
					try {
						trackName = (rm.getTrackConfig() != null ? rm.getTrackConfig().getCurrentName() : null);
					} catch (Throwable ignored2) {
						trackName = null;
					}
					if (trackName == null || trackName.isBlank()) trackName = "(không rõ)";

					Text.tell(p, "&7● &fĐang tham gia: &a✔");
					Text.tell(p, "&7● &fĐường đua: &e" + trackName);

					String phase;
					if (rm.isRunning()) phase = "&aĐang đua";
					else if (rm.isIntroActive()) phase = "&dIntro";
					else if (rm.isCountdownActiveFor(tid)) phase = "&eĐếm ngược";
					else if (rm.isRegistering()) phase = "&bĐăng ký";
					else if (rm.isAnyCountdownActive()) phase = "&eĐếm ngược";
					else phase = "&7Nhàn rỗi";
					Text.tell(p, "&7● &fGiai đoạn: " + phase);

					boolean reg = false;
					try { reg = rm.getRegistered().contains(tid); } catch (Throwable ignored2) { reg = false; }
					Text.tell(p, "&7● &fĐã đăng ký: " + (reg ? "&a✔" : "&c❌"));
					Text.tell(p, "&7● &fĐếm ngược (người này): " + (rm.isCountdownActiveFor(tid) ? "&a✔" : "&c❌"));
					Text.tell(p, "&7● &fChặn rời thuyền: " + (rm.shouldPreventBoatExit(tid) ? "&a✔" : "&c❌"));

					RaceManager.ParticipantState st = null;
					try { st = rm.peekParticipantState(tid); } catch (Throwable ignored2) { st = null; }
					if (st != null) {
						Text.tell(p, "&7● &fLap: &e" + st.currentLap + "&7/&e" + rm.getTotalLaps()
								+ " &8● &7Checkpoint kế: &e" + (st.nextCheckpointIndex + 1)
								+ " &8● &7Phạt: &e" + st.penaltySeconds + "s");
						Text.tell(p, "&7● &fHoàn thành: " + (st.finished ? ("&a✔ #" + st.finishPosition) : "&c❌"));
						try {
							Text.tell(p, "&7● &fQuãng đường: &e" + String.format(java.util.Locale.ROOT, "%.1f", st.distanceBlocks) + "&7 blocks");
						} catch (Throwable ignored2) {
						}
					}
				} catch (Throwable t2) {
					Text.tell(p, "&cLỗi khi đọc trạng thái đua: &f" + (t2.getMessage() == null ? t2.getClass().getSimpleName() : t2.getMessage()));
				}

				return true;
			}

			Text.msg(p, "&cLệnh debug không rõ. Dùng: /" + label + " debug help");
			return true;
		}
		// end debug

		// /boatracing event ...
		if (args[0].equalsIgnoreCase("event")) {
			return dev.belikhun.boatracing.event.EventCommands.handle(plugin, p, label, args);
		}

		if (args[0].equalsIgnoreCase("scoreboard") || args[0].equalsIgnoreCase("sb")) {
			if (!p.hasPermission("boatracing.admin")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				return true;
			}
			if (args.length < 2) {
				Text.msg(p, "&eDùng: /" + label + " scoreboard <on|off|tick|debug on|debug off>");
				return true;
			}
			String sub = args[1].toLowerCase();
			switch (sub) {
				case "on" -> {
					try {
						plugin.getScoreboardService().start();
						Text.msg(p, "&aScoreboard bật.");
					} catch (Throwable ignored) {
					}
				}
				case "off" -> {
					try {
						plugin.getScoreboardService().stop();
					} catch (Throwable ignored) {
					}
					for (org.bukkit.entity.Player pl : org.bukkit.Bukkit.getOnlinePlayers()) {
						org.bukkit.scoreboard.Scoreboard sb = org.bukkit.Bukkit.getScoreboardManager()
								.getNewScoreboard();
						pl.setScoreboard(sb);
					}
					Text.msg(p, "&aScoreboard tắt.");
				}
				case "tick" -> {
					try {
						dev.belikhun.boatracing.ui.ScoreboardService svc = plugin.getScoreboardService();
						if (svc != null)
							svc.forceTick();
						Text.msg(p, "&aĐã cập nhật.");
					} catch (Throwable ignored) {
					}
				}
				case "debug" -> {
					if (args.length < 3) {
						Text.msg(p, "&eDùng: /" + label + " scoreboard debug <on|off>");
						return true;
					}
					boolean enable = args[2].equalsIgnoreCase("on");
					try {
						plugin.getScoreboardService().setDebug(enable);
					} catch (Throwable ignored) {
					}
					Text.msg(p, enable ? "&aBật debug scoreboard." : "&aTắt debug scoreboard.");
				}
				default -> Text.msg(p, "&eDùng: /" + label + " scoreboard <on|off|tick>");
			}
			return true;
		}

		if (args[0].equalsIgnoreCase("profile")) {
			// /boatracing profile speedunit <kmh|bps>
			if (args.length >= 3 && args[1].equalsIgnoreCase("speedunit")) {
				String u = args[2].toLowerCase();
				if (!u.equals("kmh") && !u.equals("bps") && !u.equals("bph")) {
					Text.msg(p, "&cĐơn vị không hợp lệ. Dùng: &fkmh&7, &fbps&7 hoặc &fbph");
					return true;
				}
				plugin.getProfileManager().setSpeedUnit(p.getUniqueId(), u);
				String unitLabel = u.equals("kmh") ? "km/h" : (u.equals("bps") ? "bps" : "bph");
				Text.msg(p, "&aĐã đặt đơn vị tốc độ: &f" + unitLabel);
				return true;
			}

			// Open player profile GUI
			plugin.getProfileGUI().open(p);
			return true;
		}

		// /boatracing version
		if (args[0].equalsIgnoreCase("version")) {
			if (!p.hasPermission("boatracing.version")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
				return true;
			}
			String current = plugin.getPluginVersion();
			java.util.List<String> authors = plugin.getPluginAuthors();
			Text.msg(p, "&e" + plugin.getName() + "-" + current);
			if (!authors.isEmpty()) {
				Text.msg(p, "&eAuthors: &f" + String.join(", ", authors));
			}

			// Update checks removed; only display local metadata here
			return true;
		}

		if (args[0].equalsIgnoreCase("reload")) {
			if (!p.hasPermission("boatracing.reload")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
				return true;
			}

			// Reload config and data
			plugin.reloadConfig();
			// After reload, also merge any new defaults into config.yml
			try {
				plugin.mergeConfigDefaultsExternal();
			} catch (Throwable ignored) {
			}
			plugin.reloadPrefixFromConfig();

			// Restart scoreboard so update-ticks/templates changes apply immediately.
			try {
				if (plugin.getScoreboardService() != null) {
					plugin.getScoreboardService().restart();
					boolean sbDebug = plugin.getConfig().getBoolean("scoreboard.debug", false);
					plugin.getScoreboardService().setDebug(sbDebug);
				}
			} catch (Throwable ignored) {
			}

			// Reload MapEngine lobby board config
			try {
				if (plugin.getLobbyBoardService() != null)
					plugin.getLobbyBoardService().reloadFromConfig();
			} catch (Throwable ignored) {
			}

			Text.msg(p, "&aĐã tải lại plugin.");
			p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
			return true;
		}

		// /boatracing board (MapEngine lobby board)
		if (args[0].equalsIgnoreCase("board")) {
			if (!p.hasPermission("boatracing.admin")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				return true;
			}
			if (plugin.getLobbyBoardService() == null) {
				Text.msg(p, "&cTính năng bảng đang bị tắt.");
				return true;
			}

			if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
				Text.msg(p, "&eBảng thông tin sảnh (MapEngine):");
				Text.tell(p, "&7 - &f/" + label
						+ " board set [north|south|east|west] &7(Dùng selection hiện tại; bỏ trống để tự chọn theo hướng nhìn)");
				Text.tell(p, "&7 - &f/" + label + " board clear");
				Text.tell(p, "&7 - &f/" + label + " board status");
				return true;
			}

			String sub = args[1].toLowerCase();
			switch (sub) {
				case "status" -> {
					java.util.List<String> lines;
					try {
						lines = plugin.getLobbyBoardService().statusLines();
					} catch (Throwable t) {
						lines = java.util.List.of(
								"&cKhông thể lấy trạng thái bảng: "
										+ (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
					}
					for (String line : lines) {
						try {
							Text.msg(p, line);
						} catch (Throwable ignored) {
						}
					}
					return true;
				}
				case "clear" -> {
					plugin.getLobbyBoardService().clearPlacement();
					Text.msg(p, "&aĐã xóa vị trí bảng.");
					return true;
				}
				case "set" -> {
					// Facing is optional; if omitted, auto-select based on player view/position.
					var sel = dev.belikhun.boatracing.track.SelectionUtils.getSelectionDetailed(p);
					if (sel == null) {
						Text.msg(p, "&cKhông phát hiện selection. Dùng wand để chọn 2 góc trước.");
						return true;
					}
					org.bukkit.block.BlockFace face = null;
					if (args.length >= 3) {
						try {
							face = org.bukkit.block.BlockFace.valueOf(args[2].toUpperCase(java.util.Locale.ROOT));
						} catch (Throwable t) {
							Text.msg(p, "&cHướng không hợp lệ. Dùng: north|south|east|west");
							return true;
						}
					}

					boolean ok = plugin.getLobbyBoardService().setPlacementFromSelection(p, sel.box, face);
					if (!ok) {
						Text.msg(p, "&cKhông thể đặt bảng. Hãy chọn vùng phẳng (2D) phù hợp và thử lại.");
						return true;
					}
					Text.msg(p, "&aĐã đặt bảng thông tin sảnh.");
					Text.tell(p, plugin.getLobbyBoardService().placementSummary());
					return true;
				}
				default -> {
					Text.msg(p, "&cKhông rõ lệnh. Dùng: /" + label + " board help");
					return true;
				}
			}
		}

		// /boatracing race
		if (args[0].equalsIgnoreCase("race")) {
			if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
				Text.msg(p, "&eLệnh đua:");
				Text.tell(p, "&7 - &f/" + label
						+ " race join <track> &7(Tham gia đăng ký cho đường đua; cần có đội)");
				Text.tell(p, "&7 - &f/" + label + " race leave <track> &7(Rời khỏi đăng ký cho đường đua)");
				Text.tell(p, "&7 - &f/" + label
						+ " race status <track> &7(Hiển thị trạng thái cuộc đua cho đường đua)");
				if (p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup")) {
					Text.tell(p, "&8Quản trị:&7 /" + label + " race open|start|force|stop <track>");
				}
				return true;
			}

			switch (args[1].toLowerCase()) {
				case "open" -> {
					if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
						Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race open <track>");
						return true;
					}
					String tname = args[2];
					RaceManager rm = plugin.getRaceService().getOrCreate(tname);
					if (rm == null) {
						Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
						return true;
					}
					if (!rm.getTrackConfig().isReady()) {
						Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", rm.getTrackConfig().missingRequirements()));
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					int laps = rm.getTotalLaps();
					boolean ok = rm.openRegistration(laps, null);
					if (!ok)
						Text.msg(p, "&cKhông thể mở đăng ký lúc này.");
					return true;
				}
				case "join" -> {
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race join <track>");
						return true;
					}
					String tname = args[2];
					RaceManager rm = plugin.getRaceService().getOrCreate(tname);
					if (rm == null) {
						Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
						return true;
					}
					if (!rm.getTrackConfig().isReady()) {
						Text.msg(p, "&cTrack is not ready: &7" + String.join(", ", rm.getTrackConfig().missingRequirements()));
						return true;
					}
					// Tracks are open by default: joining auto-opens registration if needed.
					if (!plugin.getRaceService().join(tname, p)) {
						Text.msg(p, "&cKhông thể tham gia đăng ký lúc này.");
					}
					return true;
				}
				case "leave" -> {
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race leave <track>");
						return true;
					}
					String tname = args[2];
					RaceManager rm = plugin.getRaceService().getOrCreate(tname);
					if (rm == null) {
						Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
						return true;
					}
					boolean removed = plugin.getRaceService().leave(tname, p);
					if (!removed) {
						if (!rm.isRegistering()) {
							Text.msg(p, "&cĐăng ký chưa mở.");
						} else {
							Text.msg(p, "&7Bạn chưa đăng ký.");
						}
					}
					return true;
				}
				case "force" -> {
					if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
						Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race force <track>");
						return true;
					}
					String tname = args[2];
					RaceManager rm = plugin.getRaceService().getOrCreate(tname);
					if (rm == null) {
						Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
						return true;
					}
					if (rm.getRegistered().isEmpty()) {
						Text.msg(p, "&cKhông có người tham gia đã đăng ký. &7Mở đăng ký trước: &f/" + label + " race open");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					rm.forceStart();
					return true;
				}
				case "start" -> {
					if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
						Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race start <track>");
						return true;
					}
					String tname = args[2];
					RaceManager raceManager = plugin.getRaceService().getOrCreate(tname);
					if (raceManager == null) {
						Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
						return true;
					}
					if (!raceManager.getTrackConfig().isReady()) {
						Text.msg(p, "&cTrack is not ready: &7"
								+ String.join(", ", raceManager.getTrackConfig().missingRequirements()));
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					if (raceManager.isRunning()) {
						Text.msg(p, "&cCuộc đua đang diễn ra.");
						return true;
					}
					// Build participants: strictly registered participants only
					java.util.List<org.bukkit.entity.Player> participants = new java.util.ArrayList<>();
					java.util.Set<java.util.UUID> regs = new java.util.LinkedHashSet<>(raceManager.getRegistered());
					for (java.util.UUID id : regs) {
						org.bukkit.entity.Player rp = Bukkit.getPlayer(id);
						if (rp != null && rp.isOnline())
							participants.add(rp);
					}
					if (participants.isEmpty()) {
						Text.msg(p, "&cKhông có người tham gia đã đăng ký. Sử dụng &f/" + label
								+ " race open &7để mở đăng ký.");
						return true;
					}
					// Place with boats and start
					java.util.List<org.bukkit.entity.Player> placed = raceManager.placeAtStartsWithBoats(participants);
					if (placed.isEmpty()) {
						Text.msg(p, "&cKhông còn vị trí bắt đầu trống trên đường đua này.");
						return true;
					}
					if (placed.size() < participants.size()) {
						Text.msg(p, "&7Một số người chơi đăng ký không thể được đặt do thiếu vị trí bắt đầu.");
					}
					// Use start lights countdown if configured
					raceManager.startLightsCountdown(placed);
					return true;
				}
				case "stop" -> {
					if (!(p.hasPermission("boatracing.race.admin") || p.hasPermission("boatracing.setup"))) {
						Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race stop <track>");
						return true;
					}
					String tname = args[2];
					boolean any = plugin.getRaceService().stopRace(tname, true);
					if (!any) {
						Text.msg(p, "&7Không có gì để dừng.");
					}
					return true;
				}
				case "status" -> {
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " race status <track>");
						return true;
					}
					String tname = args[2];
					RaceManager raceManager = plugin.getRaceService().getOrCreate(tname);
					if (raceManager == null) {
						Text.msg(p, "&cTrack not found or failed to load: &f" + tname);
						return true;
					}
					var tc = raceManager.getTrackConfig();
					String cur = tc.getCurrentName() != null ? tc.getCurrentName() : tname;
					boolean running = raceManager.isRunning();
					boolean registering = raceManager.isRegistering();
					int regs = raceManager.getRegistered().size();
					int laps = raceManager.getTotalLaps();
					int participants = running ? raceManager.getInvolved().size() : 0;
					int starts = tc.getStarts().size();
					int lights = tc.getLights().size();
					int cps = tc.getCheckpoints().size();
					boolean hasFinish = tc.getFinish() != null;
					boolean ready = tc.isReady();
					java.util.List<String> missing = ready ? java.util.Collections.emptyList() : tc.missingRequirements();

					Text.msg(p, "&eTrạng thái cuộc đua:");
					Text.tell(p, "&7Đường đua: &f" + cur);
					Text.tell(p, running ? "&aĐang chạy &7(Tham gia: &f" + participants + "&7)"
							: "&7Không có cuộc đua đang chạy.");
					Text.tell(p, registering ? "&eĐăng ký mở &7(Đã đăng ký: &f" + regs + "&7)" : "&7Đăng ký đóng.");
					Text.tell(p, "&7Số vòng: &f" + laps);
					Text.tell(p, "&7Vị trí bắt đầu: &f" + starts + " &8● &7Đèn xuất phát: &f" + lights
							+ "/5 &8● &7Vạch kết thúc: &f" + (hasFinish ? "có" : "không"));
					Text.tell(p, "&7Điểm checkpoint: &f" + cps);
					if (ready) {
						Text.tell(p, "&aĐường đua sẵn sàng.");
					} else {
						Text.tell(p, "&cĐường đua chưa sẵn sàng: &7" + String.join(", ", missing));
					}
					return true;
				}
				default -> {
					Text.msg(p, "&cKhông rõ lệnh con đua. Sử dụng /" + label + " race help");
					return true;
				}
			}
		}

		// /boatracing setup
		if (args[0].equalsIgnoreCase("setup")) {
			if (!p.hasPermission("boatracing.setup")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
				return true;
			}
			if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
				Text.msg(p, "&eLệnh cấu hình:");
				Text.tell(p, "&7 - &f/" + label + " setup addstart &7(Thêm vị trí hiện tại làm vị trí bắt đầu; lặp lại để thêm nhiều)");
				Text.tell(p, "&7 - &f/" + label + " setup clearstarts &7(Xóa tất cả vị trí bắt đầu)");
				Text.tell(p, "&7 - &f/" + label + " setup pos1 &7(Đặt góc A = vị trí hiện tại)");
				Text.tell(p, "&7 - &f/" + label + " setup pos2 &7(Đặt góc B = vị trí hiện tại)");
				Text.tell(p, "&7 - &f/" + label + " setup setbounds &7(Đặt vùng bao đường đua từ selection hiện tại)");
				Text.tell(p, "&7 - &f/" + label + " setup setwaitspawn &7(Đặt điểm spawn chờ từ vị trí hiện tại)");
				Text.tell(p, "&7 - &f/" + label + " setup setfinish &7(Sử dụng selection của bạn để đặt vùng vạch đích)");
				Text.tell(p,
						"&7 - &f/" + label + " setup addcheckpoint &7(Thêm checkpoint từ selection; có thể thêm nhiều. Thứ tự quan trọng)");
				Text.tell(p,
						"&7 - &f/" + label + " setup addlight &7(Thêm Đèn Redstone đang nhìn thành đèn xuất phát; tối đa 5, từ trái sang phải)");
				Text.tell(p, "&7 - &f/" + label + " setup clearlights &7(Xóa tất cả đèn xuất phát đã cấu hình)");
				Text.tell(p, "&7 - &f/" + label + " setup setlaps <n> &7(Đặt số vòng cho cuộc đua)");
				Text.tell(p, "&7 - &f/" + label
						+ " setup setpos <player> <slot|auto> &7(Gán người chơi vào vị trí bắt đầu cụ thể, 1-based; auto để xóa)");
				Text.tell(p, "&7 - &f/" + label + " setup clearpos <player> &7(Xóa vị trí bắt đầu tùy chỉnh của người chơi)");
				Text.tell(p, "&7 - &f/" + label + " setup clearcheckpoints &7(Xóa tất cả checkpoint)");
				Text.tell(p, "&7 - &f/" + label + " setup show &7(Tóm tắt cấu hình đường đua hiện tại)");
				Text.tell(p, "&7 - &f/" + label + " setup selinfo &7(Debug selection: selection hiện tại)");
				Text.tell(p, "&7 - &f/" + label + " setup wand &7(Phát công cụ chọn BoatRacing)");
				Text.tell(p, "&7 - &f/" + label + " setup wizard &7(Khởi chạy trợ lý thiết lập)");
				return true;
			}

			String sub = args[1].toLowerCase();
			switch (sub) {
				case "pos1" -> {
					dev.belikhun.boatracing.track.SelectionManager.setCornerA(p, p.getLocation());
					Text.msg(p, "&aĐã đặt &fGóc A &a= &f" + Text.fmtPos(p.getLocation()));
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.3f);
					return true;
				}
				case "pos2" -> {
					dev.belikhun.boatracing.track.SelectionManager.setCornerB(p, p.getLocation());
					Text.msg(p, "&aĐã đặt &fGóc B &a= &f" + Text.fmtPos(p.getLocation()));
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.3f);
					return true;
				}
				case "setbounds" -> {
					dev.belikhun.boatracing.track.SelectionUtils.SelectionDetails sel = dev.belikhun.boatracing.track.SelectionUtils
							.getSelectionDetailed(p);
					if (sel == null) {
						Text.msg(p, "&cKhông có selection hợp lệ. Dùng &f/" + label
								+ " setup pos1 &7và &f" + label + " setup pos2 &7hoặc dùng wand.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					dev.belikhun.boatracing.track.Region r = new dev.belikhun.boatracing.track.Region(sel.worldName, sel.box);
					plugin.getTrackConfig().setBounds(r);
					Text.msg(p, "&aĐã đặt vùng bao cho đường đua.");
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "setwaitspawn" -> {
					org.bukkit.Location raw = p.getLocation();
					org.bukkit.Location loc = dev.belikhun.boatracing.track.TrackConfig.normalizeStart(raw);
					plugin.getTrackConfig().setWaitingSpawn(loc);
					Text.msg(p, "&aĐã đặt spawn chờ tại &f" + Text.fmtPos(loc) + " &7yaw=" + Math.round(loc.getYaw())
							+ ", pitch=0");
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.2f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "wand" -> {
					dev.belikhun.boatracing.track.SelectionManager.giveWand(p);
					Text.msg(p, "&aCông cụ chọn đã sẵn sàng. &7Nhấp trái đánh dấu &fGóc A&7; nhấp phải đánh dấu &fGóc B&7.");
					p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.9f, 1.2f);
					return true;
				}
				case "wizard" -> {
					SetupWizard sw = plugin.getSetupWizard();
					if (sw == null) {
						Text.msg(p, "&cTrợ lý thiết lập đang bị tắt.");
						return true;
					}

					// Guided setup assistant with simple sub-actions
					if (args.length >= 3) {
						String action = args[2].toLowerCase();
						switch (action) {
							case "finish" -> sw.finish(p);
							case "back" -> sw.back(p);
							case "status" -> sw.status(p);
							case "cancel" -> sw.cancel(p);
							case "skip" -> sw.skip(p);
							case "next" -> sw.next(p);
							default -> sw.start(p);
						}
					} else if (sw.isActive(p)) {
						sw.status(p);
					} else {
						sw.start(p);
					}
					return true;
				}
				case "addstart" -> {
					org.bukkit.Location raw = p.getLocation();
					org.bukkit.Location loc = dev.belikhun.boatracing.track.TrackConfig.normalizeStart(raw);
					plugin.getTrackConfig().addStart(loc);
					Text.msg(p, "&aĐã thêm vị trí bắt đầu tại &f" + Text.fmtPos(loc) + " &7yaw=" + Math.round(loc.getYaw())
							+ ", pitch=0");
					Text.tell(p, "&7Mẹo: Bạn có thể thêm nhiều vị trí bắt đầu. Chạy lệnh một lần nữa để thêm.");
					p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "clearstarts" -> {
					plugin.getTrackConfig().clearStarts();
					Text.msg(p, "&aĐã xóa tất cả vị trí bắt đầu.");
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "setfinish" -> {
					var sel = SelectionUtils.getSelectionDetailed(p);
					if (sel == null) {
						Text.msg(p,
								"&cKhông phát hiện selection. Dùng công cụ chọn để đánh dấu &fGóc A&c (nhấp trái) và &fGóc B&c (nhấp phải).");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					Region r = new Region(sel.worldName, sel.box);
					plugin.getTrackConfig().setFinish(r);
					Text.msg(p, "&aĐã đặt vùng đích (&f" + Text.fmtArea(r) + "&a)");
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "addcheckpoint" -> {
					var sel = SelectionUtils.getSelectionDetailed(p);
					if (sel == null) {
						Text.msg(p,
								"&cKhông phát hiện selection. Dùng công cụ chọn để đánh dấu &fGóc A&c (nhấp trái) và &fGóc B&c (nhấp phải).");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					Region r = new Region(sel.worldName, sel.box);
					plugin.getTrackConfig().addCheckpoint(r);
					Text.msg(p, "&aĐã thêm checkpoint #&f" + plugin.getTrackConfig().getCheckpoints().size()
							+ " &7(" + Text.fmtArea(r) + ")");
					Text.tell(p, "&7Mẹo: Có thể thêm nhiều checkpoint. Thứ tự quan trọng.");
					p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "addlight" -> {
					org.bukkit.block.Block target = getTargetBlockLenient(p, 20);
					if (target == null) {
						Text.msg(p, "&cHãy nhìn vào Đèn Redstone trong bán kính 20 block.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					if (target.getType() != org.bukkit.Material.REDSTONE_LAMP) {
						Text.msg(p, "&cBlock đang nhìn không phải Đèn Redstone: &f" + target.getType());
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					boolean ok = plugin.getTrackConfig().addLight(target);
					if (!ok) {
						Text.msg(p, "&cKhông thể thêm đèn. Dùng Đèn Redstone, tránh trùng lặp, tối đa 5.");
						p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
						return true;
					}
					Text.msg(p, "&aĐã thêm đèn xuất phát &7(" + Text.fmtBlock(target) + ")");
					p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "clearlights" -> {
					plugin.getTrackConfig().clearLights();
					Text.msg(p, "&aĐã xóa tất cả đèn xuất phát.");
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "setlaps" -> {
					if (args.length < 3 || !args[2].matches("\\d+")) {
						Text.msg(p, "&cCách dùng: /" + label + " setup setlaps <số>");
						return true;
					}
					int laps = Math.max(1, Integer.parseInt(args[2]));
					if (plugin.getRaceService() != null)
						plugin.getRaceService().setDefaultLaps(laps);
					Text.msg(p, "&aĐã đặt số vòng là &f" + laps);
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "setpos" -> {
					if (args.length < 4) {
						Text.msg(p, "&cCách dùng: /" + label + " setup setpos <player> <slot|auto>");
						return true;
					}
					org.bukkit.OfflinePlayer off = resolveOffline(args[2]);
					if (off == null || off.getUniqueId() == null) {
						Text.msg(p, "&cKhông tìm thấy người chơi.");
						return true;
					}
					String slotArg = args[3];
					if (slotArg.equalsIgnoreCase("auto")) {
						plugin.getTrackConfig().clearCustomStartSlot(off.getUniqueId());
						Text.msg(p, "&aĐã xóa vị trí bắt đầu tùy chỉnh cho &f"
								+ (off.getName() != null ? off.getName() : off.getUniqueId().toString()));
					} else if (slotArg.matches("\\d+")) {
						int oneBased = Integer.parseInt(slotArg);
						if (oneBased < 1 || oneBased > plugin.getTrackConfig().getStarts().size()) {
							Text.msg(p, "&cVị trí không hợp lệ. Phạm vi: 1-" + plugin.getTrackConfig().getStarts().size());
							return true;
						}
						plugin.getTrackConfig().setCustomStartSlot(off.getUniqueId(), oneBased - 1);
						Text.msg(p, "&aĐã gán vị trí bắt đầu tùy chỉnh cho &f"
								+ (off.getName() != null ? off.getName() : off.getUniqueId().toString())
								+ " &7vào vị trí &f#" + oneBased);
					} else {
						Text.msg(p, "&cCách dùng: /" + label + " setup setpos <player> <slot|auto>");
					}
					return true;
				}
				case "clearpos" -> {
					if (args.length < 3) {
						Text.msg(p, "&cCách dùng: /" + label + " setup clearpos <player>");
						return true;
					}
					org.bukkit.OfflinePlayer off = resolveOffline(args[2]);
					if (off == null || off.getUniqueId() == null) {
						Text.msg(p, "&cKhông tìm thấy người chơi.");
						return true;
					}
					plugin.getTrackConfig().clearCustomStartSlot(off.getUniqueId());
					Text.msg(p, "&aĐã xóa vị trí bắt đầu tùy chỉnh cho &f"
							+ (off.getName() != null ? off.getName() : off.getUniqueId().toString()));
					return true;
				}
				case "clearcheckpoints" -> {
					plugin.getTrackConfig().clearCheckpoints();
					Text.msg(p, "&aĐã xóa tất cả checkpoint.");
					p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
					SetupWizard sw = plugin.getSetupWizard();
					if (sw != null)
						sw.afterAction(p);
					return true;
				}
				case "show" -> {
					int starts = plugin.getTrackConfig().getStarts().size();
					int lights = plugin.getTrackConfig().getLights().size();
					int cps = plugin.getTrackConfig().getCheckpoints().size();
					boolean hasFinish = plugin.getTrackConfig().getFinish() != null;
					int customStarts = plugin.getTrackConfig().getCustomStartSlots().size();
					Text.msg(p, "&eCấu hình đường đua:");
					String tname = (plugin.getTrackLibrary() != null && plugin.getTrackLibrary().getCurrent() != null)
							? plugin.getTrackLibrary().getCurrent()
							: "(unsaved)";
					Text.tell(p, "&7 - &fĐường đua: &e" + tname);
					Text.tell(p, "&7 - &fVị trí bắt đầu: &e" + starts);
					Text.tell(p, "&7 - &fĐèn bắt đầu: &e" + lights + "/5");
					Text.tell(p, "&7 - &fVùng đích: &e" + (hasFinish ? "có" : "không"));
					Text.tell(p, "&7 - &fVị trí bắt đầu tùy chỉnh: &e" + (customStarts > 0 ? (customStarts + " người") : "không có"));
					Text.tell(p, "&7 - &fCheckpoints: &e" + cps);
					return true;
				}
				case "selinfo" -> {
					java.util.List<String> dump = SelectionUtils.debugSelection(p);
					Text.msg(p, "&eThông tin vùng chọn:");
					for (String line : dump)
						Text.tell(p, "&7 - &f" + line);
					return true;
				}
				default -> {
					Text.msg(p, "&cLệnh cấu hình không rõ. Dùng /" + label + " setup help");
					return true;
				}
			}
		}

		// /boatracing admin
		if (args[0].equalsIgnoreCase("admin")) {
			if (!p.hasPermission("boatracing.admin")) {
				Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
				return true;
			}
			if (args.length == 1) {
				// Open Admin GUI by default
				plugin.getAdminGUI().openMain(p);
				return true;
			}
			if (args[1].equalsIgnoreCase("help")) {
				Text.msg(p, "&eLệnh quản trị:");
				Text.tell(p, "&7 - &f/" + label + " admin tracks &7(Quản lý đường đua qua GUI)");
				Text.tell(p, "&7 - &f/" + label + " admin event &7(Quản lý sự kiện qua GUI)");
				return true;
			}
			if (args[1].equalsIgnoreCase("tracks")) {
				if (!p.hasPermission("boatracing.setup")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				plugin.getTracksGUI().open(p);
				return true;
			}
			if (args[1].equalsIgnoreCase("event")) {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return true;
				}
				if (plugin.getAdminEventGUI() == null) {
					Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
					return true;
				}
				plugin.getAdminEventGUI().open(p);
				return true;
			}
			// Only tracks admin remains
			Text.msg(p, "&cCách dùng: /" + label + " admin help");
			return true;
		}

		// default fallback (teams removed)
		Text.msg(p, "&cLệnh con không hợp lệ. Sử dụng: /boatracing version|reload|setup|race");
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("boatracing"))
			return Collections.emptyList();

		// Root suggestions (handle no-arg and first arg prefix)
		if (args.length == 0 || (args.length == 1 && (args[0] == null || args[0].isEmpty()))) {
			java.util.List<String> root = new java.util.ArrayList<>();
			root.add("profile");
			root.add("event");
			// Expose 'race' root to all users for join/leave/status discoverability
			root.add("race");
			root.add("scoreboard");
			if (sender.hasPermission("boatracing.setup"))
				root.add("setup");
			if (sender.hasPermission("boatracing.admin"))
				root.add("admin");
			if (sender.hasPermission("boatracing.admin"))
				root.add("board");
			if (sender.hasPermission("boatracing.reload"))
				root.add("reload");
			if (sender.hasPermission("boatracing.version"))
				root.add("version");
			return root;
		}
		if (args.length == 1) {
			String pref = args[0].toLowerCase();
			java.util.List<String> root = new java.util.ArrayList<>();
			root.add("profile");
			root.add("event");
			root.add("race");
			root.add("scoreboard");
			if (sender.hasPermission("boatracing.admin"))
				root.add("debug");
			if (sender.hasPermission("boatracing.setup"))
				root.add("setup");
			if (sender.hasPermission("boatracing.admin"))
				root.add("admin");
			if (sender.hasPermission("boatracing.admin"))
				root.add("board");
			if (sender.hasPermission("boatracing.reload"))
				root.add("reload");
			if (sender.hasPermission("boatracing.version"))
				root.add("version");
			return root.stream().filter(s -> s.startsWith(pref)).toList();
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
			if (!sender.hasPermission("boatracing.admin"))
				return java.util.Collections.emptyList();
			String pref2 = args[1] == null ? "" : args[1].toLowerCase();
			return java.util.List.of("help", "player").stream().filter(s -> s.startsWith(pref2)).toList();
		}
		if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("player")) {
			if (!sender.hasPermission("boatracing.admin"))
				return java.util.Collections.emptyList();
			String pref3 = args[2] == null ? "" : args[2].toLowerCase();
			java.util.List<String> names = new java.util.ArrayList<>();
			for (org.bukkit.entity.Player pl : org.bukkit.Bukkit.getOnlinePlayers()) {
				if (pl == null)
					continue;
				names.add(pl.getName());
			}
			return names.stream().filter(s -> s.toLowerCase().startsWith(pref3)).toList();
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("event")) {
			if (args.length == 2) {
				java.util.List<String> subs = new java.util.ArrayList<>();
				subs.add("help");
				subs.add("status");
				subs.add("join");
				subs.add("leave");
				if (sender.hasPermission("boatracing.event.admin")) {
					subs.add("create");
					subs.add("open");
					subs.add("schedule");
					subs.add("start");
					subs.add("cancel");
					subs.add("track");
					subs.add("board");
					subs.add("opening");
				}
				String pref2 = args[1] == null ? "" : args[1].toLowerCase();
				return subs.stream().filter(s -> s.startsWith(pref2)).toList();
			}
			if (!sender.hasPermission("boatracing.event.admin"))
				return java.util.Collections.emptyList();
			if (args.length == 3 && args[1].equalsIgnoreCase("opening")) {
				String pref3 = args[2] == null ? "" : args[2].toLowerCase();
				return java.util.List.of("help", "status", "start", "stop", "stage", "camera", "board")
						.stream().filter(s -> s.startsWith(pref3)).toList();
			}
			if (args.length == 4 && args[1].equalsIgnoreCase("opening")
					&& (args[2].equalsIgnoreCase("stage") || args[2].equalsIgnoreCase("camera"))) {
				String pref4 = args[3] == null ? "" : args[3].toLowerCase();
				return java.util.List.of("set", "clear").stream().filter(s -> s.startsWith(pref4)).toList();
			}
			if (args.length == 4 && args[1].equalsIgnoreCase("opening") && args[2].equalsIgnoreCase("board")) {
				String pref4 = args[3] == null ? "" : args[3].toLowerCase();
				return java.util.List.of("help", "set", "status", "clear", "preview")
						.stream().filter(s -> s.startsWith(pref4)).toList();
			}
			if (args.length == 5 && args[1].equalsIgnoreCase("opening") && args[2].equalsIgnoreCase("board")
					&& args[3].equalsIgnoreCase("set")) {
				String pref5 = args[4] == null ? "" : args[4].toLowerCase();
				return java.util.List.of("north", "south", "east", "west").stream().filter(s -> s.startsWith(pref5))
						.toList();
			}
			if (args.length == 3 && args[1].equalsIgnoreCase("board")) {
				String pref3 = args[2] == null ? "" : args[2].toLowerCase();
				return java.util.List.of("help", "set", "status", "clear").stream().filter(s -> s.startsWith(pref3))
						.toList();
			}
			if (args.length == 4 && args[1].equalsIgnoreCase("board") && args[2].equalsIgnoreCase("set")) {
				String pref4 = args[3] == null ? "" : args[3].toLowerCase();
				return java.util.List.of("north", "south", "east", "west").stream().filter(s -> s.startsWith(pref4))
						.toList();
			}
			if (args.length == 3 && args[1].equalsIgnoreCase("track")) {
				return java.util.Arrays.asList("add", "remove", "list");
			}
			if (args.length == 4 && args[1].equalsIgnoreCase("track")
					&& (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))) {
				String prefix = args[3] == null ? "" : args[3].toLowerCase();
				java.util.List<String> names = new java.util.ArrayList<>();
				if (plugin.getTrackLibrary() != null) {
					for (String n : plugin.getTrackLibrary().list())
						if (n.toLowerCase().startsWith(prefix))
							names.add(n);
				}
				return names;
			}
			return java.util.Collections.emptyList();
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("profile")) {
			if (args.length == 2) {
				java.util.List<String> subs = new java.util.ArrayList<>();
				subs.add("speedunit");
				String pref2 = args[1] == null ? "" : args[1].toLowerCase();
				return subs.stream().filter(s -> s.startsWith(pref2)).toList();
			}
			if (args.length == 3 && args[1].equalsIgnoreCase("speedunit")) {
				String pref3 = args[2] == null ? "" : args[2].toLowerCase();
				return java.util.List.of("kmh", "bps", "bph").stream().filter(s -> s.startsWith(pref3)).toList();
			}
			return java.util.Collections.emptyList();
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("board")) {
			if (!sender.hasPermission("boatracing.admin"))
				return java.util.Collections.emptyList();
			if (args.length == 2)
				return java.util.Arrays.asList("help", "set", "status", "clear");
			if (args.length == 3 && args[1].equalsIgnoreCase("set"))
				return java.util.Arrays.asList("north", "south", "east", "west");
			return java.util.Collections.emptyList();
		}

		if (args.length >= 2 && (args[0].equalsIgnoreCase("scoreboard") || args[0].equalsIgnoreCase("sb"))) {
			if (!sender.hasPermission("boatracing.admin"))
				return java.util.Collections.emptyList();
			if (args.length == 2)
				return java.util.Arrays.asList("on", "off", "tick", "debug");
			if (args.length == 3 && args[1].equalsIgnoreCase("debug"))
				return java.util.Arrays.asList("on", "off");
			return java.util.Collections.emptyList();
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
			if (!sender.hasPermission("boatracing.admin"))
				return java.util.Collections.emptyList();
			if (args.length == 2)
				return java.util.Arrays.asList("help", "tracks", "event");
			return java.util.Collections.emptyList();
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("race")) {
			// Admin subcommands guarded; expose join/leave/status to everyone
			if (args.length == 2) {
				java.util.List<String> subs = new java.util.ArrayList<>();
				subs.add("help");
				subs.add("join");
				subs.add("leave");
				subs.add("status");
				if (sender.hasPermission("boatracing.race.admin") || sender.hasPermission("boatracing.setup")) {
					subs.add("open");
					subs.add("start");
					subs.add("force");
					subs.add("stop");
				}
				String pref = args[1] == null ? "" : args[1].toLowerCase();
				return subs.stream().filter(s -> s.startsWith(pref)).toList();
			}

			// For subcommands that take <track>, suggest track names from library
			if (args.length == 3
					&& java.util.Arrays.asList("open", "join", "leave", "force", "start", "stop", "status")
							.contains(args[1].toLowerCase())) {
				String prefix = args[2] == null ? "" : args[2].toLowerCase();
				java.util.List<String> names = new java.util.ArrayList<>();
				if (plugin.getTrackLibrary() != null) {
					for (String n : plugin.getTrackLibrary().list())
						if (n.toLowerCase().startsWith(prefix))
							names.add(n);
				}
				return names;
			}

			return java.util.Collections.emptyList();
		}

		if (args.length >= 2 && args[0].equalsIgnoreCase("setup")) {
			if (!sender.hasPermission("boatracing.setup"))
				return Collections.emptyList();
			if (args.length == 2)
				return List.of("help", "addstart", "clearstarts", "pos1", "pos2", "setbounds", "setwaitspawn",
						"setfinish", "addcheckpoint", "clearcheckpoints", "addlight", "clearlights", "setpos", "clearpos",
						"show", "selinfo", "wand", "wizard");
			if (args.length >= 3 && (args[1].equalsIgnoreCase("setpos") || args[1].equalsIgnoreCase("clearpos"))) {
				// Suggest player names (online + known offline)
				String prefName = args[2] == null ? "" : args[2].toLowerCase();
				java.util.Set<String> names = new java.util.LinkedHashSet<>();
				for (org.bukkit.entity.Player op : org.bukkit.Bukkit.getOnlinePlayers()) {
					if (op.getName() != null && op.getName().toLowerCase().startsWith(prefName))
						names.add(op.getName());
				}
				for (org.bukkit.OfflinePlayer op : org.bukkit.Bukkit.getOfflinePlayers()) {
					if (op.getName() != null && op.getName().toLowerCase().startsWith(prefName))
						names.add(op.getName());
				}
				if (args.length == 3)
					return new java.util.ArrayList<>(names);
				if (args.length == 4 && args[1].equalsIgnoreCase("setpos")) {
					// Suggest slot numbers and keyword 'auto'
					java.util.List<String> opts = new java.util.ArrayList<>();
					opts.add("auto");
					int max = plugin.getTrackConfig().getStarts().size();
					for (int i = 1; i <= Math.min(max, 20); i++)
						opts.add(String.valueOf(i));
					String pref = args[3] == null ? "" : args[3].toLowerCase();
					return opts.stream().filter(s -> s.startsWith(pref)).toList();
				}
				return java.util.Collections.emptyList();
			}
			// Do not expose wizard subcommands in tab-completion; single entrypoint UX
			return Collections.emptyList();
		}

		return Collections.emptyList();
	}

	// Resolve an OfflinePlayer without remote lookups: prefer online, then cache,
	// or UUID literal
	private org.bukkit.OfflinePlayer resolveOffline(String token) {
		if (token == null || token.isEmpty())
			return null;
		// 1) Exact online match
		org.bukkit.entity.Player online = Bukkit.getPlayerExact(token);
		if (online != null)
			return online;
		// 2) Try UUID literal
		try {
			java.util.UUID uid = java.util.UUID.fromString(token);
			return Bukkit.getOfflinePlayer(uid);
		} catch (IllegalArgumentException ignored) {
		}
		// 3) Try offline cache entries by name (case-insensitive)
		for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
			if (op.getName() != null && op.getName().equalsIgnoreCase(token))
				return op;
		}
		// Not found locally
		return null;
	}

	private static org.bukkit.block.Block getTargetBlockLenient(org.bukkit.entity.Player p, int range) {
		if (p == null)
			return null;
		try {
			try {
				org.bukkit.block.Block b = p.getTargetBlockExact(range, org.bukkit.FluidCollisionMode.ALWAYS);
				if (b != null)
					return b;
			} catch (Throwable ignored) {
			}

			try {
				org.bukkit.block.Block b = p.getTargetBlockExact(range);
				if (b != null)
					return b;
			} catch (Throwable ignored) {
			}

			try {
				org.bukkit.util.RayTraceResult rr = p.rayTraceBlocks((double) range, org.bukkit.FluidCollisionMode.ALWAYS);
				if (rr != null && rr.getHitBlock() != null)
					return rr.getHitBlock();
			} catch (Throwable ignored) {
			}
		} catch (Throwable ignored) {
		}
		return null;
	}
}
