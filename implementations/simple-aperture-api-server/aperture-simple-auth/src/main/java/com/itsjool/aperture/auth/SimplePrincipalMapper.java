package com.itsjool.aperture.auth;

import com.itsjool.aperture.spi.PrincipalMapper;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.ValidationResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;

public class SimplePrincipalMapper implements PrincipalMapper {
    private final Set<String> securityKeys;

    public SimplePrincipalMapper(Set<String> securityKeys) {
        this.securityKeys = securityKeys != null ? securityKeys : Set.of();
    }

    @Override
    public AperturePrincipal map(ValidationResult validationResult) {
        if (validationResult instanceof SimpleCredentialValidator.TrustedAccountValidationResult account
                && account.isValid()) {
            // Domain roles must only contain YAML-declared roles — no platform authority strings
            Set<String> roles = Collections.unmodifiableSet(new LinkedHashSet<>(account.roles()));
            Map<String, Object> profile = Collections.unmodifiableMap(
                    new LinkedHashMap<>(account.profile()));
            Map<String, Object> securityAttributes = Collections.unmodifiableMap(
                    new LinkedHashMap<>(account.securityAttributes()));
            return new AperturePrincipal(account.subject(), account.tenantId(), roles,
                    account.kind(), profile, securityAttributes,
                    account.scopes(), account.isSuperAdmin(), account.isTenantAdmin());
        }
        return null;
    }
}
