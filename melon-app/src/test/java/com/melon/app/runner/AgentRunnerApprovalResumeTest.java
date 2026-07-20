package com.melon.app.runner;

import com.melon.app.service.ApprovalService;
import com.melon.core.agent.MultiAgentManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunnerApprovalResumeTest {

    @Test
    void confirmedLiveApprovalIsNotReclassifiedAsAnOrphanedDenial() {
        MultiAgentManager agents = mock(MultiAgentManager.class);
        ApprovalService approvals = mock(ApprovalService.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("call-1")
                .name("execute_shell_command")
                .input(Map.of("command", "rm generated.tmp"))
                .state(ToolCallState.ASKING)
                .build();

        Msg staleApproval = Msg.builderForRole(MsgRole.ASSISTANT).content(toolCall).build();
        AgentState cleanState = AgentState.builder().context(List.of()).build();
        AgentState staleState = AgentState.builder().context(List.of(staleApproval)).build();
        AtomicInteger stateReads = new AtomicInteger();
        when(delegate.getAgentState(any(RuntimeContext.class)))
                .thenAnswer(ignored -> stateReads.getAndIncrement() == 0 ? cleanState : staleState);
        when(agent.getDelegate()).thenReturn(delegate);
        when(agent.getToolkit()).thenReturn(null);
        when(agents.getOrCreate("personal-test")).thenReturn(agent);
        when(approvals.getApprovalMode("personal-test")).thenReturn("RISK_BASED");
        when(approvals.openPendingApproval(eq("personal-test"), eq("session-1"), eq("user-1"),
                anyList(), anyList()))
                .thenReturn(Mono.just(List.of(new ConfirmResult(true, toolCall))));

        AtomicInteger streamCalls = new AtomicInteger();
        AtomicReference<List<Msg>> resumedMessages = new AtomicReference<>();
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            if (streamCalls.getAndIncrement() == 0) {
                return Flux.just(
                        new RequireUserConfirmEvent("reply-1", List.of(toolCall)),
                        new AgentEndEvent("reply-1"));
            }
            resumedMessages.set(invocation.getArgument(0));
            return Flux.empty();
        });

        AgentRunner runner = new AgentRunner(agents, approvals);
        runner.stream("personal-test", List.of(), "user-1", "session-1", Map.of())
                .collectList()
                .block(Duration.ofSeconds(5));

        List<Msg> resumed = resumedMessages.get();
        assertNotNull(resumed);
        @SuppressWarnings("unchecked")
        List<ConfirmResult> decisions = (List<ConfirmResult>) resumed.get(0).getMetadata()
                .get(Msg.METADATA_CONFIRM_RESULTS);
        assertNotNull(decisions);
        assertTrue(decisions.get(0).isConfirmed());
        assertTrue(stateReads.get() == 1, "live approval resume must not run orphan recovery again");
    }
}
