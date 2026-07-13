package com.itsjool.aperture.starter;

import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import io.micrometer.common.KeyValue;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ApertureObservationFilter#map} enriches the {@code http.server.requests}
 * observation context correctly. Behaviors that matter most:
 *
 * <ul>
 *   <li>{@code aperture.api.version} / {@code aperture.entity} are added as low-cardinality
 *   key-values for EVERY request, including non-{@code /api/vN} ones (using a "none" sentinel) —
 *   this keeps the tag key set identical across all {@code http.server.requests} series, which
 *   Prometheus requires (mismatched key sets across series for the same meter name cause
 *   registration failures and silently drop series from {@code /actuator/prometheus}).</li>
 *   <li>Both segments are validated against a bounded source of truth before being tagged — the
 *   version against {@code ^v\d+$} and the entity against the registered {@link EntityDictionary}
 *   json-api types — and fall back to the "none" sentinel rather than the raw, attacker-controlled
 *   URI segment. This filter wraps the whole chain and runs before authentication, so an
 *   unauthenticated caller sending arbitrary path segments (e.g. {@code /api/vXXXX/YYYY}) must not
 *   be able to mint a fresh Prometheus time series per distinct segment pair.</li>
 *   <li>{@code aperture.tenant.id} is read from the request attribute stashed by AuthFilter, not
 *   from {@code TenantContextHolder} (which has already been cleared by the time this filter runs,
 *   since {@code map()} executes when the observation stops, in the outer
 *   {@code ServerHttpObservationFilter}).</li>
 * </ul>
 */
class ApertureObservationFilterTest {

    @Include(name = "customers")
    static class TestCustomer {
        @Id
        private Long id;
    }

    private static EntityDictionary entityDictionaryWithCustomers() {
        EntityDictionary dictionary = EntityDictionary.builder().build();
        dictionary.bindEntity(TestCustomer.class);
        return dictionary;
    }

    private final ApertureObservationFilter filter = new ApertureObservationFilter(entityDictionaryWithCustomers());

    @Test
    void versionedApiRequestGetsRealVersionAndEntityTags() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.setRequestURI("/api/v1/customers");
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        filter.map(context);

        assertThat(context.getLowCardinalityKeyValues())
            .contains(KeyValue.of("aperture.api.version", "v1"))
            .contains(KeyValue.of("aperture.entity", "customers"))
            .contains(KeyValue.of("aperture.operation", "GET"));
    }

    @Test
    void nonApiRequestStillGetsVersionAndEntityTagsAsNoneSentinel() {
        // This is the crux of the Prometheus-registration-failure fix: these keys must be present
        // on every http.server.requests series, not just /api/vN ones, or Prometheus rejects
        // whichever tag-key-set shows up second for the same meter name.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/manage/tenants");
        request.setRequestURI("/manage/tenants");
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        filter.map(context);

        assertThat(context.getLowCardinalityKeyValues())
            .contains(KeyValue.of("aperture.api.version", "none"))
            .contains(KeyValue.of("aperture.entity", "none"))
            .contains(KeyValue.of("aperture.operation", "GET"));
    }

    @Test
    void tenantIdIsAddedAsHighCardinalityKeyWhenRequestAttributePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.setRequestURI("/api/v1/customers");
        request.setAttribute(ApertureRequestAttributes.TENANT_ID, "tenant-A");
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues())
            .contains(KeyValue.of("aperture.tenant.id", "tenant-A"));
    }

    @Test
    void tenantIdIsAbsentWhenRequestAttributeNotSet() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.setRequestURI("/api/v1/customers");
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues())
            .noneMatch(kv -> kv.getKey().equals("aperture.tenant.id"));
    }

    @Test
    void unauthenticatedRequestWithFabricatedVersionAndEntityGetsNoneSentinelsNotRawSegments() {
        // Adversarial case: an unauthenticated caller (this filter runs before AuthFilter) sending
        // arbitrary /api/vN/segment path pairs must not be able to mint a fresh Prometheus time
        // series per distinct pair. Neither "vXXXX" (fails the ^v\d+$ check) nor "YYYY" (not a
        // registered entity) may be promoted onto the http.server.requests metric tags raw.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/vXXXX/YYYY");
        request.setRequestURI("/api/vXXXX/YYYY");
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        filter.map(context);

        assertThat(context.getLowCardinalityKeyValues())
            .contains(KeyValue.of("aperture.api.version", "none"))
            .contains(KeyValue.of("aperture.entity", "none"))
            .doesNotContain(KeyValue.of("aperture.api.version", "vXXXX"))
            .doesNotContain(KeyValue.of("aperture.entity", "YYYY"));
    }

    @Test
    void unregisteredEntityUnderARealVersionFallsBackToNoneSentinelForEntityOnly() {
        // A well-formed version with a non-registered entity segment: the version tag is still
        // trusted (it matched ^v\d+$), but the entity tag must not leak the unregistered segment.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/nonexistent");
        request.setRequestURI("/api/v1/nonexistent");
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        filter.map(context);

        assertThat(context.getLowCardinalityKeyValues())
            .contains(KeyValue.of("aperture.api.version", "v1"))
            .contains(KeyValue.of("aperture.entity", "none"));
    }
}
