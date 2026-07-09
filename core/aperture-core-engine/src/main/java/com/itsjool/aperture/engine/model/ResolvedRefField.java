package com.itsjool.aperture.engine.model;

public record ResolvedRefField(
    String entityName,
    String fieldName,
    FieldDef field,
    EntityDef targetEntity
) implements ResolvedField {
    @Override
    public FieldKind kind() {
        return FieldKind.REF;
    }
}
