package com.itsjool.aperture.demo.audit;

import com.itsjool.aperture.audit.JdbcAuditWriter;
import com.itsjool.aperture.audit.webhook.WebhookAuditWriter;
import com.itsjool.aperture.spi.AuditEvent;
import com.itsjool.aperture.spi.AuditWriter;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.time.Duration;

@Configuration
class AuditDemoAuditConfiguration {
    @Bean
    WebhookAuditWriter webhookAuditWriter(
            @Value("${aperture.audit.webhook.url}") URI url,
            @Value("${aperture.audit.webhook.batch-size:10}") int batchSize,
            @Value("${aperture.audit.webhook.flush-interval:PT1S}") Duration flushInterval) {
        return new WebhookAuditWriter(url, batchSize, flushInterval, 10_000);
    }

    @Bean
    @Primary
    AuditWriter compositeAuditWriter(JdbcAuditWriter jdbcAuditWriter, WebhookAuditWriter webhookAuditWriter) {
        return new CompositeAuditWriter(jdbcAuditWriter, webhookAuditWriter);
    }

    private static final class CompositeAuditWriter implements AuditWriter {
        private final AuditWriter jdbcAuditWriter;
        private final WebhookAuditWriter webhookAuditWriter;

        private CompositeAuditWriter(AuditWriter jdbcAuditWriter, WebhookAuditWriter webhookAuditWriter) {
            this.jdbcAuditWriter = jdbcAuditWriter;
            this.webhookAuditWriter = webhookAuditWriter;
        }

        @Override
        public void write(AuditEvent event) {
            jdbcAuditWriter.write(event);
            webhookAuditWriter.write(event);
        }

        @PreDestroy
        void shutdown() {
            webhookAuditWriter.shutdown();
        }
    }
}
