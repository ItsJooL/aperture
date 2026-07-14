package com.itsjool.aperture.mcp;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;

import java.util.List;
import java.util.Map;

/**
 * Reusable EntityDef/FieldDef fixtures for McpToolGenerator tests.
 *
 * <p>Under plan 016, the effective MCP tool set is {@code derived(entity) ∩ ceiling ∩ narrowing},
 * where {@code derived(entity)} comes from the entity's own {@code permissions}/{@code policies}/
 * {@code publicOperations}. These fixtures grant a role full CRUD so tests that exercise the
 * generator's other behavior (descriptions, params, relationships) aren't incidentally testing
 * the derivation itself — the ceiling and narrowing parameters passed into each test still do the
 * work of restricting which of those fully-derived tools actually get generated.
 */
final class TestEntities {

    /** Grants a single role full CRUD, so every manifest operation is reachable. */
    static final Map<String, List<String>> FULL_CRUD_PERMISSIONS =
        Map.of("Admin", List.of("read", "create", "update", "delete"));

    private TestEntities() {}

    static FieldDef stringField(String description) {
        return new FieldDef("String", false, false, false, false, null, null, null, null, null, null, description);
    }

    static FieldDef requiredStringField(String description) {
        return new FieldDef("String", true, false, false, false, null, null, null, null, null, null, description);
    }

    static FieldDef manyToOneField(String targetClass) {
        return new FieldDef("UUID", false, false, false, false, null, null, null, "ManyToOne", targetClass, null, null);
    }

    static FieldDef requiredManyToOneField(String targetClass) {
        return new FieldDef("UUID", true, false, false, false, null, null, null, "ManyToOne", targetClass, null, null);
    }

    static FieldDef oneToManyField(String mappedBy) {
        return new FieldDef("List", false, false, false, false, null, null, null, "OneToMany", null, mappedBy, null);
    }

    static EntityDef simpleCustomer() {
        return new EntityDef(
            "Customer", "customers", "A customer record", null,
            false, false, false,
            Map.of(
                "name", requiredStringField("Legal name"),
                "email", stringField("Contact email")
            ),
            FULL_CRUD_PERMISSIONS, null, null, null, null
        );
    }

    static EntityDef simpleWidget() {
        return new EntityDef(
            "Widget", "widgets", null, null,
            false, false, false,
            Map.of("name", requiredStringField(null)),
            FULL_CRUD_PERMISSIONS, null, null, null, null
        );
    }

    static EntityDef taskWithProjectRelation() {
        return new EntityDef(
            "Task", "Tasks", null, null,
            false, false, false,
            Map.of(
                "title", requiredStringField(null),
                "project", requiredManyToOneField("Project")
            ),
            FULL_CRUD_PERMISSIONS, null, null, null, null
        );
    }
}
