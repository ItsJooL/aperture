package com.itsjool.aperture.runtime.scope;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds the per-request value(s) for entities declaring {@code scopedBy: <field>} in their
 * manifest — e.g. a {@code Task} scoped by its {@code project} relationship. Keyed by the
 * relationship field name (lowercase), mirroring how {@link com.itsjool.aperture.runtime.tenant.TenantContextHolder}
 * holds exactly one value for tenant scoping.
 *
 * <p>Unlike tenant scoping, there is no principal-embedded claim to fall back to — a
 * regular user's tenant comes from their own JWT, but no principal here carries "which
 * project(s) do you belong to." The value populated here always comes from an explicit
 * request header (see the filter that populates this holder), for any authenticated caller
 * who already has ordinary read/write permission on the entity. This is a mandatory query
 * filter, not a per-user access grant: it does not verify the caller "belongs" to the scope
 * value they supply, only that they picked one. Real per-scope authorization requires
 * pairing this with an ABAC policy or equivalent, which this holder does not provide.
 */
public final class ScopeContextHolder {
    private static final ThreadLocal<Map<String, String>> CONTEXT = ThreadLocal.withInitial(HashMap::new);

    private ScopeContextHolder() {
    }

    public static void set(String field, String value) {
        CONTEXT.get().put(field, value);
    }

    public static String get(String field) {
        return CONTEXT.get().get(field);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** Copy of the current thread's scope map, for handoff across thread boundaries
     *  (Elide's Spring controllers return {@code Callable}, so requests are processed
     *  on an MVC async thread, not the servlet thread that populated this holder). */
    public static Map<String, String> snapshot() {
        return new HashMap<>(CONTEXT.get());
    }

    public static void restore(Map<String, String> values) {
        CONTEXT.set(new HashMap<>(values));
    }
}
