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

    /** Carries the per-request context holders across the servlet-to-async-executor thread
     *  hop: Elide's Spring controllers return {@code Callable}, so the request is processed
     *  on this executor's thread — plain ThreadLocals populated by AuthFilter on the servlet
     *  thread are invisible there unless re-set here. */
    private static class TenantTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            String tenantId = TenantContextHolder.getTenantId();
            java.util.Map<String, String> scopes = com.itsjool.aperture.runtime.scope.ScopeContextHolder.snapshot();
            return () -> {
                if (tenantId != null) {
                    TenantContextHolder.setTenantId(tenantId);
                }
                com.itsjool.aperture.runtime.scope.ScopeContextHolder.restore(scopes);
                try {
                    runnable.run();
                } finally {
                    TenantContextHolder.clear();
                    com.itsjool.aperture.runtime.scope.ScopeContextHolder.clear();
                }
            };
        }
    }
}
