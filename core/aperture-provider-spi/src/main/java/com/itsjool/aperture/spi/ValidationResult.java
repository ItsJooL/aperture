package com.itsjool.aperture.spi;

public interface ValidationResult {
    boolean isValid();
    String tokenSubject();
    String errorMessage();

    static ValidationResult success(String subject) {
        return new ValidationResult() {
            public boolean isValid() { return true; }
            public String tokenSubject() { return subject; }
            public String errorMessage() { return null; }
        };
    }

    static ValidationResult failure(String error) {
        return new ValidationResult() {
            public boolean isValid() { return false; }
            public String tokenSubject() { return null; }
            public String errorMessage() { return error; }
        };
    }
}
