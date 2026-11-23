package net.openan.observability.example;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.Observability;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;
import net.openan.observability.TraceContextPropagation;

/** Sends one Task-T sample over HTTP between two JVMs. */
public final class TaskTHttpExample {
    private static final String OPERATION = "SendMessage";
    private static final String REQUEST = "{\"extension\":\"task-t\",\"taskType\":\"diagnosis\"}";
    private static final String RESPONSE = "{\"status\":\"completed\"}";
    private static final TraceContextPropagation.Getter<Headers> HEADER_GETTER = new TraceContextPropagation.Getter<>() {
        @Override public Iterable<String> keys(Headers headers) { return headers.keySet(); }
        @Override public String get(Headers headers, String name) { return headers.getFirst(name); }
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !java.util.Set.of("server", "client").contains(args[0])) {
            throw new IllegalArgumentException("Usage: TaskTHttpExample <server|client> <port> <run-id>");
        }
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        String runId = args[2];
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("taskt-" + runId + "-" + mode).build())) {
            if (mode.equals("server")) serve(sdk.observe(), port, runId);
            else send(sdk.observe(), port, runId);
            if (!sdk.flush(10, TimeUnit.SECONDS)) throw new IllegalStateException("OTLP flush failed");
        }
    }

    private static void serve(Observability observe, int port, String runId) throws Exception {
        var complete = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/" + OPERATION, exchange -> {
            try (exchange) {
                handle(observe, exchange, runId);
            } finally {
                complete.countDown();
            }
        });
        server.start();
        try {
            System.out.println("Listening on " + server.getAddress().getPort());
            if (!complete.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("No request received");
        } finally {
            server.stop(0);
        }
    }

    private static void handle(Observability observe, HttpExchange exchange, String runId) throws IOException {
        try (var scope = TraceContextPropagation.extractToCurrent(exchange.getRequestHeaders(), HEADER_GETTER);
             var span = observe.trace(OPERATION, SignalKind.SERVER, attributes(runId))) {
            long started = System.nanoTime();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!"POST".equals(exchange.getRequestMethod()) || !REQUEST.equals(body)) {
                span.error();
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            observe.metric("task.duration", (System.nanoTime() - started) / 1_000_000.0, Map.of("task.type", "diagnosis"));
            observe.metric("task.count", 1, Map.of("task.type", "diagnosis"));
            byte[] response = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "Task-T completed " + runId,
                Map.of("extension.name", "task-t", "task.type", "diagnosis"), span);
            span.ok();
        }
    }

    private static void send(Observability observe, int port, String runId) throws Exception {
        var url = URI.create("http://127.0.0.1:" + port + "/" + OPERATION).toURL();
        var request = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        try (var span = observe.trace(OPERATION, SignalKind.CLIENT, attributes(runId)); var scope = span.makeCurrent()) {
            request.setConnectTimeout(10_000);
            request.setReadTimeout(10_000);
            request.setRequestMethod("POST");
            request.setRequestProperty("Content-Type", "application/json");
            request.setDoOutput(true);
            TraceContextPropagation.inject(request, HttpURLConnection::setRequestProperty);
            long started = System.nanoTime();
            try {
                try (var body = request.getOutputStream()) {
                    body.write(REQUEST.getBytes(StandardCharsets.UTF_8));
                }
                try (var response = request.getInputStream()) {
                    if (request.getResponseCode() != 200 || !RESPONSE.equals(new String(response.readAllBytes(), StandardCharsets.UTF_8))) {
                        throw new IOException("Unexpected Task-T response");
                    }
                }
            } catch (IOException error) {
                span.error();
                throw error;
            }
            observe.metric("gen_ai.client.operation.duration", (System.nanoTime() - started) / 1_000_000_000.0,
                Map.of("operation.name", OPERATION));
            span.ok();
        } finally {
            request.disconnect();
        }
    }

    private static Map<String, String> attributes(String runId) {
        return Map.of("operation.name", OPERATION, "message_id", runId, "extension.name", "task-t", "task.type", "diagnosis");
    }
}
