package dev.belikhun.boatracing.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RaceEvent {
	public String id;
	public String title;
	public String description;
	public long startTimeMillis;
	/**
	 * Cached maximum allowed participants for this event, derived from the track pool.
	 *
	 * Policy: use the minimum start-slot count across all tracks in {@link #trackPool}.
	 * 0 means unknown/uncomputed.
	 */
	public int maxParticipants;
	public List<String> trackPool;
	public EventState state;
	public int currentTrackIndex;
	public Map<UUID, EventParticipant> participants;
	// First-come-first-serve ordering for seating when a track has limited start slots.
	public List<UUID> registrationOrder;

	public RaceEvent() {
		this.id = "";
		this.title = "";
		this.description = "";
		this.startTimeMillis = 0L;
		this.maxParticipants = 0;
		this.trackPool = new ArrayList<>();
		this.state = EventState.DRAFT;
		this.currentTrackIndex = 0;
		this.participants = new HashMap<>();
		this.registrationOrder = new ArrayList<>();
	}

	public String currentTrackName() {
		if (trackPool == null || trackPool.isEmpty())
			return null;
		int idx = Math.max(0, currentTrackIndex);
		if (idx >= trackPool.size())
			idx = trackPool.size() - 1;
		String t = trackPool.get(idx);
		return (t == null || t.isBlank()) ? null : t.trim();
	}

	public boolean isRunning() {
		return state == EventState.RUNNING;
	}
}
