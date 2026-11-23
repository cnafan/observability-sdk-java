package net.openan.observability;

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records A2A-T, workflow, and agent signals through the configured OpenTelemetry providers.
 *
 * <p>Invalid signal names or attributes are rejected locally and reported through the SDK diagnostics logger.
 */
public final class Observability {
    private static final java.util.logging.Logger DIAGNOSTICS = java.util.logging.Logger.getLogger(Observability.class.getName());
    private final Tracer tracer;
    private final Logger logger;
    private final Map<String, DoubleHistogram> histograms = new HashMap<>();
    private final Map<String, LongCounter> counters = new HashMap<>();
    private final AtomicBoolean enabled;
    Observability(OpenTelemetry otel) {
        this(otel, new AtomicBoolean(true));
    }
    Observability(OpenTelemetry otel, AtomicBoolean enabled) {
        this.enabled = enabled;
        tracer = otel.getTracer("a2at.observability");
        logger = otel.getLogsBridge().get("a2at.observability");
        Meter meter = otel.getMeter("a2at.observability");
        Definitions.METRICS.forEach((name, metric) -> registerMetric(meter, metric.name(), metric));
    }
    private void registerMetric(Meter meter, String name, Definitions.Metric metric) {
        if (metric.histogram()) histograms.put(name, meter.histogramBuilder(name).setUnit(metric.unit()).build());
        else counters.put(name, meter.counterBuilder(name).setUnit(metric.unit()).build());
    }
    /**
     * Starts a trace span with the current context as its parent.
     *
     * @return a span handle; it is safe to close even when the requested signal is rejected
     */
    public ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes) { return trace(name, kind, attributes, null); }
    /**
     * Starts a trace span using an explicit parent. Stream-event signals add the parent as a span link.
     *
     * @return a span handle; it is safe to close even when the requested signal is rejected
     */
    public ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes, ObservationSpan parent) {
        if (!enabled.get()) return ObservationSpan.noop();
        Context context = parent == null ? Context.current() : parent.context();
        try {
            boolean streamEvent = Definitions.STREAM_EVENT.equals(name) || Definitions.STREAM_ERROR.equals(name);
            boolean clientOnly = Definitions.A2A_CLIENT_ONLY_SPAN_NAMES.contains(name);
            boolean a2a = Definitions.A2A_SPAN_NAMES.contains(name);
            Definitions.Signal definition;
            if (a2a) {
                if (kind != SignalKind.CLIENT && (kind != SignalKind.SERVER || clientOnly)) throw new IllegalArgumentException();
                Definitions.Signal extension = AttributesConverter.selectOptionalSignal(attributes, Definitions.SPANS);
                definition = Definitions.span(name, extension);
            } else {
                if (kind != SignalKind.INTERNAL || streamEvent) throw new IllegalArgumentException();
                definition = Definitions.namedTrace(name);
                if (definition == null) throw new IllegalArgumentException();
            }
            Attributes attrs = AttributesConverter.convertSignal(attributes, 'S', definition, true);
            SpanBuilder builder = tracer.spanBuilder(name).setSpanKind(SpanKind.valueOf(kind.name())).setAllAttributes(attrs);
            if (streamEvent) {
                builder.setNoParent();
                SpanContext link = Span.fromContext(context).getSpanContext();
                if (link.isValid()) builder.addLink(link);
            } else {
                builder.setParent(context);
            }
            Span span = builder.startSpan();
            return new ObservationSpan(span, context, definition);
        } catch (RuntimeException e) {
            DIAGNOSTICS.warning("Observation trace rejected");
            return ObservationSpan.noop();
        }
    }
    /** Records a configured counter or histogram value. Invalid values and dimensions are discarded. */
    public void metric(String id, Number value, Map<String, ?> attributes) {
        if (!enabled.get()) return;
        try {
            if (id == null || value == null) throw new IllegalArgumentException();
            String name = Definitions.shortName(id);
            Definitions.Metric definition = Definitions.METRICS.get(name);
            if (definition == null || id.startsWith(Definitions.PREFIX) && definition.a2a()) throw new IllegalArgumentException();
            boolean a2a = definition.a2a();
            double number = value.doubleValue();
            if (!Double.isFinite(number) || number < 0
                || value instanceof java.math.BigDecimal decimal && decimal.signum() < 0
                || value instanceof java.math.BigInteger integer && integer.signum() < 0) throw new IllegalArgumentException();
            if (definition.integer() && !(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
            Attributes attrs = AttributesConverter.convert(attributes, 'M', definition.dimensions());
            if (a2a) {
                if (attrs.get(AttributeKey.stringKey(Definitions.A2A_PREFIX + "operation.name")) == null) throw new IllegalArgumentException();
                attrs = attrs.toBuilder().put("gen_ai.system", "a2a").build();
            } else {
                for (String required : definition.requiredDimensions()) {
                    Object requiredValue = attrs.asMap().entrySet().stream()
                        .filter(entry -> entry.getKey().getKey().equals(Definitions.attributeName(required)))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                    if (requiredValue == null || requiredValue instanceof String text && text.isBlank()) throw new IllegalArgumentException();
                }
            }
            String instrumentName = definition.name();
            if (definition.histogram()) histograms.get(instrumentName).record(number, attrs);
            else counters.get(instrumentName).add(value.longValue(), attrs);
        } catch (RuntimeException e) { DIAGNOSTICS.warning("Observation metric rejected"); }
    }
    /** Emits a log record using the current context. */
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes) {
        if (!enabled.get()) return;
        emitLog(name, severity, body, attributes, Context.current());
    }
    /** Emits a log record associated with an explicit OpenTelemetry context. */
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes, Context context) {
        if (!enabled.get()) return;
        emitLog(name, severity, body, attributes, context == null ? Context.current() : context);
    }
    /** Emits a log record associated with the supplied span. */
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes, ObservationSpan span) {
        if (!enabled.get()) return;
        emitLog(name, severity, body, attributes, span == null ? Context.current() : span.context());
    }
    private void emitLog(String name, LogSeverity severity, String body, Map<String, ?> attributes, Context context) {
        try {
            if (severity == null || body == null) throw new IllegalArgumentException();
            Definitions.Signal definition = Definitions.MESSAGE_LOG.equals(name)
                ? AttributesConverter.selectSignal(attributes, Definitions.LOGS) : Definitions.namedLog(name);
            if (definition == null) throw new IllegalArgumentException();
            Attributes attrs = AttributesConverter.convertSignal(attributes, 'L', definition, true);
            logger.logRecordBuilder().setEventName(name).setSeverity(Severity.valueOf(severity.name()))
                .setBody(AttributesConverter.clean(body)).setAllAttributes(attrs).setContext(context).emit();
        } catch (RuntimeException e) { DIAGNOSTICS.warning("Observation log rejected"); }
    }
}
