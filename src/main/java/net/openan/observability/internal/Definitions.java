package net.openan.observability.internal;

import java.util.Map;
import java.util.Set;

/** Internal signal definitions; not a supported public API. */
public final class Definitions {
    public static final String PREFIX = "gen_ai.agent.a2at.";
    public static final String MESSAGE_SPAN = "message/send";
    public static final String MESSAGE_LOG = PREFIX + "message";
    public record Attribute(String signals, boolean integer, Set<String> values) {}
    public record Metric(boolean histogram, boolean integer, String unit, Set<String> dimensions) {}
    public record Signal(String extension, Set<String> attributes, Set<String> required) {}
    public static final Map<String, Signal> SPANS = Map.of(
        "task-t", signal("task-t", false),
        "negotiation-t", signal("negotiation-t", false),
        "notification-t", signal("notification-t", false),
        "authorization-t", signal("authorization-t", false));
    public static final Map<String, Signal> LOGS = Map.of(
        "task-t", signal("task-t", true),
        "negotiation-t", signal("negotiation-t", true),
        "notification-t", signal("notification-t", true),
        "authorization-t", signal("authorization-t", true));
    private static Signal signal(String extension, boolean log) {
        Set<String> allowed = switch (extension) {
            case "task-t" -> Set.of("extension.name", "task.type", "task.object");
            case "negotiation-t" -> log
                ? Set.of("extension.name", "negotiation.id", "negotiation.round", "negotiation.total_rounds", "negotiation.result", "negotiation.performative", "negotiation.max_rounds")
                : Set.of("extension.name", "negotiation.id", "negotiation.round", "negotiation.total_rounds", "negotiation.result", "negotiation.performative");
            case "notification-t" -> Set.of("extension.name", "notification.topic", "push.notification.url");
            case "authorization-t" -> Set.of("extension.name", "authorization.stage", "authorization.policy.operation.type");
            default -> throw new IllegalArgumentException();
        };
        return new Signal(extension, allowed, extension.equals("task-t") ? Set.of("extension.name", "task.type") : Set.of("extension.name"));
    }
    static final Map<String, Attribute> ATTRIBUTES = Map.ofEntries(
        entry("extension.name", "SML", false, "task-t", "negotiation-t", "notification-t", "authorization-t"),
        entry("task.type", "SML", false), entry("task.object", "SL", false),
        entry("negotiation.id", "SML", false), entry("negotiation.round", "SML", true),
        entry("negotiation.total_rounds", "SL", true), entry("negotiation.max_rounds", "L", true),
        entry("negotiation.result", "SML", false, "accept", "reject", "timeout"),
        entry("negotiation.performative", "SML", false, "propose", "reject", "accept"),
        entry("notification.topic", "SML", false), entry("push.notification.url", "SL", false),
        entry("authorization.stage", "SL", false, "preauthorize", "execute"),
        entry("authorization.policy.operation.type", "SML", false));
    public static final Map<String, Metric> METRICS = Map.of(
        "task.duration", new Metric(true, false, "ms", Set.of("task.type")),
        "task.count", new Metric(false, true, "", Set.of("task.type")),
        "negotiation.rounds", new Metric(true, true, "{round}", Set.of("negotiation.id", "negotiation.result")),
        "negotiation.count", new Metric(false, true, "", Set.of("negotiation.result")),
        "notification.count", new Metric(false, true, "", Set.of("notification.topic")),
        "authorization.preauthorize.count", new Metric(false, true, "", Set.of("authorization.policy.operation.type")),
        "authorization.preauthorize.duration", new Metric(true, false, "ms", Set.of("authorization.policy.operation.type")),
        "authorization.execute.count", new Metric(false, true, "", Set.of("authorization.policy.operation.type")));
    private static Map.Entry<String, Attribute> entry(String name, String signals, boolean integer, String... values) {
        return Map.entry(name, new Attribute(signals, integer, Set.of(values)));
    }
    public static String shortName(String name) { return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name; }
    private Definitions() {}
}
