package com.itsjool.aperture.engine.diff;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.OneOfDef;
import java.util.List;
import java.util.Map;

public record DiffResult(
    List<EntityDef> addedEntities,
    List<EntityDef> modifiedEntities,
    List<String> removedEntities,
    Map<String, Map<String, FieldDef>> addedFields,
    Map<String, Map<String, FieldDef>> deferredDrops,
    Map<String, Map<String, FieldDef>> renamedFields,
    ChangeType overallClassification,
    Map<String, EntityDef> allEntities,
    List<OneOfDef> addedOneOfs,
    List<OneOfDef> modifiedOneOfs,
    List<String> removedOneOfs
) {
    public DiffResult {
        addedEntities = addedEntities != null ? List.copyOf(addedEntities) : List.of();
        modifiedEntities = modifiedEntities != null ? List.copyOf(modifiedEntities) : List.of();
        removedEntities = removedEntities != null ? List.copyOf(removedEntities) : List.of();
        addedFields = addedFields != null ? Map.copyOf(addedFields) : Map.of();
        deferredDrops = deferredDrops != null ? Map.copyOf(deferredDrops) : Map.of();
        renamedFields = renamedFields != null ? Map.copyOf(renamedFields) : Map.of();
        allEntities = allEntities != null ? Map.copyOf(allEntities) : Map.of();
        addedOneOfs = addedOneOfs != null ? List.copyOf(addedOneOfs) : List.of();
        modifiedOneOfs = modifiedOneOfs != null ? List.copyOf(modifiedOneOfs) : List.of();
        removedOneOfs = removedOneOfs != null ? List.copyOf(removedOneOfs) : List.of();
    }

    public DiffResult(
        List<EntityDef> addedEntities,
        List<EntityDef> modifiedEntities,
        List<String> removedEntities,
        Map<String, Map<String, FieldDef>> addedFields,
        Map<String, Map<String, FieldDef>> deferredDrops,
        Map<String, Map<String, FieldDef>> renamedFields,
        ChangeType overallClassification
    ) {
        this(addedEntities, modifiedEntities, removedEntities, addedFields, deferredDrops, renamedFields,
            overallClassification, Map.of());
    }

    public DiffResult(
        List<EntityDef> addedEntities,
        List<EntityDef> modifiedEntities,
        List<String> removedEntities,
        Map<String, Map<String, FieldDef>> addedFields,
        Map<String, Map<String, FieldDef>> deferredDrops,
        Map<String, Map<String, FieldDef>> renamedFields,
        ChangeType overallClassification,
        Map<String, EntityDef> allEntities
    ) {
        this(addedEntities, modifiedEntities, removedEntities, addedFields, deferredDrops, renamedFields,
            overallClassification, allEntities, List.of(), List.of(), List.of());
    }

    public boolean hasBreakingChanges() { return overallClassification == ChangeType.BREAKING; }
}
