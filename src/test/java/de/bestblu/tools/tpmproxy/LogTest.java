package de.bestblu.tools.tpmproxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTest {

    private static final Pattern TIMESTAMP_PREFIX =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} tpm-proxy: .*");

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void captureStreams() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void infoLinesCarryATimestampPrefix() {
        Log.info("something happened");

        String line = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(TIMESTAMP_PREFIX.matcher(line).matches(), "expected a timestamped line, got: " + line);
        assertTrue(line.endsWith("something happened"));
    }

    @Test
    void infofFormatsBeforeStamping() {
        Log.infof("limit changed %d -> %d", 100, 200);

        String line = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(TIMESTAMP_PREFIX.matcher(line).matches(), "expected a timestamped line, got: " + line);
        assertTrue(line.endsWith("limit changed 100 -> 200"));
    }

    @Test
    void errorLinesGoToStderrWithATimestampPrefix() {
        Log.error("invalid configuration - LANGDOCK_API_KEY missing");

        assertTrue(out.toString(StandardCharsets.UTF_8).isEmpty(), "error() must not write to stdout");
        String line = err.toString(StandardCharsets.UTF_8).trim();
        assertTrue(TIMESTAMP_PREFIX.matcher(line).matches(), "expected a timestamped line, got: " + line);
        assertTrue(line.endsWith("invalid configuration - LANGDOCK_API_KEY missing"));
    }
}
