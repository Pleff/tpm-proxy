package de.bestblu.tools.tpmproxy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Single place all proxy log lines go through, so every one of them carries a
 * timestamp - callers can't forget it since there's no other way to log.
 */
public final class Log {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void info(String message) {
        System.out.println(timestamp() + " tpm-proxy: " + message);
    }

    public static void infof(String format, Object... args) {
        info(String.format(format, args));
    }

    public static void error(String message) {
        System.err.println(timestamp() + " tpm-proxy: " + message);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    private Log() {
    }
}
