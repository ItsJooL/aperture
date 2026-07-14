package com.itsjool.aperture.runtime.hook;

import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that {@link HookExecutor#executeHookWithResponse} records an "aperture.hook"
 * {@link io.micrometer.observation.Observation} with the expected low-cardinality tags, using a
 * real {@link com.sun.net.httpserver.HttpServer} instance so the HTTP call (and its observation
 * lifecycle) is exercised end to end rather than mocked.
 */
class HookExecutorTest {

    private HttpServer server;
    private HookExecutor hookExecutor;

    @AfterEach
    void tearDown() {
        if (hookExecutor != null) {
            hookExecutor.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void recordsOkOutcomeAndExpectedTagsOnSuccess() throws Exception {
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        String url = startServer(200, "{\"ok\":true}");
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), observationRegistry);

        String body = hookExecutor.executeHookWithResponse("my-hook", "Invoice", "PREENRICH", url, "{}", null, "warn");

        assertThat(body).isEqualTo("{\"ok\":true}");
        TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasSingleObservationThat()
            .hasNameEqualTo("aperture.hook")
            .hasBeenStarted()
            .hasBeenStopped()
            .hasLowCardinalityKeyValue("hook.name", "my-hook")
            .hasLowCardinalityKeyValue("hook.phase", "PREENRICH")
            .hasLowCardinalityKeyValue("hook.async", "false")
            .hasLowCardinalityKeyValue("entity", "Invoice")
            .hasLowCardinalityKeyValue("outcome", "ok");
    }

    @Test
    void recordsRejectedOutcomeOnNon2xxWithoutThrowingWhenFailBehaviorIsWarn() throws Exception {
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        String url = startServer(422, "{\"error\":\"nope\"}");
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), observationRegistry);

        String body = hookExecutor.executeHookWithResponse("my-hook", "Invoice", "PREENRICH", url, "{}", null, "warn");

        assertThat(body).isNull();
        TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasSingleObservationThat()
            .hasNameEqualTo("aperture.hook")
            .hasLowCardinalityKeyValue("hook.name", "my-hook")
            .hasLowCardinalityKeyValue("hook.phase", "PREENRICH")
            .hasLowCardinalityKeyValue("hook.async", "false")
            .hasLowCardinalityKeyValue("entity", "Invoice")
            .hasLowCardinalityKeyValue("outcome", "rejected");
    }

    @Test
    void rejectFailBehaviorThrowsAndStillRecordsRejectedOutcome() throws Exception {
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        String url = startServer(500, "{\"error\":\"boom\"}");
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), observationRegistry);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            hookExecutor.executeHookWithResponse("my-hook", "Invoice", "PREENRICH", url, "{}", null, "reject")
        ).isInstanceOf(com.yahoo.elide.core.exceptions.BadRequestException.class);

        TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasSingleObservationThat()
            .hasNameEqualTo("aperture.hook")
            .hasLowCardinalityKeyValue("outcome", "rejected");
    }

    @Test
    void executeHookWithResponseParentsOntoObservationStashedOnInboundRequest() throws Exception {
        // Simulates the fix for detached-root aperture.hook spans: Elide can invoke the hook off
        // the request's observation scope, so observationRegistry.getCurrentObservation() alone
        // would return null here. AuthFilter instead stashes the parent as a request attribute,
        // which HookExecutor must prefer.
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        String url = startServer(200, "{\"ok\":true}");
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), observationRegistry);

        Observation parent = Observation.start("http.server.requests", observationRegistry);
        parent.stop(); // stopped parents are still valid link targets for POSTCOMMIT-style hooks

        MockHttpServletRequest inboundRequest = new MockHttpServletRequest();
        inboundRequest.setAttribute(ApertureRequestAttributes.PARENT_OBSERVATION, parent);

        hookExecutor.executeHookWithResponse("my-hook", "Invoice", "PREENRICH", url, "{}", inboundRequest, "warn");

        TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasObservationWithNameEqualTo("aperture.hook")
            .that()
            .hasParentObservationEqualTo(parent);
    }

    @Test
    void executeHookParentsOntoObservationStashedOnInboundRequest() throws Exception {
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        String url = startServer(200, "{\"ok\":true}");
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), observationRegistry);

        Observation parent = Observation.start("http.server.requests", observationRegistry);
        parent.stop();

        MockHttpServletRequest inboundRequest = new MockHttpServletRequest();
        inboundRequest.setAttribute(ApertureRequestAttributes.PARENT_OBSERVATION, parent);

        hookExecutor.executeHook("my-hook", "Invoice", "POSTCOMMIT", url, "{}", inboundRequest, "warn", 0, false).join();

        TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasObservationWithNameEqualTo("aperture.hook")
            .that()
            .hasParentObservationEqualTo(parent);
    }

    private String startServer(int status, String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    // ---------- executeHook retry counts (plan 032) ----------
    //
    // First time retry *counts* are asserted anywhere, not just observation tagging — the retry
    // mechanism (handleRetry/doExecuteAsync) predates plan 032 but was unreachable in production
    // (CodeGenerator always passed retries=0), so nothing previously exercised attempt < retries.

    private final AtomicInteger requestCount = new AtomicInteger();

    private String startServerFailingFirstNThenSucceeding(int failCount, int failStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            int attempt = requestCount.incrementAndGet();
            boolean fail = attempt <= failCount;
            byte[] bytes = (fail ? "{\"error\":\"transient\"}" : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? failStatus : 200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    private String startAlwaysFailingCountingServer(int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    @Test
    void executeHookRetriesUntilSuccessWithinBudget() throws Exception {
        // Fails the first 2 calls, succeeds on the 3rd. retries=3 gives up to 4 total attempts —
        // it must stop retrying as soon as it succeeds, not exhaust the whole retry budget.
        String url = startServerFailingFirstNThenSucceeding(2, 503);
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(2), TestObservationRegistry.create());

        hookExecutor.executeHook("my-hook", "Invoice", "PRECOMMIT", url, "{}", null, "reject", 3, false);

        assertThat(requestCount.get()).as("initial attempt + 2 retries, then success — not the full retries=3 budget")
            .isEqualTo(3);
    }

    @Test
    void executeHookExhaustsRetriesThenRejectThrowsAfterExactlyRetryPlusOneAttempts() throws Exception {
        String url = startAlwaysFailingCountingServer(503);
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(2), TestObservationRegistry.create());

        assertThatThrownBy(() ->
            hookExecutor.executeHook("my-hook", "Invoice", "PRECOMMIT", url, "{}", null, "reject", 2, false)
        ).isInstanceOf(com.yahoo.elide.core.exceptions.BadRequestException.class);

        assertThat(requestCount.get()).as("initial attempt + 2 retries = 3 total, exhausted then rejected")
            .isEqualTo(3);
    }

    @Test
    void executeHookExhaustsRetriesThenWarnLogsAndSucceeds() throws Exception {
        String url = startAlwaysFailingCountingServer(503);
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(2), TestObservationRegistry.create());

        assertThatCode(() ->
            hookExecutor.executeHook("my-hook", "Invoice", "PRECOMMIT", url, "{}", null, "warn", 2, false)
        ).doesNotThrowAnyException();

        assertThat(requestCount.get()).as("initial attempt + 2 retries = 3 total, exhausted then warn-succeeded")
            .isEqualTo(3);
    }

    @Test
    void executeHookExhaustsRetriesThenPassthroughSucceedsSilently() throws Exception {
        String url = startAlwaysFailingCountingServer(503);
        hookExecutor = new HookExecutor("test-secret", null, Duration.ofSeconds(2), Duration.ofSeconds(2),
            Duration.ofSeconds(2), TestObservationRegistry.create());

        assertThatCode(() ->
            hookExecutor.executeHook("my-hook", "Invoice", "PRECOMMIT", url, "{}", null, "passthrough", 2, false)
        ).doesNotThrowAnyException();

        assertThat(requestCount.get()).as("initial attempt + 2 retries = 3 total, exhausted then passthrough-succeeded")
            .isEqualTo(3);
    }
}
