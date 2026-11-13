# Observability SDK（首期）

Java 17 及以上。SDK 接收采集方提供的 A2A-T 数据，校验 13 项协议属性与 8 项指标，转换后使用 OTel SDK 统一导出 Trace、Metric 和 Log。

## 构建

```powershell
mvn -B -ntp test
mvn -B -ntp package
```

## 接入

应用启动时创建一个 `ObservabilitySdk`，将 `observe()` 交给采集方；应用退出时关闭。避免在一个进程重复初始化上报通道，也不要将同一测量同时写入原来的 OTel 通道。SDK 不注册 OTel 全局实例；使用其他 OTel 插桩的宿主需要单独处理其上报通道。

```java
try (ObservabilitySdk sdk = ObservabilitySdk.start(
        SdkConfig.builder().serviceName("my-agent").build())) {
    Observability observe = sdk.observe();
    var attrs = Map.of("extension.name", "task-t", "task.type", "diagnosis");
    try (ObservationSpan span = observe.trace("message/send", SignalKind.CLIENT, attrs)) {
        observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "诊断完成", attrs, span);
        observe.metric("task.duration", 42000, Map.of("task.type", "diagnosis"));
        observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
        span.ok();
    }
    boolean sent = sdk.flush(10, TimeUnit.SECONDS);
}
```

调用方负责业务异常处理；发生异常时可调用 `span.error()` 并确保结束 Span。`span.makeCurrent()` 返回 OTel `Scope`，用于同线程关联，使用后应关闭；跨线程可将父 `ObservationSpan` 显式传给 `trace`，或将关联的 Span 传给 `log`。跨进程的上下文注入、提取及 A2A SDK 真实接入尚未实现。

属性使用短名或 `gen_ai.agent.a2at.` 全名，重复传入同一属性、未知属性、信号不适用的属性、指标维度不匹配、错误类型或枚举值会使该条信号被丢弃，诊断信息不包含输入值。枚举忽略大小写。Span 和 Log 的名称分别为 `message/send` 和 `gen_ai.agent.a2at.message`，必填 `extension.name`，支持 `task-t`、`negotiation-t`、`notification-t`、`authorization-t`；各扩展只允许对应协议字段。Task-T 还须提供非空 `task.type`；`task.duration`、`task.count` 必须提供非空 `task.type`，其余指标维度可省略。耗时以毫秒传入，轮次和计数使用整数。Histogram 传单次测量值，由 OTel 聚合。

SDK 对字符串中的常见凭证形式做替换，去掉 URL 的用户信息、查询串和片段，单个字符串最多保留 2048 字符。此处理无法识别所有敏感内容；接入方仍需提供允许上报的字段和经过处理的日志摘要，避免在 URL 路径中携带敏感数据。

## Collector 配置

支持统一配置（显式 builder 值优先于环境变量）：

| Builder | 环境变量 | 默认值 |
|---|---|---|
| `endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | gRPC: `http://localhost:4317`；HTTP: `http://localhost:4318` |
| `protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc`；也支持 `http/protobuf` |
| `serviceName` | `OTEL_SERVICE_NAME` | `unknown_service` |
| `metricInterval` | `OTEL_METRIC_EXPORT_INTERVAL` | `60000` 毫秒 |

HTTP 地址为基地址，SDK 分别添加 `/v1/traces`、`/v1/metrics` 和 `/v1/logs`。此版本不提供认证头、TLS 客户端证书等配置；生产接入前需补充并验证。`flush` 返回导出是否成功，建议短进程在退出前调用；`close` 释放资源。网络不可用时 OTel 导出器负责重试和报告错误，打点方法不会等待 Collector 响应。

## 本地联调

用已安装的 OpenTelemetry Collector 启动本目录下的 `collector-config.yaml`，然后在另一终端运行：

```powershell
mvn -B -ntp compile exec:java '-Dexec.mainClass=net.openan.observability.example.TaskTExample' '-Dexec.cleanupDaemonThreads=false'
```

检查 Collector 输出中 `message/send` Span、`task.duration` Histogram（count 为 1、sum 为 42000）、`task.count` Sum（值为 1）、`gen_ai.agent.a2at.message` Log，以及 Trace/Span ID 关联。联调时请确保原采集方没有同时启用旧通道。

## 首期验收记录（2026-11-13）

使用 OpenTelemetry Collector 0.139.0 和本模块 `collector-config.yaml`，分别以独立 SDK 进程通过 gRPC 和 HTTP/Protobuf 运行 `TaskTExample`。每次均收到一个 CLIENT `message/send` Span、一个 INFO 日志及两项指标；日志与 Span 的 Trace ID、Span ID 一致，资源服务名为 `a2at-demo`，scope 为 `a2at.observability`。Histogram 的 count 为 1、sum 为 42000、单位为 ms；单调 Counter 值为 1。未知 Span、Log、Metric 均未出现，带非法维度的 999 增量未改变计数。刷新和关闭各导出的累计指标值一致。

示例同时提交上述非法输入；切换协议可设置 `OTEL_EXPORTER_OTLP_PROTOCOL` 为 `grpc` 或 `http/protobuf`。无接收端测试检查打点返回、限时刷新返回失败和关闭完成。

## 四种扩展验收（2026-11-13）

使用 Collector 0.139.0 和 `collector-config.yaml`，通过独立 SDK 进程分别以 gRPC 和 HTTP/Protobuf 运行：

```powershell
mvn -B -ntp compile exec:java '-Dexec.mainClass=net.openan.observability.example.ExtensionsExample' '-Dexec.cleanupDaemonThreads=false'
```

两次分别接收 4 个 Span、4 条关联 Log 和全部 8 项指标；日志与对应 Span 的 Trace ID 和 Span ID 一致。四种扩展及各自字段通过两个通道，通知 URL 的用户信息和查询参数均被去除。未知扩展、跨扩展字段、Span 中仅 Log 允许的字段，以及带非法维度的计数增量未导出。刷新及关闭导出的累计指标均符合样例值。

下表的 S、L、M 表示本次在两个 Collector 通道均检查到该属性；Metric 仅按各指标允许的维度接收。

| 属性（省略 `gen_ai.agent.a2at.`） | 验收信号 |
|---|---|
| `extension.name` | S、L |
| `task.type` | S、L、M |
| `task.object` | S、L |
| `negotiation.id` | S、L、M |
| `negotiation.round` | S、L |
| `negotiation.total_rounds` | S、L |
| `negotiation.max_rounds` | L |
| `negotiation.result` | S、L、M |
| `negotiation.performative` | S、L |
| `notification.topic` | S、L、M |
| `push.notification.url` | S、L |
| `authorization.stage` | S、L |
| `authorization.policy.operation.type` | S、L、M |
