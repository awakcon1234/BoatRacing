package es.jaie55.boatracing.team;

import org.bukkit.DyeColor;
import org.bukkit.Material;

import java.util.*;

public class Team {
    private final UUID id;
    private String name;
    private DyeColor color;
    private final Set<UUID> members = new HashSet<>();
    // Per-member preferences
    private final Map<UUID, Integer> racerNumbers = new HashMap<>();
    private final Map<UUID, String> boatTypes = new HashMap<>(); // Boat.Type name

    public Team(UUID id, String name, DyeColor color, UUID firstMember) {
        this.id = id;
        this.name = name;
        this.color = color;
        if (firstMember != null) {
            this.members.add(firstMember);
        }
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public DyeColor getColor() { return color; }
    public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }

    public void setName(String name) { this.name = name; }
    public void setColor(DyeColor color) { this.color = color; }

    public boolean addMember(UUID uuid) {
        int max = es.jaie55.boatracing.BoatRacingPlugin.getInstance().getTeamManager().getMaxMembers();
        if (members.size() >= max) return false;
        return members.add(uuid);
    }

    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    public boolean isMember(UUID uuid) { return members.contains(uuid); }

    // --- Member preferences ---
    public int getRacerNumber(UUID uuid) {
        return racerNumbers.getOrDefault(uuid, 0);
    }
    public void setRacerNumber(UUID uuid, int racerNumber) {
        racerNumbers.put(uuid, racerNumber);
    }
    public String getBoatType(UUID uuid) { return boatTypes.getOrDefault(uuid, Material.OAK_BOAT.name()); }
    public void setBoatType(UUID uuid, String boatType) {
        boatTypes.put(uuid, boatType);
    }
    public Map<UUID, Integer> getAllRacerNumbers() { return Collections.unmodifiableMap(racerNumbers); }
    public Map<UUID, String> getAllBoatTypes() { return Collections.unmodifiableMap(boatTypes); }
}
