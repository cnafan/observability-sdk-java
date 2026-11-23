package net.openan.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceContextPropagationTest {
    @Test void injectsAndExtractsW3cContextForSdkSpans() {
        var exporter = InMemorySpanExporter.create();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build()) {
            var observe = new Observability(sdk);
            var carrier = new LinkedHashMap<String, String>();
            var client = observe.trace("SendMessage", SignalKind.CLIENT, Map.of());
            try (var scope = client.makeCurrent()) {
                TraceContextPropagation.inject(carrier, Map::put);
            }
            assertTrue(carrier.containsKey("traceparent"));
            try (var scope = TraceContextPropagation.extractToCurrent(carrier, new TraceContextPropagation.Getter<Map<String, String>>() {
                @Override public Iterable<String> keys(Map<String, String> headers) { return headers.keySet(); }
                @Override public String get(Map<String, String> headers, String key) { return headers.get(key); }
            })) {
                assertTrue(Span.current().getSpanContext().isValid());
                observe.trace("SendMessage", SignalKind.SERVER, Map.of()).end();
            }
            client.end();
            var server = exporter.getFinishedSpanItems().get(0);
            var clientData = exporter.getFinishedSpanItems().get(1);
            assertEquals(clientData.getTraceId(), server.getTraceId());
            assertEquals(clientData.getSpanId(), server.getParentSpanContext().getSpanId());
        }
    }

    @Test void recordsThrowableAndMarksSpanAsError() {
        var exporter = InMemorySpanExporter.create();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build()) {
            var span = new Observability(sdk).trace("SendMessage", SignalKind.CLIENT, Map.of());
            span.error(new IllegalStateException("unavailable"));
            span.end();
            var data = exporter.getFinishedSpanItems().get(0);
            assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, data.getStatus().getStatusCode());
            assertEquals("exception", data.getEvents().get(0).getName());
            assertThrows(NullPointerException.class, () -> span.error(null));
        }
    }
}
