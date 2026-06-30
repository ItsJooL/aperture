package com.itsjool.aperture.engine.model;
public record HookDef(String phase, boolean async, String onFailure, String url) {}
