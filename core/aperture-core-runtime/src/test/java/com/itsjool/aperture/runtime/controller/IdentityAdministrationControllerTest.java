package com.itsjool.aperture.runtime.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.runtime.config.TenancyMode;
import com.itsjool.aperture.runtime.filter.AuthFilter;
import com.itsjool.aperture.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = IdentityAdministrationController.class)
@org.springframework.context.annotation.Import(AuthFilter.class)
@org.springframework.test.context.ContextConfiguration(classes = {IdentityAdministrationControllerTest.TestApp.class, IdentityAdministrationController.class, AuthFilter.class})
class IdentityAdministrationControllerTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication
    static class TestApp {}


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IdentityAdministrationProvider provider;

    @MockBean
    private CredentialValidator credentialValidator;

    @MockBean
    private PrincipalMapper principalMapper;

    @MockBean
    private ApertureRuntimeMetadata apertureRuntimeMetadata;

    @org.junit.jupiter.api.BeforeEach
    void configurePoolMode() {
        when(apertureRuntimeMetadata.tenancyMode()).thenReturn(TenancyMode.POOL);
    }

    private void mockAuthentication(String userId, String tenantId, Set<String> roles) {
        ValidationResult mockResult = mock(ValidationResult.class);
        when(mockResult.isValid()).thenReturn(true);
        when(credentialValidator.validate(any())).thenReturn(mockResult);

        boolean superAdmin = roles.contains("SuperAdmin");
        boolean tenantAdmin = roles.contains("TenantAdmin");
        // Domain roles must not contain platform authority strings
        Set<String> domainRoles = roles.stream()
                .filter(r -> !r.equals("SuperAdmin") && !r.equals("TenantAdmin"))
                .collect(java.util.stream.Collectors.toSet());
        AperturePrincipal principal = new AperturePrincipal(userId, tenantId, domainRoles,
                com.itsjool.aperture.spi.PrincipalKind.USER, Collections.emptyMap(), java.util.Map.of(),
                java.util.Set.of(), superAdmin, tenantAdmin);
        when(principalMapper.map(mockResult)).thenReturn(principal);
    }

    private void mockUnauthenticated() {
        ValidationResult mockResult = mock(ValidationResult.class);
        when(mockResult.isValid()).thenReturn(false);
        when(credentialValidator.validate(any())).thenReturn(mockResult);
    }

    @Test
    void testUnauthenticatedIs401() throws Exception {
        mockUnauthenticated();
        mockMvc.perform(get("/manage/tenants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testInsufficientRoleIs403() throws Exception {
        mockAuthentication("user1", "tenantA", Set.of("TenantAdmin")); // Missing SuperAdmin
        mockMvc.perform(get("/manage/tenants"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testCrossTenantAccessIs403() throws Exception {
        mockAuthentication("user1", "tenantA", Set.of("TenantAdmin"));
        mockMvc.perform(get("/manage/tenants/tenantB/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testTenantAdminCanAccessOwnTenant() throws Exception {
        mockAuthentication("user1", "tenantA", Set.of("TenantAdmin"));
        when(provider.listUsers(any(UserListQuery.class))).thenReturn(new PagedResult<>(Collections.emptyList(), 0, 20, 0));

        mockMvc.perform(get("/manage/tenants/tenantA/users"))
                .andExpect(status().isOk());
    }

    @Test
    void testSuperAdminCanAccessAnyTenant() throws Exception {
        mockAuthentication("user1", "tenantA", Set.of("SuperAdmin"));
        when(provider.listUsers(any(UserListQuery.class))).thenReturn(new PagedResult<>(Collections.emptyList(), 0, 20, 0));

        mockMvc.perform(get("/manage/tenants/tenantB/users"))
                .andExpect(status().isOk());
    }

    @Test
    void managementApiKeyCreateRouteIsNotExposed() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));

        mockMvc.perform(post("/manage/tenants/tenantA/api-keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("userId", "user2", "name", "admin-created-key"))))
                .andExpect(status().isNotFound());

        verify(provider, never()).createApiKey(any());
    }

    @Test
    void managementApiKeyDisableRouteIsNotExposed() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));

        mockMvc.perform(post("/manage/tenants/tenantA/api-keys/key-1/disable"))
                .andExpect(status().isNotFound());

        verify(provider, never()).disableApiKey(any(), any());
    }

    @Test
    void testProvisionTenant() throws Exception {
        mockAuthentication("user1", "tenantA", Set.of("SuperAdmin"));
        
        IdentityAdministrationController.TenantProvisioningRequest req = new IdentityAdministrationController.TenantProvisioningRequest();
        req.tenantName = "New Tenant";
        req.initialAdminUsername = "admin";
        req.initialAdminPassword = "password";

        TenantProvisioningResult mockResult = new TenantProvisioningResult(
                new TenantRecord("tenant-123", "New Tenant", "ACTIVE"),
                new UserRecord("user-123", "tenant-123", "admin", "ACTIVE", false, Map.of(), Map.of()),
                Collections.singletonList("TenantAdmin")
        );
        when(provider.provisionTenant(any())).thenReturn(mockResult);

        mockMvc.perform(post("/manage/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }

    @Test
    void tenantAdminCannotDeleteAnotherTenantAdmin() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));
        when(provider.isTenantAdmin("tenantA", "victim")).thenReturn(true);

        mockMvc.perform(delete("/manage/tenants/tenantA/users/victim"))
                .andExpect(status().isForbidden());

        verify(provider, never()).deleteUser(any(), any());
    }

    @Test
    void tenantAdminCannotModifyAnotherTenantAdmin() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));
        when(provider.isTenantAdmin("tenantA", "victim")).thenReturn(true);

        IdentityAdministrationController.UserUpdateRequest req = new IdentityAdministrationController.UserUpdateRequest();
        req.profile = Map.of("firstName", "hacked");

        mockMvc.perform(patch("/manage/tenants/tenantA/users/victim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verify(provider, never()).updateUser(any(), any(), any());
    }

    @Test
    void tenantAdminCannotAssignReservedRoles() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));
        when(provider.isTenantAdmin("tenantA", "user2")).thenReturn(false);

        IdentityAdministrationController.ReplaceUserRolesRequest req = new IdentityAdministrationController.ReplaceUserRolesRequest();
        req.roleNames = List.of("Viewer", "TenantAdmin");

        mockMvc.perform(put("/manage/tenants/tenantA/users/user2/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verify(provider, never()).replaceUserRoles(any(), any(), any());
    }

    @Test
    void tenantAdminCannotInviteWithReservedRoles() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));

        IdentityAdministrationController.InviteCreateRequest req = new IdentityAdministrationController.InviteCreateRequest();
        req.roleNames = List.of("SuperAdmin");
        req.expiresInHours = 24;

        mockMvc.perform(post("/manage/tenants/tenantA/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        verify(provider, never()).createInvite(any());
    }

    @Test
    void tenantAdminCannotAssignTenantAdminAuthority() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));

        mockMvc.perform(post("/manage/tenants/tenantA/tenant-admins/user2"))
                .andExpect(status().isForbidden());

        verify(provider, never()).assignTenantAdmin(any(), any());
    }

    @Test
    void tenantAdminCannotRemoveTenantAdminAuthority() throws Exception {
        mockAuthentication("admin1", "tenantA", Set.of("TenantAdmin"));

        mockMvc.perform(delete("/manage/tenants/tenantA/tenant-admins/user2"))
                .andExpect(status().isForbidden());

        verify(provider, never()).removeTenantAdmin(any(), any());
    }

    @Test
    void testUpdateUser() throws Exception {
        mockAuthentication("user1", "tenantA", Set.of("TenantAdmin"));
        
        IdentityAdministrationController.UserUpdateRequest req = new IdentityAdministrationController.UserUpdateRequest();
        req.profile = Map.of("department", "engineering");
        req.status = "INACTIVE";

        when(provider.getUser("tenantA", "user2")).thenReturn(java.util.Optional.of(
                new UserRecord("user2", "tenantA", "user2", "INACTIVE", false, req.profile, req.securityAttributes == null ? Map.of() : req.securityAttributes)
        ));

        mockMvc.perform(patch("/manage/tenants/tenantA/users/user2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.profile.department").value("engineering"));
                
        verify(provider).updateUser(eq("tenantA"), eq("user2"), any(UserUpdateCommand.class));
    }
}
