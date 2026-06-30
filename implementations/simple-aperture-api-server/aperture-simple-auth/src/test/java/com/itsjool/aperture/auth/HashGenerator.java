package com.itsjool.aperture.auth;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public class HashGenerator {
    public static void main(String[] args) {
        Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        System.out.println("HASH_IS:" + encoder.encode("password"));
    }
}
