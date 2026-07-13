package com.itsjool.aperture.keycloak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = {"com.itsjool.aperture.keycloak"})
@EntityScan(basePackages = {"com.itsjool.aperture"})
public class KeycloakDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(KeycloakDemoApplication.class, args);
    }
}
