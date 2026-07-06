package com.itsjool.aperture.audit;

import com.itsjool.aperture.spi.AperturePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/manage/audit")
public class AuditQueryController {
    private final JdbcTemplate jdbcTemplate;

    public AuditQueryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<Map<String, Object>> queryAudit(
            HttpServletRequest httpRequest,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        AperturePrincipal principal = (AperturePrincipal) httpRequest.getAttribute("aperturePrincipal");
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        boolean isSuperAdmin = principal.superAdmin();
        boolean isTenantAdmin = principal.tenantAdmin();

        if (!isSuperAdmin && !isTenantAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Audit access requires TenantAdmin or SuperAdmin authority");
        }

        // Tenant admins can only see their own tenant's audit logs
        String effectiveTenantId = tenantId;
        if (!isSuperAdmin) {
            effectiveTenantId = principal.tenantId();
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM aperture_audit_log WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (effectiveTenantId != null) {
            sql.append(" AND tenant_id = ?");
            args.add(effectiveTenantId);
        }
        if (entity != null) {
            sql.append(" AND entity = ?");
            args.add(entity);
        }
        if (entityId != null) {
            sql.append(" AND entity_id = ?");
            args.add(entityId);
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        if (from != null) {
            sql.append(" AND timestamp >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND timestamp <= ?");
            args.add(to);
        }

        sql.append(" ORDER BY timestamp DESC LIMIT 100");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }
}
