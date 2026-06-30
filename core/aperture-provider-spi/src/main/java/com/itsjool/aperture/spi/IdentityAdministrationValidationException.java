package com.itsjool.aperture.spi;

public class IdentityAdministrationValidationException extends RuntimeException {
    public IdentityAdministrationValidationException(String message) {
        super(message);
    }

    public IdentityAdministrationValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
