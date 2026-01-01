package dev.belikhun.boatracing.integrations.fancynpcs;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public final class FancyNpcsApi {
	private FancyNpcsApi() {
	}

	/**
	 * Returns true if FancyNpcs' NpcManager is loaded and can spawn NPCs.
	 */
	public static boolean isReady() {
		return isNpcManagerReady();
	}

	private static boolean isNpcManagerReady() {
		try {
			var fancy = FancyNpcsPlugin.get();
			var npcManager = fancy.getNpcManager();
			return npcManager != null && npcManager.isLoaded();
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static Npc getNpcById(String npcId) {
		if (npcId == null || npcId.isBlank())
			return null;
		try {
			var fancy = FancyNpcsPlugin.get();
			var npcManager = fancy.getNpcManager();
			if (npcManager == null || !npcManager.isLoaded())
				return null;
			return npcManager.getNpcById(npcId);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Entity tryGetNpcEntity(Npc npc) {
		if (npc == null)
			return null;
		try {
			java.lang.reflect.Method m = npc.getClass().getMethod("getEntity");
			Object o = m.invoke(npc);
			if (o instanceof Entity e)
				return e;
		} catch (Throwable ignored) {
		}
		try {
			java.lang.reflect.Method m = npc.getClass().getMethod("getBukkitEntity");
			Object o = m.invoke(npc);
			if (o instanceof Entity e)
				return e;
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static void tryApplySlimFlag(NpcData data, boolean slim) {
		if (!slim || data == null)
			return;
		// FancyNpcs has an optional '--slim' flag in commands. The API surface differs
		// between versions, so use reflection and best-effort.
		try {
			java.lang.reflect.Method m = data.getClass().getMethod("setSlim", boolean.class);
			m.invoke(data, true);
			return;
		} catch (Throwable ignored) {
		}
		try {
			java.lang.reflect.Method m = data.getClass().getMethod("setSkinSlim", boolean.class);
			m.invoke(data, true);
			return;
		} catch (Throwable ignored) {
		}
		try {
			java.lang.reflect.Method m = data.getClass().getMethod("setUseSlimModel", boolean.class);
			m.invoke(data, true);
		} catch (Throwable ignored) {
		}
	}

	private static boolean trySetMirrorSkin(NpcData data, boolean mirror) {
		if (data == null)
			return false;
		try {
			java.lang.reflect.Method m = data.getClass().getMethod("setMirrorSkin", boolean.class);
			m.invoke(data, mirror);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean trySetSkinData(NpcData data, Object skinDataOrNull) {
		if (data == null)
			return false;
		try {
			for (java.lang.reflect.Method m : data.getClass().getMethods()) {
				if (!m.getName().equals("setSkinData"))
					continue;
				if (m.getParameterCount() != 1)
					continue;
				m.invoke(data, skinDataOrNull);
				return true;
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static String[] tryGetOnlinePlayerTextures(UUID playerId) {
		if (playerId == null)
			return null;
		try {
			org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(playerId);
			if (p == null)
				return null;

			// Paper/Spigot API does not reliably expose the base64 texture value+signature.
			// Use reflection against CraftPlayer -> GameProfile -> PropertyMap.
			Object gameProfile;
			try {
				java.lang.reflect.Method m = p.getClass().getMethod("getProfile");
				gameProfile = m.invoke(p);
			} catch (Throwable ignored) {
				gameProfile = null;
			}
			if (gameProfile == null)
				return null;

			Object props;
			try {
				java.lang.reflect.Method m = gameProfile.getClass().getMethod("getProperties");
				props = m.invoke(gameProfile);
			} catch (Throwable ignored) {
				props = null;
			}
			if (props == null)
				return null;

			Object textures;
			try {
				java.lang.reflect.Method m = props.getClass().getMethod("get", Object.class);
				textures = m.invoke(props, "textures");
			} catch (Throwable ignored) {
				textures = null;
			}
			if (textures == null)
				return null;

			java.util.Iterator<?> it;
			try {
				it = ((java.lang.Iterable<?>) textures).iterator();
			} catch (Throwable ignored) {
				try {
					java.lang.reflect.Method m = textures.getClass().getMethod("iterator");
					Object o = m.invoke(textures);
					it = (o instanceof java.util.Iterator<?> i) ? i : null;
				} catch (Throwable ignored2) {
					it = null;
				}
			}
			if (it == null || !it.hasNext())
				return null;
			Object prop = it.next();
			if (prop == null)
				return null;

			String v;
			String s;
			try {
				java.lang.reflect.Method mv = prop.getClass().getMethod("getValue");
				Object ov = mv.invoke(prop);
				v = (ov == null ? null : ov.toString());
			} catch (Throwable ignored) {
				v = null;
			}
			try {
				java.lang.reflect.Method ms = prop.getClass().getMethod("getSignature");
				Object os = ms.invoke(prop);
				s = (os == null ? "" : os.toString());
			} catch (Throwable ignored) {
				s = "";
			}

			if (v == null || v.isBlank())
				return null;
			return new String[] { v, (s == null ? "" : s) };
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static Object tryBuildSkinDataFromTextures(String value, String signature, boolean slim) {
		if (value == null || value.isBlank())
			return null;
		try {
			Class<?> skinDataClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData");

			// Prefer static factories if present.
			for (String mname : new String[] { "fromTexture", "ofTextures", "of" }) {
				try {
					java.lang.reflect.Method m = skinDataClass.getMethod(mname, String.class, String.class);
					Object sd = m.invoke(null, value, signature);
					if (sd != null)
						return sd;
				} catch (Throwable ignored) {
				}
			}

			// Try constructors.
			for (java.lang.reflect.Constructor<?> c : skinDataClass.getConstructors()) {
				try {
					Class<?>[] p = c.getParameterTypes();
					if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
						return c.newInstance(value, signature);
					}
				} catch (Throwable ignored) {
				}
			}

			// No-arg + setters.
			Object sd;
			try {
				sd = skinDataClass.getDeclaredConstructor().newInstance();
			} catch (Throwable ignored) {
				sd = null;
			}
			if (sd == null)
				return null;

			boolean any = false;
			for (String mname : new String[] { "setTexture", "setTextures", "setValue" }) {
				try {
					java.lang.reflect.Method m = skinDataClass.getMethod(mname, String.class);
					m.invoke(sd, value);
					any = true;
					break;
				} catch (Throwable ignored) {
				}
			}
			for (String mname : new String[] { "setSignature", "setTextureSignature" }) {
				try {
					java.lang.reflect.Method m = skinDataClass.getMethod(mname, String.class);
					m.invoke(sd, signature);
					any = true;
					break;
				} catch (Throwable ignored) {
				}
			}

			// Optional variant; ignore failures.
			if (slim) {
				try {
					Class<?> variantClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData$SkinVariant");
					Object variant = java.lang.Enum.valueOf((Class) variantClass, "SLIM");
					java.lang.reflect.Method m = skinDataClass.getMethod("setVariant", variantClass);
					m.invoke(sd, variant);
				} catch (Throwable ignored) {
				}
			}

			return any ? sd : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static boolean tryApplyTextureSkin(NpcData data, String value, String signature, boolean slim, String identifierForDebug) {
		if (data == null)
			return false;
		Object sd = tryBuildSkinDataFromTextures(value, signature, slim);
		if (sd == null)
			return false;
		trySetMirrorSkin(data, false);
		if (!trySetSkinData(data, sd))
			return false;
		try {
			if (identifierForDebug != null && !identifierForDebug.isBlank()) {
				java.lang.reflect.Method setIdentifier = sd.getClass().getMethod("setIdentifier", String.class);
				setIdentifier.invoke(sd, identifierForDebug);
			}
		} catch (Throwable ignored) {
		}
		return true;
	}

	/**
	 * Best-effort implementation that mirrors FancyNpcs official command behavior:
	 * - @mirror: mirror skin
	 * - @none: clear skin
	 * - otherwise: load SkinData via SkinManager#getByIdentifier(identifier, variant)
	 *
	 * Uses reflection to avoid hard dependency on FancyNpcs internals.
	 */
	private static boolean tryApplyOfficialSkinSemantics(NpcData data, String skin, boolean slim) {
		if (data == null)
			return false;
		skin = (skin == null ? "" : skin.trim());
		if (skin.isBlank())
			return false;

		boolean isMirror = skin.equalsIgnoreCase("@mirror");
		boolean isNone = skin.equalsIgnoreCase("@none");
		if (isMirror) {
			boolean okMirror = trySetMirrorSkin(data, true);
			boolean okClear = trySetSkinData(data, null);
			return okMirror || okClear;
		}
		if (isNone) {
			boolean okMirror = trySetMirrorSkin(data, false);
			boolean okClear = trySetSkinData(data, null);
			return okMirror || okClear;
		}

		try {
			Class<?> fancyClazz = Class.forName("de.oliver.fancynpcs.FancyNpcs");
			Object fancy = fancyClazz.getMethod("getInstance").invoke(null);
			if (fancy == null)
				return false;
			Object skinMgr = fancy.getClass().getMethod("getSkinManagerImpl").invoke(fancy);
			if (skinMgr == null)
				return false;

			Class<?> variantClass = Class.forName("de.oliver.fancynpcs.api.skins.SkinData$SkinVariant");
			Object variant;
			if (slim) {
				variant = java.lang.Enum.valueOf((Class) variantClass, "SLIM");
			} else {
				variant = java.lang.Enum.valueOf((Class) variantClass, "AUTO");
			}

			java.lang.reflect.Method getByIdentifier = null;
			for (java.lang.reflect.Method m : skinMgr.getClass().getMethods()) {
				if (!m.getName().equals("getByIdentifier"))
					continue;
				Class<?>[] params = m.getParameterTypes();
				if (params.length == 2 && params[0] == String.class && params[1].getName().equals(variantClass.getName())) {
					getByIdentifier = m;
					break;
				}
			}
			if (getByIdentifier == null)
				return false;

			Object skinData = getByIdentifier.invoke(skinMgr, skin, variant);
			if (skinData == null)
				return false;
			try {
				java.lang.reflect.Method setIdentifier = skinData.getClass().getMethod("setIdentifier", String.class);
				setIdentifier.invoke(skinData, skin);
			} catch (Throwable ignored) {
			}

			trySetMirrorSkin(data, false);
			if (!trySetSkinData(data, skinData))
				return false;
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	public static boolean tagNpcEntityById(String npcId, NamespacedKey key) {
		if (key == null)
			return false;
		Npc npc = getNpcById(npcId);
		Entity e = tryGetNpcEntity(npc);
		if (e == null)
			return false;
		try {
			e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * Spawns a temporary PLAYER NPC (not persisted) via FancyNpcs.
	 *
	 * Returns null if FancyNpcs isn't ready yet (NpcManager not loaded).
	 */
	public static String spawnPlayerNpc(String npcName, UUID skinUuid, Location location,
			String displayNameMini, boolean collidable, boolean showInTab) {
		if (npcName == null || npcName.isBlank())
			return null;
		if (location == null || location.getWorld() == null)
			return null;
		if (!isNpcManagerReady())
			return null;
		var fancy = FancyNpcsPlugin.get();
		var npcManager = fancy.getNpcManager();

		NpcData data = new NpcData(npcName, UUID.randomUUID(), location);

		try {
			if (skinUuid != null)
				data.setSkin(skinUuid.toString());
		} catch (Throwable ignored) {
		}

		try {
			if (displayNameMini != null)
				data.setDisplayName(displayNameMini);
		} catch (Throwable ignored) {
		}

		try {
			data.setCollidable(collidable);
		} catch (Throwable ignored) {
		}

		try {
			data.setShowInTab(showInTab);
		} catch (Throwable ignored) {
		}

		Npc npc = fancy.getNpcAdapter().apply(data);
		if (npc == null)
			return null;

		// Do not persist the podium NPC.
		try {
			npc.setSaveToFile(false);
		} catch (Throwable ignored) {
		}

		npcManager.registerNpc(npc);
		npc.create();
		npc.spawnForAll();

		return data.getId();
	}

	/**
	 * Spawn a temporary PLAYER NPC by preferring live texture properties (value+signature)
	 * from an online player profile, then falling back to UUID string, then username identifier.
	 */
	public static String spawnPlayerNpcPreferTextures(String npcName, UUID playerId, String playerName, boolean slim, Location location,
			String displayNameMini, boolean collidable, boolean showInTab) {
		return spawnPlayerNpcPreferTextures(npcName, playerId, playerName, slim, location, displayNameMini, collidable, showInTab, -1);
	}

	/**
	 * Same as {@link #spawnPlayerNpcPreferTextures(String, UUID, String, boolean, Location, String, boolean, boolean)}
	 * but allows overriding the NPC visibility distance.
	 *
	 * visibilityDistanceBlocks: pass a positive number to override, or <= 0 to keep FancyNpcs defaults.
	 */
	public static String spawnPlayerNpcPreferTextures(String npcName, UUID playerId, String playerName, boolean slim, Location location,
			String displayNameMini, boolean collidable, boolean showInTab, int visibilityDistanceBlocks) {
		if (npcName == null || npcName.isBlank())
			return null;
		if (location == null || location.getWorld() == null)
			return null;
		if (!isNpcManagerReady())
			return null;

		var fancy = FancyNpcsPlugin.get();
		var npcManager = fancy.getNpcManager();

		NpcData data = new NpcData(npcName, UUID.randomUUID(), location);

		boolean applied = false;
		try {
			String[] tex = tryGetOnlinePlayerTextures(playerId);
			if (tex != null && tex.length >= 1) {
				String v = tex[0];
				String s = (tex.length >= 2 ? tex[1] : "");
				applied = tryApplyTextureSkin(data, v, s, slim, playerName);
			}
		} catch (Throwable ignored) {
			applied = false;
		}

		if (!applied) {
			// Fallback #1: UUID string (older FancyNpcs versions accepted this)
			try {
				if (playerId != null)
					data.setSkin(playerId.toString());
				applied = true;
			} catch (Throwable ignored) {
				applied = false;
			}
		}

		if (!applied) {
			// Fallback #2: username identifier
			try {
				String idf = (playerName == null ? "" : playerName.trim());
				if (!idf.isBlank()) {
					data.setSkin(idf);
					applied = true;
				}
			} catch (Throwable ignored) {
				applied = false;
			}
		}

		if (!applied) {
			// Last resort: keep default skin
		}

		tryApplySlimFlag(data, slim);

		try {
			if (displayNameMini != null)
				data.setDisplayName(displayNameMini);
		} catch (Throwable ignored) {
		}
		try {
			data.setCollidable(collidable);
		} catch (Throwable ignored) {
		}
		try {
			data.setShowInTab(showInTab);
		} catch (Throwable ignored) {
		}

		try {
			if (visibilityDistanceBlocks > 0)
				data.setVisibilityDistance(visibilityDistanceBlocks);
		} catch (Throwable ignored) {
		}

		Npc npc = fancy.getNpcAdapter().apply(data);
		if (npc == null)
			return null;
		try {
			npc.setSaveToFile(false);
		} catch (Throwable ignored) {
		}
		npcManager.registerNpc(npc);
		npc.create();
		npc.spawnForAll();
		return data.getId();
	}

	/**
	 * Spawns a temporary PLAYER NPC using FancyNpcs skin syntax.
	 *
	 * skin can be: @none | @mirror | name | url | file name
	 */
	public static String spawnPlayerNpc(String npcName, String skin, boolean slim, Location location,
			String displayNameMini, boolean collidable, boolean showInTab) {
		if (npcName == null || npcName.isBlank())
			return null;
		if (location == null || location.getWorld() == null)
			return null;
		if (!isNpcManagerReady())
			return null;

		var fancy = FancyNpcsPlugin.get();
		var npcManager = fancy.getNpcManager();
		if (npcManager == null || !npcManager.isLoaded())
			return null;

		NpcData data = new NpcData(npcName, UUID.randomUUID(), location);

		// Apply skin using official semantics where possible; fall back to NpcData#setSkin
		// for older API versions.
		boolean applied = false;
		try {
			applied = tryApplyOfficialSkinSemantics(data, skin, slim);
		} catch (Throwable ignored) {
			applied = false;
		}
		if (!applied) {
			try {
				if (skin != null && !skin.isBlank())
					data.setSkin(skin);
			} catch (Throwable ignored) {
			}
			tryApplySlimFlag(data, slim);
		}

		try {
			if (displayNameMini != null)
				data.setDisplayName(displayNameMini);
		} catch (Throwable ignored) {
		}

		try {
			data.setCollidable(collidable);
		} catch (Throwable ignored) {
		}

		try {
			data.setShowInTab(showInTab);
		} catch (Throwable ignored) {
		}

		Npc npc = fancy.getNpcAdapter().apply(data);
		if (npc == null)
			return null;

		try {
			npc.setSaveToFile(false);
		} catch (Throwable ignored) {
		}

		npcManager.registerNpc(npc);
		npc.create();
		npc.spawnForAll();

		return data.getId();
	}

	public static void removeNpcById(String npcId) {
		if (npcId == null || npcId.isBlank())
			return;

		var fancy = FancyNpcsPlugin.get();
		var npcManager = fancy.getNpcManager();
		if (npcManager == null || !npcManager.isLoaded())
			return;

		Npc npc = npcManager.getNpcById(npcId);
		if (npc == null)
			return;

		try {
			npc.removeForAll();
		} catch (Throwable ignored) {
		}
		try {
			npcManager.removeNpc(npc);
		} catch (Throwable ignored) {
		}
	}
}
