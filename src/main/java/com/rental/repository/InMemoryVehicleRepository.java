package com.rental.repository;

import com.rental.model.Vehicle;
import com.rental.model.VehicleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVehicleRepository implements VehicleRepository {
    private final Map<String, Vehicle> vehiclesById = new ConcurrentHashMap<>();

    @Override
    public void addVehicle(Vehicle vehicle) {
        vehiclesById.put(vehicle.getId(), vehicle);
    }

    @Override
    public List<Vehicle> findByType(VehicleType type) {
        List<Vehicle> result = new ArrayList<>();
        for (Vehicle v : vehiclesById.values()) {
            if (v.getType() == type) {
                result.add(v);
            }
        }
        return result;
    }

    @Override
    public List<Vehicle> findAll() {
        return new ArrayList<>(vehiclesById.values());
    }
}
