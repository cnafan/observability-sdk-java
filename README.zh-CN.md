# OpenAN Observability SDK for Java

[English](README.md) | 简体中文

面向 OpenAN 应用的 Java 观测 SDK。应用在 A2A-T 协作、Workflow 编排和 Agent 执行处调用统一 API；SDK 将 Trace、Metric、Log 转换为 OpenTelemetry 数据并通过 OTLP 发送到 OpenTelemetry Collector。

```text
业务代码 ──> Observability SDK ──> OpenTelemetry SDK ──OTLP──> Collector
                │
                ├── A2A / A2A-T
                ├── Workflow
                └── Agent / Substep
```

## 支持范围

| 分类 | Trace | Metric | Log | 定义文件 |
|---|---|---|---|---|
| A2A / A2A-T | A2A 消息、任务和流式调用；四种 A2A-T 扩展 | 2 项 A2A 指标、8 项 A2A-T 指标 | `gen_ai.agent.a2at.message` | [`a2at.yaml`](src/main/resources/observability/a2at.yaml) |
| Workflow | `a2at.workflow`、`a2at.step.<name>` | `workflow.duration`、`workflow.count`、`step.duration` | Workflow 与 Step 事件 | [`workflow.yaml`](src/main/resources/observability/workflow.yaml) |
| Agent | `a2at.substep.<name>` | `substep.duration`、`gen_ai.client.token.usage` | Agent 生命周期与 Substep 事件 | [`agent.yaml`](src/main/resources/observability/agent.yaml) |

三份 YAML 均包含版本、属性类型、信号名称、指标维度和必填属性。调整标准时修改对应 YAML 并递增 `version`；SDK 在导出前按信号名称和属性白名单校验输入。

其中 `extension.name` 是 A2A-T 的分支字段：值为 `task-t`、`negotiation-t`、`notification-t` 或 `authorization-t` 时，分别使用该扩展定义的 Span 和 Log 属性白名单。其他属性只校验名称、类型、信号适用范围和必填性，不校验业务取值。

## 快速开始

需要 JDK 17+、Maven 和可用的 OpenTelemetry Collector。

1. 在仓库根目录启动本地 Collector：

```powershell
otelcol --config=examples/collector-config.yaml
```

2. 构建并安装 SDK：

```powershell
mvn -B -ntp clean install
```

3. 在接入应用的 `pom.xml` 中引入依赖：

```xml
<dependency>
    <groupId>net.openan</groupId>
    <artifactId>observability-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`examples/collector-config.yaml` 同时接收 Trace、Metric 和 Log，并输出到 Collector 日志，适合本地检查数据结构。生产环境只需将三个 pipeline 的 exporter 替换为实际存储或转发目标。

## 接入方式

应用启动时创建一个 `ObservabilitySdk`；业务代码通过 `observe()` 获得 API。显式 `SdkConfig` 值优先于环境变量。

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

    // A2A-T：Task-T 消息、日志和指标。
    var task = Map.of("extension.name", "task-t", "task.type", "diagnosis");
    try (var span = observe.trace("SendMessage", SignalKind.CLIENT, task)) {
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO,
            "diagnosis completed", task, span);
        observe.metric("task.duration", 42_000, Map.of("task.type", "diagnosis"));
        observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
        span.ok();
    }

    // Workflow：工作流 Trace 与指标。
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

    // Agent：子步骤 Trace、Token 用量和生命周期日志。
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

调用 `span.error()` 记录失败；`span.makeCurrent()` 可在当前线程建立上下文，使用结束后关闭返回的 `Scope`。跨线程或关联日志时，传入对应的 `ObservationSpan`。应用停止前关闭 `ObservabilitySdk`；短进程在结束 Span 后调用 `flush()`。

### 跨进程 Trace Context 与异常

使用 `TraceContextPropagation` 向出站载体写入当前 W3C `traceparent`，并在创建服务端 Span 前从入站载体提取上下文。`error(Throwable)` 会记录异常并将 Span 标记为失败。

```java
try (var client = observe.trace("SendMessage", SignalKind.CLIENT, attributes);
     var scope = client.makeCurrent()) {
    TraceContextPropagation.inject(requestHeaders, Map::put);
}

try (var scope = TraceContextPropagation.extractToCurrent(requestHeaders, headerGetter);
     var server = observe.trace("SendMessage", SignalKind.SERVER, attributes)) {
    try {
        // 处理请求。
        server.ok();
    } catch (RuntimeException error) {
        server.error(error);
        throw error;
    }
}
```

## 信号规则

### A2A / A2A-T

- A2A Trace 支持 `SendMessage`、`SendStreamingMessage`、`SendStreamingMessage-event`、`SendStreamingMessage-end` 与各任务操作（`GetTask`、`CancelTask`、`ListTasks`、`SubscribeToTask` 及推送通知配置操作）；流事件可关联上游 Span Link。
- A2A 指标为 `gen_ai.client.operation.duration` 与 `gen_ai.agent.a2a.streaming.duration`，均要求 `operation.name`。
- A2A-T 包含 Task-T、Negotiation-T、Notification-T、Authorization-T 四种扩展，以及 `task.duration`、`task.count`、`negotiation.rounds`、`negotiation.count`、`notification.count` 和三项 Authorization 指标。
- `SendMessage`、`SendStreamingMessage` 和 `gen_ai.agent.a2at.message` 使用 `extension.name` 选择对应扩展属性。Task-T 的 `task.type` 必填且不能为空。

### Workflow

- `a2at.workflow` 要求 `workflow.id`、`workflow.type`、`workflow.status`；`a2at.step.<name>` 要求步骤名称、序号和状态。
- `workflow.duration`、`workflow.count` 要求 `workflow.type` 与 `workflow.status`；`step.duration` 要求 `workflow.step` 与 `gen_ai.agent.name`。
- Log 名称为 `gen_ai.agent.a2at.workflow` 和 `gen_ai.agent.a2at.step`。

### Agent

- `a2at.substep.<name>` 要求 `gen_ai.agent.name`、`substep.name` 和 `substep.status`。
- `substep.duration` 要求 Agent 与子步骤名称；`gen_ai.client.token.usage` 要求 Agent、模型和 Token 类型。
- Log 名称为 `gen_ai.agent.a2at.agent` 和 `gen_ai.agent.a2at.substep`；`agent.skills` 传非空字符串列表。

短属性名和带 `gen_ai.agent.a2at.` 前缀的属性名都可传入，SDK 导出时使用 YAML 中的 OTel 名称。同一属性同时使用短名和全名时，该条信号会被拒绝。非法 Trace、Metric 或 Log 不会发送到 Collector，并会输出不带输入值的诊断日志。

## OTLP 配置

| Builder 方法 | 环境变量 | 默认值 |
|---|---|---|
| `endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC：`http://localhost:4317`；HTTP/Protobuf：`http://localhost:4318` |
| `protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc`；也支持 `http/protobuf` |
| `serviceName` | `OTEL_SERVICE_NAME` | `unknown_service` |
| `metricInterval` | `OTEL_METRIC_EXPORT_INTERVAL` | `60000` 毫秒 |
| `headers(Map)` / `header(name, value)` | `OTEL_EXPORTER_OTLP_HEADERS` | 不添加请求头 |
| `trustedCertificate(Path)` | `OTEL_EXPORTER_OTLP_CERTIFICATE` | 系统默认信任库 |
| `clientCertificate(Path)` + `clientPrivateKey(Path)` | — | 不配置客户端证书 |
| `exportTimeout(Duration)` | `OTEL_EXPORTER_OTLP_TIMEOUT` | `10000` 毫秒 |
| `enabled(boolean)` | — | `true` |
| `spanBatch(BatchConfig)` | — | 队列 `2048`、批大小 `512`、延迟 `5s`、超时 `30s` |
| `logBatch(BatchConfig)` | — | 队列 `2048`、批大小 `512`、延迟 `1s`、超时 `30s` |

HTTP/Protobuf 填写 Collector 基地址，SDK 自动追加 `/v1/traces`、`/v1/metrics` 和 `/v1/logs`。`https` 启用 TLS；指定受信 CA 或客户端证书时 endpoint 必须使用 `https`。认证头由三类 exporter 共用。环境变量中的头按 `key=value,key2=value2` 填写，值使用百分号编码；Builder 中直接传原始值。当前不支持 OTLP `http/json`。

调用 `setEnabled(false)` 会停止创建新的观测信号，调用 `setEnabled(true)` 恢复；开关前已创建的 Span 仍可结束并导出。关闭时 SDK 会跳过自身的字段校验和 OpenTelemetry API 调用，应用在调用 SDK 前自行创建的对象不在此范围内。

Log4j2 可选模块位于 `observability-sdk-log4j2/`，仅转发包含指定 Marker 的日志。模块从 MDC 读取 `traceId` 和 `spanId`，并映射 Log4j2 级别到 SDK 的日志级别。

## 示例与验证

| 位置 | 用途 |
|---|---|
| [`examples/`](examples/) | A2A、四种 A2A-T 扩展及跨进程 Task-T 示例 |
| [`WorkflowAgentExample`](examples/src/main/java/net/openan/observability/example/WorkflowAgentExample.java) | Workflow、Step、Agent 子步骤、Token 用量和生命周期日志 |
| [`examples/collector-config.yaml`](examples/collector-config.yaml) | 本地 gRPC / HTTP OTLP 接收配置 |
| [`examples/collector-auth-tls-config.yaml`](examples/collector-auth-tls-config.yaml) | TLS 与 Bearer Token 接收配置 |
| [`deploy/local-storage/`](deploy/local-storage/) | 本地 Collector、Prometheus/Thanos、OpenSearch/Data Prepper 与 Jaeger 存储栈 |
| [`consumer-smoke/`](consumer-smoke/) | 独立 Maven 消费应用 |
| [`src/test/java/`](src/test/java/) | YAML 定义、字段校验、三类信号转换、OTLP 配置与导出失败测试 |

运行完整测试：

```powershell
mvn -B -ntp -Pexamples clean verify
```

运行四种 A2A-T 扩展示例：

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
$runId = 'a2at-extensions-local'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.ExtensionsExample' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

运行 Workflow 与 Agent 示例：

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.WorkflowAgentExample' '-Dexec.args=local' '-Dexec.cleanupDaemonThreads=false'
```

默认 JAR 不包含 `examples/` 中的类；`examples` profile 仅用于编译和运行这些本地样例。
