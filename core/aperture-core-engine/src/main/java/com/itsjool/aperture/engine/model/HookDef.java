package com.itsjool.aperture.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookDef(String type, List<String> on, String onFailure, String url, int retries) {

    // Pre-existing call sites (mostly tests) predate the `retries` field; default it to 0
    // (today's behavior — no retries) rather than touching every 4-arg construction site.
    // Mirrors the same trailing-field-with-legacy-constructor pattern EntityDef already uses.
    public HookDef(String type, List<String> on, String onFailure, String url) {
        this(type, on, onFailure, url, 0);
    }
}
