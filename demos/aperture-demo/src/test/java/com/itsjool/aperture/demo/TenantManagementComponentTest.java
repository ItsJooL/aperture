package com.itsjool.aperture.demo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public class TenantManagementComponentTest extends DemoApplicationTestSupport {
    @Test
    public void testProvisionTenant() throws Exception {
        performElideRequest(post("/manage/tenants")
                .header("Authorization", getSuperAdminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantName\":\"new-corp\",\"initialAdminUsername\":\"admin\",\"initialAdminPassword\":\"password\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant.id").exists())
                .andExpect(jsonPath("$.tenant.name").value("new-corp"));

        // Real assertion: verify tenant created
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM aperture_tenants WHERE name = 'new-corp'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
