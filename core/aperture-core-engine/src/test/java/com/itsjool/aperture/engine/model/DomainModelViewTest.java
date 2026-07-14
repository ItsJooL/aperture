package com.itsjool.aperture.engine.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelViewTest {

    @Test
    void classifiesManifestFieldsThroughOneCanonicalDecoder() {
        assertThat(FieldKind.from(stringField())).isEqualTo(FieldKind.SCALAR);
        assertThat(FieldKind.from(manyToOneField("Product"))).isEqualTo(FieldKind.REF);
        assertThat(FieldKind.from(oneOfField("Billable"))).isEqualTo(FieldKind.ONEOF);
    }

    @Test
    void resolvesScalarReferenceAndOneOfFields() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        EntityDef lineItem = entity("LineItem", Map.of(
            "description", stringField(),
            "product", manyToOneField("Product"),
            "billable", oneOfField("Billable")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));
        ResolvedDomainModel model = new ResolvedDomainModel(
            List.of(product, servicePackage, lineItem), List.of(), null,
            List.of(), List.of(), List.of(), List.of(), List.of(billable));

        DomainModelView view = DomainModelView.of(model);

        assertThat(view.field("LineItem", "description").kind()).isEqualTo(FieldKind.SCALAR);

        ResolvedField productField = view.field("LineItem", "product");
        assertThat(productField.kind()).isEqualTo(FieldKind.REF);
        assertThat(productField).isInstanceOfSatisfying(ResolvedRefField.class, ref ->
            assertThat(ref.targetEntity().name()).isEqualTo("Product"));

        ResolvedField billableField = view.field("LineItem", "billable");
        assertThat(billableField.kind()).isEqualTo(FieldKind.ONEOF);
        assertThat(billableField).isInstanceOfSatisfying(ResolvedOneOfField.class, oneOf -> {
            assertThat(oneOf.oneOf().name()).isEqualTo("Billable");
            assertThat(oneOf.members()).extracting(EntityDef::name)
                .containsExactly("Product", "ServicePackage");
        });
    }

    @Test
    void resolvesAllThreeMembersForThreeMemberOneOf() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        EntityDef subscriptionPlan = entity("SubscriptionPlan", Map.of("name", stringField()));
        EntityDef lineItem = entity("LineItem", Map.of("billable", oneOfField("Billable")));
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage", "SubscriptionPlan"));
        ResolvedDomainModel model = ResolvedDomainModel.builder()
            .entities(List.of(product, servicePackage, subscriptionPlan, lineItem))
            .oneOfs(List.of(billable))
            .build();

        DomainModelView view = DomainModelView.of(model);

        ResolvedField billableField = view.field("LineItem", "billable");
        assertThat(billableField.kind()).isEqualTo(FieldKind.ONEOF);
        assertThat(billableField).isInstanceOfSatisfying(ResolvedOneOfField.class, oneOf ->
            assertThat(oneOf.members()).extracting(EntityDef::name)
                .containsExactly("Product", "ServicePackage", "SubscriptionPlan"));
    }

    @Test
    void exposesResolvedFieldsInManifestOrder() {
        EntityDef product = entity("Product", Map.of("name", stringField()));
        EntityDef servicePackage = entity("ServicePackage", Map.of("name", stringField()));
        Map<String, FieldDef> fields = new java.util.LinkedHashMap<>();
        fields.put("description", stringField());
        fields.put("billable", oneOfField("Billable"));
        EntityDef lineItem = entity("LineItem", fields);
        OneOfDef billable = new OneOfDef("Billable", List.of("Product", "ServicePackage"));
        DomainModelView view = DomainModelView.of(new ResolvedDomainModel(
            List.of(product, servicePackage, lineItem), List.of(), null,
            List.of(), List.of(), List.of(), List.of(), List.of(billable)));

        assertThat(view.fields("LineItem"))
            .extracting(ResolvedField::fieldName)
            .containsExactly("description", "billable");
    }

    private static EntityDef entity(String name, Map<String, FieldDef> fields) {
        return new EntityDef(name, name.toLowerCase() + "s", null, null,
            false, false, false, fields, null, null, null, Map.of(), Map.of());
    }

    private static FieldDef stringField() {
        return new FieldDef("String", false, false, false, false, null, null, null, null, null, null, null);
    }

    private static FieldDef manyToOneField(String targetClass) {
        return new FieldDef("ref", false, false, false, false, null, null, null, "ManyToOne", targetClass, null, null);
    }

    private static FieldDef oneOfField(String targetClass) {
        return new FieldDef("oneof", false, false, false, false, null, null, null, null, targetClass, null, null);
    }
}
