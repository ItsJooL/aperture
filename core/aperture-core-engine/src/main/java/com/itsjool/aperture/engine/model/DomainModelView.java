package com.itsjool.aperture.engine.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DomainModelView {
    private final Map<String, EntityDef> entities;
    private final Map<String, OneOfDef> oneOfs;

    private DomainModelView(ResolvedDomainModel model) {
        this.entities = new LinkedHashMap<>();
        for (EntityDef entity : model.entities()) {
            entities.put(entity.name(), entity);
        }

        this.oneOfs = new LinkedHashMap<>();
        for (OneOfDef oneOf : model.oneOfs()) {
            oneOfs.put(oneOf.name(), oneOf);
        }
    }

    public static DomainModelView of(ResolvedDomainModel model) {
        return new DomainModelView(model);
    }

    public EntityDef entity(String entityName) {
        EntityDef entity = entities.get(entityName);
        if (entity == null) {
            throw new IllegalArgumentException("Unknown entity: " + entityName);
        }
        return entity;
    }

    public OneOfDef oneOf(String oneOfName) {
        OneOfDef oneOf = oneOfs.get(oneOfName);
        if (oneOf == null) {
            throw new IllegalArgumentException("Unknown oneof: " + oneOfName);
        }
        return oneOf;
    }

    public ResolvedField field(String entityName, String fieldName) {
        EntityDef entity = entity(entityName);
        FieldDef field = entity.fields() != null ? entity.fields().get(fieldName) : null;
        if (field == null) {
            throw new IllegalArgumentException("Unknown field: " + entityName + "." + fieldName);
        }

        if ("oneof".equalsIgnoreCase(field.type())) {
            OneOfDef oneOf = oneOf(field.targetClass());
            List<EntityDef> members = oneOf.members().stream()
                .map(this::entity)
                .toList();
            return new ResolvedOneOfField(entityName, fieldName, field, oneOf, members);
        }

        if (field.targetClass() != null) {
            return new ResolvedRefField(entityName, fieldName, field, entity(field.targetClass()));
        }

        return new ResolvedScalarField(entityName, fieldName, field);
    }
}
