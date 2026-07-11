package com.itsjool.aperture.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.runtime.config.TenancyMode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class ApertureRuntimeMetadataLoaderTest {
    private final ApertureRuntimeMetadataLoader loader =
            new ApertureRuntimeMetadataLoader(new ObjectMapper());

    @Test
    void loadsRealisticMetadataAndPreservesDeterministicOrdering() {
        ApertureRuntimeMetadata metadata = loader.load(json("""
                {"activeVersions":["1","2"],
                 "defaultRoles":["TenantAdmin","Viewer"],
                 "declaredRoles":["Accountant","TenantAdmin","Viewer"]}
                """));

        assertThat(metadata.activeVersions()).containsExactly("1", "2");
        assertThat(metadata.defaultRoles()).containsExactly("TenantAdmin", "Viewer");
        assertThat(metadata.declaredRoles()).containsExactly("Accountant", "TenantAdmin", "Viewer");
    }

    @Test
    void rejectsMissingOrEmptyDefaults() {
        assertInvalid("{\"activeVersions\":[\"1\"],\"declaredRoles\":[\"TenantAdmin\"]}",
                "defaultRoles");
        assertInvalid("{\"activeVersions\":[\"1\"],\"defaultRoles\":[],"
                + "\"declaredRoles\":[\"TenantAdmin\"]}", "defaultRoles");
    }

    @Test
    void rejectsDuplicatesDefaultsOutsideDeclarations() {
        assertInvalid(metadata("[\"TenantAdmin\",\"TenantAdmin\"]", "[\"TenantAdmin\"]"),
                "duplicate");
        assertInvalid(metadata("[\"TenantAdmin\",\"Viewer\"]", "[\"TenantAdmin\"]"),
                "declared");
    }

    @Test
    void rejectsBlankOrDuplicateRolesAndVersions() {
        assertInvalid(metadata("[\"TenantAdmin\"]", "[\"TenantAdmin\",\" \"]"), "blank");
        assertInvalid(metadata("[\"TenantAdmin\"]", "[\"TenantAdmin\",\"TenantAdmin\"]"),
                "duplicate");
        assertInvalid("{\"activeVersions\":[\"1\",\"1\"],"
                + "\"defaultRoles\":[\"TenantAdmin\"],\"declaredRoles\":[\"TenantAdmin\"]}",
                "duplicate");
        assertInvalid("{\"activeVersions\":[\" \"],"
                + "\"defaultRoles\":[\"TenantAdmin\"],\"declaredRoles\":[\"TenantAdmin\"]}",
                "blank");
    }

    @Test
    void loadsTenancyModeNone() {
        ApertureRuntimeMetadata metadata = loader.load(json("""
                {"activeVersions":["1"],
                 "defaultRoles":["TenantAdmin"],
                 "declaredRoles":["TenantAdmin"],
                 "tenancyMode":"none"}
                """));
        assertThat(metadata.tenancyMode()).isEqualTo(TenancyMode.NONE);
    }

    @Test
    void loadsOneOfMetadata() {
        ApertureRuntimeMetadata metadata = loader.load(json("""
                {"activeVersions":["1"],
                 "defaultRoles":["TenantAdmin"],
                 "declaredRoles":["TenantAdmin"],
                 "oneOfs":{
                   "Billable":{
                     "name":"Billable",
                     "members":["Product","ServicePackage"],
                     "memberResourceTypes":["products","servicepackages"],
                     "fields":["lineitems.billable"]
                   }
                 }}
                """));

        ApertureRuntimeMetadata.OneOfMetadata billable = metadata.oneOfs().get("Billable");
        assertThat(billable.members()).containsExactly("Product", "ServicePackage");
        assertThat(billable.memberResourceTypes()).containsExactly("products", "servicepackages");
        assertThat(billable.fields()).containsExactly("lineitems.billable");
    }

    @Test
    void tenancyModeDefaultsToPoolWhenAbsent() {
        ApertureRuntimeMetadata metadata = loader.load(json("""
                {"activeVersions":["1"],
                 "defaultRoles":["TenantAdmin"],
                 "declaredRoles":["TenantAdmin"]}
                """));
        assertThat(metadata.tenancyMode()).isEqualTo(TenancyMode.POOL);
    }

    @Test
    void rejectsMalformedAndMissingResources() {
        assertThatThrownBy(() -> loader.load(json("not-json")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("metadata");
        assertThatThrownBy(() -> loader.load(new ClassPathResource("missing-runtime-metadata.json")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
    }

    private void assertInvalid(String value, String message) {
        assertThatThrownBy(() -> loader.load(json(value)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(exception -> assertThat(exception.getMessage().toLowerCase())
                        .contains(message.toLowerCase()));
    }

    private static String metadata(String defaults, String declared) {
        return "{\"activeVersions\":[\"1\"],\"defaultRoles\":" + defaults
                + ",\"declaredRoles\":" + declared + "}";
    }

    private static ByteArrayResource json(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8), "test metadata");
    }
}
