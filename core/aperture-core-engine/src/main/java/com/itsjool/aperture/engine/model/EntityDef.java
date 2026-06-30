package com.itsjool.aperture.engine.model;
import java.util.List;
import java.util.Map;
public record EntityDef(String name, String plural, String description, McpEntityConfig mcpConfig, boolean optimisticLocking, boolean softDelete, boolean tenantScoped, Map<String, FieldDef> fields, Map<String, List<String>> permissions, Map<String, List<String>> policies, List<String> publicOperations, Map<String, String> abacRules, Map<String, HookDef> hooks) {}
