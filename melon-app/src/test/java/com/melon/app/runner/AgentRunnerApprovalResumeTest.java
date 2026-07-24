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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunnerApprovalResumeTest {

    @Test
    void recoversPersistedApprovalBeforeAcceptingTheNextUserMessage() {
        MultiAgentManager agents = mock(MultiAgentManager.class);
        ApprovalService approvals = mock(ApprovalService.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        ToolUseBlock toolCall = askingShellCall("call-stopped");
        AgentState persistedState = AgentState.builder()
                .userId("user-1")
                .sessionId("session-1")
                .context(List.of(Msg.builderForRole(MsgRole.ASSISTANT).content(toolCall).build()))
                .build();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        stateStore.save("user-1", "session-1", "agent_state", persistedState);

        when(agent.getDelegate()).thenReturn(delegate);
        when(agent.getStateStore()).thenReturn(stateStore);
        when(agent.getDefaultSessionId()).thenReturn("default");
        when(agents.getOrCreate("personal-test")).thenReturn(agent);
        when(delegate.getAgentState(any(RuntimeContext.class)))
                .thenReturn(AgentState.builder().context(List.of()).build());

        AtomicInteger streamCalls = new AtomicInteger();
        AtomicReference<List<Msg>> userMessages = new AtomicReference<>();
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            streamCalls.incrementAndGet();
            userMessages.set(invocation.getArgument(0));
            return Flux.empty();
        });

        Msg userMessage = Msg.builderForRole(MsgRole.USER)
                .content(TextBlock.builder().text("next question").build())
                .build();
        AgentRunner runner = new AgentRunner(agents, approvals);
        runner.stream("personal-test", List.of(userMessage), "user-1", "session-1", Map.of())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(1, streamCalls.get(), "the stopped turn must not be resumed");
        assertEquals(List.of(userMessage), userMessages.get());
        AgentState recovered = stateStore.get(
                "user-1", "session-1", "agent_state", AgentState.class).orElseThrow();
        assertEquals(1, recovered.getContext().stream()
                .flatMap(message -> message.getContentBlocks(
                        io.agentscope.core.message.ToolResultBlock.class).stream())
                .filter(result -> result.getState()
                        == io.agentscope.core.message.ToolResultState.DENIED)
                .count());
    }

    @Test
    void stoppingAChatPersistsDeniedResultsForPendingConfirmations() {
        MultiAgentManager agents = mock(MultiAgentManager.class);
        ApprovalService approvals = mock(ApprovalService.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        ToolUseBlock toolCall = askingShellCall("call-stopped");
        AgentState state = AgentState.builder()
                .userId("user-1")
                .sessionId("session-1")
                .context(List.of(Msg.builderForRole(MsgRole.ASSISTANT).content(toolCall).build()))
                .build();
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        stateStore.save("user-1", "session-1", "agent_state", state);

        when(agents.getAgent("personal-test")).thenReturn(agent);
        when(agent.getDelegate()).thenReturn(delegate);
        when(agent.getStateStore()).thenReturn(stateStore);
        when(agent.getDefaultSessionId()).thenReturn("default");
        when(agent.getName()).thenReturn("test-agent");

        AgentRunner runner = new AgentRunner(agents, approvals);
        assertEquals(1, runner.abandonPendingConfirmations(
                "personal-test", "user-1", "session-1"));
        assertEquals(0, runner.abandonPendingConfirmations(
                "personal-test", "user-1", "session-1"));

        AgentState saved = stateStore.get("user-1", "session-1", "agent_state", AgentState.class)
                .orElseThrow();
        assertEquals(1, saved.getContext().stream()
                .flatMap(message -> message.getContentBlocks(
                        io.agentscope.core.message.ToolResultBlock.class).stream())
                .filter(result -> result.getState()
                        == io.agentscope.core.message.ToolResultState.DENIED)
                .count());
    }

    @Test
    void retriesLiveApprovalOnceWhenAgentScopeMissesImmediateConfirmation() {
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
            int call = streamCalls.getAndIncrement();
            if (call == 0) {
                return Flux.just(
                        new RequireUserConfirmEvent("reply-1", List.of(toolCall)),
                        new AgentEndEvent("reply-1"));
            }
            resumedMessages.set(invocation.getArgument(0));
            if (call == 1) {
                return Flux.error(new IllegalStateException(
                        "Agent is paused: ASKING state; this call supplied no confirmation"));
            }
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
        assertTrue(streamCalls.get() == 3, "missing confirmation recovery must retry exactly once");
    }

    private ToolUseBlock askingShellCall(String id) {
        return ToolUseBlock.builder()
                .id(id)
                .name("execute_shell_command")
                .input(Map.of("command", "rm generated.tmp"))
                .state(ToolCallState.ASKING)
                .build();
    }
}
