package com.itsjool.aperture.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityDef(String name, String plural, String description, @JsonProperty("mcp") McpEntityConfig mcpConfig, boolean optimisticLocking, boolean softDelete, boolean tenantScoped, Map<String, FieldDef> fields, Map<String, List<String>> permissions, Map<String, List<String>> policies, List<String> publicOperations, Map<String, String> abacRules, Map<String, HookDef> hooks, String scopedBy) {

    public EntityDef(String name, String plural, String description, McpEntityConfig mcpConfig, boolean optimisticLocking, boolean softDelete, boolean tenantScoped, Map<String, FieldDef> fields, Map<String, List<String>> permissions, Map<String, List<String>> policies, List<String> publicOperations, Map<String, String> abacRules, Map<String, HookDef> hooks) {
        this(name, plural, description, mcpConfig, optimisticLocking, softDelete, tenantScoped, fields, permissions, policies, publicOperations, abacRules, hooks, null);
    }
}
