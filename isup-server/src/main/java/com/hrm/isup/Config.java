package com.hrm.isup;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads config.properties (working dir first, then classpath). */
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
            } catch (IOException e) {
                throw new RuntimeException("config.properties not found", e);
            }
        }
    }

    public static String get(String key) { return P.getProperty(key, "").trim(); }

    public static int getInt(String key, int def) {
        String v = get(key);
        return v.isEmpty() ? def : Integer.parseInt(v);
    }

    private Config() {}
}
