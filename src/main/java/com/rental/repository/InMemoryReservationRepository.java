package com.rental.repository;

import com.rental.model.Reservation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReservationRepository implements ReservationRepository {
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();

    @Override
    public void save(Reservation reservation) {
        reservationsById.put(reservation.getId(), reservation);
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
        for (Reservation r : reservationsById.values()) {
            if (r.getVehicleId().equals(vehicleId) && r.isActive() && r.overlaps(start, end)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Reservation> findAll() {
        return new ArrayList<>(reservationsById.values());
    }
}
