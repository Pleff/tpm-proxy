package de.bestblu.tools.tpmproxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads the Maven project version, baked into the jar at build time via resource filtering. */
public final class Version {

    private static final String VALUE = load();

    public static String get() {
        return VALUE;
    }

    private static String load() {
        Properties props = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/tpm-proxy-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Fall through to "unknown" below.
        }
        return props.getProperty("version", "unknown");
    }

    private Version() {
    }
}
