# SDK Consumer Smoke App

This is a separate Maven application that imports the locally installed `net.openan:observability-sdk` artifact.

From the SDK repository root, install the artifact:

```powershell
mvn -B -ntp install
```

Then, from this directory, compile and run the app. Collector connection settings are read from the standard OTLP environment variables:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'http/protobuf'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:14318'
$runId = 'consumer-20261117'
mvn -B -ntp package exec:java '-Dexec.mainClass=net.openan.observability.consumer.ConsumerSmoke' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

The app creates one Task-T Span, a linked message Log, and the `task.duration` and `task.count` metrics, waits for `flush()`, then closes the SDK. It sets `service.name` to `sdk-consumer-<runId>` for Collector filtering.

## Full signal consumer

`ConsumerAllSignals` is separate from the fast smoke consumer. It emits every configured
signal family with valid attributes as a correlated task flow. The A2A client/server,
Workflow, Step, and Agent Substep spans use parent-child relationships and share the
same task, context, and message identifiers. Logs are attached to their corresponding
business span. Streaming event and end spans are intentionally emitted as Span Links,
which is the SDK's configured model for asynchronous stream events.

```text
SendMessage CLIENT (Task-T)
└─ SendMessage SERVER
   ├─ A2A client/server operation pairs and push-related client spans
   ├─ SendStreamingMessage CLIENT → SERVER → stream negotiation
   │  ├─ SendStreamingMessage-event  -- Span Link --> stream client
   │  └─ SendStreamingMessage-end    -- Span Link --> stream client
   ├─ Workflow → Step → Agent Substep
   └─ Negotiation-T / Notification-T / Authorization-T message and stream spans
```

The flow covers:

- A2A client/server operations, streaming event/end, stream negotiation, and push-related traces;
- A2A operation and streaming metrics;
- Task-T, Negotiation-T, Notification-T, and Authorization-T message/stream traces, logs, and metrics;
- Workflow and step traces, logs, and metrics;
- Agent and substep traces, logs, and metrics.

Run it after installing the SDK artifact and configuring the Collector endpoint:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'http/protobuf'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:14318'
$runId = 'consumer-all-20261124'
mvn -B -ntp package exec:java '-Dexec.mainClass=net.openan.observability.consumer.ConsumerAllSignals' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

The full consumer sets `service.name` to `sdk-consumer-all-<runId>`. Use this service name
when querying Prometheus, OpenSearch, and Jaeger.
