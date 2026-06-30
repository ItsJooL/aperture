package com.itsjool.aperture.spi;

import java.util.List;

public record TenantProvisioningResult(TenantRecord tenant, UserRecord initialAdmin, List<String> roles) {
    public TenantProvisioningResult {
        roles = List.copyOf(roles);
    }
}
