package com.itsjool.aperture.encryption;

import com.itsjool.aperture.spi.EncryptionService;
import com.itsjool.aperture.spi.EncryptedValue;
import com.itsjool.aperture.spi.EncryptionContext;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class LocalEncryptionService implements EncryptionService {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalEncryptionService(String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != 32) {
            throw new IllegalArgumentException("Key must be exactly 32 bytes (256 bits) for AES-256");
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    @Override
    public EncryptedValue encrypt(String plaintext, EncryptionContext ctx) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            if (ctx.isDeterministic()) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
                System.arraycopy(hash, 0, iv, 0, GCM_IV_LENGTH);
            } else {
                secureRandom.nextBytes(iv);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);
            
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, encrypted, 0, iv.length);
            System.arraycopy(cipherText, 0, encrypted, iv.length, cipherText.length);
            
            return new EncryptedValue(Base64.getEncoder().encodeToString(encrypted));
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(EncryptedValue value, EncryptionContext ctx) {
        if (value == null || value.value() == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value.value());
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, iv.length);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            byte[] cipherText = new byte[decoded.length - iv.length];
            System.arraycopy(decoded, iv.length, cipherText, 0, cipherText.length);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new EncryptionException("Decryption failed — possible key mismatch or data corruption", e);
        }
    }
}
