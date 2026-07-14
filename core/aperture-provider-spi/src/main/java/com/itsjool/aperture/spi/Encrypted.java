package com.itsjool.aperture.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marker annotation CodeGenerator emits on every generated field where the manifest declares
// encrypted: true, alongside the existing @Convert(converter = ...) annotation it already attaches
// for the field's EncryptionService-backed AttributeConverter. Purely additive: this annotation
// carries no behavior of its own and does not participate in the encrypt/decrypt path. It exists
// solely as a runtime-queryable signal — AuditBridge looks it up via EntityDictionary (reflection)
// to decide whether a changed field's before/after values must be redacted in the audit trail.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Encrypted {
}
