package dev.belikhun.boatracing.integrations.discord;

import dev.belikhun.boatracing.BoatRacingPlugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional module: relay in-game chat messages to a Discord webhook.
 *
 * This module is controlled by config.yml: discord.chat-webhook.*
 */
public final class DiscordWebhookChatRelayService {
	private final BoatRacingPlugin plugin;
	private final HttpClient http;
	private final AtomicReference<Config> config = new AtomicReference<>(Config.disabled());
	private Listener listener;
	private Listener ventureChatListener;
	private Listener hookListener;
	private final ConcurrentHashMap<UUID, LastMessage> lastMessageByPlayer = new ConcurrentHashMap<>();

	public DiscordWebhookChatRelayService(BoatRacingPlugin plugin) {
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public void start() {
		reloadFromConfig();
		Config cfg = config.get();
		if (!cfg.enabled) {
			try {
				plugin.getLogger().info("[Discord] Chat-webhook đang tắt (discord.chat-webhook.enabled=false)");
			} catch (Throwable ignored) {
			}
			return;
		}
		if (cfg.webhookUrl.isBlank()) {
			plugin.getLogger().warning("[Discord] chat-webhook.enabled=true nhưng url đang trống.");
			return;
		}

		try {
			boolean papi = false;
			boolean vc = false;
			try {
				papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
			} catch (Throwable ignored) {
				papi = false;
			}
			try {
				vc = Bukkit.getPluginManager().isPluginEnabled("VentureChat");
			} catch (Throwable ignored) {
				vc = false;
			}

			plugin.getLogger().info("[Discord] Đang bật relay chat -> Discord webhook.");
			plugin.getLogger().info("[Discord] Tích hợp: PlaceholderAPI=" + papi + " | VentureChat=" + vc);
			if (!vc) {
				plugin.getLogger().info("[Discord] VentureChat chưa bật; sẽ tự hook khi VentureChat enable.");
			}
		} catch (Throwable ignored) {
		}

		ensureHookListener();
		ensureListener();
		ensureVentureChatListener();
	}

	public void stop() {
		try {
			if (listener != null) {
				HandlerList.unregisterAll(listener);
			}
			if (ventureChatListener != null) {
				HandlerList.unregisterAll(ventureChatListener);
			}
			if (hookListener != null) {
				HandlerList.unregisterAll(hookListener);
			}
		} catch (Throwable ignored) {
		} finally {
			listener = null;
			ventureChatListener = null;
			hookListener = null;
		}
	}

	private void ensureHookListener() {
		if (hookListener != null)
			return;

		hookListener = new Listener() {
			@EventHandler
			public void onPluginEnable(PluginEnableEvent e) {
				try {
					if (e == null || e.getPlugin() == null)
						return;
					String name = e.getPlugin().getName();
					if (name == null)
						return;
					if (!"VentureChat".equalsIgnoreCase(name))
						return;
					Config cfg = config.get();
					if (!cfg.enabled)
						return;
					ensureVentureChatListener();
				} catch (Throwable ignored) {
				}
			}

			@EventHandler
			public void onPluginDisable(PluginDisableEvent e) {
				try {
					if (e == null || e.getPlugin() == null)
						return;
					String name = e.getPlugin().getName();
					if (name == null)
						return;
					if (!"VentureChat".equalsIgnoreCase(name))
						return;

					try {
						if (ventureChatListener != null) {
							HandlerList.unregisterAll(ventureChatListener);
							ventureChatListener = null;
							plugin.getLogger().info("[Discord] Đã gỡ hook VentureChat (plugin disabled).");
						}
					} catch (Throwable ignored2) {
						ventureChatListener = null;
					}
				} catch (Throwable ignored) {
				}
			}
		};

		try {
			Bukkit.getPluginManager().registerEvents(hookListener, plugin);
		} catch (Throwable t) {
			hookListener = null;
			plugin.getLogger().warning("[Discord] Không thể đăng ký hook listener (PluginEnableEvent): " + t.getMessage());
		}
	}

	public void restart() {
		stop();
		start();
	}

	public void reloadFromConfig() {
		boolean enabled = false;
		String url = "";
		String username = "%player_name%";
		String avatarUrl = "";
		String message = "[%world%] %player_name%: %message%";
		int timeoutMs = 5000;
		boolean trim = true;
		boolean debug = false;
		try {
			enabled = plugin.getConfig().getBoolean("discord.chat-webhook.enabled", false);
			url = plugin.getConfig().getString("discord.chat-webhook.url", "");
			username = plugin.getConfig().getString("discord.chat-webhook.username", username);
			avatarUrl = plugin.getConfig().getString("discord.chat-webhook.avatar-url", avatarUrl);
			message = plugin.getConfig().getString("discord.chat-webhook.message", message);
			timeoutMs = plugin.getConfig().getInt("discord.chat-webhook.timeout-ms", timeoutMs);
			trim = plugin.getConfig().getBoolean("discord.chat-webhook.trim-to-2000", trim);
			debug = plugin.getConfig().getBoolean("discord.chat-webhook.debug", debug);
		} catch (Throwable ignored) {
		}

		if (url == null)
			url = "";
		if (username == null)
			username = "%player_name%";
		if (avatarUrl == null)
			avatarUrl = "";
		if (message == null)
			message = "[%world%] %player_name%: %message%";
		if (timeoutMs < 500)
			timeoutMs = 500;
		if (timeoutMs > 60000)
			timeoutMs = 60000;

		config.set(new Config(enabled, url.trim(), username, avatarUrl, message, timeoutMs, trim, debug));
	}

	private void ensureListener() {
		if (listener != null)
			return;

		listener = new Listener() {
			@EventHandler(ignoreCancelled = true)
			public void onChat(AsyncChatEvent e) {
				Config cfg = config.get();
				if (!cfg.enabled)
					return;
				if (cfg.webhookUrl.isBlank())
					return;

				Player p = e.getPlayer();
				if (p == null)
					return;

				String plain = toPlainText(e.message());
				enqueueSend(p.getUniqueId(), plain);
			}
		};

		try {
			Bukkit.getPluginManager().registerEvents(listener, plugin);
			try {
				plugin.getLogger().info("[Discord] Đã đăng ký hook chat (Paper AsyncChatEvent).");
			} catch (Throwable ignored) {
			}
		} catch (Throwable t) {
			listener = null;
			plugin.getLogger().warning("[Discord] Không thể đăng ký chat listener: " + t.getMessage());
		}
	}

	private void ensureVentureChatListener() {
		if (ventureChatListener != null)
			return;
		try {
			if (!Bukkit.getPluginManager().isPluginEnabled("VentureChat"))
				return;
		} catch (Throwable ignored) {
			return;
		}

		final Class<? extends Event> eventClass;
		try {
			Class<?> c = Class.forName("mineverse.Aust1n46.chat.api.events.VentureChatEvent");
			if (!Event.class.isAssignableFrom(c))
				return;
			@SuppressWarnings("unchecked")
			Class<? extends Event> cc = (Class<? extends Event>) c;
			eventClass = cc;
		} catch (Throwable ignored) {
			try {
				plugin.getLogger().warning("[Discord] VentureChat đang bật nhưng không tìm thấy class VentureChatEvent.");
			} catch (Throwable ignored2) {
			}
			return;
		}

		ventureChatListener = new Listener() {
			// no @EventHandler (registered dynamically)
		};

		EventExecutor exec = (l, e) -> {
			try {
				if (e == null)
					return;
				Config cfg = config.get();
				if (!cfg.enabled)
					return;
				if (cfg.webhookUrl.isBlank())
					return;

				Player p = resolveVentureChatPlayer(e);
				if (p == null)
					return;

				String raw = resolveVentureChatMessage(e);
				if (raw == null)
					raw = "";
				enqueueSend(p.getUniqueId(), raw);
			} catch (Throwable ignored) {
			}
		};

		try {
			Bukkit.getPluginManager().registerEvent(eventClass, ventureChatListener,
					org.bukkit.event.EventPriority.MONITOR, exec, plugin, true);
			plugin.getLogger().info("[Discord] Đã bật hook VentureChat để relay chat.");
		} catch (Throwable t) {
			ventureChatListener = null;
			plugin.getLogger().warning("[Discord] Không thể đăng ký hook VentureChat: " + t.getMessage());
		}
	}

	private void enqueueSend(UUID playerId, String message) {
		if (playerId == null)
			return;
		if (message == null)
			message = "";

		String plain = DiscordWebhookSanitizer.stripAllFormatting(message);
		if (plain.isBlank())
			return;

		long now = System.currentTimeMillis();
		LastMessage prev = lastMessageByPlayer.get(playerId);
		if (prev != null && prev.message != null && prev.message.equals(plain) && (now - prev.atMs) <= 750L) {
			try {
				if (config.get().debug)
					plugin.getLogger().info("[Discord][DBG] Bỏ qua do trùng lặp (dedupe) player=" + playerId);
			} catch (Throwable ignored) {
			}
			return;
		}
		lastMessageByPlayer.put(playerId, new LastMessage(plain, now));
		try {
			if (config.get().debug)
				plugin.getLogger().info("[Discord][DBG] Enqueue chat player=" + playerId + " msg=\"" + plain + "\"");
		} catch (Throwable ignored) {
		}

		String msgFinal = plain;
		Bukkit.getScheduler().runTask(plugin, () -> {
			try {
				Config cfg = config.get();
				if (!cfg.enabled)
					return;
				if (cfg.webhookUrl.isBlank())
					return;
				Player p = Bukkit.getPlayer(playerId);
				if (p == null || !p.isOnline())
					return;

				String renderedContent = renderTemplate(p, msgFinal, cfg.messageTemplate);
				String renderedUsername = renderTemplate(p, msgFinal, cfg.usernameTemplate);
				String renderedAvatarUrl = renderTemplate(p, msgFinal, cfg.avatarUrlTemplate);

				renderedContent = DiscordWebhookSanitizer.stripAllFormatting(renderedContent);
				renderedUsername = DiscordWebhookSanitizer.stripAllFormatting(renderedUsername);
				renderedAvatarUrl = DiscordWebhookSanitizer.stripAllFormatting(renderedAvatarUrl);

				if (cfg.trimTo2000 && renderedContent.length() > 2000) {
					renderedContent = renderedContent.substring(0, 1997) + "...";
				}
				if (renderedContent.isBlank())
					return;

				if (cfg.debug) {
					try {
						plugin.getLogger().info("[Discord][DBG] Send webhook content=\"" + renderedContent + "\"");
					} catch (Throwable ignored) {
					}
				}
				sendWebhook(cfg, renderedContent, renderedUsername, renderedAvatarUrl);
			} catch (Throwable ignored) {
			}
		});
	}

	private Player resolveVentureChatPlayer(Object ventureChatEvent) {
		if (ventureChatEvent == null)
			return null;
		// Prefer MineverseChatPlayer -> getPlayer()
		try {
			Object mcp = ventureChatEvent.getClass().getMethod("getMineverseChatPlayer").invoke(ventureChatEvent);
			if (mcp != null) {
				Object bp = mcp.getClass().getMethod("getPlayer").invoke(mcp);
				if (bp instanceof Player p)
					return p;
			}
		} catch (Throwable ignored) {
		}

		// Fallback: direct getPlayer()
		try {
			Object bp = ventureChatEvent.getClass().getMethod("getPlayer").invoke(ventureChatEvent);
			if (bp instanceof Player p)
				return p;
		} catch (Throwable ignored) {
		}

		// Last resort: username -> Bukkit player
		try {
			Object u = ventureChatEvent.getClass().getMethod("getUsername").invoke(ventureChatEvent);
			if (u instanceof String name && !name.isBlank()) {
				return Bukkit.getPlayerExact(name);
			}
		} catch (Throwable ignored) {
		}

		return null;
	}

	private String resolveVentureChatMessage(Object ventureChatEvent) {
		if (ventureChatEvent == null)
			return "";
		try {
			Object chat = ventureChatEvent.getClass().getMethod("getChat").invoke(ventureChatEvent);
			if (chat instanceof String s)
				return s;
		} catch (Throwable ignored) {
		}
		try {
			Object msg = ventureChatEvent.getClass().getMethod("getMessage").invoke(ventureChatEvent);
			if (msg instanceof String s)
				return s;
		} catch (Throwable ignored) {
		}
		return "";
	}

	private static String toPlainText(Component c) {
		try {
			return PlainTextComponentSerializer.plainText().serialize(c);
		} catch (Throwable ignored) {
			return "";
		}
	}

	private String renderTemplate(Player p, String plainMessage, String template) {
		if (template == null)
			return "";

		String s = template;
		try {
			s = s.replace("%player_name%", safe(p.getName()))
					.replace("%player_uuid%", safe(p.getUniqueId().toString()))
					.replace("%world%", safe(p.getWorld() != null ? p.getWorld().getName() : ""))
					.replace("%message%", safe(plainMessage));
		} catch (Throwable ignored) {
		}

		// PlaceholderAPI if present
		try {
			boolean enabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
			if (enabled) {
				s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
			}
		} catch (Throwable ignored) {
		}

		return s;
	}

	private void sendWebhook(Config cfg, String content, String username, String avatarUrl) {
		String body = DiscordWebhookPayload.json(content, username, avatarUrl);

		HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(cfg.webhookUrl))
					.timeout(Duration.ofMillis(cfg.timeoutMs))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
		} catch (Throwable t) {
			plugin.getLogger().warning("[Discord] URL webhook không hợp lệ: " + t.getMessage());
			return;
		}

		http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
				.whenComplete((resp, err) -> {
					if (err != null) {
						try {
							plugin.getLogger().warning("[Discord] Gửi webhook thất bại: " + err.getMessage());
						} catch (Throwable ignored) {
						}
						return;
					}

					int code;
					try {
						code = resp.statusCode();
					} catch (Throwable ignored) {
						code = 0;
					}
					if (cfg.debug) {
						try {
							plugin.getLogger().info("[Discord][DBG] Webhook HTTP " + code);
						} catch (Throwable ignored) {
						}
					}
					if (code >= 400) {
						try {
							plugin.getLogger().warning("[Discord] Webhook trả về HTTP " + code);
						} catch (Throwable ignored) {
						}
					}
				});
	}

	private static String safe(String s) {
		return s == null ? "" : s;
	}

	private record LastMessage(String message, long atMs) {}

	private record Config(
			boolean enabled,
			String webhookUrl,
			String usernameTemplate,
			String avatarUrlTemplate,
			String messageTemplate,
			int timeoutMs,
			boolean trimTo2000,
			boolean debug
	) {
		static Config disabled() {
			return new Config(false, "", "%player_name%", "", "[%world%] %player_name%: %message%", 5000, true, false);
		}
	}
}
