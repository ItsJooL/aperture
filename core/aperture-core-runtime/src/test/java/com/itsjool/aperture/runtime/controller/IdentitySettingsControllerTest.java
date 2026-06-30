package com.itsjool.aperture.runtime.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import com.itsjool.aperture.runtime.config.TenancyMode;
import com.itsjool.aperture.runtime.filter.AuthFilter;
import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.CredentialValidator;
import com.itsjool.aperture.spi.IdentityAdministrationProvider;
import com.itsjool.aperture.spi.PersonalApiKeySettings;
import com.itsjool.aperture.spi.PrincipalKind;
import com.itsjool.aperture.spi.PrincipalMapper;
import com.itsjool.aperture.spi.ValidationResult;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IdentitySettingsController.class)
@Import(AuthFilter.class)
@ContextConfiguration(classes = {IdentitySettingsControllerTest.TestApp.class, IdentitySettingsController.class, AuthFilter.class})
class IdentitySettingsControllerTest {

    @SpringBootApplication
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

    @BeforeEach
    void configurePoolMode() {
        when(apertureRuntimeMetadata.tenancyMode()).thenReturn(TenancyMode.POOL);
    }

    @Test
    void superAdminCanReadGlobalPersonalApiKeySettings() throws Exception {
        mockAuthentication("root", "platform", Set.of("SuperAdmin"));
        when(provider.getGlobalPersonalApiKeySettings())
                .thenReturn(new PersonalApiKeySettings(true, false, 30, 90));

        mockMvc.perform(get("/manage/settings/personal-api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowNonExpiring").value(false))
                .andExpect(jsonPath("$.defaultTtlDays").value(30))
                .andExpect(jsonPath("$.maxTtlDays").value(90));
    }

    @Test
    void tenantAdminCannotReadGlobalPersonalApiKeySettings() throws Exception {
        mockAuthentication("admin", "tenantA", Set.of("TenantAdmin"));

        mockMvc.perform(get("/manage/settings/personal-api-keys"))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanUpdateGlobalPersonalApiKeySettings() throws Exception {
        mockAuthentication("root", "platform", Set.of("SuperAdmin"));
        PersonalApiKeySettings settings = new PersonalApiKeySettings(false, false, 14, 30);
        when(provider.getGlobalPersonalApiKeySettings()).thenReturn(settings);

        mockMvc.perform(put("/manage/settings/personal-api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.allowNonExpiring").value(false))
                .andExpect(jsonPath("$.defaultTtlDays").value(14))
                .andExpect(jsonPath("$.maxTtlDays").value(30));

        verify(provider).updateGlobalPersonalApiKeySettings(settings);
    }

    private void mockAuthentication(String userId, String tenantId, Set<String> roles) {
        ValidationResult mockResult = mock(ValidationResult.class);
        when(mockResult.isValid()).thenReturn(true);
        when(credentialValidator.validate(any())).thenReturn(mockResult);

        AperturePrincipal principal = new AperturePrincipal(
                userId,
                tenantId,
                Collections.emptySet(),
                PrincipalKind.USER,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                roles.contains("SuperAdmin"),
                roles.contains("TenantAdmin"));
        when(principalMapper.map(mockResult)).thenReturn(principal);
    }
}
