package com.itsjool.aperture.starter;

import com.yahoo.elide.core.dictionary.EntityBinding;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.Observation;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;

import java.util.regex.Pattern;

public class ApertureObservationFilter implements ObservationFilter {

    private static final String NONE = "none";
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("^v\\d+$");

    private final EntityDictionary entityDictionary;

    public ApertureObservationFilter(EntityDictionary entityDictionary) {
        this.entityDictionary = entityDictionary;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof ServerRequestObservationContext serverContext) {
            String uri = serverContext.getCarrier().getRequestURI();
            String method = serverContext.getCarrier().getMethod();

            // Read from the request attribute — not TenantContextHolder — because this filter runs
            // when the observation STOPS, in the outer ServerHttpObservationFilter, which is AFTER
            // AuthFilter's finally-block TenantContextHolder.clear() has already wiped the ThreadLocal.
            // Request attributes live for the whole request and are set by AuthFilter before that clear.
            Object tenantId = serverContext.getCarrier().getAttribute(ApertureRequestAttributes.TENANT_ID);
            if (tenantId != null) {
                // High-cardinality on purpose: tenant id is unbounded (one per customer org). As a
                // low-cardinality key it would become a tag on the shared http.server.requests timer
                // and explode the metric's time-series count as tenants are onboarded. High-cardinality
                // keys ride on spans (useful for trace filtering) but are not promoted to metric tags.
                context.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of("aperture.tenant.id", String.valueOf(tenantId)));
            }

            // Always add these two low-cardinality keys, even for non-versioned-API requests (e.g.
            // /manage/**, /actuator/**, /auth/**), using a "none" sentinel when there's no real value.
            // Prometheus requires every series of a meter to share the exact same tag key set; adding
            // these keys only sometimes causes registration of the http.server.requests meter to fail
            // for whichever tag-key-set arrives second, silently dropping those series from
            // /actuator/prometheus (though they remain visible in /actuator/metrics).
            //
            // These are metric tags (low-cardinality), so — unlike aperture.tenant.id above — the raw
            // URI segments must never be trusted directly: this filter wraps the whole chain and runs
            // before authentication, so an unauthenticated caller can send arbitrary path segments
            // (e.g. GET /api/vANYTHING/WHATEVER) and mint a fresh Prometheus time series per distinct
            // segment pair. Each segment is validated independently against a bounded source of truth
            // before being tagged; anything that doesn't validate falls back to the NONE sentinel
            // rather than the attacker-controlled raw value.
            String version = NONE;
            String entity = NONE;
            if (uri.startsWith("/api/v")) {
                String[] parts = uri.split("/");
                if (parts.length >= 4) {
                    if (API_VERSION_PATTERN.matcher(parts[2]).matches()) {
                        version = parts[2];
                    }
                    if (isRegisteredEntity(parts[3])) {
                        entity = parts[3];
                    }
                }
            }
            context.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("aperture.api.version", version));
            context.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("aperture.entity", entity));

            context.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("aperture.operation", method));
        }
        return context;
    }

    // Deliberately not cached at construction time: the EntityDictionary singleton is populated
    // by the DataStore (e.g. bindEntity for every @Include-annotated generated entity) as part of
    // the same application-context refresh that constructs this filter, and bean creation order
    // between the two is not guaranteed. Reading through the live reference on every call is a
    // small bounded scan (one entry per registered entity/version) and always sees the fully
    // populated dictionary once the app is actually serving traffic.
    private boolean isRegisteredEntity(String candidate) {
        for (EntityBinding binding : entityDictionary.getBindings()) {
            if (candidate.equals(binding.jsonApiType)) {
                return true;
            }
        }
        return false;
    }

}
