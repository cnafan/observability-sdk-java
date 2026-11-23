package net.openan.observability;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtlpExporterConfigurationTest {
    private static final String TEST_PASSWORD = "local-test-only";

    static {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Path unavailableUnixSocketDirectory = Path.of(
                System.getProperty("java.io.tmpdir"), "otel-sdk-test-no-uds-" + UUID.randomUUID());
            System.setProperty("jdk.net.unixdomain.tmpdir", unavailableUnixSocketDirectory.toString());
        }
    }

    @TempDir Path tempDir;

    @Test
    void environmentHeadersReachAllHttpSignalExporters() throws Exception {
        var requests = new ConcurrentLinkedQueue<CapturedRequest>();
        TlsIdentity identity = createIdentity("trusted");
        HttpsServer server = startServer(identity, requests);
        try {
            String endpoint = "https://localhost:" + server.getAddress().getPort();
            var config = SdkConfig.builder()
                .endpoint(endpoint)
                .protocol("http/protobuf")
                .trustedCertificate(identity.certificate())
                .build(Map.of(
                    "OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20local%2Ctest",
                    "OTEL_EXPORTER_OTLP_TIMEOUT", "3000",
                    "OTEL_EXPORTER_OTLP_CERTIFICATE", "ignored-invalid-certificate.pem"));

            try (var sdk = ObservabilitySdk.start(config)) {
                recordSignals(sdk, "trusted-ca-run");
                assertTrue(sdk.flush(5, TimeUnit.SECONDS));
            }

            assertEquals(Set.of("/v1/traces", "/v1/metrics", "/v1/logs"),
                requests.stream().map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet()));
            assertTrue(requests.size() >= 3);
            assertTrue(requests.stream().allMatch(request -> request.status() == 200));
            assertTrue(requests.stream().allMatch(request -> "Bearer local,test".equals(request.authorization())));
            assertTrue(requests.stream().allMatch(request -> request.payloadBytes() > 0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void trustedAndUntrustedCaAndRejectedCredentialsAreDistinguishable() throws Exception {
        TlsIdentity serverIdentity = createIdentity("collector");
        TlsIdentity unrelatedIdentity = createIdentity("unrelated-ca");
        var requests = new ConcurrentLinkedQueue<CapturedRequest>();
        HttpsServer server = startServer(serverIdentity, requests);
        String endpoint = "https://localhost:" + server.getAddress().getPort();
        try {
            try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
                .endpoint(endpoint)
                .protocol("http/protobuf")
                .trustedCertificate(serverIdentity.certificate())
                .header("authorization", "Bearer accepted")
                .exportTimeout(Duration.ofSeconds(2))
                .metricInterval(Duration.ofHours(1))
                .build())) {
                recordSignals(sdk, "accepted-run");
                assertTrue(sdk.flush(5, TimeUnit.SECONDS));
            }

            assertTrue(requests.size() >= 3);
            assertEquals(Set.of("/v1/traces", "/v1/metrics", "/v1/logs"),
                requests.stream().map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet()));
            assertTrue(requests.stream().allMatch(request -> request.status() == 200));
            assertTrue(requests.stream().allMatch(request -> "Bearer accepted".equals(request.authorization())));
            assertTrue(requests.stream().allMatch(request -> request.payloadBytes() > 0));

            try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
                .endpoint(endpoint)
                .protocol("http/protobuf")
                .trustedCertificate(serverIdentity.certificate())
                .header("authorization", "Bearer rejected")
                .exportTimeout(Duration.ofSeconds(2))
                .metricInterval(Duration.ofHours(1))
                .build())) {
                recordSignals(sdk, "rejected-auth-run");
                assertFalse(sdk.flush(5, TimeUnit.SECONDS));
            }

            var rejectedRequests = requests.stream().filter(request -> request.status() == 401).toList();
            assertTrue(rejectedRequests.size() >= 3);
            assertTrue(rejectedRequests.stream().map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of("/v1/traces", "/v1/metrics", "/v1/logs")));
            assertTrue(rejectedRequests.stream()
                .allMatch(request -> "Bearer rejected".equals(request.authorization())));

            int requestsBeforeUntrustedCa = requests.size();
            try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
                .endpoint(endpoint)
                .protocol("http/protobuf")
                .trustedCertificate(unrelatedIdentity.certificate())
                .header("authorization", "Bearer accepted")
                .exportTimeout(Duration.ofMillis(500))
                .metricInterval(Duration.ofHours(1))
                .build())) {
                recordSignals(sdk, "untrusted-ca-run");
                assertFalse(sdk.flush(2, TimeUnit.SECONDS));
            }
            assertEquals(requestsBeforeUntrustedCa, requests.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sameSdkRecoversAfterCollectorReturns() throws Exception {
        AtomicBoolean collectorAvailable = new AtomicBoolean(false);
        var requests = new ConcurrentLinkedQueue<CapturedRequest>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = exchange.getRequestBody().readAllBytes();
            int status = collectorAvailable.get() ? 200 : 503;
            requests.add(new CapturedRequest(exchange.getRequestURI().getPath(), null, status, payload.length,
                sha256(payload), contains(payload, "collector-down-run"), contains(payload, "collector-recovered-run")));
            if (status == 200) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] body = "temporarily unavailable".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            Map<String, String> failedPayloads;
            try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
                .endpoint("http://localhost:" + server.getAddress().getPort())
                .protocol("http/protobuf")
                .exportTimeout(Duration.ofSeconds(2))
                .metricInterval(Duration.ofHours(1))
                .build())) {
                recordSignals(sdk, "collector-down-run");
                assertFalse(sdk.flush(5, TimeUnit.SECONDS));
                var failedRequests = requests.stream().filter(request -> request.status() == 503).toList();
                assertTrue(failedRequests.size() >= 3);
                var failedMarkerPaths = failedRequests.stream().filter(CapturedRequest::containsCollectorDownMarker)
                    .map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet());
                assertEquals(Set.of("/v1/traces", "/v1/metrics", "/v1/logs"), failedMarkerPaths);
                failedPayloads = failedRequests.stream().collect(java.util.stream.Collectors.toMap(
                    CapturedRequest::path, CapturedRequest::payloadDigest, (first, ignored) -> first));

                collectorAvailable.set(true);
                recordSignals(sdk, "collector-recovered-run");
                assertTrue(sdk.flush(5, TimeUnit.SECONDS));
            }
            var recoveredRequests = requests.stream().filter(request -> request.status() == 200).toList();
            var recoveredPaths = recoveredRequests.stream().map(CapturedRequest::path)
                .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("/v1/traces", "/v1/metrics", "/v1/logs"), recoveredPaths);
            var recoveredMarkerPaths = recoveredRequests.stream().filter(CapturedRequest::containsCollectorRecoveredMarker)
                .map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("/v1/traces", "/v1/metrics", "/v1/logs"), recoveredMarkerPaths);
            var exactRetryPaths = recoveredRequests.stream()
                .filter(request -> request.payloadDigest().equals(failedPayloads.get(request.path())))
                .map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet());
            assertTrue(exactRetryPaths.isEmpty(), "failed HTTP bodies were unexpectedly replayed: " + exactRetryPaths);
            var oldMarkerPaths = recoveredRequests.stream().filter(CapturedRequest::containsCollectorDownMarker)
                .map(CapturedRequest::path).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("/v1/metrics"), oldMarkerPaths,
                "Cumulative metric data should remain visible after the rejected export; span and log batches are not buffered");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exportTimeoutBoundsAStalledBatch() throws Exception {
        StalledReceiver receiver = startStalledReceiver(3000);
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
            .endpoint("http://localhost:" + receiver.server().getAddress().getPort())
            .protocol("http/protobuf")
            .exportTimeout(Duration.ofMillis(250))
            .metricInterval(Duration.ofHours(1))
            .build())) {
            recordSignals(sdk, "export-timeout-run");
            long started = System.nanoTime();
            assertFalse(sdk.flush(5, TimeUnit.SECONDS));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMillis < 2000, "exportTimeout did not bound the batch: " + elapsedMillis + " ms");
            assertTrue(receiver.requests().await(1, TimeUnit.SECONDS));
        } finally {
            receiver.close();
        }
    }

    @Test
    void flushTimeoutBoundsTheCallEvenWhenExporterDeadlineIsLonger() throws Exception {
        StalledReceiver receiver = startStalledReceiver(4000);
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
            .endpoint("http://localhost:" + receiver.server().getAddress().getPort())
            .protocol("http/protobuf")
            .exportTimeout(Duration.ofSeconds(2))
            .metricInterval(Duration.ofHours(1))
            .build())) {
            recordSignals(sdk, "flush-timeout-run");
            long started = System.nanoTime();
            assertFalse(sdk.flush(100, TimeUnit.MILLISECONDS));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(elapsedMillis < 1000, "flush exceeded its caller deadline: " + elapsedMillis + " ms");
        } finally {
            receiver.close();
        }
    }

    private HttpsServer startServer(TlsIdentity identity, ConcurrentLinkedQueue<CapturedRequest> requests) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext(identity)));
        server.createContext("/", exchange -> {
            byte[] payload = exchange.getRequestBody().readAllBytes();
            Headers headers = exchange.getRequestHeaders();
            String authorization = headers.getFirst("authorization");
            int status = "Bearer rejected".equals(authorization) ? 401 : 200;
            requests.add(new CapturedRequest(exchange.getRequestURI().getPath(), authorization, status, payload.length,
                sha256(payload), contains(payload, "collector-down-run"), contains(payload, "collector-recovered-run")));
            if (status == 200) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static StalledReceiver startStalledReceiver(long delayMillis) throws IOException {
        var requests = new CountDownLatch(3);
        ExecutorService executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.countDown();
            try {
                Thread.sleep(delayMillis);
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The exporter is expected to close a request after its configured deadline.
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new StalledReceiver(server, executor, requests);
    }

    private TlsIdentity createIdentity(String name) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        Path keyStore = tempDir.resolve(name + ".p12");
        Path certificate = tempDir.resolve(name + ".pem");
        runKeytool(keytool,
            "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", TEST_PASSWORD,
            "-dname", "CN=localhost", "-validity", "3650", "-ext", "SAN=dns:localhost");
        runKeytool(keytool,
            "-exportcert", "-rfc", "-alias", name, "-keystore", keyStore.toString(),
            "-storepass", TEST_PASSWORD, "-file", certificate.toString());
        return new TlsIdentity(keyStore, certificate);
    }

    private static void runKeytool(Path keytool, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(keytool.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int status = process.waitFor();
        assertEquals(0, status, new String(output, StandardCharsets.UTF_8));
    }

    private static SSLContext serverSslContext(TlsIdentity identity) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(identity.keyStore())) {
            keyStore.load(input, TEST_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, TEST_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        return sslContext;
    }

    private static void recordSignals(ObservabilitySdk sdk, String runId) {
        var attributes = Map.of("extension.name", "task-t", "task.type", "collector-config-test", "task.object", runId);
        var span = sdk.observe().trace("SendMessage", SignalKind.CLIENT, attributes);
        sdk.observe().log("gen_ai.agent.a2at.message", LogSeverity.INFO, runId, attributes, span);
        sdk.observe().metric("task.count", 1, Map.of("task.type", runId));
        span.end();
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean contains(byte[] payload, String marker) {
        return new String(payload, StandardCharsets.ISO_8859_1).contains(marker);
    }

    private record TlsIdentity(Path keyStore, Path certificate) { }
    private record CapturedRequest(String path, String authorization, int status, int payloadBytes,
        String payloadDigest, boolean containsCollectorDownMarker, boolean containsCollectorRecoveredMarker) { }

    private record StalledReceiver(HttpServer server, ExecutorService executor, CountDownLatch requests) {
        void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
