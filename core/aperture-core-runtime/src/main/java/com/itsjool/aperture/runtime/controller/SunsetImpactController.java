package com.itsjool.aperture.runtime.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/manage/sunset-impact")
public class SunsetImpactController {
    private final JdbcTemplate jdbcTemplate;

    public SunsetImpactController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> getSunsetImpact(@RequestParam("version") int version) {
        // Query the materialized view as per specs to prevent DoS via raw log GROUP BYs
        String sql = "SELECT COUNT(DISTINCT tenant_id) FROM aperture_sunset_impact_mv WHERE version = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, version);
        return Map.of(
            "version", version,
            "affectedTenants", count != null ? count : 0
        );
    }
}
