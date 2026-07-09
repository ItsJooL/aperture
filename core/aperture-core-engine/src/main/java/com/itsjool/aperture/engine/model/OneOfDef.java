package com.itsjool.aperture.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OneOfDef(String name, List<String> members) {
    public OneOfDef {
        members = members != null ? List.copyOf(members) : List.of();
    }
}
