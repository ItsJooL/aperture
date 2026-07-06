package com.itsjool.aperture.keycloak;

import com.itsjool.aperture.spi.ServiceAccountIssuer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class KeycloakDemoConfiguration {

    // SimpleIdentityAdministrationProvider (always active) depends on ServiceAccountIssuer.
    // SimpleAuthConfiguration is skipped because KeycloakCredentialValidator is present,
    // so no SimpleServiceAccountIssuer is created. Provide a no-op here.
    @Bean
    public ServiceAccountIssuer keycloakServiceAccountIssuer() {
        return new ServiceAccountIssuer() {
            @Override
            public Optional<com.itsjool.aperture.spi.ServiceAccountToken> issue(
                    com.itsjool.aperture.spi.ServiceAccountRequest req) {
                return Optional.empty();
            }
            @Override
            public void disableCredentials(String clientId) {}
            @Override
            public IssuedCredentials issueCredentials() {
                throw new UnsupportedOperationException("Not supported in Keycloak demo");
            }
        };
    }
}
