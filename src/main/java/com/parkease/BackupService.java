package com.parkease;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class BackupService {
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path BACKUP_DIR = Paths.get("backups");

    public record BackupResult(boolean success, String message, Path filePath, long fileSizeBytes, String methodUsed) {}

    private BackupService() {}

    public static BackupResult createBackup() {
        try {
            if (!Files.exists(BACKUP_DIR)) {
                Files.createDirectories(BACKUP_DIR);
            }
            String timestamp = LocalDateTime.now().format(FILE_FMT);
            Path targetFile = BACKUP_DIR.resolve("parkease_backup_" + timestamp + ".sql");

            DbConfig cfg = parseDbConfig();

            // Attempt native mysqldump first
            if (isMysqlDumpAvailable()) {
                BackupResult nativeResult = executeMysqlDump(cfg, targetFile);
                if (nativeResult.success()) {
                    return nativeResult;
                }
            }

            // Fallback to pure JDBC export
            return executeJdbcExport(targetFile);

        } catch (Exception e) {
            return new BackupResult(false, "Backup failed: " + e.getMessage(), null, 0, "None");
        }
    }

    public static List<Path> listBackups() {
        if (!Files.exists(BACKUP_DIR)) return List.of();
        try (Stream<Path> stream = Files.list(BACKUP_DIR)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                         .sorted(Comparator.comparingLong((Path p) -> {
                             try { return Files.getLastModifiedTime(p).toMillis(); }
                             catch (IOException e) { return 0L; }
                         }).reversed())
                         .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void printRecoveryInstructions() {
        System.out.println("\n============================================================");
        System.out.println("               DATABASE RECOVERY INSTRUCTIONS");
        System.out.println("============================================================");
        System.out.println("To manually restore a ParkEase database backup:");
        System.out.println("1. Ensure MySQL Server is running.");
        System.out.println("2. Open a terminal / command prompt in the ParkEase project root.");
        System.out.println("3. Run the following command (replace <filename> with your backup):");
        System.out.println();
        System.out.println("   mysql -u " + DBConnection.getDbUser() + " -p " + parseDbConfig().dbName() + " < backups/<filename>.sql");
        System.out.println();
        System.out.println("4. Enter your MySQL password when prompted.");
        System.out.println("5. Verification step:");
        System.out.println("   - Start ParkEase ('mvn exec:java').");
        System.out.println("   - Choose menu option 4 (Advanced Parking Summary) to verify slots,");
        System.out.println("     active tickets, and historical revenue.");
        System.out.println("   - Choose menu option 3 (View Slot Map) to check slot occupancies.");
        System.out.println("============================================================\n");
    }

    private static boolean isMysqlDumpAvailable() {
        try {
            Process p = new ProcessBuilder("mysqldump", "--version").start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static BackupResult executeMysqlDump(DbConfig cfg, Path targetFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "mysqldump",
                "-h", cfg.host(),
                "-P", String.valueOf(cfg.port()),
                "-u", cfg.user(),
                "--add-drop-table",
                "--routines",
                cfg.dbName()
            );

            // Pass password safely via MYSQL_PWD environment variable without logging/displaying
            if (cfg.password() != null && !cfg.password().isEmpty()) {
                pb.environment().put("MYSQL_PWD", cfg.password());
            }

            pb.redirectOutput(targetFile.toFile());
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0 && Files.size(targetFile) > 0) {
                return new BackupResult(true, "Native MySQL backup completed successfully.",
                    targetFile, Files.size(targetFile), "MySQL Native (mysqldump)");
            } else {
                Files.deleteIfExists(targetFile);
                return new BackupResult(false, "mysqldump exited with error code.", null, 0, "mysqldump");
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(targetFile); } catch (Exception ignored) {}
            return new BackupResult(false, e.getMessage(), null, 0, "mysqldump");
        }
    }

    private static BackupResult executeJdbcExport(Path targetFile) {
        String[] tables = {"parking_slots", "parking_tickets", "ticket_slots"};
        try (Connection c = DBConnection.getConnection();
             BufferedWriter writer = Files.newBufferedWriter(targetFile)) {

            writer.write("-- ========================================================\n");
            writer.write("-- ParkEase Database Backup (Pure JDBC Export)\n");
            writer.write("-- Created: " + LocalDateTime.now().format(DISPLAY_FMT) + "\n");
            writer.write("-- ========================================================\n\n");
            writer.write("SET FOREIGN_KEY_CHECKS = 0;\n\n");

            for (String table : tables) {
                writer.write("-- --------------------------------------------------------\n");
                writer.write("-- Structure for table `" + table + "`\n");
                writer.write("-- --------------------------------------------------------\n");
                writer.write("DROP TABLE IF EXISTS `" + table + "`;\n");

                try (Statement stmt = c.createStatement();
                     ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    if (rs.next()) {
                        writer.write(rs.getString(2) + ";\n\n");
                    }
                }

                writer.write("-- Dumping data for table `" + table + "`\n");
                try (Statement stmt = c.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM `" + table + "`")) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();

                    int rowCount = 0;
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("INSERT INTO `").append(table).append("` VALUES (");
                        for (int i = 1; i <= cols; i++) {
                            Object val = rs.getObject(i);
                            if (val == null) {
                                sb.append("NULL");
                            } else if (val instanceof Number || val instanceof Boolean) {
                                sb.append(val);
                            } else {
                                String str = val.toString().replace("\\", "\\\\").replace("'", "\\'");
                                sb.append("'").append(str).append("'");
                            }
                            if (i < cols) sb.append(", ");
                        }
                        sb.append(");\n");
                        writer.write(sb.toString());
                        rowCount++;
                    }
                    if (rowCount == 0) {
                        writer.write("-- (No records in `" + table + "`)\n");
                    }
                    writer.write("\n");
                }
            }

            writer.write("SET FOREIGN_KEY_CHECKS = 1;\n");
            writer.write("-- Backup complete.\n");
            writer.flush();

            long size = Files.size(targetFile);
            return new BackupResult(true, "JDBC SQL export created successfully.",
                targetFile, size, "Pure JDBC Exporter");

        } catch (Exception e) {
            try { Files.deleteIfExists(targetFile); } catch (Exception ignored) {}
            return new BackupResult(false, "JDBC Export failed: " + e.getMessage(), null, 0, "JDBC Exporter");
        }
    }

    private record DbConfig(String host, int port, String dbName, String user, String password) {}

    private static DbConfig parseDbConfig() {
        String url = DBConnection.getDbUrl();
        String host = "localhost";
        int port = 3306;
        String dbName = "parkease";

        try {
            Pattern pattern = Pattern.compile("jdbc:mysql://([^:/]+)(?::(\\d+))?/([^?]+)");
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                host = matcher.group(1);
                if (matcher.group(2) != null) {
                    port = Integer.parseInt(matcher.group(2));
                }
                dbName = matcher.group(3);
            }
        } catch (Exception ignored) {}

        return new DbConfig(host, port, dbName, DBConnection.getDbUser(), DBConnection.getDbPassword());
    }
}
