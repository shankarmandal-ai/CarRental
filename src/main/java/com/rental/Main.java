package com.rental;

import com.rental.persistence.FleetSnapshot;
import com.rental.repository.InMemoryReservationRepository;
import com.rental.repository.InMemoryVehicleRepository;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;
import com.rental.server.RentalCommandServer;
import com.rental.service.RentalService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires the repositories, loads any existing fleet snapshot on startup, starts a TCP
 * command server so multiple terminals can issue requests against the one shared
 * RentalService at once, and saves current state back to the snapshot file on shutdown
 * (including Ctrl+C / normal exit).
 *
 * RentalService is constructed and reachable immediately; the snapshot load runs on a
 * separate thread, and RentalService.markReady() is only called once it finishes. Any
 * reserve/cancel/isAvailable call that comes in while loading is still in flight blocks
 * on RentalService's internal CountDownLatch until the load completes, rather than
 * racing against a partially-populated fleet.
 */
public final class Main {

    private static final Path DEFAULT_SNAPSHOT_PATH = Paths.get("fleet.csv");
    private static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) throws IOException, InterruptedException {
        Path snapshotPath = args.length > 0 ? Paths.get(args[0]) : DEFAULT_SNAPSHOT_PATH;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        VehicleRepository vehicleRepository = new InMemoryVehicleRepository();
        ReservationRepository reservationRepository = new InMemoryReservationRepository(vehicleRepository);
        RentalService rentalService = new RentalService(vehicleRepository, reservationRepository);

        Thread loader = new Thread(() -> {
            FleetSnapshot.load(snapshotPath, vehicleRepository, reservationRepository);
            rentalService.markReady();
            System.out.println("Fleet loaded from " + snapshotPath.toAbsolutePath()
                    + " (" + vehicleRepository.findAll().size() + " vehicles). RentalService is now accepting requests.");
        }, "fleet-loader");
        loader.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                FleetSnapshot.save(snapshotPath, vehicleRepository, reservationRepository)));

        System.out.println("Loading fleet from " + snapshotPath.toAbsolutePath()
                + "; requests will block until loading completes.");

        RentalCommandServer server = new RentalCommandServer(rentalService, port);
        server.start();

        // Keep the JVM alive to serve connections until the process is killed (Ctrl+C),
        // at which point the shutdown hook above saves state before exit.
        Thread.currentThread().join();
    }
}
