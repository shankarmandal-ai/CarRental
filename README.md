# Car Rental System

Simulated in-memory car rental reservation system in Java.

## Build & test

```
mvn test
```

## Layout

- `src/main/java/com/rental/model` — `Vehicle`, `VehicleType`, `Reservation`, `ReservationStatus`
- `src/main/java/com/rental/repository` — `VehicleRepository`, `ReservationRepository` and in-memory implementations
- `src/main/java/com/rental/service` — `RentalService`, the orchestrator and lock owner
- `src/main/java/com/rental/exception` — domain exceptions
- `src/test/java` — unit tests, including a concurrency stress test

See [DESIGN.md](DESIGN.md) for the class/sequence diagrams and the locking
rationale.
