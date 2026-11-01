package com.parkease;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

public class ParkingService {

    public record ParkedVehicle(long ticketId, String registration, VehicleType type,
                                LocalDateTime entryTime, List<Integer> slots) {}

    public record SlotStatus(int id, boolean occupied, String registration) {}

    public record Summary(int total, int occupied, int activeVehicles, BigDecimal revenue) {}

    public record Receipt(long ticketId, String registration, VehicleType type,
                           List<Integer> slots, LocalDateTime entryTime, LocalDateTime exitTime,
                           Duration duration, long billableHours, BigDecimal hourlyRate,
                           BigDecimal totalFee, String status) {}

    public record AdvancedSummary(
        int totalSlots,
        int occupiedSlots,
        int availableSlots,
        double occupancyRate,
        int activeTickets,
        int vehiclesParkedToday,
        int completedSessionsToday,
        BigDecimal revenueToday,
        BigDecimal totalRevenue,
        Map<VehicleType, Integer> activeByType,
        int availableForBike,
        int availableForCar,
        int availableForTruck
    ) {}

    public record TicketHistory(
        long ticketId,
        String registration,
        VehicleType type,
        List<Integer> slots,
        LocalDateTime entryTime,
        LocalDateTime exitTime,
        Duration duration,
        long billableHours,
        BigDecimal fee,
        String status
    ) {}

    public record HistoryFilter(
        Long ticketId,
        String registration,
        VehicleType type,
        LocalDate date,
        String status
    ) {}

    public long park(String rawRegistration, VehicleType type) throws SQLException {
        if (type == null) {
            throw new IllegalArgumentException("Vehicle type must not be null.");
        }
        String reg = normalize(rawRegistration);

        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Prevent duplicate active tickets
                Long existingActiveTicket = getActiveTicketId(c, reg);
                if (existingActiveTicket != null) {
                    throw new IllegalArgumentException("Vehicle " + reg + " already has an active parking ticket (Ticket ID: " + existingActiveTicket + ").");
                }

                // Row-lock available slots to prevent concurrent assignment
                List<Integer> slots = findSlots(c, type.slotsRequired());
                if (slots.size() != type.slotsRequired()) {
                    throw new IllegalStateException(type == VehicleType.TRUCK
                        ? "No two consecutive slots are available for a truck."
                        : "Parking lot is full for " + type.name().toLowerCase() + ".");
                }

                LocalDateTime now = LocalDateTime.now();
                long ticketId;
                try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO parking_tickets(registration_number, vehicle_type, entry_time, status, fee) VALUES(?, ?, ?, 'ACTIVE', 0.00)",
                    Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, reg);
                    p.setString(2, type.name());
                    p.setTimestamp(3, Timestamp.valueOf(now));
                    p.executeUpdate();
                    try (ResultSet keys = p.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Ticket ID was not generated.");
                        ticketId = keys.getLong(1);
                    }
                }

                try (PreparedStatement update = c.prepareStatement(
                         "UPDATE parking_slots SET is_occupied=TRUE WHERE slot_id=? AND is_occupied=FALSE");
                     PreparedStatement link = c.prepareStatement(
                         "INSERT INTO ticket_slots(ticket_id, slot_id) VALUES(?, ?)")) {
                    for (int slot : slots) {
                        update.setInt(1, slot);
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("Slot " + slot + " is no longer available.");
                        }
                        link.setLong(1, ticketId);
                        link.setInt(2, slot);
                        link.executeUpdate();
                    }
                }

                c.commit();
                return ticketId;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException x) throw x;
                if (e instanceof RuntimeException x) throw x;
                throw new SQLException(e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public BigDecimal calculateFee(VehicleType type, double hours) {
        if (type == null) {
            throw new IllegalArgumentException("Vehicle type cannot be null.");
        }
        if (hours <= 0) {
            throw new IllegalArgumentException("Hours must be greater than 0.");
        }
        return BigDecimal.valueOf(type.hourlyRate())
            .multiply(BigDecimal.valueOf(hours))
            .setScale(2, RoundingMode.HALF_UP);
    }

    public Receipt exitVehicle(long ticketId, Double manualHours) throws SQLException {
        if (ticketId <= 0) {
            throw new IllegalArgumentException("Invalid ticket ID. Ticket IDs are positive numbers.");
        }
        if (manualHours != null && manualHours <= 0) {
            throw new IllegalArgumentException("Hours must be greater than 0.");
        }

        try (Connection c = DBConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                String registration;
                VehicleType type;
                LocalDateTime entry;
                String currentStatus;

                // Acquire row lock on ticket row
                try (PreparedStatement p = c.prepareStatement(
                    "SELECT registration_number, vehicle_type, entry_time, status, exit_time FROM parking_tickets WHERE ticket_id=? FOR UPDATE")) {
                    p.setLong(1, ticketId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) {
                            throw new IllegalArgumentException("Ticket ID " + ticketId + " does not exist.");
                        }
                        registration = r.getString("registration_number");
                        type = VehicleType.valueOf(r.getString("vehicle_type"));
                        entry = r.getTimestamp("entry_time").toLocalDateTime();
                        currentStatus = r.getString("status");
                        if ("CLOSED".equalsIgnoreCase(currentStatus)) {
                            Timestamp closedAt = r.getTimestamp("exit_time");
                            String closedMsg = closedAt != null ? closedAt.toString() : "previously";
                            throw new IllegalStateException("Ticket ID " + ticketId + " has already been completed / exited on " + closedMsg + ".");
                        }
                    }
                }

                List<Integer> slots = slotsForTicket(c, ticketId);
                if (slots.isEmpty()) {
                    throw new IllegalStateException("No slots are currently assigned to Ticket ID " + ticketId + ".");
                }

                LocalDateTime now = LocalDateTime.now();
                LocalDateTime exitTime;
                Duration duration;
                long billableHours;

                if (manualHours != null) {
                    billableHours = Math.max(1, (long) Math.ceil(manualHours));
                    duration = Duration.ofMinutes(Math.round(manualHours * 60));
                    exitTime = now.isAfter(entry) ? now : entry.plusMinutes(Math.round(manualHours * 60));
                } else {
                    exitTime = now;
                    duration = Duration.between(entry, exitTime);
                    if (duration.isNegative()) {
                        duration = Duration.ZERO;
                    }
                    long seconds = duration.getSeconds();
                    // Partial hours rounded up policy (min 1 hr)
                    billableHours = Math.max(1, (seconds + 3599) / 3600);
                }

                BigDecimal hourlyRate = BigDecimal.valueOf(type.hourlyRate());
                BigDecimal totalFee = hourlyRate.multiply(BigDecimal.valueOf(billableHours)).setScale(2, RoundingMode.HALF_UP);

                // Update ticket status
                try (PreparedStatement p = c.prepareStatement(
                    "UPDATE parking_tickets SET exit_time=?, fee=?, status='CLOSED' WHERE ticket_id=? AND status='ACTIVE'")) {
                    p.setTimestamp(1, Timestamp.valueOf(exitTime));
                    p.setBigDecimal(2, totalFee);
                    p.setLong(3, ticketId);
                    if (p.executeUpdate() != 1) {
                        throw new SQLException("Ticket could not be closed; it may have been closed by another session.");
                    }
                }

                // Release slots
                try (PreparedStatement p = c.prepareStatement(
                    "UPDATE parking_slots SET is_occupied=FALSE WHERE slot_id IN (SELECT slot_id FROM ticket_slots WHERE ticket_id=?)")) {
                    p.setLong(1, ticketId);
                    p.executeUpdate();
                }

                c.commit();

                return new Receipt(
                    ticketId,
                    registration,
                    type,
                    slots,
                    entry,
                    exitTime,
                    duration,
                    billableHours,
                    hourlyRate,
                    totalFee,
                    "COMPLETED"
                );

            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException x) throw x;
                if (e instanceof RuntimeException x) throw x;
                throw new SQLException(e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public BigDecimal exit(long ticketId, double hours) throws SQLException {
        Receipt receipt = exitVehicle(ticketId, hours);
        System.out.println("Vehicle " + receipt.registration() + " exited after " + receipt.billableHours() + " billable hour(s).");
        return receipt.totalFee();
    }

    public BigDecimal exit(long ticketId) throws SQLException {
        Receipt receipt = exitVehicle(ticketId, null);
        System.out.println("Vehicle " + receipt.registration() + " exited after " + receipt.billableHours() + " billable hour(s).");
        return receipt.totalFee();
    }

    public List<SlotStatus> slots() throws SQLException {
        String sql = """
            SELECT s.slot_id, s.is_occupied, active.registration_number
            FROM parking_slots s
            LEFT JOIN (
                SELECT ts.slot_id, t.registration_number
                FROM ticket_slots ts
                JOIN parking_tickets t ON t.ticket_id = ts.ticket_id
                WHERE t.status = 'ACTIVE'
            ) active ON active.slot_id = s.slot_id
            ORDER BY s.slot_id
            """;
        List<SlotStatus> out = new ArrayList<>();
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(sql);
             ResultSet r = p.executeQuery()) {
            while (r.next()) {
                out.add(new SlotStatus(r.getInt(1), r.getBoolean(2), r.getString(3)));
            }
        }
        return out;
    }

    public List<ParkedVehicle> activeVehicles() throws SQLException {
        List<ParkedVehicle> out = new ArrayList<>();
        String sql = "SELECT ticket_id, registration_number, vehicle_type, entry_time FROM parking_tickets WHERE status='ACTIVE' ORDER BY ticket_id";
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(sql);
             ResultSet r = p.executeQuery()) {
            while (r.next()) {
                long id = r.getLong("ticket_id");
                List<Integer> assigned = slotsForTicket(c, id);
                out.add(new ParkedVehicle(
                    id,
                    r.getString("registration_number"),
                    VehicleType.valueOf(r.getString("vehicle_type")),
                    r.getTimestamp("entry_time").toLocalDateTime(),
                    assigned
                ));
            }
        }
        return out;
    }

    public ParkedVehicle search(long id) throws SQLException {
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(
                 "SELECT ticket_id, registration_number, vehicle_type, entry_time FROM parking_tickets WHERE ticket_id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new ParkedVehicle(
                    id,
                    r.getString("registration_number"),
                    VehicleType.valueOf(r.getString("vehicle_type")),
                    r.getTimestamp("entry_time").toLocalDateTime(),
                    slotsForTicket(c, id)
                );
            }
        }
    }

    public Summary summary() throws SQLException {
        String sql = """
            SELECT (SELECT COUNT(*) FROM parking_slots) AS total,
                   (SELECT COUNT(*) FROM parking_slots WHERE is_occupied=TRUE) AS occupied,
                   (SELECT COUNT(*) FROM parking_tickets WHERE status='ACTIVE') AS active,
                   (SELECT COALESCE(SUM(fee), 0.00) FROM parking_tickets WHERE status='CLOSED') AS revenue
            """;
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(sql);
             ResultSet r = p.executeQuery()) {
            if (r.next()) {
                return new Summary(r.getInt("total"), r.getInt("occupied"), r.getInt("active"), r.getBigDecimal("revenue"));
            }
            return new Summary(0, 0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
    }

    public AdvancedSummary advancedSummary() throws SQLException {
        try (Connection c = DBConnection.getConnection()) {
            int totalSlots = 0;
            int occupiedSlots = 0;

            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN is_occupied=TRUE THEN 1 ELSE 0 END), 0) AS occupied FROM parking_slots")) {
                if (r.next()) {
                    totalSlots = r.getInt("total");
                    occupiedSlots = r.getInt("occupied");
                }
            }

            int availableSlots = Math.max(0, totalSlots - occupiedSlots);
            double occupancyRate = totalSlots > 0 ? (occupiedSlots * 100.0 / totalSlots) : 0.0;

            int activeTickets = 0;
            int vehiclesParkedToday = 0;
            int completedSessionsToday = 0;
            BigDecimal revenueToday = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalRevenue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            String ticketStatsSql = """
                SELECT
                    COALESCE(SUM(CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END), 0) AS active_cnt,
                    COALESCE(SUM(CASE WHEN entry_time >= CURDATE() AND entry_time < CURDATE() + INTERVAL 1 DAY THEN 1 ELSE 0 END), 0) AS parked_today,
                    COALESCE(SUM(CASE WHEN status='CLOSED' AND exit_time >= CURDATE() AND exit_time < CURDATE() + INTERVAL 1 DAY THEN 1 ELSE 0 END), 0) AS completed_today,
                    COALESCE(SUM(CASE WHEN status='CLOSED' AND exit_time >= CURDATE() AND exit_time < CURDATE() + INTERVAL 1 DAY THEN fee ELSE 0 END), 0.00) AS rev_today,
                    COALESCE(SUM(CASE WHEN status='CLOSED' THEN fee ELSE 0 END), 0.00) AS rev_total
                FROM parking_tickets
                """;

            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery(ticketStatsSql)) {
                if (r.next()) {
                    activeTickets = r.getInt("active_cnt");
                    vehiclesParkedToday = r.getInt("parked_today");
                    completedSessionsToday = r.getInt("completed_today");
                    revenueToday = r.getBigDecimal("rev_today");
                    totalRevenue = r.getBigDecimal("rev_total");
                }
            }

            Map<VehicleType, Integer> activeByType = new EnumMap<>(VehicleType.class);
            for (VehicleType vt : VehicleType.values()) {
                activeByType.put(vt, 0);
            }
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT vehicle_type, COUNT(*) FROM parking_tickets WHERE status='ACTIVE' GROUP BY vehicle_type")) {
                while (r.next()) {
                    try {
                        VehicleType vt = VehicleType.valueOf(r.getString(1));
                        activeByType.put(vt, r.getInt(2));
                    } catch (Exception ignored) {}
                }
            }

            List<Integer> freeSlotList = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT slot_id FROM parking_slots WHERE is_occupied=FALSE ORDER BY slot_id")) {
                while (r.next()) freeSlotList.add(r.getInt(1));
            }

            int availableForBike = freeSlotList.size();
            int availableForCar = freeSlotList.size();
            int availableForTruck = 0;
            for (int i = 0; i < freeSlotList.size() - 1; i++) {
                if (freeSlotList.get(i + 1) == freeSlotList.get(i) + 1) {
                    availableForTruck++;
                    i++; // Non-overlapping pair
                }
            }

            return new AdvancedSummary(
                totalSlots,
                occupiedSlots,
                availableSlots,
                occupancyRate,
                activeTickets,
                vehiclesParkedToday,
                completedSessionsToday,
                revenueToday,
                totalRevenue,
                activeByType,
                availableForBike,
                availableForCar,
                availableForTruck
            );
        }
    }

    public List<TicketHistory> searchHistory(HistoryFilter filter, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("""
            SELECT ticket_id, registration_number, vehicle_type, entry_time, exit_time, fee, status
            FROM parking_tickets
            WHERE 1=1
            """);

        List<Object> params = new ArrayList<>();
        buildFilterClause(sql, params, filter);
        sql.append(" ORDER BY entry_time DESC LIMIT ? OFFSET ?");

        List<TicketHistory> list = new ArrayList<>();
        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object param : params) {
                p.setObject(idx++, param);
            }
            p.setInt(idx++, limit);
            p.setInt(idx, offset);

            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    long id = r.getLong("ticket_id");
                    String reg = r.getString("registration_number");
                    VehicleType vt = VehicleType.valueOf(r.getString("vehicle_type"));
                    LocalDateTime entry = r.getTimestamp("entry_time").toLocalDateTime();
                    Timestamp exitTs = r.getTimestamp("exit_time");
                    LocalDateTime exit = exitTs != null ? exitTs.toLocalDateTime() : null;
                    BigDecimal fee = r.getBigDecimal("fee");
                    String status = r.getString("status");

                    Duration dur = (exit != null)
                        ? Duration.between(entry, exit)
                        : Duration.between(entry, LocalDateTime.now());
                    if (dur.isNegative()) dur = Duration.ZERO;
                    long billable = Math.max(1, (dur.getSeconds() + 3599) / 3600);

                    List<Integer> slots = slotsForTicket(c, id);

                    list.add(new TicketHistory(
                        id, reg, vt, slots, entry, exit, dur, billable, fee, status
                    ));
                }
            }
        }
        return list;
    }

    public int countHistory(HistoryFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM parking_tickets WHERE 1=1");
        List<Object> params = new ArrayList<>();
        buildFilterClause(sql, params, filter);

        try (Connection c = DBConnection.getConnection();
             PreparedStatement p = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object param : params) {
                p.setObject(idx++, param);
            }
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) return r.getInt(1);
            }
        }
        return 0;
    }

    private void buildFilterClause(StringBuilder sql, List<Object> params, HistoryFilter filter) {
        if (filter == null) return;
        if (filter.ticketId() != null) {
            sql.append(" AND ticket_id = ?");
            params.add(filter.ticketId());
        }
        if (filter.registration() != null && !filter.registration().isBlank()) {
            sql.append(" AND UPPER(registration_number) LIKE ?");
            params.add("%" + filter.registration().trim().toUpperCase() + "%");
        }
        if (filter.type() != null) {
            sql.append(" AND vehicle_type = ?");
            params.add(filter.type().name());
        }
        if (filter.date() != null) {
            sql.append(" AND entry_time >= ? AND entry_time < ?");
            params.add(Timestamp.valueOf(filter.date().atStartOfDay()));
            params.add(Timestamp.valueOf(filter.date().plusDays(1).atStartOfDay()));
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND status = ?");
            params.add(filter.status().trim().toUpperCase());
        }
    }

    private List<Integer> slotsForTicket(Connection c, long id) throws SQLException {
        List<Integer> slots = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT slot_id FROM ticket_slots WHERE ticket_id=? ORDER BY slot_id")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) slots.add(r.getInt(1));
            }
        }
        return slots;
    }

    private Long getActiveTicketId(Connection c, String reg) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            "SELECT ticket_id FROM parking_tickets WHERE registration_number=? AND status='ACTIVE' LIMIT 1 FOR UPDATE")) {
            p.setString(1, reg);
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) return r.getLong(1);
            }
        }
        return null;
    }

    private List<Integer> findSlots(Connection c, int needed) throws SQLException {
        List<Integer> free = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
            "SELECT slot_id FROM parking_slots WHERE is_occupied=FALSE ORDER BY slot_id FOR UPDATE");
             ResultSet r = p.executeQuery()) {
            while (r.next()) free.add(r.getInt(1));
        }
        if (needed == 1) return free.isEmpty() ? List.of() : List.of(free.get(0));
        for (int i = 0; i < free.size() - 1; i++) {
            if (free.get(i + 1) == free.get(i) + 1) {
                return List.of(free.get(i), free.get(i + 1));
            }
        }
        return List.of();
    }

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Registration number cannot be empty.");
        }
        String reg = raw.trim().toUpperCase().replaceAll("\\s+", " ");
        if (reg.length() < 4 || reg.length() > 20) {
            throw new IllegalArgumentException("Registration must be between 4 and 20 characters.");
        }
        if (!reg.matches("^[A-Z0-9 -]{4,20}$")) {
            throw new IllegalArgumentException("Use 4-20 letters, numbers, spaces, or hyphens.");
        }
        return reg;
    }
}
