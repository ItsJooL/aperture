package com.itsjool.aperture.runtime.hook;

import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.SenderContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.micrometer.observation.ObservationRegistry;

public class HookExecutor {
    private static final Logger log = LoggerFactory.getLogger(HookExecutor.class);
    private final HttpClient httpClient;
    private final String hookSecret;
    private final String hookBaseUrl;
    private final ScheduledExecutorService asyncExecutor;
    private final Duration commitTimeout;
    private final Duration asyncTimeout;
    private final Duration connectTimeout;
    private final ObservationRegistry observationRegistry;

    public HookExecutor(String hookSecret, String hookBaseUrl, Duration commitTimeout, Duration asyncTimeout, Duration connectTimeout, ObservationRegistry observationRegistry) {
        this.hookSecret = hookSecret;
        this.hookBaseUrl = normalizeBaseUrl(hookBaseUrl);
        this.commitTimeout = commitTimeout;
        this.asyncTimeout = asyncTimeout;
        this.connectTimeout = connectTimeout;
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        this.asyncExecutor = Executors.newScheduledThreadPool(4);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public CompletableFuture<Boolean> executeHook(String hookName, String entity, String phase, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior, int retries, boolean isAsync) {
        boolean isCommitPhase = "PRECOMMIT".equalsIgnoreCase(phase) || "POSTCOMMIT".equalsIgnoreCase(phase);
        Duration timeout = isCommitPhase ? commitTimeout : asyncTimeout;

        // Prefer the parent observation stashed on the inbound request by AuthFilter: Elide invokes
        // generated lifecycle hooks off the scoped request path (possibly a different thread), where
        // observationRegistry.getCurrentObservation() returns null. The request attribute survives
        // regardless of which thread calls in, so it is reliable where the ambient lookup is not.
        // Fall back to the ambient lookup for callers that don't go through AuthFilter (e.g. tests).
        Observation parentObservation = resolveParentObservation(inboundRequest);

        CompletableFuture<Boolean> future = doExecuteAsync(hookName, entity, hookUrl, payload, inboundRequest, failBehavior, retries, timeout, 0, phase, isAsync, parentObservation);

        if (isAsync) {
            // Fire-and-forget for commit phases to avoid blocking DB transactions
            future.exceptionally(e -> {
                log.warn("Async hook execution failed for phase {}: {}", phase, e.getMessage());
                return false;
            });
            return CompletableFuture.completedFuture(true);
        }

        // For sync hooks, join internally and rethrow unwrapped exceptions
        try {
            future.join();
            return CompletableFuture.completedFuture(true);
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    // Synchronous enrichment call — returns the response body for mutate hooks to apply overrides.
    public String executeHookWithResponse(String hookName, String entity, String phase, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior) {
        final String resolvedUrl = rewriteUrl(hookUrl);
        log.debug("{} enrichment hook: {}", phase, resolvedUrl);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(resolvedUrl))
            .timeout(asyncTimeout)
            .header("Content-Type", "application/json")
            .header("X-Hook-Secret", hookSecret)
            .POST(HttpRequest.BodyPublishers.ofString(payload));

        SenderContext<HttpRequest.Builder> senderContext = new SenderContext<>((carrierBuilder, key, value) -> carrierBuilder.header(key, value));
        senderContext.setCarrier(requestBuilder);
        senderContext.setRemoteServiceName("aperture.hook");

        Observation observation = Observation.createNotStarted("aperture.hook", () -> senderContext, observationRegistry)
            .lowCardinalityKeyValue("hook.name", hookName)
            .lowCardinalityKeyValue("hook.phase", phase)
            .lowCardinalityKeyValue("hook.async", "false")
            .lowCardinalityKeyValue("entity", entity);

        // See resolveParentObservation() javadoc: Elide can invoke this hook off the request's
        // observation scope, so the ambient getCurrentObservation() lookup alone is not reliable.
        Observation parentObservation = resolveParentObservation(inboundRequest);
        if (parentObservation != null) {
            observation.parentObservation(parentObservation);
        }

        // Trace-context propagation is handled by the SenderContext above: observation.start()
        // injects this child span's traceparent into requestBuilder. Do not also copy the inbound
        // request's traceparent — HttpRequest.Builder.header() appends, producing two conflicting
        // traceparent values and a broken (wrong-parent) link at the hook service.
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                observation.lowCardinalityKeyValue("outcome", "ok");
                return response.body();
            }
            observation.lowCardinalityKeyValue("outcome", "rejected");
            if ("reject".equalsIgnoreCase(failBehavior)) {
                throw new com.yahoo.elide.core.exceptions.BadRequestException("Enrichment hook rejected: HTTP " + response.statusCode());
            }
            return null;
        } catch (com.yahoo.elide.core.exceptions.BadRequestException e) {
            observation.error(e);
            throw e;
        } catch (Exception e) {
            observation.error(e);
            observation.lowCardinalityKeyValue("outcome", "error");
            if ("reject".equalsIgnoreCase(failBehavior)) {
                throw new com.yahoo.elide.core.exceptions.BadRequestException("Enrichment hook failed: " + e.getMessage());
            }
            log.warn("Enrichment hook call failed (failBehavior={}): {}", failBehavior, e.getMessage());
            return null;
        } finally {
            observation.stop();
        }
    }

    /**
     * Resolves the observation that the {@code aperture.hook} span should parent onto.
     *
     * <p>Elide invokes generated lifecycle hooks off the scoped request path (possibly on a
     * different thread than the one that opened the {@code http.server.requests} observation
     * scope), so {@link ObservationRegistry#getCurrentObservation()} reliably returns {@code null}
     * at the point hooks fire. {@link com.itsjool.aperture.runtime.filter.AuthFilter} — which runs
     * inside Spring's {@code ServerHttpObservationFilter} scope — stashes that observation as a
     * request attribute precisely so it survives to this point regardless of thread. Fall back to
     * the ambient lookup for callers that bypass AuthFilter (e.g. unit tests), where it still works.
     */
    private Observation resolveParentObservation(HttpServletRequest inboundRequest) {
        Observation parent = inboundRequest != null
            ? (Observation) inboundRequest.getAttribute(ApertureRequestAttributes.PARENT_OBSERVATION)
            : null;
        if (parent == null) {
            parent = observationRegistry.getCurrentObservation();
        }
        return parent;
    }

    private String rewriteUrl(String hookUrl) {
        if (hookBaseUrl == null || hookBaseUrl.isBlank()) return hookUrl;
        String rewritten = hookUrl.replaceFirst("^https?://[^/]+", hookBaseUrl);
        if (rewritten.equals(hookUrl)) {
            log.warn("Hook URL '{}' does not start with http:// or https://; base-url override was not applied", hookUrl);
        }
        return rewritten;
    }

    private CompletableFuture<Boolean> doExecuteAsync(String hookName, String entity, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior, int retries, Duration timeout, int attempt, String phase, boolean isAsync, Observation parentObservation) {
        final String resolvedUrl = rewriteUrl(hookUrl);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(resolvedUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("X-Hook-Secret", hookSecret)
            .POST(HttpRequest.BodyPublishers.ofString(payload));

        SenderContext<HttpRequest.Builder> senderContext = new SenderContext<>((carrierBuilder, key, value) -> carrierBuilder.header(key, value));
        senderContext.setCarrier(requestBuilder);
        senderContext.setRemoteServiceName("aperture.hook");

        Observation observation = Observation.createNotStarted("aperture.hook", () -> senderContext, observationRegistry)
            .lowCardinalityKeyValue("hook.name", hookName)
            .lowCardinalityKeyValue("hook.phase", phase)
            .lowCardinalityKeyValue("hook.async", String.valueOf(isAsync))
            .lowCardinalityKeyValue("entity", entity);
        
        if (attempt > 0) {
            observation.lowCardinalityKeyValue("retry.attempt", String.valueOf(attempt));
        }

        // Explicit parent keeps retries (which run on asyncExecutor threads, off the request scope)
        // linked to the originating trace. On the first attempt this is the same ambient parent
        // start() would pick up anyway, so it is a no-op there.
        if (parentObservation != null) {
            observation.parentObservation(parentObservation);
        }

        // Trace-context propagation is handled by the SenderContext above (see executeHookWithResponse).
        observation.start();
        // handle(...) + thenCompose(identity()) — not thenCompose(...).exceptionallyCompose(...) — is
        // deliberate: handle() sees this attempt's outcome (success, non-2xx, or thrown exception)
        // exactly once and calls handleRetry() exactly once. A prior version chained
        // .thenCompose(response -> ... handleRetry(...) ...).exceptionallyCompose(e -> handleRetry(...))
        // instead; exceptionallyCompose there does not only catch a failed sendAsync() — it also
        // catches the *nested* retry chain's eventual failure (handleRetry's own returned future,
        // once retries are exhausted with failBehavior=reject), because that failure is exactly what
        // thenCompose's result completes with. That silently called handleRetry a second time per
        // attempt with the same (already-consumed) attempt number, which re-passed the `attempt <
        // retries` check and scheduled a genuinely duplicate retry — cascading through every nested
        // level of the recursion. Confirmed empirically while adding plan 032's first-ever retry-count
        // tests (HookExecutorTest): retries=2 with an always-failing server and onFailure=reject
        // produced 7 actual HTTP attempts instead of the intended 3. guard/validate can only use
        // onFailure=reject, so this bug would have made every guard/validate retries configuration
        // silently multiply real attempts (and latency) well beyond the configured cap. Not previously
        // caught because retries was hardcoded to 0 in production until this plan, and no prior test
        // drove a reject-onFailure hook through actual retry exhaustion.
        return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            .whenComplete((res, ex) -> {
                if (ex != null) {
                    observation.error(ex);
                    observation.lowCardinalityKeyValue("outcome", "error");
                } else if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    observation.lowCardinalityKeyValue("outcome", "ok");
                } else {
                    observation.lowCardinalityKeyValue("outcome", "rejected");
                }
                observation.stop();
            })
            .handle((response, ex) -> {
                if (ex != null) {
                    return handleRetry(ex, hookName, entity, hookUrl, payload, inboundRequest, failBehavior, retries, timeout, attempt, phase, isAsync, parentObservation);
                }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return CompletableFuture.completedFuture(true);
                }
                return handleRetry(new RuntimeException("Hook returned HTTP " + response.statusCode()), hookName, entity, hookUrl, payload, inboundRequest, failBehavior, retries, timeout, attempt, phase, isAsync, parentObservation);
            })
            .thenCompose(java.util.function.Function.identity());
    }

    private CompletableFuture<Boolean> handleRetry(Throwable error, String hookName, String entity, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior, int retries, Duration timeout, int attempt, String phase, boolean isAsync, Observation parentObservation) {
        if (attempt < retries) {
            long delayMs = (long) Math.pow(2, attempt) * 500; // Exponential backoff
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            asyncExecutor.schedule(() -> {
                doExecuteAsync(hookName, entity, hookUrl, payload, inboundRequest, failBehavior, retries, timeout, attempt + 1, phase, isAsync, parentObservation)
                    .whenComplete((res, ex) -> {
                        if (ex != null) future.completeExceptionally(ex);
                        else future.complete(res);
                    });
            }, delayMs, TimeUnit.MILLISECONDS);
            return future;
        }

        if ("reject".equalsIgnoreCase(failBehavior)) {
            return CompletableFuture.failedFuture(new com.yahoo.elide.core.exceptions.BadRequestException("Hook rejected the request"));
        } else if ("warn".equalsIgnoreCase(failBehavior)) {
            log.warn("Hook execution failed, continuing due to warn failBehavior: {}", error.getMessage());
            return CompletableFuture.completedFuture(true);
        } else if ("passthrough".equalsIgnoreCase(failBehavior)) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown failBehavior: " + failBehavior));
    }
}
