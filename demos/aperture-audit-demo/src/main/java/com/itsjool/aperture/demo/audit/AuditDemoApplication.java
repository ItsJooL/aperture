package com.itsjool.aperture.demo.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = {"com.itsjool.aperture"})
@EntityScan(basePackages = {"com.itsjool.aperture"})
public class AuditDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditDemoApplication.class, args);
    }
}
