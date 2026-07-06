package com.itsjool.aperture.engine.diff;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DiffEngine {

    public DiffResult computeDiff(ResolvedDomainModel oldModel, ResolvedDomainModel newModel, List<String> activeVersions) {
        Map<String, EntityDef> oldEntities = oldModel.entities().stream().collect(Collectors.toMap(EntityDef::name, e -> e));
        Map<String, EntityDef> newEntities = newModel.entities().stream().collect(Collectors.toMap(EntityDef::name, e -> e));

        List<EntityDef> added = new ArrayList<>();
        List<EntityDef> modified = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Map<String, Map<String, FieldDef>> addedFields = new HashMap<>();
        Map<String, Map<String, FieldDef>> deferredDrops = new HashMap<>();
        Map<String, Map<String, FieldDef>> renamedFields = new HashMap<>();
        ChangeType classification = ChangeType.SAFE;

        for (EntityDef newEntity : newEntities.values()) {
            EntityDef oldEntity = oldEntities.get(newEntity.name());
            if (oldEntity == null) {
                added.add(newEntity);
            } else {
                Map<String, FieldDef> oldFields = oldEntity.fields() == null ? new HashMap<>() : oldEntity.fields();
                Map<String, FieldDef> newFields = newEntity.fields() == null ? new HashMap<>() : newEntity.fields();

                for (Map.Entry<String, FieldDef> entry : newFields.entrySet()) {
                    String fieldName = entry.getKey();
                    FieldDef newField = entry.getValue();
                    FieldDef oldField = oldFields.get(fieldName);
                    
                    if (oldField == null && newField.renamedFrom() != null) {
                        oldField = oldFields.get(newField.renamedFrom());
                        if (oldField != null) {
                            renamedFields.computeIfAbsent(newEntity.name(), k -> new HashMap<>()).put(fieldName, newField);
                        }
                    }
                    
                    if (oldField == null) {
                        addedFields.computeIfAbsent(newEntity.name(), k -> new HashMap<>()).put(fieldName, newField);
                    } else {
                        if (!oldField.type().equals(newField.type()) || (!oldField.required() && newField.required())) {
                            classification = ChangeType.BREAKING;
                        }
                        if (!java.util.Objects.equals(oldField.relation(), newField.relation())
                            || !java.util.Objects.equals(oldField.targetClass(), newField.targetClass())
                            || !java.util.Objects.equals(oldField.mappedBy(), newField.mappedBy())) {
                            classification = ChangeType.BREAKING;
                        }
                    }
                }
                
                for (Map.Entry<String, FieldDef> entry : oldFields.entrySet()) {
                    String oldFieldName = entry.getKey();
                    FieldDef oldField = entry.getValue();
                    boolean found = newFields.containsKey(oldFieldName) || 
                                    newFields.values().stream().anyMatch(f -> oldFieldName.equals(f.renamedFrom()));
                    if (!found) {
                        if (oldField.removedIn() != null) {
                            int removalVersion = Integer.parseInt(oldField.removedIn());
                            boolean safelyRemoved = activeVersions.stream().map(Integer::parseInt).allMatch(v -> v >= removalVersion);
                            if (safelyRemoved) {
                                deferredDrops.computeIfAbsent(oldEntity.name(), k -> new HashMap<>()).put(oldFieldName, oldField);
                            } else {
                                classification = ChangeType.BREAKING;
                            }
                        } else {
                            classification = ChangeType.BREAKING;
                        }
                    }
                }
                if (oldEntity.tenantScoped() != newEntity.tenantScoped()) {
                    classification = ChangeType.BREAKING;
                }
                if (oldEntity.softDelete() != newEntity.softDelete()) {
                    classification = ChangeType.BREAKING;
                }
                if (oldEntity.optimisticLocking() != newEntity.optimisticLocking()) {
                    classification = ChangeType.BREAKING;
                }
                // scopedBy filters are fail-closed: adding one to an already-published entity
                // (or repointing it at a different field) 403s every existing client that
                // doesn't yet send the X-Aperture-Scope-<Field> header, so both are breaking.
                // Removing scopedBy only widens access and is safe.
                if (oldEntity.scopedBy() == null && newEntity.scopedBy() != null) {
                    classification = ChangeType.BREAKING;
                } else if (oldEntity.scopedBy() != null && newEntity.scopedBy() != null
                        && !oldEntity.scopedBy().equals(newEntity.scopedBy())) {
                    classification = ChangeType.BREAKING;
                }

                if (!oldEntity.equals(newEntity)) modified.add(newEntity);
            }
        }

        for (String oldName : oldEntities.keySet()) {
            if (!newEntities.containsKey(oldName)) {
                removed.add(oldName);
                classification = ChangeType.BREAKING;
            }
        }

        Map<String, EntityDef> allEntities = newEntities;
        return new DiffResult(added, modified, removed, addedFields, deferredDrops, renamedFields, classification, allEntities);
    }
}
