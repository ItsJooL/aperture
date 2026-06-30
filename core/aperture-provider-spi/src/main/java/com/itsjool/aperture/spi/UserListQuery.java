package com.itsjool.aperture.spi;

public record UserListQuery(String tenantId, String search, int page, int size) {}
