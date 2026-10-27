package com.rental.repository;

import com.rental.model.Vehicle;
import com.rental.model.VehicleType;

import java.util.List;

public interface VehicleRepository {
    void addVehicle(Vehicle vehicle);

    List<Vehicle> findByType(VehicleType type);

    List<Vehicle> findAll();
}
