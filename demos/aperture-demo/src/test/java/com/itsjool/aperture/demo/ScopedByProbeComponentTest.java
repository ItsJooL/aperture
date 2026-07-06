package com.itsjool.aperture.demo;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A {@code scopedBy: project} entity (Task) fails closed on the X-Aperture-Scope-Project header
 * for single-object operations — the CreatePermission expression embeds the same filterChecks
 * string as ReadPermission, and Elide evaluates ReadPermission when serializing the created
 * entity back in the response, so a create without the header is rejected (403) even though the
 * record itself would otherwise be valid. A create with the matching header succeeds.
 *
 * <p>Collection reads (list) do not exhibit the same fail-closed behavior: the scope check is
 * evaluated there as a SQL filter predicate rather than a single-object permission check, so a
 * list without the header returns 200 with an empty page rather than 403. Both behaviors are
 * asserted here as verified, not assumed.
 */
public class ScopedByProbeComponentTest extends DemoApplicationTestSupport {

    @Test
    public void createWithoutScopeHeaderIsRejectedButMatchingScopeSucceeds() throws Exception {
        String token = getAcmeAdminToken();

        // Create a Project (not scopedBy — should just work).
        var projectResult = performElideRequest(post("/api/v1/projects")
                .header("Authorization", token)
                .accept(MediaType.parseMediaType("application/vnd.api+json"))
                .contentType(MediaType.parseMediaType("application/vnd.api+json"))
                .content("{\"data\":{\"type\":\"projects\",\"attributes\":{\"name\":\"Probe Project\"}}}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode projectRoot = MAPPER.readTree(projectResult.getResponse().getContentAsString());
        String projectId = projectRoot.path("data").path("id").asText();

        // Create a Task with NO X-Aperture-Scope-Project header at all — fail-closed rejects the
        // read-back of the just-created record (same filterChecks string as ReadPermission).
        String createBody = "{\"data\":{\"type\":\"tasks\",\"attributes\":{\"title\":\"Probe Task\"},"
                + "\"relationships\":{\"project\":{\"data\":{\"type\":\"projects\",\"id\":\"" + projectId + "\"}}}}}";
        performElideRequest(post("/api/v1/tasks")
                .header("Authorization", token)
                .accept(MediaType.parseMediaType("application/vnd.api+json"))
                .contentType(MediaType.parseMediaType("application/vnd.api+json"))
                .content(createBody))
                .andExpect(status().isForbidden());

        // Create a Task WITH the matching X-Aperture-Scope-Project header — succeeds.
        var taskResultScoped = performElideRequest(post("/api/v1/tasks")
                .header("Authorization", token)
                .header("X-Aperture-Scope-Project", projectId)
                .accept(MediaType.parseMediaType("application/vnd.api+json"))
                .contentType(MediaType.parseMediaType("application/vnd.api+json"))
                .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode taskRoot = MAPPER.readTree(taskResultScoped.getResponse().getContentAsString());
        String taskId = taskRoot.path("data").path("id").asText();
        assertThat(taskId).isNotBlank();

        // List tasks with no scope header — NOTE this does not fail closed the same way create's
        // read-back does: a collection query evaluates the scope FilterExpressionCheck as a SQL
        // predicate rather than a single-object permission check, so the thrown
        // ForbiddenAccessException from getFilterExpression() does not propagate as a 403 here;
        // it yields an empty page instead. Asserting the actual (verified) behavior, not the
        // single-object contract — see class javadoc.
        performElideRequest(get("/api/v1/tasks")
                .header("Authorization", token)
                .accept(MediaType.parseMediaType("application/vnd.api+json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        // List tasks with the matching scope header — succeeds and returns the created task.
        performElideRequest(get("/api/v1/tasks")
                .header("Authorization", token)
                .header("X-Aperture-Scope-Project", projectId)
                .accept(MediaType.parseMediaType("application/vnd.api+json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.id=='" + taskId + "')]").exists());

        // SuperAdmin bypass — no scope header, needs tenant context header instead since tenancy is POOL.
        String superAdminToken = getSuperAdminToken();
        String tenantId = "00000000-0000-0000-0000-000000000001"; // acme-corp, from init/tenants.sql
        performElideRequest(get("/api/v1/tasks")
                .header("Authorization", superAdminToken)
                .header("X-Aperture-Tenant-Context", tenantId)
                .accept(MediaType.parseMediaType("application/vnd.api+json")))
                .andExpect(status().isOk());
    }
}
