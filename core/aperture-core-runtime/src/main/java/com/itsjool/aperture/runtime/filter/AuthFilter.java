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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

import java.io.IOException;

import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter implements org.springframework.context.ApplicationContextAware {
    private org.springframework.context.ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(org.springframework.context.ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final CredentialValidator credentialValidator;
    private final PrincipalMapper principalMapper;
    private final ApertureRuntimeMetadata metadata;
    private final ObservationRegistry observationRegistry;

    // ObjectProvider (not a bare ObservationRegistry) so this filter still constructs cleanly in
    // application-context slices that don't include Spring Boot's ObservationAutoConfiguration
    // (e.g. @WebMvcTest slice tests) — it degrades to ObservationRegistry.NOOP instead of failing
    // bean creation with an UnsatisfiedDependencyException.
    public AuthFilter(CredentialValidator credentialValidator, PrincipalMapper principalMapper, ApertureRuntimeMetadata metadata, ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.credentialValidator = credentialValidator;
        this.principalMapper = principalMapper;
        this.metadata = metadata;
        this.observationRegistry = observationRegistryProvider != null
            ? observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP)
            : ObservationRegistry.NOOP;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // Spring's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1) opens an Observation.Scope
        // around the whole filter chain, so this filter (order +10) runs inside it. Elide invokes
        // generated lifecycle hooks off the scoped request path (possibly a different thread), where
        // getCurrentObservation() returns null — stash the current observation as a request attribute
        // now, while it is still reliably available, so HookExecutor can parent onto it later.
        Observation currentObservation = observationRegistry.getCurrentObservation();
        if (currentObservation != null && currentObservation != Observation.NOOP) {
            request.setAttribute(ApertureRequestAttributes.PARENT_OBSERVATION, currentObservation);
        }

        TenantContextHolder.clear();
        ScopeContextHolder.clear();
        try {
            String uri = request.getRequestURI();
            if (uri.equals("/auth/login") || uri.equals("/auth/token") || uri.equals("/auth/refresh") || uri.equals("/auth/logout") || uri.equals("/auth/accept-invite") || uri.startsWith("/actuator/") || uri.startsWith("/v3/api-docs") || uri.startsWith("/swagger-ui") || uri.equals("/openapi.yaml") || uri.equals("/openapi.json")) {
                filterChain.doFilter(request, response);
                return;
            }

            ValidationResult result = credentialValidator.validate(request);
            
            if (result != null && result.isValid()) {
                AperturePrincipal principal = principalMapper.map(result);
                if (principal == null) {
                    log.warn("Principal mapping failed for valid credential");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                    return;
                }
                log.debug("Authenticated request. User: {}, Tenant: {}", principal.userId(), principal.tenantId());
                request.setAttribute("aperturePrincipal", principal);
                if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null) {
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes().setAttribute("aperturePrincipal", principal, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
                }
                if (metadata.tenancyMode() == TenancyMode.POOL) {
                    boolean isSuperAdmin = principal.superAdmin();
                    String explicitContext = request.getHeader("X-Aperture-Tenant-Context");
                    if (isSuperAdmin) {
                        if (explicitContext != null && !explicitContext.isEmpty()) {
                            if (applicationContext != null) {
                                try {
                                    com.itsjool.aperture.spi.IdentityAdministrationProvider provider = applicationContext.getBean(com.itsjool.aperture.spi.IdentityAdministrationProvider.class);
                                    java.util.Optional<com.itsjool.aperture.spi.TenantRecord> tenantOpt = provider.getTenant(explicitContext);
                                    if (tenantOpt.isEmpty() || !"ACTIVE".equals(tenantOpt.get().status())) {
                                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid or inactive tenant context");
                                        return;
                                    }
                                } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
                                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Tenant validation unavailable");
                                    return;
                                }
                            }
                            TenantContextHolder.setTenantId(explicitContext);
                            request.setAttribute(ApertureRequestAttributes.TENANT_ID, explicitContext);
                        } else if (requiresTenantContext(uri)) {
                            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "SuperAdmins must provide X-Aperture-Tenant-Context for generated APIs");
                            return;
                        }
                    } else {
                        if (explicitContext != null && !explicitContext.isEmpty()) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Only SuperAdmins can set explicit tenant context");
                            return;
                        }
                        TenantContextHolder.setTenantId(principal.tenantId());
                        request.setAttribute(ApertureRequestAttributes.TENANT_ID, principal.tenantId());
                    }
                }

                populateScopeContext(request);

                if (principal.scopes() != null && principal.scopes().contains("FORCE_CHANGE")
                        && !uri.equals("/auth/change-password")) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Password change required");
                    return;
                }

                jakarta.servlet.http.HttpServletRequestWrapper wrapper = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                    @Override
                    public java.security.Principal getUserPrincipal() {
                        return principal;
                    }
                };
                filterChain.doFilter(wrapper, response);
            } else if (result != null && !result.isValid()) {
                log.debug("Invalid credentials for URI: {}", uri);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            } else {
                // No credentials provided — pass through unauthenticated. Elide enforces
                // per-entity @ReadPermission / @CreatePermission on every request, so
                // entities without Prefab.Role.All will still be protected.
                log.debug("Unauthenticated request fallthrough for URI: {}", uri);
                filterChain.doFilter(request, response);
            }
        } finally {
            TenantContextHolder.clear();
            ScopeContextHolder.clear();
        }
    }

    /**
     * Populates {@link ScopeContextHolder} from any {@code X-Aperture-Scope-<Field>} headers
     * present on the request — e.g. {@code X-Aperture-Scope-Project: <uuid>} sets the "project"
     * scope value, matched at generation time against an entity's {@code scopedBy: project}.
     * Available to any authenticated caller, unlike {@code X-Aperture-Tenant-Context} (SuperAdmin
     * only): there is no principal-embedded scope claim to fall back to for a regular user, so
     * this is the only way to supply it. See {@link ScopeContextHolder} for what this does and
     * does not guarantee.
     */
    private void populateScopeContext(HttpServletRequest request) {
        java.util.Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) return;
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName.regionMatches(true, 0, "X-Aperture-Scope-", 0, "X-Aperture-Scope-".length())) {
                String field = headerName.substring("X-Aperture-Scope-".length()).toLowerCase();
                String value = request.getHeader(headerName);
                if (!field.isEmpty() && value != null && !value.isBlank()) {
                    ScopeContextHolder.set(field, value);
                }
            }
        }
    }

    private boolean requiresTenantContext(String uri) {
        if (!uri.startsWith("/api/")) {
            return false;
        }
        if (metadata.tenantScopedApiResources().isEmpty()) {
            return true;
        }
        String[] parts = uri.split("/");
        if (parts.length < 4) {
            return true;
        }
        return metadata.tenantScopedApiResources().contains(parts[3]);
    }
}
