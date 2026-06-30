package com.itsjool.aperture.runtime.audit;

import com.itsjool.aperture.spi.AuditEvent;
import com.itsjool.aperture.spi.AuditWriter;
import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditBridgeTest {

    private AuditBridge auditBridge;
    @Mock
    private AuditWriter auditWriter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        auditBridge = new AuditBridge(auditWriter);
    }

    @Test
    void versionSuffixStripping() throws InterruptedException {
        // Create mock entities with different version suffixes
        ProductV1 entityV1 = new ProductV1("123");
        CustomerV3 entityV3 = new CustomerV3("456");
        OrderV10 entityV10 = new OrderV10("789");
        Item entityNoSuffix = new Item("999");

        // Execute audits for each entity
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityV1, null, Optional.empty());
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityV3, null, Optional.empty());
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityV10, null, Optional.empty());
        auditBridge.execute(Operation.CREATE, TransactionPhase.POSTCOMMIT, entityNoSuffix, null, Optional.empty());

        // Give async executor time to process
        Thread.sleep(100);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter, times(4)).write(captor.capture());

        var events = captor.getAllValues();

        // V1 suffix should be stripped
        assertEquals("Product", events.get(0).entity());

        // V3 suffix should be stripped
        assertEquals("Customer", events.get(1).entity());

        // V10 suffix should be stripped
        assertEquals("Order", events.get(2).entity());

        // Name without suffix should be unchanged
        assertEquals("Item", events.get(3).entity());
    }

    @Test
    void shutdownTerminatesExecutor() throws Exception {
        java.lang.reflect.Field executorField = AuditBridge.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        java.util.concurrent.ExecutorService executor = (java.util.concurrent.ExecutorService) executorField.get(auditBridge);

        assertFalse(executor.isTerminated());
        auditBridge.shutdown();
        assertTrue(executor.isTerminated());
    }

    // Test entity classes for version suffix stripping
    static class ProductV1 {
        private String id;

        ProductV1(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    static class CustomerV3 {
        private String id;

        CustomerV3(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    static class OrderV10 {
        private String id;

        OrderV10(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    static class Item {
        private String id;

        Item(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }
}
