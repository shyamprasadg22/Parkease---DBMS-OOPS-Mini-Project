CREATE DATABASE IF NOT EXISTS parkease CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE parkease;

CREATE TABLE IF NOT EXISTS parking_slots (
  slot_id INT PRIMARY KEY,
  is_occupied BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS parking_tickets (
  ticket_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  registration_number VARCHAR(20) NOT NULL,
  vehicle_type VARCHAR(10) NOT NULL,
  entry_time DATETIME NOT NULL,
  exit_time DATETIME NULL,
  fee DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  status ENUM('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE',
  INDEX idx_registration_status (registration_number, status),
  INDEX idx_ticket_status (status)
);

CREATE TABLE IF NOT EXISTS ticket_slots (
  ticket_id BIGINT NOT NULL,
  slot_id INT NOT NULL,
  PRIMARY KEY (ticket_id, slot_id),
  CONSTRAINT fk_ts_ticket FOREIGN KEY (ticket_id) REFERENCES parking_tickets(ticket_id),
  CONSTRAINT fk_ts_slot FOREIGN KEY (slot_id) REFERENCES parking_slots(slot_id)
);

INSERT IGNORE INTO parking_slots (slot_id, is_occupied) VALUES
(1,FALSE),(2,FALSE),(3,FALSE),(4,FALSE),(5,FALSE),
(6,FALSE),(7,FALSE),(8,FALSE),(9,FALSE),(10,FALSE),
(11,FALSE),(12,FALSE),(13,FALSE),(14,FALSE),(15,FALSE),
(16,FALSE),(17,FALSE),(18,FALSE),(19,FALSE),(20,FALSE);
