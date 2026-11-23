package net.openan.observability.internal;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Loads the versioned, bundled signal configuration. */
final class SignalConfigurationLoader {
    private static final List<String> RESOURCES = List.of("observability/a2at.yaml", "observability/agent.yaml", "observability/workflow.yaml");

    static Catalog load() {
        Map<String, Definitions.Metric> metrics = new LinkedHashMap<>();
        Map<String, Definitions.Signal> spans = new LinkedHashMap<>();
        Map<String, Definitions.Signal> logs = new LinkedHashMap<>();
        Map<String, Definitions.NamedSignal> traces = new LinkedHashMap<>();
        Map<String, Definitions.NamedSignal> namedLogs = new LinkedHashMap<>();
        Map<String, Definitions.Attribute> attributes = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        A2aProtocol a2a = null;
        for (String resource : RESOURCES) {
            A2aProtocol loaded = load(resource, metrics, spans, logs, traces, namedLogs, attributes, versions);
            if (loaded != null) {
                if (a2a != null) throw new IllegalStateException("Duplicate A2A protocol configuration");
                a2a = loaded;
            }
        }
        if (a2a == null) throw new IllegalStateException("Missing A2A protocol configuration");
        return new Catalog(Map.copyOf(metrics), Map.copyOf(spans), Map.copyOf(logs), Map.copyOf(traces), Map.copyOf(namedLogs), Map.copyOf(attributes), Map.copyOf(versions), a2a);
    }

    private static A2aProtocol load(String resource, Map<String, Definitions.Metric> metrics, Map<String, Definitions.Signal> spans,
        Map<String, Definitions.Signal> logs, Map<String, Definitions.NamedSignal> traces, Map<String, Definitions.NamedSignal> namedLogs,
        Map<String, Definitions.Attribute> attributes, Map<String, String> versions) {
        try (InputStream stream = SignalConfigurationLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing signal configuration: " + resource);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(stream);
            Map<String, Object> root = map(loaded, resource);
            String signal = string(root.get("signal"), resource + ".signal");
            String version = string(root.get("version"), resource + ".version");
            if (versions.putIfAbsent(signal, version) != null) throw new IllegalStateException("Duplicate signal configuration: " + signal);
            loadAttributes(root.get("attributes"), resource + ".attributes", attributes);
            for (Map.Entry<String, Object> entry : map(root.get("metrics"), resource + ".metrics").entrySet()) {
                Map<String, Object> item = map(entry.getValue(), resource + ".metrics." + entry.getKey());
                Map<String, Object> metricAttributes = map(item.get("attributes"), resource + ".metrics." + entry.getKey() + ".attributes");
                boolean histogram = string(item.get("type"), entry.getKey() + ".type").equals("histogram");
                boolean integer = string(item.get("value_type"), entry.getKey() + ".value_type").equals("integer");
                boolean a2a = optionalString(item.get("protocol"), entry.getKey() + ".protocol") != null
                    && string(item.get("protocol"), entry.getKey() + ".protocol").equals("a2a");
                if (item.containsKey("protocol") && !a2a) throw new IllegalStateException("Unsupported metric protocol at " + entry.getKey() + ".protocol");
                Definitions.Metric metric = new Definitions.Metric(string(item.get("otel_name"), entry.getKey() + ".otel_name"), histogram, integer, a2a, text(item.get("unit"), entry.getKey() + ".unit"),
                    strings(metricAttributes.get("allowed"), entry.getKey() + ".attributes.allowed"), strings(metricAttributes.get("required"), entry.getKey() + ".attributes.required"));
                if (metrics.putIfAbsent(entry.getKey(), metric) != null) throw new IllegalStateException("Duplicate metric: " + entry.getKey());
            }
            for (Map.Entry<String, Object> entry : map(root.get("extensions"), resource + ".extensions").entrySet()) {
                Map<String, Object> extension = map(entry.getValue(), resource + ".extensions." + entry.getKey());
                if (spans.putIfAbsent(entry.getKey(), signal(entry.getKey(), extension.get("span_attributes"), entry.getKey() + ".span_attributes")) != null
                    || logs.putIfAbsent(entry.getKey(), signal(entry.getKey(), extension.get("log_attributes"), entry.getKey() + ".log_attributes")) != null)
                    throw new IllegalStateException("Duplicate extension: " + entry.getKey());
            }
            loadNamedSignals(root.get("traces"), resource + ".traces", traces);
            loadNamedSignals(root.get("logs"), resource + ".logs", namedLogs);
            return root.containsKey("a2a") ? a2aProtocol(root.get("a2a"), resource + ".a2a") : null;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid signal configuration: " + resource, e);
        }
    }
    private static void loadAttributes(Object value, String path, Map<String, Definitions.Attribute> target) {
        for (Map.Entry<String, Object> entry : map(value, path).entrySet()) {
            String key = entry.getKey();
            Map<String, Object> item = map(entry.getValue(), path + "." + key);
            String type = string(item.get("type"), path + "." + key + ".type");
            Definitions.AttributeType attributeType;
            try { attributeType = Definitions.AttributeType.valueOf(type.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Unsupported attribute type at " + path + "." + key + ".type"); }
            Set<String> values = item.containsKey("values") ? strings(item.get("values"), path + "." + key + ".values") : Set.of();
            if (!values.isEmpty() && !key.equals("extension.name")) throw new IllegalStateException("Only extension.name may declare values at " + path + "." + key);
            String signals = item.containsKey("signals") ? string(item.get("signals"), path + "." + key + ".signals") : "SML";
            if (!signals.matches("[SML]+")) throw new IllegalStateException("Invalid attribute signals at " + path + "." + key + ".signals");
            Definitions.Attribute attribute = new Definitions.Attribute(string(item.get("otel_name"), path + "." + key + ".otel_name"), signals, attributeType, values);
            Definitions.Attribute existing = target.putIfAbsent(key, attribute);
            if (existing != null && !existing.equals(attribute)) throw new IllegalStateException("Conflicting attribute definition: " + key);
        }
    }

    private static Definitions.Signal signal(String extension, Object value, String path) {
        Map<String, Object> attributes = map(value, path);
        return new Definitions.Signal(extension, strings(attributes.get("allowed"), path + ".allowed"), strings(attributes.get("required"), path + ".required"));
    }
    private static void loadNamedSignals(Object value, String path, Map<String, Definitions.NamedSignal> target) {
        if (value == null) return;
        for (Map.Entry<String, Object> entry : map(value, path).entrySet()) {
            Map<String, Object> item = map(entry.getValue(), path + "." + entry.getKey());
            String name = optionalString(item.get("name"), path + "." + entry.getKey() + ".name");
            String namePrefix = optionalString(item.get("name_prefix"), path + "." + entry.getKey() + ".name_prefix");
            if ((name == null) == (namePrefix == null)) throw new IllegalStateException("Provide exactly one name or name_prefix at " + path + "." + entry.getKey());
            Definitions.NamedSignal named = new Definitions.NamedSignal(name, namePrefix, signal(null, item.get("attributes"), path + "." + entry.getKey() + ".attributes"));
            if (target.putIfAbsent(entry.getKey(), named) != null) throw new IllegalStateException("Duplicate named signal: " + entry.getKey());
        }
    }
    private static A2aProtocol a2aProtocol(Object value, String path) {
        Map<String, Object> protocol = map(value, path);
        Map<String, Object> specialTraces = map(protocol.get("special_traces"), path + ".special_traces");
        String message = string(specialTraces.get("message"), path + ".special_traces.message");
        String stream = string(specialTraces.get("stream"), path + ".special_traces.stream");
        String streamEvent = string(specialTraces.get("stream_event"), path + ".special_traces.stream_event");
        String streamError = string(specialTraces.get("stream_error"), path + ".special_traces.stream_error");
        Set<String> traceNames = strings(protocol.get("trace_names"), path + ".trace_names");
        Set<String> clientOnlyTraces = strings(protocol.get("client_only_traces"), path + ".client_only_traces");
        if (!traceNames.containsAll(Set.of(message, stream, streamEvent, streamError)) || !traceNames.containsAll(clientOnlyTraces)) {
            throw new IllegalStateException("A2A special traces must be listed at " + path);
        }
        return new A2aProtocol(string(protocol.get("attribute_prefix"), path + ".attribute_prefix"), message, stream, streamEvent,
            streamError, traceNames, clientOnlyTraces, signal(null, protocol.get("span_attributes"), path + ".span_attributes"),
            signal(null, protocol.get("stream_event_attributes"), path + ".stream_event_attributes"));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalStateException("Expected mapping at " + path);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalStateException("Expected string key at " + path);
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static String string(Object value, String path) {
        String text = text(value, path);
        if (text.isBlank()) throw new IllegalStateException("Expected non-empty string at " + path);
        return text;
    }
    private static String text(Object value, String path) {
        if (!(value instanceof String text)) throw new IllegalStateException("Expected string at " + path);
        return text;
    }
    private static String optionalString(Object value, String path) { return value == null ? null : string(value, path); }
    private static Set<String> strings(Object value, String path) {
        if (!(value instanceof List<?> values)) throw new IllegalStateException("Expected list at " + path);
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object item : values) {
            String text = string(item, path);
            if (!result.add(text)) throw new IllegalStateException("Duplicate value at " + path);
        }
        return Set.copyOf(result);
    }

    record Catalog(Map<String, Definitions.Metric> metrics, Map<String, Definitions.Signal> spans,
        Map<String, Definitions.Signal> logs, Map<String, Definitions.NamedSignal> traces,
        Map<String, Definitions.NamedSignal> namedLogs, Map<String, Definitions.Attribute> attributes,
        Map<String, String> versions, A2aProtocol a2a) {}
    record A2aProtocol(String attributePrefix, String message, String stream, String streamEvent, String streamError,
        Set<String> traceNames, Set<String> clientOnlyTraces, Definitions.Signal span, Definitions.Signal streamEventSpan) {}
    private SignalConfigurationLoader() {}
}
