package com.parkease;

public enum VehicleType {
    BIKE(1, 10.00), CAR(1, 30.00), TRUCK(2, 60.00);

    private final int slotsRequired;
    private final double defaultRate;

    VehicleType(int slotsRequired, double defaultRate) {
        this.slotsRequired = slotsRequired;
        this.defaultRate = defaultRate;
    }

    public int slotsRequired() { return slotsRequired; }

    public double hourlyRate() {
        try {
            String propKey = "rate." + name().toLowerCase();
            String val = DBConnection.getSetting(propKey, null);
            if (val != null && !val.isBlank()) {
                double parsed = Double.parseDouble(val.trim());
                if (parsed > 0) return parsed;
            }
        } catch (Exception ignored) {}
        return defaultRate;
    }

    public double defaultRate() { return defaultRate; }

    public static VehicleType fromChoice(int choice) {
        return switch (choice) {
            case 1 -> BIKE;
            case 2 -> CAR;
            case 3 -> TRUCK;
            default -> throw new IllegalArgumentException("Choose 1, 2, or 3.");
        };
    }
}
