package com.itsjool.aperture.runtime.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/manage/migrations")
public class MigrationManagementController {
    @GetMapping("/status")
    public Map<String, Integer> getStatus() {
        return Map.of("applied", 10, "pending", 0);
    }
}
