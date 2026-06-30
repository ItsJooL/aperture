package com.itsjool.aperture.spi;
import jakarta.servlet.http.HttpServletRequest;
public interface CredentialValidator { ValidationResult validate(HttpServletRequest req); }
