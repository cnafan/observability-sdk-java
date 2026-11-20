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
            loadMetrics(root.get("metrics"), resource + ".metrics", metrics);
            if (root.containsKey("extensions")) {
                for (Map.Entry<String, Object> entry : map(root.get("extensions"), resource + ".extensions").entrySet()) {
                    Map<String, Object> extension = map(entry.getValue(), resource + ".extensions." + entry.getKey());
                    if (spans.putIfAbsent(entry.getKey(), signal(entry.getKey(), extension.get("span_attributes"), entry.getKey() + ".span_attributes")) != null
                        || logs.putIfAbsent(entry.getKey(), signal(entry.getKey(), extension.get("log_attributes"), entry.getKey() + ".log_attributes")) != null)
                        throw new IllegalStateException("Duplicate extension: " + entry.getKey());
                }
            }
            A2aProtocol a2a = loadTraces(root.get("traces"), resource + ".traces", spans, traces);
            loadLogs(root.get("logs"), resource + ".logs", logs, namedLogs);
            return a2a;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid signal configuration: " + resource, e);
        }
    }

    private static void loadMetrics(Object value, String path, Map<String, Definitions.Metric> metrics) {
        Map<String, Object> groups = map(value, path);
        boolean grouped = groups.values().stream().allMatch(item -> item instanceof Map<?, ?> source && source.containsKey("signals"));
        if (grouped) {
            for (Map.Entry<String, Object> source : groups.entrySet()) {
                boolean a2a = source.getKey().equals("a2a");
                if (!a2a && !Set.of("a2at", "genai").contains(source.getKey())) {
                    throw new IllegalStateException("Unsupported metric source at " + path + "." + source.getKey());
                }
                Map<String, Object> sourceMap = map(source.getValue(), path + "." + source.getKey());
                loadMetricSignals(map(sourceMap.get("signals"), path + "." + source.getKey() + ".signals"),
                    path + "." + source.getKey() + ".signals", a2a, metrics);
            }
            return;
        }
        loadMetricSignals(groups, path, false, metrics);
    }

    private static void loadMetricSignals(Map<String, Object> source, String path, boolean sourceA2a,
        Map<String, Definitions.Metric> metrics) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Map<String, Object> item = map(entry.getValue(), path + "." + entry.getKey());
            Map<String, Object> metricAttributes = map(item.get("attributes"), path + "." + entry.getKey() + ".attributes");
            boolean histogram = string(item.get("type"), path + "." + entry.getKey() + ".type").equals("histogram");
            boolean integer = string(item.get("value_type"), path + "." + entry.getKey() + ".value_type").equals("integer");
            boolean a2a = sourceA2a || "a2a".equals(optionalString(item.get("protocol"), path + "." + entry.getKey() + ".protocol"));
            if (item.containsKey("protocol") && !a2a) throw new IllegalStateException("Unsupported metric protocol at " + path + "." + entry.getKey() + ".protocol");
            Definitions.Metric metric = new Definitions.Metric(string(item.get("otel_name"), path + "." + entry.getKey() + ".otel_name"), histogram,
                integer, a2a, text(item.get("unit"), path + "." + entry.getKey() + ".unit"),
                strings(metricAttributes.get("allowed"), path + "." + entry.getKey() + ".attributes.allowed"),
                strings(metricAttributes.get("required"), path + "." + entry.getKey() + ".attributes.required"));
            if (metrics.putIfAbsent(entry.getKey(), metric) != null) throw new IllegalStateException("Duplicate metric: " + entry.getKey());
        }
    }

    private static A2aProtocol loadTraces(Object value, String path, Map<String, Definitions.Signal> spans,
        Map<String, Definitions.NamedSignal> traces) {
        Map<String, Object> traceGroups = map(value, path);
        if (!traceGroups.containsKey("a2a")) {
            loadNamedSignals(traceGroups, path, traces);
            return null;
        }
        if (!traceGroups.containsKey("a2at")) throw new IllegalStateException("Missing A2A-T trace source at " + path);
        loadExtensions(map(map(traceGroups.get("a2at"), path + ".a2at").get("signals"), path + ".a2at.signals"),
            "extensions", spans, path + ".a2at.signals");
        return a2aProtocol(map(traceGroups.get("a2a"), path + ".a2a"), path + ".a2a");
    }

    private static void loadLogs(Object value, String path, Map<String, Definitions.Signal> logs,
        Map<String, Definitions.NamedSignal> namedLogs) {
        Map<String, Object> logGroups = map(value, path);
        if (!logGroups.containsKey("a2at")) {
            loadNamedSignals(logGroups, path, namedLogs);
            return;
        }
        loadExtensions(map(map(logGroups.get("a2at"), path + ".a2at").get("signals"), path + ".a2at.signals"),
            "extensions", logs, path + ".a2at.signals");
    }

    private static void loadExtensions(Map<String, Object> signals, String extensionsKey, Map<String, Definitions.Signal> target,
        String path) {
        for (Map.Entry<String, Object> signalEntry : signals.entrySet()) {
            Map<String, Object> signal = map(signalEntry.getValue(), path + "." + signalEntry.getKey());
            for (Map.Entry<String, Object> extensionEntry : map(signal.get(extensionsKey), path + "." + signalEntry.getKey() + "." + extensionsKey).entrySet()) {
                Map<String, Object> extension = map(extensionEntry.getValue(), path + "." + signalEntry.getKey() + "." + extensionsKey + "." + extensionEntry.getKey());
                Definitions.Signal definition = signal(extensionEntry.getKey(), extension.get("attributes"), path + "." + signalEntry.getKey() + "." + extensionsKey + "." + extensionEntry.getKey() + ".attributes");
                Definitions.Signal existing = target.putIfAbsent(extensionEntry.getKey(), definition);
                if (existing != null && !existing.equals(definition)) {
                    throw new IllegalStateException("Conflicting extension definition: " + extensionEntry.getKey());
                }
            }
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
    private static A2aProtocol a2aProtocol(Map<String, Object> protocol, String path) {
        Map<String, Object> signals = map(protocol.get("signals"), path + ".signals");
        String message = traceWithRole(signals, "message", path);
        String stream = traceWithRole(signals, "stream", path);
        String streamEvent = traceWithRole(signals, "stream_event", path);
        String streamError = traceWithRole(signals, "stream_error", path);
        Set<String> traceNames = Set.copyOf(signals.keySet());
        java.util.LinkedHashSet<String> clientOnlyTraces = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : signals.entrySet()) {
            Map<String, Object> item = map(entry.getValue(), path + ".signals." + entry.getKey());
            if (Boolean.TRUE.equals(item.get("client_only"))) clientOnlyTraces.add(entry.getKey());
        }
        return new A2aProtocol(string(protocol.get("attribute_prefix"), path + ".attribute_prefix"), message, stream, streamEvent,
            streamError, traceNames, Set.copyOf(clientOnlyTraces), signal(null,
                map(signals.get(message), path + ".signals." + message).get("attributes"), path + ".signals." + message + ".attributes"),
            signal(null, map(signals.get(streamEvent), path + ".signals." + streamEvent).get("attributes"), path + ".signals." + streamEvent + ".attributes"));
    }

    private static String traceWithRole(Map<String, Object> signals, String role, String path) {
        String match = null;
        for (Map.Entry<String, Object> entry : signals.entrySet()) {
            Map<String, Object> signal = map(entry.getValue(), path + ".signals." + entry.getKey());
            if (role.equals(optionalString(signal.get("role"), path + ".signals." + entry.getKey() + ".role"))) {
                if (match != null) throw new IllegalStateException("Duplicate A2A trace role at " + path + ".signals: " + role);
                match = entry.getKey();
            }
        }
        if (match == null) throw new IllegalStateException("Missing A2A trace role at " + path + ".signals: " + role);
        return match;
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
