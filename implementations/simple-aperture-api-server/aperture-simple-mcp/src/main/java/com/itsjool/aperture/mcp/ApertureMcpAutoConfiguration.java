package com.itsjool.aperture.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.elide.spring.controllers.JsonApiController;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@ConditionalOnProperty(name = "aperture.mcp.enabled", havingValue = "true")
@EnableConfigurationProperties(ApertureMcpProperties.class)
@ComponentScan(basePackages = "com.itsjool.aperture.generated.mcp")
public class ApertureMcpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(McpRequestAdapter.class)
    public McpRequestAdapter mcpElideAdapter(JsonApiController jsonApiController, ObjectMapper objectMapper) {
        return new McpElideAdapter(jsonApiController, objectMapper);
    }

    @Bean
    public ToolCallbackProvider apertureToolCallbackProvider(ApplicationContext ctx) {
        List<Object> toolObjects = ctx.getBeansWithAnnotation(Component.class).values().stream()
            .filter(bean -> Arrays.stream(bean.getClass().getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(Tool.class)))
            .collect(Collectors.toList());
        if (toolObjects.isEmpty()) {
            return ToolCallbackProvider.from();
        }
        // toolObjects(Object...) is varargs — must spread the list, not pass it as a single element
        return MethodToolCallbackProvider.builder().toolObjects(toolObjects.toArray()).build();
    }

    @Bean
    public McpSanitizationFilter mcpSanitizationFilter() {
        return new McpSanitizationFilter();
    }
}
