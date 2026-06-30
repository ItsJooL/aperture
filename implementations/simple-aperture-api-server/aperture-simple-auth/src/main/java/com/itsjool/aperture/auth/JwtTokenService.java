package com.itsjool.aperture.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class JwtTokenService {
    private final SecretKey key;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenDuration;
    private final Clock clock;

    public JwtTokenService(String secret, String issuer, String audience,
            Duration accessTokenDuration, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = requireText(issuer, "issuer");
        this.audience = requireText(audience, "audience");
        this.accessTokenDuration = Objects.requireNonNull(accessTokenDuration, "accessTokenDuration");
        if (accessTokenDuration.isZero() || accessTokenDuration.isNegative()) {
            throw new IllegalArgumentException("accessTokenDuration must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String generateToken(AuthenticatedAccount account) {
        Instant now = clock.instant();
        return generateToken(account, now, now.plus(accessTokenDuration));
    }

    public String generateToken(AuthenticatedAccount account, Instant notAfter) {
        Instant now = clock.instant();
        Objects.requireNonNull(notAfter, "notAfter");
        if (!notAfter.isAfter(now)) {
            throw new IllegalArgumentException("notAfter must be after the current time");
        }
        return generateToken(account, now, notAfter.isBefore(now.plus(accessTokenDuration))
                ? notAfter : now.plus(accessTokenDuration));
    }

    private String generateToken(AuthenticatedAccount account, Instant issuedAtInstant, Instant expiresAt) {
        Objects.requireNonNull(account, "account");
        requireJsonValue(account.profile(), "profile");
        requireJsonValue(account.securityAttributes(), "securityAttributes");
        Date issuedAt = Date.from(issuedAtInstant);
        return Jwts.builder()
                .subject(account.userId())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(issuedAt)
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .claim("username", account.username())
                .claim("tenantId", account.tenantId())
                .claim("roles", account.roles())
                .claim("profile", account.profile())
                .claim("securityAttributes", account.securityAttributes())
                .claim("accountKind", account.kind().name())
                .claim("isTenantAdmin", account.isTenantAdmin())
                .claim("isSuperAdmin", account.isSuperAdmin())
                .signWith(key)
                .compact();
    }

    public String generateForceChangeToken(AuthenticatedAccount account) {
        Instant now = clock.instant();
        Objects.requireNonNull(account, "account");
        return Jwts.builder()
                .subject(account.userId())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(5))))
                .id(UUID.randomUUID().toString())
                .claim("username", account.username())
                .claim("tenantId", account.tenantId())
                .claim("roles", account.roles())
                .claim("profile", account.profile())
                .claim("securityAttributes", account.securityAttributes())
                .claim("accountKind", account.kind().name())
                .claim("isTenantAdmin", account.isTenantAdmin())
                .claim("isSuperAdmin", account.isSuperAdmin())
                .claim("scope", List.of("FORCE_CHANGE"))
                .signWith(key)
                .compact();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireJsonValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                requireJsonValue(list.get(i), path + "[" + i + "]");
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException(path + " keys must be strings");
                }
                requireJsonValue(item, path + "." + key);
            });
            return;
        }
        throw new IllegalArgumentException(path + " contains a non-JSON value");
    }
}
