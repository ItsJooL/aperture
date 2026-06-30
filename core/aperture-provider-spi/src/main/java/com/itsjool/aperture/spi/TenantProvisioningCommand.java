package com.itsjool.aperture.spi;

import java.util.Map;

public record TenantProvisioningCommand(
        String tenantId,
        String tenantName,
        String initialAdminUserId,
        String initialAdminUsername,
        String initialAdminPassword,
        Map<String, Object> initialAdminProfile,
        Map<String, Object> initialAdminSecurityAttributes) {

    public TenantProvisioningCommand {
        initialAdminProfile = JsonAttributes.copy(initialAdminProfile);
        initialAdminSecurityAttributes = JsonAttributes.copy(initialAdminSecurityAttributes);
    }

    @Override
    public String toString() {
        return "TenantProvisioningCommand[tenantId=" + tenantId + ", tenantName=" + tenantName
                + ", initialAdminUserId=" + initialAdminUserId + ", initialAdminUsername="
                + initialAdminUsername + ", initialAdminPassword=[REDACTED], initialAdminProfile="
                + initialAdminProfile + ", initialAdminSecurityAttributes=" + initialAdminSecurityAttributes + "]";
    }
}
