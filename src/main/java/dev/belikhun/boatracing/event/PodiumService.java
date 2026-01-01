package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.integrations.fancynpcs.FancyNpcsApi;
import dev.belikhun.boatracing.profile.PlayerProfileManager;
import dev.belikhun.boatracing.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class PodiumService {
	private final BoatRacingPlugin plugin;
	private final NamespacedKey podiumKey;

	private final List<UUID> spawned = new ArrayList<>();
	private final List<String> spawnedFancyNpcIds = new ArrayList<>();

	public PodiumService(BoatRacingPlugin plugin) {
		this.plugin = plugin;
		this.podiumKey = new NamespacedKey(plugin, "boatracing_event_podium");
	}

	private boolean isDebug() {
		try {
			return plugin != null && plugin.getConfig().getBoolean("event.podium.debug", false);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void dbg(String msg) {
		if (!isDebug())
			return;
		try {
			if (plugin != null)
				plugin.getLogger().info("[PodiumDBG] " + msg);
		} catch (Throwable ignored) {
		}
	}

	public boolean hasSpawnedAnything() {
		return !spawned.isEmpty() || !spawnedFancyNpcIds.isEmpty();
	}

	public void clear() {
		// Remove FancyNpcs NPCs first (if used)
		for (String id : new ArrayList<>(spawnedFancyNpcIds)) {
			try {
				FancyNpcsApi.removeNpcById(id);
			} catch (Throwable ignored) {
			}
		}
		spawnedFancyNpcIds.clear();

		// Remove tracked entities
		for (UUID id : new ArrayList<>(spawned)) {
			try {
				Entity e = Bukkit.getEntity(id);
				if (e != null)
					e.remove();
			} catch (Throwable ignored) {
			}
		}
		spawned.clear();

		// Best-effort sweep across all worlds (covers reloads + custom podium locations)
		try {
			for (World w : Bukkit.getWorlds()) {
				if (w == null)
					continue;
				for (Entity e : w.getEntities()) {
					try {
						if (e == null)
							continue;
						if (!e.getPersistentDataContainer().has(podiumKey, PersistentDataType.BYTE))
							continue;
						e.remove();
					} catch (Throwable ignored) {
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public void spawnTop3(RaceEvent event) {
		if (plugin == null || event == null)
			return;
		dbg("spawnTop3: eventId=" + (event.id == null ? "<no-id>" : event.id)
				+ " state=" + (event.state == null ? "<null>" : event.state.name())
				+ " participants=" + (event.participants == null ? 0 : event.participants.size()));
		clear();

		Location spawn = readPodiumBase();
		if (spawn == null) {
			dbg("spawnTop3: readPodiumBase() returned null (no world loaded?)");
			return;
		}
		dbg("spawnTop3: base=" + safeLoc(spawn));

		List<EventParticipant> sorted = new ArrayList<>();
		if (event.participants != null)
			sorted.addAll(event.participants.values());
		sorted.sort(Comparator
				.comparingInt((EventParticipant p) -> p == null ? Integer.MIN_VALUE : p.pointsTotal).reversed()
				.thenComparing(p -> p == null ? "" : (p.nameSnapshot == null ? "" : p.nameSnapshot), String.CASE_INSENSITIVE_ORDER));

		List<EventParticipant> top = new ArrayList<>();
		for (EventParticipant p : sorted) {
			if (p == null || p.id == null)
				continue;
			if (p.status == EventParticipantStatus.LEFT)
				continue;
			top.add(p);
			if (top.size() >= 3)
				break;
		}
		if (top.isEmpty())
		{
			dbg("spawnTop3: no eligible participants for podium.");
			return;
		}
		try {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < top.size(); i++) {
				EventParticipant ep = top.get(i);
				if (ep == null)
					continue;
				if (sb.length() > 0)
					sb.append(" | ");
				sb.append("#").append(i + 1).append(":")
						.append(ep.nameSnapshot == null ? "<no-name>" : ep.nameSnapshot)
						.append("(").append(ep.pointsTotal).append(")");
			}
			dbg("spawnTop3: top=" + sb);
		} catch (Throwable ignored) {
		}

		// Positions:
		// - If top1/top2/top3 are configured, use them exactly.
		// - Otherwise fall back to a simple layout around base and snap to surface.
		Location base = spawn.clone().add(0.5, 0.0, 0.5);
		ensureChunkLoaded(base);
		Location first = readConfigured("event.podium.positions.top1");
		Location second = readConfigured("event.podium.positions.top2");
		Location third = readConfigured("event.podium.positions.top3");
		dbg("spawnTop3: configured top1=" + safeLoc(first) + " top2=" + safeLoc(second) + " top3=" + safeLoc(third));

		if (first == null)
			first = snapToSurface(base.clone().add(0.0, 0.0, 2.0));
		if (second == null)
			second = snapToSurface(base.clone().add(-1.5, 0.0, 2.0));
		if (third == null)
			third = snapToSurface(base.clone().add(1.5, 0.0, 2.0));

		ensureChunkLoaded(first);
		ensureChunkLoaded(second);
		ensureChunkLoaded(third);

		spawnOne(top, 0, first);
		if (top.size() >= 2)
			spawnOne(top, 1, second);
		if (top.size() >= 3)
			spawnOne(top, 2, third);
		dbg("spawnTop3: done (spawnedEntities=" + spawned.size() + ", spawnedFancyNpcs=" + spawnedFancyNpcIds.size() + ")");
	}

	private Location readConfigured(String path) {
		if (plugin == null || path == null || path.isBlank())
			return null;
		try {
			ConfigurationSection sec = plugin.getConfig().getConfigurationSection(path);
			if (sec == null)
				return null;
			String worldName = sec.getString("world", "");
			if (worldName == null || worldName.isBlank())
				return null;
			World w = Bukkit.getWorld(worldName);
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

	private Location readPodiumBase() {
		try {
			ConfigurationSection sec = plugin.getConfig().getConfigurationSection("event.podium.location");
			if (sec != null) {
				String worldName = sec.getString("world", "");
				if (worldName != null && !worldName.isBlank()) {
					World w = Bukkit.getWorld(worldName);
					if (w != null) {
						double x = sec.getDouble("x", 0.0);
						double y = sec.getDouble("y", 0.0);
						double z = sec.getDouble("z", 0.0);
						float yaw = (float) sec.getDouble("yaw", 0.0);
						float pitch = (float) sec.getDouble("pitch", 0.0);
						return new Location(w, x, y, z, yaw, pitch);
					}
				}
			}
		} catch (Throwable ignored) {
		}

		World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
		try {
			if (w == null)
				w = Bukkit.getWorld("world");
		} catch (Throwable ignored) {
		}
		if (w == null)
			return null;
		return w.getSpawnLocation();
	}

	private Location snapToSurface(Location l) {
		if (l == null || l.getWorld() == null)
			return l;
		try {
			int y = l.getWorld().getHighestBlockYAt(l);
			return new Location(l.getWorld(), l.getX(), y + 1.0, l.getZ(), l.getYaw(), l.getPitch());
		} catch (Throwable ignored) {
			return l;
		}
	}

	private void spawnOne(List<EventParticipant> top, int index, Location loc) {
		if (loc == null || loc.getWorld() == null)
			return;
		ensureChunkLoaded(loc);
		if (index < 0 || index >= top.size())
			return;
		EventParticipant p = top.get(index);
		UUID id = p.id;
		dbg("spawnOne: index=" + index + " loc=" + safeLoc(loc)
				+ " fancynpcsEnabled=" + Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")
				+ " playerId=" + id);

		// Prefer a proper PLAYER NPC (full skin) when FancyNpcs is available.
		try {
			if (Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
				String name = (p.nameSnapshot == null || p.nameSnapshot.isBlank()) ? "(không rõ)" : p.nameSnapshot;
				// FancyNpcs official skin loader expects an identifier (username/url/file/@none/@mirror),
				// so use the player name rather than UUID.
				String skinIdentifier = null;
				try {
					skinIdentifier = (p.nameSnapshot == null ? null : p.nameSnapshot.trim());
					if (skinIdentifier != null && skinIdentifier.isBlank())
						skinIdentifier = null;
				} catch (Throwable ignored) {
					skinIdentifier = null;
				}
				if (skinIdentifier == null) {
					try {
						String n2 = Bukkit.getOfflinePlayer(id).getName();
						if (n2 != null && !n2.isBlank())
							skinIdentifier = n2;
					} catch (Throwable ignored) {
					}
				}
				if (skinIdentifier == null)
					skinIdentifier = name;
				String line1Mini;
				String line1Legacy;
				try {
					PlayerProfileManager pm = plugin.getProfileManager();
					line1Mini = (pm != null) ? pm.formatRacerMini(id, name) : ("<white>" + name);
					line1Legacy = (pm != null) ? pm.formatRacerLegacy(id, name) : ("&f" + name);
				} catch (Throwable ignored) {
					line1Mini = "<white>" + name;
					line1Legacy = "&f" + name;
				}
				String line2Legacy = "&e" + p.pointsTotal + " &7điểm";

				// Hide the NPC's own name tag and render our own multi-line TextDisplay.
				// FancyNpcs supports the special token "<empty>" for an empty name tag.
				String display = "<empty>";

				String npcName = "br-event-podium-" + System.currentTimeMillis() + "-" + index;
				dbg("spawnOne: FancyNpcs skinIdentifier=" + skinIdentifier);
				String npcId = FancyNpcsApi.spawnPlayerNpc(
						npcName,
						skinIdentifier,
						false,
						loc,
						display,
						false,
						false
				);
				if (npcId != null && !npcId.isBlank()) {
					spawnedFancyNpcIds.add(npcId);
					dbg("spawnOne: FancyNpcs spawned npcId=" + npcId);
					// Lower by 1 block compared to the previous placement.
					spawnNpcFrontTextBlock(loc, line1Legacy + "\n" + line2Legacy, 0.35, 0.38, 0.95f);
					return;
				}
				dbg("spawnOne: FancyNpcs spawn returned empty npcId");

				// FancyNpcs may not be fully ready yet during early startup (NpcManager not loaded).
				// If no players are online, do NOT fall back to the ArmorStand head variant;
				// let the caller retry later so we can still spawn real FancyNpcs player models.
				try {
					if (Bukkit.getOnlinePlayers().isEmpty()) {
						dbg("spawnOne: deferring fallback (no players online)");
						return;
					}
				} catch (Throwable ignored) {
					return;
				}
			}
		} catch (Throwable ignored) {
			dbg("spawnOne: FancyNpcs exception: " + ignored.getMessage());
		}

		// ArmorStand "body" holding the player head.
		ArmorStand as = null;
		try {
			as = loc.getWorld().spawn(loc, ArmorStand.class, e -> {
				e.setInvisible(true);
				e.setMarker(true);
				e.setGravity(false);
				e.setSilent(true);
				try {
					e.getPersistentDataContainer().set(podiumKey, PersistentDataType.BYTE, (byte) 1);
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable ignored) {
			as = null;
		}
		if (as == null)
			return;
		dbg("spawnOne: fallback ArmorStand spawned=" + as.getUniqueId());

		try {
			ItemStack head = new ItemStack(Material.PLAYER_HEAD);
			if (head.getItemMeta() instanceof SkullMeta sm) {
				try {
					sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
				} catch (Throwable ignored) {
				}
				head.setItemMeta(sm);
			}
			as.getEquipment().setHelmet(head);
		} catch (Throwable ignored) {
		}

		spawned.add(as.getUniqueId());

		// TextDisplay nameplate (2 lines) - use 2 separate displays to guarantee line breaks.
		String name = (p.nameSnapshot == null || p.nameSnapshot.isBlank()) ? "(không rõ)" : p.nameSnapshot;
		String line1;
		try {
			PlayerProfileManager pm = plugin.getProfileManager();
			line1 = (pm != null) ? pm.formatRacerLegacy(id, name) : ("&f" + name);
		} catch (Throwable ignored) {
			line1 = "&f" + name;
		}
		spawnNameLine(loc, line1, 1.60);
		spawnPointsLine(loc, "&e" + p.pointsTotal + " &7điểm", 1.25);
	}

	private String safeLoc(Location l) {
		if (l == null)
			return "<null>";
		World w = l.getWorld();
		String wn = (w == null ? "<null-world>" : w.getName());
		return wn + String.format("@(%.2f,%.2f,%.2f yaw=%.1f)", l.getX(), l.getY(), l.getZ(), l.getYaw());
	}

	private void spawnNpcFrontTextBlock(Location base, String legacyMultiline, double yOffset, double forward, float scale) {
		if (base == null || base.getWorld() == null)
			return;
		ensureChunkLoaded(base);

		org.bukkit.Location dirLoc = base.clone();
		dirLoc.setPitch(0.0f);
		org.bukkit.util.Vector fv;
		try {
			fv = dirLoc.getDirection().normalize().multiply(forward);
		} catch (Throwable ignored) {
			fv = new org.bukkit.util.Vector(0, 0, 0);
		}

		Location tl = base.clone().add(fv).add(0.0, yOffset, 0.0);
		try {
			TextDisplay td = tl.getWorld().spawn(tl, TextDisplay.class, d -> {
				try {
					d.getPersistentDataContainer().set(podiumKey, PersistentDataType.BYTE, (byte) 1);
				} catch (Throwable ignored) {
				}
				// Fixed: don't face the camera; face the same direction as the NPC.
				try {
					d.setRotation(base.getYaw(), 0.0f);
				} catch (Throwable ignored) {
				}
				try {
					d.text(Text.c(legacyMultiline));
				} catch (Throwable ignored) {
				}
				try {
					d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
				} catch (Throwable ignored) {
				}
				try {
					d.setSeeThrough(true);
				} catch (Throwable ignored) {
				}
				try {
					d.setDefaultBackground(false);
				} catch (Throwable ignored) {
				}
				try {
					d.setViewRange(256.0f);
				} catch (Throwable ignored) {
				}
				try {
					d.setShadowed(false);
				} catch (Throwable ignored) {
				}
				try {
					d.setTransformation(new org.bukkit.util.Transformation(
							new org.joml.Vector3f(0, 0, 0),
							new org.joml.Quaternionf(),
							new org.joml.Vector3f(scale, scale, scale),
							new org.joml.Quaternionf()
					));
				} catch (Throwable ignored) {
				}
			});
			spawned.add(td.getUniqueId());
		} catch (Throwable ignored) {
		}
	}

	private void spawnNameLine(Location base, String legacy, double yOffset) {
		spawnTextLine(base, legacy, yOffset, 1.00f);
	}

	private void spawnPointsLine(Location base, String legacy, double yOffset) {
		spawnTextLine(base, legacy, yOffset, 0.90f);
	}

	private void spawnTextLine(Location base, String legacy, double yOffset, float scale) {
		if (base == null || base.getWorld() == null)
			return;
		ensureChunkLoaded(base);
		Location tl = base.clone().add(0.0, yOffset, 0.0);
		try {
			TextDisplay td = tl.getWorld().spawn(tl, TextDisplay.class, d -> {
				try {
					d.getPersistentDataContainer().set(podiumKey, PersistentDataType.BYTE, (byte) 1);
				} catch (Throwable ignored) {
				}
				try {
					d.text(Text.c(legacy));
				} catch (Throwable ignored) {
				}
				try {
					d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
				} catch (Throwable ignored) {
				}
				try {
					d.setSeeThrough(true);
				} catch (Throwable ignored) {
				}
				try {
					d.setDefaultBackground(false);
				} catch (Throwable ignored) {
				}
				try {
					// 16 chunks = 256 blocks
					d.setViewRange(256.0f);
				} catch (Throwable ignored) {
				}
				try {
					d.setShadowed(false);
				} catch (Throwable ignored) {
				}
				try {
					d.setTransformation(new org.bukkit.util.Transformation(
							new org.joml.Vector3f(0, 0, 0),
							new org.joml.Quaternionf(),
							new org.joml.Vector3f(scale, scale, scale),
							new org.joml.Quaternionf()
					));
				} catch (Throwable ignored) {
				}
			});
			spawned.add(td.getUniqueId());
		} catch (Throwable ignored) {
		}
	}

	private void ensureChunkLoaded(Location l) {
		if (l == null)
			return;
		World w = l.getWorld();
		if (w == null)
			return;
		try {
			org.bukkit.Chunk c = w.getChunkAt(l);
			if (c != null && !c.isLoaded()) {
				boolean ok = c.load();
				dbg("ensureChunkLoaded: world=" + w.getName() + " chunk=" + c.getX() + "," + c.getZ() + " loaded=" + ok);
			}
		} catch (Throwable ignored) {
			dbg("ensureChunkLoaded: exception: " + ignored.getMessage());
		}
	}
}
