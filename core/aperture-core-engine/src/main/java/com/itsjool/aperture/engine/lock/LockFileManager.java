package com.itsjool.aperture.engine.lock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.engine.model.EntityDef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class LockFileManager {
    private final ObjectMapper mapper = new ObjectMapper();

    public void writeLockFile(String version, EntityDef entity, Path lockDir) {
        try {
            if (!Files.exists(lockDir)) {
                Files.createDirectories(lockDir);
            }
            String fileName = String.format("%s-%s.json", version, entity.name());
            Path filePath = lockDir.resolve(fileName);
            mapper.writeValue(filePath.toFile(), entity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write lock file", e);
        }
    }

    public EntityDef readLockFile(Path filePath) {
        try {
            return mapper.readValue(filePath.toFile(), EntityDef.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read lock file: " + filePath, e);
        }
    }

    public List<EntityDef> readAllLockFiles(Path lockDir) {
        List<EntityDef> entities = new ArrayList<>();
        if (!Files.exists(lockDir)) {
            return entities;
        }
        try (Stream<Path> paths = Files.walk(lockDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".json"))
                 .forEach(p -> entities.add(readLockFile(p)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read lock files from directory: " + lockDir, e);
        }
        return entities;
    }

    public String hashEntity(EntityDef entity) {
        try {
            String json = mapper.writeValueAsString(entity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash entity", e);
        }
    }
}
