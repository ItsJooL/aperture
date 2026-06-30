package com.itsjool.aperture.keycloak;

import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.PrincipalMapper;
import com.itsjool.aperture.spi.ValidationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class KeycloakPrincipalMapper implements PrincipalMapper {
    private final Set<String> securityKeys;

    public KeycloakPrincipalMapper(@Value("#{apertureRuntimeMetadata.securityAttributeKeys()}") Set<String> securityKeys) {
        this.securityKeys = securityKeys != null ? securityKeys : Set.of();
    }

    @Override
    public AperturePrincipal map(ValidationResult result) {
        if (result instanceof KeycloakValidationResult kv && kv.isValid()) {
            Map<String, Object> securityAttributes = new java.util.LinkedHashMap<>();
            Map<String, Object> profile = new java.util.LinkedHashMap<>();
            if (kv.attributes() != null) {
                kv.attributes().forEach((k, v) -> {
                    if (securityKeys.contains(k)) securityAttributes.put(k, v);
                    else profile.put(k, v);
                });
            }
            return new AperturePrincipal(
                kv.subject(),
                null,
                Set.copyOf(kv.roles()),
                com.itsjool.aperture.spi.PrincipalKind.USER,
                Map.copyOf(profile),
                Map.copyOf(securityAttributes));
        }
        return null;
    }
}
