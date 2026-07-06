package com.itsjool.aperture.generation.target;

import com.itsjool.aperture.engine.gen.CodeGenerator;
import com.itsjool.aperture.generation.context.StagingGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationContext;
import com.itsjool.aperture.generation.spi.ApertureGenerationRequest;
import com.itsjool.aperture.generation.spi.ApertureGenerationTarget;

public class SecurityChecksGenerationTarget implements ApertureGenerationTarget {

    @Override
    public String name() {
        return "security-checks";
    }

    @Override
    public boolean enabled(ApertureGenerationRequest request) {
        return true;
    }

    @Override
    public void generate(ApertureGenerationRequest request, ApertureGenerationContext context) throws Exception {
        StagingGenerationContext staging = (StagingGenerationContext) context;
        CodeGenerator codeGenerator = new CodeGenerator();
        for (String source : codeGenerator.generateAdminChecks()) {
            staging.writeJavaSourceFromString(source);
        }
        if (request.model().abacPolicies() != null && !request.model().abacPolicies().isEmpty()) {
            for (String source : codeGenerator.generatePolicyChecks(request.model().abacPolicies())) {
                staging.writeJavaSourceFromString(source);
            }
        }
    }
}
