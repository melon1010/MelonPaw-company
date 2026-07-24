package com.melon.core.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ToolRejectionGuardMiddlewareTest {

    private final ToolUseBlock call = ToolUseBlock.builder()
            .id("call-1")
            .name("execute_shell_command")
            .build();

    @Test
    void stopsTheReasoningLoopAfterARejectedTool() {
        ToolRejectionGuardMiddleware middleware = new ToolRejectionGuardMiddleware();

        List<AgentEvent> events = middleware.onActing(null, RuntimeContext.empty(),
                        new ActingInput(List.of(call)), ignored -> Flux.just(
                                new ToolResultEndEvent("reply-1", "call-1",
                                        "execute_shell_command", ToolResultState.DENIED)))
                .collectList()
                .block();

        assertEquals(2, events.size());
        RequestStopEvent stop = assertInstanceOf(RequestStopEvent.class, events.get(1));
        assertEquals(GenerateReason.ALL_TOOLS_DENIED, stop.getGenerateReason());
    }

    @Test
    void doesNotStopAfterASuccessfulTool() {
        ToolRejectionGuardMiddleware middleware = new ToolRejectionGuardMiddleware();

        List<AgentEvent> events = middleware.onActing(null, RuntimeContext.empty(),
                        new ActingInput(List.of(call)), ignored -> Flux.just(
                                new ToolResultEndEvent("reply-1", "call-1",
                                        "execute_shell_command", ToolResultState.SUCCESS)))
                .collectList()
                .block();

        assertEquals(1, events.size());
        assertInstanceOf(ToolResultEndEvent.class, events.get(0));
    }
}
