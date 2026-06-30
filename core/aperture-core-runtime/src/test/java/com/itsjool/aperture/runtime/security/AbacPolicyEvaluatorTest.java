package com.itsjool.aperture.runtime.security;

import com.itsjool.aperture.spi.AperturePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbacPolicyEvaluatorTest {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    private boolean evaluate(String expressionString, Object record, Object input, AperturePrincipal principal) {
        Expression expression = parser.parseExpression(expressionString);
        return AbacPolicyEvaluator.evaluate(expression, record, input, principal);
    }

    @Test
    void testAttributeAllow() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of("User"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of("department", "sales"));
        assertThat(evaluate("#user.securityAttributes['department'] == 'sales'", null, null, principal)).isTrue();
    }

    @Test
    void testAttributeDeny() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of("User"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of("department", "marketing"));
        assertThat(evaluate("#user.securityAttributes['department'] == 'sales'", null, null, principal)).isFalse();
    }

    @Test
    void testMissingAttribute() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of("User"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of());
        // Missing attribute returns null, comparison against 'sales' fails safely
        assertThat(evaluate("#user.securityAttributes['department'] == 'sales'", null, null, principal)).isFalse();
        
        // Ensure evaluating a non-existent nested property on a missing attribute doesn't crash but fails closed
        assertThat(evaluate("#user.securityAttributes['missing'].something == 'sales'", null, null, principal)).isFalse();
    }

    @Test
    void testWrongType() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of("User"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of("age", 25));
        // Trying to call string methods on an integer attribute
        assertThat(evaluate("#user.securityAttributes['age'].startsWith('2')", null, null, principal)).isFalse();
    }

    @Test
    void testMaliciousTypeAccess() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of("User"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of());
        // SpEL should block type access since we use SimpleEvaluationContext
        assertThat(evaluate("T(java.lang.Runtime).getRuntime().exec('ls')", null, null, principal)).isFalse();
    }
    
    @Test
    void testMaliciousBeanAccess() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of("User"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of());
        // Bean access is disabled in SimpleEvaluationContext
        assertThat(evaluate("@someBean.doSomething()", null, null, principal)).isFalse();
    }

    @Test
    void testAbsentPrincipal() {
        assertThat(evaluate("#user.roles.contains('Admin')", null, null, null)).isFalse();
        assertThat(evaluate("#user.credentialType == 'anonymous'", null, null, null)).isTrue();
    }

    @Test
    void testRecordOwnership() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of(), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of());
        record Invoice(String ownerId) {}
        
        assertThat(evaluate("#record.ownerId == #user.identityId", new Invoice("user1"), null, principal)).isTrue();
        assertThat(evaluate("#record.ownerId == #user.identityId", new Invoice("user2"), null, principal)).isFalse();
    }

    @Test
    void testTenantComparison() {
        AperturePrincipal principal = new AperturePrincipal("user1", "tenantA", Set.of(), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of());
        record Invoice(String tenantId) {}
        
        assertThat(evaluate("#record.tenantId == #user.tenantId", new Invoice("tenantA"), null, principal)).isTrue();
        assertThat(evaluate("#record.tenantId == #user.tenantId", new Invoice("tenantB"), null, principal)).isFalse();
    }

    @Test
    void subjectDefensivelyCopiesNestedClaims() {
        Set<String> roles = new HashSet<>(Set.of("Viewer"));
        ArrayList<String> regions = new ArrayList<>(java.util.List.of("EU"));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("regions", regions);

        AbacSubject subject = new AbacSubject("user1", "tenantA", roles, attributes, "user");
        roles.add("Admin");
        regions.add("US");
        attributes.put("department", "finance");

        assertThat(subject.roles()).containsExactly("Viewer");
        assertThat(subject.securityAttributes()).doesNotContainKey("department");
        assertThat(subject.securityAttributes().get("regions")).isEqualTo(java.util.List.of("EU"));
        assertThatThrownBy(() -> subject.roles().add("Admin"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((java.util.List<Object>) subject.securityAttributes().get("regions")).add("US"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotInvokeArbitraryRecordMethods() {
        class MutableRecord {
            private boolean invoked;

            public boolean authorizeAndMutate() {
                invoked = true;
                return true;
            }
        }

        MutableRecord record = new MutableRecord();
        assertThat(evaluate("#record.authorizeAndMutate()", record, null, null)).isFalse();
        assertThat(record.invoked).isFalse();
    }

    @Test
    void allowsOnlySafeCollectionMembershipMethod() {
        AperturePrincipal principal = new AperturePrincipal(
                "user1", "tenantA", Set.of("Admin"), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), java.util.Map.of());

        assertThat(evaluate("#user.roles.contains('Admin')", null, null, principal)).isTrue();
    }

    @Test
    void testEvaluatesWithSecurityAttributes() {
        AperturePrincipal principal = new AperturePrincipal("u1", "t1", Set.of(), com.itsjool.aperture.spi.PrincipalKind.USER, Map.of(), Map.of("department", "finance"));
        assertThat(evaluate("#user.securityAttributes['department'] == 'finance'", null, null, principal)).isTrue();
    }
}
