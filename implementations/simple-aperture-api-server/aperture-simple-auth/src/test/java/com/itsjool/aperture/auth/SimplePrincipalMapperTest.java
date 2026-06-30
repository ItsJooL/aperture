package com.itsjool.aperture.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.ValidationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimplePrincipalMapperTest {
    @Test
    void mapsExactTrustedClaimsAndPreservesNullAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("department", "finance");
        attributes.put("nullable", null);
        var result = new SimpleCredentialValidator.TrustedAccountValidationResult(
                "user-1", "tenant-1", List.of("Viewer", "Auditor"), attributes, java.util.Map.of(), com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT, false, java.util.Set.of(), false);

        AperturePrincipal principal = new SimplePrincipalMapper(Set.of("department", "nullable")).map(result);

        assertThat(principal.userId()).isEqualTo("user-1");
        assertThat(principal.tenantId()).isEqualTo("tenant-1");
        assertThat(principal.roles()).containsExactlyInAnyOrder("Viewer", "Auditor");
        assertThat(principal.profile()).containsExactlyEntriesOf(attributes);
        assertThat(principal.kind()).isEqualTo(com.itsjool.aperture.spi.PrincipalKind.SERVICE_ACCOUNT);
        assertThat(principal.roles()).isUnmodifiable();
        assertThat(principal.profile()).isUnmodifiable();
    }

    @Test
    void doesNotMapInvalidOrUnrelatedResults() {
        SimplePrincipalMapper mapper = new SimplePrincipalMapper(null);

        assertThat(mapper.map(ValidationResult.failure("invalid token"))).isNull();
        assertThat(mapper.map(ValidationResult.success("user-1"))).isNull();
    }
}
