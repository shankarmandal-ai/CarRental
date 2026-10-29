package com.rental.repository;

import com.rental.model.Vehicle;
import com.rental.model.VehicleType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vehicles are grouped by type at insertion time, so findByType is an O(1) map lookup
 * instead of scanning the whole fleet on every call. The per-type lists are
 * CopyOnWriteArrayList: fleet composition is written rarely and read constantly, which is
 * exactly the access pattern that type favors — findByType stays lock-free, and addVehicle
 * is safe under concurrent calls without any extra synchronization.
 */
public class InMemoryVehicleRepository implements VehicleRepository {
    private final Map<String, Vehicle> vehiclesById = new ConcurrentHashMap<>();
    private final Map<VehicleType, List<Vehicle>> vehiclesByType = new ConcurrentHashMap<>();

    @Override
    public void addVehicle(Vehicle vehicle) {
        vehiclesById.put(vehicle.getId(), vehicle);
        vehiclesByType
                .computeIfAbsent(vehicle.getType(), type -> new CopyOnWriteArrayList<>())
                .add(vehicle);
    }

    @Override
    public List<Vehicle> findByType(VehicleType type) {
        return Collections.unmodifiableList(vehiclesByType.getOrDefault(type, List.of()));
    }

    @Override
    public Optional<Vehicle> findById(String vehicleId) {
        return Optional.ofNullable(vehiclesById.get(vehicleId));
    }

    @Override
    public List<Vehicle> findAll() {
        return new ArrayList<>(vehiclesById.values());
    }
}
