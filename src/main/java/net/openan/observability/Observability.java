package net.openan.observability;

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;

/** Receives measurements supplied by instrumented SDKs. */
public final class Observability {
    private static final java.util.logging.Logger DIAGNOSTICS = java.util.logging.Logger.getLogger(Observability.class.getName());
    private final Tracer tracer;
    private final Logger logger;
    private final Map<String, DoubleHistogram> histograms = new HashMap<>();
    private final Map<String, LongCounter> counters = new HashMap<>();
    Observability(OpenTelemetry otel) {
        tracer = otel.getTracer("a2at.observability");
        logger = otel.getLogsBridge().get("a2at.observability");
        Meter meter = otel.getMeter("a2at.observability");
        Definitions.METRICS.forEach((name, metric) -> {
            if (metric.histogram()) histograms.put(name, meter.histogramBuilder(Definitions.PREFIX + name).setUnit(metric.unit()).build());
            else counters.put(name, meter.counterBuilder(Definitions.PREFIX + name).setUnit(metric.unit()).build());
        });
    }
    public ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes) { return trace(name, kind, attributes, null); }
    /** Explicit parent supports passing the SDK handle between threads. */
    public ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes, ObservationSpan parent) {
        Context context = parent == null ? Context.current() : parent.context();
        try {
            if (!Definitions.MESSAGE_SPAN.equals(name) || (kind != SignalKind.CLIENT && kind != SignalKind.SERVER)) throw new IllegalArgumentException();
            Definitions.Signal definition = AttributesConverter.selectSignal(attributes, Definitions.SPANS);
            Attributes attrs = AttributesConverter.convertSignal(attributes, 'S', definition, true);
            Span span = tracer.spanBuilder(name).setSpanKind(SpanKind.valueOf(kind.name())).setParent(context).setAllAttributes(attrs).startSpan();
            return new ObservationSpan(span, context, definition);
        } catch (RuntimeException e) {
            DIAGNOSTICS.warning("Observation trace rejected");
            return ObservationSpan.noop();
        }
    }
    public void metric(String id, Number value, Map<String, ?> attributes) {
        try {
            if (id == null || value == null) throw new IllegalArgumentException();
            String name = Definitions.shortName(id);
            Definitions.Metric definition = Definitions.METRICS.get(name);
            if (definition == null) throw new IllegalArgumentException();
            double number = value.doubleValue();
            if (!Double.isFinite(number) || number < 0
                || value instanceof java.math.BigDecimal decimal && decimal.signum() < 0
                || value instanceof java.math.BigInteger integer && integer.signum() < 0) throw new IllegalArgumentException();
            if (definition.integer() && !(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
            Attributes attrs = AttributesConverter.convert(attributes, 'M', definition.dimensions());
            if ((name.equals("task.duration") || name.equals("task.count"))) {
                String taskType = attrs.get(io.opentelemetry.api.common.AttributeKey.stringKey(Definitions.PREFIX + "task.type"));
                if (taskType == null || taskType.isBlank()) throw new IllegalArgumentException();
            }
            if (definition.histogram()) histograms.get(name).record(number, attrs);
            else counters.get(name).add(value.longValue(), attrs);
        } catch (RuntimeException e) { DIAGNOSTICS.warning("Observation metric rejected"); }
    }
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes) { log(name, severity, body, attributes, null); }
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes, ObservationSpan span) {
        try {
            if (!Definitions.MESSAGE_LOG.equals(name) || severity == null || body == null) throw new IllegalArgumentException();
            Definitions.Signal definition = AttributesConverter.selectSignal(attributes, Definitions.LOGS);
            Attributes attrs = AttributesConverter.convertSignal(attributes, 'L', definition, true);
            logger.logRecordBuilder().setEventName(name).setSeverity(Severity.valueOf(severity.name()))
                .setBody(AttributesConverter.clean(body)).setAllAttributes(attrs).setContext(span == null ? Context.current() : span.context()).emit();
        } catch (RuntimeException e) { DIAGNOSTICS.warning("Observation log rejected"); }
    }
}
