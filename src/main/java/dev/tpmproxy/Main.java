package dev.tpmproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.tpmproxy.config.ConfigException;
import dev.tpmproxy.config.ProxyConfig;
import dev.tpmproxy.http.LimitHandler;
import dev.tpmproxy.http.MessagesHandler;
import dev.tpmproxy.http.StatusHandler;
import dev.tpmproxy.limiter.SlidingWindowLimiter;
import dev.tpmproxy.stats.ProxyStats;
import dev.tpmproxy.upstream.LangdockClient;
import dev.tpmproxy.upstream.TokenEstimator;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class Main {

    public static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        ProxyConfig config;
        try {
            config = ProxyConfig.fromEnv();
        } catch (ConfigException e) {
            System.err.println("tpm-proxy: invalid configuration - " + e.getMessage());
            System.exit(1);
            return;
        }

        SlidingWindowLimiter limiter = new SlidingWindowLimiter(config.initialTpmLimit());
        LangdockClient langdock = new LangdockClient(config.langdockBaseUrl(), config.langdockApiKey());
        TokenEstimator estimator = new TokenEstimator();
        ProxyStats stats = new ProxyStats();

        try {
            // SPEC.md Section 8: bind to loopback only, not exposed on the network.
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), config.proxyPort()), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/internal/status", new StatusHandler(config, limiter, stats, JSON));
            server.createContext("/internal/limit", new LimitHandler(limiter, JSON));
            server.createContext("/v1/messages", new MessagesHandler(config, limiter, langdock, estimator, JSON, stats));

            server.start();
            System.out.printf(
                    "tpm-proxy listening on port %d, forwarding to %s (initial TPM limit: %d)%n",
                    config.proxyPort(), config.langdockBaseUrl(), config.initialTpmLimit());
        } catch (IOException e) {
            System.err.println("tpm-proxy: failed to start HTTP server - " + e.getMessage());
            System.exit(1);
        }
    }

    private Main() {
    }
}
