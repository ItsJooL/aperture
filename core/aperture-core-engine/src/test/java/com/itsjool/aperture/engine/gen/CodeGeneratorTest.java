package com.itsjool.aperture.engine.gen;

import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.HookDef;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {
    @Test
    void generatesApiVersionOnPackageOnly() {
        EntityDef entity = new EntityDef("Customer", "Customers", null, null, false, false, false,
            Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());

        List<String> classes = new CodeGenerator()
            .generateForEntity(entity, TenancyMode.NONE, List.of("2"));

        assertThat(classes).anySatisfy(source -> assertThat(source)
            .contains("@com.yahoo.elide.annotation.ApiVersion(version = \"2\")")
            .contains("package com.itsjool.aperture.generated.v2;"));
        assertThat(classes.stream().filter(source -> source.contains("public class CustomerV2")))
            .singleElement()
            .satisfies(source -> assertThat(source).doesNotContain("@ApiVersion"));
    }

    @Test
    void generatesEntityAndSecurityClasses() {
        EntityDef entity = new EntityDef("Customer", "Customers", null, null, true, true, true, Map.of(
            "email", new FieldDef("String", true, true, false, false, "1", null, null, null, null, null, null),
            "ssn", new FieldDef("String", false, false, false, true, "1", null, null, null, null, null, null)
        ), null, Map.of("ViewerRead", List.of("read")), null, null, Map.of("EnrichCustomer", new HookDef("PRECREATE", false, "passthrough", "http://hook")));

        CodeGenerator generator = new CodeGenerator();
        List<String> classes = generator.generateForEntity(entity, TenancyMode.POOL, List.of("1", "2"));
        List<String> policyClasses = generator.generatePolicyChecks(List.of(
            new com.itsjool.aperture.engine.model.AbacPolicyDef("ViewerRead", "#user.roles.contains('Viewer')")
        ));
        classes.addAll(policyClasses);

        String joined = String.join("\n\n", classes);
        assertThat(joined).contains("public class CustomerV1");
        assertThat(joined).contains("public class CustomerV2");
        assertThat(joined).contains("com.yahoo.elide.annotation.Include");
        assertThat(joined).contains("private UUID apertureTenantId;");
        assertThat(joined).contains("@Convert(\n      converter = CustomerSsnConverter.class\n  )");
        assertThat(joined).contains("public class CustomerSsnConverter implements AttributeConverter<String, String>");
        assertThat(joined).contains("public class CustomerV2TenantFilter extends FilterExpressionCheck<CustomerV2>");
        assertThat(joined).contains("public class CustomerV2SoftDeleteFilter extends FilterExpressionCheck<CustomerV2>");
        assertThat(joined).contains("@Component");
        assertThat(joined).contains("public class ViewerReadCheck extends OperationCheck");
        assertThat(joined).contains("public class CustomerV1EnrichCustomerHook implements LifeCycleHook<CustomerV1>");
        assertThat(joined).contains("principal.tenantId()");
    }

    @Test
    void since_fieldAbsentFromEarlierVersions_presentFromTargetVersion() {
        EntityDef entity = new EntityDef("Product", "products", null, null, false, false, false, Map.of(
            "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
            "sku", new FieldDef("String", true, false, false, false, "2", null, null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        CodeGenerator generator = new CodeGenerator();
        List<String> v1Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        List<String> v2Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("2"));

        String v1Source = String.join("\n", v1Classes);
        String v2Source = String.join("\n", v2Classes);

        assertThat(v1Source).contains("ProductV1").doesNotContain("private String sku");
        assertThat(v2Source).contains("ProductV2").contains("private String sku");
    }

    @Test
    void removedIn_fieldPresentBeforeTargetVersion_absentFromTargetVersion() {
        EntityDef entity = new EntityDef("Product", "products", null, null, false, false, false, Map.of(
            "name", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
            "legacyCode", new FieldDef("String", false, false, false, false, null, "2", null, null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        CodeGenerator generator = new CodeGenerator();
        List<String> v1Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        List<String> v2Classes = generator.generateForEntity(entity, TenancyMode.NONE, List.of("2"));

        String v1Source = String.join("\n", v1Classes);
        String v2Source = String.join("\n", v2Classes);

        assertThat(v1Source).contains("private String legacyCode");
        assertThat(v2Source).doesNotContain("legacyCode");
    }

    @Test
    void renamedFrom_fieldGeneratesWithNewJavaName() {
        // renamedFrom only drives the Liquibase migration — CodeGenerator generates the Java field
        // using the new (correct) name; the column rename is handled by ChangesetGenerator
        EntityDef entity = new EntityDef("Product", "products", null, null, false, false, false, Map.of(
            "unit_price", new FieldDef("decimal", false, false, false, false, null, null, "unitPrice", null, null, null, null)
        ), null, null, null, Map.of(), Map.of());

        List<String> classes = new CodeGenerator().generateForEntity(entity, TenancyMode.NONE, List.of("1"));
        String joined = String.join("\n", classes);

        assertThat(joined).contains("private BigDecimal unit_price");
        assertThat(joined).doesNotContain("unitPrice");
    }

    @Test
    void tenantScopedManyToOneEmitsSingleJoinColumn() {
        EntityDef invoice = new EntityDef("Invoice", "invoices", null, null, false, false, true, Map.of(
            "customer", new FieldDef("ref", true, false, false, false, "1", null, null, "ManyToOne", "Customer", null, null)
        ), null, null, null, Map.of(), Map.of());

        CodeGenerator generator = new CodeGenerator();
        List<String> classes = generator.generateForEntity(invoice, TenancyMode.POOL, List.of("1"));

        String joined = String.join("\n\n", classes);
        assertThat(joined).contains("@ManyToOne");
        assertThat(joined).contains("@JoinColumn(")
            .contains("name = \"customer_id\"");
        assertThat(joined).doesNotContain("@JoinColumns");
        assertThat(joined).contains("private CustomerV1 customer;");
    }

    @Test
    void tenantScopedPermissionsUseGeneratedAdminCheckNamesOnce() {
        EntityDef customer = new EntityDef("Customer", "customers", null, null, false, false, true,
                Map.of(), Map.of("Viewer", List.of("read")), Map.of(), List.of(), Map.of(), Map.of());

        List<String> classes = new CodeGenerator().generateForEntity(customer, TenancyMode.POOL, List.of("1"));
        String joined = String.join("\n\n", classes);

        assertThat(joined).contains("expression = \"SuperAdminCheck OR (CustomerV1TenantFilter AND (TenantAdminCheck OR (CustomerViewerCheck)))\"");
        assertThat(joined).doesNotContain("SuperAdmin OR TenantAdmin");
    }
}
