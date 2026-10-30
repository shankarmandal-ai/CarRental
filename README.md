# Car Rental System

In-memory car rental reservation system in Java. Fleet and bookings persist
to [fleet.csv](fleet.csv); a TCP server lets multiple terminals issue
requests against one running instance.

## Build

```
mvn clean package -DskipTests
```

## Run

```
java -cp target/classes com.rental.Main [path-to-fleet.csv] [port]
```

Defaults: `fleet.csv` in the current directory, port `9090`. Stays running
until killed (Ctrl+C); saves state back to the CSV on exit.

## Use

Connect from any terminal:

```
nc localhost 9090
```

Commands (one per line):

```
RESERVE <TYPE> <customerId> <startISO> <endISO>
CANCEL <reservationId>
AVAILABLE <TYPE> <startISO> <endISO>
QUIT
```

`<TYPE>` is `SEDAN`, `SUV`, or `VAN`. Dates are ISO-8601, e.g.
`2026-11-01T10:00`.

Examples:

```
RESERVE SEDAN cust-1 2026-11-05T10:00 2026-11-06T10:00
CANCEL 3f1e2b4a-...
AVAILABLE VAN 2026-11-10T09:00 2026-11-12T09:00
QUIT
```

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

## Test

```
mvn test
```

Run a single test class or method:

```
mvn test -Dtest=RentalServiceTest
mvn test -Dtest=RentalServiceTest#concurrentRequestsForSameTypeOnDifferentVehiclesBothSucceed
```

## Layout

- `model` — `Vehicle`, `VehicleType`, `Reservation`, `ReservationStatus`
- `datastructure` — `IntervalTree`, the augmented AVL tree behind overlap queries
- `repository` — `VehicleRepository`, `ReservationRepository`
- `persistence` — `FleetSnapshot`, CSV save/load
- `server` — `RentalCommandServer`, the TCP line protocol
- `service` — `RentalService`, the orchestrator and lock owner
- `Main.java` — entry point

See [DESIGN.md](DESIGN.md) for diagrams and design rationale.
