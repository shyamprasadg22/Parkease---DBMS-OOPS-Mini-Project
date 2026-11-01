package com.parkease;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {
    private static final Scanner IN = new Scanner(System.in);
    private static final ParkingService SERVICE = new ParkingService();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("       PARKEASE SMART PARKING");
        System.out.println("========================================");
        try {
            SERVICE.summary();
            System.out.println("Connected to MySQL successfully.");
        } catch (SQLException e) {
            System.out.println("MySQL connection failed: " + e.getMessage());
            System.out.println("Check MySQL is running, schema.sql was executed, and config.properties is correct.");
            return;
        }

        try {
            boolean running = true;
            while (running) {
                printMainMenu();
                int choice = readInt("Enter choice: ");
                try {
                    switch (choice) {
                        case 1 -> park();
                        case 2 -> exit();
                        case 3 -> showSlots();
                        case 4 -> advancedSummary();
                        case 5 -> historyAndSearch();
                        case 6 -> backupAndRecovery();
                        case 7, 0 -> {
                            running = false;
                            System.out.println("\nThank you for using ParkEase. Have a safe journey!");
                        }
                        default -> System.out.println("Invalid menu choice. Please choose 1 to 7.");
                    }
                } catch (SQLException e) {
                    System.out.println("Database error: " + e.getMessage());
                } catch (IllegalArgumentException | IllegalStateException e) {
                    System.out.println("Cannot complete operation: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("Unexpected error: " + e.getMessage());
                }
            }
        } finally {
            com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }

    private static void printMainMenu() {
        System.out.println("\n========================================");
        System.out.println("          PARKEASE SMART PARKING");
        System.out.println("========================================");
        System.out.println("1. Park Vehicle");
        System.out.println("2. Exit Vehicle & Generate Receipt");
        System.out.println("3. View Slot Map");
        System.out.println("4. Advanced Parking Summary");
        System.out.println("5. Parking History & Search");
        System.out.println("6. Database Backup & Recovery Help");
        System.out.println("7. Quit");
        System.out.println("========================================");
    }

    private static void park() throws SQLException {
        System.out.printf("1. Bike (Rs.%.2f/hr)  2. Car (Rs.%.2f/hr)  3. Truck (Rs.%.2f/hr; 2 adjacent slots)%n",
            VehicleType.BIKE.hourlyRate(), VehicleType.CAR.hourlyRate(), VehicleType.TRUCK.hourlyRate());
        VehicleType type = VehicleType.fromChoice(readInt("Vehicle type: "));
        System.out.print("Registration number: ");
        String reg = IN.nextLine().trim();

        long id = SERVICE.park(reg, type);
        ParkingService.ParkedVehicle v = SERVICE.search(id);

        System.out.println("\n========================================");
        System.out.println("             PARKING TICKET");
        System.out.println("========================================");
        System.out.println("Ticket ID     : " + id);
        System.out.println("Registration  : " + v.registration());
        System.out.println("Vehicle Type  : " + v.type());
        System.out.println("Slot(s)       : " + v.slots());
        System.out.println("Entry Time    : " + v.entryTime().format(FMT));
        System.out.println("Status        : ACTIVE");
        System.out.println("Saved to MySQL successfully.");
        System.out.println("========================================");
    }

    private static void exit() throws SQLException {
        long id = readLong("Ticket ID: ");
        Double hours = readOptionalDouble("How many hours has been parked? (Press Enter to auto-calculate from entry time): ");

        ParkingService.Receipt receipt = SERVICE.exitVehicle(id, hours);

        System.out.println("\n============================================================");
        System.out.println("                  PARKEASE PARKING RECEIPT");
        System.out.println("============================================================");
        System.out.printf("Ticket Number       : %d%n", receipt.ticketId());
        System.out.printf("Vehicle Reg. Number : %s%n", receipt.registration());
        System.out.printf("Vehicle Type        : %s%n", receipt.type());
        System.out.printf("Assigned Slot(s)    : %s%n", receipt.slots());
        System.out.printf("Entry Date & Time   : %s%n", receipt.entryTime().format(FMT));
        System.out.printf("Exit Date & Time    : %s%n", receipt.exitTime().format(FMT));
        System.out.printf("Parking Duration    : %s%n", formatDuration(receipt.duration()));
        System.out.printf("Billable Hours      : %d hour(s) (partial hours rounded up)%n", receipt.billableHours());
        System.out.printf("Hourly Rate Applied : Rs. %.2f / hr%n", receipt.hourlyRate());
        System.out.println("------------------------------------------------------------");
        System.out.printf("Total Amount Due    : Rs. %.2f%n", receipt.totalFee());
        System.out.printf("Payment Status      : %s%n", receipt.status());
        System.out.println("============================================================");
        System.out.println("Ticket closed and slot(s) released in MySQL.");
    }

    private static void showSlots() throws SQLException {
        System.out.println("\nPARKING SLOT MAP");
        for (ParkingService.SlotStatus s : SERVICE.slots()) {
            String text = s.occupied() ? "OCCUPIED" + (s.registration() == null ? "" : " - " + s.registration()) : "AVAILABLE";
            System.out.printf("Slot %02d: %s%n", s.id(), text);
        }
    }

    private static void advancedSummary() throws SQLException {
        ParkingService.AdvancedSummary s = SERVICE.advancedSummary();
        System.out.println("\n============================================================");
        System.out.println("                 ADVANCED PARKING SUMMARY");
        System.out.println("============================================================");
        System.out.printf("Total Parking Slots           : %d%n", s.totalSlots());
        System.out.printf("Currently Occupied Slots      : %d%n", s.occupiedSlots());
        System.out.printf("Currently Available Slots     : %d%n", s.availableSlots());
        System.out.printf("Current Occupancy Rate        : %.1f%%%n", s.occupancyRate());
        System.out.println("------------------------------------------------------------");
        System.out.printf("Active Parking Tickets        : %d%n", s.activeTickets());
        System.out.printf("Total Vehicles Parked Today   : %d%n", s.vehiclesParkedToday());
        System.out.printf("Completed Sessions Today      : %d%n", s.completedSessionsToday());
        System.out.println("------------------------------------------------------------");
        System.out.printf("Revenue Collected Today       : Rs. %.2f%n", s.revenueToday());
        System.out.printf("Total Historical Revenue      : Rs. %.2f%n", s.totalRevenue());
        System.out.println("------------------------------------------------------------");
        System.out.println("Active Vehicles by Type:");
        for (Map.Entry<VehicleType, Integer> entry : s.activeByType().entrySet()) {
            System.out.printf("  - %-6s: %d active%n", entry.getKey(), entry.getValue());
        }
        System.out.println("Available Capacity by Compatible Slot Type:");
        System.out.printf("  - Bikes  : %d slots%n", s.availableForBike());
        System.out.printf("  - Cars   : %d slots%n", s.availableForCar());
        System.out.printf("  - Trucks : %d spaces (requires 2 adjacent slots)%n", s.availableForTruck());
        System.out.println("============================================================");
    }

    private static void historyAndSearch() throws SQLException {
        boolean inSearch = true;
        while (inSearch) {
            System.out.println("\n----------------------------------------");
            System.out.println("       PARKING HISTORY & SEARCH");
            System.out.println("----------------------------------------");
            System.out.println("1. Search by Ticket ID");
            System.out.println("2. Search by Vehicle Registration Number");
            System.out.println("3. Filter by Vehicle Type");
            System.out.println("4. Filter by Parking Date (YYYY-MM-DD)");
            System.out.println("5. Filter by Status (Active / Completed)");
            System.out.println("6. View All Recent Records");
            System.out.println("0. Back to Main Menu");
            int choice = readInt("Choose search option: ");

            ParkingService.HistoryFilter filter = null;
            switch (choice) {
                case 1 -> {
                    long tid = readLong("Enter Ticket ID: ");
                    filter = new ParkingService.HistoryFilter(tid, null, null, null, null);
                }
                case 2 -> {
                    System.out.print("Enter Registration (full or partial): ");
                    String reg = IN.nextLine().trim();
                    if (reg.isEmpty()) {
                        System.out.println("Registration search query cannot be empty.");
                        continue;
                    }
                    filter = new ParkingService.HistoryFilter(null, reg, null, null, null);
                }
                case 3 -> {
                    System.out.println("1. Bike  2. Car  3. Truck");
                    VehicleType vt = VehicleType.fromChoice(readInt("Choose vehicle type: "));
                    filter = new ParkingService.HistoryFilter(null, null, vt, null, null);
                }
                case 4 -> {
                    LocalDate date = readDate("Enter parking date (YYYY-MM-DD): ");
                    filter = new ParkingService.HistoryFilter(null, null, null, date, null);
                }
                case 5 -> {
                    System.out.println("1. ACTIVE  2. CLOSED (Completed)");
                    int stChoice = readInt("Choose status: ");
                    String status = switch (stChoice) {
                        case 1 -> "ACTIVE";
                        case 2 -> "CLOSED";
                        default -> throw new IllegalArgumentException("Choose 1 for ACTIVE or 2 for CLOSED.");
                    };
                    filter = new ParkingService.HistoryFilter(null, null, null, null, status);
                }
                case 6 -> filter = new ParkingService.HistoryFilter(null, null, null, null, null);
                case 0 -> { inSearch = false; continue; }
                default -> {
                    System.out.println("Invalid search option.");
                    continue;
                }
            }

            displayPaginatedHistory(filter);
        }
    }

    private static void displayPaginatedHistory(ParkingService.HistoryFilter filter) throws SQLException {
        int totalMatches = SERVICE.countHistory(filter);
        if (totalMatches == 0) {
            System.out.println("\nNo parking records found matching the given criteria.");
            return;
        }

        int pageSize = 5;
        int page = 0;
        int totalPages = (totalMatches + pageSize - 1) / pageSize;

        while (true) {
            int offset = page * pageSize;
            List<ParkingService.TicketHistory> list = SERVICE.searchHistory(filter, pageSize, offset);

            System.out.printf("%nFound %d record(s). Showing page %d of %d:%n", totalMatches, page + 1, totalPages);
            System.out.println("--------------------------------------------------------------------------------------------------");
            System.out.printf("%-8s | %-15s | %-6s | %-6s | %-19s | %-19s | %-9s | %-8s%n",
                "Ticket", "Registration", "Type", "Slots", "Entry Time", "Exit Time", "Fee (Rs)", "Status");
            System.out.println("--------------------------------------------------------------------------------------------------");
            for (ParkingService.TicketHistory h : list) {
                String exitStr = h.exitTime() != null ? h.exitTime().format(FMT) : "(Still Parked)";
                String feeStr = h.exitTime() != null ? String.format("%.2f", h.fee()) : "0.00";
                System.out.printf("%-8d | %-15s | %-6s | %-6s | %-19s | %-19s | %-9s | %-8s%n",
                    h.ticketId(),
                    h.registration(),
                    h.type(),
                    h.slots(),
                    h.entryTime().format(FMT),
                    exitStr,
                    feeStr,
                    h.status()
                );
            }
            System.out.println("--------------------------------------------------------------------------------------------------");

            if (totalPages <= 1) break;

            System.out.printf("Page %d/%d. [N]ext, [P]revious, or [Q]uit to search menu: ", page + 1, totalPages);
            String nav = IN.nextLine().trim().toUpperCase();
            if ("N".equals(nav) && page + 1 < totalPages) {
                page++;
            } else if ("P".equals(nav) && page > 0) {
                page--;
            } else if ("Q".equals(nav) || nav.isEmpty()) {
                break;
            } else {
                System.out.println("Invalid navigation choice.");
            }
        }
    }

    private static void backupAndRecovery() {
        boolean inBackup = true;
        while (inBackup) {
            System.out.println("\n----------------------------------------");
            System.out.println("    DATABASE BACKUP & RECOVERY HELP");
            System.out.println("----------------------------------------");
            System.out.println("1. Create Database Backup (.sql)");
            System.out.println("2. View Existing Backups");
            System.out.println("3. View Manual Recovery Instructions");
            System.out.println("0. Back to Main Menu");
            int choice = readInt("Choose option: ");

            switch (choice) {
                case 1 -> {
                    System.out.print("Are you sure you want to create a database backup now? (y/n): ");
                    String confirm = IN.nextLine().trim();
                    if (!confirm.equalsIgnoreCase("y") && !confirm.equalsIgnoreCase("yes")) {
                        System.out.println("Backup operation cancelled.");
                        continue;
                    }
                    System.out.println("Creating database backup, please wait...");
                    BackupService.BackupResult result = BackupService.createBackup();
                    if (result.success()) {
                        System.out.println("\nBackup completed successfully!");
                        System.out.println("File Path   : " + result.filePath().toAbsolutePath());
                        System.out.printf("File Size   : %.2f KB (%d bytes)%n", result.fileSizeBytes() / 1024.0, result.fileSizeBytes());
                        System.out.println("Method Used : " + result.methodUsed());
                    } else {
                        System.out.println("\nBackup failed: " + result.message());
                    }
                }
                case 2 -> {
                    List<Path> backups = BackupService.listBackups();
                    System.out.println("\nEXISTING DATABASE BACKUPS");
                    if (backups.isEmpty()) {
                        System.out.println("No backups found in backups/ directory.");
                    } else {
                        for (int i = 0; i < backups.size(); i++) {
                            Path b = backups.get(i);
                            try {
                                long sz = Files.size(b);
                                System.out.printf("%d. %-35s (%.2f KB)%n", i + 1, b.getFileName(), sz / 1024.0);
                            } catch (Exception e) {
                                System.out.printf("%d. %s%n", i + 1, b.getFileName());
                            }
                        }
                    }
                }
                case 3 -> BackupService.printRecoveryInstructions();
                case 0 -> inBackup = false;
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    private static String formatDuration(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) return "0 minutes";
        long totalMinutes = d.toMinutes();
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) return minutes + " minute(s)";
        if (minutes == 0) return hours + " hour(s)";
        return hours + " hour(s) " + minutes + " minute(s)";
    }

    private static int readInt(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                if (!IN.hasNextLine()) return 0;
                return Integer.parseInt(IN.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Enter a whole number.");
            }
        }
    }

    private static long readLong(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                if (!IN.hasNextLine()) return 0;
                return Long.parseLong(IN.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid numeric ticket ID.");
            }
        }
    }

    private static Double readOptionalDouble(String prompt) {
        while (true) {
            System.out.print(prompt);
            if (!IN.hasNextLine()) return null;
            String line = IN.nextLine().trim();
            if (line.isEmpty()) return null;
            try {
                double val = Double.parseDouble(line);
                if (val <= 0) {
                    System.out.println("Hours must be greater than 0.");
                    continue;
                }
                return val;
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid number or press Enter.");
            }
        }
    }

    private static LocalDate readDate(String prompt) {
        while (true) {
            System.out.print(prompt);
            if (!IN.hasNextLine()) return LocalDate.now();
            String line = IN.nextLine().trim();
            try {
                return LocalDate.parse(line, DATE_FMT);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid date format. Use YYYY-MM-DD (e.g. 2026-10-18).");
            }
        }
    }
}
