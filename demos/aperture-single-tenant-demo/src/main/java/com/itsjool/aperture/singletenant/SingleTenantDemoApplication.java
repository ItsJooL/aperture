package com.itsjool.aperture.singletenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = {"com.itsjool.aperture"})
@EntityScan(basePackages = {"com.itsjool.aperture"})
public class SingleTenantDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SingleTenantDemoApplication.class, args);
    }
}
