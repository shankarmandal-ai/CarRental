package com.rental.repository;

import com.rental.model.Reservation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository {
    void save(Reservation reservation);

    Optional<Reservation> findById(String id);

    List<Reservation> findByVehicleId(String vehicleId);

    /** True if the given vehicle has any active reservation overlapping [start, end). */
    boolean hasOverlap(String vehicleId, LocalDateTime start, LocalDateTime end);

    List<Reservation> findAll();
}
