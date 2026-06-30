package com.itsjool.aperture.engine.diff;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
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
    Map<String, EntityDef> allEntities
) {
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

    public boolean hasBreakingChanges() { return overallClassification == ChangeType.BREAKING; }
}
