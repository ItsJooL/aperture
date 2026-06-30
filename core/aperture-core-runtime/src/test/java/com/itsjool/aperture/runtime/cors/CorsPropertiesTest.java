package com.itsjool.aperture.runtime.cors;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class CorsPropertiesTest {

    @Test
    void validate_enabledWithNoOrigins_throwsIllegalStateException() {
        CorsProperties props = new CorsProperties();
        props.setEnabled(true);
        // allowedOrigins and allowedOriginPatterns default to empty lists
        assertThatThrownBy(props::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no aperture.cors.allowed-origins");
    }

    @Test
    void validate_enabledWithOrigin_doesNotThrow() {
        CorsProperties props = new CorsProperties();
        props.setEnabled(true);
        props.setAllowedOrigins(List.of("http://localhost:3000"));
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_disabledWithNoOrigins_doesNotThrow() {
        CorsProperties props = new CorsProperties();
        // enabled=false by default
        assertThatCode(props::validate).doesNotThrowAnyException();
    }
}
