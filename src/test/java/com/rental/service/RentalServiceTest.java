package com.rental.service;

import com.rental.exception.InvalidReservationException;
import com.rental.exception.NoVehicleAvailableException;
import com.rental.model.Reservation;
import com.rental.model.Vehicle;
import com.rental.model.VehicleType;
import com.rental.repository.InMemoryReservationRepository;
import com.rental.repository.InMemoryVehicleRepository;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RentalServiceTest {

    private VehicleRepository vehicleRepository;
    private ReservationRepository reservationRepository;
    private RentalService rentalService;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 10, 0);

    @BeforeEach
    void setUp() {
        vehicleRepository = new InMemoryVehicleRepository();
        reservationRepository = new InMemoryReservationRepository(vehicleRepository);
        rentalService = new RentalService(vehicleRepository, reservationRepository);

        vehicleRepository.addVehicle(new Vehicle("sedan-1", VehicleType.SEDAN));
        vehicleRepository.addVehicle(new Vehicle("sedan-2", VehicleType.SEDAN));
        vehicleRepository.addVehicle(new Vehicle("suv-1", VehicleType.SUV));
        vehicleRepository.addVehicle(new Vehicle("van-1", VehicleType.VAN));

        rentalService.markReady();
    }

    @Test
    void reservesAnAvailableVehicleOfRequestedType() {
        Reservation reservation = rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE.plusDays(3));

        assertNotNull(reservation);
        assertEquals(BASE, reservation.getStart());
        assertEquals(BASE.plusDays(3), reservation.getEnd());
        assertTrue(reservation.getVehicleId().startsWith("sedan"));
    }

    @Test
    void secondReservationOfSameTypeGetsDifferentVehicleWhenBothAvailable() {
        Reservation r1 = rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE.plusDays(2));
        Reservation r2 = rentalService.reserve(VehicleType.SEDAN, "cust-2", BASE, BASE.plusDays(2));

        assertNotEqualsVehicle(r1, r2);
    }

    private void assertNotEqualsVehicle(Reservation r1, Reservation r2) {
        assertTrue(!r1.getVehicleId().equals(r2.getVehicleId()));
    }

    @Test
    void throwsWhenFleetOfTypeIsExhaustedForOverlappingPeriod() {
        rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE.plusDays(2)); // sedan-1 or sedan-2
        rentalService.reserve(VehicleType.SEDAN, "cust-2", BASE, BASE.plusDays(2)); // the other sedan

        assertThrows(NoVehicleAvailableException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, "cust-3", BASE, BASE.plusDays(2)));
    }

    @Test
    void onlyOneVanExistsSoSecondOverlappingVanRequestFails() {
        rentalService.reserve(VehicleType.VAN, "cust-1", BASE, BASE.plusDays(5));

        assertThrows(NoVehicleAvailableException.class,
                () -> rentalService.reserve(VehicleType.VAN, "cust-2", BASE.plusDays(1), BASE.plusDays(2)));
    }

    @Test
    void sameVehicleCanBeReusedOnceReservationPeriodEnds() {
        // Only one VAN in the fleet.
        rentalService.reserve(VehicleType.VAN, "cust-1", BASE, BASE.plusDays(2)); // occupies [BASE, BASE+2)

        // Starts exactly when the previous reservation ends -> no overlap (half-open interval).
        Reservation r2 = rentalService.reserve(VehicleType.VAN, "cust-2", BASE.plusDays(2), BASE.plusDays(5));

        assertEquals("van-1", r2.getVehicleId());
    }

    @Test
    void partiallyOverlappingRequestIsRejectedForSingleVehicleType() {
        rentalService.reserve(VehicleType.VAN, "cust-1", BASE, BASE.plusDays(3)); // [BASE, BASE+3)

        // Overlaps by one day at the end.
        assertThrows(NoVehicleAvailableException.class,
                () -> rentalService.reserve(VehicleType.VAN, "cust-2", BASE.plusDays(2), BASE.plusDays(4)));
    }

    @Test
    void cancellingAReservationFreesTheVehicle() {
        Reservation r1 = rentalService.reserve(VehicleType.VAN, "cust-1", BASE, BASE.plusDays(3));

        rentalService.cancel(r1.getId());

        Reservation r2 = rentalService.reserve(VehicleType.VAN, "cust-2", BASE, BASE.plusDays(3));
        assertEquals("van-1", r2.getVehicleId());
    }

    @Test
    void isAvailableReflectsCurrentBookings() {
        assertTrue(rentalService.isAvailable(VehicleType.SUV, BASE, BASE.plusDays(1)));

        rentalService.reserve(VehicleType.SUV, "cust-1", BASE, BASE.plusDays(1));

        assertFalse(rentalService.isAvailable(VehicleType.SUV, BASE, BASE.plusDays(1)));
    }

    @Test
    void rejectsNonPositiveDuration() {
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE));
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE.minusDays(1)));
    }

    @Test
    void rejectsMissingCustomerId() {
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, " ", BASE, BASE.plusDays(1)));
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, null, BASE, BASE.plusDays(1)));
    }

    @Test
    void rejectsMissingType() {
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(null, "cust-1", BASE, BASE.plusDays(1)));
    }

    @Test
    void rejectsMissingStart() {
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, "cust-1", null, BASE.plusDays(1)));
    }

    @Test
    void rejectsMissingEndTime() {
        assertThrows(InvalidReservationException.class,
                () -> rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, null));
    }

    /**
     * Concurrency correctness: with only one VAN in the fleet, fire many concurrent
     * overlapping requests for it. Exactly one must succeed; all others must fail with
     * NoVehicleAvailableException. This proves the check-then-act race is closed by the
     * per-vehicle lock in RentalService.
     */
    @Test
    void concurrentOverlappingRequestsForScarceVehicleYieldExactlyOneWinner() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        Set<String> wonVehicleIds = new ConcurrentSkipListSet<>();

        for (int i = 0; i < threadCount; i++) {
            final String customerId = "cust-" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Reservation r = rentalService.reserve(VehicleType.VAN, customerId, BASE, BASE.plusDays(1));
                    wonVehicleIds.add(r.getVehicleId());
                    successCount.incrementAndGet();
                } catch (NoVehicleAvailableException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // release all threads at once
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "all threads should finish within timeout");
        assertEquals(1, successCount.get(), "exactly one reservation should win the single VAN");
        assertEquals(threadCount - 1, failureCount.get());
        assertEquals(1, wonVehicleIds.size());
        assertEquals(1, reservationRepository.findByVehicleId("van-1").size());
    }

    /**
     * Concurrent requests for *different* vehicle types must not block each other
     * (locking is per-vehicle, never global or per-type) and both should succeed.
     */
    @Test
    void concurrentRequestsForDifferentTypesBothSucceed() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        executor.submit(() -> {
            rentalService.reserve(VehicleType.SEDAN, "cust-a", BASE, BASE.plusDays(1));
            successCount.incrementAndGet();
            doneLatch.countDown();
        });
        executor.submit(() -> {
            rentalService.reserve(VehicleType.VAN, "cust-b", BASE, BASE.plusDays(1));
            successCount.incrementAndGet();
            doneLatch.countDown();
        });

        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished);
        assertEquals(2, successCount.get());
    }

    /**
     * The granularity per-vehicle locking buys over per-type locking: two concurrent
     * requests for the *same* type that land on two *different* vehicles must both succeed
     * without either blocking on the other's lock. (Both sedans exist and are idle at setup.)
     */
    @Test
    void concurrentRequestsForSameTypeOnDifferentVehiclesBothSucceed() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch doneLatch = new CountDownLatch(2);
        Set<String> wonVehicleIds = new ConcurrentSkipListSet<>();

        executor.submit(() -> {
            Reservation r = rentalService.reserve(VehicleType.SEDAN, "cust-a", BASE, BASE.plusDays(1));
            wonVehicleIds.add(r.getVehicleId());
            doneLatch.countDown();
        });
        executor.submit(() -> {
            Reservation r = rentalService.reserve(VehicleType.SEDAN, "cust-b", BASE, BASE.plusDays(1));
            wonVehicleIds.add(r.getVehicleId());
            doneLatch.countDown();
        });

        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished);
        assertEquals(2, wonVehicleIds.size(), "both sedans should be booked by two different winners");
    }

    /**
     * reserve/cancel/isAvailable must block until markReady() is called, so a fleet
     * load running on another thread (see com.rental.Main) can't race with callers
     * that start hitting the service before the initial data is in place.
     */
    @Test
    void operationsBlockUntilMarkReadyIsCalled() throws InterruptedException {
        VehicleRepository freshVehicles = new InMemoryVehicleRepository();
        ReservationRepository freshReservations = new InMemoryReservationRepository(freshVehicles);
        RentalService notYetReady = new RentalService(freshVehicles, freshReservations);
        freshVehicles.addVehicle(new Vehicle("van-9", VehicleType.VAN));

        CountDownLatch callerStarted = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicInteger resultHolder = new AtomicInteger(-1);

        Thread caller = new Thread(() -> {
            callerStarted.countDown();
            boolean available = notYetReady.isAvailable(VehicleType.VAN, BASE, BASE.plusDays(1));
            resultHolder.set(available ? 1 : 0);
            callerFinished.countDown();
        });
        caller.start();

        assertTrue(callerStarted.await(2, TimeUnit.SECONDS));
        // The caller is blocked on the latch; give it a moment to prove it hasn't finished.
        assertFalse(callerFinished.await(300, TimeUnit.MILLISECONDS),
                "isAvailable should still be blocked before markReady() is called");

        notYetReady.markReady();

        assertTrue(callerFinished.await(2, TimeUnit.SECONDS), "call should complete once markReady() runs");
        assertEquals(1, resultHolder.get());
        caller.join();
    }

    /**
     * pause()/resume() must be reusable, unlike the one-shot startup gate: a call blocks
     * while paused, resume() releases it, and a second pause()/resume() cycle must work
     * identically (proving the gate isn't a one-time latch).
     */
    @Test
    void operationsBlockUntilResumeIsCalledAndGateIsReusable() throws InterruptedException {
        rentalService.pause();

        CountDownLatch callerStarted = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicInteger resultHolder = new AtomicInteger(-1);

        Thread caller = new Thread(() -> {
            callerStarted.countDown();
            boolean available = rentalService.isAvailable(VehicleType.VAN, BASE, BASE.plusDays(1));
            resultHolder.set(available ? 1 : 0);
            callerFinished.countDown();
        });
        caller.start();

        assertTrue(callerStarted.await(2, TimeUnit.SECONDS));
        assertFalse(callerFinished.await(300, TimeUnit.MILLISECONDS),
                "isAvailable should still be blocked while paused");

        rentalService.resume();

        assertTrue(callerFinished.await(2, TimeUnit.SECONDS), "call should complete once resume() runs");
        assertEquals(1, resultHolder.get());
        caller.join();

        // Second pause/resume cycle must behave the same way - proves the gate is reusable.
        rentalService.pause();

        CountDownLatch secondCallerStarted = new CountDownLatch(1);
        CountDownLatch secondCallerFinished = new CountDownLatch(1);

        Thread secondCaller = new Thread(() -> {
            secondCallerStarted.countDown();
            rentalService.isAvailable(VehicleType.VAN, BASE, BASE.plusDays(1));
            secondCallerFinished.countDown();
        });
        secondCaller.start();

        assertTrue(secondCallerStarted.await(2, TimeUnit.SECONDS));
        assertFalse(secondCallerFinished.await(300, TimeUnit.MILLISECONDS),
                "isAvailable should block again on the second pause() cycle");

        rentalService.resume();

        assertTrue(secondCallerFinished.await(2, TimeUnit.SECONDS));
        secondCaller.join();
    }

    @Test
    void resumeWithoutPauseIsANoOpAndOperationsProceedNormally() {
        rentalService.resume();

        assertTrue(rentalService.isAvailable(VehicleType.SUV, BASE, BASE.plusDays(1)));
    }

    /**
     * pause() shares one CountDownLatch-based gate with the startup wait (see RentalService's
     * class-level note): swapping that latch before markReady() has ever fired would orphan
     * the original latch and deadlock the service permanently, since markReady() only ever
     * counts down once. pause() must refuse to run before markReady() rather than risk that.
     */
    @Test
    void pauseBeforeMarkReadyThrows() {
        VehicleRepository freshVehicles = new InMemoryVehicleRepository();
        ReservationRepository freshReservations = new InMemoryReservationRepository(freshVehicles);
        RentalService notYetReady = new RentalService(freshVehicles, freshReservations);

        assertThrows(IllegalStateException.class, notYetReady::pause);
    }

    /**
     * freeVehiclesByType is a fast path: a vehicle with zero reservations is handed out
     * without any tree query. Once every vehicle of a type has at least one booking, the
     * free set is empty and reserve() must fall back to the normal overlap scan - this
     * proves that fallback still finds a vehicle that's free for the *requested* interval
     * even though no vehicle is free unconditionally.
     */
    @Test
    void fallsBackToOverlapScanOnceNoVehicleIsUnconditionallyFree() {
        // Book sedan-1 and sedan-2 for different, non-overlapping periods so neither
        // vehicle has an empty reservation tree anymore - the free-set fast path is exhausted.
        rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE.plusDays(1));
        rentalService.reserve(VehicleType.SEDAN, "cust-2", BASE.plusDays(10), BASE.plusDays(11));

        // Neither sedan is unconditionally free anymore, but sedan-1 is free for this
        // later period (it only has a booking on day 0-1), so the overlap scan must find it.
        Reservation r = rentalService.reserve(VehicleType.SEDAN, "cust-3", BASE.plusDays(2), BASE.plusDays(3));

        assertNotNull(r);
        assertTrue(r.getVehicleId().startsWith("sedan"));
    }

    /**
     * Cancelling a vehicle's only reservation must make it eligible for the free-set fast
     * path again: book both sedans, cancel one, then confirm a fresh request for an
     * unrelated period still succeeds (the cancelled vehicle is available for anything).
     */
    @Test
    void cancellingAVehiclesOnlyReservationRestoresItToFreeFastPath() {
        Reservation r1 = rentalService.reserve(VehicleType.SEDAN, "cust-1", BASE, BASE.plusDays(1));
        Reservation r2 = rentalService.reserve(VehicleType.SEDAN, "cust-2", BASE.plusDays(1), BASE.plusDays(2));
        assertNotEqualsVehicle(r1, r2);

        rentalService.cancel(r1.getId());

        // r1's vehicle has no reservations again - it should be available for a period far
        // from anything previously booked, proving it rejoined the free set rather than
        // requiring an overlap check to discover it's free.
        Reservation r3 = rentalService.reserve(VehicleType.SEDAN, "cust-3", BASE.plusDays(100), BASE.plusDays(101));
        assertEquals(r1.getVehicleId(), r3.getVehicleId());
    }
}
