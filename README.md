# Car Rental System

Simulated in-memory car rental reservation system in Java. The fleet and
active bookings are persisted to [fleet.csv](fleet.csv) across restarts, and
a TCP command server lets multiple terminals issue requests against one
shared `RentalService` at once.

## Build & test

```
mvn test
```

## Run

```
mvn -q package -DskipTests
java -cp target/classes com.rental.Main [path-to-fleet.csv] [port]
```

Defaults to `fleet.csv` in the current directory and port `9090` if omitted
(a missing CSV just starts with an empty fleet). The process stays running
until killed (Ctrl+C), and the shutdown hook saves current state back to
the CSV on exit — including the normal `kill`/SIGTERM path, not just a
clean return from `main`.

### Connecting from multiple terminals

Each terminal opens its own TCP connection to the same running server and
shares the one `RentalService` instance — `RentalService`'s per-type locks
and startup latch are what make concurrent requests from different
terminals safe, not anything in the server itself.

```
nc localhost 9090
```

(or `telnet localhost 9090`). Commands, one per line:

```
RESERVE <TYPE> <customerId> <startISO> <endISO>
CANCEL <reservationId>
AVAILABLE <TYPE> <startISO> <endISO>
QUIT
```

`<TYPE>` is `SEDAN`, `SUV`, or `VAN`. Dates are ISO-8601 local date-times,
e.g. `2026-11-01T10:00`. Example session:

```
$ nc localhost 9090
Connected to RentalCommandServer. Commands: RESERVE, CANCEL, AVAILABLE, QUIT
AVAILABLE SEDAN 2026-11-05T10:00 2026-11-06T10:00
OK true
RESERVE SEDAN cust-1 2026-11-05T10:00 2026-11-06T10:00
OK 3f1e... sedan-1
QUIT
OK bye
```

## Layout

- `src/main/java/com/rental/model` — `Vehicle` (owns its own reservation `IntervalTree`), `VehicleType`, `Reservation`, `ReservationStatus`
- `src/main/java/com/rental/datastructure` — `IntervalTree`, the augmented AVL tree behind overlap queries
- `src/main/java/com/rental/repository` — `VehicleRepository`, `ReservationRepository` and in-memory implementations
- `src/main/java/com/rental/persistence` — `FleetSnapshot`, CSV save/load of the fleet and active bookings
- `src/main/java/com/rental/server` — `RentalCommandServer`, the TCP line-protocol server
- `src/main/java/com/rental/service` — `RentalService`, the orchestrator and lock owner
- `src/main/java/com/rental/exception` — domain exceptions
- `src/main/java/com/rental/Main.java` — entry point: loads the snapshot, starts the server, saves on shutdown
- `src/test/java` — unit tests, including a concurrency stress test and a snapshot round-trip test
- `fleet.csv` — sample/default fleet snapshot used by `Main` when no path is given

See [DESIGN.md](DESIGN.md) for the class/sequence diagrams and the locking
rationale.
