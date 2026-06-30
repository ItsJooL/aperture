package com.itsjool.aperture.runtime.cors;

import com.itsjool.aperture.runtime.config.ApertureRuntimeMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class ApertureCorsFilter extends OncePerRequestFilter {

    private static final List<String> ALLOWED_HEADERS = List.of(
        "Authorization", "Content-Type", "Accept", "X-Requested-With");

    private final CorsConfiguration corsConfiguration;

    public ApertureCorsFilter(CorsProperties props, ApertureRuntimeMetadata metadata) {
        CorsConfiguration config = new CorsConfiguration();
        if (!props.getAllowedOrigins().isEmpty()) {
            config.setAllowedOrigins(props.getAllowedOrigins());
        }
        props.getAllowedOriginPatterns().forEach(config::addAllowedOriginPattern);
        config.setAllowedMethods(List.copyOf(metadata.allowedHttpMethods()));
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(props.getMaxAge());
        this.corsConfiguration = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            String allowed = corsConfiguration.checkOrigin(origin);
            if (allowed != null) {
                response.setHeader("Access-Control-Allow-Origin", allowed);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.setHeader("Vary", "Origin");

                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    List<String> methods = corsConfiguration.getAllowedMethods();
                    if (methods != null) {
                        response.setHeader("Access-Control-Allow-Methods", String.join(", ", methods));
                    }
                    response.setHeader("Access-Control-Allow-Headers", String.join(", ", ALLOWED_HEADERS));
                    response.setHeader("Access-Control-Max-Age", String.valueOf(corsConfiguration.getMaxAge()));
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
