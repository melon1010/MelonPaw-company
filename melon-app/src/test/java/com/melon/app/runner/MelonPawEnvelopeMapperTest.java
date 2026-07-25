package com.melon.app.runner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MelonPawEnvelopeMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Test
    void agentEndCompletesAssistantWithoutCompletingResponse() throws Exception {
        MelonPawEnvelopeMapper mapper = new MelonPawEnvelopeMapper("session");
        List<Map<String, Object>> payloads = new ArrayList<>();
        collect(payloads, mapper.translate(new TextBlockStartEvent("reply", "answer")));
        collect(payloads, mapper.translate(new TextBlockDeltaEvent("reply", "answer", "done")));
        collect(payloads, mapper.translate(new TextBlockEndEvent("reply", "answer")));

        collect(payloads, mapper.translate(new AgentEndEvent("reply")));

        assertTrue(payloads.stream().anyMatch(payload ->
                "message".equals(payload.get("object"))
                        && "completed".equals(payload.get("status"))
                        && payload.get("metadata") instanceof Map<?, ?> metadata
                        && "final".equals(metadata.get("phase"))
                        && Boolean.TRUE.equals(metadata.get("terminal"))));
        assertTrue(payloads.stream().anyMatch(payload ->
                "response".equals(payload.get("object"))
                        && "in_progress".equals(payload.get("status"))
                        && "completed".equals(payload.get("assistant_status"))));
        assertFalse(payloads.stream().anyMatch(payload ->
                "response".equals(payload.get("object"))
                        && "completed".equals(payload.get("status"))));

        collect(payloads, mapper.finish());

        assertTrue(payloads.stream().anyMatch(payload ->
                "response".equals(payload.get("object"))
                        && "completed".equals(payload.get("status"))));
    }

    private void collect(List<Map<String, Object>> payloads,
                         List<ServerSentEvent<String>> events) throws Exception {
        for (ServerSentEvent<String> event : events) {
            payloads.add(JSON.readValue(event.data(), MAP));
        }
    }
}
