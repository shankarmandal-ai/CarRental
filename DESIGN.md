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
                       ┌─────────┴────────────────────┐
                       │          Vehicle               │
                       │  id: String                    │
                       │  type: VehicleType              │
                       │  reservations: IntervalTree<Res> │
                       │  addReservation(r)              │
                       │  removeReservation(r)           │
                       │  hasOverlap(s, e)               │
                       └─────────▲────────────────────┘
                                 │ reserved as
                       ┌─────────┴──────────┐
                       │    Reservation      │
                       │  id: String         │
                       │  vehicleId: String  │
                       │  vehicleType         │
                       │  customerId: String │
                       │  start, end         │
                       │  status             │
                       │  overlaps(s, e)      │
                       └─────────▲──────────┘
                                 │ reads/writes
        ┌────────────────────────┴───────────────────────┐
        │                                                  │
┌───────┴────────────────┐                      ┌──────────┴───────────┐
│ VehicleRepository        │                      │ ReservationRepository │
│  (interface)             │                      │  (interface)          │
│  findByType(type) [O(1)] │                      │  hasOverlap(id,s,e)   │
│  findById(id)            │                      │  save(r) / cancel(id) │
│  addVehicle(v)            │                      │                       │
└───────▲──────────────────┘                      └───────────▲───────────┘
        │ implements                                           │ implements
┌───────┴──────────────┐                          ┌────────────┴───────────┐
│InMemoryVehicleRepo    │◄─────────────────────────│InMemoryReservationRepo  │
│ vehiclesByType: Map    │   resolves Vehicle by id │ (delegates tree ops    │
│ vehiclesById: Map      │                          │  to Vehicle itself)    │
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
| `Vehicle` | Identity + category of one physical car, **and** that car's own reservation history, held as its own `IntervalTree<Reservation>` (see §7). A vehicle's bookings are intrinsically its own state, not a cross-cutting index owned by a separate repository. |
| `Reservation` | One booking: which vehicle (id + type), which customer, `[start, end)` interval, status. Owns the overlap predicate — the core domain rule lives next to the data it operates on. |
| `VehicleRepository` / `InMemoryVehicleRepository` | The finite fleet: identity and type lookup only, grouped by type at insertion time so `findByType` is `O(1)` instead of scanning every vehicle on each call. Knows nothing about bookings. |
| `ReservationRepository` / `InMemoryReservationRepository` | The master list of reservations by id, for `findById`/`findAll`/audit purposes. For overlap checks and mutating a vehicle's booking state, it resolves the `Vehicle` via `VehicleRepository` and delegates to that vehicle's own tree — it does not keep a second parallel index of bookings per vehicle. |
| `RentalService` | The only class that knows the business rule "to book a type, find a vehicle of that type with no overlapping active reservation, then create the reservation" — and the only class that takes locks. |

I deliberately did not create a `Customer` class (a bare `customerId: String` is enough for this exercise), and did not create three `Sedan`/`Suv`/`Van` subclasses, since nothing in the problem statement gives them distinct behavior.

### Why `Vehicle` owns its own reservation tree, not a separate repository-side map

An earlier version kept `Vehicle` as a small immutable value (id + type) and had
`InMemoryReservationRepository` maintain a second map, `Map<String,
IntervalTree<Reservation>>`, keyed by vehicle id, mirroring the vehicle roster. That
meant two structures — `VehicleRepository`'s roster and `ReservationRepository`'s
per-vehicle tree map — had to stay in sync by vehicle id with no enforced
relationship between them.

Moving the tree onto `Vehicle` itself removes that duplication: a vehicle's identity
and its booking history are now one object, reached through one lookup
(`vehicleRepository.findById(id)`) instead of two parallel maps. The tradeoff,
accepted deliberately: `Vehicle` is no longer a pure immutable value — it now carries
mutable, frequently-changing state (its tree), so handing out `Vehicle` references
outside of `RentalService`'s per-type lock is no longer as inherently safe as handing
out plain id+type data would be. For this exercise's scope, that's an acceptable
trade for removing a redundant, independently-maintained index.

An alternative floated and rejected during design: giving every vehicle a sentinel
"free" reservation at a far-future placeholder date (e.g. year 7026) so that a single
shared interval structure per type could double as both the fleet roster and the
booking index. Rejected because (a) a shared tree's overlap hit on one busy vehicle
doesn't tell you anything about whether a *different* vehicle of that type is free,
so it can't actually answer "is any vehicle of this type available" without still
iterating vehicles individually, and (b) it mixes fictitious placeholder data into a
structure meant to hold only real reservations, requiring every consumer (overlap
queries, `findAll`, cancellation) to know to filter it out — the same contamination
risk that motivated keeping `VehicleRepository` and `ReservationRepository` as
separate concerns in the first place.

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

**Where locking is NOT needed:** `isAvailable` does not take the per-type
lock. It is a pure read-only query — "is there a free vehicle of this type
right now" — used for browsing before a customer decides what to book. The
lock only has to protect the *transition* from "decide which vehicle to
assign" to "record that assignment," i.e. the critical section inside
`reserve(...)`; it buys nothing for a call that doesn't write.

This was a deliberate choice, not an oversight, and it has a real
consequence worth stating plainly: **the boolean `isAvailable` returns can
be stale by the time the caller acts on it.** Thread A can call
`isAvailable(VAN, ...)`, get `true`, and before it calls `reserve`, Thread B
can book the last VAN — A's `reserve` call then throws
`NoVehicleAvailableException` despite the "available" answer moments
earlier. This is the same TOCTOU (time-of-check to time-of-use) gap that the
lock in `reserve` exists to close for the *write* path, and taking the lock
in `isAvailable` would not close it for the *read-then-write* path either,
because the lock is released before the caller gets the boolean back — there
is no way for a separate, later `reserve()` call to observe the same
lock-protected critical section unless it's literally the same critical
section (i.e. unless `isAvailable` and `reserve` were fused into one locked
call). So locking `isAvailable` would add lock contention (now competing
with real bookings of the same type) while providing zero additional
correctness guarantee to callers who check-then-book across two calls.

The right way to treat `isAvailable` is as what its doc comment says: a UX
hint for building a search/browse screen, never a precondition for
`reserve`. The correct pattern for "try to book" is to call `reserve`
directly and handle `NoVehicleAvailableException`, since that call alone
holds the lock across the only check that matters.

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

### Per-type vs. per-vehicle locking, revisited

Per-vehicle locking is viable and strictly finer-grained than the per-type
lock used here, *provided* the search loop never holds more than one
vehicle's lock at a time: `tryLock` each candidate in turn, check its
overlap under that lock, release it immediately whether or not it matched,
and move to the next candidate on failure. Holding multiple candidate locks
simultaneously while probing them is what creates lock-ordering deadlocks —
but that's a self-inflicted implementation hazard, not an inherent property
of per-vehicle locking. Per-type remains the simpler choice for this
exercise's scale (a handful of vehicles per type, where contention is
already low), but per-vehicle would give better throughput as fleet size per
type grows, since it only serializes requests that actually contend for the
*same* car rather than all requests for the *same type*.

### Why `isAvailable` does not take the lock

`isAvailable` is a pure read with no accompanying write, so taking the
per-type lock around it buys nothing: the boolean it returns is stale the
instant another thread books concurrently, regardless of whether the read
itself was lock-protected. Locking it would only add contention with real
`reserve` calls for no correctness gain. The consequence is explicit and
accepted: a caller that does `isAvailable()` then `reserve()` can still get
`NoVehicleAvailableException` despite having just seen "available" — this is
the same TOCTOU gap that `reserve`'s own lock closes for the write path, and
it cannot be closed across two separate calls without fusing them into one
locked operation (which `reserve` already is). `isAvailable` is documented
as advisory-only; the correct pattern for "try to book" is calling `reserve`
directly and handling the exception, never pre-checking with `isAvailable`.

### Why `ReadWriteLock` doesn't fit here

A `ReadWriteLock` optimizes for workloads where many pure reads can overlap
safely and only writes need exclusivity. `reserve`'s critical section isn't
a pure read followed by a separate write — it's a read-modify-write, where
the read's result (is there an unoverlapped vehicle) is only valid if
nothing else can write between the read and the write. `ReentrantReadWriteLock`
doesn't support upgrading a held read lock to a write lock without releasing
it first, which reopens exactly the race window the lock exists to close. So
using it for `reserve` would mean always taking the write lock anyway,
gaining nothing over a plain `ReentrantLock`. The one place a pure-reader
workload exists in this design (`isAvailable`) doesn't need stronger
consistency than `ConcurrentHashMap` already provides for free, so there's
no remaining read path that benefits from `ReadWriteLock`.

## 7. Scaling the overlap query: `IntervalTree`

`ReservationRepository.hasOverlap` is called once per fleet candidate inside
`RentalService.findAvailableVehicle`'s loop, so its own cost compounds with
fleet size. An early version scanned every reservation in the whole system
and filtered by vehicle ID and overlap on each call — `O(V × R)` for `V`
candidates and `R` total reservations, with `R` growing unboundedly over the
system's lifetime.

Each `Vehicle` now owns one `com.rental.datastructure.IntervalTree` of its
own reservations instead: an augmented AVL tree keyed by `start`, where
every node also stores `maxEnd` — the largest `end` value anywhere in its
subtree. During a search, a subtree is pruned without being visited
whenever its `maxEnd` cannot reach the query's `start`, which is what makes
`hasOverlap` and `findOverlapping` run in `O(log r)` against that vehicle's
own reservation count `r`, instead of scanning all of them, and independent
of total system-wide reservation count `R`. Insert and remove are also
`O(log r)`, via standard AVL rotations with `maxEnd` recomputed bottom-up
along the rotation path. `ReservationRepository.hasOverlap(vehicleId, ...)`
resolves the vehicle via `VehicleRepository` and delegates straight to it.

Cancelling a reservation (`ReservationRepository.cancel`) removes it from
its vehicle's own tree so a cancelled booking stops contributing to future
overlap checks, rather than leaving a stale "cancelled but still indexed"
entry that would require every read to re-check `isActive()`.

A B-tree/B+tree was considered and rejected: those structures optimize for
minimizing *disk page reads*, which is irrelevant for an all-in-memory
system — the thing that matters here is comparison count and tree height,
which a balanced in-memory tree already minimizes without any disk-oriented
machinery. A plain `TreeMap<start, Reservation>` per vehicle was also
considered; it gives `O(log r)` navigation but the overlap check itself
still has to walk every entry in `headMap(end)` one at a time, which can be
`O(r)` in the worst case regardless of how few of those entries actually
overlap. The `maxEnd` augmentation is what gives the interval tree true
`O(log r)` worst-case overlap queries regardless of booking density,
independent of how many reservations happen to start before the query's
`end`.

`IntervalTree` is deliberately not thread-safe on its own — like a plain
`TreeMap` would be, it relies on the caller (`RentalService`'s per-type
lock) to serialize access, since a concurrent search and mutation of the
same AVL tree could observe a tree mid-rotation.

`IntervalTreeTest` includes a randomized cross-check (5,000 mixed
insert/remove/query operations) comparing the tree's overlap answers
against a brute-force linear scan over the same live intervals, to verify
the AVL rotations and `maxEnd` augmentation stay correct under arbitrary
mutation sequences, not just the hand-picked scenarios.

## 8. Persistence: `FleetSnapshot`

`com.rental.persistence.FleetSnapshot` saves and loads the fleet and its
active bookings as a flat CSV, one row per active reservation (or one bare
row for a vehicle with zero bookings, so it isn't lost):

```
vehicleId,type,customerId,start,end     (one row per active reservation)
vehicleId,type                          (a vehicle with zero bookings)
```

Only `CONFIRMED` reservations are persisted; cancelled ones are dropped on
save and simply don't exist after reload — there's no cancellation history
across a restart with this format. Reservation ids aren't persisted either,
since nothing in this snapshot format depends on the original id; a
reloaded reservation gets a freshly generated id.

**Nothing about `IntervalTree`'s internal structure (node layout, AVL
balance) is ever serialized.** The tree is a derived index, not source
data: on `load`, each row is replayed through the exact same `Vehicle.addVehicle`
/ `ReservationRepository.save` path that a live `reserve()` call uses, which
rebuilds each vehicle's tree identically from the flat reservation list.
This is deliberate — serializing tree internals directly would be fragile
across any future change to `IntervalTree`'s shape and wouldn't buy
anything, since rebuilding from the flat list is already `O(r log r)` per
vehicle and runs once at startup.

`Main` wires this at the process boundary: it constructs `RentalService`
immediately, then loads the snapshot (if the file exists; a missing file is
a no-op, not an error) on a separate thread so startup isn't blocked on
disk I/O, and registers a JVM shutdown hook that saves current state back
to the same path on exit — including `Ctrl+C`/`SIGTERM`, not just a normal
return from `main`. This is a best-effort save, not a transaction log: a
hard kill (`kill -9`) or a crash between bookings and the next save loses
whatever happened since the last successful save, which is acceptable for
this exercise but would need a real write-ahead approach (e.g. append each
booking/cancellation as it happens, rather than snapshotting the whole
fleet at exit) for anything production-grade — see §12.

## 9. Startup gate: `CountDownLatch`

Loading the snapshot asynchronously (§8) opens a real race: `RentalService`
is reachable the instant it's constructed, but the fleet it operates on is
still being populated on another thread. A `reserve`/`cancel`/`isAvailable`
call that lands during that window would see a partially-loaded (or
entirely empty) fleet — not a corrupted one, since `VehicleRepository` and
`ReservationRepository` are internally thread-safe for individual
operations, but a *semantically wrong* one: a customer could get
`NoVehicleAvailableException` for a van that's actually in the snapshot but
just hasn't been read yet.

`RentalService` holds a `CountDownLatch readyLatch` initialized to `1`.
Every public operation calls `readyLatch.await()` as its first step, before
validation or locking. `Main`'s loader thread calls
`rentalService.markReady()` only after `FleetSnapshot.load` returns, which
counts the latch down to zero and permanently releases every blocked (and
every future) caller. `CountDownLatch` fits this specifically because it's
a one-shot gate — it can't be reset or re-closed, which matches "not ready"
→ "ready" being a single, irreversible phase transition for the lifetime of
one `RentalService` instance. It would be the wrong tool for a *recurring*
pause (e.g. "block new reservations during a maintenance window, then allow
them again") since that needs a gate that can reopen and re-close, which a
`CountDownLatch` structurally cannot do — that would call for something
like a toggled flag checked under a lock, or a fresh latch created per
pause window.

`RentalServiceTest.operationsBlockUntilMarkReadyIsCalled` proves the gate
actually blocks: it starts a caller thread against a fresh, not-yet-ready
`RentalService`, confirms the call is still blocked after a short wait,
calls `markReady()`, and confirms the call then completes and returns the
expected result — not just that the API compiles, but that a concurrent
caller genuinely stalls and is genuinely released.

## 10. Validation and errors


`RentalService.reserve` validates before taking any lock: type and customer
id required, start required, `endTime` required and after `start`. Violations throw
`InvalidReservationException` (never silently coerced). No available vehicle
after a valid request throws `NoVehicleAvailableException`. Cancelling a
nonexistent reservation throws `ReservationNotFoundException`. All three are
unchecked — this is an interview-scope simulation, not a library with a
checked-exception contract.

## 11. Test coverage (`src/test/java`)

- `ReservationTest` — the overlap predicate itself: identical, partial-start,
  partial-end, boundary-touching (non-overlap), disjoint, invalid interval
  construction, cancel semantics.
- `IntervalTreeTest` — insert/remove/overlap/findOverlapping correctness,
  half-open boundary semantics, duplicate-id rejection, and a 5,000-iteration
  randomized cross-check against brute-force linear scan.
- `FleetSnapshotTest` — round-trips vehicles and active bookings through a
  CSV file and rebuilds a working overlap index (not just flat records);
  cancelled reservations are confirmed absent after reload; a vehicle with
  no bookings survives as a bare row; loading a missing file is a no-op.
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
  - **concurrency**: a caller blocks on `reserve`/`isAvailable`/`cancel`
    until `markReady()` is called, then completes correctly once released

Run with `mvn test` (JDK 17+, Maven 3.9+).

## 12. Multi-terminal access: `RentalCommandServer`

`com.rental.server.RentalCommandServer` is a plain-text line protocol over
TCP, letting several terminals issue `RESERVE`/`CANCEL`/`AVAILABLE`/`QUIT`
commands against one running process. Each accepted connection is handled
on its own thread from a cached pool, but all connections call into the
exact same `RentalService` instance — concurrency safety comes entirely
from `RentalService`'s own per-type locks (§6) and startup latch (§9); the
server adds no locking of its own, because it doesn't need to; the
invariant it depends on already lives at the right layer.

`Main` starts the server after constructing `RentalService` and keeps the
process alive (`Thread.currentThread().join()`) until killed, at which
point the same shutdown hook used for persistence (§8) saves current state.
This is intentionally a minimal line protocol (no framing beyond newlines,
no auth, no reconnect/backoff) — adequate for demonstrating that the
service is safe under concurrent multi-client access, not a production
wire protocol.

## 13. What I'd add for production (out of scope here)

- A real write-ahead persistence mechanism (append each booking/cancellation
  as it happens) instead of a best-effort snapshot-on-exit (see §8), plus
  DB-enforced overlap exclusion (see §6) instead of/in addition to the
  in-memory lock.
- Pricing, customer accounts, payment.
- Pagination/filtering on vehicle search once fleets are large.
- Idempotency keys on `reserve` for safe client retries.
- A real wire protocol (HTTP/gRPC, auth, structured errors) instead of the
  plain-text TCP line protocol in §12.
