package com.itsjool.aperture.spi;

import java.util.List;

public record SecurityAttributeDefinition(
        String type,
        List<String> allowedValues,
        String personalKeyDelegation,
        Boolean serviceAccountAssignable) {

    public SecurityAttributeDefinition {
        type = type == null || type.isBlank() ? "string" : type;
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        personalKeyDelegation = personalKeyDelegation == null || personalKeyDelegation.isBlank()
                ? "exact"
                : personalKeyDelegation;
        serviceAccountAssignable = serviceAccountAssignable != null ? serviceAccountAssignable : Boolean.FALSE;
    }
}
