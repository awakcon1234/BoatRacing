package dev.belikhun.boatracing.integrations.discord;

final class DiscordWebhookPayload {
	private DiscordWebhookPayload() {}

	static String json(String content, String username, String avatarUrl) {
		StringBuilder sb = new StringBuilder(256);
		sb.append('{');

		boolean hasPrev = false;
		if (username != null && !username.isBlank()) {
			sb.append("\"username\":\"").append(escape(username)).append('"');
			hasPrev = true;
		}
		if (avatarUrl != null && !avatarUrl.isBlank()) {
			if (hasPrev)
				sb.append(',');
			sb.append("\"avatar_url\":\"").append(escape(avatarUrl)).append('"');
			hasPrev = true;
		}

		if (hasPrev)
			sb.append(',');
		sb.append("\"content\":\"").append(escape(content == null ? "" : content)).append('"');

		sb.append('}');
		return sb.toString();
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}
}
