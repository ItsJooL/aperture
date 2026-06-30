package com.itsjool.aperture.engine.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class PublicationManager {

    private final Path journalFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public PublicationManager(Path baseDirectory) {
        this.journalFile = baseDirectory.resolveSibling(".aperture.journal");
    }

    public void publish(List<Path> targets, List<Path> lockFiles, java.util.Map<Path, Path> stagingMap, String failureInjectionPoint) {
        if (targets == null) {
            targets = new ArrayList<>();
        }
        if (lockFiles == null) {
            lockFiles = new ArrayList<>();
        }
        if (stagingMap == null) {
            stagingMap = new java.util.HashMap<>();
        }

        java.util.Set<Path> uniqueTargets = new java.util.LinkedHashSet<>();
        for (Path target : targets) {
            if (!uniqueTargets.add(target.toAbsolutePath())) {
                throw new IllegalArgumentException("Duplicate target path: " + target);
            }
        }
        targets = new ArrayList<>(uniqueTargets);

        PublicationJournal journal = new PublicationJournal();
        journal.setState(PublicationJournal.State.PREPARED);
        
        List<PublicationJournal.FileMapping> mappings = new ArrayList<>();
        
        // Add normal targets
        for (Path target : targets) {
            mappings.add(createMapping(target, stagingMap.get(target)));
        }
        
        // Add lock files if present
        for (Path lockFile : lockFiles) {
            if (!uniqueTargets.add(lockFile.toAbsolutePath())) {
                throw new IllegalArgumentException("Duplicate target path (lock file): " + lockFile);
            }
            mappings.add(createMapping(lockFile.toAbsolutePath(), stagingMap.get(lockFile)));
        }

        journal.setMappings(mappings);

        for (PublicationJournal.FileMapping mapping : mappings) {
            if (mapping.getStaging() != null) {
                Path stagingPath = Path.of(mapping.getStaging());
                if (stagingMap.containsKey(Path.of(mapping.getTarget())) && !Files.exists(stagingPath)) {
                    throw new IllegalArgumentException("Staging file does not exist: " + mapping.getStaging());
                }
            }
        }

        writeJournal(journal);

        injectFailure(failureInjectionPoint, "AFTER_JOURNAL_WRITE");

        java.util.Set<Path> absoluteLockFiles = new java.util.HashSet<>();
        for (Path lockFile : lockFiles) absoluteLockFiles.add(lockFile.toAbsolutePath());

        try {
            for (PublicationJournal.FileMapping mapping : journal.getMappings()) {
                processMapping(mapping);
                Path targetPath = Path.of(mapping.getTarget());
                if (absoluteLockFiles.contains(targetPath.toAbsolutePath())) {
                    injectFailure(failureInjectionPoint, "AFTER_LOCKFILE");
                } else {
                    injectFailure(failureInjectionPoint, "AFTER_TARGET_" + targetPath.getFileName());
                }
            }

            journal.setState(PublicationJournal.State.COMMITTED);
            writeJournal(journal);

            injectFailure(failureInjectionPoint, "AFTER_COMMIT");

            // Delete backups and staging
            for (PublicationJournal.FileMapping mapping : journal.getMappings()) {
                Files.deleteIfExists(Path.of(mapping.getBackup()));
                if (mapping.getStaging() != null) {
                    Files.deleteIfExists(Path.of(mapping.getStaging()));
                }
            }

            Files.deleteIfExists(journalFile);

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Publication failed", e);
        }
    }

    public void recover() {
        if (!Files.exists(journalFile)) {
            return;
        }

        try {
            PublicationJournal journal = mapper.readValue(journalFile.toFile(), PublicationJournal.class);
            
            if (journal.getState() == PublicationJournal.State.PREPARED) {
                for (PublicationJournal.FileMapping mapping : journal.getMappings()) {
                    Path target = Path.of(mapping.getTarget());
                    Path backup = Path.of(mapping.getBackup());
                    Path staging = Path.of(mapping.getStaging());

                    // Simple recovery as per prompt: "move backups back to targets, then delete staging."
                    if (Files.exists(backup)) {
                        Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                    } else if (!mapping.isOriginalTargetExisted()) {
                        Files.deleteIfExists(target);
                    }

                    if (mapping.getStaging() != null) {
                        Files.deleteIfExists(Path.of(mapping.getStaging()));
                    }
                }
                Files.deleteIfExists(journalFile);
                
            } else if (journal.getState() == PublicationJournal.State.COMMITTED) {
                for (PublicationJournal.FileMapping mapping : journal.getMappings()) {
                    Files.deleteIfExists(Path.of(mapping.getBackup()));
                    if (mapping.getStaging() != null) {
                        Files.deleteIfExists(Path.of(mapping.getStaging()));
                    }
                }
                Files.deleteIfExists(journalFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Recovery failed", e);
        }
    }

    private PublicationJournal.FileMapping createMapping(Path target, Path providedStaging) {
        PublicationJournal.FileMapping mapping = new PublicationJournal.FileMapping();
        mapping.setTarget(target.toAbsolutePath().toString());
        mapping.setStaging(providedStaging != null ? providedStaging.toAbsolutePath().toString() : null);
        mapping.setBackup(target.toAbsolutePath().toString() + ".backup");
        mapping.setOriginalTargetExisted(Files.exists(target.toAbsolutePath()));
        return mapping;
    }

    private void processMapping(PublicationJournal.FileMapping mapping) throws IOException {
        Path target = Path.of(mapping.getTarget());
        Path backup = Path.of(mapping.getBackup());

        if (Files.exists(target)) {
            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        if (mapping.getStaging() != null) {
            Path staging = Path.of(mapping.getStaging());
            if (Files.exists(staging)) {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writeJournal(PublicationJournal journal) {
        Path tmpFile = journalFile.resolveSibling(journalFile.getFileName() + ".tmp");
        try {
            mapper.writeValue(tmpFile.toFile(), journal);
            Files.move(tmpFile, journalFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write journal", e);
        }
    }

    private void injectFailure(String injectionPoint, String currentStage) {
        if (injectionPoint != null && injectionPoint.equals(currentStage)) {
            throw new RuntimeException("Simulated crash");
        }
    }
}
