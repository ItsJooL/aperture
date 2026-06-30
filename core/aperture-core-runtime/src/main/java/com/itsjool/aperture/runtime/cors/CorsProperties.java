package com.itsjool.aperture.runtime.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "aperture.cors")
public class CorsProperties {

    private boolean enabled = false;
    private List<String> allowedOrigins = List.of();
    private List<String> allowedOriginPatterns = List.of();
    private long maxAge = 3600L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }

    public List<String> getAllowedOriginPatterns() { return allowedOriginPatterns; }
    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) { this.allowedOriginPatterns = allowedOriginPatterns; }

    public long getMaxAge() { return maxAge; }
    public void setMaxAge(long maxAge) { this.maxAge = maxAge; }

    public void validate() {
        if (enabled && allowedOrigins.isEmpty() && allowedOriginPatterns.isEmpty()) {
            throw new IllegalStateException(
                "aperture.cors.enabled=true but no aperture.cors.allowed-origins or " +
                "aperture.cors.allowed-origin-patterns configured. " +
                "Refusing to start with CORS enabled but no origins permitted.");
        }
    }
}
