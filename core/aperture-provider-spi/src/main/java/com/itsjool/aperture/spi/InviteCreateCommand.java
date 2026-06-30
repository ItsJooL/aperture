package com.itsjool.aperture.spi;

import java.util.List;

import java.util.Map;

public record InviteCreateCommand(String tenantId, String createdBy, List<String> roleNames, Map<String, Object> profile, Map<String, Object> securityAttributes, int expiresInHours) {}
