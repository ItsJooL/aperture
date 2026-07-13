package com.itsjool.aperture.demo.vault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.vault.core.VaultOperations;

// Plan 030: the extra "com.itsjool.aperture.auth" entry was investigated and found redundant —
// its one scan-discoverable @RestController (AuthController) is also explicitly @Bean-wired by
// SimpleAuthConfiguration, which is now reachable via .imports regardless of this app's own scan.
@SpringBootApplication(scanBasePackages = {"com.itsjool.aperture.demo.vault"})
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
