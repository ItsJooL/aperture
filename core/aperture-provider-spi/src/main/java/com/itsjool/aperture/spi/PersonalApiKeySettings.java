package com.itsjool.aperture.spi;

public record PersonalApiKeySettings(
        Boolean enabled,
        Boolean allowNonExpiring,
        Integer defaultTtlDays,
        Integer maxTtlDays) {

    public static final PersonalApiKeySettings DEFAULTS =
            new PersonalApiKeySettings(true, false, 365, 365);

    public PersonalApiKeySettings {
        if (defaultTtlDays != null && defaultTtlDays < 1) {
            throw new IdentityAdministrationValidationException("defaultTtlDays must be at least 1");
        }
        if (maxTtlDays != null && maxTtlDays < 1) {
            throw new IdentityAdministrationValidationException("maxTtlDays must be at least 1");
        }
        if (defaultTtlDays != null && maxTtlDays != null && defaultTtlDays > maxTtlDays) {
            throw new IdentityAdministrationValidationException("defaultTtlDays must not exceed maxTtlDays");
        }
    }

    /**
     * Merges tenant-level overrides onto this (global) baseline, treating global as a ceiling.
     * A TenantAdmin can only restrict relative to global — they cannot relax global restrictions.
     * Rules:
     *   enabled       — tenant can disable but not re-enable if global is false
     *   allowNonExpiring — AND: false if either global or tenant says false
     *   maxTtlDays    — min(global, tenant): tenant cannot exceed the platform cap
     *   defaultTtlDays — clamped to the effective maxTtlDays after merge
     */
    public PersonalApiKeySettings merge(PersonalApiKeySettings override) {
        if (override == null) {
            return this;
        }
        boolean effectiveEnabled = (override.enabled != null ? override.enabled : enabled)
                && Boolean.TRUE.equals(enabled);
        boolean effectiveAllowNonExpiring = Boolean.TRUE.equals(allowNonExpiring)
                && Boolean.TRUE.equals(override.allowNonExpiring != null ? override.allowNonExpiring : allowNonExpiring);
        int effectiveMaxTtl = Math.min(
                maxTtlDays != null ? maxTtlDays : Integer.MAX_VALUE,
                override.maxTtlDays != null ? override.maxTtlDays : (maxTtlDays != null ? maxTtlDays : Integer.MAX_VALUE));
        int effectiveDefaultTtl = Math.min(
                override.defaultTtlDays != null ? override.defaultTtlDays : defaultTtlDays,
                effectiveMaxTtl);
        return new PersonalApiKeySettings(effectiveEnabled, effectiveAllowNonExpiring, effectiveDefaultTtl, effectiveMaxTtl);
    }
}
