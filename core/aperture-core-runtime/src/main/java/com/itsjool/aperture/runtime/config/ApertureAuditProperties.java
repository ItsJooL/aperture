package com.itsjool.aperture.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "aperture.audit")
public class ApertureAuditProperties {
    private final Redaction redaction = new Redaction();

    public Redaction getRedaction() {
        return redaction;
    }

    public static final class Redaction {
        // Redact by default: an entity/field pair carrying the codegen-emitted @Encrypted marker
        // is sentinel-replaced in the audit trail unless this is turned off or the pair is
        // explicitly exempted below.
        private boolean enabled = true;
        private List<Exemption> exemptions = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<Exemption> getExemptions() {
            return exemptions;
        }

        public void setExemptions(List<Exemption> exemptions) {
            this.exemptions = exemptions;
        }
    }

    public static final class Exemption {
        private String entity;
        private String field;

        public String getEntity() {
            return entity;
        }

        public void setEntity(String entity) {
            this.entity = entity;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }
    }
}
