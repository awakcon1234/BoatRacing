package dev.belikhun.boatracing.event;

import dev.belikhun.boatracing.BoatRacingPlugin;
import dev.belikhun.boatracing.track.TrackConfig;
import dev.belikhun.boatracing.util.Text;
import dev.belikhun.boatracing.util.Time;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EventRegistrationNpcService {
	private final BoatRacingPlugin plugin;
	private final EventService eventService;
	private final NamespacedKey registerNpcKey;

	private final List<UUID> spawned = new ArrayList<>();
	private UUID textDisplayId;
	private UUID npcEntityId;

	private String cachedSlotsTrack;
	private int cachedSlots;

	public EventRegistrationNpcService(BoatRacingPlugin plugin, EventService eventService) {
		this.plugin = plugin;
		this.eventService = eventService;
		this.registerNpcKey = new NamespacedKey(plugin, "boatracing_event_register_npc");
		this.cachedSlotsTrack = null;
		this.cachedSlots = 0;
	}

	public NamespacedKey getRegisterNpcKey() {
		return registerNpcKey;
	}

	public boolean isRegisterNpcEntity(Entity e) {
		if (e == null)
			return false;
		try {
			return e.getPersistentDataContainer().has(registerNpcKey, PersistentDataType.BYTE);
		} catch (Throwable ignored) {
			return false;
		}
	}

	public boolean hasSpawnedAnything() {
		return !spawned.isEmpty() || textDisplayId != null || npcEntityId != null;
	}

	public void clear() {
		for (UUID id : new ArrayList<>(spawned)) {
			try {
				Entity e = Bukkit.getEntity(id);
				if (e != null)
					e.remove();
			} catch (Throwable ignored) {
			}
		}
		spawned.clear();
		textDisplayId = null;
		npcEntityId = null;

		// Best-effort sweep across all worlds (covers reloads + manual removals)
		try {
			for (World w : Bukkit.getWorlds()) {
				if (w == null)
					continue;
				for (Entity e : w.getEntities()) {
					try {
						if (e == null)
							continue;
						if (!isRegisterNpcEntity(e))
							continue;
						e.remove();
					} catch (Throwable ignored) {
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public void tick(RaceEvent e) {
		if (plugin == null)
			return;
		if (e == null || e.state != EventState.REGISTRATION) {
			if (hasSpawnedAnything())
				clear();
			return;
		}

		ensureSpawned(e);
		updateText(e);
	}

	public void ensureSpawned(RaceEvent e) {
		if (hasSpawnedAnything())
			return;
		Location loc = readLocation("event.registration-npc.location");
		if (loc == null) {
			World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
			if (w == null)
				return;
			loc = w.getSpawnLocation();
		}
		if (loc.getWorld() == null)
			return;
		ensureChunkLoaded(loc);

		UUID skinUuid = readSkinUuid();

		ArmorStand as;
		try {
			Location spawn = loc.clone();
			as = loc.getWorld().spawn(spawn, ArmorStand.class, ent -> {
				ent.setInvisible(true);
				ent.setMarker(false);
				ent.setGravity(false);
				ent.setSilent(true);
				try {
					ent.setInvulnerable(true);
				} catch (Throwable ignored) {
				}
				try {
					ent.setRemoveWhenFarAway(false);
				} catch (Throwable ignored) {
				}
				try {
					ent.getPersistentDataContainer().set(registerNpcKey, PersistentDataType.BYTE, (byte) 1);
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable ignored) {
			as = null;
		}
		if (as == null)
			return;
		npcEntityId = as.getUniqueId();
		spawned.add(npcEntityId);

		try {
			ItemStack head = new ItemStack(Material.PLAYER_HEAD);
			if (head.getItemMeta() instanceof SkullMeta sm) {
				try {
					if (skinUuid != null)
						sm.setOwningPlayer(Bukkit.getOfflinePlayer(skinUuid));
				} catch (Throwable ignored) {
				}
				head.setItemMeta(sm);
			}
			as.getEquipment().setHelmet(head);
		} catch (Throwable ignored) {
		}

		// Same approach as podium NPC: use a fixed TextDisplay block in front of the NPC.
		textDisplayId = spawnNpcFrontTextBlock(loc, buildText(e), 0.35, 0.38, 0.95f);
		if (textDisplayId != null)
			spawned.add(textDisplayId);
	}

	private void updateText(RaceEvent e) {
		if (textDisplayId == null)
			return;
		try {
			Entity ent = Bukkit.getEntity(textDisplayId);
			if (!(ent instanceof TextDisplay td))
				return;
			td.text(Text.c(buildText(e)));
		} catch (Throwable ignored) {
		}
	}

	private String buildText(RaceEvent e) {
		if (e == null)
			return "&7Chưa có sự kiện.";
		String title = (e.title == null || e.title.isBlank()) ? "(không rõ)" : e.title;
		int registered = countRegistered(e);
		int slots = getTotalSlotsCached(e);
		String slotsPart = (slots > 0) ? (registered + "/" + slots) : (String.valueOf(registered));

		String timeLeft;
		if (e.startTimeMillis > 0L) {
			long remainSec = Math.max(0L, (e.startTimeMillis - System.currentTimeMillis()) / 1000L);
			int sec = (int) Math.min(Integer.MAX_VALUE, remainSec);
			timeLeft = Time.formatCountdownSeconds(sec);
		} else {
			timeLeft = "Chưa đặt lịch";
		}

		return "&6&lSự kiện: &f" + title
				+ "\n&a● &fĐã đăng ký: &a" + slotsPart
				+ "\n&e⌚ &fCòn lại: &e" + timeLeft;
	}

	private int countRegistered(RaceEvent e) {
		if (e == null || e.participants == null)
			return 0;
		int c = 0;
		for (EventParticipant ep : e.participants.values()) {
			if (ep == null)
				continue;
			if (ep.status == EventParticipantStatus.REGISTERED)
				c++;
		}
		return c;
	}

	private int getTotalSlotsCached(RaceEvent e) {
		String tn = null;
		try {
			if (e != null && e.trackPool != null && !e.trackPool.isEmpty())
				tn = e.trackPool.get(0);
		} catch (Throwable ignored) {
			tn = null;
		}
		if (tn == null || tn.isBlank()) {
			cachedSlotsTrack = null;
			cachedSlots = 0;
			return 0;
		}
		if (cachedSlotsTrack != null && cachedSlotsTrack.equalsIgnoreCase(tn))
			return cachedSlots;
		cachedSlotsTrack = tn;
		cachedSlots = loadTrackStartSlots(tn);
		return cachedSlots;
	}

	private int loadTrackStartSlots(String trackName) {
		if (plugin == null || trackName == null || trackName.isBlank())
			return 0;
		try {
			TrackConfig cfg = new TrackConfig(plugin, plugin.getDataFolder());
			boolean ok = cfg.load(trackName);
			if (!ok)
				return 0;
			return Math.max(0, cfg.getStarts().size());
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private Location readLocation(String path) {
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

	private UUID readSkinUuid() {
		if (plugin == null)
			return null;
		try {
			String raw = plugin.getConfig().getString("event.registration-npc.skin", "");
			if (raw == null || raw.isBlank())
				return null;
			raw = raw.trim();
			try {
				return UUID.fromString(raw);
			} catch (Throwable ignored) {
				return null;
			}
		} catch (Throwable ignored) {
			return null;
		}
	}

	private void ensureChunkLoaded(Location loc) {
		if (loc == null || loc.getWorld() == null)
			return;
		try {
			loc.getChunk().load();
		} catch (Throwable ignored) {
		}
	}

	private UUID spawnNpcFrontTextBlock(Location base, String legacyMultiline, double yOffset, double forward, float scale) {
		if (base == null || base.getWorld() == null)
			return null;
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
					d.getPersistentDataContainer().set(registerNpcKey, PersistentDataType.BYTE, (byte) 1);
				} catch (Throwable ignored) {
				}
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
			return td.getUniqueId();
		} catch (Throwable ignored) {
			return null;
		}
	}
}
