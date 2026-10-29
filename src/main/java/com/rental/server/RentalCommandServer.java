package com.rental.server;

import com.rental.exception.InvalidReservationException;
import com.rental.exception.NoVehicleAvailableException;
import com.rental.exception.ReservationNotFoundException;
import com.rental.model.Reservation;
import com.rental.model.VehicleType;
import com.rental.service.RentalService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plain-text line protocol over TCP so multiple terminals can issue requests against one
 * shared RentalService at once. Each accepted connection runs on its own thread from an
 * unbounded cached pool; RentalService's own per-type locks and readyLatch are what make
 * concurrent access from many connections safe, not anything in this class.
 *
 * Commands (one per line):
 *   RESERVE <TYPE> <customerId> <startISO> <endISO>
 *   CANCEL <reservationId>
 *   AVAILABLE <TYPE> <startISO> <endISO>
 *   QUIT
 *
 * TYPE is one of SEDAN, SUV, VAN. Dates are ISO-8601 local date-times, e.g.
 * 2026-11-01T10:00. Connect with `nc localhost <port>` or `telnet localhost <port>`.
 */
public class RentalCommandServer {

    private final RentalService rentalService;
    private final int port;
    private final ExecutorService connectionPool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public RentalCommandServer(RentalService rentalService, int port) {
        this.rentalService = rentalService;
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("RentalCommandServer listening on port " + port
                + ". Connect with: nc localhost " + port);

        Thread acceptLoop = new Thread(this::acceptLoop, "rental-server-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
    }

    public void stop() {
        running = false;
        connectionPool.shutdownNow();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down anyway
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connectionPool.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            out.println("Connected to RentalCommandServer. Commands: RESERVE, CANCEL, AVAILABLE, QUIT");
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("QUIT")) {
                    out.println("OK bye");
                    return;
                }
                out.println(handleCommand(line));
            }
        } catch (IOException e) {
            // connection dropped; nothing to clean up beyond the try-with-resources above
        }
    }

    private String handleCommand(String line) {
        String[] parts = line.split("\\s+");
        try {
            String verb = parts[0].toUpperCase();
            switch (verb) {
                case "RESERVE":
                    return handleReserve(parts);
                case "CANCEL":
                    return handleCancel(parts);
                case "AVAILABLE":
                    return handleAvailable(parts);
                default:
                    return "ERROR Unknown command: " + parts[0];
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return "ERROR Wrong number of arguments for: " + line;
        } catch (IllegalArgumentException e) {
            return "ERROR " + e.getMessage();
        } catch (InvalidReservationException | NoVehicleAvailableException | ReservationNotFoundException e) {
            return "ERROR " + e.getMessage();
        }
    }

    private String handleReserve(String[] parts) {
        VehicleType type = parseType(parts[1]);
        String customerId = parts[2];
        LocalDateTime start = parseDateTime(parts[3]);
        LocalDateTime end = parseDateTime(parts[4]);
        Reservation reservation = rentalService.reserve(type, customerId, start, end);
        return "OK " + reservation.getId() + " " + reservation.getVehicleId();
    }

    private String handleCancel(String[] parts) {
        rentalService.cancel(parts[1]);
        return "OK cancelled " + parts[1];
    }

    private String handleAvailable(String[] parts) {
        VehicleType type = parseType(parts[1]);
        LocalDateTime start = parseDateTime(parts[2]);
        LocalDateTime end = parseDateTime(parts[3]);
        return "OK " + rentalService.isAvailable(type, start, end);
    }

    private VehicleType parseType(String raw) {
        try {
            return VehicleType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown vehicle type: " + raw + " (expected SEDAN, SUV, or VAN)");
        }
    }

    private LocalDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time: " + raw + " (expected e.g. 2026-11-01T10:00)");
        }
    }
}
