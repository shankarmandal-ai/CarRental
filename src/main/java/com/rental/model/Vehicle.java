package com.rental.model;

import java.util.Objects;

public final class Vehicle {
    private final String id;
    private final VehicleType type;

    public Vehicle(String id, VehicleType type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String getId() {
        return id;
    }

    public VehicleType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vehicle)) return false;
        return id.equals(((Vehicle) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Vehicle{id='" + id + "', type=" + type + "}";
    }
}
