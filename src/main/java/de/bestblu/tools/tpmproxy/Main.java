package de.bestblu.tools.tpmproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.bestblu.tools.tpmproxy.config.ConfigException;
import de.bestblu.tools.tpmproxy.config.ProxyConfig;
import de.bestblu.tools.tpmproxy.http.DashboardHandler;
import de.bestblu.tools.tpmproxy.http.LimitHandler;
import de.bestblu.tools.tpmproxy.http.MessagesHandler;
import de.bestblu.tools.tpmproxy.http.StatusHandler;
import de.bestblu.tools.tpmproxy.limiter.DailyTokenLimiter;
import de.bestblu.tools.tpmproxy.limiter.SlidingWindowLimiter;
import de.bestblu.tools.tpmproxy.stats.ProxyStats;
import de.bestblu.tools.tpmproxy.upstream.LangdockClient;
import de.bestblu.tools.tpmproxy.upstream.TokenEstimator;

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

        SlidingWindowLimiter tpmLimiter = new SlidingWindowLimiter(config.initialTpmLimit());
        DailyTokenLimiter dailyLimiter = new DailyTokenLimiter(config.initialDailyTokenLimit());
        LangdockClient langdock = new LangdockClient(config.langdockBaseUrl(), config.langdockApiKey());
        TokenEstimator estimator = new TokenEstimator();
        ProxyStats stats = new ProxyStats();

        try {
            // SPEC.md Section 8: bind to loopback only, not exposed on the network.
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), config.proxyPort()), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/internal/status", new StatusHandler(config, tpmLimiter, dailyLimiter, stats, JSON));
            server.createContext("/internal/limit", new LimitHandler(tpmLimiter, dailyLimiter, JSON));
            server.createContext("/v1/messages",
                    new MessagesHandler(config, tpmLimiter, dailyLimiter, langdock, estimator, JSON, stats));
            server.createContext("/", new DashboardHandler());

            server.start();
            System.out.printf(
                    "tpm-proxy v%s - listening on port %d, forwarding to %s (TPM limit: %d, daily limit: %d)%n",
                    Version.get(), config.proxyPort(), config.langdockBaseUrl(),
                    config.initialTpmLimit(), config.initialDailyTokenLimit());
            System.out.printf("tpm-proxy dashboard: http://localhost:%d/%n", config.proxyPort());
        } catch (IOException e) {
            System.err.println("tpm-proxy: failed to start HTTP server - " + e.getMessage());
            System.exit(1);
        }
    }

    private Main() {
    }
}
