# OpenAN Observability SDK for Java: API Reference

[简体中文](../zh/API参考.md) · [Development Guide](development-guide.md)

This reference describes the public API in the current `0.1.0-SNAPSHOT` build. It requires JDK 17 or later.

## Package and entry point

All public classes are in `net.openan.observability`. Create one `ObservabilitySdk` when the application starts and close it when the application stops.

```java
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;

try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
        .serviceName("diagnosis-agent")
        .endpoint("http://localhost:4317")
        .protocol("grpc")
        .build())) {
    var observe = sdk.observe();
    // Record traces, metrics, and logs with observe.
}
```

## `ObservabilitySdk`

### `start(SdkConfig config)`

Creates the SDK, OTLP trace/metric/log exporters, and batch processors. `config` must not be `null`.

### `observe()`

Returns the `Observability` API. Repeated calls on one SDK instance return the same object.

### `flush(long timeout, TimeUnit unit)`

Waits for pending traces, metrics, and logs. It returns `true` only when all providers finish in time and no exporter failure has been observed since the preceding `flush`.

```java
if (!sdk.flush(10, TimeUnit.SECONDS)) {
    throw new IllegalStateException("OTLP export failed or timed out");
}
```

Use it before a short-lived process exits.

### `close()`

Closes the OpenTelemetry providers. Try-with-resources is recommended; do not use the SDK afterwards.

### Runtime controls

`setEnabled(boolean)` controls creation of new telemetry, and `isEnabled()` returns its current value. Existing spans can still be ended after disabling.

`CompletableResultCode updateExporters(SdkConfig config)` replaces the OTLP endpoint, protocol, headers, TLS material, and export timeout for all three signals. It builds all replacements and flushes the current providers first. A changed `serviceName`, metric interval, or batch configuration is rejected; start a new SDK instance for those changes.

## `SdkConfig` and `SdkConfig.Builder`

Create a builder through `SdkConfig.builder()`. Explicit builder values take precedence over their environment variables.

| Builder method | Environment variable | Default | Notes |
|---|---|---|---|
| `endpoint(String)` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC: `http://localhost:4317`; HTTP/Protobuf: `http://localhost:4318` | Collector base address |
| `protocol(String)` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | `grpc` or `http/protobuf` |
| `serviceName(String)` | `OTEL_SERVICE_NAME` | `unknown_service` | OpenTelemetry `service.name` resource attribute |
| `metricInterval(Duration)` | `OTEL_METRIC_EXPORT_INTERVAL` | 60 seconds | Periodic metric export interval |
| `headers(Map<String,String>)` | `OTEL_EXPORTER_OTLP_HEADERS` | none | Replaces all request headers |
| `header(String,String)` | `OTEL_EXPORTER_OTLP_HEADERS` | none | Adds a request header; builder headers then take precedence |
| `trustedCertificate(Path)` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | system trust store | PEM or DER X.509 CA; requires `https` |
| `clientCertificate(Path)` + `clientPrivateKey(Path)` | — | none | mutual-TLS client certificate and its PEM private key; requires `https` |
| `exportTimeout(Duration)` | `OTEL_EXPORTER_OTLP_TIMEOUT` | 10 seconds | Timeout for one OTLP export batch; zero means no exporter deadline |
| `enabled(boolean)` | — | `true` | Initial runtime recording state |
| `spanBatch(BatchConfig)` | — | 2048 / 512 / 5s / 30s | Trace queue size, batch size, delay, and processor timeout |
| `logBatch(BatchConfig)` | — | 2048 / 512 / 1s / 30s | Log queue size, batch size, delay, and processor timeout |

`OTEL_EXPORTER_OTLP_HEADERS` is a comma-separated `key=value` string; values must be percent-encoded. Builder-supplied values are raw values.

For HTTP/Protobuf, supply the Collector base URL such as `http://localhost:4318`. The SDK adds `/v1/traces`, `/v1/metrics`, and `/v1/logs`.

```java
var config = SdkConfig.builder()
    .serviceName("workflow-engine")
    .endpoint("https://otel.example.com:4318")
    .protocol("http/protobuf")
    .header("authorization", "Bearer <token>")
    .trustedCertificate(Path.of("config/collector-ca.pem"))
    .exportTimeout(Duration.ofSeconds(15))
    .build();
```

## `Observability`

The object returned by `sdk.observe()` records the three signal types. Attribute keys may use either the short names in the YAML definitions or the declared OpenTelemetry names. Do not provide both forms for the same attribute.

An invalid signal is not exported. The SDK writes a diagnostic message without business attribute values.

### `trace`

```java
ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes)
ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes,
                      ObservationSpan parent)
```

Creates an `ObservationSpan`. The second overload accepts an explicit parent. For `SendStreamingMessage-event`, that object is used as a Span Link.

| `SignalKind` | Use |
|---|---|
| `INTERNAL` | Workflow, step, and agent substep |
| `CLIENT` | A2A caller and stream event |
| `SERVER` | A2A receiver; stream event and stream error are not allowed |
| `PRODUCER` / `CONSUMER` | Present in the enum; current trace definitions do not accept them |

```java
Map<String, Object> attributes = Map.of(
    "workflow.id", "wf-001",
    "workflow.type", "fault-diagnosis",
    "workflow.status", "running");
try (var span = observe.trace("a2at.workflow", SignalKind.INTERNAL, attributes)) {
    // Execute work.
    span.ok();
}
```

### `metric`

```java
void metric(String id, Number value, Map<String, ?> attributes)
```

Records a metric sample. Values must be finite and non-negative; counters accept Java integer types only. `id` may be the YAML short name or the metric OpenTelemetry name.

```java
observe.metric("workflow.duration", 180, Map.of(
    "workflow.type", "fault-diagnosis",
    "workflow.status", "completed"));
```

### `log`

```java
void log(String name, LogSeverity severity, String body, Map<String, ?> attributes)
void log(String name, LogSeverity severity, String body, Map<String, ?> attributes,
         ObservationSpan span)
void log(String name, LogSeverity severity, String body, Map<String, ?> attributes,
         Context context)
```

Records a structured log. When `span` or `context` is supplied, the log is associated with it; otherwise the current context is used.

`LogSeverity` values are `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, and `FATAL`.

## `ObservationSpan`

`ObservationSpan` implements `AutoCloseable`. Use try-with-resources; `close()` ends the span. `end()` is equivalent and repeated calls do not end a span twice.

| Method | Description |
|---|---|
| `attributes(Map<String,?>)` | Adds attributes permitted by that trace definition |
| `ok()` | Sets span status to `OK` |
| `error()` | Sets span status to `ERROR` |
| `error(Throwable)` | Records the exception and sets span status to `ERROR` |
| `makeCurrent()` | Makes the span context current; close the returned `Scope` |
| `close()` / `end()` | Ends the span |

```java
try (var span = observe.trace("a2at.substep.retrieve-alarms", SignalKind.INTERNAL, attributes);
     var scope = span.makeCurrent()) {
    try {
        callTool();
        span.ok();
    } catch (RuntimeException error) {
        span.error(error);
        throw error;
    }
}
```

## `TraceContextPropagation`

Use this helper to carry W3C Trace Context across HTTP, message queues, or another caller-owned carrier.

### `inject(C carrier, Setter<C> setter)`

Writes the current context to the carrier. It writes `traceparent` when the current span is valid.

```java
Map<String, String> headers = new HashMap<>();
try (var client = observe.trace("SendMessage", SignalKind.CLIENT, attributes);
     var scope = client.makeCurrent()) {
    TraceContextPropagation.inject(headers, Map::put);
}
```

### `extractToCurrent(C carrier, Getter<C> getter)`

Reads W3C headers and makes the extracted context current. Close its returned `Scope`, then create the server span.

```java
TraceContextPropagation.Getter<Map<String, String>> getter = new TraceContextPropagation.Getter<>() {
    public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
    public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
};

try (var scope = TraceContextPropagation.extractToCurrent(headers, getter);
     var server = observe.trace("SendMessage", SignalKind.SERVER, attributes)) {
    server.ok();
}
```

## Signal name quick reference

The complete allowlists, required attributes, types, and metric dimensions are defined by YAML resources packaged in the SDK.

| Category | Traces | Metrics | Logs |
|---|---|---|---|
| A2A / A2A-T | `SendMessage`, `SendStreamingMessage`, `SendStreamingMessage-event`, `SendStreamingMessage-end`, `GetTask`, `CancelTask`, `ListTasks`, `SubscribeToTask`, push-notification config operations | Two A2A metrics and eight Task-T / Negotiation-T / Notification-T / Authorization-T metrics | `gen_ai.agent.a2at.message` |
| Workflow | `a2at.workflow`, `a2at.step.<name>` | `workflow.duration`, `workflow.count`, `step.duration` | `gen_ai.agent.a2at.workflow`, `gen_ai.agent.a2at.step` |
| Agent | `a2at.substep.<name>` | `substep.duration`, `gen_ai.client.token.usage` | `gen_ai.agent.a2at.agent`, `gen_ai.agent.a2at.substep` |

See the [Development Guide](development-guide.md) for signal rules and YAML maintenance.
