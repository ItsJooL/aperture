package com.itsjool.aperture.engine.hook;

import com.itsjool.aperture.engine.model.HookDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HookSemanticsResolver {

    private static final Set<String> VALID_OPERATIONS = Set.of("create", "update", "delete");
    private static final Set<String> VALID_FAILURE_MODES = Set.of("reject", "passthrough", "warn");

    public HookSemantics resolve(String entityName, String hookName, HookDef hook) {
        if (hook.type() == null) {
            throw invalid(entityName, hookName, "must declare hook type");
        }
        String type = hook.type().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "guard" -> semantics(entityName, hookName, type, "PRESECURITY", false,
                hook.onFailure() != null ? hook.onFailure() : "reject",
                hook.on(), List.of("create", "update", "delete"),
                Set.of("create", "update", "delete"), false, Set.of("reject"));
            case "validate" -> semantics(entityName, hookName, type, "PRECOMMIT", false,
                hook.onFailure() != null ? hook.onFailure() : "reject",
                hook.on(), List.of("create", "update"), Set.of("create", "update"), false, Set.of("reject"));
            case "mutate" -> semantics(entityName, hookName, type, "PRECOMMIT", false,
                hook.onFailure() != null ? hook.onFailure() : "reject", hook.on(), List.of("create", "update"),
                Set.of("create", "update"), true, Set.of("reject", "passthrough"));
            case "trigger" -> semantics(entityName, hookName, type, "POSTCOMMIT", true,
                hook.onFailure() != null ? hook.onFailure() : "warn",
                hook.on(), List.of("create", "update", "delete"), Set.of("create", "update", "delete"),
                false, Set.of("warn"));
            default -> throw invalid(entityName, hookName, "unknown hook type '" + hook.type() + "'");
        };
    }

    private HookSemantics semantics(String entityName, String hookName, String type, String phase, boolean async,
                                    String onFailure, List<String> configuredOperations,
                                    List<String> defaultOperations, Set<String> allowedOperations, boolean enrichment,
                                    Set<String> allowedFailureModes) {
        String normalizedFailureMode = onFailure.toLowerCase(Locale.ROOT);
        validateFailureMode(entityName, hookName, normalizedFailureMode, allowedFailureModes);
        List<String> operations = normalizeOperations(entityName, hookName, configuredOperations, defaultOperations);
        for (String operation : operations) {
            if (!allowedOperations.contains(operation)) {
                throw invalid(entityName, hookName, "operation '" + operation + "' is not allowed for " + type
                    + " hooks");
            }
        }
        return new HookSemantics(type, phase, async, normalizedFailureMode, operations, enrichment);
    }

    private List<String> normalizeOperations(String entityName, String hookName, List<String> configuredOperations,
                                             List<String> defaultOperations) {
        List<String> operations = configuredOperations == null ? defaultOperations : configuredOperations;
        if (operations.isEmpty()) {
            throw invalid(entityName, hookName, "must declare at least one operation");
        }
        List<String> normalized = new ArrayList<>();
        for (String operation : operations) {
            String normalizedOperation = operation.toLowerCase(Locale.ROOT);
            if (!VALID_OPERATIONS.contains(normalizedOperation)) {
                throw invalid(entityName, hookName, "unknown operation '" + operation + "'");
            }
            if (normalized.contains(normalizedOperation)) {
                throw invalid(entityName, hookName, "duplicate operation '" + operation + "'");
            }
            normalized.add(normalizedOperation);
        }
        return List.copyOf(normalized);
    }

    private void validateFailureMode(String entityName, String hookName, String onFailure,
                                     Set<String> allowedFailureModes) {
        if (!VALID_FAILURE_MODES.contains(onFailure)) {
            throw invalid(entityName, hookName, "unknown onFailure mode '" + onFailure + "'");
        }
        if (!allowedFailureModes.contains(onFailure)) {
            throw invalid(entityName, hookName, "onFailure '" + onFailure + "' is not allowed for this hook type");
        }
    }

    private HookSemanticException invalid(String entityName, String hookName, String reason) {
        return new HookSemanticException("Invalid hook configuration (Entity " + entityName + " hook " + hookName
            + "): " + reason);
    }
}
