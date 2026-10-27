package com.rental.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a booking of one vehicle for the half-open interval [start, end).
 * Half-open semantics let one reservation end at the exact instant the next begins
 * without the two being treated as overlapping.
 */
public final class Reservation {
    private final String id;
    private final String vehicleId;
    private final VehicleType vehicleType;
    private final String customerId;
    private final LocalDateTime start;
    private final LocalDateTime end;
    private volatile ReservationStatus status;

    public Reservation(String vehicleId, VehicleType vehicleType, String customerId,
                        LocalDateTime start, LocalDateTime end) {
        this.id = UUID.randomUUID().toString();
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        this.status = ReservationStatus.CONFIRMED;
    }

    public String getId() {
        return id;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public String getCustomerId() {
        return customerId;
    }

    public LocalDateTime getStart() {
        return start;
    }

    public LocalDateTime getEnd() {
        return end;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public boolean isActive() {
        return status == ReservationStatus.CONFIRMED;
    }

    /** True if this reservation's interval overlaps the given [otherStart, otherEnd) interval. */
    public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return this.start.isBefore(otherEnd) && otherStart.isBefore(this.end);
    }

    @Override
    public String toString() {
        return "Reservation{id='" + id + "', vehicleId='" + vehicleId + "', vehicleType=" + vehicleType
                + ", customerId='" + customerId + "', start=" + start + ", end=" + end + ", status=" + status + "}";
    }
}
