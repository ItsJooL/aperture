package com.itsjool.aperture.spi;

import java.util.Map;

public record UserCreateCommand(
        String tenantId,
        String userId,
        String username,
        String password,
        Map<String, Object> profile,
        Map<String, Object> securityAttributes,
        boolean forcePasswordChange) {
    public UserCreateCommand {
        profile = JsonAttributes.copy(profile);
        securityAttributes = JsonAttributes.copy(securityAttributes);
    }

    @Override
    public String toString() {
        return "UserCreateCommand[tenantId=" + tenantId + ", userId=" + userId
                + ", username=" + username + ", password=[REDACTED], profile="
                + profile + ", securityAttributes=" + securityAttributes
                + ", forcePasswordChange=" + forcePasswordChange + "]";
    }
}
