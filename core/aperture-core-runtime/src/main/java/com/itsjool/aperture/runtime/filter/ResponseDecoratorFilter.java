package com.itsjool.aperture.runtime.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

import java.io.IOException;

import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ResponseDecoratorFilter extends OncePerRequestFilter {
    private final boolean httpsOnly;

    public ResponseDecoratorFilter(@Value("${aperture.server.https-only:false}") boolean httpsOnly) {
        this.httpsOnly = httpsOnly;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (httpsOnly) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        String uri = request.getRequestURI();
        if (uri != null && uri.contains("/v1/")) {
            response.setHeader("Deprecation", "true");
            response.setHeader("Sunset", "Sun, 01 Jan 2028 00:00:00 GMT");
            response.setHeader("Link", "<https://aperture.itsjool.com/docs/migration>; rel=\"sunset\"");
        }
        filterChain.doFilter(request, response);
    }
}
