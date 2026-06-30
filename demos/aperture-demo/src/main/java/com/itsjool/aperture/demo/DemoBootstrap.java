package com.itsjool.aperture.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "aperture.profile", havingValue = "demo")
public class DemoBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);
    private static final String SUPERADMIN_USERNAME = "superadmin@framework.local";
    private static final String DEFAULT_PASSWORD = "changeme-local-only";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public DemoBootstrap(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
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
        if (count != null && count > 0) {
            log.info("Superadmin already exists, skipping bootstrap");
            return;
        }

        if (DEFAULT_PASSWORD.equals(adminPassword)) {
            log.warn("!!! Using default bootstrap password — NOT suitable for production. " +
                     "Set APERTURE_BOOTSTRAP_ADMIN_PASSWORD before deploying. !!!");
        }

        jdbcTemplate.update(
                "INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) " +
                "VALUES (?, ?, ?, NULL, 'ACTIVE', true)",
                UUID.randomUUID().toString(), SUPERADMIN_USERNAME, passwordEncoder.encode(adminPassword));

        log.info("Bootstrap complete: superadmin '{}' created", SUPERADMIN_USERNAME);
    }
}
