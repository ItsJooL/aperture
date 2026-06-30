package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class RoleSeedingComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testRoleSeeding() {
        // Platform authorities must be in aperture_tenant_admins, not the domain role tables
        Integer adminCount = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_tenant_admins", Integer.class);
        assertThat(adminCount).isGreaterThanOrEqualTo(2);

        // No reserved role names must appear in aperture_roles or aperture_user_roles
        Integer reservedInRoles = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_roles WHERE role_name IN ('TenantAdmin', 'SuperAdmin')", Integer.class);
        assertThat(reservedInRoles).isZero();

        Integer reservedInUserRoles = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM aperture_user_roles WHERE role_name IN ('TenantAdmin', 'SuperAdmin')", Integer.class);
        assertThat(reservedInUserRoles).isZero();
    }
}
