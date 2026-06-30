package com.itsjool.aperture.spi;

public record EncryptionContext(String tenantId, String entity, String field, boolean isDeterministic) {}
