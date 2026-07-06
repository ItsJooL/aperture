package com.itsjool.aperture.audit;

import com.itsjool.aperture.spi.AperturePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.*;

class AuditQueryControllerTest {

    private static HttpServletRequest requestWithPrincipal(AperturePrincipal principal) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("aperturePrincipal")).thenReturn(principal);
        return req;
    }

    @Test
    void superAdminQueryFiltersOnSuppliedTenant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditQueryController ctrl = new AuditQueryController(jdbc);

        AperturePrincipal superAdmin = new AperturePrincipal("uid-1", null, Set.of(), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of(), Set.of(), true, false);
        HttpServletRequest req = requestWithPrincipal(superAdmin);

        ctrl.queryAudit(req, "Invoice", "123", "t1", null, null, null);

        verify(jdbc).queryForList(
            eq("SELECT * FROM aperture_audit_log WHERE 1=1 AND tenant_id = ? AND entity = ? AND entity_id = ? ORDER BY timestamp DESC LIMIT 100"),
            eq("t1"), eq("Invoice"), eq("123")
        );
    }

    @Test
    void tenantAdminQueryScopedToOwnTenant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditQueryController ctrl = new AuditQueryController(jdbc);

        AperturePrincipal tenantAdmin = new AperturePrincipal("uid-2", "tenant-abc", Set.of(), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of(), Set.of(), false, true);
        HttpServletRequest req = requestWithPrincipal(tenantAdmin);

        ctrl.queryAudit(req, "Invoice", null, null, null, null, null);

        verify(jdbc).queryForList(
            eq("SELECT * FROM aperture_audit_log WHERE 1=1 AND tenant_id = ? AND entity = ? ORDER BY timestamp DESC LIMIT 100"),
            eq("tenant-abc"), eq("Invoice")
        );
    }

    @Test
    void superAdminQueryFiltersOnUserIdAndDateRange() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditQueryController ctrl = new AuditQueryController(jdbc);

        AperturePrincipal superAdmin = new AperturePrincipal("uid-1", null, Set.of(), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of(), Set.of(), true, false);
        HttpServletRequest req = requestWithPrincipal(superAdmin);

        ctrl.queryAudit(req, null, null, null, "user-123", "2026-07-01T00:00:00Z", "2026-07-05T23:59:59Z");

        verify(jdbc).queryForList(
            eq("SELECT * FROM aperture_audit_log WHERE 1=1 AND user_id = ? AND timestamp >= ? AND timestamp <= ? ORDER BY timestamp DESC LIMIT 100"),
            eq("user-123"), eq("2026-07-01T00:00:00Z"), eq("2026-07-05T23:59:59Z")
        );
    }
}
