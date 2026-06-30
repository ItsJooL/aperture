package com.itsjool.aperture.runtime.controller;

import com.itsjool.aperture.spi.AperturePrincipal;
import com.itsjool.aperture.spi.IdentityAdministrationProvider;
import com.itsjool.aperture.spi.PersonalApiKeySettings;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/manage/settings")
public class IdentitySettingsController {
    private final IdentityAdministrationProvider provider;

    public IdentitySettingsController(IdentityAdministrationProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/personal-api-keys")
    public ResponseEntity<PersonalApiKeySettings> getGlobalPersonalApiKeySettings(Principal principal) {
        requireSuperAdmin(principal);
        return ResponseEntity.ok(provider.getGlobalPersonalApiKeySettings());
    }

    @PutMapping("/personal-api-keys")
    public ResponseEntity<PersonalApiKeySettings> updateGlobalPersonalApiKeySettings(
            @RequestBody PersonalApiKeySettings settings, Principal principal) {
        requireSuperAdmin(principal);
        provider.updateGlobalPersonalApiKeySettings(settings);
        return ResponseEntity.ok(provider.getGlobalPersonalApiKeySettings());
    }

    private void requireSuperAdmin(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        AperturePrincipal p = (AperturePrincipal) principal;
        if (!p.superAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires SuperAdmin");
        }
    }
}
