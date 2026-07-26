package com.hrm.isup;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Config resolution order (first match wins):
 *   1. environment variable with the exact key   (e.g. ISUPKey)
 *   2. environment variable, UPPER_SNAKE_CASE     (e.g. ISUP_KEY / ALARM_SERVER_IP)
 *   3. config.properties (working dir, then classpath)
 *   4. caller default
 *
 * Env vars let a PaaS (Dokploy) set values from its UI without editing files.
 */
public final class Config {
    private static final Properties P = new Properties();

    static {
        boolean loaded = false;
        try (InputStream in = new FileInputStream("config.properties")) {
            P.load(in);
            loaded = true;
        } catch (IOException ignored) { }
        if (!loaded) {
            try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
                if (in != null) P.load(in);
            } catch (IOException ignored) { }
        }
    }

    public static String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env.trim();

        String snake = toUpperSnake(key);
        env = System.getenv(snake);
        if (env != null && !env.isBlank()) return env.trim();

        return P.getProperty(key, "").trim();
    }

    public static int getInt(String key, int def) {
        String v = get(key);
        return v.isEmpty() ? def : Integer.parseInt(v);
    }

    /** ISUPKey -> ISUP_KEY, AlarmServerIP -> ALARM_SERVER_IP */
    private static String toUpperSnake(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }

    private Config() {}
}
