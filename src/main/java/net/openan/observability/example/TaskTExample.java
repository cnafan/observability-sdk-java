package net.openan.observability.example;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.*;

public final class TaskTExample {
    public static void main(String[] args) {
        try (ObservabilitySdk sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("a2at-demo").build())) {
            Observability observe = sdk.observe();
            Map<String, String> attrs = Map.of("extension.name", "task-t", "task.type", "SPN专线投诉诊断", "task.object", "demo-line");
            try (ObservationSpan span = observe.trace("message/send", SignalKind.CLIENT, attrs)) {
                observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "Task-T 诊断结果已返回", attrs, span);
                observe.metric("task.duration", 42000, Map.of("task.type", "SPN专线投诉诊断"));
                observe.metric("task.count", 1, Map.of("task.type", "SPN专线投诉诊断"));
                observe.trace("invalid-span", SignalKind.CLIENT, attrs).end();
                observe.log("invalid-log", LogSeverity.INFO, "rejected", attrs);
                observe.metric("invalid-metric", 1, Map.of("task.type", "SPN专线投诉诊断"));
                observe.metric("task.count", 999, Map.of("task.type", "SPN专线投诉诊断", "extension.name", "task-t"));
                span.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) System.err.println("Observability flush failed or timed out");
        }
    }
}
