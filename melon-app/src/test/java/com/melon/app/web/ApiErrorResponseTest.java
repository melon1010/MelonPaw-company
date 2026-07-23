package com.melon.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiErrorResponseTest {

    @Test
    void keepsCompatibilityFieldsAtTheTopLevel() throws Exception {
        ApiErrorResponse response = ApiErrorResponse.of(
                "ACTIVE_TASK", "Task already running", "request-1",
                Map.of("task_id", "task-1", "reconnect", true));

        JsonNode json = JsonUtils.getMapper().readTree(JsonUtils.toJson(response));

        assertEquals("ACTIVE_TASK", json.path("code").asText());
        assertEquals("Task already running", json.path("detail").asText());
        assertEquals("request-1", json.path("request_id").asText());
        assertEquals("task-1", json.path("task_id").asText());
        assertFalse(json.has("attributes"));
    }
}
