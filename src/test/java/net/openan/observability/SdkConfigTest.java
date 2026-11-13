package net.openan.observability;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkConfigTest {
    @Test void explicitValuesOverrideEnvironment() {
        var config = SdkConfig.builder().endpoint("http://localhost:4317").protocol("grpc").serviceName("explicit").metricInterval(Duration.ofSeconds(2)).build(Map.of("OTEL_SERVICE_NAME", "environment", "OTEL_METRIC_EXPORT_INTERVAL", "invalid"));
        assertEquals("explicit", config.serviceName); assertEquals(Duration.ofSeconds(2), config.metricInterval);
    }
    @Test void unavailableCollectorDoesNotBlockRecordingAndFlushHonorsTimeout() {
        var sdk = ObservabilitySdk.start(SdkConfig.builder().endpoint("http://127.0.0.1:1").protocol("grpc").build());
        long start = System.nanoTime();
        var span = sdk.observe().trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "task-t", "task.type", "test"));
        sdk.observe().log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", Map.of("extension.name", "task-t", "task.type", "test"), span);
        sdk.observe().metric("task.count", 1, Map.of("task.type", "test"));
        span.end();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        start = System.nanoTime();
        assertFalse(sdk.flush(1, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        sdk.close();
    }
    @Test void environmentAndInvalidConfiguration() {
        var config = SdkConfig.builder().build(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf", "OTEL_SERVICE_NAME", "test", "OTEL_METRIC_EXPORT_INTERVAL", "123"));
        assertEquals("http://localhost:4318", config.endpoint.toString()); assertEquals(123, config.metricInterval.toMillis());
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().build(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json")));
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().endpoint("http://u:p@localhost:4317").build(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.builder().build(Map.of("OTEL_METRIC_EXPORT_INTERVAL", "0")));
    }
}
