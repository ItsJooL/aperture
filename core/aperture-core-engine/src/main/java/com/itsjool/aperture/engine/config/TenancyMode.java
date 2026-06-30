package com.itsjool.aperture.engine.config;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TenancyMode { NONE, POOL, SILO;

    @JsonCreator
    public static TenancyMode fromJson(String value) {
        if (value == null) return POOL;
        return switch (value.toLowerCase()) {
            case "none" -> NONE;
            case "silo" -> SILO;
            default -> POOL;
        };
    }
}
