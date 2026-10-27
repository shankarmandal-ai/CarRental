package com.rental.service;

import com.rental.exception.InvalidReservationException;
import com.rental.exception.NoVehicleAvailableException;
import com.rental.exception.ReservationNotFoundException;
import com.rental.model.Reservation;
import com.rental.model.ReservationStatus;
import com.rental.model.Vehicle;
import com.rental.model.VehicleType;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates reservations and enforces the fleet's availability rules.
 *
 * Concurrency: "find an available vehicle of type T" and "book it" must be a single
 * atomic step, otherwise two threads can both see vehicle V as free and both book it
 * (classic check-then-act race). We serialize that critical section per VehicleType
 * using a dedicated lock per type, so bookings for SEDAN never block bookings for VAN.
 * The lock protects the decision (which vehicle to assign), not the whole system.
 */
public class RentalService {
    private final VehicleRepository vehicleRepository;
    private final ReservationRepository reservationRepository;
    private final Map<VehicleType, Lock> locksByType = new ConcurrentHashMap<>();

    public RentalService(VehicleRepository vehicleRepository, ReservationRepository reservationRepository) {
        this.vehicleRepository = vehicleRepository;
        this.reservationRepository = reservationRepository;
        for (VehicleType type : VehicleType.values()) {
            locksByType.put(type, new ReentrantLock());
        }
    }

    public Reservation reserve(VehicleType type, String customerId, LocalDateTime start, int days) {
        validate(type, customerId, start, days);
        LocalDateTime end = start.plusDays(days);

        Lock lock = locksByType.get(type);
        lock.lock();
        try {
            Vehicle available = findAvailableVehicle(type, start, end);
            if (available == null) {
                throw new NoVehicleAvailableException(
                        "No " + type + " available for " + start + " to " + end);
            }
            Reservation reservation = new Reservation(available.getId(), customerId, start, end);
            reservationRepository.save(reservation);
            return reservation;
        } finally {
            lock.unlock();
        }
    }

    public void cancel(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("No reservation with id " + reservationId));
        reservation.cancel();
    }

    public boolean isAvailable(VehicleType type, LocalDateTime start, int days) {
        LocalDateTime end = start.plusDays(days);
        Lock lock = locksByType.get(type);
        lock.lock();
        try {
            return findAvailableVehicle(type, start, end) != null;
        } finally {
            lock.unlock();
        }
    }

    private Vehicle findAvailableVehicle(VehicleType type, LocalDateTime start, LocalDateTime end) {
        List<Vehicle> candidates = vehicleRepository.findByType(type);
        for (Vehicle v : candidates) {
            if (!reservationRepository.hasOverlap(v.getId(), start, end)) {
                return v;
            }
        }
        return null;
    }

    private void validate(VehicleType type, String customerId, LocalDateTime start, int days) {
        if (type == null) {
            throw new InvalidReservationException("Vehicle type is required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidReservationException("Customer id is required");
        }
        if (start == null) {
            throw new InvalidReservationException("Start date/time is required");
        }
        if (days <= 0) {
            throw new InvalidReservationException("Number of days must be positive");
        }
    }
}
