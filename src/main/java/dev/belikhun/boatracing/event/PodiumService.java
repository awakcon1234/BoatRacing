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

		// Best-effort sweep around spawn (covers reloads)
		try {
			for (World w : Bukkit.getWorlds()) {
				if (w == null)
					continue;
				Location s = w.getSpawnLocation();
				if (s == null)
					continue;
				for (Entity e : w.getNearbyEntities(s, 16, 16, 16)) {
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
		clear();

		World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
		try {
			if (w == null)
				w = Bukkit.getWorld("world");
		} catch (Throwable ignored) {

		}
		if (w == null)
			return;

		Location spawn = w.getSpawnLocation();
		if (spawn == null)
			return;

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
			return;

		// Simple layout around spawn (no fancy podium blocks).
		// 1st: center, 2nd: left, 3rd: right
		Location base = spawn.clone().add(0.5, 0.0, 0.5);
		Location first = base.clone().add(0.0, 0.0, 2.0);
		Location second = base.clone().add(-1.5, 0.0, 2.0);
		Location third = base.clone().add(1.5, 0.0, 2.0);

		// Adjust Y to surface.
		first = snapToSurface(first);
		second = snapToSurface(second);
		third = snapToSurface(third);

		spawnOne(top, 0, first);
		if (top.size() >= 2)
			spawnOne(top, 1, second);
		if (top.size() >= 3)
			spawnOne(top, 2, third);
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
		if (index < 0 || index >= top.size())
			return;
		EventParticipant p = top.get(index);
		UUID id = p.id;

		// Prefer a proper PLAYER NPC (full skin) when FancyNpcs is available.
		try {
			if (Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
				String name = (p.nameSnapshot == null || p.nameSnapshot.isBlank()) ? "(không rõ)" : p.nameSnapshot;
				String line1;
				try {
					PlayerProfileManager pm = plugin.getProfileManager();
					line1 = (pm != null) ? pm.formatRacerMini(id, name) : ("<white>" + name);
				} catch (Throwable ignored) {
					line1 = "<white>" + name;
				}
				String line2 = "<yellow>" + p.pointsTotal + " <gray>điểm";
				String display = line1 + "\n" + line2;

				String npcName = "br-event-podium-" + System.currentTimeMillis() + "-" + index;
				String npcId = FancyNpcsApi.spawnPlayerNpc(
						npcName,
						id,
						loc,
						display,
						false,
						false
				);
				if (npcId != null && !npcId.isBlank()) {
					spawnedFancyNpcIds.add(npcId);
					return;
				}
			}
		} catch (Throwable ignored) {
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

		// TextDisplay nameplate (2 lines)
		String name = (p.nameSnapshot == null || p.nameSnapshot.isBlank()) ? "(không rõ)" : p.nameSnapshot;
		String line1;
		try {
			PlayerProfileManager pm = plugin.getProfileManager();
			line1 = (pm != null) ? pm.formatRacerLegacy(id, name) : ("&f" + name);
		} catch (Throwable ignored) {
			line1 = "&f" + name;
		}
		String line2 = "&e" + p.pointsTotal + " &7điểm";
		final String nameplate = line1 + "\n" + line2;

		Location tl = loc.clone().add(0.0, 1.6, 0.0);
		try {
			TextDisplay td = tl.getWorld().spawn(tl, TextDisplay.class, d -> {
				try {
					d.getPersistentDataContainer().set(podiumKey, PersistentDataType.BYTE, (byte) 1);
				} catch (Throwable ignored) {
				}
				try {
					d.text(Text.c(nameplate));
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
					d.setViewRange(48.0f);
				} catch (Throwable ignored) {
				}
			});
			spawned.add(td.getUniqueId());
		} catch (Throwable ignored) {
		}
	}
}
