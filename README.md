# OpenAN Observability SDK for Java

将 A2A-T 观测数据转换为 OpenTelemetry 信号，并通过 OTLP 上报到 OpenTelemetry Collector。

## 项目简介

本模块供 A2A 和 A2A-T SDK 接入。采集方在消息发送、接收或业务结果产生时打点，提供协议属性、测量值或消息摘要。Observability Core 校验白名单并转换数据，由内置的 OpenTelemetry Java SDK 完成聚合、批处理和导出。

```text
A2A / A2A-T 采集方 → Observability Core → OpenTelemetry SDK → OTLP → Collector
```

## 核心能力

| 能力 | 当前实现 |
|---|---|
| 信号校验 | 按信号和扩展类型检查名称、允许字段、必填项和取值。非法输入在写入 OpenTelemetry 前被拒绝。 |
| OTel 转换 | 支持 Task-T、Negotiation-T、Notification-T、Authorization-T。13 项协议属性均有可用的上报入口，另支持 8 项 Metric。 |
| 统一上报 | Trace、Metric、Log 共用一套 Collector 地址、OTLP 协议和服务名。SDK 统一管理三个 Exporter 的初始化、刷新和关闭。 |

## 项目结构

| 位置 | 内容 |
|---|---|
| [`src/main/java/net/openan/observability/`](src/main/java/net/openan/observability/) | Java 接口和 OTel 导出配置 |
| [`internal/`](src/main/java/net/openan/observability/internal/) | 内部字段定义、校验与转换，不作为稳定公开 API |
| [`examples/`](examples/) | `TaskTExample` 演示任务场景，`ExtensionsExample` 验证四种扩展 |
| [`src/test/java/`](src/test/java/) | 字段校验、信号转换、上下文和配置测试 |
| [`examples/collector-config.yaml`](examples/collector-config.yaml) | 本地联调配置，接收三类 OTLP 信号并输出到 Collector 日志 |

## 快速开始

需要 JDK 17 或更高版本、Maven，以及可运行的 OpenTelemetry Collector。以下命令在仓库根目录执行。

**1. 启动 Collector。** 将 `otelcol` 加入 `PATH`，在第一个终端运行：

```powershell
otelcol --config=examples/collector-config.yaml
```

**2. 运行四种扩展的样例。** 在第二个终端运行：

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'grpc'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4317'
mvn -B -ntp -Pexamples test-compile exec:java '-Dexec.classpathScope=test' '-Dexec.mainClass=net.openan.observability.example.ExtensionsExample' '-Dexec.cleanupDaemonThreads=false'
```

验证 HTTP/Protobuf 时，将协议改为 `http/protobuf`、地址改为 `http://localhost:4318`，再运行同一条 Maven 命令。

**3. 查看 Collector 输出。** 每次运行应收到 4 个 `message/send` Span、4 条 `gen_ai.agent.a2at.message` Log 和 8 项 Metric。日志的 Trace ID 和 Span ID 应与对应 Span 一致。可用 `service.name=a2at-extensions-demo` 找到样例数据。

例如 `task.duration` 的 Histogram 应为 `count=1`、`sum=42000`、单位 `ms`，`task.count` 应为 `1`。样例也会提交非法输入，本地出现 `Observation ... rejected` 是预期行为。Collector 中不应出现 `invalid-marker`，非法增量 `999` 不应改变 `task.count`。刷新和关闭可能分别导出一次相同的累计值。

## 接入应用

从本目录执行 `mvn -B -ntp install`，在接入方的 `pom.xml` 中添加本地构建的依赖。

```xml
<dependency>
    <groupId>net.openan</groupId>
    <artifactId>observability-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

应用启动时创建一个 `ObservabilitySdk`，将 `sdk.observe()` 交给采集方。下面用固定的任务耗时演示三个入口。实际接入时，由采集方在业务结果到达时提交测量值。

```java
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

class Example {
    public static void main(String[] args) {
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("my-agent").build())) {
            var observe = sdk.observe();
            var attrs = Map.of("extension.name", "task-t", "task.type", "diagnosis");
            try (var span = observe.trace("message/send", SignalKind.CLIENT, attrs)) {
                observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "诊断完成", attrs, span);
                observe.metric("task.duration", 42_000, Map.of("task.type", "diagnosis"));
                observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
                span.ok();
            }
            if (!sdk.flush(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Observability flush failed or timed out");
            }
        }
    }
}
```

在真实业务代码中，操作失败时可调用 `span.error()`，用 `try-with-resources` 确保 Span 结束。`span.makeCurrent()` 可在同线程建立上下文，返回的 `Scope` 用后关闭。跨线程时可显式传入父 `ObservationSpan` 或日志关联的 `ObservationSpan`。应用退出时关闭 SDK，短进程可在结束 Span 后先调用 `flush()`。

### Collector 配置

Java builder 显式值优先，其次读取环境变量，最后使用默认值。

| Builder 方法 | 环境变量 | 默认值 |
|---|---|---|
| `endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC 为 `http://localhost:4317`，HTTP 为 `http://localhost:4318` |
| `protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc`，也支持 `http/protobuf` |
| `serviceName` | `OTEL_SERVICE_NAME` | `unknown_service` |
| `metricInterval` | `OTEL_METRIC_EXPORT_INTERVAL` | `60000` 毫秒，builder 接收 `Duration` |

HTTP 配置填写基地址，SDK 会分别拼接 `/v1/traces`、`/v1/metrics`、`/v1/logs`。Collector 需要启用对应的 OTLP receiver，并接入三类信号的 pipeline。

## 当前支持范围

### Span 和 Log

Span 名称为 `message/send`，Kind 为 `CLIENT` 或 `SERVER`。Log 事件名为 `gen_ai.agent.a2at.message`，正文传处理后的消息摘要。两类信号都必须提供 `extension.name`，其取值决定允许的其他字段。

| `extension.name` | Span 和 Log 允许的其他属性 | 仅 Log 允许 |
|---|---|---|
| `task-t` | `task.type`（必填且非空白）、`task.object` | — |
| `negotiation-t` | `negotiation.id`、`negotiation.round`、`negotiation.total_rounds`、`negotiation.result`、`negotiation.performative` | `negotiation.max_rounds` |
| `notification-t` | `notification.topic`、`push.notification.url` | — |
| `authorization-t` | `authorization.stage`、`authorization.policy.operation.type` | — |

轮次属性传整数，其余属性传字符串。枚举输入忽略大小写，输出为小写。`negotiation.result` 取 `accept`、`reject`、`timeout`，`negotiation.performative` 取 `propose`、`reject`、`accept`，`authorization.stage` 取 `preauthorize`、`execute`。

### Metric

| 指标 ID | 类型 | 单位 | 允许的维度 |
|---|---|---|---|
| `task.duration` | Histogram | `ms` | `task.type`（必填且非空白） |
| `task.count` | Counter | — | `task.type`（必填且非空白） |
| `negotiation.rounds` | Histogram | `{round}` | `negotiation.id`、`negotiation.result` |
| `negotiation.count` | Counter | — | `negotiation.result` |
| `notification.count` | Counter | — | `notification.topic` |
| `authorization.preauthorize.count` | Counter | — | `authorization.policy.operation.type` |
| `authorization.preauthorize.duration` | Histogram | `ms` | `authorization.policy.operation.type` |
| `authorization.execute.count` | Counter | — | `authorization.policy.operation.type` |

Histogram 接收单次测量值，由 OTel 聚合。Counter 接收单次计数增量。测量值须为有限非负数，轮次和计数传整数。表中未标必填的维度可省略。

### 输入规则与接入边界

- 属性名和指标 ID 可使用表中的短名，也可带 `gen_ai.agent.a2at.` 前缀。导出统一使用全名。同一属性同时用短名和全名传入时，整条信号会被拒绝。
- 诊断日志省略输入值。SDK 会处理字符串中的常见凭证，去掉 URL 的用户信息、查询串和片段，并将单个字符串限制在 2048 字符以内。采集方负责提供允许上报的数据和处理后的日志摘要。
- 当前实现通过显式 Java 接口接入，SDK 持有自己的 OTel 实例。同一打点迁移时应切换到单一上报通道。跨进程上下文传播和 A2A SDK 真实接入尚未验证，自定义认证头和 TLS 客户端证书配置尚未提供。

## 开发与测试

```powershell
mvn -B -ntp clean verify
```

JUnit 测试覆盖三类信号转换、四种扩展的字段白名单、Span 与 Log 关联，以及配置和刷新超时。实际 OTLP 上报可按快速开始的步骤验证。
