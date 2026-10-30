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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates reservations and enforces the fleet's availability rules.
 *
 * Concurrency: "is vehicle V free for [start,end)" and "book V" must be a single atomic
 * step, otherwise two threads can both see V as free and both book it (classic check-then-act
 * race). That critical section is serialized per Vehicle, not per VehicleType: the race is
 * ever only over one specific car, so locking the whole type would block two requests that
 * were always going to land on two different idle vehicles, for no correctness benefit.
 * locksByVehicle hands out one java.util.concurrent.locks.Lock per vehicle id, created lazily
 * via ConcurrentHashMap.computeIfAbsent and never removed - removing an "unused" lock would
 * race against a thread that already read the old Lock reference and is about to call lock()
 * on it, so entries are kept forever (one ReentrantLock per vehicle is a trivial memory cost).
 *
 * reserve() never holds more than one vehicle's lock at a time: it reads an unlocked,
 * cheap hint about which vehicle to try (freeVehiclesByType, or a plain candidate scan),
 * then takes that one vehicle's lock and re-checks availability under the lock before
 * committing - a double-checked pattern, not an optimistic one. If the recheck finds the
 * vehicle is no longer free (lost a race to another thread between the hint and the lock),
 * the lock is released and the next candidate is tried the same way. Because at most one
 * lock is ever held at once, this cannot deadlock on lock ordering.
 *
 * Gate: a single CountDownLatch-based gate serves both the one-shot startup wait and the
 * reusable maintenance pause. It starts as a closed CountDownLatch(1), counted down by
 * markReady() once the initial fleet load completes - the same one-shot behavior as before.
 * After that, pause() swaps in a fresh closed CountDownLatch(1) and resume() counts it down,
 * any number of times; callers always await() whatever latch is currently referenced, so a
 * pause() that happens after a caller already read the (open) latch does not retroactively
 * block it - only callers that check after the new pause takes effect are gated by it.
 *
 * This only works because pause()/resume() are documented and enforced to run after the
 * service is ready: swapping the latch before markReady() has fired would orphan the
 * original latch (markReady() would count down a latch nobody awaits anymore, deadlocking
 * every caller permanently, since markReady() is only ever called once). pause() throws
 * IllegalStateException if called before markReady(), rather than risk that silently.
 *
 * freeVehiclesByType is a fast path, not a source of truth: a vehicle with zero reservations
 * is available for any interval, so it's tried first without any IntervalTree query. Each
 * type's set is built lazily on first use (scanning the fleet once) and kept in sync as part
 * of each vehicle's own locked critical section - a vehicle leaves the set the moment it gets
 * its first reservation, and rejoins the moment its last one is cancelled. The set is only a
 * hint (membership is re-verified under the vehicle's lock before being trusted); if it's
 * empty, reserve() falls back to the normal per-vehicle candidate scan.
 */
public class RentalService {
    private final VehicleRepository vehicleRepository;
    private final ReservationRepository reservationRepository;
    private final Map<String, Lock> locksByVehicle = new ConcurrentHashMap<>();
    private final Map<VehicleType, Set<Vehicle>> freeVehiclesByType = new ConcurrentHashMap<>();
    private volatile CountDownLatch gateLatch = new CountDownLatch(1);
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public RentalService(VehicleRepository vehicleRepository, ReservationRepository reservationRepository) {
        this.vehicleRepository = vehicleRepository;
        this.reservationRepository = reservationRepository;
    }

    /** Releases every thread blocked in reserve/cancel/isAvailable. Call once, after the initial fleet load completes. */
    public void markReady() {
        ready.set(true);
        gateLatch.countDown();
    }

    /**
     * Blocks every subsequent reserve/cancel/isAvailable call until resume() is called. Safe
     * to call repeatedly. Must only be called after markReady() - throws otherwise, since
     * pausing before startup has completed would orphan the startup latch and deadlock the
     * service permanently (see class-level note).
     */
    public void pause() {
        if (!ready.get()) {
            throw new IllegalStateException("Cannot pause before markReady() has been called");
        }
        gateLatch = new CountDownLatch(1);
    }

    /** Releases callers blocked by the current pause(), if any. A no-op if not currently paused. */
    public void resume() {
        gateLatch.countDown();
    }

    public Reservation reserve(VehicleType type, String customerId, LocalDateTime start, LocalDateTime endTime) {
        awaitReady();
        validate(type, customerId, start, endTime);

        Reservation reservation = tryReserveFreeVehicle(type, customerId, start, endTime);
        if (reservation != null) {
            return reservation;
        }

        for (Vehicle candidate : vehicleRepository.findByType(type)) {
            reservation = tryReserveVehicle(candidate, type, customerId, start, endTime);
            if (reservation != null) {
                return reservation;
            }
        }

        throw new NoVehicleAvailableException("No " + type + " available for " + start + " to " + endTime);
    }

    /**
     * Cheap unlocked hint: try a vehicle known (as of a moment ago) to have zero reservations.
     * Membership in freeVehiclesByType is never trusted by itself - tryReserveVehicle always
     * re-checks under that vehicle's own lock before committing. Returns null if the hint set
     * is empty or the hinted vehicle lost the race (another thread booked it first).
     */
    private Reservation tryReserveFreeVehicle(VehicleType type, String customerId, LocalDateTime start, LocalDateTime endTime) {
        Set<Vehicle> free = freeVehiclesOf(type);
        if (free.isEmpty()) {
            return null;
        }
        Vehicle hinted = free.iterator().next();
        return tryReserveVehicle(hinted, type, customerId, start, endTime);
    }

    /**
     * Locks exactly one vehicle, re-checks its availability under that lock (the hint that led
     * here - freeVehiclesByType or a plain candidate scan - is never trusted on its own), and
     * commits the reservation if it's still free. Returns null without side effects if the
     * vehicle turns out not to be free, so the caller can move on to the next candidate. Never
     * holds more than one vehicle's lock at a time, so this cannot deadlock on lock ordering.
     */
    private Reservation tryReserveVehicle(Vehicle vehicle, VehicleType type, String customerId, LocalDateTime start, LocalDateTime endTime) {
        Lock lock = lockFor(vehicle.getId());
        lock.lock();
        try {
            if (vehicle.hasOverlap(start, endTime)) {
                return null;
            }
            Reservation reservation = new Reservation(vehicle.getId(), type, customerId, start, endTime);
            reservationRepository.save(reservation);
            freeVehiclesOf(type).remove(vehicle);
            return reservation;
        } finally {
            lock.unlock();
        }
    }

    public void cancel(String reservationId) {
        awaitReady();
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("No reservation with id " + reservationId));

        Vehicle vehicle = vehicleRepository.findById(reservation.getVehicleId())
                .orElseThrow(() -> new IllegalStateException("Vehicle " + reservation.getVehicleId() + " not found for reservation " + reservationId));

        Lock lock = lockFor(vehicle.getId());
        lock.lock();
        try {
            reservationRepository.cancel(reservationId);
            if (vehicle.hasNoReservations()) {
                freeVehiclesOf(reservation.getVehicleType()).add(vehicle);
            }
        } finally {
            lock.unlock();
        }
    }

    private Lock lockFor(String vehicleId) {
        return locksByVehicle.computeIfAbsent(vehicleId, id -> new ReentrantLock());
    }

    /**
     * Advisory read only — does not take any vehicle lock. The result is stale the instant
     * it's returned if another thread books concurrently, so this must never be used as a
     * precondition for a subsequent reserve() call; call reserve() directly and handle
     * NoVehicleAvailableException instead.
     */
    public boolean isAvailable(VehicleType type, LocalDateTime start, LocalDateTime endTime) {
        awaitReady();
        if (!freeVehiclesOf(type).isEmpty()) {
            return true;
        }
        for (Vehicle v : vehicleRepository.findByType(type)) {
            if (!v.hasOverlap(start, endTime)) {
                return true;
            }
        }
        return false;
    }

    private void awaitReady() {
        try {
            gateLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for fleet load to complete or pause to be lifted", e);
        }
    }

    /**
     * Lazily builds and caches the free-vehicle set for a type on first access, scanning the
     * fleet once. The set itself (CopyOnWriteArraySet) is safe for concurrent add/remove/read
     * without external synchronization; it is only ever a hint, never trusted on its own -
     * reserve() always re-verifies a vehicle's availability under that vehicle's own lock
     * before committing, so a stale or racily-populated entry here cannot cause a double
     * booking, only an occasional unnecessary fallback to the candidate scan.
     */
    private Set<Vehicle> freeVehiclesOf(VehicleType type) {
        return freeVehiclesByType.computeIfAbsent(type, t -> {
            Set<Vehicle> free = new CopyOnWriteArraySet<>();
            for (Vehicle v : vehicleRepository.findByType(t)) {
                if (v.hasNoReservations()) {
                    free.add(v);
                }
            }
            return free;
        });
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
