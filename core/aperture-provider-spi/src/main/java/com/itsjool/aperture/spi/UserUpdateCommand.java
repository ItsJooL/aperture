package com.itsjool.aperture.spi;

import java.util.Map;
import java.util.Optional;

public record UserUpdateCommand(
        Optional<String> status,
        Optional<Map<String, Object>> profile,
        Optional<Map<String, Object>> securityAttributes
) {}
