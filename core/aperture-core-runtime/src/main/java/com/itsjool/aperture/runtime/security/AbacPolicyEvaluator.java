package com.itsjool.aperture.runtime.security;

import com.itsjool.aperture.spi.AperturePrincipal;
import org.springframework.expression.Expression;
import org.springframework.expression.AccessException;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;

import java.util.Map;
import java.util.Set;

public class AbacPolicyEvaluator {
    private static final ReflectiveMethodResolver REFLECTIVE_METHOD_RESOLVER = new ReflectiveMethodResolver();
    private static final MethodResolver SAFE_METHOD_RESOLVER = (context, target, name, argumentTypes) -> {
        if (target instanceof java.util.Collection<?> && "contains".equals(name) && argumentTypes.size() == 1) {
            return REFLECTIVE_METHOD_RESOLVER.resolve(context, target, name, argumentTypes);
        }
        throw new AccessException("Method invocation is not allowed in ABAC policies");
    };

    public static boolean evaluate(Expression expression, Object record, Object input, AperturePrincipal principal) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withMethodResolvers(SAFE_METHOD_RESOLVER)
                .build();
        context.setVariable("record", record);
        context.setVariable("input", input);

        AbacSubject subject;
        if (principal != null) {
            subject = new AbacSubject(
                principal.userId(),
                principal.tenantId(),
                principal.roles() != null ? principal.roles() : Set.of(),
                principal.securityAttributes() != null ? principal.securityAttributes() : Map.of(),
                principal.kind() != null ? principal.kind().name().toLowerCase() : "anonymous"
            );
        } else {
            subject = new AbacSubject(null, null, Set.of(), Map.of(), "anonymous");
        }
        context.setVariable("user", subject);

        try {
            Boolean result = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            // Fail closed on evaluation errors without leaking internals
            return false;
        }
    }
}
