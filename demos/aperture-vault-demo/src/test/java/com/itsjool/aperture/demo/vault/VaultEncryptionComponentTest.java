package com.itsjool.aperture.demo.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.spi.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = VaultDemoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///postgres",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.cloud.compatibility-verifier.enabled=false"
    }
)
@Testcontainers
class VaultEncryptionComponentTest {

    private static final String ROOT_TOKEN = "root-token";
    private static final String TENANT_ID = "00000000-0000-0000-0000-000000000101";
    private static final String TENANT_NAME = "Clinic A";
    private static final String EMAIL = "doctor@example.test";
    private static final String PASSWORD = "password";
    private static final String SECRET_NOTES = "Top Secret Diagnosis";

    @Container
    static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.15")
            .withVaultToken(ROOT_TOKEN)
            .withInitCommand("secrets enable transit");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EncryptionService encryptionService;

    @Autowired(required = false)
    com.itsjool.aperture.auth.AuthController authController;

    TestRestTemplate rest;

    @DynamicPropertySource
    static void vaultProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.vault.uri", vault::getHttpHostAddress);
        registry.add("spring.cloud.vault.token", () -> ROOT_TOKEN);
        registry.add("spring.cloud.vault.kv.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        rest = new TestRestTemplate();
        seedTenantUserAndRole();
    }

    @Test
    void encryptedPatientIsSavedAsVaultCiphertextAndReturnedAsPlaintext() {
        assertThat(encryptionService).isInstanceOf(VaultEncryptionService.class);
        assertThat(authController).isNotNull();

        String token = loginAndReturnAccessToken();
        String patientId = createPatient(token);

        String rawDatabaseValue = jdbcTemplate.queryForObject(
                "select " + quoteIdentifier(patientNotesColumnName()) + " from aperture_patients where id = ?",
                String.class,
                UUID.fromString(patientId)
        );

        assertThat(rawDatabaseValue)
                .isNotBlank()
                .doesNotContain(SECRET_NOTES)
                .startsWith("vault:v");

        ResponseEntity<String> getResponse = rest.exchange(
                "http://localhost:" + port + "/api/v1/patients/" + patientId,
                HttpMethod.GET,
                bearer(token),
                String.class
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).contains(SECRET_NOTES);
    }

    private String seedTenantUserAndRole() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_users WHERE username = ?", Integer.class, EMAIL);
        if (count != null && count > 0) {
            return jdbcTemplate.queryForObject("SELECT id FROM aperture_users WHERE username = ?", String.class, EMAIL);
        }

        String userId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO aperture_tenants (id, name, status) VALUES (?, ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING", TENANT_ID, TENANT_NAME);
        
        String passwordHash = "$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM";
        jdbcTemplate.update("INSERT INTO aperture_users (id, username, password_hash, tenant_id, status) VALUES (?, ?, ?, ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING", userId, EMAIL, passwordHash, TENANT_ID);
        
        String roleId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES (?, ?, 'Doctor') ON CONFLICT (id) DO NOTHING", roleId, TENANT_ID);
        
        jdbcTemplate.update("INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (?, ?, 'Doctor') ON CONFLICT DO NOTHING", TENANT_ID, userId);
        return userId;
    }

    private String loginAndReturnAccessToken() {
        String body = "{\"username\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK) {
            throw new RuntimeException("Login failed with status " + response.getStatusCode() + " and body: " + response.getBody());
        }
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode root = new ObjectMapper().readTree(response.getBody());
            return root.get("accessToken").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createPatient(String token) {
        String body = """
                {
                  "data": {
                    "type": "patients",
                    "attributes": {
                      "name": "Jane",
                      "medical_history_notes": "%s"
                    }
                  }
                }
                """.formatted(SECRET_NOTES);

        ResponseEntity<String> response = rest.exchange(
                "http://localhost:" + port + "/api/v1/patients",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonApiBearerHeaders(token)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains(SECRET_NOTES);
        try {
            JsonNode root = new ObjectMapper().readTree(response.getBody());
            String id = root.path("data").path("id").asText();
            assertThat(id).isNotBlank();
            return id;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse created patient response: " + response.getBody(), e);
        }
    }

    private String patientNotesColumnName() {
        return jdbcTemplate.queryForObject("""
                select column_name
                from information_schema.columns
                where table_name = 'aperture_patients'
                  and column_name = 'medical_history_notes'
                """, String.class);
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private HttpEntity<Void> bearer(String token) {
        return new HttpEntity<>(jsonApiBearerHeaders(token));
    }

    private HttpHeaders jsonApiBearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.parseMediaType("application/vnd.api+json"));
        headers.setAccept(List.of(MediaType.parseMediaType("application/vnd.api+json")));
        headers.set("X-Tenant-ID", TENANT_ID);
        return headers;
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.vault.core.VaultOperations vaultOperations;

    @Test
    void vaultEncryptionServiceBindsCiphertextToTenantAndEntityContext() {
        VaultEncryptionService service = new VaultEncryptionService(vaultOperations, "context-test-key");
        com.itsjool.aperture.spi.EncryptionContext clinicA = new com.itsjool.aperture.spi.EncryptionContext("clinic-a", "Patient", "medical_history_notes", false);
        com.itsjool.aperture.spi.EncryptionContext clinicB = new com.itsjool.aperture.spi.EncryptionContext("clinic-b", "Patient", "medical_history_notes", false);

        com.itsjool.aperture.spi.EncryptedValue encrypted = service.encrypt("sensitive", clinicA);

        org.assertj.core.api.Assertions.assertThat(encrypted.value()).startsWith("vault:v");
        org.assertj.core.api.Assertions.assertThat(service.decrypt(encrypted, clinicA)).isEqualTo("sensitive");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.decrypt(encrypted, clinicB))
                .isInstanceOf(RuntimeException.class);
    }
}
