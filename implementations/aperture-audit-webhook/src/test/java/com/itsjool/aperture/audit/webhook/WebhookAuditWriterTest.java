package com.itsjool.aperture.audit.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.spi.AuditEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookAuditWriterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsAuditEventsAsJsonArrayBatches() throws Exception {
        URI url = startServer(200);
        WebhookAuditWriter writer = new WebhookAuditWriter(url, 10, Duration.ofMillis(25), 100);

        writer.write(new AuditEvent("user-1", "tenant-1", "Customer", "customer-1", "UPDATE", "{\"fieldPath\":\"name\",\"before\":\"old\",\"after\":\"new\"}"));
        writer.write(new AuditEvent("user-2", "tenant-1", "Customer", "customer-2", "CREATE", "{\"fieldPath\":\"email\",\"before\":null,\"after\":\"a@example.com\"}"));

        JsonNode payload = waitForPayload();
        writer.shutdown();

        assertThat(payload).hasSize(2);
        assertThat(payload.get(0).get("userId").asText()).isEqualTo("user-1");
        assertThat(payload.get(0).get("entity").asText()).isEqualTo("Customer");
        assertThat(payload.get(0).get("details").get("before").asText()).isEqualTo("old");
        assertThat(payload.get(1).get("details").get("before").isNull()).isTrue();
    }

    @Test
    void shutdownFlushesQueuedEvents() throws Exception {
        URI url = startServer(200);
        WebhookAuditWriter writer = new WebhookAuditWriter(url, 10, Duration.ofSeconds(30), 100);

        writer.write(new AuditEvent("user-1", "tenant-1", "Invoice", "invoice-1", "DELETE", "{\"fieldPath\":\"status\",\"before\":\"open\",\"after\":null}"));
        writer.shutdown();

        JsonNode payload = waitForPayload();
        assertThat(payload).hasSize(1);
        assertThat(payload.get(0).get("operation").asText()).isEqualTo("DELETE");
        assertThat(payload.get(0).get("details").get("after").isNull()).isTrue();
    }

    private URI startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/siem/audit", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(bytes, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/siem/audit");
    }

    private JsonNode waitForPayload() throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (!bodies.isEmpty()) {
                return OBJECT_MAPPER.readTree(bodies.get(0));
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for webhook payload");
    }
}
