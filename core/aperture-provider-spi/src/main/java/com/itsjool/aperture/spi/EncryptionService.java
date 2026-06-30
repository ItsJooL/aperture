package com.itsjool.aperture.spi;
public interface EncryptionService {
    EncryptedValue encrypt(String plaintext, EncryptionContext ctx);
    String decrypt(EncryptedValue value, EncryptionContext ctx);
}
