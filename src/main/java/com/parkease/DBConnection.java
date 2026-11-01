package com.parkease;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DBConnection {
    private static final Properties CONFIG = loadConfig();
    private DBConnection() {}

    private static Properties loadConfig() {
        Properties p = new Properties();
        Path path = Path.of("config.properties");
        try {
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) { p.load(in); }
            } else {
                try (InputStream in = DBConnection.class.getClassLoader().getResourceAsStream("config.properties")) {
                    if (in != null) p.load(in);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read database configuration.", e);
        }
        return p;
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getDbUrl(), getDbUser(), getDbPassword());
    }

    public static String getDbUrl() {
        return setting("DB_URL", "db.url", "jdbc:mysql://localhost:3306/parkease?serverTimezone=UTC");
    }

    public static String getDbUser() {
        return setting("DB_USER", "db.user", "root");
    }

    public static String getDbPassword() {
        return setting("DB_PASSWORD", "db.password", "");
    }

    public static String getSetting(String key, String fallback) {
        String env = key.toUpperCase().replace('.', '_');
        return setting(env, key, fallback);
    }

    private static String setting(String env, String key, String fallback) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) return value;
        value = CONFIG.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
