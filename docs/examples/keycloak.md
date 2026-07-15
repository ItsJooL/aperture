---
title: Keycloak Integration
description: Swapping the auth provider using the CredentialValidator SPI.
---

# Keycloak Integration

The Keycloak demo shows how to replace Aperture's built-in JWT auth with an external identity provider. The technique generalises to any OIDC provider: Okta, Auth0, Azure AD, or a custom SSO system.

**What changes:** token validation. **What stays the same:** every entity endpoint, multi-tenancy, RBAC, ABAC, hooks, audit, and schema management.

## The `CredentialValidator` SPI

The entire auth seam is this single interface:

```java
public interface CredentialValidator {
    ValidationResult validate(HttpServletRequest req);
}
```

Aperture's filter calls `validate(request)` on every incoming request. The implementation extracts credentials from the request (from `Authorization`, `X-API-Key`, or anything else), validates them against the identity provider, and returns either a success containing an `AperturePrincipal` or a failure with an error message.

The `AperturePrincipal` carries everything Aperture needs:

```java
public record AperturePrincipal(
    String userId,
    String tenantId,
    Set<String> roles,            // domain roles only
    PrincipalKind kind,
    Map<String, Object> profile,
    Map<String, Object> securityAttributes,
    Set<String> scopes,
    boolean superAdmin,
    boolean tenantAdmin
) implements java.security.Principal {}
```

## The Keycloak implementation

The Keycloak demo's `KeycloakCredentialValidator` validates JWTs using Keycloak's JWKS endpoint:

```java
@Component
public class KeycloakCredentialValidator implements CredentialValidator {

    private final NimbusJwtDecoder jwtDecoder;
    private final Map<String, String> securityClaimMappings;

    public KeycloakCredentialValidator(
            @Value("${keycloak.jwks-uri}") String jwksUri,
            @Value("${keycloak.security-claim-mappings:}") String securityClaimMappings) {
        this.jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        this.securityClaimMappings = parseMappings(securityClaimMappings);
    }

    @Override
    public ValidationResult validate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return ValidationResult.failure("Missing bearer token");
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(7));
            return new KeycloakValidationResult(jwt.getSubject(), extractRoles(jwt), extractAttributes(jwt));
        } catch (JwtException e) {
            return ValidationResult.failure("Invalid Keycloak token");
        }
    }

    private static final Set<String> PROFILE_CLAIMS = Set.of(
            "name", "given_name", "family_name", "email", "preferred_username", "picture");

    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return List.of();
        Object rolesObj = realmAccess.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    Map<String, Object> extractAttributes(Jwt jwt) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        // Well-known OIDC profile claims
        PROFILE_CLAIMS.forEach(claim -> {
            Object value = jwt.getClaim(claim);
            if (value != null) attrs.put(claim, value);
        });
        // Mapped security claims (keycloakClaim → apertureSecurityAttribute)
        securityClaimMappings.forEach((claim, attribute) -> {
            Object value = jwt.getClaim(claim);
            if (value != null) attrs.put(attribute, value);
        });
        return attrs;
    }
}
```

The `NimbusJwtDecoder` fetches Keycloak's public keys from the JWKS endpoint and verifies the JWT signature automatically. Role extraction reads the `realm_access.roles` Keycloak claim.

Attribute extraction is **explicit, not broad**: profile is populated from a fixed allowlist of well-known OIDC claims (`name`, `given_name`, `family_name`, `email`, `preferred_username`, `picture`). Security attributes arrive only via the `KEYCLOAK_SECURITY_CLAIM_MAPPINGS` allowlist, with no ambient claim promotion. This prevents unexpected JWT claims from silently entering Aperture's security context.

## How Spring wiring disables simple-auth

`SimpleCredentialValidator` (the built-in implementation) is declared with:

```java
@ConditionalOnMissingBean(CredentialValidator.class)
```

Declaring `KeycloakCredentialValidator` as a `@Component` satisfies this condition. Spring creates your bean and skips the entire `SimpleCredentialValidator` and the JWT infrastructure it depends on.

The demo also sets:

```yaml
aperture:
  auth:
    simple:
      enabled: false
```

This suppresses the `/auth/login`, `/auth/refresh`, and similar endpoints because they no longer make sense when Keycloak handles authentication.

## Configuration

```yaml
aperture:
  auth:
    simple:
      enabled: false    # disables built-in auth endpoints

keycloak:
  jwks-uri: ${KEYCLOAK_JWKS_URI:http://localhost:8180/realms/aperture/protocol/openid-connect/certs}
  security-claim-mappings: ${KEYCLOAK_SECURITY_CLAIM_MAPPINGS:kc_region:region}
```

`KEYCLOAK_JWKS_URI` should point to your realm's JWKS endpoint. The decoder caches and auto-rotates public keys using the TTL from Keycloak's response.
`KEYCLOAK_SECURITY_CLAIM_MAPPINGS` is a comma-separated allowlist of `keycloakClaim:apertureSecurityAttribute` pairs.

## Running the demo

```bash
git clone https://github.com/ItsJooL/aperture.git
cd aperture/demos/aperture-keycloak-demo
docker compose up -d
```

The `docker-compose.yml` starts Keycloak alongside `postgres` and `api-server`. The realm `aperture` with a pre-configured client and demo users is imported automatically.

Get a token from Keycloak (password grant for demo purposes):

```bash
export TOKEN=$(curl -s \
  -d "client_id=aperture-client" \
  -d "client_secret=aperture-secret" \
  -d "username=demouser" \
  -d "password=password" \
  -d "grant_type=password" \
  "http://localhost:8180/realms/aperture/protocol/openid-connect/token" | jq -r .access_token)
```

Use the Keycloak token directly against the Aperture API:

```bash
curl -s http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The request goes through `KeycloakCredentialValidator` → JWKS validation → `AperturePrincipal` extraction → RBAC enforcement → Elide response. No difference from the caller's perspective.

## Generalising to other providers

The pattern is always the same:

1. Implement `CredentialValidator`: extract the token, validate it against your provider's JWKS or introspection endpoint, and build an `AperturePrincipal`
2. Declare the bean as a `@Component` (or `@Bean` in a `@Configuration` class)
3. Set `aperture.auth.simple.enabled: false` to suppress built-in auth endpoints
4. Map your provider's claims to Aperture roles, ensuring the role names in JWT claims match the role names in your `RoleDefinition` manifest

For providers that use different claim structures (e.g. Okta uses `groups`, Auth0 uses custom namespaced claims), adjust the role extraction logic in step 1.
