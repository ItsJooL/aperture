package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itsjool.aperture.runtime.filter.ApertureRequestAttributes;
import com.itsjool.apertureautoconfigure.mcp.ApertureMcpAutoConfiguration;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.annotation.Order;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Not a {@code @Component} for the same reason as {@link McpElideAdapter}: component scanning of
 * {@code com.itsjool.aperture} would install this servlet filter into every application, MCP
 * enabled or not. {@link ApertureMcpAutoConfiguration} registers it behind
 * {@code aperture.mcp.enabled}.
 */
@Order(-100) // Run before the MCP router
public class McpSanitizationFilter implements Filter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        if (httpRequest.getRequestURI().equals("/mcp") && httpRequest.getMethod().equalsIgnoreCase("POST")) {
            // Read the body
            String body = new String(httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                JsonNode root = objectMapper.readTree(body);
                String method = (root != null && root.has("method")) ? root.get("method").asText() : null;
                // Stashed for every request that parses, regardless of what method turns out to
                // be: McpToolListFilter reads this to decide whether to buffer/rewrite the
                // response, instead of shape-detecting it.
                httpRequest.setAttribute(ApertureRequestAttributes.MCP_JSONRPC_METHOD, method);

                if ("initialize".equals(method)) {
                    JsonNode params = root.get("params");
                    if (params != null && params.has("capabilities")) {
                        JsonNode capabilities = params.get("capabilities");
                        if (capabilities.has("elicitation")) {
                            ((ObjectNode) capabilities).remove("elicitation");
                            body = objectMapper.writeValueAsString(root);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore parsing errors here and let the framework handle it
            }

            final String sanitizedBody = body;
            
            // Pass a wrapper with the sanitized body
            HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(httpRequest) {
                @Override
                public jakarta.servlet.ServletInputStream getInputStream() {
                    final ByteArrayInputStream bais = new ByteArrayInputStream(sanitizedBody.getBytes(StandardCharsets.UTF_8));
                    return new jakarta.servlet.ServletInputStream() {
                        @Override
                        public boolean isFinished() {
                            return bais.available() == 0;
                        }
                        @Override
                        public boolean isReady() {
                            return true;
                        }
                        @Override
                        public void setReadListener(jakarta.servlet.ReadListener readListener) {
                        }
                        @Override
                        public int read() {
                            return bais.read();
                        }
                    };
                }

                @Override
                public BufferedReader getReader() {
                    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                }
            };
            
            chain.doFilter(wrapper, response);
            return;
        }
        
        chain.doFilter(request, response);
    }
}
