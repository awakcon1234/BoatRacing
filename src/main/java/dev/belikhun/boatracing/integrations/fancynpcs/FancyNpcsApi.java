package dev.belikhun.boatracing.integrations.fancynpcs;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import org.bukkit.Location;

import java.util.UUID;

public final class FancyNpcsApi {
	private FancyNpcsApi() {
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

		var fancy = FancyNpcsPlugin.get();
		var npcManager = fancy.getNpcManager();
		if (npcManager == null || !npcManager.isLoaded())
			return null;

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
