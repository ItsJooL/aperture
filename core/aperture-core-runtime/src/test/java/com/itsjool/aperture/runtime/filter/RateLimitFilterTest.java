package com.itsjool.aperture.runtime.filter;

import com.itsjool.aperture.runtime.config.ApertureRateLimitProperties;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.PrincipalKind;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
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
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(true), true);

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

        RateLimitFilter filter = new RateLimitFilter(provider, properties, true);

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
    void disabledPropertiesSkipRateLimitingEntirely() throws Exception {
        RecordingRateLimitProvider provider = new RecordingRateLimitProvider();
        RateLimitFilter filter = new RateLimitFilter(provider, rateLimitProperties(false), true);

        MockHttpServletRequest request = requestWithPrincipal();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};

        filter.doFilter(request, response, chain((req, res) -> chainCalled[0] = true));

        assertThat(provider.calls).isEmpty();
        assertThat(chainCalled[0]).isTrue();
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
}
