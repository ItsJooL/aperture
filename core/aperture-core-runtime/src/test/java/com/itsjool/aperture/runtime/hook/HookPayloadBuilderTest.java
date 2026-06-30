package com.itsjool.aperture.runtime.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class HookPayloadBuilderTest {

    private static final ObjectMapper om = new ObjectMapper();

    @jakarta.persistence.Entity
    public static class MockParent {
        @Id
        private UUID id;
        private String name;

        public MockParent(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @jakarta.persistence.Entity
    public static class MockEntity {
        @Id
        private UUID id;
        private String name;
        private Integer age;

        @ManyToOne
        private MockParent parent;

        @OneToMany(mappedBy = "owner")
        private Set<MockRelated> relatedItems = new HashSet<>();

        public MockEntity(UUID id, String name, Integer age, MockParent parent) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.parent = parent;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
        public MockParent getParent() { return parent; }
        public void setParent(MockParent parent) { this.parent = parent; }
        public Set<MockRelated> getRelatedItems() { return relatedItems; }
        public void setRelatedItems(Set<MockRelated> relatedItems) { this.relatedItems = relatedItems; }
    }

    @jakarta.persistence.Entity
    public static class MockRelated {
        @Id
        private UUID id;
        private String value;

        @ManyToOne
        private MockEntity owner;

        public MockRelated(UUID id, String value, MockEntity owner) {
            this.id = id;
            this.value = value;
            this.owner = owner;
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public MockEntity getOwner() { return owner; }
        public void setOwner(MockEntity owner) { this.owner = owner; }
    }

    @Test
    void payloadIncludesScalarFields() throws Exception {
        UUID id = UUID.randomUUID();
        MockParent parent = new MockParent(UUID.randomUUID(), "Parent");
        MockEntity entity = new MockEntity(id, "Test Name", 42, parent);

        String payload = HookPayloadBuilder.build(entity, om);

        assertThat(payload).contains("\"name\":\"Test Name\"");
        assertThat(payload).contains("\"age\":42");
        assertThat(payload).contains("\"id\":");
    }

    @Test
    void payloadIncludesManyToOneAsId() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        MockParent parent = new MockParent(parentId, "Parent");
        MockEntity entity = new MockEntity(entityId, "Test", 30, parent);

        String payload = HookPayloadBuilder.build(entity, om);

        assertThat(payload).as("payload must include parent FK as parent_id").contains("\"parent_id\":");
        assertThat(payload).contains(parentId.toString());
        assertThat(payload).doesNotContain("\"parent\":");
    }

    @Test
    void payloadExcludesOneToMany() throws Exception {
        UUID id = UUID.randomUUID();
        MockParent parent = new MockParent(UUID.randomUUID(), "Parent");
        MockEntity entity = new MockEntity(id, "Test", 30, parent);
        MockRelated related = new MockRelated(UUID.randomUUID(), "Related Item", entity);
        entity.getRelatedItems().add(related);

        String payload = HookPayloadBuilder.build(entity, om);

        assertThat(payload).as("payload must not include OneToMany collection").doesNotContain("relatedItems");
    }

    @Test
    void payloadHandlesNullManyToOne() throws Exception {
        UUID id = UUID.randomUUID();
        MockEntity entity = new MockEntity(id, "Test", 30, null);

        String payload = HookPayloadBuilder.build(entity, om);

        assertThat(payload).contains("\"parent_id\":null");
    }

    @Test
    void applyEnrichmentOverridesModifiesAttribute() throws Exception {
        UUID id = UUID.randomUUID();
        MockParent parent = new MockParent(UUID.randomUUID(), "Parent");
        MockEntity entity = new MockEntity(id, "original name", 30, parent);

        String responseBody = "{\"data\":{\"attributes\":{\"name\":\"enriched name\",\"age\":99}}}";
        HookPayloadBuilder.applyEnrichmentOverrides(entity, responseBody, om);

        assertThat(entity.getName()).isEqualTo("enriched name");
        assertThat(entity.getAge()).isEqualTo(99);
    }

    @Test
    void applyEnrichmentOverridesHandlesNull() throws Exception {
        UUID id = UUID.randomUUID();
        MockParent parent = new MockParent(UUID.randomUUID(), "Parent");
        MockEntity entity = new MockEntity(id, "name", 30, parent);

        HookPayloadBuilder.applyEnrichmentOverrides(entity, null, om);
        // Should not throw; entity should be unchanged
        assertThat(entity.getName()).isEqualTo("name");
    }

    @Test
    void applyEnrichmentOverridesIgnoresProtectedFields() throws Exception {
        UUID originalId = UUID.randomUUID();
        MockParent parent = new MockParent(UUID.randomUUID(), "Parent");
        MockEntity entity = new MockEntity(originalId, "name", 30, parent);

        // id is a protected field — must be ignored even if the hook returns it
        String responseBody = "{\"data\":{\"attributes\":{\"id\":\"00000000-0000-0000-0000-000000000000\",\"name\":\"overridden\"}}}";
        HookPayloadBuilder.applyEnrichmentOverrides(entity, responseBody, om);

        assertThat(entity.getId()).as("protected 'id' field must not be overwritten").isEqualTo(originalId);
        assertThat(entity.getName()).as("non-protected field must be updated").isEqualTo("overridden");
    }
}
