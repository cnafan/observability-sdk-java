package net.openan.observability.example;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.*;

/** Fixed inputs for validating all four extensions against a Collector. */
public final class ExtensionsExample {
    public static void main(String[] args) {
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("a2at-extensions-demo").build())) {
            var o = sdk.observe();
            send(o, Map.of("extension.name", "task-t", "task.type", "diagnosis", "task.object", "demo-line"), Map.of());
            o.metric("task.duration", 42000, Map.of("task.type", "diagnosis"));
            o.metric("task.count", 1, Map.of("task.type", "diagnosis"));
            send(o, Map.of("extension.name", "negotiation-t", "negotiation.id", "session-1", "negotiation.round", 2,
                "negotiation.total_rounds", 3, "negotiation.result", "ACCEPT", "negotiation.performative", "PROPOSE"), Map.of("negotiation.max_rounds", 4));
            o.metric("negotiation.rounds", 2, Map.of("negotiation.id", "session-1", "negotiation.result", "accept"));
            o.metric("negotiation.count", 1, Map.of("negotiation.result", "accept"));
            send(o, Map.of("extension.name", "notification-t", "notification.topic", "demo-topic", "push.notification.url", "https://user:password@example.com/path?token=secret"), Map.of());
            o.metric("notification.count", 1, Map.of("notification.topic", "demo-topic"));
            send(o, Map.of("extension.name", "authorization-t", "authorization.stage", "EXECUTE", "authorization.policy.operation.type", "create"), Map.of());
            o.metric("authorization.preauthorize.count", 1, Map.of("authorization.policy.operation.type", "create"));
            o.metric("authorization.preauthorize.duration", 25, Map.of("authorization.policy.operation.type", "create"));
            o.metric("authorization.execute.count", 1, Map.of("authorization.policy.operation.type", "create"));
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "unknown-extension", "task.type", "invalid-marker")).end();
            o.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "invalid-marker", Map.of("extension.name", "notification-t", "negotiation.id", "invalid-marker"));
            o.trace("message/send", SignalKind.CLIENT, Map.of("extension.name", "negotiation-t", "negotiation.max_rounds", 999)).end();
            o.metric("task.count", 999, Map.of("task.type", "diagnosis", "notification.topic", "invalid-marker"));
            if (!sdk.flush(10, TimeUnit.SECONDS)) throw new IllegalStateException("Flush failed or timed out");
        }
    }
    private static void send(Observability o, Map<String, Object> attrs, Map<String, Object> extra) {
        var logAttrs = new java.util.HashMap<>(attrs); logAttrs.putAll(extra);
        try (var span = o.trace("message/send", SignalKind.CLIENT, attrs)) {
            o.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "extension sample", logAttrs, span);
            span.ok();
        }
    }
}
