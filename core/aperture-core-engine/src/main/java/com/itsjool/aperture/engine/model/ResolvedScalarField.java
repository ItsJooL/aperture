package com.itsjool.aperture.engine.model;

public record ResolvedScalarField(
    String entityName,
    String fieldName,
    FieldDef field
) implements ResolvedField {
    @Override
    public FieldKind kind() {
        return FieldKind.SCALAR;
    }
}
