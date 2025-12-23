package es.jaie55.boatracing.race;

import es.jaie55.boatracing.track.TrackConfig;
import es.jaie55.boatracing.track.Region;
import es.jaie55.boatracing.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Race manager with minimal yet functional placement and countdown.
 */
public class RaceManager {
    private Plugin plugin;
    @SuppressWarnings("unused")
    private final TrackConfig trackConfig;
    private boolean running = false;
    private boolean registering = false;
    private final Set<UUID> registered = new HashSet<>();
    private int totalLaps = 3;
    // Pit mechanic removed: no mandatory pitstops

    // runtime participant state
    private final java.util.Map<UUID, ParticipantState> participants = new java.util.HashMap<>();
    private final java.util.Map<UUID, Player> participantPlayers = new java.util.HashMap<>();

    public RaceManager(Plugin plugin, TrackConfig trackConfig) {
        this.plugin = plugin;
        this.trackConfig = trackConfig;
    }

    // test-only constructor that avoids needing a Plugin instance in unit tests
    public RaceManager(TrackConfig trackConfig) {
        this.plugin = null;
        this.trackConfig = trackConfig;
    }

    public boolean isRunning() { return running; }
    public boolean isRegistering() { return registering; }
    public Set<UUID> getRegistered() { return Collections.unmodifiableSet(registered); }

    /**
     * Called on player movement to detect checkpoint/pit/finish crossings
     */
    public void tickPlayer(Player player, Location to) {
        if (!running) return;
        ParticipantState s = participants.get(player.getUniqueId());
        if (s == null || s.finished) return;

        // Pit mechanic removed

        // Checkpoints
        java.util.List<Region> checkpoints = trackConfig.getCheckpoints();
        for (int i = 0; i < checkpoints.size(); i++) {
            Region r = checkpoints.get(i);
            if (r != null && r.contains(to)) {
                checkpointReached(player.getUniqueId(), i);
                Text.msg(player, "&eĐã qua checkpoint " + (i+1) + ".");
            }
        }

        // Finish detection (also counts as lap completion if appropriate)
        Region finish = trackConfig.getFinish();
        if (finish != null && finish.contains(to)) {
            // If track has checkpoints, finishing is managed by checkpoint flow; otherwise treat finish as lap completion
            if (checkpoints.isEmpty()) {
                // treat finish like completing a lap
                handleLapCompletion(player.getUniqueId());
            } else {
                // if they have just completed all checkpoints, handle lap completion
                ParticipantState ps = participants.get(player.getUniqueId());
                if (ps != null && ps.nextCheckpointIndex == 0) {
                    handleLapCompletion(player.getUniqueId());
                }
            }
        }
    }

    // test hook: allow tests to simulate checkpoints without needing Region instances
    private int testCheckpointCount = -1;
    void setTestCheckpointCount(int n) { this.testCheckpointCount = n; }

    // package-private helpers for testing and fine-grained control
    void checkpointReached(UUID uuid, int checkpointIndex) {
        ParticipantState s = participants.get(uuid);
        if (s == null || s.finished) return;
        if (checkpointIndex != s.nextCheckpointIndex) return; // enforce sequence
        s.nextCheckpointIndex++;
        int totalCheckpoints = testCheckpointCount >= 0 ? testCheckpointCount : trackConfig.getCheckpoints().size();
        if (s.nextCheckpointIndex >= totalCheckpoints) {
            // wrapped around
            s.nextCheckpointIndex = 0;
            handleLapCompletion(uuid);
        }
    }

    ParticipantState getParticipantState(UUID uuid) { return participants.get(uuid); }

    // test helper: add a participant without needing a Player or a running race
    void addParticipantForTests(UUID uuid) {
        participants.put(uuid, new ParticipantState(uuid));
    }

    void handleLapCompletion(UUID uuid) {
        ParticipantState s = participants.get(uuid);
        if (s == null || s.finished) return;
        s.currentLap++;
        // Pit mechanic removed: no penalties or pit flags

        // Finished?
        if (s.currentLap >= getTotalLaps()) {
            finishPlayer(uuid);
        } else {
            Player p = participantPlayers.get(uuid);
            if (p != null) Text.msg(p, "&aVòng " + s.currentLap + " / " + getTotalLaps());
        }
    }

    void finishPlayer(UUID uuid) {
        ParticipantState s = participants.get(uuid);
        if (s == null || s.finished) return;
        s.finished = true;
        s.finishTimeMillis = System.currentTimeMillis();
        // compute position as number of already finished + 1
        int pos = (int) participants.values().stream().filter(x -> x.finished && x.finishTimeMillis > 0).count();
        s.finishPosition = pos + 1;
        Player p = participantPlayers.get(uuid);
        if (p != null) {
            Text.msg(p, "&6Bạn đã về đích! Vị trí: &e" + s.finishPosition);
        }
    }

    /**
     * Get standings ordered by finish time + penalty (unfinished players last)
     */
    public List<ParticipantState> getStandings() {
        List<ParticipantState> out = new ArrayList<>(participants.values());
        out.sort((a,b) -> {
            if (a.finished && b.finished) {
                long ta = a.finishTimeMillis + a.penaltySeconds*1000L;
                long tb = b.finishTimeMillis + b.penaltySeconds*1000L;
                return Long.compare(ta, tb);
            } else if (a.finished) return -1;
            else if (b.finished) return 1;
            else return Long.compare(b.currentLap, a.currentLap); // more laps ahead first
        });
        return out;
    }

    // Simple state holder
    public static class ParticipantState {
        public final UUID id;
        public int currentLap = 0;
        public int nextCheckpointIndex = 0;
        // pit flags removed
        public boolean finished = false;
        public long finishTimeMillis = 0;
        public int finishPosition = 0;
        public int penaltySeconds = 0;

        public ParticipantState(UUID id) { this.id = id; }
    }

    public int getTotalLaps() {
        return Math.max(1, totalLaps);
    }

    public boolean openRegistration(int laps, Object unused) {
        this.registering = true;
        this.totalLaps = laps;
        return true;
    }

    public boolean join(Player p) {
        if (!registering) return false;
        return registered.add(p.getUniqueId());
    }

    public boolean leave(Player p) {
        return registered.remove(p.getUniqueId());
    }

    public void forceStart() {
        if (!registered.isEmpty()) {
            this.running = true;
            this.registering = false;
        }
    }

    // Place participants at start locations and spawn boats for them
    public List<Player> placeAtStartsWithBoats(List<Player> participants) {
        List<Location> starts = trackConfig.getStarts();
        if (starts.isEmpty()) return Collections.emptyList();
        List<Player> placed = new ArrayList<>();
        int slot = 0;
        for (Player p : participants) {
            if (slot >= starts.size()) break; // no more slots
            Location target = starts.get(slot).clone();
            try {
                // teleport player slightly above block
                target.setY(target.getY() + 1.0);
                p.teleport(target);
                // spawn a boat and set player as passenger
                var ent = p.getWorld().spawnEntity(target, EntityType.BOAT);
                if (ent instanceof Boat) {
                    Boat b = (Boat) ent;
                    b.addPassenger(p);
                }
                placed.add(p);
            } catch (Throwable ignored) {}
            slot++;
        }
        return placed;
    }

    // Simple countdown using server scheduler
    public void startRaceWithCountdown(List<Player> placed) {
        if (placed.isEmpty()) return;
        this.registering = false;
        final int[] counter = {5};
        final var task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (counter[0] <= 0) {
                // Start
                running = true;
                // initialize participant state from placed players
                participants.clear();
                participantPlayers.clear();
                for (Player p : placed) {
                    ParticipantState st = new ParticipantState(p.getUniqueId());
                    participants.put(p.getUniqueId(), st);
                    participantPlayers.put(p.getUniqueId(), p);
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (Player p : placed) {
                        p.sendTitle("", "§aGo!", 5, 20, 5);
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                    }
                });
                // cancel
                throw new RuntimeException("__cancel__");
            } else {
                final int cur = counter[0];
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (Player p : placed) {
                        p.sendTitle("", "§e" + cur, 5, 20, 5);
                        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
                    }
                });
            }
            counter[0]--;
        }, 0L, 20L);
        // Wrap cancellation
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try { task.cancel(); } catch (Throwable ignored) {}
        }, 20L * 6L);
    }

    public boolean cancelRegistration(boolean announce) {
        boolean had = registering || !registered.isEmpty();
        registering = false;
        registered.clear();
        return had;
    }

    public boolean cancelRace() {
        boolean was = running;
        running = false;
        return was;
    }

    public java.util.Set<UUID> getParticipants() { return java.util.Collections.unmodifiableSet(registered); }

    public void setTotalLaps(int laps) { this.totalLaps = Math.max(1, laps); }

    // Pit mechanic removed: no mandatory pitstops API
}
