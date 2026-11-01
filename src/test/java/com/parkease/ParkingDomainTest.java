package com.parkease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParkingDomainTest {
    private final ParkingService parkingService = new ParkingService();

    @Test
    void vehicleTypesExposeTheirParkingRequirementsAndRates() {
        assertEquals(1, VehicleType.BIKE.slotsRequired());
        assertEquals(10.00, VehicleType.BIKE.hourlyRate());
        assertEquals(1, VehicleType.CAR.slotsRequired());
        assertEquals(30.00, VehicleType.CAR.hourlyRate());
        assertEquals(2, VehicleType.TRUCK.slotsRequired());
        assertEquals(60.00, VehicleType.TRUCK.hourlyRate());
    }

    @Test
    void fromChoiceMapsSupportedChoices() {
        assertEquals(VehicleType.BIKE, VehicleType.fromChoice(1));
        assertEquals(VehicleType.CAR, VehicleType.fromChoice(2));
        assertEquals(VehicleType.TRUCK, VehicleType.fromChoice(3));
    }

    @Test
    void fromChoiceRejectsUnsupportedChoices() {
        assertThrows(IllegalArgumentException.class, () -> VehicleType.fromChoice(0));
        assertThrows(IllegalArgumentException.class, () -> VehicleType.fromChoice(4));
    }

    @Test
    void parkRejectsMissingRegistrationBeforeConnectingToDatabase() {
        assertThrows(IllegalArgumentException.class, () -> parkingService.park(null, VehicleType.CAR));
        assertThrows(IllegalArgumentException.class, () -> parkingService.park("   ", VehicleType.CAR));
    }

    @Test
    void parkRejectsMalformedRegistrationBeforeConnectingToDatabase() {
        assertThrows(IllegalArgumentException.class, () -> parkingService.park("ABC", VehicleType.CAR));
        assertThrows(IllegalArgumentException.class, () -> parkingService.park("AB#123", VehicleType.CAR));
        assertThrows(IllegalArgumentException.class, () -> parkingService.park("A".repeat(25), VehicleType.CAR));
    }

    @Test
    void normalizeNormalizesCaseAndWhitespace() {
        assertEquals("KA 01 AB 1234", parkingService.normalize("  ka  01  ab  1234 "));
        assertEquals("MH-12-CD-9999", parkingService.normalize("mh-12-cd-9999"));
    }

    @Test
    void calculateFeeComputesAmountBasedOnVehicleTypeAndHours() {
        assertEquals(new BigDecimal("10.00"), parkingService.calculateFee(VehicleType.BIKE, 1));
        assertEquals(new BigDecimal("20.00"), parkingService.calculateFee(VehicleType.BIKE, 2));
        assertEquals(new BigDecimal("60.00"), parkingService.calculateFee(VehicleType.CAR, 2));
        assertEquals(new BigDecimal("75.00"), parkingService.calculateFee(VehicleType.CAR, 2.5));
        assertEquals(new BigDecimal("180.00"), parkingService.calculateFee(VehicleType.TRUCK, 3));
    }

    @Test
    void calculateFeeRejectsNonPositiveHoursOrNullType() {
        assertThrows(IllegalArgumentException.class, () -> parkingService.calculateFee(VehicleType.CAR, 0));
        assertThrows(IllegalArgumentException.class, () -> parkingService.calculateFee(VehicleType.CAR, -2));
        assertThrows(IllegalArgumentException.class, () -> parkingService.calculateFee(null, 1));
    }

    @Test
    void exitRejectsNonPositiveTicketIdOrHoursBeforeConnectingToDatabase() {
        assertThrows(IllegalArgumentException.class, () -> parkingService.exit(0L));
        assertThrows(IllegalArgumentException.class, () -> parkingService.exit(-5L));
        assertThrows(IllegalArgumentException.class, () -> parkingService.exit(1L, 0));
        assertThrows(IllegalArgumentException.class, () -> parkingService.exit(1L, -1.5));
        assertThrows(IllegalArgumentException.class, () -> parkingService.exitVehicle(0L, 2.0));
        assertThrows(IllegalArgumentException.class, () -> parkingService.exitVehicle(1L, -0.5));
    }

    @Test
    void receiptAndHistoryFiltersAreWellFormed() {
        ParkingService.Receipt receipt = new ParkingService.Receipt(
            101L, "KA 01 AB 1234", VehicleType.CAR, List.of(3),
            LocalDateTime.now().minusHours(2), LocalDateTime.now(),
            Duration.ofHours(2), 2L, BigDecimal.valueOf(30.00), BigDecimal.valueOf(60.00), "COMPLETED"
        );
        assertEquals(101L, receipt.ticketId());
        assertEquals(BigDecimal.valueOf(60.00), receipt.totalFee());
        assertEquals("COMPLETED", receipt.status());

        ParkingService.HistoryFilter filter = new ParkingService.HistoryFilter(
            101L, "KA 01", VehicleType.CAR, LocalDate.of(2026, 10, 18), "ACTIVE"
        );
        assertEquals(101L, filter.ticketId());
        assertEquals("KA 01", filter.registration());
        assertEquals(VehicleType.CAR, filter.type());
        assertEquals("ACTIVE", filter.status());
        assertNotNull(filter.date());
    }
}