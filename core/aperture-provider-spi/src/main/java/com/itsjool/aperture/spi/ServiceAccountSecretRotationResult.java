package com.itsjool.aperture.spi;

public record ServiceAccountSecretRotationResult(
        ServiceAccountRecord account,
        String rawSecret) {
}
