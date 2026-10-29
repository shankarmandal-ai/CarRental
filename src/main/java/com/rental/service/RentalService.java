package com.rental.service;

import com.rental.exception.InvalidReservationException;
import com.rental.exception.NoVehicleAvailableException;
import com.rental.exception.ReservationNotFoundException;
import com.rental.model.Reservation;
import com.rental.model.Vehicle;
import com.rental.model.VehicleType;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
 *
 * Startup gate: readyLatch blocks every public operation (reserve/cancel/isAvailable)
 * until markReady() is called, so a fleet load running on a separate thread (see
 * com.rental.Main) can't race with callers that start hitting this service before the
 * initial data is in place. It's a one-shot gate, not reusable for pausing later.
 */
public class RentalService {
    private final VehicleRepository vehicleRepository;
    private final ReservationRepository reservationRepository;
    private final Map<VehicleType, Lock> locksByType = new ConcurrentHashMap<>();
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    public RentalService(VehicleRepository vehicleRepository, ReservationRepository reservationRepository) {
        this.vehicleRepository = vehicleRepository;
        this.reservationRepository = reservationRepository;
        for (VehicleType type : VehicleType.values()) {
            locksByType.put(type, new ReentrantLock());
        }
    }

    /** Releases every thread blocked in reserve/cancel/isAvailable. Call once, after the initial fleet load completes. */
    public void markReady() {
        readyLatch.countDown();
    }

    public Reservation reserve(VehicleType type, String customerId, LocalDateTime start, LocalDateTime endTime) {
        awaitReady();
        validate(type, customerId, start, endTime);

        Lock lock = locksByType.get(type);
        lock.lock();
        try {
            Vehicle available = findAvailableVehicle(type, start, endTime);
            if (available == null) {
                throw new NoVehicleAvailableException(
                        "No " + type + " available for " + start + " to " + endTime);
            }
            Reservation reservation = new Reservation(available.getId(), type, customerId, start, endTime);
            reservationRepository.save(reservation);
            return reservation;
        } finally {
            lock.unlock();
        }
    }

    public void cancel(String reservationId) {
        awaitReady();
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("No reservation with id " + reservationId));

        Lock lock = locksByType.get(reservation.getVehicleType());
        lock.lock();
        try {
            reservationRepository.cancel(reservationId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Advisory read only — does not take the per-type lock. The result is stale the
     * instant it's returned if another thread books concurrently, so this must never be
     * used as a precondition for a subsequent reserve() call; call reserve() directly and
     * handle NoVehicleAvailableException instead.
     */
    public boolean isAvailable(VehicleType type, LocalDateTime start, LocalDateTime endTime) {
        awaitReady();
        return findAvailableVehicle(type, start, endTime) != null;
    }

    private void awaitReady() {
        try {
            readyLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for fleet load to complete", e);
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

    private void validate(VehicleType type, String customerId, LocalDateTime start, LocalDateTime endTime) {
        if (type == null) {
            throw new InvalidReservationException("Vehicle type is required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidReservationException("Customer id is required");
        }
        if (start == null) {
            throw new InvalidReservationException("Start date/time is required");
        }
        if (endTime == null) {
            throw new InvalidReservationException("End date/time is required");
        }
        if (!endTime.isAfter(start)) {
            throw new InvalidReservationException("End date/time must be after start date/time");
        }
    }
}
