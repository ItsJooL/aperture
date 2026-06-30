package com.itsjool.aperture.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantProvisioningCommandTest {
    @Test
    void redactsPasswordAndDeeplyCopiesJsonAttributesIncludingNulls() {
        List<Object> nested = new ArrayList<>(List.of("first"));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("nullable", null);
        attributes.put("nested", nested);

        TenantProvisioningCommand command = new TenantProvisioningCommand(
                "tenant-1", "Tenant One", "admin-1", "admin", "raw-secret", attributes, Map.of());
        nested.add("later");
        attributes.put("later", true);

        assertThat(command.toString()).doesNotContain("raw-secret").contains("[REDACTED]");
        assertThat(command.initialAdminPassword()).isEqualTo("raw-secret");
        assertThat(command.initialAdminProfile()).containsEntry("nullable", null)
                .doesNotContainKey("later");
        assertThat(command.initialAdminProfile().get("nested")).isEqualTo(List.of("first"));
        assertThatThrownBy(() -> command.initialAdminProfile().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) command.initialAdminProfile().get("nested")).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void provisioningResultsExposeNoCredentialMaterialAndAreImmutable() {
        TenantProvisioningResult result = new TenantProvisioningResult(
                new TenantRecord("tenant-1", "Tenant One", "ACTIVE"),
                new UserRecord("admin-1", "admin", "tenant-1", "ACTIVE", false,
                        Map.of("department", "ops"), Map.of()),
                List.of("TenantAdmin", "Viewer"));

        assertThat(result.toString()).doesNotContainIgnoringCase("password").doesNotContain("hash");
        assertThatThrownBy(() -> result.roles().add("Editor"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
