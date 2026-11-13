package net.openan.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ObservabilitySdk implements AutoCloseable {
    private final OpenTelemetrySdk otel;
    private final Observability observe;
    private final AtomicBoolean closed = new AtomicBoolean();
    private ObservabilitySdk(OpenTelemetrySdk otel) { this.otel = otel; this.observe = new Observability(otel); }
    public static ObservabilitySdk start(SdkConfig config) {
        if (config == null) throw new IllegalArgumentException("config is required");
        String endpoint = config.endpoint.toString().replaceAll("/+$", "");
        SpanExporter spans;
        MetricExporter metrics;
        LogRecordExporter logs;
        if (config.protocol.equals("grpc")) {
            spans = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
            metrics = OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build();
            logs = OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint).build();
        } else {
            spans = OtlpHttpSpanExporter.builder().setEndpoint(endpoint + "/v1/traces").build();
            metrics = OtlpHttpMetricExporter.builder().setEndpoint(endpoint + "/v1/metrics").build();
            logs = OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs").build();
        }
        Resource resource = Resource.getDefault().toBuilder().put(AttributeKey.stringKey("service.name"), config.serviceName).build();
        SdkTracerProvider tracer = SdkTracerProvider.builder().setResource(resource).addSpanProcessor(BatchSpanProcessor.builder(spans).build()).build();
        SdkMeterProvider meter = SdkMeterProvider.builder().setResource(resource).registerMetricReader(PeriodicMetricReader.builder(metrics).setInterval(config.metricInterval).build()).build();
        SdkLoggerProvider logger = SdkLoggerProvider.builder().setResource(resource).addLogRecordProcessor(BatchLogRecordProcessor.builder(logs).build()).build();
        return new ObservabilitySdk(OpenTelemetrySdk.builder().setTracerProvider(tracer).setMeterProvider(meter).setLoggerProvider(logger).build());
    }
    public Observability observe() { return observe; }
    /** Returns false when the export fails or the timeout expires. */
    public boolean flush(long timeout, TimeUnit unit) {
        CompletableResultCode result = CompletableResultCode.ofAll(java.util.List.of(
            otel.getSdkTracerProvider().forceFlush(), otel.getSdkMeterProvider().forceFlush(), otel.getSdkLoggerProvider().forceFlush()));
        result.join(timeout, unit);
        return result.isSuccess();
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) otel.close(); }
}
