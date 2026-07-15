package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class TokenLifecycleAndAdminComponentTest extends DemoApplicationTestSupport {

    @Test
    void logoutRevokesRefreshTokenFamily() throws Exception {
        // Step 1: Login as viewer@acme.com → extract refreshToken
        var loginResult = performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"viewer@acme.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String loginBody = loginResult.getResponse().getContentAsString();
        var tokenNode = MAPPER.readTree(loginBody).get("refreshToken");
        if (tokenNode == null) throw new RuntimeException("Missing refreshToken: " + loginBody);
        String refreshToken = tokenNode.asText();

        // Step 2: Logout with the refresh token → assert 204 No Content
        performElideRequest(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        // Step 3: Attempt to use the revoked refresh token → assert 401 Unauthorized
        performElideRequest(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantAdminCannotAccessSuperAdminApi() throws Exception {
        // Login as admin@acme.com (TenantAdmin, not SuperAdmin)
        String acmeAdminToken = getAcmeAdminToken();

        // GET /manage/tenants with tenant admin token → assert 403
        performElideRequest(get("/manage/tenants")
                .header("Authorization", acmeAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedCannotAccessSuperAdminApi() throws Exception {
        // GET /manage/tenants with NO Authorization header → assert 401
        performElideRequest(get("/manage/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void superAdminCanProvisionAndListTenants() throws Exception {
        String superAdminToken = getSuperAdminToken();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String tenantName = "test-org-" + suffix;

        // POST /manage/tenants → assert 201 Created
        performElideRequest(post("/manage/tenants")
                .header("Authorization", superAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantName\":\"" + tenantName + "\",\"initialAdminUsername\":\"admin@" + tenantName + ".local\",\"initialAdminPassword\":\"password\"}"))
                .andExpect(status().isCreated());

        // GET /manage/tenants → assert 200, paginated result contains the new tenant
        performElideRequest(get("/manage/tenants")
                .header("Authorization", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[?(@.name == '" + tenantName + "')]").exists());
    }
}
