package com.itsjool.aperture.runtime.filter;

import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.RateLimitDecision;
import com.itsjool.aperture.spi.RateLimitKey;
import com.itsjool.aperture.spi.RateLimitProvider;
import com.itsjool.aperture.spi.RateLimitRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitProvider rateLimitProvider;
    private final boolean tenancyEnabled;

    public RateLimitFilter(RateLimitProvider rateLimitProvider, @Value("${aperture.tenancy.enabled:true}") boolean tenancyEnabled) {
        this.rateLimitProvider = rateLimitProvider;
        this.tenancyEnabled = tenancyEnabled;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        RateLimitRule ipRule = new RateLimitRule(100, 100, 60);
        RateLimitDecision ipDecision = rateLimitProvider.evaluate(new RateLimitKey("ip", ip), ipRule);
        
        if (!ipDecision.allowed()) {
            writeRateLimitHeaders(response, ipDecision, ipRule);
            response.sendError(429, "Too Many Requests");
            return;
        }

        if (tenancyEnabled) {
            AperturePrincipal principal = (AperturePrincipal) request.getAttribute("aperturePrincipal");
            if (principal != null) {
                RateLimitRule userRule = new RateLimitRule(50, 50, 60);
                RateLimitDecision userDecision = rateLimitProvider.evaluate(new RateLimitKey("user", principal.userId()), userRule);
                if (!userDecision.allowed()) {
                    writeRateLimitHeaders(response, userDecision, userRule);
                    response.sendError(429, "Too Many Requests");
                    return;
                }

                if (principal.tenantId() != null) {
                    RateLimitRule tenantRule = new RateLimitRule(500, 500, 60);
                    RateLimitDecision tenantDecision = rateLimitProvider.evaluate(new RateLimitKey("tenant", principal.tenantId()), tenantRule);
                    if (!tenantDecision.allowed()) {
                        writeRateLimitHeaders(response, tenantDecision, tenantRule);
                        response.sendError(429, "Too Many Requests");
                        return;
                    }
                }
            }
        }
        
        writeRateLimitHeaders(response, ipDecision, ipRule);
        filterChain.doFilter(request, response);
    }

    private void writeRateLimitHeaders(HttpServletResponse response, RateLimitDecision decision, RateLimitRule rule) {
        response.setHeader("X-RateLimit-Limit", String.valueOf(rule.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(java.time.Instant.now().getEpochSecond() + decision.retryAfterSeconds()));
    }
}
