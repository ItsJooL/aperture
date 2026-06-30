package com.itsjool.aperture.engine.model;
import java.util.Map;
public record ApiVersionConfigDef(Map<String, ApiVersionDef> versions) {}
