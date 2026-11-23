package net.openan.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdkConfigTest {
    @TempDir Path tempDir;

    @Test
    void explicitValuesOverrideEnvironment() {
        var config = SdkConfig.builder()
            .endpoint("http://localhost:4317")
            .protocol("grpc")
            .serviceName("explicit")
            .metricInterval(Duration.ofSeconds(2))
            .exportTimeout(Duration.ofMillis(321))
            .headers(Map.of("Authorization", "Bearer explicit"))
            .build(Map.of(
                "OTEL_SERVICE_NAME", "environment",
                "OTEL_METRIC_EXPORT_INTERVAL", "invalid",
                "OTEL_EXPORTER_OTLP_TIMEOUT", "invalid",
                "OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20environment,environment-only=x"));

        assertEquals("explicit", config.serviceName);
        assertEquals(Duration.ofSeconds(2), config.metricInterval);
        assertEquals(Duration.ofMillis(321), config.exportTimeout);
        assertEquals(Map.of("authorization", "Bearer explicit"), config.headers);
    }

    @Test
    void environmentValuesAreParsedWithOtlpUnitsAndEncoding() {
        var config = SdkConfig.builder().build(Map.of(
            "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
            "OTEL_SERVICE_NAME", "test",
            "OTEL_METRIC_EXPORT_INTERVAL", "123",
            "OTEL_EXPORTER_OTLP_TIMEOUT", "456",
            "OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20token,x-value=a%3Db%2Cc"));

        assertEquals("http://localhost:4318", config.endpoint.toString());
        assertEquals(123, config.metricInterval.toMillis());
        assertEquals(456, config.exportTimeout.toMillis());
        assertEquals("Bearer token", config.headers.get("authorization"));
        assertEquals("a=b,c", config.headers.get("x-value"));
    }

    @Test
    void zeroExportTimeoutMeansNoExporterDeadline() {
        var config = SdkConfig.builder().build(Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "0"));
        assertEquals(Duration.ZERO, config.exportTimeout);
    }

    @Test
    void invalidConfigurationNamesTheSettingWithoutEchoingValues() {
        var badProtocol = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().build(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json")));
        assertEquals("Invalid OTEL_EXPORTER_OTLP_PROTOCOL", badProtocol.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().endpoint("http://u:p@localhost:4317").build(Map.of()));
        var badMetricInterval = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().build(Map.of("OTEL_METRIC_EXPORT_INTERVAL", "0")));
        assertEquals("Invalid OTEL_METRIC_EXPORT_INTERVAL", badMetricInterval.getMessage());
        var badExportTimeout = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().build(Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "-1")));
        assertEquals("Invalid OTLP export timeout", badExportTimeout.getMessage());

        var badHeaders = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().build(Map.of(
            "OTEL_EXPORTER_OTLP_HEADERS", "=test-secret")));
        assertEquals("Invalid OTEL_EXPORTER_OTLP_HEADERS", badHeaders.getMessage());
        assertFalse(badHeaders.getMessage().contains("test-secret"));

        var duplicateHeaders = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().headers(Map.of(
            "Authorization", "first", "authorization", "second")).build(Map.of()));
        assertEquals("Invalid OTLP request headers", duplicateHeaders.getMessage());
    }

    @Test
    void certificateErrorsAreSanitizedAndCertificateRequiresTls() {
        Path missingCertificate = tempDir.resolve("private-location-ca.pem");
        var missing = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder()
            .endpoint("https://localhost:4318")
            .trustedCertificate(missingCertificate)
            .build(Map.of()));
        assertEquals("Invalid OTEL_EXPORTER_OTLP_CERTIFICATE", missing.getMessage());
        assertFalse(missing.getMessage().contains(missingCertificate.toString()));

        var insecure = assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder()
            .endpoint("http://localhost:4318")
            .trustedCertificate(missingCertificate)
            .build(Map.of()));
        assertEquals("OTEL_EXPORTER_OTLP_CERTIFICATE requires an https endpoint", insecure.getMessage());
        assertFalse(insecure.getMessage().contains(missingCertificate.toString()));
    }

    @Test
    void unavailableCollectorDoesNotBlockRecordingAndFlushHonorsTimeout() {
        var sdk = ObservabilitySdk.start(SdkConfig.builder()
            .endpoint("http://127.0.0.1:1")
            .protocol("grpc")
            .exportTimeout(Duration.ofMillis(500))
            .build());
        long start = System.nanoTime();
        var span = sdk.observe().trace("SendMessage", SignalKind.CLIENT, Map.of("extension.name", "task-t", "task.type", "test"));
        sdk.observe().log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", Map.of("extension.name", "task-t", "task.type", "test"), span);
        sdk.observe().metric("task.count", 1, Map.of("task.type", "test"));
        span.end();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);

        start = System.nanoTime();
        assertFalse(sdk.flush(1, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        sdk.close();
    }

}
