package com.itsjool.aperture.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IdentityAdministrationProvider {
    TenantProvisioningResult provisionTenant(TenantProvisioningCommand command);

    Optional<TenantRecord> getTenant(String tenantId);
    PagedResult<TenantRecord> listTenants(int page, int size);
    void updateTenantStatus(String tenantId, String status);
    void deleteTenant(String tenantId);

    UserRecord createUser(UserCreateCommand command);
    PagedResult<UserRecord> listUsers(UserListQuery query);
    Optional<UserRecord> getUser(String tenantId, String userId);
    void updateUserStatus(String tenantId, String userId, String status);
    void replaceUserRoles(String tenantId, String userId, List<String> roleNames);
    void updateUser(String tenantId, String userId, UserUpdateCommand command);
    void deleteUser(String tenantId, String userId);

    void assignTenantAdmin(String tenantId, String userId);
    void removeTenantAdmin(String tenantId, String userId);
    boolean isTenantAdmin(String tenantId, String userId);


    ServiceAccountCreationResult createServiceAccount(ServiceAccountCreateCommand command);
    ServiceAccountSecretRotationResult rotateServiceAccountSecret(String tenantId, String accountId);
    List<ServiceAccountRecord> listServiceAccounts(String tenantId);
    Optional<ServiceAccountRecord> getServiceAccount(String tenantId, String accountId);
    void disableServiceAccount(String tenantId, String accountId);

    ApiKeyCreationResult createApiKey(ApiKeyCreateCommand command);
    List<ApiKeyRecord> listApiKeys(String tenantId);
    List<ApiKeyRecord> listApiKeysByUser(String tenantId, String userId);
    Optional<ApiKeyRecord> getApiKey(String tenantId, String keyId);
    void disableApiKey(String tenantId, String keyId);

    InviteCreationResult createInvite(InviteCreateCommand command);
    List<InviteRecord> listInvites(String tenantId);
    void revokeInvite(String tenantId, String inviteId);
    UserRecord acceptInvite(String rawToken, String username, String rawPassword);

    default PersonalApiKeySettings getGlobalPersonalApiKeySettings() { return PersonalApiKeySettings.DEFAULTS; }
    default void updateGlobalPersonalApiKeySettings(PersonalApiKeySettings settings) {}
    default PersonalApiKeySettings getPersonalApiKeySettings(String tenantId) { return PersonalApiKeySettings.DEFAULTS; }
    default void updatePersonalApiKeySettings(String tenantId, PersonalApiKeySettings settings) {}
}
