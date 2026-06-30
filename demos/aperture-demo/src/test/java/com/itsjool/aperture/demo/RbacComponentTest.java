package com.itsjool.aperture.demo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class RbacComponentTest extends DemoApplicationTestSupport {

    @ParameterizedTest
    @MethodSource("com.itsjool.aperture.demo.YamlModelTestHelper#provideRbacTestCases")
    public void testRbac(String entityPath, String method, String role, int expectedStatus) throws Exception {
        // Generate real token for the role based on fixture credentials
        String username = switch (role) {
            case "TenantAdmin" -> "admin@acme.com";
            case "Accountant" -> "accountant@acme.com";
            case "Viewer" -> "viewer@acme.com";
            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
        String token = loginAs(username, "password");
        var resultActions = performElideRequest(request(HttpMethod.valueOf(method), "/api/v1/" + entityPath)
                .header("Authorization", token)
                .accept(MediaType.parseMediaType("application/vnd.api+json"))
                .contentType(MediaType.parseMediaType("application/vnd.api+json")))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print());

        resultActions.andExpect(status().is(expectedStatus));
        if (expectedStatus == 200 && "GET".equals(method)) {
            resultActions.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").exists());
        }
    }

    @org.junit.jupiter.api.Test
    public void testTestEndpoint() throws Exception {
        String token = loginAs("admin@acme.com", "password");
        var result = performElideRequest(request(HttpMethod.valueOf("GET"), "/api/test-endpoint")
                .header("Authorization", token)
                .accept(MediaType.parseMediaType("application/vnd.api+json"))
                .contentType(MediaType.parseMediaType("application/vnd.api+json")))
                .andReturn();
        System.out.println("DEBUG BODY: >>>" + result.getResponse().getContentAsString() + "<<<");
        System.out.println("DEBUG STATUS: >>>" + result.getResponse().getStatus() + "<<<");
        System.out.println("DEBUG ASYNC RESULT: >>>" + (result.getRequest().isAsyncStarted() ? result.getAsyncResult() : "NOT ASYNC") + "<<<");
    }
}
