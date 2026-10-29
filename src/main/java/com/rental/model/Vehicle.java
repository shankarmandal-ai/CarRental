package com.rental.model;

import com.rental.datastructure.IntervalTree;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One physical car and its own reservation history. The IntervalTree is owned here
 * rather than in a separate per-vehicle map in the reservation repository, since a
 * vehicle's bookings are intrinsically its own state, not a cross-cutting index.
 *
 * Not thread-safe by itself: callers serialize access via RentalService's per-type
 * lock, the same way the tree itself requires external synchronization.
 */
public final class Vehicle {
    private final String id;
    private final VehicleType type;
    private final IntervalTree<Reservation> reservations = new IntervalTree<>();

    public Vehicle(String id, VehicleType type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String getId() {
        return id;
    }

    public VehicleType getType() {
        return type;
    }

    public void addReservation(Reservation reservation) {
        reservations.insert(reservation.getStart(), reservation.getEnd(), reservation.getId(), reservation);
    }

    public void removeReservation(Reservation reservation) {
        reservations.remove(reservation.getStart(), reservation.getId());
    }

    public boolean hasOverlap(LocalDateTime start, LocalDateTime end) {
        return reservations.hasOverlap(start, end);
    }

    public List<Reservation> findOverlapping(LocalDateTime start, LocalDateTime end) {
        return reservations.findOverlapping(start, end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vehicle)) return false;
        return id.equals(((Vehicle) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Vehicle{id='" + id + "', type=" + type + "}";
    }
}
