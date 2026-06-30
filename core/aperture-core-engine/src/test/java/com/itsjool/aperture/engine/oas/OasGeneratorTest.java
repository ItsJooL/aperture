package com.itsjool.aperture.engine.oas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OasGeneratorTest {

    @Test
    void generatesValidYamlWithExpectedPaths() throws Exception {
        EntityDef e1 = new EntityDef("Invoice", "Invoices", "Invoice", null, true, false, false, Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());
        EntityDef e2 = new EntityDef("User", "Users", "User", null, true, true, true, Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());

        ResolvedDomainModel model = new ResolvedDomainModel(List.of(e1, e2));

        OasGenerator gen = new OasGenerator();
        String yaml = gen.generate(model, TenancyMode.POOL, List.of("1"));

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> parsed = mapper.readValue(yaml, Map.class);

        assertThat(parsed).containsKey("openapi");
        assertThat(parsed).containsKey("info");
        assertThat(parsed).containsKey("paths");

        Map<String, Object> paths = (Map<String, Object>) parsed.get("paths");
        assertThat(paths).containsKey("/api/v1/invoices");
        assertThat(paths).containsKey("/api/v1/users/{id}");
        assertThat(paths).containsKey("/auth/login");
        assertThat(paths).containsKey("/auth/me");
        assertThat(paths).containsKey("/auth/me/api-keys");
        assertThat(paths).containsKey("/auth/token");
        assertThat(paths).containsKey("/manage/tenants");
        assertThat(paths).containsKey("/manage/tenants/{tenantId}/personal-api-keys");
        assertThat(paths).containsKey("/manage/settings/personal-api-keys");
        assertThat(paths).containsKey("/manage/audit");
        assertThat(paths).doesNotContainKey("/framework/audit");
        assertThat(paths).doesNotContainKey("/manage/tenants/{tenantId}/api-keys");
        
        // Optimistic locking
        Map<String, Object> userPatch = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/users/{id}")).get("patch");
        List<Map<String, Object>> patchParams = (List<Map<String, Object>>) userPatch.get("parameters");
        assertThat(patchParams).anySatisfy(p -> assertThat(p.get("name")).isEqualTo("If-Match"));
        
        // Soft delete
        Map<String, Object> userDelete = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/users/{id}")).get("delete");
        assertThat(userDelete.get("summary").toString()).contains("(soft delete)");
    }

    @Test
    void excludesManagePathsWhenTenancyModeNone() throws Exception {
        ResolvedDomainModel model = new ResolvedDomainModel(List.of());

        OasGenerator gen = new OasGenerator();
        String yaml = gen.generate(model, TenancyMode.NONE, List.of("1"));

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> parsed = mapper.readValue(yaml, Map.class);
        Map<String, Object> paths = (Map<String, Object>) parsed.get("paths");

        assertThat(paths).doesNotContainKey("/manage/tenants");
        assertThat(paths).containsKey("/auth/login");
    }
}
