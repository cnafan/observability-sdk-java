package net.openan.observability;

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class ObservationSpan implements AutoCloseable {
    private static final Logger DIAGNOSTICS = Logger.getLogger(ObservationSpan.class.getName());
    private final Span span;
    private final Context context;
    private final Definitions.Signal definition;
    private final AtomicBoolean ended = new AtomicBoolean();
    ObservationSpan(Span span, Context parent, Definitions.Signal definition) { this.span = span; this.context = parent.with(span); this.definition = definition; }
    static ObservationSpan noop() { return new ObservationSpan(Span.getInvalid(), Context.root(), null); }
    public void attributes(Map<String, ?> attrs) {
        if (definition == null) return;
        try { span.setAllAttributes(AttributesConverter.convertSignal(attrs, 'S', definition, false)); }
        catch (RuntimeException ex) { DIAGNOSTICS.warning("Observation span attributes rejected"); }
    }
    public void ok() { span.setStatus(StatusCode.OK); }
    public void error() { span.setStatus(StatusCode.ERROR); }
    public Scope makeCurrent() { return context().makeCurrent(); }
    Context context() { return definition == null ? Context.current() : context; }
    @Override public void close() { if (ended.compareAndSet(false, true)) span.end(); }
    public void end() { close(); }
}
