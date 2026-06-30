package com.itsjool.aperture.spi;

public class IdentityAdministrationNotFoundException extends RuntimeException {
    public IdentityAdministrationNotFoundException(String message) {
        super(message);
    }

    public IdentityAdministrationNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
