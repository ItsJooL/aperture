package com.itsjool.aperture.engine.model;
import com.fasterxml.jackson.databind.JsonNode;
public record ManifestEnvelope(String apiVersion, String kind, MetadataDef metadata, JsonNode spec) {}
