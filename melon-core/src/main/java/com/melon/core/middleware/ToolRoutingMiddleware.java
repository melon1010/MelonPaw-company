package com.melon.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/** Adds stable tool-selection rules even when a workspace has an older AGENTS.md. */
public final class ToolRoutingMiddleware implements MiddlewareBase {

    private static final String MARKER = "<melonpaw-tool-routing>";
    private static final String POLICY = """

            <melonpaw-tool-routing>
            Tool routing rules:
            - Answer directly without tools when the request can be answered from the conversation or stable knowledge.
            - Prefer specialized tools. Use web_search to discover current public sources, web_fetch to read a known public URL, and file tools for workspace files.
            - Use execute_shell_command only for actual system commands such as builds, tests, package managers, Git, or process inspection. Never use it for web browsing, HTTP requests, file reading, or text search when a specialized tool exists.
            - agent_spawn starts background work and returns task_id. Launch independent subagents once, then use task_wait for several task IDs or task_output for one. A wait timeout means the task is still running; never spawn the same task again because waiting timed out.
            - When combining research, distinguish inspected sources from failed sources, cite evidence for material claims, and resolve contradictions or numeric inconsistencies before answering. Never claim cross-verification from sources that failed to load.
            - These routing rules override older skill instructions that suggest curl, wget, or temporary Python scripts for public web access.
            - Do not create helper scripts merely to inspect or summarize information. Do not repeat the same tool call with the same arguments. After enough evidence is available, stop calling tools and answer the user.
            </melonpaw-tool-routing>
            """;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String prompt = currentPrompt != null ? currentPrompt : "";
        return Mono.just(prompt.contains(MARKER) ? prompt : prompt + POLICY);
    }
}
