package com.itsjool.aperture.spi;

import java.time.Instant;
import java.util.List;

import java.util.Map;

public record InviteRecord(String inviteId, String tenantId, String status, Instant expiresAt, Instant acceptedAt, List<String> roleNames, Map<String, Object> profile, Map<String, Object> securityAttributes) {}
