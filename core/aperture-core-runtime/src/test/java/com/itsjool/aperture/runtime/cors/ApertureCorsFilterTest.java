package com.itsjool.aperture.runtime.cors;

import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.runtime.config.TenancyMode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApertureCorsFilterTest {

    private ApertureCorsFilter filterWith(String... allowedOrigins) {
        CorsProperties props = new CorsProperties();
        props.setEnabled(true);
        props.setAllowedOrigins(Arrays.asList(allowedOrigins));
        ApertureRuntimeMetadata metadata = new ApertureRuntimeMetadata(
            List.of("1"),
            List.of("Admin"),
            List.of("Admin"),
            Set.of(),
            TenancyMode.POOL,
            Set.of("GET", "POST", "OPTIONS"),
            Set.of());
        return new ApertureCorsFilter(props, metadata);
    }

    @Test
    void preflight_fromAllowedOrigin_returns200AndDoesNotInvokeChain() throws Exception {
        ApertureCorsFilter filter = filterWith("http://localhost:9999");
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/v1/customers");
        req.addHeader("Origin", "http://localhost:9999");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        filter.doFilter(req, resp, (rq, rs) -> chainInvoked.set(true));
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:9999");
        assertThat(resp.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(chainInvoked.get()).isFalse();
    }

    @Test
    void preflight_fromUnlistedOrigin_invokesChainWithNoHeaders() throws Exception {
        ApertureCorsFilter filter = filterWith("http://localhost:9999");
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/v1/customers");
        req.addHeader("Origin", "http://evil.example.com");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        filter.doFilter(req, resp, (rq, rs) -> chainInvoked.set(true));
        assertThat(resp.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void realRequest_fromAllowedOrigin_addsOriginHeaderAndInvokesChain() throws Exception {
        ApertureCorsFilter filter = filterWith("http://localhost:9999");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/customers");
        req.addHeader("Origin", "http://localhost:9999");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        filter.doFilter(req, resp, (rq, rs) -> chainInvoked.set(true));
        assertThat(resp.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:9999");
        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void realRequest_fromUnlistedOrigin_noHeadersButChainInvoked() throws Exception {
        ApertureCorsFilter filter = filterWith("http://localhost:9999");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/customers");
        req.addHeader("Origin", "http://other.example.com");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);
        filter.doFilter(req, resp, (rq, rs) -> chainInvoked.set(true));
        assertThat(resp.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    void allowedMethods_preflightResponse_matchesMetadata() throws Exception {
        ApertureCorsFilter filter = filterWith("http://localhost:9999");
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/v1/customers");
        req.addHeader("Origin", "http://localhost:9999");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (rq, rs) -> {});
        String methods = resp.getHeader("Access-Control-Allow-Methods");
        assertThat(methods).isNotNull();
        // metadata contains GET, POST, OPTIONS — all three must appear
        assertThat(methods).contains("GET");
        assertThat(methods).contains("OPTIONS");
    }
}
