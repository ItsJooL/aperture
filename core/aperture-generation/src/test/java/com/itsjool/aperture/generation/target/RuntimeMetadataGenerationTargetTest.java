package com.itsjool.aperture.generation.target;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.FrameworkConfigDef;
import com.itsjool.aperture.engine.model.OneOfDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeMetadataGenerationTargetTest {
    @TempDir
    Path tempDir;

    @Test
    void emitsOneOfSetsMembersResourceTypesAndTargetingFields() throws Exception {
        EntityDef product = entity("Product", null, Map.of());
        EntityDef servicePackage = entity("ServicePackage", "ServicePackages", Map.of());
        EntityDef lineItem = entity("LineItem", null, Map.of(
            "billable", new FieldDef("oneof", false, false, false, false,
                null, null, null, null, "Billable", null, null)));
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(product, servicePackage, lineItem),
            List.of(),
            new FrameworkConfigDef(List.of("TenantAdmin"), TenancyMode.POOL, null, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new OneOfDef("Billable", List.of("Product", "ServicePackage"))));
        ApertureGenerationRequest request = new ApertureGenerationRequest(
            model, null, null, List.of("1"), TenancyMode.POOL, null);
        StagingGenerationContext context = new StagingGenerationContext(
            tempDir.resolve("sources"), tempDir.resolve("resources"), tempDir.resolve("locks"));

        new RuntimeMetadataGenerationTarget().generate(request, context);

        String json = Files.readString(tempDir.resolve("resources/aperture/aperture-runtime-metadata.json"));
        Map<String, Object> metadata = new ObjectMapper().readValue(json, Map.class);
        Map<String, Object> oneOfs = (Map<String, Object>) metadata.get("oneOfs");
        Map<String, Object> billable = (Map<String, Object>) oneOfs.get("Billable");

        assertThat((List<String>) billable.get("members")).containsExactly("Product", "ServicePackage");
        assertThat((List<String>) billable.get("memberResourceTypes")).containsExactly("products", "servicepackages");
        assertThat((List<String>) billable.get("fields")).containsExactly("lineitems.billable");
    }

    private static EntityDef entity(String name, String plural, Map<String, FieldDef> fields) {
        return new EntityDef(name, plural, name, null, false, false, false,
            fields, Map.of(), Map.of(), List.of(), Map.of(), Map.of());
    }
}
