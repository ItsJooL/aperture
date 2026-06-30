package com.itsjool.aperture.spi;

import java.util.List;

public interface PersonalApiKeyProvider {
    ApiKeyCreationResult createPersonalApiKey(String tenantId, String userId, ApiKeyCreateCommand command);
    List<ApiKeyRecord> listPersonalApiKeys(String tenantId, String userId);
    void revokePersonalApiKey(String tenantId, String keyId);
}
