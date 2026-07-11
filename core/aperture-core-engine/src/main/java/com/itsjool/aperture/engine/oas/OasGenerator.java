package com.itsjool.aperture.engine.oas;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class OasGenerator {

    public String generate(ResolvedDomainModel model, TenancyMode tenancyMode, List<String> activeVersions) {
        StringBuilder sb = new StringBuilder();
        
        // Info block
        sb.append("openapi: \"3.1.0\"\n");
        sb.append("info:\n");
        sb.append("  title: Aperture API\n");
        sb.append("  version: \"").append(activeVersions.isEmpty() ? "1" : activeVersions.get(0)).append("\"\n");
        
        // Security schemes
        sb.append("components:\n");
        sb.append("  securitySchemes:\n");
        sb.append("    BearerAuth:\n");
        sb.append("      type: http\n");
        sb.append("      scheme: bearer\n");
        sb.append("      bearerFormat: JWT\n");
        sb.append("    ApiKeyAuth:\n");
        sb.append("      type: apiKey\n");
        sb.append("      in: header\n");
        sb.append("      name: X-API-Key\n");
        appendOneOfSchemas(sb, model);

        sb.append("security:\n");
        sb.append("  - BearerAuth: []\n");
        sb.append("  - ApiKeyAuth: []\n");
        
        // Paths
        sb.append("paths:\n");
        
        // Entities
        for (EntityDef entity : model.entities()) {
            for (String version : activeVersions) {
                appendEntityPaths(sb, entity, version);
            }
        }
        
        // Auth endpoints
        appendAuthPaths(sb);

        // Management endpoints
        if (tenancyMode == TenancyMode.POOL) {
            appendManagePaths(sb);
        }
        
        // Audit
        appendAuditPaths(sb);
        
        return sb.toString();
    }

    private void appendOneOfSchemas(StringBuilder sb, ResolvedDomainModel model) {
        Map<String, EntityDef> entitiesByName = model.entities().stream()
            .collect(Collectors.toMap(EntityDef::name, entity -> entity, (left, right) -> left, LinkedHashMap::new));
        Map<String, OneOfDef> oneOfsByName = model.oneOfs().stream()
            .collect(Collectors.toMap(OneOfDef::name, oneOf -> oneOf, (left, right) -> left, LinkedHashMap::new));

        boolean started = false;
        for (EntityDef entity : model.entities()) {
            if (entity.fields() == null) {
                continue;
            }
            for (Map.Entry<String, FieldDef> entry : entity.fields().entrySet()) {
                FieldDef field = entry.getValue();
                if (!"oneof".equalsIgnoreCase(field.type())) {
                    continue;
                }
                OneOfDef oneOf = oneOfsByName.get(field.targetClass());
                if (oneOf == null) {
                    continue;
                }
                if (!started) {
                    sb.append("  schemas:\n");
                    started = true;
                }
                String schemaName = entity.name() + capitalize(entry.getKey()) + "RelationshipData";
                sb.append("    ").append(schemaName).append(":\n");
                sb.append("      type: object\n");
                sb.append("      required: [type, id]\n");
                sb.append("      description: JSON:API resource identifier for one selected member of ").append(oneOf.name()).append("\n");
                sb.append("      properties:\n");
                sb.append("        type:\n");
                sb.append("          type: string\n");
                sb.append("          enum:\n");
                oneOf.members().stream()
                    .map(entitiesByName::get)
                    .filter(java.util.Objects::nonNull)
                    .map(this::plural)
                    .forEach(resourceType -> sb.append("            - ").append(resourceType).append("\n"));
                sb.append("        id:\n");
                sb.append("          type: string\n");
                sb.append("          format: uuid\n");
            }
        }
    }
    
    private void appendEntityPaths(StringBuilder sb, EntityDef entity, String version) {
        String pathBase = "/api/v" + version + "/" + (entity.plural() != null ? entity.plural().toLowerCase() : entity.name().toLowerCase() + "s");
        String title = entity.name();
        
        // Collection paths
        sb.append("  ").append(pathBase).append(":\n");
        
        // GET (List)
        sb.append("    get:\n");
        sb.append("      summary: List ").append(title).append(" resources\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Successful response\n");
        
        // POST (Create)
        sb.append("    post:\n");
        sb.append("      summary: Create a ").append(title).append("\n");
        sb.append("      responses:\n");
        sb.append("        '201':\n");
        sb.append("          description: Created\n");
        if (entity.optimisticLocking()) {
            sb.append("          headers:\n");
            sb.append("            ETag:\n");
            sb.append("              schema:\n");
            sb.append("                type: string\n");
        }
        
        // Item paths
        String itemPath = pathBase + "/{id}";
        sb.append("  ").append(itemPath).append(":\n");
        sb.append("    parameters:\n");
        sb.append("      - name: id\n");
        sb.append("        in: path\n");
        sb.append("        required: true\n");
        sb.append("        schema:\n");
        sb.append("          type: string\n");
        
        // GET (Read)
        sb.append("    get:\n");
        sb.append("      summary: Get a ").append(title).append(" by ID\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Successful response\n");
        if (entity.optimisticLocking()) {
            sb.append("          headers:\n");
            sb.append("            ETag:\n");
            sb.append("              schema:\n");
            sb.append("                type: string\n");
        }
        
        // PATCH (Update)
        sb.append("    patch:\n");
        sb.append("      summary: Update a ").append(title).append("\n");
        if (entity.optimisticLocking()) {
            sb.append("      parameters:\n");
            sb.append("        - name: If-Match\n");
            sb.append("          in: header\n");
            sb.append("          required: true\n");
            sb.append("          schema:\n");
            sb.append("            type: string\n");
        }
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Updated\n");
        if (entity.optimisticLocking()) {
            sb.append("          headers:\n");
            sb.append("            ETag:\n");
            sb.append("              schema:\n");
            sb.append("                type: string\n");
        }
        
        // DELETE
        sb.append("    delete:\n");
        sb.append("      summary: Delete a ").append(title).append(entity.softDelete() ? " (soft delete)" : "").append("\n");
        if (entity.optimisticLocking()) {
            sb.append("      parameters:\n");
            sb.append("        - name: If-Match\n");
            sb.append("          in: header\n");
            sb.append("          required: true\n");
            sb.append("          schema:\n");
            sb.append("            type: string\n");
        }
        sb.append("      responses:\n");
        sb.append("        '204':\n");
        sb.append("          description: Deleted\n");
    }

    private String plural(EntityDef entity) {
        return Optional.ofNullable(entity.plural())
            .map(String::toLowerCase)
            .orElseGet(() -> entity.name().toLowerCase() + "s");
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1);
    }

    private void appendAuthPaths(StringBuilder sb) {
        sb.append("  /auth/login:\n");
        sb.append("    post:\n");
        sb.append("      summary: Login\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Tokens\n");

        sb.append("  /auth/refresh:\n");
        sb.append("    post:\n");
        sb.append("      summary: Refresh token\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: New tokens\n");

        sb.append("  /auth/logout:\n");
        sb.append("    post:\n");
        sb.append("      summary: Logout\n");
        sb.append("      responses:\n");
        sb.append("        '204':\n");
        sb.append("          description: Logged out\n");

        sb.append("  /auth/me:\n");
        sb.append("    get:\n");
        sb.append("      summary: Get current user profile and security attributes\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Current user\n");
        sb.append("    patch:\n");
        sb.append("      summary: Update own profile (not security attributes)\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Updated\n");

        sb.append("  /auth/change-password:\n");
        sb.append("    post:\n");
        sb.append("      summary: Change own password\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Password changed\n");

        sb.append("  /auth/accept-invite:\n");
        sb.append("    post:\n");
        sb.append("      summary: Accept invite and set credentials\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Tokens\n");

        sb.append("  /auth/token:\n");
        sb.append("    post:\n");
        sb.append("      summary: Service account client credentials exchange\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Access token\n");

        sb.append("  /auth/me/api-keys:\n");
        sb.append("    get:\n");
        sb.append("      summary: List own personal API keys (metadata only, no raw values)\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: API key list\n");
        sb.append("    post:\n");
        sb.append("      summary: Create personal API key with optional role/attribute delegation\n");
        sb.append("      responses:\n");
        sb.append("        '201':\n");
        sb.append("          description: Created\n");

        sb.append("  /auth/me/api-keys/{keyId}/disable:\n");
        sb.append("    post:\n");
        sb.append("      summary: Revoke own personal API key\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Disabled\n");
    }

    private void appendManagePaths(StringBuilder sb) {
        sb.append("  /manage/tenants:\n");
        sb.append("    get:\n");
        sb.append("      summary: List tenants\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    post:\n");
        sb.append("      summary: Create tenant\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}:\n");
        sb.append("    get:\n");
        sb.append("      summary: Get tenant\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    patch:\n");
        sb.append("      summary: Update tenant\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    delete:\n");
        sb.append("      summary: Soft delete tenant\n");
        sb.append("      responses:\n        '204':\n          description: Deleted\n");

        sb.append("  /manage/tenants/{tenantId}/users:\n");
        sb.append("    get:\n");
        sb.append("      summary: List users\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    post:\n");
        sb.append("      summary: Create user\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/users/{userId}:\n");
        sb.append("    patch:\n");
        sb.append("      summary: Update user\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    delete:\n");
        sb.append("      summary: Soft delete user\n");
        sb.append("      responses:\n        '204':\n          description: Deleted\n");

        sb.append("  /manage/tenants/{tenantId}/users/{userId}/roles:\n");
        sb.append("    put:\n");
        sb.append("      summary: Update user roles\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/service-accounts:\n");
        sb.append("    get:\n");
        sb.append("      summary: List service accounts\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    post:\n");
        sb.append("      summary: Create service account\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/service-accounts/{accountId}/disable:\n");
        sb.append("    post:\n");
        sb.append("      summary: Disable service account\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/personal-api-keys:\n");
        sb.append("    get:\n");
        sb.append("      summary: Admin list of personal API keys for a tenant (no raw values)\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/personal-api-keys/{keyId}/revoke:\n");
        sb.append("    post:\n");
        sb.append("      summary: Admin revoke a personal key\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/settings/personal-api-keys:\n");
        sb.append("    get:\n");
        sb.append("      summary: Get global personal API key settings\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    put:\n");
        sb.append("      summary: Update global personal API key settings\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/service-accounts/{accountId}/rotate-secret:\n");
        sb.append("    post:\n");
        sb.append("      summary: Issue new credentials and invalidate old secret\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/invites:\n");
        sb.append("    get:\n");
        sb.append("      summary: List pending invites\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    post:\n");
        sb.append("      summary: Create invite\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/invites/{inviteId}:\n");
        sb.append("    parameters:\n");
        sb.append("      - name: inviteId\n");
        sb.append("        in: path\n");
        sb.append("        required: true\n");
        sb.append("        schema:\n");
        sb.append("          type: string\n");
        sb.append("    delete:\n");
        sb.append("      summary: Revoke invite\n");
        sb.append("      responses:\n        '204':\n          description: Revoked\n");

        sb.append("  /manage/tenants/{tenantId}/tenant-admins:\n");
        sb.append("    get:\n");
        sb.append("      summary: List users with TenantAdmin authority\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");

        sb.append("  /manage/tenants/{tenantId}/tenant-admins/{userId}:\n");
        sb.append("    parameters:\n");
        sb.append("      - name: userId\n");
        sb.append("        in: path\n");
        sb.append("        required: true\n");
        sb.append("        schema:\n");
        sb.append("          type: string\n");
        sb.append("    put:\n");
        sb.append("      summary: Grant TenantAdmin authority\n");
        sb.append("      responses:\n        '200':\n          description: OK\n");
        sb.append("    delete:\n");
        sb.append("      summary: Revoke TenantAdmin authority\n");
        sb.append("      responses:\n        '204':\n          description: Revoked\n");
    }

    private void appendAuditPaths(StringBuilder sb) {
        sb.append("  /manage/audit:\n");
        sb.append("    get:\n");
        sb.append("      summary: Get audit logs\n");
        sb.append("      description: Access requires TenantAdmin or SuperAdmin\n");
        sb.append("      responses:\n");
        sb.append("        '200':\n");
        sb.append("          description: Successful response\n");
    }
}
