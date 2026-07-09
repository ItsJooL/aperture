package com.itsjool.aperture.engine.model;

import java.util.List;

public record ResolvedOneOfField(
    String entityName,
    String fieldName,
    FieldDef field,
    OneOfDef oneOf,
    List<EntityDef> members
) implements ResolvedField {
    public ResolvedOneOfField {
        members = members != null ? List.copyOf(members) : List.of();
    }

    @Override
    public FieldKind kind() {
        return FieldKind.ONEOF;
    }
}
