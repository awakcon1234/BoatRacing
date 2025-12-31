package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

public final class EventCommands {
	private EventCommands() {}

	public static boolean handle(BoatRacingPlugin plugin, Player p, String label, String[] args) {
		if (plugin == null || p == null)
			return true;
		EventService svc = plugin.getEventService();
		if (svc == null) {
			Text.msg(p, "&cTính năng sự kiện đang bị tắt.");
			return true;
		}

		if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
			help(p, label);
			return true;
		}

		String sub = args[1].toLowerCase();
		switch (sub) {
			case "opening" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return true;
				}
				if (args.length == 2 || args[2].equalsIgnoreCase("help")) {
					Text.msg(p, "&eMở đầu sự kiện (Opening Titles):");
					Text.tell(p, "&7 - &f/" + label + " event opening status");
					Text.tell(p, "&7 - &f/" + label + " event opening start");
					Text.tell(p, "&7 - &f/" + label + " event opening stop");
					Text.tell(p, "");
					Text.tell(p, "&eVị trí sân khấu / camera:");
					Text.tell(p, "&7 - &f/" + label + " event opening stage set &7(Lấy vị trí hiện tại)");
					Text.tell(p, "&7 - &f/" + label + " event opening stage clear");
					Text.tell(p, "&7 - &f/" + label + " event opening camera set &7(Lấy vị trí hiện tại)");
					Text.tell(p, "&7 - &f/" + label + " event opening camera clear");
					Text.tell(p, "");
					Text.tell(p, "&eBảng mở đầu (MapEngine):");
					Text.tell(p, "&7 - &f/" + label + " event opening board set [north|south|east|west] &7(Dùng selection hiện tại; bỏ trống để tự chọn)");
					Text.tell(p, "&7 - &f/" + label + " event opening board clear");
					Text.tell(p, "&7 - &f/" + label + " event opening board status");
					Text.tell(p, "&7 - &f/" + label + " event opening board preview");
					return true;
				}

				String act = args[2].toLowerCase();
				switch (act) {
					case "status" -> {
						Text.msg(p, "&eMở đầu sự kiện:");
						Text.tell(p, "&7● Đang chạy: " + (svc.isOpeningTitlesRunning() ? "&a✔" : "&c❌"));
						Location stage = readLocation(plugin, "mapengine.opening-titles.stage");
						Location cam = readLocation(plugin, "mapengine.opening-titles.camera");
						Text.tell(p, "&7● Sân khấu: " + (stage == null ? "&cChưa đặt" : "&a" + fmt(stage)));
						Text.tell(p, "&7● Camera: " + (cam == null ? "&cChưa đặt" : "&a" + fmt(cam)));
						try {
							var bs = svc.getOpeningTitlesBoardService();
							if (bs != null) {
								for (String line : bs.statusLines()) {
									Text.msg(p, line);
								}
							}
						} catch (Throwable ignored) {
						}
						return true;
					}
					case "start" -> {
						boolean ok = svc.startOpeningTitlesNow();
						Text.msg(p, ok ? "&aĐã bắt đầu mở đầu sự kiện." : "&cKhông thể bắt đầu lúc này. &7(Hãy đảm bảo chưa có chặng đang chạy/đếm ngược.)");
						return true;
					}
					case "stop" -> {
						svc.stopOpeningTitlesNow(true);
						Text.msg(p, "&aĐã dừng mở đầu sự kiện.");
						return true;
					}
					case "stage" -> {
						if (args.length < 4) {
							Text.msg(p, "&eDùng: /" + label + " event opening stage set|clear");
							return true;
						}
						String a2 = args[3].toLowerCase();
						switch (a2) {
							case "set" -> {
								writeLocation(plugin, "mapengine.opening-titles.stage", p.getLocation());
								Text.msg(p, "&aĐã đặt sân khấu mở đầu tại: &f" + fmt(p.getLocation()));
								return true;
							}
							case "clear" -> {
								clearLocation(plugin, "mapengine.opening-titles.stage");
								Text.msg(p, "&aĐã xóa vị trí sân khấu mở đầu.");
								return true;
							}
							default -> {
								Text.msg(p, "&cKhông rõ. Dùng: /" + label + " event opening stage set|clear");
								return true;
							}
						}
					}
					case "camera" -> {
						if (args.length < 4) {
							Text.msg(p, "&eDùng: /" + label + " event opening camera set|clear");
							return true;
						}
						String a2 = args[3].toLowerCase();
						switch (a2) {
							case "set" -> {
								writeLocation(plugin, "mapengine.opening-titles.camera", p.getLocation());
								Text.msg(p, "&aĐã đặt camera mở đầu tại: &f" + fmt(p.getLocation()));
								return true;
							}
							case "clear" -> {
								clearLocation(plugin, "mapengine.opening-titles.camera");
								Text.msg(p, "&aĐã xóa vị trí camera mở đầu.");
								return true;
							}
							default -> {
								Text.msg(p, "&cKhông rõ. Dùng: /" + label + " event opening camera set|clear");
								return true;
							}
						}
					}
					case "board" -> {
						var bs = svc.getOpeningTitlesBoardService();
						if (bs == null) {
							Text.msg(p, "&cTính năng bảng mở đầu đang bị tắt.");
							return true;
						}
						if (args.length == 3 || args[3].equalsIgnoreCase("help")) {
							Text.msg(p, "&eBảng mở đầu (MapEngine):");
							Text.tell(p, "&7 - &f/" + label + " event opening board set [north|south|east|west]");
							Text.tell(p, "&7 - &f/" + label + " event opening board clear");
							Text.tell(p, "&7 - &f/" + label + " event opening board status");
							Text.tell(p, "&7 - &f/" + label + " event opening board preview");
							return true;
						}

						String a2 = args[3].toLowerCase();
						switch (a2) {
							case "status" -> {
								for (String line : bs.statusLines()) {
									Text.msg(p, line);
								}
								return true;
							}
							case "preview" -> {
								svc.previewOpeningTitlesBoard(p);
								Text.msg(p, "&aĐã gửi bản xem trước bảng mở đầu.");
								return true;
							}
							case "clear" -> {
								bs.clearPlacement();
								Text.msg(p, "&aĐã xóa vị trí bảng mở đầu.");
								return true;
							}
							case "set" -> {
								var sel = dev.belikhun.boatracing.track.SelectionUtils.getSelectionDetailed(p);
								if (sel == null) {
									Text.msg(p, "&cKhông phát hiện selection. Dùng wand để chọn 2 góc trước.");
									return true;
								}
								org.bukkit.block.BlockFace face = null;
								if (args.length >= 5) {
									try {
										face = org.bukkit.block.BlockFace.valueOf(args[4].toUpperCase(java.util.Locale.ROOT));
									} catch (Throwable t) {
										Text.msg(p, "&cHướng không hợp lệ. Dùng: north|south|east|west");
										return true;
									}
								}

								boolean ok = bs.setPlacementFromSelection(p, sel.box, face);
								if (!ok) {
									Text.msg(p, "&cKhông thể đặt bảng. Hãy chọn vùng phẳng (2D) phù hợp và thử lại.");
									return true;
								}
								Text.msg(p, "&aĐã đặt bảng mở đầu.");
								Text.tell(p, bs.placementSummary());
								return true;
							}
							default -> {
								Text.msg(p, "&cKhông rõ lệnh. Dùng: /" + label + " event opening board help");
								return true;
							}
						}
					}
					default -> {
						Text.msg(p, "&cKhông rõ lệnh. Dùng: /" + label + " event opening help");
						return true;
					}
				}
			}
			case "board" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
					return true;
				}
				var bs = (svc != null ? svc.getEventBoardService() : null);
				if (bs == null) {
					Text.msg(p, "&cTính năng bảng sự kiện đang bị tắt.");
					return true;
				}
				if (args.length == 2 || args[2].equalsIgnoreCase("help")) {
					Text.msg(p, "&eBảng sự kiện (MapEngine):");
					Text.tell(p, "&7 - &f/" + label + " event board set [north|south|east|west] &7(Dùng selection hiện tại; bỏ trống để tự chọn theo hướng nhìn)");
					Text.tell(p, "&7 - &f/" + label + " event board clear");
					Text.tell(p, "&7 - &f/" + label + " event board status");
					return true;
				}

				String act = args[2].toLowerCase();
				switch (act) {
					case "status" -> {
						java.util.List<String> lines;
						try {
							lines = bs.statusLines();
						} catch (Throwable t) {
							lines = java.util.List.of("&cKhông thể lấy trạng thái bảng: "
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
						bs.clearPlacement();
						Text.msg(p, "&aĐã xóa vị trí bảng sự kiện.");
						return true;
					}
					case "set" -> {
						var sel = dev.belikhun.boatracing.track.SelectionUtils.getSelectionDetailed(p);
						if (sel == null) {
							Text.msg(p, "&cKhông phát hiện selection. Dùng wand để chọn 2 góc trước.");
							return true;
						}
						org.bukkit.block.BlockFace face = null;
						if (args.length >= 4) {
							try {
								face = org.bukkit.block.BlockFace.valueOf(args[3].toUpperCase(java.util.Locale.ROOT));
							} catch (Throwable t) {
								Text.msg(p, "&cHướng không hợp lệ. Dùng: north|south|east|west");
								return true;
							}
						}

						boolean ok = bs.setPlacementFromSelection(p, sel.box, face);
						if (!ok) {
							Text.msg(p, "&cKhông thể đặt bảng. Hãy chọn vùng phẳng (2D) phù hợp và thử lại.");
							return true;
						}
						Text.msg(p, "&aĐã đặt bảng sự kiện.");
						Text.tell(p, bs.placementSummary());
						return true;
					}
					default -> {
						Text.msg(p, "&cKhông rõ lệnh. Dùng: /" + label + " event board help");
						return true;
					}
				}
			}
			case "status" -> {
				if (!p.hasPermission("boatracing.event.status")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				RaceEvent e = svc.getActiveEvent();
				if (e == null) {
					Text.msg(p, "&7Hiện không có sự kiện nào đang hoạt động.");
					return true;
				}
				Text.msg(p, "&eSự kiện: &f" + safe(e.title));
				Text.tell(p, "&7ID: &f" + safe(e.id));
				Text.tell(p, "&7Trạng thái: &f" + safe(e.state == null ? null : e.state.name()));
				Text.tell(p, "&7Đường đua: &f" + (e.trackPool == null ? 0 : e.trackPool.size())
						+ " &8● &7Chặng hiện tại: &f" + (e.currentTrackIndex + 1));
				int regs = (e.participants == null ? 0 : e.participants.size());
				Text.tell(p, "&7Đã đăng ký: &f" + regs);
				return true;
			}
			case "join" -> {
				if (!p.hasPermission("boatracing.event.join")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				RaceEvent e = svc.getActiveEvent();
				if (e == null) {
					Text.msg(p, "&7Hiện không có sự kiện nào để đăng ký.");
					return true;
				}
				boolean ok = svc.registerToActiveEvent(p);
				if (!ok) {
					Text.msg(p, "&cKhông thể đăng ký lúc này. &7Hãy chờ khi sự kiện mở đăng ký.");
					return true;
				}
				Text.msg(p, "&a✔ Đã đăng ký sự kiện: &f" + safe(e.title));
				p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.3f);
				return true;
			}
			case "leave" -> {
				if (!p.hasPermission("boatracing.event.leave")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				boolean ok = svc.leaveActiveEvent(p);
				if (!ok) {
					Text.msg(p, "&7Bạn không ở trong sự kiện nào.");
					return true;
				}
				Text.msg(p, "&a⎋ Đã rời khỏi sự kiện.");
				return true;
			}

			case "create" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				if (args.length < 4) {
					Text.msg(p, "&eDùng: /" + label + " event create <id> <tiêu đề>");
					return true;
				}
				String id = args[2];
				String title = joinFrom(args, 3);
				boolean ok = svc.createEvent(id, title);
				Text.msg(p, ok ? "&aĐã tạo sự kiện: &f" + safe(title) : "&cKhông thể tạo sự kiện.");
				return true;
			}
			case "open" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				if (args.length < 3) {
					Text.msg(p, "&eDùng: /" + label + " event open <id>");
					return true;
				}
				boolean ok = svc.openRegistration(args[2]);
				Text.msg(p, ok ? "&aĐã mở đăng ký sự kiện." : "&cKhông thể mở đăng ký. &7Chỉ 1 sự kiện có thể hoạt động.");
				return true;
			}
			case "schedule" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				if (args.length < 3 || !args[2].matches("\\d+")) {
					Text.msg(p, "&eDùng: /" + label + " event schedule <giây_từ_bây_giờ>");
					return true;
				}
				int sec = Integer.parseInt(args[2]);
				boolean ok = svc.scheduleActiveEvent(sec);
				Text.msg(p, ok ? "&aĐã đặt giờ bắt đầu sau &f" + sec + "&a giây." : "&cKhông thể đặt lịch lúc này.");
				return true;
			}
			case "start" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				boolean ok = svc.startActiveEventNow();
				Text.msg(p, ok ? "&aĐã bắt đầu sự kiện." : "&cKhông thể bắt đầu. &7Kiểm tra track pool và trạng thái.");
				return true;
			}
			case "cancel" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				boolean ok = svc.cancelActiveEvent();
				Text.msg(p, ok ? "&aĐã hủy sự kiện." : "&cKhông có sự kiện để hủy.");
				return true;
			}
			case "track" -> {
				if (!p.hasPermission("boatracing.event.admin")) {
					Text.msg(p, "&cBạn không có quyền thực hiện điều đó.");
					return true;
				}
				if (args.length < 3) {
					Text.msg(p, "&eDùng: /" + label + " event track add|remove|list ...");
					return true;
				}
				String act = args[2].toLowerCase();
				switch (act) {
					case "list" -> {
						RaceEvent e = svc.getActiveEvent();
						if (e == null) {
							Text.msg(p, "&7Chưa có sự kiện đang mở. Dùng &f/" + label + " event open <id>&7.");
							return true;
						}
						Text.msg(p, "&eTrack pool:");
						List<String> pool = (e.trackPool == null ? List.of() : e.trackPool);
						if (pool.isEmpty()) {
							Text.tell(p, "&7(Trống)");
							return true;
						}
						for (int i = 0; i < pool.size(); i++) {
							Text.tell(p, "&7 - &f" + (i + 1) + ": &e" + pool.get(i));
						}
						return true;
					}
					case "add" -> {
						if (args.length < 4) {
							Text.msg(p, "&eDùng: /" + label + " event track add <track>");
							return true;
						}
						String tn = args[3];
						boolean ok = svc.addTrackToActiveEvent(tn);
						Text.msg(p, ok ? "&aĐã thêm track: &f" + tn : "&cKhông thể thêm track.");
						return true;
					}
					case "remove" -> {
						if (args.length < 4) {
							Text.msg(p, "&eDùng: /" + label + " event track remove <track>");
							return true;
						}
						String tn = args[3];
						boolean ok = svc.removeTrackFromActiveEvent(tn);
						Text.msg(p, ok ? "&aĐã xóa track: &f" + tn : "&cKhông thể xóa track.");
						return true;
					}
					default -> {
						Text.msg(p, "&cKhông rõ. Dùng: /" + label + " event track add|remove|list");
						return true;
					}
				}
			}
			default -> {
				help(p, label);
				return true;
			}
		}
	}

	private static void help(Player p, String label) {
		Text.msg(p, "&eSự kiện:");
		Text.tell(p, "&7 - &f/" + label + " event status");
		Text.tell(p, "&7 - &f/" + label + " event join");
		Text.tell(p, "&7 - &f/" + label + " event leave");
		if (p.hasPermission("boatracing.event.admin")) {
			Text.tell(p, "&8Quản trị:&7 /" + label + " event create|open|schedule|start|cancel");
			Text.tell(p, "&8Quản trị:&7 /" + label + " event track add|remove|list");
			Text.tell(p, "&8Quản trị:&7 /" + label + " event board set|status|clear");
			Text.tell(p, "&8Quản trị:&7 /" + label + " event opening ...");
		}
	}

	private static void writeLocation(BoatRacingPlugin plugin, String path, Location loc) {
		if (plugin == null || path == null || path.isBlank() || loc == null || loc.getWorld() == null)
			return;
		ConfigurationSection sec = plugin.getConfig().createSection(path);
		sec.set("world", loc.getWorld().getName());
		sec.set("x", loc.getX());
		sec.set("y", loc.getY());
		sec.set("z", loc.getZ());
		sec.set("yaw", (double) loc.getYaw());
		sec.set("pitch", (double) loc.getPitch());
		plugin.saveConfig();
	}

	private static void clearLocation(BoatRacingPlugin plugin, String path) {
		if (plugin == null || path == null || path.isBlank())
			return;
		plugin.getConfig().set(path, null);
		plugin.saveConfig();
	}

	private static Location readLocation(BoatRacingPlugin plugin, String path) {
		if (plugin == null || path == null || path.isBlank())
			return null;
		try {
			ConfigurationSection sec = plugin.getConfig().getConfigurationSection(path);
			if (sec == null)
				return null;
			String worldName = sec.getString("world", "");
			if (worldName == null || worldName.isBlank())
				return null;
			org.bukkit.World w = org.bukkit.Bukkit.getWorld(worldName);
			if (w == null)
				return null;
			double x = sec.getDouble("x", 0.0);
			double y = sec.getDouble("y", 0.0);
			double z = sec.getDouble("z", 0.0);
			float yaw = (float) sec.getDouble("yaw", 0.0);
			float pitch = (float) sec.getDouble("pitch", 0.0);
			return new Location(w, x, y, z, yaw, pitch);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String fmt(Location loc) {
		if (loc == null || loc.getWorld() == null)
			return "(không rõ)";
		return loc.getWorld().getName() + " &7(" + String.format(java.util.Locale.ROOT, "%.2f", loc.getX())
				+ ", " + String.format(java.util.Locale.ROOT, "%.2f", loc.getY())
				+ ", " + String.format(java.util.Locale.ROOT, "%.2f", loc.getZ()) + ")"
				+ " &8● &7yaw &f" + String.format(java.util.Locale.ROOT, "%.1f", loc.getYaw())
				+ "&7, pitch &f" + String.format(java.util.Locale.ROOT, "%.1f", loc.getPitch());
	}

	private static String joinFrom(String[] args, int start) {
		if (args == null || start >= args.length)
			return "";
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < args.length; i++) {
			if (i > start)
				sb.append(' ');
			sb.append(args[i]);
		}
		return sb.toString();
	}

	private static String safe(String s) {
		if (s == null)
			return "(không rõ)";
		String t = s.trim();
		return t.isEmpty() ? "(không rõ)" : t;
	}
}
