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
        // Exact env var name first (e.g. ISUPKey).
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env.trim();

        // Robust match: ignore case and underscores, so ISUP_KEY / ISUPKEY /
        // ISUPKey and ALARM_SERVER_IP / AlarmServerIP all resolve to the key.
        String norm = normalize(key);
        for (var e : System.getenv().entrySet()) {
            if (normalize(e.getKey()).equals(norm) && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim();
            }
        }
        return P.getProperty(key, "").trim();
    }

    public static int getInt(String key, int def) {
        String v = get(key);
        return v.isEmpty() ? def : Integer.parseInt(v);
    }

    private static String normalize(String s) {
        return s.replace("_", "").toUpperCase();
    }

    private Config() {}
}
