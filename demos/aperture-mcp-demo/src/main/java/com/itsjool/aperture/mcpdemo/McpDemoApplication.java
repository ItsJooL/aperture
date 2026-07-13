package com.itsjool.aperture.mcpdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = {"com.itsjool.aperture.mcpdemo"})
@EntityScan(basePackages = {"com.itsjool.aperture"})
public class McpDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpDemoApplication.class, args);
    }
}
