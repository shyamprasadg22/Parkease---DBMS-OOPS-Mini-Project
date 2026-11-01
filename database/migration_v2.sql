-- ====================================================================
-- ParkEase Safe Migration Script (v2)
-- Purpose: Add non-destructive performance indexes for history search,
--          revenue filtering, and summary calculations.
-- Safe to run on existing database without resetting or dropping data.
-- ====================================================================

USE parkease;

-- 1. Index on entry_time for date filtering and chronological sorting
SET @dbname = DATABASE();
SET @tablename = 'parking_tickets';
SET @indexname = 'idx_tickets_entry_time';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @dbname
      AND table_name = @tablename
      AND index_name = @indexname
  ) > 0,
  'SELECT "Index idx_tickets_entry_time already exists."',
  'CREATE INDEX idx_tickets_entry_time ON parking_tickets(entry_time DESC);'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Index on exit_time for daily completed sessions and daily revenue
SET @indexname = 'idx_tickets_exit_time';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @dbname
      AND table_name = @tablename
      AND index_name = @indexname
  ) > 0,
  'SELECT "Index idx_tickets_exit_time already exists."',
  'CREATE INDEX idx_tickets_exit_time ON parking_tickets(exit_time);'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. Index on vehicle_type for vehicle type breakdowns and filtering
SET @indexname = 'idx_tickets_vehicle_type';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @dbname
      AND table_name = @tablename
      AND index_name = @indexname
  ) > 0,
  'SELECT "Index idx_tickets_vehicle_type already exists."',
  'CREATE INDEX idx_tickets_vehicle_type ON parking_tickets(vehicle_type);'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'Migration completed safely.' AS status;
