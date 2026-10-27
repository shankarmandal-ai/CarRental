package com.rental.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 9, 0);

    @Test
    void identicalIntervalsOverlap() {
        Reservation r = new Reservation("v1", "c1", T0, T0.plusDays(2));
        assertTrue(r.overlaps(T0, T0.plusDays(2)));
    }

    @Test
    void partialOverlapAtStartIsOverlap() {
        Reservation r = new Reservation("v1", "c1", T0, T0.plusDays(2));
        assertTrue(r.overlaps(T0.minusDays(1), T0.plusDays(1)));
    }

    @Test
    void partialOverlapAtEndIsOverlap() {
        Reservation r = new Reservation("v1", "c1", T0, T0.plusDays(2));
        assertTrue(r.overlaps(T0.plusDays(1), T0.plusDays(3)));
    }

    @Test
    void adjacentIntervalsTouchingAtBoundaryDoNotOverlap() {
        Reservation r = new Reservation("v1", "c1", T0, T0.plusDays(2));
        assertFalse(r.overlaps(T0.plusDays(2), T0.plusDays(4))); // starts exactly when r ends
        assertFalse(r.overlaps(T0.minusDays(2), T0)); // ends exactly when r starts
    }

    @Test
    void disjointIntervalsDoNotOverlap() {
        Reservation r = new Reservation("v1", "c1", T0, T0.plusDays(2));
        assertFalse(r.overlaps(T0.plusDays(5), T0.plusDays(6)));
    }

    @Test
    void endMustBeAfterStart() {
        assertThrows(IllegalArgumentException.class, () -> new Reservation("v1", "c1", T0, T0));
        assertThrows(IllegalArgumentException.class, () -> new Reservation("v1", "c1", T0, T0.minusDays(1)));
    }

    @Test
    void cancelMakesReservationInactive() {
        Reservation r = new Reservation("v1", "c1", T0, T0.plusDays(1));
        assertTrue(r.isActive());
        r.cancel();
        assertFalse(r.isActive());
    }
}
