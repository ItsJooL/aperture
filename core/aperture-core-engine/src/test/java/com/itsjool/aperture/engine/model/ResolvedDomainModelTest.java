package com.itsjool.aperture.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResolvedDomainModelTest {

    @Test
    void builderNamesOptionalModelPartsAndDefensivelyCopiesCollections() {
        EntityDef product = new EntityDef("Product", "products", null, null, false, false, false,
            null, null, null, null, null, null);
        OneOfDef billable = new OneOfDef("Billable", List.of("Product"));
        List<EntityDef> entities = new ArrayList<>(List.of(product));

        ResolvedDomainModel model = ResolvedDomainModel.builder()
            .entities(entities)
            .oneOfs(List.of(billable))
            .build();
        entities.clear();

        assertThat(model.entities()).containsExactly(product);
        assertThat(model.oneOfs()).containsExactly(billable);
        assertThat(model.migrations()).isEmpty();
        assertThat(model.roleDefinitions()).isEmpty();
        assertThat(model.abacPolicies()).isEmpty();
        assertThat(model.apiVersionConfigs()).isEmpty();
        assertThat(model.principalAttributeDefinitions()).isEmpty();
        assertThat(model.frameworkConfig()).isNull();
        assertThatThrownBy(() -> model.entities().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
