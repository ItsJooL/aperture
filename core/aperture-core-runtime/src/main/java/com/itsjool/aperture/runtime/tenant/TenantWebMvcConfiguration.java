package com.itsjool.aperture.runtime.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantWebMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new TenantTaskDecorator());
        executor.initialize();
        configurer.setTaskExecutor(executor);
    }

    private static class TenantTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            String tenantId = TenantContextHolder.getTenantId();
            return () -> {
                if (tenantId != null) {
                    TenantContextHolder.setTenantId(tenantId);
                }
                try {
                    runnable.run();
                } finally {
                    TenantContextHolder.clear();
                }
            };
        }
    }
}
