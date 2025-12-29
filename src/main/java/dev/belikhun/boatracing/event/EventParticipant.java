package dev.belikhun.boatracing.event;

import java.util.UUID;

public class EventParticipant {
	public final UUID id;
	public String nameSnapshot;
	public int pointsTotal;
	public EventParticipantStatus status;
	public long lastSeenMillis;

	public EventParticipant(UUID id) {
		this.id = id;
		this.nameSnapshot = "";
		this.pointsTotal = 0;
		this.status = EventParticipantStatus.REGISTERED;
		this.lastSeenMillis = System.currentTimeMillis();
	}
}
