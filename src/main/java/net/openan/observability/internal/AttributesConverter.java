package net.openan.observability.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Internal validation and conversion; not a supported public API. */
public final class AttributesConverter {
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(bearer\\s+)[^\\s,;\\\"']+|((?:token|password|passwd|secret|api[_-]?key|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?(?:bearer\\s+)?)[^\\s,;\\\"']+" );
    private static final Pattern QUOTED_CREDENTIAL = Pattern.compile("(?i)((?:token|password|passwd|secret|api[_-]?key|authorization)[\"']?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
    public static String clean(String value) {
        String masked = QUOTED_CREDENTIAL.matcher(value).replaceAll("$1[REDACTED]");
        masked = CREDENTIAL.matcher(masked).replaceAll("$1$2[REDACTED]");
        masked = URL.matcher(masked).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(cleanUrl(match.group())));
        return masked.length() <= 2048 ? masked : masked.substring(0, 2048);
    }
    private static String cleanUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return "[REDACTED_URL]";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toASCIIString();
        } catch (Exception e) { return "[REDACTED_URL]"; }
    }
    public static Attributes convert(Map<String, ?> input, char signal, Set<String> dimensions) {
        if (input == null) throw new IllegalArgumentException();
        AttributesBuilder output = Attributes.builder();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, ?> item : input.entrySet()) {
            if (item.getKey() == null) throw new IllegalArgumentException();
            String rawKey = item.getKey();
            String key = rawKey.startsWith(Definitions.A2A_PREFIX)
                ? rawKey.substring(Definitions.A2A_PREFIX.length()) : Definitions.shortName(rawKey);
            Definitions.Attribute definition = Definitions.attribute(key);
            if (definition == null || (!rawKey.equals(key) && !rawKey.equals(definition.name()))
                || !seen.add(key) || definition.signals().indexOf(signal) < 0 || (dimensions != null && !dimensions.contains(key))) throw new IllegalArgumentException();
            Object value = item.getValue();
            if (definition.type() == Definitions.AttributeType.INTEGER) {
                if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
                long number = ((Number) value).longValue();
                if (key.equals("parts.number") && number < 0) throw new IllegalArgumentException();
                output.put(definition.name(), number);
            } else if (definition.type() == Definitions.AttributeType.STRING_ARRAY) {
                if (!(value instanceof java.util.List<?> values) || values.isEmpty()) throw new IllegalArgumentException();
                java.util.List<String> cleaned = new java.util.ArrayList<>();
                for (Object valueItem : values) {
                    if (!(valueItem instanceof String text) || text.isBlank()) throw new IllegalArgumentException();
                    cleaned.add(clean(text));
                }
                output.put(io.opentelemetry.api.common.AttributeKey.stringArrayKey(definition.name()), cleaned);
            } else {
                if (!(value instanceof String)) throw new IllegalArgumentException();
                String s = (String) value;
                if (!definition.values().isEmpty()) {
                    s = s.toLowerCase(Locale.ROOT);
                    if (!definition.values().contains(s)) throw new IllegalArgumentException();
                }
                if (key.equals("gen_ai.system") && !s.equals("a2a")) throw new IllegalArgumentException();
                if (key.equals("operation.name") && s.isBlank()) throw new IllegalArgumentException();
                output.put(definition.name(), clean(s));
            }
        }
        return output.build();
    }
    public static Definitions.Signal selectSignal(Map<String, ?> input, Map<String, Definitions.Signal> definitions) {
        Definitions.Signal definition = selectOptionalSignal(input, definitions);
        if (definition == null) throw new IllegalArgumentException();
        return definition;
    }
    public static Definitions.Signal selectOptionalSignal(Map<String, ?> input, Map<String, Definitions.Signal> definitions) {
        if (input == null) throw new IllegalArgumentException();
        Object extension = null;
        boolean found = false;
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            if (entry.getKey() != null && Definitions.shortName(entry.getKey()).equals("extension.name")) {
                if (found) throw new IllegalArgumentException();
                found = true;
                extension = entry.getValue();
            }
        }
        if (!found) return null;
        if (!(extension instanceof String)) throw new IllegalArgumentException();
        Definitions.Signal definition = definitions.get(((String) extension).toLowerCase(Locale.ROOT));
        if (definition == null) throw new IllegalArgumentException();
        return definition;
    }
    public static Attributes convertSignal(Map<String, ?> input, char signal, Definitions.Signal definition, boolean requireFields) {
        Attributes attributes = convert(input, signal, definition.attributes());
        if (requireFields) {
            for (String key : definition.required()) {
                if (attributes.asMap().keySet().stream().noneMatch(attribute -> attribute.getKey().equals(Definitions.attributeName(key))))
                    throw new IllegalArgumentException();
            }
        }
        String extension = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(Definitions.PREFIX + "extension.name"));
        String taskType = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(Definitions.PREFIX + "task.type"));
        if (extension != null && !extension.equals(definition.extension()) || taskType != null && taskType.isBlank())
            throw new IllegalArgumentException();
        return attributes;
    }
    private AttributesConverter() {}
}
