package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import com.itsjool.aperture.spi.IdentityAdministrationProvider;
import com.itsjool.aperture.spi.ServiceAccountIssuer;
import com.itsjool.aperture.spi.ServiceAccountRequest;
import com.itsjool.aperture.spi.ServiceAccountToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
    private AuthUserService users;
    private JwtTokenService jwt;
    private RefreshTokenService refreshTokens;
    private ServiceAccountIssuer serviceAccounts;
    private IdentityAdministrationProvider provider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserService.class);
        jwt = mock(JwtTokenService.class);
        refreshTokens = mock(RefreshTokenService.class);
        serviceAccounts = mock(ServiceAccountIssuer.class);
        provider = mock(IdentityAdministrationProvider.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(users, jwt, refreshTokens, serviceAccounts, provider)).build();
    }

    @Test
    void loginReturnsAccessAndOpaqueRefreshTokens() throws Exception {
        AuthenticatedAccount account = account("user-1");
        when(users.authenticate("viewer@example.com", "password")).thenReturn(Optional.of(account));
        when(jwt.generateToken(account)).thenReturn("access-token");
        when(refreshTokens.issue("user-1")).thenReturn("opaque-refresh");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viewer@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("opaque-refresh"));
    }

    @Test
    void refreshUsesStoredOwnerAndCurrentAuthorization() throws Exception {
        AuthenticatedAccount account = account("stored-user");
        when(users.loadActiveById("stored-user")).thenReturn(Optional.of(account));
        when(jwt.generateToken(account)).thenReturn("new-access");
        when(refreshTokens.rotate(
                org.mockito.ArgumentMatchers.eq("old-refresh"),
                org.mockito.ArgumentMatchers.<Function<String, String>>any()))
                .thenAnswer(invocation -> {
                    Function<String, String> continuation = invocation.getArgument(1);
                    return new RefreshTokenService.CompletedRotation<>(
                            continuation.apply("stored-user"), "new-refresh");
                });

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"));

        verify(users).loadActiveById("stored-user");
    }

    @Test
    void refreshWithMissingOrInactiveStoredAccountRejectsInsideRotation() throws Exception {
        when(users.loadActiveById("stored-user")).thenReturn(Optional.empty());
        when(refreshTokens.rotate(
                org.mockito.ArgumentMatchers.eq("old-refresh"),
                org.mockito.ArgumentMatchers.<Function<String, String>>any()))
                .thenAnswer(invocation -> {
                    Function<String, String> continuation = invocation.getArgument(1);
                    continuation.apply("stored-user");
                    throw new AssertionError("Continuation should reject the account");
                });

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        verifyNoInteractions(jwt);
    }

    @Test
    void jwtFailureOccursInsideRotationContinuation() {
        AuthenticatedAccount account = account("stored-user");
        when(users.loadActiveById("stored-user")).thenReturn(Optional.of(account));
        when(jwt.generateToken(account)).thenThrow(new IllegalStateException("JWT unavailable"));
        when(refreshTokens.rotate(
                org.mockito.ArgumentMatchers.eq("old-refresh"),
                org.mockito.ArgumentMatchers.<Function<String, String>>any()))
                .thenAnswer(invocation -> {
                    Function<String, String> continuation = invocation.getArgument(1);
                    continuation.apply("stored-user");
                    throw new AssertionError("JWT failure should escape the continuation");
                });

        assertThatThrownBy(() -> mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}")))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void logoutRevokesWholeFamilyAndReturnsNoContent() throws Exception {
        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"family-member\"}"))
                .andExpect(status().isNoContent());

        verify(refreshTokens).revokeFamily("family-member");
    }

    @Test
    void missingAndMalformedRequestsReturnStructuredBadRequest() throws Exception {
        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));

        verifyNoInteractions(users, jwt, refreshTokens);
    }

    @Test
    void invalidRefreshReturnsGenericUnauthorizedWithoutExceptionText() throws Exception {
        when(refreshTokens.rotate(
                org.mockito.ArgumentMatchers.eq("replayed"),
                org.mockito.ArgumentMatchers.<Function<String, String>>any()))
                .thenThrow(new RefreshTokenService.InvalidRefreshTokenException());

        String response = mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"replayed\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("Invalid refresh token", "exception");
        verify(users, never()).loadActiveById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectedCredentialsReturnGenericUnauthorized() throws Exception {
        when(users.authenticate("viewer@example.com", "wrong")).thenReturn(Optional.empty());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viewer@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));

        verifyNoInteractions(jwt, refreshTokens);
    }

    @Test
    void serviceAccountTokenReturnsTypedAccessTokenWithoutRefreshToken() throws Exception {
        when(serviceAccounts.issue(new ServiceAccountRequest("client-1", "secret")))
                .thenReturn(Optional.of(new ServiceAccountToken("client-1", "access-token")));

        mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-1\",\"clientSecret\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client-1"))
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void serviceAccountTokenValidatesBodyAndRejectsCredentialsGenerically() throws Exception {
        mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));

        when(serviceAccounts.issue(new ServiceAccountRequest("client-1", "wrong")))
                .thenReturn(Optional.empty());
        String response = mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-1\",\"clientSecret\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("wrong", "client-1", "exception");
    }

    @Test
    void serviceAccountDatabaseFailureReturnsGenericServerError() throws Exception {
        when(serviceAccounts.issue(new ServiceAccountRequest("client-1", "secret")))
                .thenThrow(new DataAccessResourceFailureException("database host secret-db"));

        String response = mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-1\",\"clientSecret\":\"secret\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("database", "secret-db", "exception");
    }

    @Test
    void testAuthEndpointsMoved() throws Exception {
        // Without authentication, this route should exist but return 401 Unauthorized
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me"))
               .andExpect(status().isUnauthorized());
    }

    private static AuthenticatedAccount account(String userId) {
        return new AuthenticatedAccount(
                userId, "viewer@example.com", "tenant-1", List.of("Viewer"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of(), false, false, false);
    }
}
