package com.itsjool.aperture.runtime.tenant;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class TenantRouter extends AbstractRoutingDataSource {
    private final boolean tenancyEnabled;

    public TenantRouter(boolean tenancyEnabled) {
        this.tenancyEnabled = tenancyEnabled;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (!tenancyEnabled) {
            return null;
        }
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required but none was found");
        }
        return tenantId;
    }
}
