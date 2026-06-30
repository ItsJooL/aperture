package com.itsjool.aperture.runtime.controller;

import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.spi.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/manage/tenants")
public class IdentityAdministrationController {

    private final IdentityAdministrationProvider provider;
    private final ApertureRuntimeMetadata metadata;

    public IdentityAdministrationController(IdentityAdministrationProvider provider, ApertureRuntimeMetadata metadata) {
        this.provider = provider;
        this.metadata = metadata;
    }

    private void requireMultiTenant() {
        if (metadata.tenancyMode() != com.itsjool.aperture.runtime.config.TenancyMode.POOL) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void requireSuperAdmin(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        AperturePrincipal p = (AperturePrincipal) principal;
        if (!p.superAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires SuperAdmin");
        }
    }

    private void requireSuperAdminOrSameTenantAdmin(Principal principal, String targetTenantId) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        AperturePrincipal p = (AperturePrincipal) principal;
        if (p.superAdmin()) {
            return;
        }
        if (p.tenantAdmin() && targetTenantId.equals(p.tenantId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires SuperAdmin or same-tenant TenantAdmin");
    }

    @PostMapping
    public ResponseEntity<?> provisionTenant(@RequestBody TenantProvisioningRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        String tenantId = req.tenantId != null ? req.tenantId : UUID.randomUUID().toString();
        String userId = req.initialAdminUserId != null ? req.initialAdminUserId : UUID.randomUUID().toString();
        Map<String, Object> profile = req.initialAdminProfile != null ? req.initialAdminProfile : java.util.Collections.emptyMap();
        Map<String, Object> secAttrs = req.initialAdminSecurityAttributes != null ? req.initialAdminSecurityAttributes : java.util.Collections.emptyMap();
        TenantProvisioningCommand cmd = new TenantProvisioningCommand(
                tenantId, req.tenantName, userId, req.initialAdminUsername, req.initialAdminPassword, profile, secAttrs
        );
        TenantProvisioningResult res = provider.provisionTenant(cmd);
        return ResponseEntity.created(URI.create("/manage/tenants/" + tenantId)).body(res);
    }

    @GetMapping
    public ResponseEntity<PagedResult<TenantRecord>> listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        if (size > 100) size = 100;
        return ResponseEntity.ok(provider.listTenants(page, size));
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<TenantRecord> getTenant(@PathVariable String tenantId, Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        return provider.getTenant(tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{tenantId}")
    public ResponseEntity<TenantRecord> updateTenantStatus(@PathVariable String tenantId, @RequestBody TenantStatusUpdateRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        String status = req.status;
        if (status == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing status");
        provider.updateTenantStatus(tenantId, status);
        return provider.getTenant(tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Void> deleteTenant(@PathVariable String tenantId, Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        provider.deleteTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{tenantId}/users")
    public ResponseEntity<?> createUser(@PathVariable String tenantId, @RequestBody UserCreateRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        String userId = req.userId != null ? req.userId : UUID.randomUUID().toString();
        Map<String, Object> profile = req.profile != null ? req.profile : java.util.Collections.emptyMap();
        Map<String, Object> secAttrs = req.securityAttributes != null ? req.securityAttributes : java.util.Collections.emptyMap();

        String temporaryPassword = null;
        boolean forcePasswordChange = false;
        String password = req.password;

        if (req.generatePassword || (password == null || password.isBlank())) {
            temporaryPassword = generateSecurePassword();
            password = temporaryPassword;
            forcePasswordChange = true;
        }

        UserCreateCommand cmd = new UserCreateCommand(tenantId, userId, req.username, password, profile, secAttrs, forcePasswordChange);
        UserRecord res = provider.createUser(cmd);

        UserCreationResponse body = new UserCreationResponse(res, temporaryPassword);
        return ResponseEntity.created(URI.create("/manage/tenants/" + tenantId + "/users/" + userId)).body(body);
    }

    private static String generateSecurePassword() {
        byte[] bytes = new byte[12];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @GetMapping("/{tenantId}/users")
    public ResponseEntity<PagedResult<UserRecord>> listUsers(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        if (size > 100) size = 100;
        return ResponseEntity.ok(provider.listUsers(new UserListQuery(tenantId, search, page, size)));
    }

    @DeleteMapping("/{tenantId}/users/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String tenantId, @PathVariable String userId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        
        AperturePrincipal p = (AperturePrincipal) principal;
        if (p.tenantAdmin() && !p.superAdmin()) {
            if (provider.isTenantAdmin(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TenantAdmins cannot delete other TenantAdmins");
            }
        }

        provider.deleteUser(tenantId, userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{tenantId}/users/{userId}/roles")
    public ResponseEntity<UserRecord> replaceUserRoles(@PathVariable String tenantId, @PathVariable String userId, @RequestBody ReplaceUserRolesRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        
        AperturePrincipal p = (AperturePrincipal) principal;
        if (p.tenantAdmin() && !p.superAdmin()) {
            if (provider.isTenantAdmin(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TenantAdmins cannot modify roles of other TenantAdmins");
            }
            if (req.roleNames != null && (req.roleNames.contains("TenantAdmin") || req.roleNames.contains("SuperAdmin"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TenantAdmins cannot assign reserved roles");
            }
        }

        provider.replaceUserRoles(tenantId, userId, req.roleNames);
        return provider.getUser(tenantId, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{tenantId}/users/{userId}")
    public ResponseEntity<UserRecord> updateUser(@PathVariable String tenantId, @PathVariable String userId, @RequestBody UserUpdateRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        
        AperturePrincipal p = (AperturePrincipal) principal;
        if (p.tenantAdmin() && !p.superAdmin()) {
            if (provider.isTenantAdmin(tenantId, userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TenantAdmins cannot modify other TenantAdmins");
            }
        }

        java.util.Optional<String> statusOpt = req.status != null ? java.util.Optional.of(req.status) : java.util.Optional.empty();
        java.util.Optional<Map<String, Object>> profileOpt = req.profile != null ? java.util.Optional.of(req.profile) : java.util.Optional.empty();
        java.util.Optional<Map<String, Object>> secAttrsOpt = req.securityAttributes != null ? java.util.Optional.of(req.securityAttributes) : java.util.Optional.empty();
        provider.updateUser(tenantId, userId, new UserUpdateCommand(statusOpt, profileOpt, secAttrsOpt));
        return provider.getUser(tenantId, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{tenantId}/tenant-admins/{userId}")
    public ResponseEntity<Void> assignTenantAdmin(@PathVariable String tenantId, @PathVariable String userId, Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        provider.assignTenantAdmin(tenantId, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{tenantId}/tenant-admins/{userId}")
    public ResponseEntity<Void> removeTenantAdmin(@PathVariable String tenantId, @PathVariable String userId, Principal principal) {
        requireMultiTenant();
        requireSuperAdmin(principal);
        provider.removeTenantAdmin(tenantId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{tenantId}/invites")
    public ResponseEntity<InviteCreationResult> createInvite(@PathVariable String tenantId, @RequestBody InviteCreateRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        
        AperturePrincipal p = (AperturePrincipal) principal;
        if (p.tenantAdmin() && !p.superAdmin()) {
            if (req.roleNames != null && (req.roleNames.contains("TenantAdmin") || req.roleNames.contains("SuperAdmin"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TenantAdmins cannot assign reserved roles in invites");
            }
        }

        InviteCreateCommand cmd = new InviteCreateCommand(tenantId, p.userId(),
                req.roleNames != null ? req.roleNames : java.util.Collections.emptyList(),
                req.profile, req.securityAttributes,
                req.expiresInHours != null ? req.expiresInHours : 72);
        InviteCreationResult result = provider.createInvite(cmd);
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/{tenantId}/invites")
    public ResponseEntity<List<InviteRecord>> listInvites(@PathVariable String tenantId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        return ResponseEntity.ok(provider.listInvites(tenantId));
    }

    @DeleteMapping("/{tenantId}/invites/{inviteId}")
    public ResponseEntity<Void> revokeInvite(@PathVariable String tenantId, @PathVariable String inviteId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        provider.revokeInvite(tenantId, inviteId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{tenantId}/service-accounts")
    public ResponseEntity<?> createServiceAccount(@PathVariable String tenantId, @RequestBody ServiceAccountCreateRequest req, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        String accountId = req.accountId != null ? req.accountId : UUID.randomUUID().toString();
        ServiceAccountCreateCommand cmd = new ServiceAccountCreateCommand(tenantId, accountId, req.clientId, req.roleNames, req.expiresAt, req.securityAttributes);
        ServiceAccountCreationResult res = provider.createServiceAccount(cmd);
        return ResponseEntity.created(URI.create("/manage/tenants/" + tenantId + "/service-accounts/" + accountId)).body(res);
    }

    @PostMapping("/{tenantId}/service-accounts/{accountId}/rotate-secret")
    public ResponseEntity<?> rotateServiceAccountSecret(@PathVariable String tenantId, @PathVariable String accountId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        var result = provider.rotateServiceAccountSecret(tenantId, accountId);
        return ResponseEntity.ok(Map.of(
                "account", result.account(),
                "secret", result.rawSecret()
        ));
    }

    @GetMapping("/{tenantId}/service-accounts")
    public ResponseEntity<List<ServiceAccountRecord>> listServiceAccounts(@PathVariable String tenantId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        return ResponseEntity.ok(provider.listServiceAccounts(tenantId));
    }

    @PostMapping("/{tenantId}/service-accounts/{accountId}/disable")
    public ResponseEntity<ServiceAccountRecord> disableServiceAccount(@PathVariable String tenantId, @PathVariable String accountId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        provider.disableServiceAccount(tenantId, accountId);
        return provider.getServiceAccount(tenantId, accountId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{tenantId}/personal-api-keys")
    public ResponseEntity<List<ApiKeyRecord>> listPersonalApiKeysForTenant(@PathVariable String tenantId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        return ResponseEntity.ok(provider.listApiKeys(tenantId));
    }

    @PostMapping("/{tenantId}/personal-api-keys/{keyId}/revoke")
    public ResponseEntity<ApiKeyRecord> revokePersonalApiKey(@PathVariable String tenantId, @PathVariable String keyId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        provider.disableApiKey(tenantId, keyId);
        return provider.getApiKey(tenantId, keyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{tenantId}/settings/personal-api-keys")
    public ResponseEntity<PersonalApiKeySettings> getPersonalApiKeySettings(@PathVariable String tenantId, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        PersonalApiKeySettings settings = provider.getPersonalApiKeySettings(tenantId);
        return ResponseEntity.ok(settings);
    }

    @PutMapping("/{tenantId}/settings/personal-api-keys")
    public ResponseEntity<PersonalApiKeySettings> updatePersonalApiKeySettings(@PathVariable String tenantId,
            @RequestBody PersonalApiKeySettings settings, Principal principal) {
        requireMultiTenant();
        requireSuperAdminOrSameTenantAdmin(principal, tenantId);
        provider.updatePersonalApiKeySettings(tenantId, settings);
        return ResponseEntity.ok(settings);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        String msg = e.getReason() != null ? e.getReason() : e.getMessage();
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse(msg));
    }

    @ExceptionHandler(IdentityAdministrationValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(IdentityAdministrationValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IdentityAdministrationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(IdentityAdministrationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IdentityAdministrationConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IdentityAdministrationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    public static class TenantProvisioningRequest {
        public String tenantId;
        public String tenantName;
        public String initialAdminUserId;
        public String initialAdminUsername;
        public String initialAdminPassword;
        public Map<String, Object> initialAdminProfile;
        public Map<String, Object> initialAdminSecurityAttributes;
    }

    public static class UserCreateRequest {
        public String userId;
        public String username;
        public String password;
        public boolean generatePassword = false;
        public Map<String, Object> profile;
        public Map<String, Object> securityAttributes;
    }

    public static class UserCreationResponse {
        public final String id;
        public final String username;
        public final String tenantId;
        public final String status;
        public final String temporaryPassword;

        public UserCreationResponse(UserRecord user, String temporaryPassword) {
            this.id = user.id();
            this.username = user.username();
            this.tenantId = user.tenantId();
            this.status = user.status();
            this.temporaryPassword = temporaryPassword;
        }
    }

    public static class ServiceAccountCreateRequest {
        public String accountId;
        public String clientId;
        public List<String> roleNames;
        public java.time.Instant expiresAt;
        public Map<String, Object> securityAttributes;
    }

    public static class TenantStatusUpdateRequest {
        public String status;
    }

    public static class ReplaceUserRolesRequest {
        public List<String> roleNames;
    }

    public static class UserUpdateRequest {
        public String status;
        public Map<String, Object> profile;
        public Map<String, Object> securityAttributes;
    }

    public static class InviteCreateRequest {
        public List<String> roleNames;
        public Integer expiresInHours;
        public Map<String, Object> profile;
        public Map<String, Object> securityAttributes;
    }

    public static class ErrorResponse {
        public String error;
        public ErrorResponse() {}
        public ErrorResponse(String error) { this.error = error; }
    }
}
