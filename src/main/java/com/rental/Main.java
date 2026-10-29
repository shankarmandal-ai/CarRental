package com.rental;

import com.rental.persistence.FleetSnapshot;
import com.rental.repository.InMemoryReservationRepository;
import com.rental.repository.InMemoryVehicleRepository;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;
import com.rental.service.RentalService;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the repositories, loads any existing fleet snapshot on startup, and saves the
 * current state back to the same file on shutdown (including Ctrl+C / normal exit).
 */
public final class Main {

    private static final Path DEFAULT_SNAPSHOT_PATH = Paths.get("fleet-snapshot.csv");

    public static void main(String[] args) {
        Path snapshotPath = args.length > 0 ? Paths.get(args[0]) : DEFAULT_SNAPSHOT_PATH;

        VehicleRepository vehicleRepository = new InMemoryVehicleRepository();
        ReservationRepository reservationRepository = new InMemoryReservationRepository(vehicleRepository);
        FleetSnapshot.load(snapshotPath, vehicleRepository, reservationRepository);

        RentalService rentalService = new RentalService(vehicleRepository, reservationRepository);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                FleetSnapshot.save(snapshotPath, vehicleRepository, reservationRepository)));

        System.out.println("Fleet loaded from " + snapshotPath.toAbsolutePath()
                + " (" + vehicleRepository.findAll().size() + " vehicles).");
        System.out.println("RentalService is ready. State will be saved to " + snapshotPath.toAbsolutePath()
                + " on exit.");
    }
}
