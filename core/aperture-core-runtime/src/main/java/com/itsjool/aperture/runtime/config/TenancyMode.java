package com.itsjool.aperture.runtime.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TenancyMode {
    NONE, POOL, SILO;

    @JsonCreator
    public static TenancyMode fromJson(String value) {
        if (value == null) return POOL;
        return switch (value.toLowerCase()) {
            case "none" -> NONE;
            case "silo" -> SILO;
            default -> POOL;
        };
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
