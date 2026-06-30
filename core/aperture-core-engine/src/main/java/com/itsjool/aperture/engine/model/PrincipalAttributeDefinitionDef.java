package com.itsjool.aperture.engine.model;
import java.util.Map;
public record PrincipalAttributeDefinitionDef(MetadataDef metadata, SpecDef spec) {
    public record SpecDef(Map<String, SecurityAttributeDef> securityAttributes) {}
}
