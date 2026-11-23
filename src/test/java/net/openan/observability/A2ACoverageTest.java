package net.openan.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class A2ACoverageTest {
    @Test void acceptsListedOperationsWithTheirKinds() {
        var spans = InMemorySpanExporter.create();
        try (var sdk = tracingSdk(spans)) {
            var observe = new Observability(sdk);
            var methods = List.of("SendMessage", "SendStreamingMessage", "GetTask", "CancelTask", "ListTasks",
                "SubscribeToTask", "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig",
                "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig");
            for (String method : methods) {
                observe.trace(method, SignalKind.CLIENT, Map.of()).end();
                observe.trace(method, SignalKind.SERVER, Map.of()).end();
            }
            for (String method : List.of("SendStreamingMessage-event", "SendStreamingMessage-end")) {
                observe.trace(method, SignalKind.CLIENT, Map.of()).end();
                observe.trace(method, SignalKind.SERVER, Map.of()).end();
            }
            observe.trace("agent/get", SignalKind.CLIENT, Map.of()).end();
            assertEquals(24, spans.getFinishedSpanItems().size());
        }
    }

    @Test void combinesA2AAndTaskAttributesAndLinksStreamEvent() {
        var spans = InMemorySpanExporter.create();
        try (var sdk = tracingSdk(spans)) {
            var observe = new Observability(sdk);
            Map<String, Object> attrs = Map.ofEntries(
                Map.entry("gen_ai.agent.a2a.operation.name", "SendStreamingMessage"), Map.entry("task_id", "task"),
                Map.entry("context_id", "ctx"), Map.entry("message_id", "msg"), Map.entry("role", "AGENT"),
                Map.entry("extensions", "task-t"), Map.entry("parts.number", 2), Map.entry("config_id", "config"),
                Map.entry("request", "https://example.com/test?token=secret"), Map.entry("error.type", "timeout"),
                Map.entry("extension.name", "task-t"), Map.entry("task.type", "diagnosis"));
            var stream = observe.trace("SendStreamingMessage", SignalKind.CLIENT, attrs);
            stream.attributes(Map.of("response", "redacted summary"));
            stream.attributes(Map.of("streaming-event", "wrong location", "response", "must not replace"));
            observe.trace("SendStreamingMessage-event", SignalKind.CLIENT, Map.of("streaming-event", "status update"), stream).end();
            stream.end();

            var event = spans.getFinishedSpanItems().get(0);
            var parent = spans.getFinishedSpanItems().get(1);
            assertEquals(parent.getSpanContext(), event.getLinks().get(0).getSpanContext());
            assertFalse(event.getParentSpanContext().isValid());
            assertEquals("status update", event.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.a2a.streaming-event")));
            var stored = parent.getAttributes();
            assertEquals(Set.of("gen_ai.agent.a2a.operation.name", "gen_ai.agent.a2a.task_id", "gen_ai.agent.a2a.context_id",
                "gen_ai.agent.a2a.message_id", "gen_ai.agent.a2a.role", "gen_ai.agent.a2a.extensions", "gen_ai.agent.a2a.parts.number",
                "gen_ai.agent.a2a.config_id", "gen_ai.agent.a2a.request", "gen_ai.agent.a2a.response", "error.type",
                "gen_ai.agent.a2at.extension.name", "gen_ai.agent.a2at.task.type"),
                stored.asMap().keySet().stream().map(AttributeKey::getKey).collect(Collectors.toSet()));
            assertEquals("AGENT", stored.get(AttributeKey.stringKey("gen_ai.agent.a2a.role")));
            assertEquals(2L, stored.get(AttributeKey.longKey("gen_ai.agent.a2a.parts.number")));
            assertEquals("https://example.com/test", stored.get(AttributeKey.stringKey("gen_ai.agent.a2a.request")));
            assertEquals("redacted summary", stored.get(AttributeKey.stringKey("gen_ai.agent.a2a.response")));
        }
    }

    @Test void rejectsWrongSignalFieldsAndDuplicateAliases() {
        var spans = InMemorySpanExporter.create();
        try (var sdk = tracingSdk(spans)) {
            var observe = new Observability(sdk);
            observe.trace("SendMessage", SignalKind.CLIENT, Map.of("gen_ai.system", "a2a")).end();
            observe.trace("SendMessage", SignalKind.CLIENT, Map.of("gen_ai.agent.a2at.task_id", "x")).end();
            observe.trace("SendMessage", SignalKind.CLIENT, Map.of("parts.number", -1)).end();
            observe.trace("SendMessage", SignalKind.CLIENT, Map.of("role", "admin")).end();
            observe.trace("SendMessage", SignalKind.CLIENT,
                Map.of("operation.name", "SendMessage", "gen_ai.agent.a2a.operation.name", "SendMessage")).end();
            observe.trace("GetTask", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t")).end();
            assertEquals(1, spans.getFinishedSpanItems().size());
            assertEquals("admin", spans.getFinishedSpanItems().get(0).getAttributes()
                .get(AttributeKey.stringKey("gen_ai.agent.a2a.role")));
        }
    }

    @Test void exportsTwoSecondHistogramsWithA2ADimensions() {
        var reader = InMemoryMetricReader.create();
        try (var sdk = OpenTelemetrySdk.builder().setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build()).build()) {
            var observe = new Observability(sdk);
            String operation = "gen_ai.client.operation.duration";
            String streaming = "gen_ai.agent.a2a.streaming.duration";
            observe.metric(operation, 0.125, Map.of("operation.name", "SendMessage"));
            observe.metric(streaming, 0.5, Map.of("gen_ai.agent.a2a.operation.name", "SendStreamingMessage", "error.type", "timeout", "gen_ai.system", "a2a"));
            observe.metric(operation, 100, Map.of());
            observe.metric(operation, 100, Map.of("operation.name", "SendMessage", "gen_ai.system", "wrong"));
            observe.metric(operation, 100, Map.of("operation.name", "SendMessage", "task_id", "wrong"));
            observe.metric("gen_ai.agent.a2at." + operation, 100, Map.of("operation.name", "SendMessage"));

            var metrics = reader.collectAllMetrics();
            assertEquals(2, metrics.size());
            for (var metric : metrics) {
                boolean stream = metric.getName().equals(streaming);
                assertEquals(stream ? streaming : operation, metric.getName());
                assertEquals("s", metric.getUnit());
                var points = metric.getHistogramData().getPoints();
                assertEquals(1, points.size());
                var point = points.iterator().next();
                assertEquals(1, point.getCount());
                assertEquals(stream ? 0.5 : 0.125, point.getSum());
                var expected = new HashMap<>(Map.of("gen_ai.system", "a2a",
                    "gen_ai.agent.a2a.operation.name", stream ? "SendStreamingMessage" : "SendMessage"));
                if (stream) expected.put("error.type", "timeout");
                assertEquals(expected.size(), point.getAttributes().size());
                expected.forEach((key, value) -> assertEquals(value, point.getAttributes().get(AttributeKey.stringKey(key))));
            }
        }
    }

    private static OpenTelemetrySdk tracingSdk(InMemorySpanExporter spans) {
        return OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build()).build();
    }
}
