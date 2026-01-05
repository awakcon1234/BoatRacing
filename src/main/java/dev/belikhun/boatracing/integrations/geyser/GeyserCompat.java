package dev.belikhun.boatracing.integrations.geyser;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Detect Bedrock players via Geyser/Floodgate APIs. Does not require either plugin
 * to be installed; absence is treated as "not Bedrock".
 */
public final class GeyserCompat {
	private static final Logger LOG = Logger.getLogger("BoatRacing-GeyserCompat");
	private static volatile boolean loggedGeyserMissing;
	private static volatile boolean loggedFloodgateMissing;

	private GeyserCompat() {
	}

	public static boolean isBedrockPlayer(UUID uuid) {
		if (uuid == null)
			return false;

		// First try Geyser API
		try {
			if (GeyserApi.api().isBedrockPlayer(uuid)) {
				LOG.info("GeyserCompat: detected Bedrock player via Geyser API: " + uuid);
				return true;
			}
		} catch (NoClassDefFoundError | Exception ignored) {
			if (!loggedGeyserMissing) {
				loggedGeyserMissing = true;
				LOG.info("GeyserCompat: Geyser API not available; skipping Bedrock detection via Geyser.");
			}
		}

		// Fallback: Floodgate (if installed)
		try {
			FloodgateApi floodgate = FloodgateApi.getInstance();
			if (floodgate != null && (floodgate.isFloodgatePlayer(uuid) || floodgate.isFloodgateId(uuid))) {
				LOG.info("GeyserCompat: detected Bedrock player via Floodgate API: " + uuid);
				return true;
			}
		} catch (NoClassDefFoundError | Exception ignored) {
			if (!loggedFloodgateMissing) {
				loggedFloodgateMissing = true;
				LOG.info("GeyserCompat: Floodgate API not available; skipping Bedrock detection via Floodgate.");
			}
		}

		return false;
	}
}
