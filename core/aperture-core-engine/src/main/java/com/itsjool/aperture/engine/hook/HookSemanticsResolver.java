package com.itsjool.aperture.engine.hook;

import com.itsjool.aperture.engine.model.HookDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class HookSemanticsResolver {

    private static final Set<String> VALID_OPERATIONS = Set.of("create", "update", "delete");
    private static final Set<String> VALID_FAILURE_MODES = Set.of("reject", "passthrough", "warn");

    // guard/validate are synchronous and block the API response; capped tighter than trigger.
    // See dev-notes/plans/032-configurable-hook-retries.md Step 5/Decisions for the latency math.
    private static final int MAX_SYNC_RETRIES = 2;
    // trigger is async (fire-and-forget, POSTCOMMIT) — retries never add latency to the client response.
    private static final int MAX_TRIGGER_RETRIES = 5;

    public HookSemantics resolve(String entityName, String hookName, HookDef hook) {
        if (hook.type() == null) {
            throw invalid(entityName, hookName, "must declare hook type");
        }
        String type = hook.type().toLowerCase(Locale.ROOT);
        int retries = hook.retries();
        if (retries < 0) {
            throw invalid(entityName, hookName, "retries must not be negative");
        }
        return switch (type) {
            case "guard" -> semantics(entityName, hookName, type, "PRESECURITY", false,
                hook.onFailure() != null ? hook.onFailure() : "reject",
                hook.on(), List.of("create", "update", "delete"),
                Set.of("create", "update", "delete"), false, Set.of("reject"), retries, MAX_SYNC_RETRIES);
            case "validate" -> semantics(entityName, hookName, type, "PRECOMMIT", false,
                hook.onFailure() != null ? hook.onFailure() : "reject",
                hook.on(), List.of("create", "update"), Set.of("create", "update"), false, Set.of("reject"),
                retries, MAX_SYNC_RETRIES);
            case "mutate" -> {
                // Excluded from retries in this pass: a retried enrichment call has no per-invocation
                // idempotency key to dedupe by (see dev-notes/plans/032-configurable-hook-retries.md,
                // STOP condition 2 finding), and mutate's response is applied straight to the persisted
                // entity, so a duplicated call can both double-apply an external side effect and bake in
                // data from whichever duplicate's response happens to arrive. Revisit once hook calls
                // carry a stable per-invocation id.
                if (retries > 0) {
                    throw invalid(entityName, hookName, "retries is not supported for mutate hooks: enrichment "
                        + "responses are applied directly to the persisted entity, and there is no "
                        + "per-invocation idempotency key today for a hook service to safely dedupe a "
                        + "retried call. Remove the retries field from this hook.");
                }
                yield semantics(entityName, hookName, type, "PRECOMMIT", false,
                    hook.onFailure() != null ? hook.onFailure() : "reject", hook.on(), List.of("create", "update"),
                    Set.of("create", "update"), true, Set.of("reject", "passthrough"), retries, 0);
            }
            case "trigger" -> semantics(entityName, hookName, type, "POSTCOMMIT", true,
                hook.onFailure() != null ? hook.onFailure() : "warn",
                hook.on(), List.of("create", "update", "delete"), Set.of("create", "update", "delete"),
                false, Set.of("warn"), retries, MAX_TRIGGER_RETRIES);
            default -> throw invalid(entityName, hookName, "unknown hook type '" + hook.type() + "'");
        };
    }

    private HookSemantics semantics(String entityName, String hookName, String type, String phase, boolean async,
                                    String onFailure, List<String> configuredOperations,
                                    List<String> defaultOperations, Set<String> allowedOperations, boolean enrichment,
                                    Set<String> allowedFailureModes, int retries, int maxRetries) {
        String normalizedFailureMode = onFailure.toLowerCase(Locale.ROOT);
        validateFailureMode(entityName, hookName, normalizedFailureMode, allowedFailureModes);
        List<String> operations = normalizeOperations(entityName, hookName, configuredOperations, defaultOperations);
        for (String operation : operations) {
            if (!allowedOperations.contains(operation)) {
                throw invalid(entityName, hookName, "operation '" + operation + "' is not allowed for " + type
                    + " hooks");
            }
        }
        if (retries > maxRetries) {
            throw invalid(entityName, hookName, "retries (" + retries + ") exceeds the maximum of " + maxRetries
                + " allowed for " + type + " hooks");
        }
        return new HookSemantics(type, phase, async, normalizedFailureMode, operations, enrichment, retries);
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
