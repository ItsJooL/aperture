package com.itsjool.aperture.runtime.filter;

import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitRule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProvider rateLimitProvider;
    private final ApertureRateLimitProperties rateLimitProperties;
    private final boolean tenancyEnabled;
    private final MeterRegistry meterRegistry;

    // ObjectProvider (not a bare MeterRegistry) so this filter still constructs cleanly in
    // application-context slices that don't include Spring Boot's metrics autoconfiguration
    // (e.g. @WebMvcTest slice tests) — it degrades to no metrics instead of failing bean
    // creation with an UnsatisfiedDependencyException. Mirrors AuthFilter's ObservationRegistry wiring.
    public RateLimitFilter(RateLimitProvider rateLimitProvider, ApertureRateLimitProperties rateLimitProperties, @Value("${aperture.tenancy.enabled:true}") boolean tenancyEnabled, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.rateLimitProvider = rateLimitProvider;
        this.rateLimitProperties = rateLimitProperties;
        this.tenancyEnabled = tenancyEnabled;
        this.meterRegistry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimitProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();
        RateLimitRule ipRule = new RateLimitRule(
            rateLimitProperties.getIp().getCapacity(),
            rateLimitProperties.getIp().getRefillTokens(),
            rateLimitProperties.getIp().getWindowSeconds()
        );
        RateLimitDecision ipDecision = evaluateOrFailOpen(new RateLimitKey("ip", ip), ipRule);

        if (!ipDecision.allowed()) {
            writeRateLimitHeaders(response, ipDecision, ipRule);
            response.sendError(429, "Too Many Requests");
            return;
        }

        // Track whichever bucket (ip/user/tenant) is closest to exhaustion as each is evaluated, so
        // the advisory headers on the success path warn about the bucket a client is actually at risk
        // of tripping next — the user and tenant limits are tighter than the ip limit by default
        // (see docs/guide/security-audit.md), so always reporting the ip bucket here would mask that.
        RateLimitDecision tightestDecision = ipDecision;
        RateLimitRule tightestRule = ipRule;

        if (tenancyEnabled) {
            AperturePrincipal principal = (AperturePrincipal) request.getAttribute("aperturePrincipal");
            if (principal != null) {
                RateLimitRule userRule = new RateLimitRule(
                    rateLimitProperties.getUser().getCapacity(),
                    rateLimitProperties.getUser().getRefillTokens(),
                    rateLimitProperties.getUser().getWindowSeconds()
                );
                RateLimitDecision userDecision = evaluateOrFailOpen(new RateLimitKey("user", principal.userId()), userRule);
                if (!userDecision.allowed()) {
                    writeRateLimitHeaders(response, userDecision, userRule);
                    response.sendError(429, "Too Many Requests");
                    return;
                }
                if (userDecision.remaining() < tightestDecision.remaining()) {
                    tightestDecision = userDecision;
                    tightestRule = userRule;
                }

                if (principal.tenantId() != null) {
                    RateLimitRule tenantRule = new RateLimitRule(
                        rateLimitProperties.getTenant().getCapacity(),
                        rateLimitProperties.getTenant().getRefillTokens(),
                        rateLimitProperties.getTenant().getWindowSeconds()
                    );
                    RateLimitDecision tenantDecision = evaluateOrFailOpen(new RateLimitKey("tenant", principal.tenantId()), tenantRule);
                    if (!tenantDecision.allowed()) {
                        writeRateLimitHeaders(response, tenantDecision, tenantRule);
                        response.sendError(429, "Too Many Requests");
                        return;
                    }
                    if (tenantDecision.remaining() < tightestDecision.remaining()) {
                        tightestDecision = tenantDecision;
                        tightestRule = tenantRule;
                    }
                }
            }
        }

        writeRateLimitHeaders(response, tightestDecision, tightestRule);
        filterChain.doFilter(request, response);
    }

    /**
     * Deliberate fail-open policy: the configured {@link RateLimitProvider} (e.g. the Valkey-backed
     * implementation) may throw on a transient backend fault such as a dropped connection. This filter
     * runs at {@code HIGHEST_PRECEDENCE + 20}, ahead of every request in the API, so an unhandled
     * exception here would turn a backend hiccup into a 500 for the entire application. Instead, we log
     * a single WARN per failure and allow the request to proceed as if the limit were not exceeded. Real
     * limit breaches (i.e. the provider returning normally with {@code allowed=false}) are unaffected and
     * still result in a 429.
     */
    private RateLimitDecision evaluateOrFailOpen(RateLimitKey key, RateLimitRule rule) {
        try {
            return rateLimitProvider.evaluate(key, rule);
        } catch (Exception e) {
            log.warn("rate-limit backend unavailable, failing open: {}", e.getMessage());
            if (meterRegistry != null) {
                meterRegistry.counter("aperture.ratelimit.failopen", "type", key.type()).increment();
            }
            return new RateLimitDecision(true, rule.capacity(), 0);
        }
    }

    private void writeRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision, RateLimitRule rule) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(rule.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(java.time.Instant.now().getEpochSecond() + decision.retryAfterSeconds()));
    }
}
