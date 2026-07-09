package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.gen.CodeGenerator;
import com.itsjool.aperture.engine.lock.LockFileManager;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class EntityJavaGenerationTarget implements ApertureGenerationTarget {

    @Override
    public String name() {
        return "entity-java";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        return true;
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        StagingGenerationContext staging = (StagingGenerationContext) context;
        CodeGenerator codeGenerator = new CodeGenerator();
        LockFileManager lockManager = new LockFileManager();
        Map<String, EntityDef> allEntities = request.model().entities().stream()
            .collect(Collectors.toMap(EntityDef::name, e -> e));
        for (EntityDef entity : request.model().entities().stream()
                .sorted(Comparator.comparing(EntityDef::name)).toList()) {
            for (String source : codeGenerator.generateForEntity(
                    entity, request.tenancyMode(), request.activeVersions(), allEntities)) {
                staging.writeJavaSourceFromString(source);
            }
            lockManager.writeLockFile(request.activeVersions().getFirst(), entity, staging.locksStaging());
        }
        lockManager.writeDomainModelLockFile(request.activeVersions().getFirst(), request.model(), staging.locksStaging());
    }
}
