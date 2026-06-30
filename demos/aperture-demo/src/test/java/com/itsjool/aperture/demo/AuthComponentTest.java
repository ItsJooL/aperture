package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AuthComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testLogin() throws Exception {
        performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin@acme.com\",\"password\":\"password\"}"))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    public void testInvalidLogin() throws Exception {
        performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin@acme.com\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testServiceAccountAuth() throws Exception {
        performElideRequest(post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"sa-acme-1\",\"clientSecret\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.clientId").value("sa-acme-1"));
    }

    @Test
    void disabledUserCannotLogin() throws Exception {
        // Arrange: disable the acme accountant user directly via SQL
        jdbcTemplate.update(
            "UPDATE aperture_users SET status = 'DISABLED' WHERE username = 'accountant@acme.com'");
        try {
            // Act + Assert
            performElideRequest(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"accountant@acme.com\",\"password\":\"password\"}"))
                .andExpect(status().isUnauthorized());
        } finally {
            // Restore
            jdbcTemplate.update(
                "UPDATE aperture_users SET status = 'ACTIVE' WHERE username = 'accountant@acme.com'");
        }
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        performElideRequest(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin@acme.com\",\"password\":\"wrongpassword\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledServiceAccountCannotGetToken() throws Exception {
        // Arrange: disable the acme service account
        jdbcTemplate.update(
            "UPDATE aperture_service_accounts SET status = 'DISABLED' WHERE client_id = 'sa-acme-1'");
        try {
            // Act + Assert
            performElideRequest(post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"sa-acme-1\",\"clientSecret\":\"password\"}"))
                .andExpect(status().isUnauthorized());
        } finally {
            // Restore
            jdbcTemplate.update(
                "UPDATE aperture_service_accounts SET status = 'ACTIVE' WHERE client_id = 'sa-acme-1'");
        }
    }

    @Test
    void revokedRefreshTokenCannotBeUsed() throws Exception {
        // Step 1: Login to get tokens
        var loginResult = performElideRequest(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"viewer@acme.com\",\"password\":\"password\"}"))
            .andExpect(status().isOk())
            .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        var refreshTokenNode = MAPPER.readTree(responseBody).get("refreshToken");
        if (refreshTokenNode == null) {
            throw new RuntimeException("Missing refreshToken in login response: " + responseBody);
        }
        String refreshToken = refreshTokenNode.asText();

        // Step 2: Rotate the refresh token — this marks the original as used
        performElideRequest(post("/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk());

        // Step 3: Try to use the original refresh token again — must fail
        performElideRequest(post("/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isUnauthorized());
    }
}
