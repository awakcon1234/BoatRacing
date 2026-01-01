package dev.belikhun.boatracing.integrations.discord;

import java.util.regex.Pattern;

final class DiscordWebhookSanitizer {
	private DiscordWebhookSanitizer() {}

	// MiniMessage tags: <red>, </red>, <gradient:#fff:#000>, <click:run_command:/x>, ...
	// Intentionally does NOT match plain text like "<3".
	private static final Pattern MINIMESSAGE_TAG = Pattern.compile("<[/!]?([a-zA-Z0-9:_-]+)(:[^>]*)?>");

	// Legacy Minecraft color codes (&a, §a, etc.) and hex formats (&x&F&F&0&0&F&F)
	private static final Pattern AMP_HEX = Pattern.compile("(?i)&x(&[0-9a-f]){6}");
	private static final Pattern SEC_HEX = Pattern.compile("(?i)§x(§[0-9a-f]){6}");
	private static final Pattern AMP_CODE = Pattern.compile("(?i)&[0-9a-fk-orx]");
	private static final Pattern SEC_CODE = Pattern.compile("(?i)§[0-9a-fk-orx]");

	static String stripAllFormatting(String s) {
		if (s == null || s.isEmpty())
			return "";

		String out = s;
		try {
			out = MINIMESSAGE_TAG.matcher(out).replaceAll("");
		} catch (Throwable ignored) {
		}

		try {
			out = AMP_HEX.matcher(out).replaceAll("");
			out = SEC_HEX.matcher(out).replaceAll("");
			out = AMP_CODE.matcher(out).replaceAll("");
			out = SEC_CODE.matcher(out).replaceAll("");
		} catch (Throwable ignored) {
		}

		return out;
	}
}
