package com.itsjool.aperture.starter.filter;

import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OptimisticLockingFilter extends OncePerRequestFilter {

    private static final Pattern API_ENTITY_ID_PATH = Pattern.compile("^/api/v[0-9]+/([^/]+)/([0-9a-fA-F\\-]+)$");
    private static final Pattern QUOTED_INTEGER = Pattern.compile("^\"([0-9]+)\"$");

    private final JdbcTemplate jdbcTemplate;
    private final Set<String> lockingEntities;

    public OptimisticLockingFilter(JdbcTemplate jdbcTemplate, ApertureRuntimeMetadata metadata) {
        this.jdbcTemplate = jdbcTemplate;
        this.lockingEntities = metadata.lockingEntities();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        String uri = request.getRequestURI();
        Matcher m = API_ENTITY_ID_PATH.matcher(uri);

        if (!m.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String entityPlural = m.group(1);
        String entityId = m.group(2);

        if (!lockingEntities.contains(entityPlural)) {
            filterChain.doFilter(request, response);
            return;
        }

        if ("PATCH".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            String ifMatch = request.getHeader("If-Match");
            if (ifMatch == null) {
                response.sendError(428, "If-Match header is required for this resource");
                return;
            }
            Matcher tagMatcher = QUOTED_INTEGER.matcher(ifMatch.trim());
            if (!tagMatcher.matches()) {
                response.sendError(400, "Malformed If-Match: must be a quoted integer, e.g. \"0\"");
                return;
            }
            int clientVersion = Integer.parseInt(tagMatcher.group(1));
            String tableName = "aperture_" + entityPlural;
            try {
                Integer dbVersion = jdbcTemplate.queryForObject(
                        "SELECT version FROM " + tableName + " WHERE id = ?::uuid",
                        Integer.class, entityId);
                if (dbVersion != null && dbVersion != clientVersion) {
                    response.sendError(412, "Precondition Failed: entity version has changed");
                    return;
                }
            } catch (DataAccessException e) {
            }
        }

        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            if (isOptimisticLockCause(e)) {
                response.sendError(412, "Concurrent modification detected");
                return;
            }
            throw e;
        }

        if ("GET".equalsIgnoreCase(method) && response.getStatus() == 200 && response.getHeader("ETag") == null) {
            try {
                Integer version = jdbcTemplate.queryForObject(
                        "SELECT version FROM aperture_" + entityPlural + " WHERE id = ?::uuid",
                        Integer.class, entityId);
                if (version != null) {
                    response.setHeader("ETag", "\"" + version + "\"");
                }
            } catch (DataAccessException ignored) {
            }
        }
    }

    private static boolean isOptimisticLockCause(Throwable t) {
        while (t != null) {
            if (t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.springframework.dao.OptimisticLockingFailureException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
