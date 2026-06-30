package com.itsjool.aperture.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServiceAccountSqlFixtureTest {
    @Test
    void fixturesUseLiquibaseSchemaAndPopulateRequiredLifecycleColumns() throws Exception {
        for (Path fixture : new Path[] {
                Path.of("src/test/resources/init/service-accounts.sql"),
                Path.of("seed-data/service-accounts.sql")}) {
            String sql = Files.readString(fixture);

            assertThat(sql).doesNotContainIgnoringCase("CREATE TABLE");
            assertThat(sql).contains("status", "expires_at", "'ACTIVE'", "TIMESTAMPTZ");
            assertThat(sql).contains("ON CONFLICT (id) DO NOTHING");
        }
    }
}
