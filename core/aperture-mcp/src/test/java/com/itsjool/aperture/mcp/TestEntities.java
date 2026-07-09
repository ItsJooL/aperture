package com.itsjool.aperture.mcp;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;

import java.util.Map;

/**
 * Reusable EntityDef/FieldDef fixtures for McpToolGenerator tests.
 */
final class TestEntities {

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
            null, null, null, null, null
        );
    }

    static EntityDef simpleWidget() {
        return new EntityDef(
            "Widget", "widgets", null, null,
            false, false, false,
            Map.of("name", requiredStringField(null)),
            null, null, null, null, null
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
            null, null, null, null, null
        );
    }
}
