package com.itsjool.aperture.engine.hook;

import com.itsjool.aperture.engine.validator.ManifestValidationException;

public class HookSemanticException extends ManifestValidationException {
    public HookSemanticException(String message) {
        super(message);
    }
}
