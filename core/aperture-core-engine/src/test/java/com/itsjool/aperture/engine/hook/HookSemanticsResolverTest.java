package com.itsjool.aperture.engine.hook;

import com.itsjool.aperture.engine.model.HookDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookSemanticsResolverTest {

    private final HookSemanticsResolver resolver = new HookSemanticsResolver();

    @Test
    void validateDefaultsToCreateAndUpdatePrecommitRejectSync() {
        HookSemantics semantics = resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", null, null, "http://hook"));

        assertThat(semantics.type()).isEqualTo("validate");
        assertThat(semantics.phase()).isEqualTo("PRECOMMIT");
        assertThat(semantics.async()).isFalse();
        assertThat(semantics.onFailure()).isEqualTo("reject");
        assertThat(semantics.operations()).containsExactly("create", "update");
        assertThat(semantics.enrichment()).isFalse();
    }

    @Test
    void mutateAllowsPassthroughAndUsesEnrichmentPath() {
        HookSemantics semantics = resolver.resolve("Customer", "NormalizeCustomer",
            new HookDef("mutate", List.of("create"), "passthrough", "http://hook"));

        assertThat(semantics.type()).isEqualTo("mutate");
        assertThat(semantics.phase()).isEqualTo("PRECOMMIT");
        assertThat(semantics.async()).isFalse();
        assertThat(semantics.onFailure()).isEqualTo("passthrough");
        assertThat(semantics.operations()).containsExactly("create");
        assertThat(semantics.enrichment()).isTrue();
    }

    @Test
    void triggerRejectsReadOperation() {
        assertThatThrownBy(() -> resolver.resolve("Supplier", "NotifySupplier",
            new HookDef("trigger", List.of("read"), null, "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("Supplier")
            .hasMessageContaining("NotifySupplier")
            .hasMessageContaining("read");
    }

    @Test
    void hookMustDeclareType() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef(null, null, null, "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("must declare hook type");
    }

    @Test
    void guardDefaultsToAllWriteOperations() {
        HookSemantics semantics = resolver.resolve("LineItem", "CheckLineItem",
            new HookDef("guard", null, null, "http://hook"));

        assertThat(semantics.type()).isEqualTo("guard");
        assertThat(semantics.phase()).isEqualTo("PRESECURITY");
        assertThat(semantics.async()).isFalse();
        assertThat(semantics.onFailure()).isEqualTo("reject");
        assertThat(semantics.operations()).containsExactly("create", "update", "delete");
        assertThat(semantics.enrichment()).isFalse();
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "BogusHook",
            new HookDef("bogus", null, null, "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("unknown hook type 'bogus'");
    }

    @Test
    void guardRejectsWarnFailureMode() {
        assertThatThrownBy(() -> resolver.resolve("LineItem", "CheckLineItem",
            new HookDef("guard", null, "warn", "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("warn");
    }

    @Test
    void triggerRejectsRejectFailureMode() {
        assertThatThrownBy(() -> resolver.resolve("Supplier", "NotifySupplier",
            new HookDef("trigger", null, "reject", "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("reject");
    }

    @Test
    void validateRejectsDeleteOperation() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", List.of("delete"), null, "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("delete")
            .hasMessageContaining("validate");
    }

    @Test
    void emptyOperationListIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", List.of(), null, "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("at least one operation");
    }

    @Test
    void duplicateOperationIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", List.of("create", "create"), null, "http://hook")))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("duplicate operation 'create'");
    }

    // ---------- retries ----------

    @Test
    void validateAcceptsRetriesUpToTheSynchronousCap() {
        HookSemantics semantics = resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", null, null, "http://hook", 2));

        assertThat(semantics.retries()).isEqualTo(2);
    }

    @Test
    void guardRejectsRetriesAboveTheSynchronousCap() {
        assertThatThrownBy(() -> resolver.resolve("LineItem", "CheckLineItem",
            new HookDef("guard", null, null, "http://hook", 3)))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("retries (3) exceeds the maximum of 2")
            .hasMessageContaining("guard");
    }

    @Test
    void validateRejectsRetriesAboveTheSynchronousCap() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", null, null, "http://hook", 3)))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("retries (3) exceeds the maximum of 2")
            .hasMessageContaining("validate");
    }

    @Test
    void triggerAcceptsRetriesUpToItsOwnHigherCap() {
        HookSemantics semantics = resolver.resolve("Supplier", "NotifySupplier",
            new HookDef("trigger", null, null, "http://hook", 5));

        assertThat(semantics.retries()).isEqualTo(5);
    }

    @Test
    void triggerRejectsRetriesAboveItsCap() {
        assertThatThrownBy(() -> resolver.resolve("Supplier", "NotifySupplier",
            new HookDef("trigger", null, null, "http://hook", 6)))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("retries (6) exceeds the maximum of 5")
            .hasMessageContaining("trigger");
    }

    @Test
    void mutateRejectsAnyRetriesAtAll() {
        assertThatThrownBy(() -> resolver.resolve("Customer", "NormalizeCustomer",
            new HookDef("mutate", List.of("create"), "passthrough", "http://hook", 1)))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("retries is not supported for mutate hooks")
            .hasMessageContaining("idempotency key");
    }

    @Test
    void mutateWithZeroRetriesIsUnaffected() {
        // retries: 0 is the same as omitting the field entirely — not an error.
        HookSemantics semantics = resolver.resolve("Customer", "NormalizeCustomer",
            new HookDef("mutate", List.of("create"), "passthrough", "http://hook", 0));

        assertThat(semantics.retries()).isEqualTo(0);
    }

    @Test
    void negativeRetriesIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("Invoice", "ValidateInvoice",
            new HookDef("validate", null, null, "http://hook", -1)))
            .isInstanceOf(HookSemanticException.class)
            .hasMessageContaining("retries must not be negative");
    }
}
