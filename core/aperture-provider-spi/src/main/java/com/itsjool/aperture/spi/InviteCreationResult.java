package com.itsjool.aperture.spi;

import java.time.Instant;

public record InviteCreationResult(String inviteId, String token, Instant expiresAt) {}
