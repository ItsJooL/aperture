package com.itsjool.aperture.spi;
import java.util.List;
public interface TenantLifecycleProvider {
    void onProvision(TenantContext ctx, List<String> defaultRoles);
    void onSuspend(TenantContext ctx);
    void onRestore(TenantContext ctx);
    void onDeprovision(TenantContext ctx);
}
