package com.itsjool.aperture.spi;
import java.util.Optional;
public interface ServiceAccountIssuer {
    Optional<ServiceAccountToken> issue(ServiceAccountRequest req);

    /**
     * Blocks future credential exchanges for the client. Already-issued stateless access tokens
     * remain valid until their bounded expiration.
     */
    void disableCredentials(String clientId);

    record IssuedCredentials(String secret, String hash) {}

    IssuedCredentials issueCredentials();
}
