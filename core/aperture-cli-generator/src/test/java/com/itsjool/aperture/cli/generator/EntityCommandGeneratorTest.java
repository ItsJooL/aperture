package com.itsjool.aperture.cli.generator;

import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityCommandGeneratorTest {

    @Test
    void generatesEntitySubcommandsUnderEachVerb() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of(
            "name", field("string", true),
            "price", field("decimal", false)
        ));
        EntityCommandGenerator gen = new EntityCommandGenerator(List.of(entity), Map.of());

        assertThat(gen.generateGetCommand()).contains("class GetCommand").contains("name = \"widgets\"").contains("class Widget");
        assertThat(gen.generateCreateCommand()).contains("class CreateCommand").contains("name = \"widgets\"").contains("class Widget");
        assertThat(gen.generateUpdateCommand()).contains("class UpdateCommand").contains("name = \"widgets\"").contains("class Widget");
        assertThat(gen.generateDeleteCommand()).contains("class DeleteCommand").contains("name = \"widgets\"").contains("class Widget");
    }

    @Test
    void requiredFieldsValidatedManuallyInRunMethod() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of(
            "name", field("string", true),
            "description", field("string", false)
        ));
        String source = new EntityCommandGenerator(List.of(entity), Map.of()).generateCreateCommand();

        // required = true must NOT appear in annotations (would block --help)
        assertThat(source).doesNotContain("required = true");
        // instead, manual null-check appears inside run()
        assertThat(source).contains("Missing required option: --name");
        // optional field does not get a null-check
        assertThat(source).doesNotContain("Missing required option: --description");
    }

    @Test
    void optimisticLockingAddsIfMatchOption() {
        EntityDef entity = entity("Widget", "widgets", true, Map.of(
            "name", field("string", true)
        ));
        EntityCommandGenerator gen = new EntityCommandGenerator(List.of(entity), Map.of());

        assertThat(gen.generateUpdateCommand()).contains("--if-match");
        assertThat(gen.generateDeleteCommand()).contains("--if-match");
    }

    @Test
    void noOptimisticLocking_noIfMatchOption() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of(
            "name", field("string", true)
        ));
        EntityCommandGenerator gen = new EntityCommandGenerator(List.of(entity), Map.of());

        assertThat(gen.generateUpdateCommand()).doesNotContain("--if-match");
        assertThat(gen.generateDeleteCommand()).doesNotContain("--if-match");
    }

    @Test
    void resourcePathUsedInApiCalls() {
        EntityDef entity = entity("Invoice", "invoices", false, Map.of());
        String source = new EntityCommandGenerator(List.of(entity), Map.of()).generateGetCommand();

        assertThat(source).contains("/invoices");
    }

    @Test
    void pageParamsAreUrlEncoded() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of());
        String source = new EntityCommandGenerator(List.of(entity), Map.of()).generateGetCommand();

        // Square brackets must be percent-encoded so Tomcat accepts them
        assertThat(source).contains("page%5Bnumber%5D");
        assertThat(source).contains("page%5Bsize%5D");
        assertThat(source).doesNotContain("page[number]");
    }

    @Test
    void noApiVersionUsesUnversionedPath() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of());
        String source = new EntityCommandGenerator(List.of(entity), Map.of()).generateGetCommand();

        // versionPrefix logic present in generated code
        assertThat(source).contains("versionPrefix");
        assertThat(source).contains("(version != null && !version.isBlank())");
        // Fallback path when no version set
        assertThat(source).contains("\"/api\"");
    }

    @Test
    void relationshipTypeUsesCustomPluralFromRegistry() {
        // Currency's declared plural is "Currencies" — the naive name+"s" fallback would
        // produce the wrong JSON:API type "currencys" and the server would reject it.
        EntityDef entity = entity("Product", "products", false, Map.of(
            "currency", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Currency", null, null)
        ));
        String source = new EntityCommandGenerator(List.of(entity), Map.of("currency", "currencies")).generateCreateCommand();

        assertThat(source).contains("\"currencies\"");
        assertThat(source).doesNotContain("\"currencys\"");
    }

    @Test
    void relationshipTypeFallsBackToNaivePluralForUnknownTarget() {
        EntityDef entity = entity("Product", "products", false, Map.of(
            "widget", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Widget", null, null)
        ));
        String source = new EntityCommandGenerator(List.of(entity), Map.of()).generateCreateCommand();

        assertThat(source).contains("\"widgets\"");
    }

    @Test
    void datetimeFieldMapsToString() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of(
            "createdAt", field("datetime", false)
        ));
        String source = new EntityCommandGenerator(List.of(entity), Map.of()).generateCreateCommand();

        // datetime maps to String option (to avoid LocalDateTime parsing issues for first slice)
        assertThat(source).contains("String createdAt");
    }

    @Test
    void everyEntityVerbCommandCallsSharedApplyParentOverridesOnFileOps() {
        // applyParentOverrides() itself lives once on the generated FileOps class (see
        // fileOpsDefinesSharedApplyParentOverrides below) — every verb command's entity
        // subcommand (and its -f file-mode path, and ApplyCommand) just calls it, so the
        // --server/--tenant/--scope override/precedence logic isn't re-parsed per call site.
        EntityDef entity = entity("Widget", "widgets", false, Map.of());
        EntityCommandGenerator generator = new EntityCommandGenerator(List.of(entity), Map.of());

        assertThat(generator.generateGetCommand()).contains("FileOps.applyParentOverrides(parent.root.global, profile)");
        // Create/Update/Delete each have two call sites: the entity subcommand (parent.root...)
        // and the -f file-mode path on the top-level verb command (root... — no "parent" field
        // there, it's the top-level command itself).
        assertThat(generator.generateCreateCommand())
            .contains("FileOps.applyParentOverrides(parent.root.global, profile)")
            .contains("FileOps.applyParentOverrides(root.global, profile)");
        assertThat(generator.generateUpdateCommand())
            .contains("FileOps.applyParentOverrides(parent.root.global, profile)")
            .contains("FileOps.applyParentOverrides(root.global, profile)");
        assertThat(generator.generateDeleteCommand())
            .contains("FileOps.applyParentOverrides(parent.root.global, profile)")
            .contains("FileOps.applyParentOverrides(root.global, profile)");
    }

    @Test
    void fileOpsDefinesSharedApplyParentOverrides() {
        EntityDef entity = entity("Widget", "widgets", false, Map.of());
        String source = CliTemplates.fileOps(List.of(entity));

        assertThat(source).contains("public static void applyParentOverrides(");
        assertThat(source).contains("for (String entry : global.scope)");
        // Field name must be lowercased on the way into the map so "--scope Project=x" and
        // "--scope project=x" collapse to the same entry (the server also lowercases the header
        // suffix it reads off X-Aperture-Scope-<Field>).
        assertThat(source).contains("entry.substring(0, eq).trim().toLowerCase()");
        assertThat(source).contains("profile.scopes.put(field, value)");
    }

    @Test
    void multipleEntitiesEachGetOwnSubcommandUnderSameVerb() {
        EntityDef widget = entity("Widget", "widgets", false, Map.of());
        EntityDef invoice = entity("Invoice", "invoices", false, Map.of());
        String source = new EntityCommandGenerator(List.of(widget, invoice), Map.of()).generateGetCommand();

        assertThat(source).contains("class Widget").contains("class Invoice");
        assertThat(source).contains("name = \"widgets\"").contains("name = \"invoices\"");
    }

    private EntityDef entity(String name, String plural, boolean optimisticLocking, Map<String, FieldDef> fields) {
        return new EntityDef(name, plural, null, null, optimisticLocking, false, false,
            fields, null, null, null, null, null);
    }

    private FieldDef field(String type, boolean required) {
        return new FieldDef(type, required, false, false, false, null, null, null, null, null, null, null);
    }
}
