package com.itsjool.aperture.engine.diff;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiffEngineTest {

    @Test
    void tenantScopedToggleIsBreaking() {
        EntityDef oldCustomer = new EntityDef("Customer", "customers", null, null, false, false, false,
            Map.of("name", new FieldDef("String", true, false, false, false, "1", null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef newCustomer = new EntityDef("Customer", "customers", null, null, false, false, true,
            Map.of("name", new FieldDef("String", true, false, false, false, "1", null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldCustomer)),
            new ResolvedDomainModel(List.of(newCustomer)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void targetClassChangeIsBreaking() {
        EntityDef oldInvoice = new EntityDef("Invoice", "invoices", null, null, false, false, true,
            Map.of("customer", new FieldDef("ref", true, false, false, false, "1", null, null, "ManyToOne", "Customer", null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef newInvoice = new EntityDef("Invoice", "invoices", null, null, false, false, true,
            Map.of("customer", new FieldDef("ref", true, false, false, false, "1", null, null, "ManyToOne", "Supplier", null, null)),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldInvoice)),
            new ResolvedDomainModel(List.of(newInvoice)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void softDeleteToggleIsBreaking() {
        EntityDef withoutSoftDelete = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("ref", new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef withSoftDelete = new EntityDef("Order", "orders", null, null, false, true, false,
            Map.of("ref", new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(withoutSoftDelete)),
            new ResolvedDomainModel(List.of(withSoftDelete)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void optimisticLockingToggleIsBreaking() {
        EntityDef withoutLock = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("ref", new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef withLock = new EntityDef("Order", "orders", null, null, true, false, false,
            Map.of("ref", new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(withoutLock)),
            new ResolvedDomainModel(List.of(withLock)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void fieldRemovedWithoutRemovedIn_isBreaking() {
        EntityDef oldOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(
                "ref", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
                "notes", new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null)
            ), null, null, null, Map.of(), Map.of());
        EntityDef newOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("ref", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldOrder)),
            new ResolvedDomainModel(List.of(newOrder)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void fieldRemovedWithRemovedIn_allVersionsSatisfied_isSafeAndTrackedAsDeferredDrop() {
        FieldDef legacyField = new FieldDef("String", false, false, false, false, null, "2", null, null, null, null, null);
        EntityDef oldOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(
                "ref", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
                "legacy", legacyField
            ), null, null, null, Map.of(), Map.of());
        EntityDef newOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("ref", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        // All active versions (2, 3) >= removedIn (2) → safe deferred drop
        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldOrder)),
            new ResolvedDomainModel(List.of(newOrder)),
            List.of("2", "3"));

        assertThat(diff.hasBreakingChanges()).isFalse();
        assertThat(diff.deferredDrops()).containsKey("Order");
        assertThat(diff.deferredDrops().get("Order")).containsKey("legacy");
    }

    @Test
    void fieldRemovedWithRemovedIn_olderVersionStillActive_isBreaking() {
        FieldDef legacyField = new FieldDef("String", false, false, false, false, null, "3", null, null, null, null, null);
        EntityDef oldOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of(
                "ref", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null),
                "legacy", legacyField
            ), null, null, null, Map.of(), Map.of());
        EntityDef newOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("ref", new FieldDef("String", true, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        // Active versions include v2 which is < removedIn (3) → breaking
        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldOrder)),
            new ResolvedDomainModel(List.of(newOrder)),
            List.of("2", "3"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void fieldRenamedViaRenamedFrom_trackedAsRenameNotAddOrRemove() {
        EntityDef oldOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("unitPrice", new FieldDef("decimal", false, false, false, false, null, null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef newOrder = new EntityDef("Order", "orders", null, null, false, false, false,
            Map.of("unit_price", new FieldDef("decimal", false, false, false, false, null, null, "unitPrice", null, null, null, null)),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldOrder)),
            new ResolvedDomainModel(List.of(newOrder)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isFalse();
        assertThat(diff.renamedFields()).containsKey("Order");
        assertThat(diff.addedFields()).doesNotContainKey("Order");
    }

    @Test
    void addedScopedByOnExistingEntityIsBreaking() {
        EntityDef oldTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, null);
        EntityDef newTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "project");

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldTask)),
            new ResolvedDomainModel(List.of(newTask)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void removedScopedByOnExistingEntityIsNonBreaking() {
        EntityDef oldTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "project");
        EntityDef newTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, null);

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldTask)),
            new ResolvedDomainModel(List.of(newTask)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isFalse();
    }

    @Test
    void changedScopedByFieldOnExistingEntityIsBreaking() {
        EntityDef oldTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of(
                "project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null),
                "team", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Team", null, null)
            ), null, null, null, null, null, "project");
        EntityDef newTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of(
                "project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null),
                "team", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Team", null, null)
            ), null, null, null, null, null, "team");

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldTask)),
            new ResolvedDomainModel(List.of(newTask)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isTrue();
    }

    @Test
    void unchangedScopedByProducesNoBreakingFinding() {
        EntityDef oldTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "project");
        EntityDef newTask = new EntityDef("Task", "tasks", null, null, false, false, false,
            Map.of("project", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Project", null, null)),
            null, null, null, null, null, "project");

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(oldTask)),
            new ResolvedDomainModel(List.of(newTask)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isFalse();
    }

    @Test
    void addedOwningReferenceFieldIsSafeButRequiresColumnAndFk() {
        EntityDef customer = new EntityDef("Customer", "customers", null, null, false, false, true,
            Map.of("name", new FieldDef("String", true, false, false, false, "1", null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef oldInvoice = new EntityDef("Invoice", "invoices", null, null, false, false, true,
            Map.of("amount", new FieldDef("decimal", true, false, false, false, "1", null, null, null, null, null, null)),
            null, null, null, Map.of(), Map.of());
        EntityDef newInvoice = new EntityDef("Invoice", "invoices", null, null, false, false, true,
            Map.of(
                "amount", new FieldDef("decimal", true, false, false, false, "1", null, null, null, null, null, null),
                "customer", new FieldDef("ref", true, false, false, false, "1", null, null, "ManyToOne", "Customer", null, null)
            ),
            null, null, null, Map.of(), Map.of());

        DiffResult diff = new DiffEngine().computeDiff(
            new ResolvedDomainModel(List.of(customer, oldInvoice)),
            new ResolvedDomainModel(List.of(customer, newInvoice)),
            List.of("1"));

        assertThat(diff.hasBreakingChanges()).isFalse();
        assertThat(diff.addedFields()).containsKey("Invoice");
        assertThat(diff.addedFields().get("Invoice")).containsKey("customer");
        assertThat(diff.allEntities()).containsKey("Customer");
    }
}
