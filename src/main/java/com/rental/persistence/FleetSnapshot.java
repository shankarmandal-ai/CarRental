package com.rental.persistence;

import com.rental.model.Reservation;
import com.rental.model.Vehicle;
import com.rental.model.VehicleType;
import com.rental.repository.ReservationRepository;
import com.rental.repository.VehicleRepository;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Flat-CSV persistence of the fleet and its active bookings, one row per reservation
 * (or one bare row per vehicle with no bookings, so an empty vehicle isn't lost):
 *
 *   vehicleId,type,customerId,start,end     (one row per active reservation)
 *   vehicleId,type                           (a vehicle with zero bookings)
 *
 * Only CONFIRMED reservations are persisted — cancelled bookings are not restored on
 * reload. Reservation ids are not persisted; reloaded reservations get freshly
 * generated ids, since nothing in this snapshot format depends on the original id.
 */
public final class FleetSnapshot {

    private static final String DELIMITER = ",";

    private FleetSnapshot() {
    }

    public static void save(Path path, VehicleRepository vehicleRepository,
                             ReservationRepository reservationRepository) {
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            for (Vehicle vehicle : vehicleRepository.findAll()) {
                List<Reservation> active = reservationRepository.findByVehicleId(vehicle.getId()).stream()
                        .filter(Reservation::isActive)
                        .toList();
                if (active.isEmpty()) {
                    writer.write(vehicle.getId() + DELIMITER + vehicle.getType());
                    writer.newLine();
                    continue;
                }
                for (Reservation reservation : active) {
                    writer.write(String.join(DELIMITER,
                            vehicle.getId(),
                            vehicle.getType().toString(),
                            reservation.getCustomerId(),
                            reservation.getStart().toString(),
                            reservation.getEnd().toString()));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fleet snapshot to " + path, e);
        }
    }

    /**
     * Populates the given repositories from a snapshot file. The repositories must be
     * empty beforehand; this does not merge with or overwrite existing entries for a
     * vehicle id that's added more than once.
     */
    public static void load(Path path, VehicleRepository vehicleRepository,
                             ReservationRepository reservationRepository) {
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                loadLine(line, vehicleRepository, reservationRepository);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fleet snapshot from " + path, e);
        }
    }

    private static void loadLine(String line, VehicleRepository vehicleRepository,
                                  ReservationRepository reservationRepository) {
        String[] fields = line.split(DELIMITER, -1);
        String vehicleId = fields[0];
        VehicleType type = VehicleType.valueOf(fields[1]);

        if (vehicleRepository.findById(vehicleId).isEmpty()) {
            vehicleRepository.addVehicle(new Vehicle(vehicleId, type));
        }

        if (fields.length == 2) {
            return; // vehicle with no bookings
        }

        String customerId = fields[2];
        LocalDateTime start = LocalDateTime.parse(fields[3]);
        LocalDateTime end = LocalDateTime.parse(fields[4]);
        reservationRepository.save(new Reservation(vehicleId, type, customerId, start, end));
    }
}
