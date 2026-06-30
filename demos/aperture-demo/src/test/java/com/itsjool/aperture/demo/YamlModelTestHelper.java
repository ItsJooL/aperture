package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class YamlModelTestHelper {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public static Stream<Arguments> provideRbacTestCases() throws Exception {
        List<Arguments> cases = new ArrayList<>();
        Path domainDir = Paths.get("manifests/domain");
        if (!Files.exists(domainDir)) return Stream.empty();
        
        Files.walk(domainDir)
            .filter(p -> p.toString().endsWith(".yaml"))
            .forEach(p -> {
                try {
                    JsonNode node = YAML_MAPPER.readTree(p.toFile());
                    if (node.has("kind") && "Entity".equals(node.get("kind").asText())) {
                        JsonNode specNode = node.get("spec");
                        String entityPath = specNode.has("plural") ? specNode.get("plural").asText().toLowerCase() : node.get("metadata").get("name").asText().toLowerCase() + "s";
                        JsonNode perms = specNode.get("permissions");
                        if (perms != null && perms.isObject()) {
                            perms.fields().forEachRemaining(entry -> {
                                String role = entry.getKey();
                                for (JsonNode op : entry.getValue()) {
                                    if ("read".equals(op.asText())) {
                                        cases.add(Arguments.of(entityPath, "GET", role, 200));
                                    }
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        return cases.stream();
    }
}
