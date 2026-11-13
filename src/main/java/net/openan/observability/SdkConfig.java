package net.openan.observability;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Explicit builder values take precedence over environment variables. */
public final class SdkConfig {
    final URI endpoint;
    final String protocol, serviceName;
    final Duration metricInterval;
    private SdkConfig(Builder b, Map<String, String> env) {
        protocol = select(b.protocol, env.get("OTEL_EXPORTER_OTLP_PROTOCOL"), "grpc").toLowerCase(Locale.ROOT);
        if (!protocol.equals("grpc") && !protocol.equals("http/protobuf")) throw new IllegalArgumentException("Unsupported OTLP protocol");
        String defaultEndpoint = protocol.equals("grpc") ? "http://localhost:4317" : "http://localhost:4318";
        try { endpoint = URI.create(select(b.endpoint, env.get("OTEL_EXPORTER_OTLP_ENDPOINT"), defaultEndpoint)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid OTLP endpoint"); }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) throw new IllegalArgumentException("Invalid OTLP endpoint");
        serviceName = select(b.serviceName, env.get("OTEL_SERVICE_NAME"), "unknown_service");
        try { metricInterval = b.metricInterval != null ? b.metricInterval : Duration.ofMillis(Long.parseLong(select(null, env.get("OTEL_METRIC_EXPORT_INTERVAL"), "60000"))); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid metric interval"); }
        if (metricInterval.isZero() || metricInterval.isNegative() || metricInterval.toMillis() < 1) throw new IllegalArgumentException("Invalid metric interval");
    }
    private static String select(String explicit, String environment, String fallback) { return explicit != null ? explicit : environment == null || environment.isBlank() ? fallback : environment; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String endpoint, protocol, serviceName;
        private Duration metricInterval;
        public Builder endpoint(String v) { endpoint = v; return this; }
        public Builder protocol(String v) { protocol = v; return this; }
        public Builder serviceName(String v) { if (v == null || v.isBlank()) throw new IllegalArgumentException("serviceName is empty"); serviceName = v; return this; }
        public Builder metricInterval(Duration v) { metricInterval = v; return this; }
        public SdkConfig build() { return new SdkConfig(this, System.getenv()); }
        SdkConfig build(Map<String, String> env) { return new SdkConfig(this, env); }
    }
}
