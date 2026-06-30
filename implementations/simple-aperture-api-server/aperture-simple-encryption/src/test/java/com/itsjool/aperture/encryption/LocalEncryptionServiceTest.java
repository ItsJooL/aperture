package com.itsjool.aperture.encryption;

import com.itsjool.aperture.spi.EncryptedValue;
import com.itsjool.aperture.spi.EncryptionContext;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEncryptionServiceTest {
    @Test
    void testEncryptDecryptRoundTrip() {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        LocalEncryptionService service = new LocalEncryptionService(base64Key);
        
        EncryptionContext ctx = new EncryptionContext("tenant1", "User", "ssn", false);
        EncryptedValue encrypted = service.encrypt("secret-data", ctx);
        
        assertThat(encrypted.value()).isNotNull().isNotEqualTo("secret-data");
        
        String decrypted = service.decrypt(encrypted, ctx);
        assertThat(decrypted).isEqualTo("secret-data");
    }

    @Test
    void testDeterministicEncryption() {
        String base64Key = Base64.getEncoder().encodeToString(new byte[32]);
        LocalEncryptionService service = new LocalEncryptionService(base64Key);
        
        EncryptionContext ctx = new EncryptionContext("tenant1", "User", "ssn", true);
        EncryptedValue encrypted1 = service.encrypt("secret-data", ctx);
        EncryptedValue encrypted2 = service.encrypt("secret-data", ctx);
        
        assertThat(encrypted1.value()).isEqualTo(encrypted2.value());
        
        String decrypted = service.decrypt(encrypted1, ctx);
        assertThat(decrypted).isEqualTo("secret-data");
    }

    @Test
    void testInvalidKeyLength() {
        String badKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new LocalEncryptionService(badKey))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testDecryptionWithWrongKeyThrowsException() {
        String key1 = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] key2Bytes = new byte[32];
        java.util.Arrays.fill(key2Bytes, (byte) 1);
        String key2 = Base64.getEncoder().encodeToString(key2Bytes);

        LocalEncryptionService service1 = new LocalEncryptionService(key1);
        LocalEncryptionService service2 = new LocalEncryptionService(key2);

        EncryptionContext ctx = new EncryptionContext("tenant1", "User", "ssn", false);
        EncryptedValue encrypted = service1.encrypt("secret-data", ctx);

        // Attempt to decrypt with wrong key should throw EncryptionException
        assertThatThrownBy(() -> service2.decrypt(encrypted, ctx))
            .isInstanceOf(EncryptionException.class)
            .hasMessageContaining("Decryption failed");
    }
}
