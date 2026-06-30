package com.itsjool.aperture.engine.model;
public record MigrationDef(String name, String sql, String rollbackSql, String positionAfter) {}
