package com.itsjool.aperture.engine.lock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class LockFileManager {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String DOMAIN_MODEL_LOCK_SUFFIX = "-domain-model.json";

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

    public void writeDomainModelLockFile(String version, ResolvedDomainModel model, Path lockDir) {
        try {
            if (!Files.exists(lockDir)) {
                Files.createDirectories(lockDir);
            }
            Path filePath = lockDir.resolve(String.format("%s-domain-model.json", version));
            mapper.writeValue(filePath.toFile(), new DomainModelLock(model.oneOfs()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to write domain model lock file", e);
        }
    }

    public EntityDef readLockFile(Path filePath) {
        try {
            return mapper.readValue(filePath.toFile(), EntityDef.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read lock file: " + filePath, e);
        }
    }

    public DomainModelLock readDomainModelLockFile(Path filePath) {
        try {
            return mapper.readValue(filePath.toFile(), DomainModelLock.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read domain model lock file: " + filePath, e);
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
                 .filter(p -> !isDomainModelLockFile(p))
                 .forEach(p -> entities.add(readLockFile(p)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read lock files from directory: " + lockDir, e);
        }
        return entities;
    }

    public ResolvedDomainModel readLockedDomainModel(Path lockDir) {
        if (!Files.exists(lockDir)) {
            return new ResolvedDomainModel(List.of());
        }
        return new ResolvedDomainModel(
            readAllLockFiles(lockDir),
            List.of(),
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            readDomainModelLock(lockDir).oneOfs());
    }

    private DomainModelLock readDomainModelLock(Path lockDir) {
        try (Stream<Path> paths = Files.walk(lockDir)) {
            return paths.filter(Files::isRegularFile)
                .filter(this::isDomainModelLockFile)
                .sorted(Comparator.comparing(Path::toString))
                .map(this::readDomainModelLockFile)
                .reduce((first, second) -> second)
                .orElse(new DomainModelLock(List.of()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read domain model lock files from directory: " + lockDir, e);
        }
    }

    private boolean isDomainModelLockFile(Path path) {
        return path.getFileName().toString().endsWith(DOMAIN_MODEL_LOCK_SUFFIX);
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
