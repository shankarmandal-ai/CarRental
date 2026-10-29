package com.rental.repository;

import com.rental.exception.ReservationNotFoundException;
import com.rental.model.Reservation;
import com.rental.model.Vehicle;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlap queries are delegated to each Reservation's Vehicle, which owns its own
 * IntervalTree of reservations (see com.rental.model.Vehicle), giving O(log r) overlap
 * checks against that vehicle's own reservations instead of a linear scan of the entire
 * reservation table. Cancelling a reservation removes it from its vehicle's tree so
 * cancelled bookings never contribute to future overlap checks.
 */
public class InMemoryReservationRepository implements ReservationRepository {
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();
    private final VehicleRepository vehicleRepository;

    public InMemoryReservationRepository(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Override
    public void save(Reservation reservation) {
        reservationsById.put(reservation.getId(), reservation);
        vehicleOf(reservation).addReservation(reservation);
    }

    @Override
    public void cancel(String reservationId) {
        Reservation reservation = reservationsById.get(reservationId);
        if (reservation == null) {
            throw new ReservationNotFoundException("No reservation with id " + reservationId);
        }
        reservation.cancel();
        vehicleOf(reservation).removeReservation(reservation);
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
        return vehicleRepository.findById(vehicleId)
                .map(vehicle -> vehicle.hasOverlap(start, end))
                .orElse(false);
    }

    @Override
    public List<Reservation> findAll() {
        return new ArrayList<>(reservationsById.values());
    }

    private Vehicle vehicleOf(Reservation reservation) {
        return vehicleRepository.findById(reservation.getVehicleId())
                .orElseThrow(() -> new IllegalStateException(
                        "No vehicle registered with id " + reservation.getVehicleId()));
    }
}
