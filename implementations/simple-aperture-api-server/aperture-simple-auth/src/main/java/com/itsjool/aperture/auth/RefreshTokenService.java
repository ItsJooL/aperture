package com.itsjool.aperture.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

public class RefreshTokenService {
    private static final int TOKEN_BYTES = 32;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration refreshDuration;
    private final SecureRandom secureRandom;

    public RefreshTokenService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactions,
            Clock clock,
            Duration refreshDuration,
            SecureRandom secureRandom) {
        if (refreshDuration.isZero() || refreshDuration.isNegative()) {
            throw new IllegalArgumentException("Refresh duration must be positive");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager(), transactions);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.refreshDuration = refreshDuration;
        this.secureRandom = secureRandom;
    }

    public String issue(String userId) {
        return transactions.execute(status -> insertToken(userId, clock.instant()).rawToken());
    }

    public Rotation rotate(String rawPresentedToken) {
        CompletedRotation<String> completed = rotate(rawPresentedToken, Function.identity());
        return new Rotation(completed.result(), completed.refreshToken());
    }

    public <T> CompletedRotation<T> rotate(
            String rawPresentedToken, Function<String, T> trustedContinuation) {
        RotationOutcome<T> outcome = transactions.execute(status -> {
            TokenRow current = findForUpdate(hash(rawPresentedToken));
            Instant now = clock.instant();
            if (current.usedAt() != null) {
                revokeFamilyById(current.id(), now);
                return RotationOutcome.rejected();
            }
            if (current.revokedAt() != null || !current.expiresAt().isAfter(now)) {
                return RotationOutcome.rejected();
            }

            T continuationResult = trustedContinuation.apply(current.userId());
            IssuedToken replacement = insertToken(current.userId(), now);
            int updated = jdbcTemplate.update("""
                    UPDATE aperture_refresh_tokens
                       SET used_at = ?, replaced_by_token_id = ?
                     WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL
                    """, Timestamp.from(now), replacement.id(), current.id());
            if (updated != 1) {
                revokeFamilyById(current.id(), now);
                return RotationOutcome.rejected();
            }
            return RotationOutcome.completed(
                    new CompletedRotation<>(continuationResult, replacement.rawToken()));
        });
        if (outcome == null || !outcome.accepted()) {
            throw new InvalidRefreshTokenException();
        }
        return outcome.completed();
    }

    public void revokeFamily(String rawPresentedToken) {
        transactions.executeWithoutResult(status -> {
            TokenRow presented = findForUpdate(hash(rawPresentedToken));
            revokeFamilyById(presented.id(), clock.instant());
        });
    }

    private void revokeFamilyById(String tokenId, Instant now) {
        jdbcTemplate.update("""
                WITH RECURSIVE family(id) AS (
                    SELECT id
                      FROM aperture_refresh_tokens
                     WHERE id = ?
                    UNION
                    SELECT CASE
                               WHEN token.id = family.id THEN token.replaced_by_token_id
                               ELSE token.id
                           END
                      FROM family
                      JOIN aperture_refresh_tokens token
                        ON (token.id = family.id AND token.replaced_by_token_id IS NOT NULL)
                        OR token.replaced_by_token_id = family.id
                )
                UPDATE aperture_refresh_tokens
                   SET revoked_at = ?
                 WHERE id IN (SELECT id FROM family)
                """, tokenId, Timestamp.from(now));
    }

    private TokenRow findForUpdate(String tokenHash) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, user_id, expires_at, used_at, revoked_at
                      FROM aperture_refresh_tokens
                     WHERE token_hash = ?
                     FOR UPDATE
                    """, (rs, rowNum) -> new TokenRow(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    instant(rs.getTimestamp("used_at")),
                    instant(rs.getTimestamp("revoked_at"))), tokenHash);
        } catch (EmptyResultDataAccessException missing) {
            throw new InvalidRefreshTokenException();
        }
    }

    private IssuedToken insertToken(String userId, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO aperture_refresh_tokens (id, user_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                """, id, userId, hash(rawToken), Timestamp.from(now.plus(refreshDuration)));
        return new IssuedToken(id, rawToken);
    }

    private static String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Rotation(String userId, String refreshToken) {}

    public record CompletedRotation<T>(T result, String refreshToken) {}

    private record IssuedToken(String id, String rawToken) {}

    private record TokenRow(
            String id, String userId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private record RotationOutcome<T>(boolean accepted, CompletedRotation<T> completed) {
        private static <T> RotationOutcome<T> completed(CompletedRotation<T> completed) {
            return new RotationOutcome<>(true, completed);
        }

        private static <T> RotationOutcome<T> rejected() {
            return new RotationOutcome<>(false, null);
        }
    }

    public static final class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException() {
            super("Invalid refresh token");
        }
    }
}
