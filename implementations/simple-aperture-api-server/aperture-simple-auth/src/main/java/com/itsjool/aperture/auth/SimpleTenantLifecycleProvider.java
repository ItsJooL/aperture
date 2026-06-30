package com.itsjool.aperture.auth;

import com.itsjool.aperture.spi.TenantLifecycleProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import com.itsjool.aperture.spi.TenantContext;
import java.util.List;

public class SimpleTenantLifecycleProvider implements TenantLifecycleProvider {
    public SimpleTenantLifecycleProvider(JdbcTemplate jdbcTemplate) {
    }
    
    @Override
    public void onProvision(TenantContext ctx, List<String> defaultRoles) {}

    @Override
    public void onSuspend(TenantContext ctx) {}

    @Override
    public void onRestore(TenantContext ctx) {}

    @Override
    public void onDeprovision(TenantContext ctx) {}
}
