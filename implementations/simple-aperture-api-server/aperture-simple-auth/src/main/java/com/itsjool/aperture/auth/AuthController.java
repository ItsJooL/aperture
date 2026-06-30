package com.itsjool.aperture.auth;

import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.IdentityAdministrationNotFoundException;
import com.itsjool.aperture.spi.IdentityAdministrationProvider;
import com.itsjool.aperture.spi.IdentityAdministrationValidationException;
import com.itsjool.aperture.spi.ServiceAccountIssuer;
import com.itsjool.aperture.spi.ServiceAccountRequest;
import com.itsjool.aperture.spi.UserRecord;
import com.itsjool.aperture.spi.UserUpdateCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/auth")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "aperture.auth.simple.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private static final ErrorResponse BAD_REQUEST =
            new ErrorResponse("bad_request", "Invalid request");
    private static final ErrorResponse UNAUTHORIZED =
            new ErrorResponse("unauthorized", "Authentication failed");
    private static final ErrorResponse INTERNAL_ERROR =
            new ErrorResponse("internal_error", "Authentication service unavailable");

    private final AuthUserService userService;
    private final JwtTokenService jwtService;
    private final RefreshTokenService refreshTokens;
    private final ServiceAccountIssuer serviceAccounts;
    private final IdentityAdministrationProvider provider;

    public AuthController(
            AuthUserService userService,
            JwtTokenService jwtService,
            RefreshTokenService refreshTokens,
            ServiceAccountIssuer serviceAccounts,
            IdentityAdministrationProvider provider) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.serviceAccounts = serviceAccounts;
        this.provider = provider;
    }

    @PostMapping("/token")
    public ResponseEntity<?> serviceAccountToken(@RequestBody ServiceAccountTokenRequest request) {
        if (request == null || blank(request.clientId()) || blank(request.clientSecret())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        return serviceAccounts.issue(new ServiceAccountRequest(request.clientId(), request.clientSecret()))
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(
                        new ServiceAccountTokenResponse(token.clientId(), token.accessToken())))
                .orElseGet(AuthController::unauthorized);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || blank(request.username()) || blank(request.password())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        var account = userService.authenticate(request.username(), request.password());
        if (account.isEmpty()) {
            return unauthorized();
        }

        AuthenticatedAccount authenticated = account.orElseThrow();
        if (authenticated.forcePasswordChange()) {
            String accessToken = jwtService.generateForceChangeToken(authenticated);
            String refreshToken = refreshTokens.issue(authenticated.userId());
            return ResponseEntity.ok(new ForceChangeTokenResponse(accessToken, refreshToken, true));
        }
        String accessToken = jwtService.generateToken(authenticated);
        String refreshToken = refreshTokens.issue(authenticated.userId());
        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        if (request == null || blank(request.refreshToken())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        try {
            RefreshTokenService.CompletedRotation<String> rotation = refreshTokens.rotate(
                    request.refreshToken(), userId -> {
                        AuthenticatedAccount account = userService.loadActiveById(userId)
                                .orElseThrow(RefreshTokenService.InvalidRefreshTokenException::new);
                        return jwtService.generateToken(account);
                    });
            return ResponseEntity.ok(new TokenResponse(rotation.result(), rotation.refreshToken()));
        } catch (RefreshTokenService.InvalidRefreshTokenException rejected) {
            return unauthorized();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshRequest request) {
        if (request == null || blank(request.refreshToken())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        try {
            refreshTokens.revokeFamily(request.refreshToken());
            return ResponseEntity.noContent().build();
        } catch (RefreshTokenService.InvalidRefreshTokenException rejected) {
            return unauthorized();
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Principal rawPrincipal) {
        if (rawPrincipal == null) return ResponseEntity.status(401).build();
        AperturePrincipal p = (AperturePrincipal) rawPrincipal;
        String username = p.profile() != null ? (String) p.profile().get("username") : null;
        return ResponseEntity.ok(new MeResponse(p.userId(), username, p.tenantId(), p.roles(), p.profile(), p.securityAttributes(), p.superAdmin(), p.tenantAdmin()));
    }

    private static final Set<String> PROFILE_KEYS = Set.of("firstName", "lastName", "name", "username", "picture");

    @PatchMapping("/me")
    public ResponseEntity<?> patchMe(@RequestBody MePatchRequest req, Principal rawPrincipal) {
        if (rawPrincipal == null) return ResponseEntity.status(401).build();
        AperturePrincipal p = (AperturePrincipal) rawPrincipal;
        if (req.securityAttributes() != null && !req.securityAttributes().isEmpty()) {
            return ResponseEntity.status(403).body(new ErrorResponse("FORBIDDEN", "Normal users cannot edit security attributes."));
        }
        if (req.profile() != null && p.tenantId() != null) {
            Map<String, Object> profileUpdates = new java.util.LinkedHashMap<>();
            req.profile().forEach((k, v) -> {
                if (PROFILE_KEYS.contains(k)) {
                    profileUpdates.put(k, v);
                }
            });
            provider.updateUser(p.tenantId(), p.userId(), new UserUpdateCommand(Optional.empty(), Optional.of(profileUpdates), Optional.empty()));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest req, Principal rawPrincipal) {
        if (rawPrincipal == null) return ResponseEntity.status(401).build();
        AperturePrincipal p = (AperturePrincipal) rawPrincipal;
        if (blank(req.currentPassword()) || blank(req.newPassword())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        try {
            userService.changePassword(p.userId(), req.currentPassword(), req.newPassword());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", e.getMessage()));
        }
        AuthenticatedAccount account = userService.loadActiveById(p.userId()).orElseThrow();
        String accessToken = jwtService.generateToken(account);
        String refreshToken = refreshTokens.issue(account.userId());
        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken));
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<?> acceptInvite(@RequestBody AcceptInviteRequest req) {
        if (blank(req.token()) || blank(req.username()) || blank(req.password())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        try {
            UserRecord user = provider.acceptInvite(req.token(), req.username(), req.password());
            AuthenticatedAccount account = userService.loadActiveById(user.id()).orElseThrow();
            String accessToken = jwtService.generateToken(account);
            String refreshToken = refreshTokens.issue(account.userId());
            return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken));
        } catch (IdentityAdministrationNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IdentityAdministrationValidationException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", e.getMessage()));
        }
    }


    @PostMapping("/me/api-keys")
    public ResponseEntity<?> createPersonalApiKey(@RequestBody ApiKeyCreateRequest req, Principal rawPrincipal) {
        if (rawPrincipal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (req == null || blank(req.name())) {
            return ResponseEntity.badRequest().body(BAD_REQUEST);
        }
        AperturePrincipal p = (AperturePrincipal) rawPrincipal;
        com.itsjool.aperture.spi.ApiKeyCreateCommand cmd = new com.itsjool.aperture.spi.ApiKeyCreateCommand(
                p.tenantId(), p.userId(), req.name(), req.expiresAt(), req.nonExpiring(), req.domainRoles(), req.securityAttributes());
        com.itsjool.aperture.spi.ApiKeyCreationResult res = provider.createApiKey(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @GetMapping("/me/api-keys")
    public ResponseEntity<?> listPersonalApiKeys(Principal rawPrincipal) {
        if (rawPrincipal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        AperturePrincipal p = (AperturePrincipal) rawPrincipal;
        java.util.List<com.itsjool.aperture.spi.ApiKeyRecord> keys = provider.listApiKeysByUser(p.tenantId(), p.userId());
        return ResponseEntity.ok(keys);
    }

    @PostMapping("/me/api-keys/{keyId}/disable")
    public ResponseEntity<?> disablePersonalApiKey(@org.springframework.web.bind.annotation.PathVariable String keyId, Principal rawPrincipal) {
        if (rawPrincipal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        AperturePrincipal p = (AperturePrincipal) rawPrincipal;
        var key = provider.getApiKey(p.tenantId(), keyId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.userId().equals(key.userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        provider.disableApiKey(p.tenantId(), keyId);
        return provider.getApiKey(p.tenantId(), keyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformedRequest() {
        return ResponseEntity.badRequest().body(BAD_REQUEST);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> databaseFailure(DataAccessException failure) {
        LOG.error("Database failure during authentication", failure);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(INTERNAL_ERROR);
    }

    private static ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNAUTHORIZED);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record LoginRequest(String username, String password) {}

    public record RefreshRequest(String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken) {}

    public record ForceChangeTokenResponse(String accessToken, String refreshToken, boolean forcePasswordChange) {}

    public record ServiceAccountTokenRequest(String clientId, String clientSecret) {}

    public record ServiceAccountTokenResponse(String clientId, String accessToken) {}

    public record ErrorResponse(String code, String message) {}

    public record MeResponse(String userId, String username, String tenantId, Set<String> roles, Map<String, Object> profile, Map<String, Object> securityAttributes, boolean superAdmin, boolean tenantAdmin) {}

    public record MePatchRequest(Map<String, Object> profile, Map<String, Object> securityAttributes) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record AcceptInviteRequest(String token, String username, String password) {}

    public record ApiKeyCreateRequest(
            String name,
            java.time.Instant expiresAt,
            boolean nonExpiring,
            java.util.List<String> domainRoles,
            Map<String, Object> securityAttributes) {}

}
