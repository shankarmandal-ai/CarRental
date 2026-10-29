package com.rental.repository;

import com.rental.model.Vehicle;
import com.rental.model.VehicleType;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository {
    void addVehicle(Vehicle vehicle);

    List<Vehicle> findByType(VehicleType type);

    Optional<Vehicle> findById(String vehicleId);

    List<Vehicle> findAll();
}
