package dev.tpmproxy.config;

/** Thrown when required configuration is missing or invalid at startup. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
