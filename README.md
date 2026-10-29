# Car Rental System

Simulated in-memory car rental reservation system in Java, with its fleet
and active bookings persisted to a flat CSV snapshot across restarts.

## Build & test

```
mvn test
```

## Run

```
mvn -q package -DskipTests
java -cp target/classes com.rental.Main [path-to-snapshot.csv]
```

Loads the fleet/bookings from the given CSV (or `fleet-snapshot.csv` in the
current directory if omitted; a missing file just starts with an empty
fleet) and saves current state back to the same file on exit.

## Layout

- `src/main/java/com/rental/model` — `Vehicle` (owns its own reservation `IntervalTree`), `VehicleType`, `Reservation`, `ReservationStatus`
- `src/main/java/com/rental/datastructure` — `IntervalTree`, the augmented AVL tree behind overlap queries
- `src/main/java/com/rental/repository` — `VehicleRepository`, `ReservationRepository` and in-memory implementations
- `src/main/java/com/rental/persistence` — `FleetSnapshot`, CSV save/load of the fleet and active bookings
- `src/main/java/com/rental/service` — `RentalService`, the orchestrator and lock owner
- `src/main/java/com/rental/exception` — domain exceptions
- `src/main/java/com/rental/Main.java` — entry point: loads the snapshot on startup, saves it on shutdown
- `src/test/java` — unit tests, including a concurrency stress test and a snapshot round-trip test

See [DESIGN.md](DESIGN.md) for the class/sequence diagrams and the locking
rationale.
