package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
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
                if (root != null && root.has("method") && "initialize".equals(root.get("method").asText())) {
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
