package net.openan.observability.consumer;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Small standalone Maven consumer for checking the published SDK artifact. */
public final class ConsumerSmoke {
    private ConsumerSmoke() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        String serviceName = "sdk-consumer-" + runId;
        var attributes = Map.of(
            "extension.name", "task-t",
            "task.type", "consumer-smoke",
            "task.object", runId);

        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(serviceName).build())) {
            var observe = sdk.observe();
            try (var span = observe.trace("SendMessage", SignalKind.CLIENT, attributes)) {
                observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "Consumer smoke " + runId, attributes, span);
                observe.metric("task.duration", 42, Map.of("task.type", "consumer-smoke"));
                observe.metric("task.count", 1, Map.of("task.type", "consumer-smoke"));
                span.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTLP export failed or flush timed out");
            }
        }

        System.out.println("Consumer smoke completed for " + serviceName);
    }
}
