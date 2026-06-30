package com.itsjool.aperture.runtime.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNull;

class TenantContextHolderTest {

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void doesNotImplicitlyInheritTenantIntoWorkerThreads() throws Exception {
        TenantContextHolder.setTenantId("tenant-A");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertNull(executor.submit(TenantContextHolder::getTenantId).get());
        } finally {
            executor.shutdownNow();
        }
    }
}
