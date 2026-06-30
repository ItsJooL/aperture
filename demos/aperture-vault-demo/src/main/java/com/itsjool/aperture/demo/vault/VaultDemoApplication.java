package com.itsjool.aperture.demo.vault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.vault.core.VaultOperations;

@SpringBootApplication(scanBasePackages = {"com.itsjool.aperture", "com.itsjool.aperture.auth"})
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {"com.itsjool.aperture"})
public class VaultDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaultDemoApplication.class, args);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public VaultEncryptionService vaultEncryptionService(VaultOperations vaultOperations) {
        // Overrides the LocalEncryptionService from ApertureSimpleAutoConfiguration
        return new VaultEncryptionService(vaultOperations, "aperture-master-key");
    }
}
