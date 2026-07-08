package com.itsjool.aperture.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookDef(String type, List<String> on, String onFailure, String url) {}
