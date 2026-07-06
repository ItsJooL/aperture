package com.itsjool.aperture.runtime.filter;

import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.runtime.config.TenancyMode;
import com.itsjool.aperture.runtime.scope.ScopeContextHolder;
import com.itsjool.aperture.runtime.tenant.TenantContextHolder;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.CredentialValidator;
import com.itsjool.aperture.spi.PrincipalMapper;
import com.itsjool.aperture.spi.ValidationResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthFilterTest {

    private AuthFilter authFilter;

    private static final ApertureRuntimeMetadata POOL_METADATA = new ApertureRuntimeMetadata(
            List.of("1"), List.of("TenantAdmin"), List.of("TenantAdmin"), null, TenancyMode.POOL, null, null);
    private static final ApertureRuntimeMetadata SCOPED_METADATA = new ApertureRuntimeMetadata(
            List.of("1"), List.of("TenantAdmin"), List.of("TenantAdmin"), null, TenancyMode.POOL, null, null,
            java.util.Map.of(), Set.of("customers"));

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void testAuthenticatedSuccess() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("user-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, java.util.Map.of(), Collections.emptyMap());
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertEquals("tenant-A", TenantContextHolder.getTenantId());
            assertNotNull(req.getAttribute("aperturePrincipal"));
            assertNotNull(((HttpServletRequest) req).getUserPrincipal());
        };

        authFilter.doFilter(request, response, chain);

        assertTrue(chainCalled[0]);
        assertNull(TenantContextHolder.getTenantId()); // cleared
    }

    @Test
    void testSuperAdminWithExplicitContext() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("admin-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("admin-1", null, Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), Collections.emptyMap(), java.util.Set.of(), true, false);
        AuthFilter filter = new AuthFilter(validator, mapper, POOL_METADATA);

        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        request.addHeader("X-Aperture-Tenant-Context", "explicit-tenant-B");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertEquals("explicit-tenant-B", TenantContextHolder.getTenantId());
        };

        filter.doFilter(request, response, chain);
        assertTrue(chainCalled[0]);
    }

    @Test
    void testSuperAdminWithoutExplicitContextOnApiIsRejected() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("admin-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("admin-1", null, Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), Collections.emptyMap(), java.util.Set.of(), true, false);
        AuthFilter filter = new AuthFilter(validator, mapper, POOL_METADATA);

        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);
        assertFalse(chainCalled[0]);
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
    }

    @Test
    void superAdminWithoutExplicitContextOnTenantScopedGeneratedApiIsRejected() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("admin-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("admin-1", null, Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), Collections.emptyMap(), java.util.Set.of(), true, false);
        AuthFilter filter = new AuthFilter(validator, mapper, SCOPED_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/customers");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        filter.doFilter(request, response, (req, res) -> chainCalled[0] = true);

        assertFalse(chainCalled[0]);
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
    }

    @Test
    void superAdminWithoutExplicitContextOnGlobalGeneratedApiIsAllowed() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("admin-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("admin-1", null, Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), Collections.emptyMap(), java.util.Set.of(), true, false);
        AuthFilter filter = new AuthFilter(validator, mapper, SCOPED_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/system-settings");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        filter.doFilter(request, response, (req, res) -> {
            chainCalled[0] = true;
            assertNull(TenantContextHolder.getTenantId());
        });

        assertTrue(chainCalled[0]);
    }

    @Test
    void testNonSuperAdminWithExplicitContextIsRejected() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("user-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), Collections.emptyMap());
        AuthFilter filter = new AuthFilter(validator, mapper, POOL_METADATA);

        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        request.addHeader("X-Aperture-Tenant-Context", "explicit-tenant-B");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);
        assertFalse(chainCalled[0]);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    void camelCaseScopeHeaderLandsUnderLowercaseKey() throws Exception {
        // The generated <Entity>V<n>ScopeFilter looks up ScopeContextHolder using the
        // lowercased manifest field name (e.g. "parentproject" for a scopedBy: parentProject
        // field), so the header-derived key must be lowercased the same way or a camelCase
        // scopedBy field can never match.
        CredentialValidator validator = req -> ValidationResult.success("user-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, java.util.Map.of(), Collections.emptyMap());
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        request.addHeader("X-Aperture-Scope-ParentProject", "project-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertEquals("project-123", ScopeContextHolder.get("parentproject"));
            assertNull(ScopeContextHolder.get("ParentProject"));
            assertNull(ScopeContextHolder.get("parentProject"));
        };

        authFilter.doFilter(request, response, chain);

        assertTrue(chainCalled[0]);
        assertNull(ScopeContextHolder.get("parentproject")); // cleared after request
    }

    @Test
    void testDownstreamExceptionClearsContext() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("user-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, java.util.Map.of(), Collections.emptyMap());
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            assertEquals("tenant-A", TenantContextHolder.getTenantId());
            throw new ServletException("Downstream error");
        };

        assertThrows(ServletException.class, () -> authFilter.doFilter(request, response, chain));
        assertNull(TenantContextHolder.getTenantId()); // cleared
    }

    @Test
    void testInvalidCredential() throws Exception {
        CredentialValidator validator = req -> ValidationResult.failure("Invalid");
        PrincipalMapper mapper = res -> null;
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        authFilter.doFilter(request, response, chain);

        assertFalse(chainCalled[0]);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertNull(TenantContextHolder.getTenantId()); // not set
    }

    @Test
    void testPublicEndpoint() throws Exception {
        CredentialValidator validator = req -> null; // fallthrough
        PrincipalMapper mapper = res -> null;
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/auth/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertNull(TenantContextHolder.getTenantId());
        };

        authFilter.doFilter(request, response, chain);

        assertTrue(chainCalled[0]);
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void testSequentialRequestsOnReusedThread() throws Exception {
        CredentialValidator validator = req -> "pass".equals(req.getHeader("Auth")) ? ValidationResult.success("user-1") : null;
        PrincipalMapper mapper = res -> new AperturePrincipal("user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, java.util.Map.of(), Collections.emptyMap());
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        // First request: Authenticated
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        request1.setRequestURI("/api/resource");
        request1.addHeader("Auth", "pass");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request1));
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        FilterChain chain1 = (req, res) -> assertEquals("tenant-A", TenantContextHolder.getTenantId());

        authFilter.doFilter(request1, response1, chain1);
        assertNull(TenantContextHolder.getTenantId()); // cleared after req1

        // Second request: Fallthrough (e.g. unauthenticated public endpoint)
        TenantContextHolder.setTenantId("leaked-tenant-id");
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        request2.setRequestURI("/api/public");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request2));
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        FilterChain chain2 = (req, res) -> assertNull(TenantContextHolder.getTenantId()); // must not bleed over

        authFilter.doFilter(request2, response2, chain2);
        assertNull(TenantContextHolder.getTenantId()); // still cleared
    }

    @Test
    void testAsyncRequestClearsServletThreadContext() throws Exception {
        CredentialValidator validator = req -> ValidationResult.success("user-1");
        PrincipalMapper mapper = res -> new AperturePrincipal(
                "user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), java.util.Map.of());
        authFilter = new AuthFilter(validator, mapper, POOL_METADATA);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        request.setAsyncStarted(true);

        authFilter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> assertEquals("tenant-A", TenantContextHolder.getTenantId()));

        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void noneModeDoesNotSetTenantContext() throws Exception {
        ApertureRuntimeMetadata noneMetadata = new ApertureRuntimeMetadata(
                List.of("1"), List.of("TenantAdmin"), List.of("TenantAdmin"), null, TenancyMode.NONE, null, null);
        CredentialValidator validator = req -> ValidationResult.success("user-1");
        PrincipalMapper mapper = res -> new AperturePrincipal("user-1", "tenant-A", Collections.emptySet(), com.itsjool.aperture.spi.PrincipalKind.USER, java.util.Map.of(), Collections.emptyMap());
        authFilter = new AuthFilter(validator, mapper, noneMetadata);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertNull(TenantContextHolder.getTenantId());
        };

        authFilter.doFilter(request, response, chain);

        assertTrue(chainCalled[0]);
        assertNull(TenantContextHolder.getTenantId());
    }
}
