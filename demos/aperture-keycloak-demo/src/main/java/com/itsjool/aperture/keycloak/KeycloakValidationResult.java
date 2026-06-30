package com.itsjool.aperture.keycloak;

import com.itsjool.aperture.spi.ValidationResult;

import java.util.List;
import java.util.Map;

// Carries Keycloak JWT claims through the auth pipeline from validator to mapper.
record KeycloakValidationResult(
        String subject,
        List<String> roles,
        Map<String, Object> attributes) implements ValidationResult {

    @Override public boolean isValid() { return true; }
    @Override public String tokenSubject() { return subject; }
    @Override public String errorMessage() { return null; }
}
