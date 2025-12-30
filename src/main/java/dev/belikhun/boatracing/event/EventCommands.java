package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.util.Text;
import org.bukkit.entity.Player;

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
		}
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
