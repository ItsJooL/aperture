package com.itsjool.aperture.engine.model;
import com.fasterxml.jackson.annotation.JsonProperty;
public record FieldDef(String type, boolean required, boolean unique, boolean index, boolean encrypted, String since, String removedIn, String renamedFrom, String relation, @JsonProperty("target") String targetClass, String mappedBy, String description) {}
