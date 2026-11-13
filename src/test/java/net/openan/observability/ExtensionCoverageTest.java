package net.openan.observability;

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import static org.junit.jupiter.api.Assertions.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExtensionCoverageTest {
    static final String P = "gen_ai.agent.a2at.";
    static final Map<String, Object> TASK = Map.of("extension.name", "TASK-T", "task.type", "diagnosis", "task.object", "line");
    static final Map<String, Object> NEG = Map.of("extension.name", "NEGOTIATION-T", "negotiation.id", "n1", "negotiation.round", 2, "negotiation.total_rounds", 3, "negotiation.result", "ACCEPT", "negotiation.performative", "PROPOSE");
    static final Map<String, Object> NOTIFY = Map.of("extension.name", "notification-t", "notification.topic", "topic", "push.notification.url", "https://user:pass@example.com/path?token=abc");
    static final Map<String, Object> AUTH = Map.of("extension.name", "authorization-t", "authorization.stage", "EXECUTE", "authorization.policy.operation.type", "create");
    static AttributeKey<String> str(String key) { return AttributeKey.stringKey(P + key); }
    static AttributeKey<Long> lng(String key) { return AttributeKey.longKey(P + key); }

    @Test void everyAttributeIsExportedWithExpectedTypeAndAssociation() {
        var spans = InMemorySpanExporter.create(); var logs = InMemoryLogRecordExporter.create();
        try (var sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()).build()) {
            var observe = new Observability(sdk);
            for (var attrs : List.of(TASK, NEG, NOTIFY, AUTH)) {
                var logAttrs = new HashMap<>(attrs);
                if (attrs == NEG) logAttrs.put("negotiation.max_rounds", 4);
                var span = observe.trace("message/send", SignalKind.CLIENT, attrs);
                observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", logAttrs, span);
                span.end();
            }
            assertEquals(4, spans.getFinishedSpanItems().size()); assertEquals(4, logs.getFinishedLogRecordItems().size());
            for (int i = 0; i < 4; i++) {
                var span = spans.getFinishedSpanItems().get(i); var log = logs.getFinishedLogRecordItems().get(i);
                assertEquals(span.getTraceId(), log.getSpanContext().getTraceId());
                assertEquals(span.getSpanId(), log.getSpanContext().getSpanId());
                assertEquals(span.getAttributes().get(str("extension.name")), log.getAttributes().get(str("extension.name")));
            }
            Attributes t = spans.getFinishedSpanItems().get(0).getAttributes();
            assertEquals("task-t", t.get(str("extension.name"))); assertEquals("diagnosis", t.get(str("task.type"))); assertEquals("line", t.get(str("task.object")));
            Attributes n = spans.getFinishedSpanItems().get(1).getAttributes();
            assertEquals("negotiation-t", n.get(str("extension.name"))); assertEquals("n1", n.get(str("negotiation.id")));
            assertEquals(2L, n.get(lng("negotiation.round"))); assertEquals(3L, n.get(lng("negotiation.total_rounds")));
            assertEquals("accept", n.get(str("negotiation.result"))); assertEquals("propose", n.get(str("negotiation.performative")));
            assertEquals(4L, logs.getFinishedLogRecordItems().get(1).getAttributes().get(lng("negotiation.max_rounds")));
            Attributes notification = spans.getFinishedSpanItems().get(2).getAttributes();
            assertEquals("topic", notification.get(str("notification.topic"))); assertEquals("https://example.com/path", notification.get(str("push.notification.url")));
            assertEquals("https://example.com/path", logs.getFinishedLogRecordItems().get(2).getAttributes().get(str("push.notification.url")));
            Attributes auth = spans.getFinishedSpanItems().get(3).getAttributes();
            assertEquals("execute", auth.get(str("authorization.stage"))); assertEquals("create", auth.get(str("authorization.policy.operation.type")));
            for (int i = 0; i < 4; i++) {
                var sa = spans.getFinishedSpanItems().get(i).getAttributes(); var la = logs.getFinishedLogRecordItems().get(i).getAttributes();
                sa.forEach((k, v) -> assertEquals(v, la.asMap().get(k)));
            }
        }
    }
    @Test void rejectCrossExtensionInvalidValuesAndAtomicChanges() {
        var spans = InMemorySpanExporter.create(); var logs = InMemoryLogRecordExporter.create();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()).build()) {
            var o = new Observability(sdk);
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "unknown")).end();
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t", "task.type", "x")).end();
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t", "negotiation.max_rounds", 2)).end();
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t", "negotiation.round", "2")).end();
            o.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", Map.of("extension.name", "negotiation-t", "negotiation.result", "wrong"));
            o.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", Map.of("extension.name", "notification-t", "authorization.stage", "execute"));
            assertTrue(spans.getFinishedSpanItems().isEmpty()); assertTrue(logs.getFinishedLogRecordItems().isEmpty());
            var span = o.trace("message/send", SignalKind.SERVER, NEG);
            span.attributes(Map.of("negotiation.round", 5));
            span.attributes(Map.of("extension.name", "task-t", "negotiation.round", 8));
            span.attributes(Map.of("negotiation.round", 9, "negotiation.max_rounds", 10));
            span.end();
            var attrs = spans.getFinishedSpanItems().get(0).getAttributes();
            assertEquals(5L, attrs.get(lng("negotiation.round"))); assertEquals("negotiation-t", attrs.get(str("extension.name")));
        }
    }
    @Test void metricsUseIndependentDimensionsAndRejectNegativeUnderflow() {
        var reader = InMemoryMetricReader.create();
        try (var sdk = OpenTelemetrySdk.builder().setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build()).build()) {
            var o = new Observability(sdk);
            Map<String, Map<String, Object>> inputs = Map.of(
                "task.duration", Map.of("task.type", "t"), "task.count", Map.of("task.type", "t"),
                "negotiation.rounds", Map.of("negotiation.id", "n", "negotiation.result", "accept"),
                "negotiation.count", Map.of("negotiation.result", "accept"), "notification.count", Map.of("notification.topic", "t"),
                "authorization.preauthorize.count", Map.of("authorization.policy.operation.type", "create"),
                "authorization.preauthorize.duration", Map.of("authorization.policy.operation.type", "create"),
                "authorization.execute.count", Map.of("authorization.policy.operation.type", "create"));
            inputs.forEach((id, attrs) -> o.metric(id, 2, attrs));
            o.metric("task.duration", new BigDecimal("-1e-400"), Map.of("task.type", "t"));
            o.metric("negotiation.count", 999, Map.of("negotiation.round", 1));
            var data = reader.collectAllMetrics(); assertEquals(8, data.size());
            for (var metric : data) {
                String id = metric.getName().substring(P.length());
                boolean histogram = Set.of("task.duration", "negotiation.rounds", "authorization.preauthorize.duration").contains(id);
                assertEquals(id.equals("negotiation.rounds") ? "{round}" : id.endsWith("duration") ? "ms" : "", metric.getUnit());
                var point = histogram ? metric.getHistogramData().getPoints().iterator().next() : metric.getLongSumData().getPoints().iterator().next();
                assertEquals(inputs.get(id).size(), point.getAttributes().size());
                inputs.get(id).forEach((key, val) -> assertEquals(val, point.getAttributes().get(str(key))));
                if (histogram) { var h = metric.getHistogramData().getPoints().iterator().next(); assertEquals(1, h.getCount()); assertEquals(2, h.getSum()); }
                else { assertTrue(metric.getLongSumData().isMonotonic()); assertEquals(2, metric.getLongSumData().getPoints().iterator().next().getValue()); }
            }
        }
    }
    @Test void optionalFieldsAndFullNameAreAccepted() {
        var spans = InMemorySpanExporter.create(); var logs = InMemoryLogRecordExporter.create();
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build()).build()) {
            var o = new Observability(sdk);
            for (String ext : List.of("negotiation-t", "notification-t", "authorization-t")) {
                var attrs = Map.of(P + "extension.name", ext);
                var span = o.trace("message/send", SignalKind.CLIENT, attrs);
                o.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "body", attrs, span); span.end();
            }
            assertEquals(3, spans.getFinishedSpanItems().size()); assertEquals(3, logs.getFinishedLogRecordItems().size());
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "task-t", P + "extension.name", "task-t", "task.type", "x")).end();
            assertEquals(3, spans.getFinishedSpanItems().size());
        }
    }
    @Test void quotedCredentialsAreFullyMasked() {
        String result = AttributesConverter.clean("password=\"hello world\" token='foo bar'");
        assertFalse(result.contains("hello")); assertFalse(result.contains("world")); assertFalse(result.contains("foo")); assertFalse(result.contains("bar"));
    }
}
