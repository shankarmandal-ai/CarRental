package com.rental.repository;

import com.rental.datastructure.IntervalTree;
import com.rental.exception.ReservationNotFoundException;
import com.rental.model.Reservation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlap queries are backed by one IntervalTree per vehicle (see
 * com.rental.datastructure.IntervalTree), giving O(log r) overlap checks against that
 * vehicle's own reservations instead of a linear scan of the entire reservation table.
 * Cancelling a reservation removes it from its vehicle's tree so cancelled bookings never
 * contribute to future overlap checks.
 */
public class InMemoryReservationRepository implements ReservationRepository {
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();
    private final Map<String, IntervalTree<Reservation>> treesByVehicleId = new ConcurrentHashMap<>();

    @Override
    public void save(Reservation reservation) {
        reservationsById.put(reservation.getId(), reservation);
        treeFor(reservation.getVehicleId())
                .insert(reservation.getStart(), reservation.getEnd(), reservation.getId(), reservation);
    }

    @Override
    public void cancel(String reservationId) {
        Reservation reservation = reservationsById.get(reservationId);
        if (reservation == null) {
            throw new ReservationNotFoundException("No reservation with id " + reservationId);
        }
        reservation.cancel();
        IntervalTree<Reservation> tree = treesByVehicleId.get(reservation.getVehicleId());
        if (tree != null) {
            tree.remove(reservation.getStart(), reservation.getId());
        }
    }

    @Override
    public Optional<Reservation> findById(String id) {
        return Optional.ofNullable(reservationsById.get(id));
    }

    @Override
    public List<Reservation> findByVehicleId(String vehicleId) {
        List<Reservation> result = new ArrayList<>();
        for (Reservation r : reservationsById.values()) {
            if (r.getVehicleId().equals(vehicleId)) {
                result.add(r);
            }
        }
        return result;
    }

    @Override
    public boolean hasOverlap(String vehicleId, LocalDateTime start, LocalDateTime end) {
        IntervalTree<Reservation> tree = treesByVehicleId.get(vehicleId);
        return tree != null && tree.hasOverlap(start, end);
    }

    @Override
    public List<Reservation> findAll() {
        return new ArrayList<>(reservationsById.values());
    }

    private IntervalTree<Reservation> treeFor(String vehicleId) {
        return treesByVehicleId.computeIfAbsent(vehicleId, id -> new IntervalTree<>());
    }
}
