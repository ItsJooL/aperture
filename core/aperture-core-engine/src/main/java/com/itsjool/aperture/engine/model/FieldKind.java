package com.itsjool.aperture.engine.model;

public enum FieldKind {
    SCALAR,
    REF,
    ONEOF;

    public static FieldKind from(FieldDef field) {
        if (field == null) {
            throw new IllegalArgumentException("Field definition is required");
        }
        if ("oneof".equalsIgnoreCase(field.type())) {
            return ONEOF;
        }
        if ("ref".equalsIgnoreCase(field.type()) || field.targetClass() != null) {
            return REF;
        }
        return SCALAR;
    }
}
