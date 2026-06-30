package com.itsjool.aperture.starter.oas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnResource(resources = "classpath:aperture-openapi.yaml")
public class ApertureOasController {

    private final String spec;

    public ApertureOasController() {
        try (var in = new ClassPathResource("aperture-openapi.yaml").getInputStream()) {
            this.spec = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("aperture-openapi.yaml not found on classpath — run mvn generate-sources first", e);
        }
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public String openapiYaml() {
        return spec;
    }

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String openapiJson() {
        try {
            ObjectMapper yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            Object obj = yaml.readValue(spec, Object.class);
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert OAS YAML to JSON", e);
        }
    }
}
