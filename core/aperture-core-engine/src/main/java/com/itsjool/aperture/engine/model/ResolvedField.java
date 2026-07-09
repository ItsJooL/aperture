package com.itsjool.aperture.engine.model;

public interface ResolvedField {
    String entityName();

    String fieldName();

    FieldDef field();

    FieldKind kind();
}
