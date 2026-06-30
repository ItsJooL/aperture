package com.itsjool.aperture.spi;

public class IdentityAdministrationConflictException extends RuntimeException {
    public IdentityAdministrationConflictException(String message) {
        super(message);
    }

    public IdentityAdministrationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
