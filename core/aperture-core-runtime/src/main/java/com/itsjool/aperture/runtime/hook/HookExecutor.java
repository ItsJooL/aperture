package com.itsjool.aperture.runtime.hook;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HookExecutor {
    private static final Logger log = LoggerFactory.getLogger(HookExecutor.class);
    private final HttpClient httpClient;
    private final String hookSecret;
    private final String hookBaseUrl;
    private final ScheduledExecutorService asyncExecutor;
    private final Duration commitTimeout;
    private final Duration asyncTimeout;
    private final Duration connectTimeout;

    public HookExecutor(String hookSecret, String hookBaseUrl, Duration commitTimeout, Duration asyncTimeout, Duration connectTimeout) {
        this.hookSecret = hookSecret;
        this.hookBaseUrl = normalizeBaseUrl(hookBaseUrl);
        this.commitTimeout = commitTimeout;
        this.asyncTimeout = asyncTimeout;
        this.connectTimeout = connectTimeout;
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

    public CompletableFuture<Boolean> executeHook(String phase, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior, int retries, boolean isAsync) {
        boolean isCommitPhase = "PRECOMMIT".equalsIgnoreCase(phase) || "POSTCOMMIT".equalsIgnoreCase(phase);
        Duration timeout = isCommitPhase ? commitTimeout : asyncTimeout;

        CompletableFuture<Boolean> future = doExecuteAsync(hookUrl, payload, inboundRequest, failBehavior, retries, timeout, 0);

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

    // Synchronous enrichment call — returns the response body for PREENRICH hooks to apply overrides.
    public String executeHookWithResponse(String phase, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior) {
        final String resolvedUrl = rewriteUrl(hookUrl);
        log.debug("{} enrichment hook: {}", phase, resolvedUrl);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(resolvedUrl))
            .timeout(asyncTimeout)
            .header("Content-Type", "application/json")
            .header("X-Hook-Secret", hookSecret)
            .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (inboundRequest != null && inboundRequest.getHeader("traceparent") != null) {
            requestBuilder.header("traceparent", inboundRequest.getHeader("traceparent"));
        }
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            if ("reject".equalsIgnoreCase(failBehavior)) {
                throw new com.yahoo.elide.core.exceptions.BadRequestException("Enrichment hook rejected: HTTP " + response.statusCode());
            }
            return null;
        } catch (com.yahoo.elide.core.exceptions.BadRequestException e) {
            throw e;
        } catch (Exception e) {
            if ("reject".equalsIgnoreCase(failBehavior)) {
                throw new com.yahoo.elide.core.exceptions.BadRequestException("Enrichment hook failed: " + e.getMessage());
            }
            log.warn("Enrichment hook call failed (failBehavior={}): {}", failBehavior, e.getMessage());
            return null;
        }
    }

    private String rewriteUrl(String hookUrl) {
        if (hookBaseUrl == null || hookBaseUrl.isBlank()) return hookUrl;
        String rewritten = hookUrl.replaceFirst("^https?://[^/]+", hookBaseUrl);
        if (rewritten.equals(hookUrl)) {
            log.warn("Hook URL '{}' does not start with http:// or https://; base-url override was not applied", hookUrl);
        }
        return rewritten;
    }

    private CompletableFuture<Boolean> doExecuteAsync(String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior, int retries, Duration timeout, int attempt) {
        final String resolvedUrl = rewriteUrl(hookUrl);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(resolvedUrl))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("X-Hook-Secret", hookSecret)
            .POST(HttpRequest.BodyPublishers.ofString(payload));

        if (inboundRequest != null) {
            if (inboundRequest.getHeader("traceparent") != null) {
                requestBuilder.header("traceparent", inboundRequest.getHeader("traceparent"));
            }
        }

        return httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return CompletableFuture.completedFuture(true);
                }
                return handleRetry(new RuntimeException("Hook returned HTTP " + response.statusCode()), resolvedUrl, payload, inboundRequest, failBehavior, retries, timeout, attempt);
            })
            .exceptionallyCompose(e -> handleRetry(e, resolvedUrl, payload, inboundRequest, failBehavior, retries, timeout, attempt));
    }

    private CompletableFuture<Boolean> handleRetry(Throwable error, String hookUrl, String payload, HttpServletRequest inboundRequest, String failBehavior, int retries, Duration timeout, int attempt) {
        if (attempt < retries) {
            long delayMs = (long) Math.pow(2, attempt) * 500; // Exponential backoff
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            asyncExecutor.schedule(() -> {
                doExecuteAsync(hookUrl, payload, inboundRequest, failBehavior, retries, timeout, attempt + 1)
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
