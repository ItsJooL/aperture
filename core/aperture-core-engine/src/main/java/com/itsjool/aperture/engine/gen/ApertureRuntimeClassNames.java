package com.itsjool.aperture.engine.gen;

/**
 * Fully-qualified class names from aperture-core-runtime that CodeGenerator embeds as string
 * literals in generated Java source. There is no Maven compile-time dependency between
 * aperture-core-engine and aperture-core-runtime, so renaming any of these classes will break
 * generated code at the consumer's build. Keep these constants in sync with the runtime module.
 */
final class ApertureRuntimeClassNames {
    static final String AUDIT_BRIDGE          = "com.itsjool.aperture.runtime.audit.AuditBridge";
    static final String SPRING_CONTEXT_HELPER = "com.itsjool.aperture.runtime.util.SpringContextHelper";
    static final String TENANT_CONTEXT_HOLDER = "com.itsjool.aperture.runtime.tenant.TenantContextHolder";
    static final String SCOPE_CONTEXT_HOLDER  = "com.itsjool.aperture.runtime.scope.ScopeContextHolder";
    static final String ABAC_POLICY_EVALUATOR = "com.itsjool.aperture.runtime.security.AbacPolicyEvaluator";
    static final String HOOK_EXECUTOR         = "com.itsjool.aperture.runtime.hook.HookExecutor";
    static final String HOOK_PAYLOAD_BUILDER  = "com.itsjool.aperture.runtime.hook.HookPayloadBuilder";
    static final String APERTURE_PRINCIPAL    = "com.itsjool.aperture.spi.AperturePrincipal";

    private ApertureRuntimeClassNames() {}
}
