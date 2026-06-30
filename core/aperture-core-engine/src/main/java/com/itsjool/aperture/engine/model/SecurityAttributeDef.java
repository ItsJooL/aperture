package com.itsjool.aperture.engine.model;
import java.util.List;
public record SecurityAttributeDef(String type, List<String> allowedValues, String personalKeyDelegation, Boolean serviceAccountAssignable) {}
