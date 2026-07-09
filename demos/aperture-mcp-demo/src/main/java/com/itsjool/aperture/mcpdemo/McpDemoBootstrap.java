package com.itsjool.aperture.mcpdemo;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aperture.mcp-demo.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class McpDemoBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpDemoBootstrap.class);
    private static final String SUPERADMIN_USERNAME = "superadmin@framework.local";
    private static final String AGENT_ADMIN_USERNAME = "agent-admin@mcp-demo.local";
    private static final String AGENT_ADMIN_TENANT_ID = "mcp-demo";
    private static final String AGENT_ADMIN_USER_ID = "mcp-demo-agent-admin";
    private static final String DEFAULT_PASSWORD = "changeme-local-only";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public McpDemoBootstrap(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
            @Value("${APERTURE_BOOTSTRAP_ADMIN_PASSWORD:" + DEFAULT_PASSWORD + "}") String adminPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aperture_users WHERE username = ? AND super_admin = true",
                Integer.class, SUPERADMIN_USERNAME);
        if (DEFAULT_PASSWORD.equals(adminPassword)) {
            log.warn("!!! Using default bootstrap password - NOT suitable for production. " +
                    "Set APERTURE_BOOTSTRAP_ADMIN_PASSWORD before deploying. !!!");
        }

        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) " +
                            "VALUES (?, ?, ?, NULL, 'ACTIVE', true)",
                    UUID.randomUUID().toString(), SUPERADMIN_USERNAME, passwordEncoder.encode(adminPassword));
        }

        jdbcTemplate.update(
                "INSERT INTO aperture_tenants (id, name, status) VALUES (?, ?, 'ACTIVE') " +
                        "ON CONFLICT (id) DO NOTHING",
                AGENT_ADMIN_TENANT_ID, "MCP Demo");
        jdbcTemplate.update(
                "INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES (?, ?, 'Admin') " +
                        "ON CONFLICT ON CONSTRAINT uq_aperture_roles_tenant_name DO NOTHING",
                "mcp-demo-admin-role", AGENT_ADMIN_TENANT_ID);
        jdbcTemplate.update(
                "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE', false) " +
                        "ON CONFLICT (id) DO NOTHING",
                AGENT_ADMIN_USER_ID, AGENT_ADMIN_USERNAME, passwordEncoder.encode(adminPassword), AGENT_ADMIN_TENANT_ID);
        jdbcTemplate.update(
                "INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES (?, ?, 'Admin') " +
                        "ON CONFLICT DO NOTHING",
                AGENT_ADMIN_TENANT_ID, AGENT_ADMIN_USER_ID);

        log.info("Bootstrap complete: superadmin '{}' and MCP agent admin '{}' created",
                SUPERADMIN_USERNAME, AGENT_ADMIN_USERNAME);
    }
}
