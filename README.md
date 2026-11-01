# Parkease---DBMS-OOPS-Mini-Project

## ParkEase Smart Parking Lot (Enhanced)

A robust, enterprise-grade Java console parking management system backed by MySQL through JDBC.

---

## Key Features

1. **Smart Slot Allocation**:
   - 20 parking slots (configurable in database).
   - Bikes and cars occupy 1 slot; trucks occupy 2 adjacent slots automatically.
2. **Smarter Billing & Itemized Receipts**:
   - Configurable hourly rates in `config.properties` (or environment variables).
   - Minimum 1-hour charge with partial-hour ceil rounding.
   - Supports both automated timestamp-based duration calculation and user-specified parked hours.
   - Comprehensive console receipts detailing Ticket ID, Registration, Vehicle Type, Slot(s), Timestamps, Actual Duration, Billable Hours, Hourly Rate, and Total Amount.
3. **Duplicate & Invalid Ticket Protection**:
   - Duplicate active parking attempts for the same registration number are blocked using database row locks (`FOR UPDATE`).
   - Normalizes registration format (trims, uppercase, cleans spaces).
   - Rejects non-existent, malformed, or already-exited/closed tickets.
   - Safe numeric input readers that prevent application crashes from unexpected inputs.
4. **Advanced Parking Summary**:
   - Real-time slot metrics: total, occupied, available, and occupancy percentage.
   - Today's activity: active tickets, vehicles parked today, completed sessions today.
   - Financial tracking: revenue collected today and total historical revenue in Indian Rupees (Rs. / ₹).
   - Active breakdown by vehicle type (Bike, Car, Truck).
   - Available capacity estimation for Bikes, Cars, and Trucks (adjacent slot pairs).
5. **Parking History & Search (with Pagination)**:
   - Search by Ticket ID, Vehicle Registration (exact or partial wildcard `LIKE %query%`), Vehicle Type, Parking Date (`YYYY-MM-DD`), or Status (`ACTIVE`/`CLOSED`).
   - Paginated display (5 records per page) with `[N]ext`, `[P]revious`, and `[Q]uit`.
6. **Transaction Safety & Row Locking**:
   - Uses JDBC transactions (`setAutoCommit(false)`, `commit()`, `rollback()`) to guarantee atomicity.
   - Slots are never released if ticket exit fails, and tickets are never closed if slot release fails.
   - Row-level locking (`FOR UPDATE`) prevents simultaneous operations from claiming the same slot.
7. **Database Backup & Recovery**:
   - Automated native MySQL backup (`mysqldump`) with automatic fallback to pure Java JDBC SQL export.
   - Dedicated `backups/` directory with timestamped files (`parkease_backup_YYYYMMdd_HHmmss.sql`).
   - Passwords are never logged or printed to the console.
   - Documented manual recovery instructions with verification steps.

---

## Hourly Rates (Configurable)

Rates can be configured in `config.properties` or environment variables `RATE_BIKE`, `RATE_CAR`, `RATE_TRUCK`:
- **Bike**: Rs. 10.00 / hour (Default)
- **Car**: Rs. 30.00 / hour (Default)
- **Truck**: Rs. 60.00 / hour (Default; requires 2 adjacent slots)

Billing Policy: Any started hour is charged as a full billable hour, with a minimum 1-hour charge.

---

## Requirements

- **JDK 25 (LTS)**
- **MySQL Server 8.x** or compatible
- **Maven 3.9+**

---

## Setup & Configuration

1. **Initialize Database**:
   ```bash
   mysql -u root -p < database/schema.sql
   ```
2. **Apply v2 Performance Indexes (Safe Migration)**:
   ```bash
   mysql -u root -p parkease < database/migration_v2.sql
   ```
3. **Configure Database Connection**:
   Ensure `config.properties` contains your credentials:
   ```properties
   db.url=jdbc:mysql://localhost:3306/parkease?serverTimezone=UTC
   db.user=root
   db.password=YOUR_MYSQL_PASSWORD

   # Optional: Custom hourly rates
   rate.bike=10.00
   rate.car=30.00
   rate.truck=60.00
   ```
4. **Compile and Run**:
   ```bash
   # Compile and run unit tests
   mvn clean test

   # Run the application
   mvn exec:java
   ```

---

## Updated Console Menu

```
========================================
          PARKEASE SMART PARKING
========================================
1. Park Vehicle
2. Exit Vehicle & Generate Receipt
3. View Slot Map
4. Advanced Parking Summary
5. Parking History & Search
6. Database Backup & Recovery Help
7. Quit
========================================
Enter choice:
```

---

## Manual Test Checklist

| Test Scenario | Action | Expected Result |
| :--- | :--- | :--- |
| **1. Park Vehicle** | Choose `1`, select Car (`2`), enter `KA 01 AB 1234` | Allots 1 slot, prints ticket with ID and entry time, saved to MySQL. |
| **2. Duplicate Park Check** | Choose `1`, enter same `KA 01 AB 1234` again | Rejects: `"Vehicle KA 01 AB 1234 already has an active parking ticket..."`. |
| **3. View Slot Map** | Choose `3` | Displays 20 slots; slot occupied by `KA 01 AB 1234` shows `OCCUPIED`. |
| **4. History Search** | Choose `5` -> `2`, search `KA 01` | Displays matching active ticket record. |
| **5. Exit Vehicle** | Choose `2`, enter Ticket ID, enter `2.5` hours | Generates itemized receipt with 3 billable hours, fee Rs. 90.00, releases slot. |
| **6. Repeated Exit Check**| Choose `2`, enter the same Ticket ID again | Rejects: `"Ticket ID ... has already been completed / exited..."`. |
| **7. Advanced Summary** | Choose `4` | Shows updated revenue today, occupancy rate, and completed sessions today. |
| **8. Database Backup** | Choose `6` -> `1`, confirm `y` | Creates `.sql` backup in `backups/`, reports file size and method used. |
| **9. Invalid Input Safety** | Enter letters or negative numbers at prompts | Displays polite error and prompts again without crashing. |
