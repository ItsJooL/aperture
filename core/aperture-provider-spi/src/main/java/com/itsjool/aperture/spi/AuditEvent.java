package com.itsjool.aperture.spi;
public record AuditEvent(String userId, String tenantId, String entity, String entityId, String operation, String detailsJson) {}
