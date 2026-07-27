package com.melon.app.mcp;

import com.melon.core.agent.MultiAgentManager;
import com.melon.core.config.AgentConfig;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolkitContributorTest {

    @Test
    void registersCachedToolsInAnInactiveNativeToolGroup() {
        McpClientManager manager = mock(McpClientManager.class);
        McpClientManager.McpServerConfig server = new McpClientManager.McpServerConfig();
        server.setId("zentao");
        server.setName("Zentao");
        server.setEnabled(true);
        McpClientManager.McpTool tool = new McpClientManager.McpTool("search_issues", "Search issues");
        tool.setInputSchema(java.util.Map.of("type", "object"));
        server.setTools(List.of(tool));
        when(manager.listServers()).thenReturn(List.of(server));
        when(manager.listTools("zentao")).thenReturn(List.of(tool));

        McpClientRegistry registry = mock(McpClientRegistry.class);
        when(registry.forAgent("personal-user")).thenReturn(manager);
        @SuppressWarnings("unchecked")
        ObjectProvider<MultiAgentManager> agents = mock(ObjectProvider.class);
        McpToolkitContributor contributor = new McpToolkitContributor(
                registry, mock(McpAccessPolicyService.class), agents);
        Toolkit toolkit = new Toolkit();

        contributor.contribute("personal-user", new AgentConfig(), Path.of("workspace"), toolkit);
        toolkit.registerMetaTool();

        String groupName = "mcp_zentao_" + Integer.toUnsignedString("zentao".hashCode(), 36);
        assertNotNull(toolkit.getToolGroup(groupName));
        assertFalse(toolkit.getToolGroup(groupName).isActive());
        assertTrue(toolkit.getToolSchemas().stream()
                .noneMatch(schema -> "search_issues".equals(schema.getName())));
        assertTrue(toolkit.getToolSchemas().stream()
                .anyMatch(schema -> "reset_equipped_tools".equals(schema.getName())));
        assertTrue(toolkit.getToolSchemas(List.of(groupName)).stream()
                .anyMatch(schema -> "search_issues".equals(schema.getName())));
        assertTrue(((ToolBase) toolkit.getTool("search_issues")).isConcurrencySafe());
        verify(manager, never()).connectAsync("zentao");
    }

    @Test
    void exposesMultipleMcpGroupsForOneAgentWithoutConnectingThem() {
        McpClientManager manager = mock(McpClientManager.class);
        McpClientManager.McpServerConfig issues = server("issues", "search_issues");
        McpClientManager.McpServerConfig knowledge = server("knowledge", "search_docs");
        when(manager.listServers()).thenReturn(List.of(issues, knowledge));
        when(manager.listTools("issues")).thenReturn(issues.getTools());
        when(manager.listTools("knowledge")).thenReturn(knowledge.getTools());

        McpClientRegistry registry = mock(McpClientRegistry.class);
        when(registry.forAgent("personal-user")).thenReturn(manager);
        @SuppressWarnings("unchecked")
        ObjectProvider<MultiAgentManager> agents = mock(ObjectProvider.class);
        Toolkit toolkit = new Toolkit();

        new McpToolkitContributor(registry, mock(McpAccessPolicyService.class), agents)
                .contribute("personal-user", new AgentConfig(), Path.of("workspace"), toolkit);

        String issuesGroup = "mcp_issues_" + Integer.toUnsignedString("issues".hashCode(), 36);
        String knowledgeGroup = "mcp_knowledge_"
                + Integer.toUnsignedString("knowledge".hashCode(), 36);
        List<String> equippedNames = toolkit.getToolSchemas(List.of(issuesGroup, knowledgeGroup))
                .stream().map(schema -> schema.getName()).toList();
        assertTrue(equippedNames.containsAll(List.of("search_issues", "search_docs")));
        verify(manager, never()).connectAsync("issues");
        verify(manager, never()).connectAsync("knowledge");
    }

    private McpClientManager.McpServerConfig server(String id, String toolName) {
        McpClientManager.McpServerConfig server = new McpClientManager.McpServerConfig();
        server.setId(id);
        server.setName(id);
        server.setEnabled(true);
        McpClientManager.McpTool tool = new McpClientManager.McpTool(toolName, toolName);
        tool.setInputSchema(java.util.Map.of("type", "object"));
        server.setTools(List.of(tool));
        return server;
    }
}
