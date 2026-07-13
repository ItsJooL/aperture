package com.itsjool.aperture.runtime.filter;

import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.PrincipalKind;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitRule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {
    @Test
    void usesDocumentedDefaultsWhenNoCustomPropertiesAreSet() throws Exception {
        RecordingRateLimitProvider provider = new RecordingRateLimitProvider();
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};

        filter.doFilter(request, response, chain((req, res) -> chainCalled[0] = true));

        assertThat(chainCalled[0]).isTrue();
        assertThat(provider.calls).containsExactly(
            call("ip", "198.51.100.9", 100, 100, 60),
            call("user", "user-123", 50, 50, 60),
            call("tenant", "tenant-abc", 500, 500, 60)
        );
    }

    @Test
    void usesCustomPropertiesWhenConfigured() throws Exception {
        RecordingRateLimitProvider provider = new RecordingRateLimitProvider();
        ApertureRateLimitProperties properties = rateLimitProperties(true);
        properties.getIp().setCapacity(7);
        properties.getIp().setRefillTokens(3);
        properties.getIp().setWindowSeconds(11);
        properties.getUser().setCapacity(8);
        properties.getUser().setRefillTokens(4);
        properties.getUser().setWindowSeconds(12);
        properties.getTenant().setCapacity(9);
        properties.getTenant().setRefillTokens(5);
        properties.getTenant().setWindowSeconds(13);

        RateLimitFilter filter = new RateLimitFilter(provider, properties, true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain((req, res) -> {}));

        assertThat(provider.calls).containsExactly(
            call("ip", "198.51.100.9", 7, 3, 11),
            call("user", "user-123", 8, 4, 12),
            call("tenant", "tenant-abc", 9, 5, 13)
        );
    }

    @Test
    void failsOpenWhenProviderThrows() throws Exception {
        ThrowingRateLimitProvider provider = new ThrowingRateLimitProvider();
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};

        filter.doFilter(request, response, chain((req, res) -> chainCalled[0] = true));

        assertThat(chainCalled[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Regression test for the fail-open observability gap: a backend error must not just fail open
     * silently, it must increment {@code aperture.ratelimit.failopen} (tagged by {@code type}) so a
     * sustained Valkey outage that disables rate limiting is alertable rather than invisible. The
     * request carries a principal with a tenant, so all three buckets (ip/user/tenant) are evaluated
     * and each should record its own fail-open increment.
     */
    @Test
    void failOpenIncrementsFailOpenCounterTaggedByType() throws Exception {
        ThrowingRateLimitProvider provider = new ThrowingRateLimitProvider();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true, meterRegistryProvider(meterRegistry));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain((req, res) -> {}));

        assertThat(meterRegistry.get("aperture.ratelimit.failopen").tags("type", "ip").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("aperture.ratelimit.failopen").tags("type", "user").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("aperture.ratelimit.failopen").tags("type", "tenant").counter().count()).isEqualTo(1.0);
    }

    /**
     * Without a {@link MeterRegistry} available (e.g. a slice test context without Spring Boot's
     * metrics autoconfiguration), the fail-open path must still degrade gracefully rather than NPE.
     */
    @Test
    void failsOpenWithoutThrowingWhenNoMeterRegistryIsAvailable() throws Exception {
        ThrowingRateLimitProvider provider = new ThrowingRateLimitProvider();
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain((req, res) -> {}));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void disabledPropertiesSkipRateLimitingEntirely() throws Exception {
        RecordingRateLimitProvider provider = new RecordingRateLimitProvider();
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(false), true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};

        filter.doFilter(request, response, chain((req, res) -> chainCalled[0] = true));

        assertThat(provider.calls).isEmpty();
        assertThat(chainCalled[0]).isTrue();
    }

    /**
     * Regression test for the success-path header bug: {@code writeRateLimitHeaders} used to always
     * report the ip bucket, even when the (tighter, tenancy-scoped) user or tenant bucket was closer
     * to exhaustion. A client relying on {@code X-RateLimit-Remaining} to back off proactively would
     * be misled into thinking it had far more headroom than it actually did.
     */
    @Test
    void successHeadersReflectTightestBucketWhenUserIsTighterThanIp() throws Exception {
        RateLimitProvider provider = new RemainingByTypeRateLimitProvider(Map.of("ip", 90, "user", 5, "tenant", 200));
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain((req, res) -> {}));

        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("5");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("50");
    }

    /**
     * Same bug, opposite bucket: when the ip bucket genuinely is the tightest, headers must still
     * reflect it (this is the pre-existing behavior — guards against a fix that always prefers
     * user/tenant instead of comparing all three).
     */
    @Test
    void successHeadersReflectIpBucketWhenIpIsTighterThanUserAndTenant() throws Exception {
        RateLimitProvider provider = new RemainingByTypeRateLimitProvider(Map.of("ip", 2, "user", 40, "tenant", 490));
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true, meterRegistryProvider(null));

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain((req, res) -> {}));

        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("100");
    }

    /** Wraps a fixed {@link MeterRegistry} (or {@code null}) as the {@link ObjectProvider}
     * RateLimitFilter's constructor expects (see RateLimitFilter's javadoc comment on that
     * constructor for why) — mirrors AuthFilterTest's ObservationRegistry equivalent. */
    private static ObjectProvider<MeterRegistry> meterRegistryProvider(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject() {
                return registry;
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }
        };
    }

    private static ApertureRateLimitProperties rateLimitProperties(boolean enabled) {
        ApertureRateLimitProperties properties = new ApertureRateLimitProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static MockHttpServletRequest requestWithPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRemoteAddr("198.51.100.9");
        request.setAttribute("aperturePrincipal", new AperturePrincipal(
            "user-123",
            "tenant-abc",
            Set.of("Admin"),
            PrincipalKind.USER,
            Map.of(),
            Map.of()
        ));
        return request;
    }

    private static FilterChain chain(ThrowingFilterChain delegate) {
        return (request, response) -> delegate.doFilter(request, response);
    }

    private static Call call(String type, String value, int capacity, int refillTokens, int windowSeconds) {
        return new Call(type, value, capacity, refillTokens, windowSeconds);
    }

    private interface ThrowingFilterChain {
        void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) throws IOException, ServletException;
    }

    private static final class RecordingRateLimitProvider implements RateLimitProvider {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule) {
            calls.add(new Call(key.type(), key.value(), rule.capacity(), rule.burst(), rule.windowSeconds()));
            return new RateLimitDecision(true, rule.capacity() - 1, 0);
        }
    }

    private record Call(String type, String value, int capacity, int refillTokens, int windowSeconds) {}

    private static final class ThrowingRateLimitProvider implements RateLimitProvider {
        @Override
        public RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule) {
            throw new IllegalStateException("Valkey connection failed");
        }
    }

    /** Always allows, but returns a caller-specified remaining count per bucket type, so tests can
     * control which of ip/user/tenant is closest to exhaustion. */
    private static final class RemainingByTypeRateLimitProvider implements RateLimitProvider {
        private final Map<String, Integer> remainingByType;

        RemainingByTypeRateLimitProvider(Map<String, Integer> remainingByType) {
            this.remainingByType = remainingByType;
        }

        @Override
        public RateLimitDecision evaluate(RateLimitKey key, RateLimitRule rule) {
            return new RateLimitDecision(true, remainingByType.get(key.type()), 0);
        }
    }
}
