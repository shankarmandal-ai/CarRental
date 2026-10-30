# Car Rental System — Design

## 1. Requirements, constraints, assumptions

**Requirement:** reserve a car of a given type (SEDAN / SUV / VAN) for a
`[start, end)` interval against a fleet with a fixed number of cars per type,
without ever double-booking a single physical car. Support cancellation and
an availability check.

**Constraints:**
- Fleet size per type is fixed and small-to-moderate (not web-scale).
- Must be correct under concurrent requests — multiple customers can race
  for the same scarce vehicle type.
- Must survive a process restart without losing the fleet roster or active
  bookings.
- Must be usable from more than one terminal/client against one running
  instance.

**Assumptions:**
- No `Customer` entity is needed — a bare `customerId: String` is enough.
- No pricing, payment, or auth — out of scope for this exercise.
- A single-process, single-JVM deployment is acceptable; no requirement to
  coordinate across multiple instances or machines.
- Intervals are half-open, `[start, end)`: a reservation ending at 10:00 and
  another starting at 10:00 for the *same* vehicle do not conflict (the car
  can be returned and immediately re-rented).

## 2. Program structure

```
model          Vehicle, VehicleType, Reservation, ReservationStatus
datastructure  IntervalTree — augmented AVL tree behind overlap queries
repository     VehicleRepository, ReservationRepository (+ in-memory impls)
persistence    FleetSnapshot — CSV save/load
server         RentalCommandServer — TCP line protocol
service        RentalService — the orchestrator and lock owner
Main.java      entry point
```

`RentalService` is the only class that knows the business rule ("find a
vehicle of the requested type with no overlapping active reservation, then
create the reservation") and the only class that takes locks.

**The fleet inventory and all active reservations live in a flat file**
(`fleet.csv` by default), not just in memory. On startup, `Main` constructs
`RentalService` immediately, then loads that file on a background thread;
`RentalService` blocks any `reserve`/`cancel`/`isAvailable` call until the
load finishes (§6). A JVM shutdown hook writes current state back to the
same file on exit, including `Ctrl+C`. One row per active reservation
(`vehicleId,type,reservationId,customerId,start,end`), or a bare
`vehicleId,type` row for an unbooked vehicle so it isn't lost. Only
`CONFIRMED` reservations are persisted — cancelled ones are simply dropped.
The reservation id **is** persisted and restored as-is: a client that
booked before a restart must still be able to `CANCEL` with the same id
afterward, so reload reconstructs each reservation with its original id
rather than minting a new one.

A TCP command server (`RentalCommandServer`) lets several terminals connect
at once and issue `RESERVE`/`CANCEL`/`AVAILABLE`/`QUIT` commands against that
one shared `RentalService` instance — each connection runs on its own thread,
but all of them call into the same service object, so the service's own
locking (not the server) is what keeps concurrent clients safe.

## 3. Why `Vehicle` owns its reservation index, not a separate repository map

`Vehicle` holds its own `IntervalTree<Reservation>` — one tree per vehicle.
Two other options were considered.

**A — per-vehicle tree, but owned by the repository**, not `Vehicle`: a
`Map<String, IntervalTree<Reservation>>` keyed by vehicle id, kept separate
from the vehicle roster.
- Pro: `Vehicle` stays a plain, immutable value.
- Con: two structures (roster, tree map) must stay in sync by id, with
  nothing enforcing that; every operation needs two lookups instead of one.

**B — one shared tree per `VehicleType`** (3 trees total), nodes tagged by
vehicle id.
- Pro: fewer tree instances overall.
- Con: a query always targets one candidate vehicle, not "the whole type" —
  a hit from one busy vehicle says nothing about another vehicle of the
  same type being free. A shared tree would still need per-vehicle
  filtering, so it adds bookkeeping without removing any work. It also
  makes the tree grow with the whole type's reservation count instead of
  one vehicle's, weakening the `O(log r)` benefit the tree exists for.

**Chosen: one tree per vehicle, owned by `Vehicle` itself.** Same
per-vehicle query shape as option A, but a vehicle's identity and its
booking history are now one object, reached with one lookup
(`vehicleRepository.findById(id)`) instead of two structures that can
drift apart. `ReservationRepository` just resolves the `Vehicle` and
delegates to it.

## 4. What `IntervalTree` is

`IntervalTree` is the index behind one vehicle's reservations. It's an
augmented, balanced AVL tree keyed by reservation `start` time, with each
node also storing `maxEnd` — the largest `end` anywhere in its subtree.
That augmentation lets an overlap search prune whole subtrees it can prove
can't match, so checking a vehicle's availability is `O(log r)` against
that vehicle's own reservation count `r`, instead of `O(r)` from checking
every reservation one by one.

### Why `IntervalTree` is indexed per vehicle, not per `VehicleType`

Each `Vehicle` has its own tree of only its own reservations, rather than one
shared tree per type holding every reservation for every vehicle of that
type.

A query is never "does *any* reservation of this type overlap this
interval" — it's "does *this specific candidate vehicle's* reservation
history overlap this interval," asked once per candidate while searching for
an open vehicle. A single tree shared across all vehicles of a type would
need every node tagged with its vehicle id, and an overlap hit on one busy
vehicle would say nothing about whether a *different* vehicle of that type
is free — the search would still have to walk per-vehicle regardless. A tree
per vehicle matches the actual query shape exactly, keeps each tree small
(bounded by one vehicle's booking count, not the whole fleet's), and keeps
insert/remove/overlap all `O(log r)` against that vehicle's own reservation
count `r` — independent of total system-wide reservation count.

A plain `TreeMap<start, Reservation>` per vehicle was considered first: it
gives `O(log r)` navigation, but the overlap check itself still has to walk
every entry one at a time, which is `O(r)` in the worst
case. The `maxEnd` augmentation (the largest `end` anywhere in a subtree) is
what lets a search prune whole subtrees it can prove can't overlap, giving
true `O(log r)` worst-case overlap queries. Insert's placement descends
top-down by `start`, and since max is monotonic under addition, an insert
alone could in principle update each ancestor's `maxEnd` to
`max(old, newEnd)` on the same top-down pass. Remove can't take that
shortcut — removing a value can shrink a subtree's true max, which can only
be known by looking at what's still there — and any AVL rotation (triggered
by either insert or remove) moves nodes between subtrees, invalidating
`maxEnd` values computed against the pre-rotation shape. So `maxEnd` is
recomputed bottom-up, from children up to the root, along the
remove/rotation path after both operations, rather than relying on a
top-down shortcut that would only apply to insert's non-rotating case.

## 5. Why `ConcurrentHashMap` (and `CopyOnWriteArrayList`)

Every shared, id-keyed or type-keyed map in this system —
`InMemoryVehicleRepository.vehiclesById`/`vehiclesByType`,
`InMemoryReservationRepository.reservationsById`,
`RentalService.locksByVehicle`, and `RentalService.freeVehiclesByType` — is
a `ConcurrentHashMap`. These maps are read far more often than written
(every `reserve`/`cancel`/`isAvailable` call reads one; only fleet setup
and bookings write), and `ConcurrentHashMap` gives thread-safe reads and
writes without forcing a single coarse lock around every map access, which
would otherwise become its own bottleneck independent of the per-vehicle
booking locks (§6).

`vehiclesByType`'s values are `CopyOnWriteArrayList<Vehicle>`, not plain
`ArrayList`. `ConcurrentHashMap.computeIfAbsent` only makes *creating* the
per-type list atomic — it doesn't make the subsequent `.add()` onto that
list safe against another thread doing the same concurrently. Vehicle lists
are written rarely (fleet construction, or an occasional fleet-reload) and
read constantly (every `findByType` call), which is exactly the access
pattern `CopyOnWriteArrayList` is for.

## 6. Locking: what, where, and why

**Granularity: per `Vehicle`, not global, not per-`VehicleType`.**
`RentalService` holds one `Lock` per vehicle id (`locksByVehicle`, a
`ConcurrentHashMap<String, Lock>` populated lazily via `computeIfAbsent`
and never evicted — removing an "unused" entry would race against a thread
that already read the old `Lock` reference and is about to call `lock()`
on it, so one `ReentrantLock` per vehicle is kept forever; a trivial memory
cost for any realistic fleet size). `reserve` locks exactly one candidate
vehicle at a time, re-checks its availability under that lock, and commits
only if it's still free — a double-checked pattern, never holding more
than one vehicle's lock at once, so it cannot deadlock on lock ordering.

- A single global lock would serialize SEDAN bookings behind VAN bookings,
  with no gain in correctness — unnecessary contention.
- A per-`VehicleType` lock (one lock per type, three total) was the first
  design and is still correct, but it over-serializes: the actual race is
  only ever over *one specific car*, not "all cars of this type." Two
  requests for the same type that would always have landed on two
  *different* idle vehicles still blocked each other under a per-type
  lock, for no correctness reason — confirmed by
  `concurrentRequestsForSameTypeOnDifferentVehiclesBothSucceed`, which
  fails under per-type locking and passes under per-vehicle locking.
- Per-vehicle is the correct unit of contention: concurrent bookings for
  *different* vehicles — whether different types or different vehicles of
  the *same* type — never block each other, while requests genuinely
  racing for the *same* vehicle are serialized exactly where correctness
  requires it (`concurrentOverlappingRequestsForScarceVehicleYieldExactlyOneWinner`,
  50 threads racing for one van, exactly one wins).

**Sequence — two customers racing for the same van (`van-1` is the only VAN):**

```
Thread A                              Thread B
  | reserve(VAN, custA, ...)            | reserve(VAN, custB, ...)
  | tryReserveVehicle(van-1)            | tryReserveVehicle(van-1)
  |--lock(van-1) [acquired]             |--lock(van-1) [BLOCKS]
  | van-1.hasOverlap? -> false          |   (waiting)
  | save(new Reservation(van-1))        |   (waiting)
  | freeVehiclesOf(VAN).remove(van-1)   |   (waiting)
  |--unlock(van-1)                      |--lock(van-1) [acquired]
  v                                     | van-1.hasOverlap? -> true (sees A's booking)
 success                                |--unlock(van-1)
                                        v
                                   null -> next candidate -> none left
                                   -> NoVehicleAvailableException
```

At most one vehicle's lock is ever held at a time, by either thread, so this
can't deadlock; the recheck inside the lock (not the earlier unlocked hint)
is what makes exactly one winner possible.

**Why this doesn't need a `tryLock`-and-retry loop to avoid deadlock:**
`reserve` never holds two vehicle locks at the same time. It takes one
candidate's lock, decides (commit or release), then moves to the next
candidate only after releasing the first. Since no thread ever holds more
than one lock in this family simultaneously, there is no lock-ordering
cycle possible between threads — deadlock is structurally ruled out, not
just avoided by convention.

**Why a lock pool instead of locking a `Vehicle` object directly:**
`locksByVehicle` is keyed by vehicle id rather than synchronizing on the
`Vehicle` instance itself, so the locking strategy stays decoupled from
`Vehicle`'s own identity/equality and from whatever object graph holds it
— a vehicle can be looked up, replaced, or reconstructed without touching
how it's locked, as long as its id is stable.

**`freeVehiclesByType` as an unlocked first try:** an O(1) lookup for a
vehicle with zero reservations — free for any interval — skips the O(V)
fleet scan in the common case. 

**Why `isAvailable` doesn't take any lock:** it's a pure read with no
accompanying write. A caller doing
`isAvailable()` then `reserve()` can still get `NoVehicleAvailableException`
despite having just seen "available." `isAvailable` is
advisory-only, for browsing.

**Why lock in the service, not the repository:** `hasOverlap` and `save`
are each individually atomic at the storage layer, but the invariant ("no
two active reservations for one vehicle overlap") spans *two* repository
calls plus a decision between them. That invariant belongs to the use case,
not to either repository in isolation, so the lock lives where the use case
is orchestrated.

**What this doesn't solve:** this is an in-process lock — it would not
coordinate across multiple JVM instances. 

## 7. Startup gate: `CountDownLatch`

Loading the snapshot asynchronously (§2) opens a race: `RentalService` is
reachable the instant it's constructed, but the fleet is still being
populated on another thread. `RentalService` holds a `CountDownLatch
readyLatch` initialized to `1`; every public operation calls
`readyLatch.await()` first. `Main`'s loader thread calls
`rentalService.markReady()` only after the snapshot load returns, which
counts the latch to zero and permanently releases every blocked (and every
future) caller.

`CountDownLatch` fits specifically because "not ready → ready" is a one-shot,
irreversible transition for the lifetime of one `RentalService` instance — it
can't be reset or re-closed. It would be the wrong tool for a *recurring*
pause (e.g. blocking new reservations during a maintenance window, then
reopening), since that needs a gate that can re-close, which a
`CountDownLatch` structurally cannot do.

## 8. Test coverage (`src/test/java`)

- `ReservationTest` — the overlap predicate: identical, partial-start,
  partial-end, boundary-touching (non-overlap), disjoint, invalid interval,
  cancel semantics.
- `IntervalTreeTest` — insert/remove/overlap/findOverlapping correctness,
  half-open boundary semantics, duplicate-id rejection, and a 5,000-iteration
  randomized cross-check against brute-force linear scan.
- `FleetSnapshotTest` — round-trips vehicles and active bookings through a
  CSV file and rebuilds a working overlap index; cancelled reservations are
  confirmed absent after reload; a vehicle with no bookings survives as a
  bare row; loading a missing file is a no-op.
- `RentalServiceTest` — happy path, fleet exhaustion, boundary reuse,
  partial-overlap rejection, cancellation frees the vehicle, `isAvailable`
  correctness, input validation, plus concurrency: 50 threads racing for one
  van (exactly one wins), different types never blocking each other, and
  callers blocking on the startup latch until `markReady()` releases them.

Run with `mvn test` (JDK 17+, Maven 3.9+).

