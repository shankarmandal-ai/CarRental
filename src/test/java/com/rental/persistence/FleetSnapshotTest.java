package com.rental.persistence;

import com.rental.model.Reservation;
import com.rental.model.Vehicle;
import com.rental.model.VehicleType;
import com.rental.repository.InMemoryReservationRepository;
import com.rental.repository.InMemoryVehicleRepository;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetSnapshotTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 10, 0);

    @TempDir
    Path tempDir;

    @Test
    void roundTripsVehiclesAndActiveBookings() {
        VehicleRepository vehicleRepository = new InMemoryVehicleRepository();
        ReservationRepository reservationRepository = new InMemoryReservationRepository(vehicleRepository);

        vehicleRepository.addVehicle(new Vehicle("sedan-1", VehicleType.SEDAN)); // no bookings
        vehicleRepository.addVehicle(new Vehicle("suv-1", VehicleType.SUV));
        vehicleRepository.addVehicle(new Vehicle("van-1", VehicleType.VAN));

        reservationRepository.save(new Reservation("suv-1", VehicleType.SUV, "cust-1",
                BASE, BASE.plusDays(2)));
        reservationRepository.save(new Reservation("suv-1", VehicleType.SUV, "cust-2",
                BASE.plusDays(5), BASE.plusDays(6)));
        reservationRepository.save(new Reservation("van-1", VehicleType.VAN, "cust-3",
                BASE, BASE.plusDays(1)));

        Path snapshotPath = tempDir.resolve("fleet.csv");
        FleetSnapshot.save(snapshotPath, vehicleRepository, reservationRepository);

        VehicleRepository reloadedVehicles = new InMemoryVehicleRepository();
        ReservationRepository reloadedReservations = new InMemoryReservationRepository(reloadedVehicles);
        FleetSnapshot.load(snapshotPath, reloadedVehicles, reloadedReservations);

        assertEquals(3, reloadedVehicles.findAll().size());
        assertTrue(reloadedVehicles.findById("sedan-1").isPresent());
        assertEquals(VehicleType.SEDAN, reloadedVehicles.findById("sedan-1").get().getType());

        List<Reservation> suvBookings = reloadedReservations.findByVehicleId("suv-1");
        assertEquals(2, suvBookings.size());

        List<Reservation> sedanBookings = reloadedReservations.findByVehicleId("sedan-1");
        assertTrue(sedanBookings.isEmpty());

        // The restored overlap index must actually work, not just hold flat records.
        assertTrue(reloadedVehicles.findById("suv-1").get().hasOverlap(BASE.plusDays(1), BASE.plusDays(1).plusHours(1)));
        assertFalse(reloadedVehicles.findById("suv-1").get().hasOverlap(BASE.plusDays(3), BASE.plusDays(4)));
    }

    @Test
    void cancelledReservationsAreNotPersisted() {
        VehicleRepository vehicleRepository = new InMemoryVehicleRepository();
        ReservationRepository reservationRepository = new InMemoryReservationRepository(vehicleRepository);
        vehicleRepository.addVehicle(new Vehicle("van-1", VehicleType.VAN));

        Reservation reservation = new Reservation("van-1", VehicleType.VAN, "cust-1", BASE, BASE.plusDays(1));
        reservationRepository.save(reservation);
        reservationRepository.cancel(reservation.getId());

        Path snapshotPath = tempDir.resolve("fleet.csv");
        FleetSnapshot.save(snapshotPath, vehicleRepository, reservationRepository);

        VehicleRepository reloadedVehicles = new InMemoryVehicleRepository();
        ReservationRepository reloadedReservations = new InMemoryReservationRepository(reloadedVehicles);
        FleetSnapshot.load(snapshotPath, reloadedVehicles, reloadedReservations);

        assertTrue(reloadedVehicles.findById("van-1").isPresent());
        assertTrue(reloadedReservations.findByVehicleId("van-1").isEmpty());
        assertFalse(reloadedVehicles.findById("van-1").get().hasOverlap(BASE, BASE.plusDays(1)));
    }

    @Test
    void writesBareRowForVehicleWithNoBookings() throws IOException {
        VehicleRepository vehicleRepository = new InMemoryVehicleRepository();
        ReservationRepository reservationRepository = new InMemoryReservationRepository(vehicleRepository);
        vehicleRepository.addVehicle(new Vehicle("sedan-9", VehicleType.SEDAN));

        Path snapshotPath = tempDir.resolve("fleet.csv");
        FleetSnapshot.save(snapshotPath, vehicleRepository, reservationRepository);

        List<String> lines = Files.readAllLines(snapshotPath);
        assertEquals(1, lines.size());
        assertEquals("sedan-9,SEDAN", lines.get(0));
    }

    @Test
    void loadIsNoOpWhenFileDoesNotExist() {
        VehicleRepository vehicleRepository = new InMemoryVehicleRepository();
        ReservationRepository reservationRepository = new InMemoryReservationRepository(vehicleRepository);

        FleetSnapshot.load(tempDir.resolve("does-not-exist.csv"), vehicleRepository, reservationRepository);

        assertTrue(vehicleRepository.findAll().isEmpty());
    }
}
