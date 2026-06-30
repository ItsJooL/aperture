package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class UserLifecycleComponentTest extends DemoApplicationTestSupport {

    // ── 1. Forced-password-change flow ─────────────────────────────────────

    @Test
    void forcedPasswordChangeFlow() throws Exception {
        String superAdmin = getSuperAdminToken();

        // Provision a fresh tenant
        String tenantId = UUID.randomUUID().toString();
        String adminUsername = "lc-admin-" + tenantId.substring(0, 8) + "@example.com";
        performElideRequest(post("/manage/tenants")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new java.util.HashMap<>() {{
                    put("tenantId", tenantId);
                    put("tenantName", "lc-tenant-" + tenantId.substring(0, 8));
                    put("initialAdminUsername", adminUsername);
                    put("initialAdminPassword", "Adm!n1234");
                }})))
                .andExpect(status().isCreated());

        // Create user without password (generatePassword=true)
        String createBody = "{\"username\":\"forcechange@example.com\",\"generatePassword\":true}";
        var createResult = performElideRequest(post("/manage/tenants/" + tenantId + "/users")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createJson = MAPPER.readTree(createResult.getResponse().getContentAsString());
        assertThat(createJson.has("temporaryPassword")).isTrue();
        String temporaryPassword = createJson.get("temporaryPassword").asText();
        assertThat(temporaryPassword).isNotBlank();

        // User logs in with temporaryPassword → forcePasswordChange=true in response
        var loginResult = performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"forcechange@example.com\",\"password\":\"" + temporaryPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forcePasswordChange").value(true))
                .andReturn();

        JsonNode loginJson = MAPPER.readTree(loginResult.getResponse().getContentAsString());
        String forceChangeToken = "Bearer " + loginJson.get("accessToken").asText();

        // Force-change token rejected on a data endpoint (403)
        performElideRequest(get("/auth/me")
                .header("Authorization", forceChangeToken))
                .andExpect(status().isForbidden());

        // User calls change-password with correct current password → 200
        var changeResult = performElideRequest(post("/auth/change-password")
                .header("Authorization", forceChangeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + temporaryPassword + "\",\"newPassword\":\"NewP@ss123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();

        // Login with new password succeeds, no forcePasswordChange in response
        var newLoginResult = performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"forcechange@example.com\",\"password\":\"NewP@ss123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode newLoginJson = MAPPER.readTree(newLoginResult.getResponse().getContentAsString());
        assertThat(newLoginJson.has("forcePasswordChange") && newLoginJson.get("forcePasswordChange").asBoolean())
                .isFalse();
    }

    // ── 2. Invite flow ──────────────────────────────────────────────────────

    @Test
    void inviteFlow() throws Exception {
        String superAdmin = getSuperAdminToken();

        // Provision fresh tenant
        String tenantId = UUID.randomUUID().toString();
        String adminUsername = "invite-admin-" + tenantId.substring(0, 8) + "@example.com";
        performElideRequest(post("/manage/tenants")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + tenantId + "\",\"tenantName\":\"invite-tenant-" + tenantId.substring(0, 8) + "\",\"initialAdminUsername\":\"" + adminUsername + "\",\"initialAdminPassword\":\"Adm!n1234\"}"))
                .andExpect(status().isCreated());

        String adminToken = loginAs(adminUsername, "Adm!n1234");

        // Create invite
        var inviteResult = performElideRequest(post("/manage/tenants/" + tenantId + "/invites")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleNames\":[\"Viewer\"],\"expiresInHours\":72}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode inviteJson = MAPPER.readTree(inviteResult.getResponse().getContentAsString());
        String rawToken = inviteJson.get("token").asText();
        String inviteId = inviteJson.get("inviteId").asText();
        assertThat(rawToken).isNotBlank();

        // Verify token is NOT stored directly in DB (only hash)
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource());
        Integer directTokenCount = namedJdbc.queryForObject(
                "SELECT COUNT(*) FROM aperture_invites WHERE token_hash = :token",
                new MapSqlParameterSource("token", rawToken), Integer.class);
        assertThat(directTokenCount).isEqualTo(0);

        // User accepts invite → gets login response
        var acceptResult = performElideRequest(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\",\"username\":\"invited@example.com\",\"password\":\"MyP@ss123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();

        // Subsequent login with supplied password succeeds
        performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"invited@example.com\",\"password\":\"MyP@ss123\"}"))
                .andExpect(status().isOk());

        // Accepting again (used invite) → 400
        performElideRequest(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\",\"username\":\"invited2@example.com\",\"password\":\"MyP@ss123\"}"))
                .andExpect(status().isBadRequest());

        // Expired invite → 400
        String expiredTenantId = UUID.randomUUID().toString();
        String expiredAdminUsername = "expired-admin-" + expiredTenantId.substring(0, 8) + "@example.com";
        performElideRequest(post("/manage/tenants")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + expiredTenantId + "\",\"tenantName\":\"expired-tenant-" + expiredTenantId.substring(0, 8) + "\",\"initialAdminUsername\":\"" + expiredAdminUsername + "\",\"initialAdminPassword\":\"Adm!n1234\"}"))
                .andExpect(status().isCreated());

        String expiredAdminToken = loginAs(expiredAdminUsername, "Adm!n1234");
        var expiredInviteResult = performElideRequest(post("/manage/tenants/" + expiredTenantId + "/invites")
                .header("Authorization", expiredAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleNames\":[],\"expiresInHours\":0}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode expiredInviteJson = MAPPER.readTree(expiredInviteResult.getResponse().getContentAsString());
        String expiredToken = expiredInviteJson.get("token").asText();

        // Accept expired invite → 400
        performElideRequest(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + expiredToken + "\",\"username\":\"lateuser@example.com\",\"password\":\"MyP@ss123\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 3. Self-service attribute merge ────────────────────────────────────

    @Test
    void selfServiceAttributeMergePatchPreservesOtherAttributes() throws Exception {
        String superAdmin = getSuperAdminToken();

        // Admin sets two user-editable profile fields
        performElideRequest(patch("/manage/tenants/00000000-0000-0000-0000-000000000001/users/10000000-0000-0000-0000-000000000003")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":{\"firstName\":\"Alice\",\"lastName\":\"Smith\"}}"))
                .andExpect(status().isOk());

        // Re-login to get fresh token with updated profile
        String token = loginAs("viewer@acme.com", "password");

        // User patches only firstName via /auth/me — lastName must be preserved (merge, not replace)
        performElideRequest(patch("/auth/me")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":{\"firstName\":\"Bob\"}}"))
                .andExpect(status().isOk());

        // Verify firstName updated and lastName preserved by merge
        var meResult = performElideRequest(get("/auth/me")
                .header("Authorization", loginAs("viewer@acme.com", "password")))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode meJson = MAPPER.readTree(meResult.getResponse().getContentAsString());
        assertThat(meJson.get("profile").get("firstName").asText()).isEqualTo("Bob");
        assertThat(meJson.get("profile").get("lastName").asText()).isEqualTo("Smith");
    }

    // ── 4. Soft-delete ──────────────────────────────────────────────────────

    @Test
    void softDeleteUserPreventsLoginAndHidesFromList() throws Exception {
        String superAdmin = getSuperAdminToken();

        // Provision fresh tenant with a user
        String tenantId = UUID.randomUUID().toString();
        String adminUsername = "del-admin-" + tenantId.substring(0, 8) + "@example.com";
        performElideRequest(post("/manage/tenants")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + tenantId + "\",\"tenantName\":\"del-tenant-" + tenantId.substring(0, 8) + "\",\"initialAdminUsername\":\"" + adminUsername + "\",\"initialAdminPassword\":\"Adm!n1234\"}"))
                .andExpect(status().isCreated());

        String userId = UUID.randomUUID().toString();
        String userUsername = "to-be-deleted-" + tenantId.substring(0, 8) + "@example.com";
        performElideRequest(post("/manage/tenants/" + tenantId + "/users")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"username\":\"" + userUsername + "\",\"password\":\"UserP@ss123\"}"))
                .andExpect(status().isCreated());

        // Login succeeds before delete
        performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + userUsername + "\",\"password\":\"UserP@ss123\"}"))
                .andExpect(status().isOk());

        // Admin deletes user
        performElideRequest(delete("/manage/tenants/" + tenantId + "/users/" + userId)
                .header("Authorization", superAdmin))
                .andExpect(status().isNoContent());

        // Deleted user cannot log in (401)
        performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + userUsername + "\",\"password\":\"UserP@ss123\"}"))
                .andExpect(status().isUnauthorized());

        // Deleted user not in list
        var listResult = performElideRequest(get("/manage/tenants/" + tenantId + "/users")
                .header("Authorization", superAdmin))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode listJson = MAPPER.readTree(listResult.getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode item : listJson.get("items")) {
            if (userId.equals(item.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).isFalse();
    }

    // ── 5. Pagination ───────────────────────────────────────────────────────

    @Test
    void paginationWithSearchAndPageing() throws Exception {
        String superAdmin = getSuperAdminToken();

        // Provision fresh tenant
        String tenantId = UUID.randomUUID().toString();
        String adminUsername = "page-admin-" + tenantId.substring(0, 8) + "@example.com";
        performElideRequest(post("/manage/tenants")
                .header("Authorization", superAdmin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + tenantId + "\",\"tenantName\":\"page-tenant-" + tenantId.substring(0, 8) + "\",\"initialAdminUsername\":\"" + adminUsername + "\",\"initialAdminPassword\":\"Adm!n1234\"}"))
                .andExpect(status().isCreated());

        // Create 5 users with predictable usernames
        for (int i = 1; i <= 5; i++) {
            performElideRequest(post("/manage/tenants/" + tenantId + "/users")
                    .header("Authorization", superAdmin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"pageuser" + i + "@example.com\",\"password\":\"P@ss" + i + "1234\"}"))
                    .andExpect(status().isCreated());
        }

        // GET with size=3 → items has 3, total=6 (5 users + 1 admin)
        var page0Result = performElideRequest(get("/manage/tenants/" + tenantId + "/users?size=3&page=0")
                .header("Authorization", superAdmin))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode page0 = MAPPER.readTree(page0Result.getResponse().getContentAsString());
        assertThat(page0.get("items").size()).isEqualTo(3);
        assertThat(page0.get("total").asLong()).isEqualTo(6);
        assertThat(page0.get("size").asInt()).isEqualTo(3);

        // Page 1 → items has ≤3
        var page1Result = performElideRequest(get("/manage/tenants/" + tenantId + "/users?size=3&page=1")
                .header("Authorization", superAdmin))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode page1 = MAPPER.readTree(page1Result.getResponse().getContentAsString());
        assertThat(page1.get("items").size()).isLessThanOrEqualTo(3);
        assertThat(page1.get("items").size()).isGreaterThan(0);

        // Search by prefix
        var searchResult = performElideRequest(get("/manage/tenants/" + tenantId + "/users?search=pageuser")
                .header("Authorization", superAdmin))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode searchJson = MAPPER.readTree(searchResult.getResponse().getContentAsString());
        assertThat(searchJson.get("total").asLong()).isEqualTo(5);
        for (JsonNode item : searchJson.get("items")) {
            assertThat(item.get("username").asText()).contains("pageuser");
        }
    }
}
