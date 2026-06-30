package com.itsjool.aperture.demo.oas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.demo.DemoApplicationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OasComponentTest extends DemoApplicationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesYamlAndJsonOasEndpoints() throws Exception {
        // Test YAML
        MvcResult yamlResult = mockMvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andReturn();
        String yamlBody = yamlResult.getResponse().getContentAsString();
        assertThat(yamlResult.getResponse().getContentType()).contains("application/yaml");
        assertThat(yamlBody).contains("openapi: \"3.1.0\"");
        assertThat(yamlBody).contains("/api/v1/invoices:");
        assertThat(yamlBody).contains("/auth/login:");
        assertThat(yamlBody).contains("/manage/tenants:");

        // Test JSON
        MvcResult jsonResult = mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andReturn();
        String jsonBody = jsonResult.getResponse().getContentAsString();
        assertThat(jsonResult.getResponse().getContentType()).contains("application/json");
        
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> parsed = mapper.readValue(jsonBody, Map.class);
        assertThat(parsed).containsKey("openapi");
        assertThat(parsed).containsKey("paths");
    }
}
