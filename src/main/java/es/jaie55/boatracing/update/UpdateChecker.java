package es.jaie55.boatracing.update;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {
    private final Plugin plugin;
    private final String releasesApi;
    private final String releasesPage;
    private final HttpClient http;
    private final String currentVersion;

    private volatile String latestVersion = null;
    private volatile String latestUrl = null;
    private volatile int behindCount = 0;
    private volatile boolean checked = false;
    private volatile boolean error = false;

    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("\"tag_name\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern HTML_URL_PATTERN = Pattern.compile("\"html_url\"\s*:\s*\"([^\"]+)\"");

    public UpdateChecker(Plugin plugin, String repoOwner, String repoName, String currentVersion) {
        this.plugin = plugin;
        this.releasesApi = "https://api.github.com/repos/" + repoOwner + "/" + repoName + "/releases?per_page=100";
        this.releasesPage = "https://github.com/" + repoOwner + "/" + repoName + "/releases";
        this.currentVersion = currentVersion;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void checkAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                checkNow();
            } catch (Exception ex) {
                error = true;
                plugin.getLogger().warning("Update check failed: " + ex.getMessage());
            }
        });
    }

    private void checkNow() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(releasesApi))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", plugin.getName() + " UpdateChecker")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("GitHub API HTTP " + res.statusCode());
        }
        String body = res.body();

        // Extract all tag_name values (ordered newest->oldest as GitHub returns by default)
        List<String> tags = new ArrayList<>();
        Matcher m = TAG_NAME_PATTERN.matcher(body);
        while (m.find()) {
            String tag = normalizeVersion(m.group(1));
            if (tag != null) tags.add(tag);
        }
        // Extract first html_url as latest URL, fallback to releases page
        Matcher urlM = HTML_URL_PATTERN.matcher(body);
        if (urlM.find()) {
            latestUrl = urlM.group(1);
        } else {
            latestUrl = releasesPage;
        }

        if (tags.isEmpty()) {
            // Fallback to repo releases page without version
            latestVersion = null;
            checked = true;
            return;
        }

        latestVersion = tags.get(0);
    String current = normalizeVersion(currentVersion);
        behindCount = 0;
        if (current != null) {
            for (String tag : tags) {
                if (isNewer(tag, current)) behindCount++;
                else break; // list is newest -> oldest; stop when we reach current or older
            }
            // If we counted the latest as newer but current equals latest, fix count
            if (latestVersion.equals(current)) behindCount = 0;
        }
        checked = true;
    }

    private String normalizeVersion(String v) {
        if (v == null || v.isEmpty()) return null;
        // strip starting 'v' if present
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        // only semver-like x.y.z
        if (!v.matches("\\d+\\.\\d+\\.\\d+")) return v; // allow non-strict tags as-is
        return v;
    }

    private boolean isNewer(String a, String b) {
        // returns true if a > b in semver; fallback to string compare
        if (a == null || b == null) return false;
        if (a.matches("\\d+\\.\\d+\\.\\d+") && b.matches("\\d+\\.\\d+\\.\\d+")) {
            String[] as = a.split("\\.");
            String[] bs = b.split("\\.");
            for (int i = 0; i < 3; i++) {
                int ai = Integer.parseInt(as[i]);
                int bi = Integer.parseInt(bs[i]);
                if (ai != bi) return ai > bi;
            }
            return false;
        }
        return a.compareTo(b) > 0;
    }

    public boolean isChecked() { return checked; }
    public boolean hasError() { return error; }
    public boolean isOutdated() { return checked && latestVersion != null && behindCount > 0; }
    public String getLatestVersion() { return latestVersion; }
    public String getLatestUrl() { return latestUrl != null ? latestUrl : releasesPage; }
    public int getBehindCount() { return behindCount; }
}
