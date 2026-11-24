package net.openan.observability.consumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservationSpan;
import net.openan.observability.Observability;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Independent consumer that emits every configured signal family as correlated flows. */
public final class ConsumerAllSignals {
    private static final String MESSAGE_LOG = "gen_ai.agent.a2at.message";

    private ConsumerAllSignals() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        String serviceName = "sdk-consumer-all-" + runId;
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(serviceName).build())) {
            emitCorrelatedScenario(sdk.observe(), runId);
            if (!sdk.flush(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OTLP export failed or flush timed out");
            }
        }
        System.out.println("All configured signals emitted for " + serviceName);
    }

    private static void emitCorrelatedScenario(Observability observe, String runId) {
        Map<String, Object> task = merge(a2aAttributes("SendMessage", runId), Map.of(
            "extension.name", "task-t", "task.type", "consumer-all", "task.object", runId));
        try (var client = observe.trace("SendMessage", SignalKind.CLIENT, task)) {
            try (var clientScope = client.makeCurrent()) {
                observe.log(MESSAGE_LOG, LogSeverity.INFO, "Task request accepted", Map.of(
                    "extension.name", "task-t", "task.type", "consumer-all", "task.object", runId), client);
                observe.metric("task.duration", 42.0, Map.of("task.type", "consumer-all", "task.object", runId));
                observe.metric("task.count", 1, Map.of("task.type", "consumer-all", "task.object", runId));
                observe.metric("gen_ai.client.operation.duration", 0.12, Map.of("operation.name", "SendMessage"));
                try (var server = observe.trace("SendMessage", SignalKind.SERVER, task, client)) {
                    try (var serverScope = server.makeCurrent()) {
                        emitA2aOperationPairs(observe, runId, server);
                        emitStreamingFlow(observe, runId, server);
                        emitWorkflowAndAgentFlow(observe, runId, server);
                        emitExtensionFlows(observe, runId, server);
                        server.ok();
                    }
                }
                client.ok();
            }
        }
    }

    private static void emitA2aOperationPairs(Observability observe, String runId, ObservationSpan parent) {
        for (String operation : List.of(
            "GetTask", "CancelTask", "ListTasks", "SubscribeToTask", "CreateTaskPushNotificationConfig",
            "GetTaskPushNotificationConfig", "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig")) {
            try (var client = observe.trace(operation, SignalKind.CLIENT, a2aAttributes(operation, runId), parent)) {
                try (var server = observe.trace(operation, SignalKind.SERVER, a2aAttributes(operation, runId), client)) {
                    server.ok();
                }
                client.ok();
            }
        }
        emitTrace(observe, "GetExtendedAgentCard", SignalKind.CLIENT, a2aAttributes("GetExtendedAgentCard", runId), parent);
        emitTrace(observe, "SendPushNotification", SignalKind.CLIENT, a2aAttributes("SendPushNotification", runId), parent);
    }

    private static void emitStreamingFlow(Observability observe, String runId, ObservationSpan parent) {
        Map<String, Object> streamAttributes = a2aAttributes("SendStreamingMessage", runId);
        try (var streamClient = observe.trace("SendStreamingMessage", SignalKind.CLIENT, streamAttributes, parent)) {
            try (var streamScope = streamClient.makeCurrent()) {
                observe.metric("gen_ai.agent.a2a.streaming.duration", 0.45, Map.of(
                    "operation.name", "SendStreamingMessage", "error.type", "none"));
                try (var streamServer = observe.trace("SendStreamingMessage", SignalKind.SERVER, streamAttributes, streamClient)) {
                    try (var negotiation = observe.trace("SendStreamingMessage-negotiation", SignalKind.SERVER, Map.of(
                        "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
                        "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.max_rounds", 3,
                        "negotiation.result", "accepted", "negotiation.performative", "propose"), streamServer)) {
                        negotiation.ok();
                    }
                    streamServer.ok();
                }
                emitTrace(observe, "SendStreamingMessage-event", SignalKind.CLIENT, Map.ofEntries(
                    Map.entry("operation.name", "SendStreamingMessage"), Map.entry("task_id", "task-" + runId),
                    Map.entry("context_id", "context-" + runId), Map.entry("message_id", "message-" + runId),
                    Map.entry("streaming-event", "status-update"), Map.entry("task.status.state", "working"),
                    Map.entry("artifact.name", "report"), Map.entry("artifact.append", true),
                    Map.entry("artifact.last_chunk", false), Map.entry("usage.input_tokens", 13),
                    Map.entry("usage.output_tokens", 21)), streamClient);
                emitTrace(observe, "SendStreamingMessage-end", SignalKind.CLIENT, Map.of(
                    "operation.name", "SendStreamingMessage", "task_id", "task-" + runId,
                    "error.type", "none"), streamClient);
                streamClient.ok();
            }
        }
    }

    private static void emitWorkflowAndAgentFlow(Observability observe, String runId, ObservationSpan parent) {
        Map<String, Object> workflow = Map.of(
            "workflow.id", "workflow-" + runId, "workflow.type", "consumer-all", "workflow.status", "completed",
            "workflow.session_id", "session-" + runId);
        Map<String, Object> step = Map.of(
            "workflow.step", "collect", "workflow.step.index", 1, "workflow.step.status", "completed",
            "workflow.step.owner_agent", "consumer-agent");
        try (var workflowSpan = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow, parent)) {
            try (var workflowScope = workflowSpan.makeCurrent()) {
                observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "Workflow completed", Map.ofEntries(
                    Map.entry("workflow.id", "workflow-" + runId), Map.entry("workflow.type", "consumer-all"),
                    Map.entry("workflow.status", "completed"), Map.entry("workflow.session_id", "session-" + runId),
                    Map.entry("business.domain", "observability"), Map.entry("business.customer", "consumer")), workflowSpan);
                observe.metric("workflow.duration", 90.0, Map.of("workflow.type", "consumer-all", "workflow.status", "completed"));
                observe.metric("workflow.count", 1, Map.of("workflow.type", "consumer-all", "workflow.status", "completed"));
                try (var stepSpan = observe.trace("a2at.step.collect", SignalKind.INTERNAL, step, workflowSpan)) {
                    try (var stepScope = stepSpan.makeCurrent()) {
                        observe.log("gen_ai.agent.a2at.step", LogSeverity.INFO, "Workflow step completed", step, stepSpan);
                        observe.metric("step.duration", 40.0, Map.of(
                            "workflow.step", "collect", "gen_ai.agent.name", "consumer-agent"));
                        emitAgentSignals(observe, stepSpan);
                        stepSpan.ok();
                    }
                }
                workflowSpan.ok();
            }
        }
    }

    private static void emitAgentSignals(Observability observe, ObservationSpan parent) {
        Map<String, Object> substep = Map.of(
            "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "completed");
        try (var span = observe.trace("a2at.substep.analyze", SignalKind.INTERNAL, substep, parent)) {
            try (var scope = span.makeCurrent()) {
                observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "Agent completed", Map.of(
                    "gen_ai.agent.name", "consumer-agent", "agent.role", "observer",
                    "agent.skills", List.of("analyze", "report"), "agent.state", "completed"), span);
                observe.log("gen_ai.agent.a2at.substep", LogSeverity.INFO, "Agent substep completed", Map.of(
                    "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze", "substep.status", "completed",
                    "workflow.step", "collect", "workflow.step.index", 1), span);
                observe.metric("substep.duration", 30.0, Map.of(
                    "gen_ai.agent.name", "consumer-agent", "substep.name", "analyze"));
                observe.metric("gen_ai.client.token.usage", 34, Map.of(
                    "gen_ai.agent.name", "consumer-agent", "gen_ai.request.model", "model-1", "gen_ai.token.type", "input"));
                span.ok();
            }
        }
    }

    private static void emitExtensionFlows(Observability observe, String runId, ObservationSpan parent) {
        emitExtensionFlow(observe, runId, parent, "negotiation-t", Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.result", "accepted",
            "negotiation.performative", "propose"), Map.of(
            "extension.name", "negotiation-t", "negotiation.id", "negotiation-" + runId,
            "negotiation.round", 1, "negotiation.total_rounds", 3, "negotiation.max_rounds", 3,
            "negotiation.result", "accepted", "negotiation.performative", "propose"));
        observe.metric("negotiation.rounds", 2, Map.of("negotiation.id", "negotiation-" + runId, "negotiation.result", "accepted"));
        observe.metric("negotiation.count", 1, Map.of("negotiation.result", "accepted"));

        emitExtensionFlow(observe, runId, parent, "notification-t", Map.of(
            "extension.name", "notification-t", "notification.topic", "consumer-all",
            "push.notification.url", "https://example.com/notifications"), Map.of(
            "extension.name", "notification-t", "notification.topic", "consumer-all",
            "push.notification.url", "https://example.com/notifications"));
        observe.metric("notification.count", 1, Map.of("notification.topic", "consumer-all"));

        emitExtensionFlow(observe, runId, parent, "authorization-t", Map.of(
            "extension.name", "authorization-t", "authorization.stage", "execute",
            "authorization.policy.operation.type", "create"), Map.of(
            "extension.name", "authorization-t", "authorization.stage", "execute",
            "authorization.policy.operation.type", "create"));
        observe.metric("authorization.preauthorize.count", 1, Map.of("authorization.policy.operation.type", "create"));
        observe.metric("authorization.preauthorize.duration", 5.0, Map.of("authorization.policy.operation.type", "create"));
        observe.metric("authorization.execute.count", 1, Map.of("authorization.policy.operation.type", "create"));
    }

    private static void emitExtensionFlow(Observability observe, String runId, ObservationSpan parent, String extension,
            Map<String, ?> spanAttributes, Map<String, ?> logAttributes) {
        Map<String, Object> attributes = merge(a2aAttributes("SendMessage", runId), spanAttributes);
        try (var span = observe.trace("SendMessage", SignalKind.CLIENT, attributes, parent)) {
            try (var scope = span.makeCurrent()) {
                observe.log(MESSAGE_LOG, LogSeverity.INFO, "Consumer all " + extension, logAttributes, span);
                emitTrace(observe, "SendStreamingMessage", SignalKind.CLIENT,
                    merge(a2aAttributes("SendStreamingMessage", runId), spanAttributes), span);
                span.ok();
            }
        }
    }

    private static Map<String, Object> a2aAttributes(String operation, String runId) {
        return Map.ofEntries(
            Map.entry("operation.name", operation), Map.entry("task_id", "task-" + runId),
            Map.entry("context_id", "context-" + runId), Map.entry("message_id", "message-" + runId),
            Map.entry("role", "agent"), Map.entry("extensions", "a2at"), Map.entry("parts.number", 1),
            Map.entry("config_id", "config-1"), Map.entry("request", "https://example.com/request"),
            Map.entry("response", "completed"), Map.entry("gen_ai.provider.name", "openan"),
            Map.entry("gen_ai.operation.name", operation), Map.entry("gen_ai.agent.name", "consumer-agent"),
            Map.entry("gen_ai.agent.id", "agent-1"), Map.entry("gen_ai.agent.version", "1.0.0"),
            Map.entry("push_notification.event_kind", "status"), Map.entry("transport", "http"),
            Map.entry("protocol.version", "0.3"), Map.entry("gen_ai.response.model", "model-1"),
            Map.entry("usage.input_tokens", 13), Map.entry("usage.output_tokens", 21));
    }

    private static Map<String, Object> merge(Map<String, ?> first, Map<String, ?> second) {
        Map<String, Object> merged = new HashMap<>();
        first.forEach(merged::put);
        second.forEach(merged::put);
        return Map.copyOf(merged);
    }

    private static void emitTrace(Observability observe, String name, SignalKind kind, Map<String, ?> attributes,
            ObservationSpan parent) {
        try (var span = observe.trace(name, kind, attributes, parent)) {
            span.ok();
        }
    }
}
