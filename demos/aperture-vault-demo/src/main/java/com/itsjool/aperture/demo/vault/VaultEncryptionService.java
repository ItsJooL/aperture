package com.itsjool.aperture.demo.vault;

import com.itsjool.aperture.spi.EncryptedValue;
import com.itsjool.aperture.spi.EncryptionContext;
import com.itsjool.aperture.spi.EncryptionService;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.vault.support.VaultTransitKey;

import java.nio.charset.StandardCharsets;

public class VaultEncryptionService implements EncryptionService {

    private final VaultOperations vaultOperations;
    private final String transitKeyName;

    public VaultEncryptionService(VaultOperations vaultOperations, String transitKeyName) {
        this.vaultOperations = vaultOperations;
        this.transitKeyName = transitKeyName;
        
        // Ensure the transit key exists in Vault during initialization
        VaultTransitKey key = vaultOperations.opsForTransit().getKey(transitKeyName);
        if (key == null) {
            vaultOperations.opsForTransit().createKey(
                    transitKeyName,
                    org.springframework.vault.support.VaultTransitKeyCreationRequest.builder()
                            .derived(true)
                            .build()
            );
        }
    }

    @Override
    public EncryptedValue encrypt(String plaintext, EncryptionContext context) {
        if (plaintext == null) return null;
        
        // Context contains the tenantId and entity/field names - perfect for Transit Context binding
        byte[] contextBytes = (context.tenantId() + ":" + context.entity()).getBytes(StandardCharsets.UTF_8);
        VaultTransitContext transitContext = VaultTransitContext.builder().context(contextBytes).build();
        
        try {
            Ciphertext ciphertext = vaultOperations.opsForTransit().encrypt(
                    transitKeyName, Plaintext.of(plaintext).with(transitContext)
            );
            return new EncryptedValue(ciphertext.getCiphertext());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Vault Transit encryption failed for key '" + transitKeyName + "'", ex);
        }
    }

    @Override
    public String decrypt(EncryptedValue encryptedValue, EncryptionContext context) {
        if (encryptedValue == null || encryptedValue.value() == null) return null;
        
        byte[] contextBytes = (context.tenantId() + ":" + context.entity()).getBytes(StandardCharsets.UTF_8);
        VaultTransitContext transitContext = VaultTransitContext.builder().context(contextBytes).build();
        
        try {
            Plaintext plaintext = vaultOperations.opsForTransit().decrypt(
                    transitKeyName, Ciphertext.of(encryptedValue.value()).with(transitContext)
            );
            return new String(plaintext.getPlaintext(), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Vault Transit decryption failed for key '" + transitKeyName + "'", ex);
        }
    }
}
