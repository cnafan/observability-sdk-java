# OpenAN Observability SDK for Java

English | [简体中文](README.zh-CN.md)

A Java observability SDK for OpenAN applications. Instrument A2A-T collaboration, workflow orchestration, and agent execution through one API. The SDK converts traces, metrics, and logs to OpenTelemetry data and exports them to an OpenTelemetry Collector over OTLP.

```text
Application code ──> Observability SDK ──> OpenTelemetry SDK ──OTLP──> Collector
                              │
                              ├── A2A / A2A-T
                              ├── Workflow
                              └── Agent / Substep
```

## Supported signals

| Category | Traces | Metrics | Logs | Definition |
|---|---|---|---|---|
| A2A / A2A-T | A2A messages, tasks, and streaming calls; four A2A-T extensions | 2 A2A metrics and 8 A2A-T metrics | `gen_ai.agent.a2at.message` | [`a2at.yaml`](src/main/resources/observability/a2at.yaml) |
| Workflow | `a2at.workflow`, `a2at.step.<name>` | `workflow.duration`, `workflow.count`, `step.duration` | Workflow and step events | [`workflow.yaml`](src/main/resources/observability/workflow.yaml) |
| Agent | `a2at.substep.<name>` | `substep.duration`, `gen_ai.client.token.usage` | Agent lifecycle and substep events | [`agent.yaml`](src/main/resources/observability/agent.yaml) |

Each YAML file defines its version, attribute types, signal names, metric dimensions, and required attributes. Update the applicable YAML file and increment its `version` when the signal definition changes. The SDK validates signal names and attribute allowlists before export.

`extension.name` is the A2A-T branching field. Its value—`task-t`, `negotiation-t`, `notification-t`, or `authorization-t`—selects the span and log attribute allowlist for that extension. Other attributes are validated by name, type, signal scope, and requiredness; business values are not constrained.

## Quick start

JDK 17+, Maven, and a reachable OpenTelemetry Collector are required.

1. Start a local Collector from the repository root:

```powershell
otelcol --config=examples/collector-config.yaml
```

2. Build and install the SDK:

```powershell
mvn -B -ntp clean install
```

3. Add the dependency to the application's `pom.xml`:

```xml
<dependency>
    <groupId>net.openan</groupId>
    <artifactId>observability-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`examples/collector-config.yaml` receives traces, metrics, and logs and prints them in the Collector output, which is useful for inspecting local data. For production, replace the exporter in each pipeline with the required storage or forwarding target.

## Instrument an application

Create `ObservabilitySdk` at application startup and obtain the API with `observe()`. Explicit `SdkConfig` values take precedence over environment variables.

```java
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
        .serviceName("diagnosis-agent")
        .endpoint("http://localhost:4317")
        .protocol("grpc")
        .metricInterval(Duration.ofSeconds(10))
        .build())) {
    var observe = sdk.observe();

    // A2A-T: Task-T message, log, and metrics.
    var task = Map.of("extension.name", "task-t", "task.type", "diagnosis");
    try (var span = observe.trace("SendMessage", SignalKind.CLIENT, task)) {
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO,
            "diagnosis completed", task, span);
        observe.metric("task.duration", 42_000, Map.of("task.type", "diagnosis"));
        observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
        span.ok();
    }

    // Workflow: workflow trace and metric.
    var workflow = Map.of(
        "workflow.id", "wf-001",
        "workflow.type", "fault-diagnosis",
        "workflow.status", "running");
    try (var span = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow)) {
        observe.metric("workflow.duration", 42, Map.of(
            "workflow.type", "fault-diagnosis",
            "workflow.status", "completed"));
        span.ok();
    }

    // Agent: substep trace, token usage, and lifecycle log.
    var substep = Map.of(
        "gen_ai.agent.name", "diagnosis-agent",
        "substep.name", "retrieve-alarms",
        "substep.status", "active");
    try (var span = observe.trace("a2at.substep.retrieve-alarms", SignalKind.INTERNAL, substep)) {
        observe.metric("gen_ai.client.token.usage", 120, Map.of(
            "gen_ai.agent.name", "diagnosis-agent",
            "gen_ai.request.model", "model-1",
            "gen_ai.token.type", "input"));
        observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "agent running", Map.of(
            "gen_ai.agent.name", "diagnosis-agent",
            "agent.state", "running"), span);
        span.ok();
    }

    if (!sdk.flush(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("OTLP flush failed or timed out");
    }
}
```

Call `span.error()` for failures. `span.makeCurrent()` establishes context on the current thread; close the returned `Scope` when done. Pass the appropriate `ObservationSpan` when crossing threads or associating a log with a span. Close `ObservabilitySdk` when the application stops; short-lived processes should call `flush()` after ending spans.

### Cross-process trace context and errors

Use `TraceContextPropagation` to inject the current W3C `traceparent` header into an outbound carrier and to make an inbound context current before creating the server span. `error(Throwable)` records the exception and marks the span as failed.

```java
try (var client = observe.trace("SendMessage", SignalKind.CLIENT, attributes);
     var scope = client.makeCurrent()) {
    TraceContextPropagation.inject(requestHeaders, Map::put);
}

try (var scope = TraceContextPropagation.extractToCurrent(requestHeaders, headerGetter);
     var server = observe.trace("SendMessage", SignalKind.SERVER, attributes)) {
    try {
        // Handle the request.
        server.ok();
    } catch (RuntimeException error) {
        server.error(error);
        throw error;
    }
}
```

## Signal rules

### A2A / A2A-T

- A2A traces support `SendMessage`, `SendStreamingMessage`, `SendStreamingMessage-event`, `SendStreamingMessage-end`, and the task operations (`GetTask`, `CancelTask`, `ListTasks`, `SubscribeToTask`, and the push-notification config operations). A stream event can hold a Span Link to its upstream span.
- The A2A metrics are `gen_ai.client.operation.duration` and `gen_ai.agent.a2a.streaming.duration`; both require `operation.name`.
- A2A-T includes Task-T, Negotiation-T, Notification-T, and Authorization-T. Metrics include `task.duration`, `task.count`, `negotiation.rounds`, `negotiation.count`, `notification.count`, and three Authorization metrics.
- `SendMessage`, `SendStreamingMessage`, and `gen_ai.agent.a2at.message` use `extension.name` to select extension attributes. Task-T requires a non-blank `task.type`.

### Workflow

- `a2at.workflow` requires `workflow.id`, `workflow.type`, and `workflow.status`; `a2at.step.<name>` requires the step name, index, and status.
- `workflow.duration` and `workflow.count` require `workflow.type` and `workflow.status`; `step.duration` requires `workflow.step` and `gen_ai.agent.name`.
- The log names are `gen_ai.agent.a2at.workflow` and `gen_ai.agent.a2at.step`.

### Agent

- `a2at.substep.<name>` requires `gen_ai.agent.name`, `substep.name`, and `substep.status`.
- `substep.duration` requires the agent and substep names; `gen_ai.client.token.usage` requires the agent, model, and token type.
- The log names are `gen_ai.agent.a2at.agent` and `gen_ai.agent.a2at.substep`; provide `agent.skills` as a non-empty string list.

Short attribute names and `gen_ai.agent.a2at.`-prefixed attribute names are both accepted; the SDK exports the OpenTelemetry name declared in YAML. A signal is rejected if the same attribute is supplied in both forms. Rejected traces, metrics, and logs are not sent to the Collector, and diagnostics do not include input values.

## OTLP configuration

| Builder method | Environment variable | Default |
|---|---|---|
| `endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC: `http://localhost:4317`; HTTP/Protobuf: `http://localhost:4318` |
| `protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc`; `http/protobuf` is also supported |
| `serviceName` | `OTEL_SERVICE_NAME` | `unknown_service` |
| `metricInterval` | `OTEL_METRIC_EXPORT_INTERVAL` | `60000` milliseconds |
| `headers(Map)` / `header(name, value)` | `OTEL_EXPORTER_OTLP_HEADERS` | No request headers |
| `trustedCertificate(Path)` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | System trust store |
| `clientCertificate(Path)` + `clientPrivateKey(Path)` | — | No client certificate |
| `exportTimeout(Duration)` | `OTEL_EXPORTER_OTLP_TIMEOUT` | `10000` milliseconds |
| `enabled(boolean)` | — | `true` |
| `spanBatch(BatchConfig)` | — | queue `2048`, batch `512`, delay `5s`, timeout `30s` |
| `logBatch(BatchConfig)` | — | queue `2048`, batch `512`, delay `1s`, timeout `30s` |

For HTTP/Protobuf, provide the Collector base address. The SDK appends `/v1/traces`, `/v1/metrics`, and `/v1/logs`. `https` enables TLS; an endpoint must use `https` when a trusted CA or a client certificate is configured. Request headers are shared by all three exporters. Set environment headers as `key=value,key2=value2` and percent-encode values; pass raw values through the builder. OTLP `http/json` is not currently supported.

`setEnabled(false)` disables creation of new telemetry at runtime. Existing spans can still be ended and exported. Use `setEnabled(true)` to resume recording. The SDK skips its own signal validation and OpenTelemetry API calls while disabled; objects created by the calling application before the SDK method is invoked are outside that optimization.

For mutual TLS, provide a client certificate and its paired PEM private key together:

```java
var config = SdkConfig.builder()
    .endpoint("https://collector.example:4318")
    .protocol("http/protobuf")
    .trustedCertificate(Path.of("collector-ca.pem"))
    .clientCertificate(Path.of("client-cert.pem"))
    .clientPrivateKey(Path.of("client-key.pem"))
    .build();
```

Tune trace or log buffering only when a deployment has measured queue pressure or export latency:

```java
var traceBatch = BatchConfig.builder()
    .maxQueueSize(4096)
    .maxExportBatchSize(1024)
    .scheduleDelay(Duration.ofSeconds(2))
    .exportTimeout(Duration.ofSeconds(15))
    .build();
```

The optional Log4j2 bridge is in `observability-sdk-log4j2/`. It forwards only events carrying its configured Marker, maps Log4j2 levels, and uses MDC `traceId` and `spanId` values when no current OpenTelemetry context exists.

## Examples and verification

| Location | Purpose |
|---|---|
| [`examples/`](examples/) | A2A, the four A2A-T extensions, and a cross-process Task-T example |
| [`WorkflowAgentExample`](examples/src/main/java/net/openan/observability/example/WorkflowAgentExample.java) | Workflow, step, agent substep, token usage, and lifecycle logs |
| [`examples/collector-config.yaml`](examples/collector-config.yaml) | Local gRPC / HTTP OTLP receiver configuration |
| [`examples/collector-auth-tls-config.yaml`](examples/collector-auth-tls-config.yaml) | TLS and Bearer Token receiver configuration |
| [`deploy/local-storage/`](deploy/local-storage/) | Local Collector, Prometheus/Thanos, OpenSearch/Data Prepper, and Jaeger storage stack |
| [`consumer-smoke/`](consumer-smoke/) | Independent Maven consumer application |
| [`src/test/java/`](src/test/java/) | YAML definitions, validation, all three signal types, OTLP configuration, and export-failure tests |

Run the full test suite:

```powershell
mvn -B -ntp -Pexamples clean verify
```

Run the four A2A-T extension examples:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
$runId = 'a2at-extensions-local'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.ExtensionsExample' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

Run the Workflow and Agent example:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.WorkflowAgentExample' '-Dexec.args=local' '-Dexec.cleanupDaemonThreads=false'
```

The default JAR does not contain classes under `examples/`; the `examples` profile only compiles and runs those local examples.
