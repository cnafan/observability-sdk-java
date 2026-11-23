# OpenAN Observability SDK for Java：API 参考

[English](../en/api-reference.md) · [开发指南](开发指南.md)

本文档对应当前 `0.1.0-SNAPSHOT` API。运行环境要求为 JDK 17 及以上。

## 包与入口

所有公开 API 位于 `net.openan.observability` 包中。应用启动时创建一个 `ObservabilitySdk`，在应用退出时关闭它。

```java
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;

try (var sdk = ObservabilitySdk.start(SdkConfig.builder()
        .serviceName("diagnosis-agent")
        .endpoint("http://localhost:4317")
        .protocol("grpc")
        .build())) {
    var observe = sdk.observe();
    // 使用 observe 上报 Trace、Metric 和 Log。
}
```

## `ObservabilitySdk`

### `start(SdkConfig config)`

创建 SDK、OTLP Trace/Metrics/Logs 导出器及批处理器。`config` 不能为 `null`。

```java
ObservabilitySdk sdk = ObservabilitySdk.start(SdkConfig.builder().build());
```

### `observe()`

返回用于采集数据的 `Observability` 实例。同一个 SDK 实例重复调用时返回同一对象。

### `flush(long timeout, TimeUnit unit)`

等待尚未导出的 Trace、Metric、Log 完成导出。全部导出成功且从上一次 `flush` 后没有导出失败时返回 `true`；超时或发生导出失败时返回 `false`。

短生命周期进程在结束前应调用该方法。

```java
if (!sdk.flush(10, TimeUnit.SECONDS)) {
    throw new IllegalStateException("OTLP export failed or timed out");
}
```

### `close()`

关闭 OpenTelemetry provider。推荐使用 try-with-resources；关闭后不应继续使用此 SDK。

### 运行时控制

`setEnabled(boolean)` 控制是否创建新的观测信号，`isEnabled()` 返回当前状态。关闭后，已经创建的 Span 仍可结束并导出。

`CompletableResultCode updateExporters(SdkConfig config)` 可更新三类信号的 OTLP endpoint、协议、请求头、TLS 材料和导出超时。SDK 先创建新 exporter 并 flush 当前 provider；`serviceName`、指标间隔或批处理参数发生变化时会拒绝更新，需新建 SDK 实例。

## `SdkConfig` 与 `SdkConfig.Builder`

`SdkConfig.builder()` 创建构造器。构造器中显式设置的值优先于对应环境变量。

| Builder 方法 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `endpoint(String)` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC: `http://localhost:4317`；HTTP/Protobuf: `http://localhost:4318` | Collector 基地址 |
| `protocol(String)` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | 支持 `grpc`、`http/protobuf` |
| `serviceName(String)` | `OTEL_SERVICE_NAME` | `unknown_service` | 写入 OpenTelemetry `service.name` 资源属性 |
| `metricInterval(Duration)` | `OTEL_METRIC_EXPORT_INTERVAL` | 60 秒 | 周期性指标导出间隔 |
| `headers(Map<String,String>)` | `OTEL_EXPORTER_OTLP_HEADERS` | 无 | 覆盖全部请求头 |
| `header(String,String)` | `OTEL_EXPORTER_OTLP_HEADERS` | 无 | 增加一个请求头；首次调用后 Builder 请求头优先 |
| `trustedCertificate(Path)` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | 系统信任库 | PEM 或 DER 格式的 X.509 CA；只可配合 `https` |
| `clientCertificate(Path)` + `clientPrivateKey(Path)` | — | 无 | mTLS 客户端证书与配对 PEM 私钥；只可配合 `https` |
| `exportTimeout(Duration)` | `OTEL_EXPORTER_OTLP_TIMEOUT` | 10 秒 | 单个 OTLP 批次导出超时；零表示不设导出时限 |
| `enabled(boolean)` | — | `true` | 初始运行时采集状态 |
| `spanBatch(BatchConfig)` | — | 2048 / 512 / 5s / 30s | Trace 队列、批大小、延迟和处理器超时 |
| `logBatch(BatchConfig)` | — | 2048 / 512 / 1s / 30s | Log 队列、批大小、延迟和处理器超时 |

`OTEL_EXPORTER_OTLP_HEADERS` 使用逗号分隔的 `key=value` 格式；值需要进行百分号编码。使用 Builder 传入时可直接提供原始值。

HTTP/Protobuf 模式请传入 Collector 基地址，例如 `http://localhost:4318`。SDK 会分别补全 `/v1/traces`、`/v1/metrics` 和 `/v1/logs`。

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

`sdk.observe()` 返回的对象提供三类信号方法。传入的属性名称可使用 YAML 中的短名称，也可使用其声明的 OpenTelemetry 全名；同一个属性不可同时使用两种名称。

校验不通过时，该条信号不会导出，SDK 会记录不含业务属性值的诊断日志。

### `trace`

```java
ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes)
ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes,
                      ObservationSpan parent)
```

创建并返回一个 Span 包装对象。第二个重载可显式指定父 Span；对于 `SendStreamingMessage-event`，该父对象作为 Span Link 使用。

| `SignalKind` | 用途 |
|---|---|
| `INTERNAL` | Workflow、Step、Agent Substep |
| `CLIENT` | A2A 发起侧，以及流事件 |
| `SERVER` | A2A 接收侧；流事件和流错误不允许 |
| `PRODUCER` / `CONSUMER` | 枚举提供的种类；当前 YAML 定义的 Trace 不接受这两种 |

```java
Map<String, Object> attributes = Map.of(
    "workflow.id", "wf-001",
    "workflow.type", "fault-diagnosis",
    "workflow.status", "running");
try (var span = observe.trace("a2at.workflow", SignalKind.INTERNAL, attributes)) {
    // 执行业务逻辑
    span.ok();
}
```

### `metric`

```java
void metric(String id, Number value, Map<String, ?> attributes)
```

记录一个指标样本。数值必须是有限且非负；计数器仅接受 Java 整数类型。`id` 使用 YAML 的短名称或指标 OpenTelemetry 全名。

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

上报结构化日志。提供 `span` 或 `context` 时，日志关联对应上下文；未提供时使用当前上下文。

`LogSeverity` 支持 `TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`FATAL`。

```java
observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, "agent completed", Map.of(
    "gen_ai.agent.name", "diagnosis-agent",
    "agent.state", "completed"), span);
```

## `ObservationSpan`

`ObservationSpan` 实现 `AutoCloseable`。建议用 try-with-resources，它会调用 `close()` 并结束 Span；`end()` 与其等效且幂等。

| 方法 | 说明 |
|---|---|
| `attributes(Map<String,?>)` | 补充该 Trace 定义允许的属性 |
| `ok()` | 将 Span 状态设为 `OK` |
| `error()` | 将 Span 状态设为 `ERROR` |
| `error(Throwable)` | 记录异常事件，并将 Span 状态设为 `ERROR` |
| `makeCurrent()` | 将该 Span 的上下文设为当前上下文，返回的 `Scope` 必须关闭 |
| `close()` / `end()` | 结束 Span，重复调用不会重复结束 |

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

该类用于在 HTTP、消息队列等自定义载体之间传播 W3C Trace Context。

### `inject(C carrier, Setter<C> setter)`

将当前上下文写入调用方持有的载体。存在有效当前 Span 时会写入 `traceparent`。

```java
Map<String, String> headers = new HashMap<>();
try (var client = observe.trace("SendMessage", SignalKind.CLIENT, attributes);
     var scope = client.makeCurrent()) {
    TraceContextPropagation.inject(headers, Map::put);
}
```

### `extractToCurrent(C carrier, Getter<C> getter)`

从载体读取 W3C 头，并将提取到的上下文设为当前上下文。返回的 `Scope` 必须关闭，之后再创建服务端 Span。

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

## 信号名称速查

完整允许属性、必填属性、类型和指标维度由打包在 SDK 中的 YAML 定义控制。

| 分类 | Trace | Metric | Log |
|---|---|---|---|
| A2A / A2A-T | `SendMessage`、`SendStreamingMessage`、`SendStreamingMessage-event`、`SendStreamingMessage-end`、`GetTask`、`CancelTask`、`ListTasks`、`SubscribeToTask`、推送通知配置操作 | A2A 两项指标、Task-T / Negotiation-T / Notification-T / Authorization-T 八项指标 | `gen_ai.agent.a2at.message` |
| Workflow | `a2at.workflow`、`a2at.step.<name>` | `workflow.duration`、`workflow.count`、`step.duration` | `gen_ai.agent.a2at.workflow`、`gen_ai.agent.a2at.step` |
| Agent | `a2at.substep.<name>` | `substep.duration`、`gen_ai.client.token.usage` | `gen_ai.agent.a2at.agent`、`gen_ai.agent.a2at.substep` |

详见 [开发指南](开发指南.md) 中的属性和 YAML 定义说明。
