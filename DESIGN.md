# Car Rental System — Design

## 1. Scope

Simulate reservation of a car of a given type (sedan / SUV / van), for a start
date/time and a number of days, against a fleet with a fixed number of cars per
type, preventing double-booking of a single physical car. In-memory, no
persistence, no web layer — the interesting problems here are the domain model
and concurrency correctness, not I/O.

## 2. Class diagram

```
                       ┌───────────────────┐
                       │   VehicleType      │ (enum)
                       │  SEDAN, SUV, VAN   │
                       └─────────▲──────────┘
                                 │
                       ┌─────────┴──────────┐
                       │      Vehicle        │
                       │  id: String         │
                       │  type: VehicleType  │
                       └─────────▲──────────┘
                                 │ reserved as
                       ┌─────────┴──────────┐
                       │    Reservation      │
                       │  id: String         │
                       │  vehicleId: String  │
                       │  customerId: String │
                       │  start, end         │
                       │  status             │
                       │  overlaps(s, e)      │
                       └─────────▲──────────┘
                                 │ reads/writes
        ┌────────────────────────┴───────────────────────┐
        │                                                  │
┌───────┴────────────┐                          ┌──────────┴───────────┐
│ VehicleRepository    │                          │ ReservationRepository │
│  (interface)         │                          │  (interface)          │
│  findByType(type)     │                          │  hasOverlap(id,s,e)   │
│  addVehicle(v)        │                          │  save(r)              │
└───────▲──────────────┘                          └───────────▲───────────┘
        │ implements                                           │ implements
┌───────┴──────────────┐                          ┌────────────┴───────────┐
│InMemoryVehicleRepo    │                          │InMemoryReservationRepo  │
└───────────────────────┘                          └─────────────────────────┘

                       ┌─────────────────────────┐
                       │      RentalService        │
                       │  - vehicleRepository       │
                       │  - reservationRepository   │
                       │  - locksByType: Map<Type,  │
                       │                   Lock>    │
                       │  + reserve(type, cust,     │
                       │            start, endTime) │
                       │  + cancel(reservationId)   │
                       │  + isAvailable(type, s, d) │
                       └─────────────────────────┘
```

### Responsibilities

| Class | Responsibility |
|---|---|
| `VehicleType` | Enum of SEDAN / SUV / VAN. No behavioral differences between types justify subclassing — a car's "type" is an attribute, not a different shape of object. |
| `Vehicle` | Identity + category of one physical car. Immutable value-ish entity. |
| `Reservation` | One booking: which vehicle, which customer, `[start, end)` interval, status. Owns the overlap predicate — the core domain rule lives next to the data it operates on. |
| `VehicleRepository` / `InMemoryVehicleRepository` | The finite fleet. Lookup by type. |
| `ReservationRepository` / `InMemoryReservationRepository` | Stores bookings; answers "does vehicle X have an active reservation overlapping [s, e)?" |
| `RentalService` | The only class that knows the business rule "to book a type, find a vehicle of that type with no overlapping active reservation, then create the reservation" — and the only class that takes locks. Everything above it is dumb storage. |

I deliberately did not create a `Customer` class (a bare `customerId: String` is enough for this exercise), and did not create three `Sedan`/`Suv`/`Van` subclasses, since nothing in the problem statement gives them distinct behavior.

## 3. Core business rule: overlap test

Reservation `A` occupies `[A.start, A.end)`, `B` occupies `[B.start, B.end)`.
They overlap iff:

```
A.start < B.end  AND  B.start < A.end
```

Half-open intervals (`end` excluded) are deliberate: a reservation ending at
10:00 on day 5 and another starting at 10:00 on day 5 for the *same vehicle*
do **not** conflict — the car can be returned and immediately re-rented. This
is exercised by `ReservationTest.adjacentIntervalsTouchingAtBoundaryDoNotOverlap`
and `RentalServiceTest.sameVehicleCanBeReusedOnceReservationPeriodEnds`.

Availability for a request is: **at least one vehicle of the requested type
has zero active reservations overlapping the requested interval.**

## 4. Sequence diagram — successful reservation

```
Customer -> RentalService: reserve(VAN, "cust-1", 2026-11-01T10:00, 2026-11-04T10:00)
RentalService -> RentalService: validate(type, customerId, start, endTime)
RentalService -> locksByType[VAN]: lock()
RentalService -> VehicleRepository: findByType(VAN)
VehicleRepository --> RentalService: [van-1]
loop for each candidate vehicle
    RentalService -> ReservationRepository: hasOverlap(van-1, start, end)
    ReservationRepository --> RentalService: false
end
RentalService -> Reservation: new Reservation(van-1, cust-1, start, end)
RentalService -> ReservationRepository: save(reservation)
RentalService -> locksByType[VAN]: unlock()
RentalService --> Customer: Reservation{id, van-1, CONFIRMED}
```

## 5. Sequence diagram — contended reservation (two customers, one van)

```
Thread A                          Thread B
  |                                  |
  | reserve(VAN, ...)                | reserve(VAN, ...)
  |--lock(VAN) [acquired]            |--lock(VAN) [BLOCKS]
  | findByType(VAN) -> [van-1]       |   (waiting)
  | hasOverlap(van-1,...) -> false   |   (waiting)
  | save(new Reservation(van-1))     |   (waiting)
  |--unlock(VAN)                     |--lock(VAN) [acquired]
  v                                  | findByType(VAN) -> [van-1]
 success                             | hasOverlap(van-1,...) -> true (sees A's booking)
                                      v
                                 NoVehicleAvailableException
```

Without the lock, both threads could pass `hasOverlap == false` before either
calls `save`, and both would book `van-1` for the same dates — the classic
check-then-act race. This exact scenario is the one the stress test
(`concurrentOverlappingRequestsForScarceVehicleYieldExactlyOneWinner`) drives
with 50 threads instead of 2.

## 6. Locking: what, where, and why

**Where locking is NOT needed:** pure read-only availability *browsing* that a
customer does before deciding what to book doesn't need to hold a lock — it's
inherently stale the instant another customer books concurrently, and the
`isAvailable` check is advisory only. The lock only has to protect the
*transition* from "decide which vehicle to assign" to "record that
assignment" — i.e. the critical section inside `reserve(...)`, not the whole
search-then-browse experience. `isAvailable` happens to take the same lock
here for a consistent read, but that's a convenience, not a requirement — a
stale read of availability is a UX nicety, not a correctness issue.

**Granularity: per vehicle-type, not global, not per-vehicle.**

- A single global lock would be simplest but serializes SEDAN bookings
  behind VAN bookings, with no gain in correctness — unnecessary contention.
- A per-vehicle lock looks finer-grained but doesn't actually work in this
  design, because *which* vehicle gets locked is literally the output of the
  `reserve` call — the race is over choosing from a set of candidates (any
  idle vehicle of that type qualifies), not over one specific row. You'd have
  to lock the *set* of same-type vehicles anyway to avoid two threads each
  grabbing a different candidate that then turns out to be the same one. So
  the natural unit of contention is "vehicles of this type," which is exactly
  `VehicleType`.
- Per-type is the sweet spot: concurrent bookings for *different* types never
  block each other (proven by `concurrentRequestsForDifferentTypesBothSucceed`),
  while bookings for the *same* type are serialized exactly where correctness
  requires it.

**Reentrancy:** `ReentrantLock` was chosen (over a plain `synchronized` block)
mainly for API clarity (`Lock` interface, explicit `lock()`/`unlock()` in a
`try/finally`) and because it would allow future extension — e.g. a
`tryLock(timeout)` to fail fast under load, or recursive calls from within
`RentalService` itself (e.g. a future `rebook` that cancels-then-reserves
under the same type's lock) without deadlocking on itself. Nothing in the
current code recurses, but `ReentrantLock` costs nothing extra and keeps that
door open. A plain `synchronized(lockObject)` would work identically for
today's requirements.

**Why lock in the service, not the repository:** `hasOverlap` and `save` are
each individually atomic at the storage layer (backed by
`ConcurrentHashMap`), but the business invariant ("no two active reservations
for one vehicle overlap") spans *two* repository calls plus a decision
between them. That invariant is a property of the use case, not of either
repository in isolation, so the lock belongs where the use case is
orchestrated — `RentalService` — not smeared across repository internals.

**What this doesn't solve:** this is an in-process lock; it would not
coordinate across multiple JVM instances / horizontally scaled service
replicas. A production version backed by a real database would instead rely
on a DB-level constraint (e.g. a unique constraint or `SELECT ... FOR UPDATE`
on overlap, or an exclusion constraint like Postgres's `EXCLUDE USING gist`
on a `tsrange`) as the actual source of truth, with the in-memory lock
(or no lock at all) only as an optimization to avoid hitting the DB with
doomed requests.

## 7. Validation and errors

`RentalService.reserve` validates before taking any lock: type and customer
id required, start required, `endTime` required and after `start`. Violations throw
`InvalidReservationException` (never silently coerced). No available vehicle
after a valid request throws `NoVehicleAvailableException`. Cancelling a
nonexistent reservation throws `ReservationNotFoundException`. All three are
unchecked — this is an interview-scope simulation, not a library with a
checked-exception contract.

## 8. Test coverage (`src/test/java`)

- `ReservationTest` — the overlap predicate itself: identical, partial-start,
  partial-end, boundary-touching (non-overlap), disjoint, invalid interval
  construction, cancel semantics.
- `RentalServiceTest`:
  - happy path booking, distinct vehicles for concurrent non-conflicting
    requests of the same type
  - fleet exhaustion for a given type/period (`NoVehicleAvailableException`)
  - reuse of a vehicle once its reservation period ends (boundary case)
  - rejection of a partially-overlapping request
  - cancellation frees the vehicle
  - `isAvailable` reflects bookings
  - input validation (type, customer id, start, endTime)
  - **concurrency**: 50 threads racing for the single VAN — exactly one wins
  - **concurrency**: different types don't block each other

Run with `mvn test` (JDK 17+, Maven 3.9+).

## 9. What I'd add for production (out of scope here)

- Persistence with DB-enforced overlap exclusion (see §6) instead of/in
  addition to the in-memory lock.
- Pricing, customer accounts, payment.
- Pagination/filtering on vehicle search once fleets are large.
- Idempotency keys on `reserve` for safe client retries.
