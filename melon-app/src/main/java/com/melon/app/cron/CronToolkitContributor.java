package com.melon.app.cron;

import com.melon.core.agent.ToolkitContributor;
import com.melon.core.config.AgentConfig;
import com.melon.tools.cron.CreateCronJobTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Registers the workspace cron tool with live scheduling supplied by the application layer. */
@Component
public class CronToolkitContributor implements ToolkitContributor {

    private final ObjectProvider<CronSchedulingService> schedulingService;

    public CronToolkitContributor(ObjectProvider<CronSchedulingService> schedulingService) {
        this.schedulingService = schedulingService;
    }

    @Override
    public void contribute(String agentId, AgentConfig agentConfig, Path workspaceDir, Toolkit toolkit) {
        if (!enabled(agentConfig)) return;
        toolkit.registerAgentTool(new CreateCronJobTool(workspaceDir,
                body -> schedulingService.getObject().create(agentId, workspaceDir, body)));
    }

    private boolean enabled(AgentConfig config) {
        if (config == null || config.getTools() == null || config.getTools().getBuiltinTools() == null) {
            return true;
        }
        var tool = config.getTools().getBuiltinTools().get("create_cron_job");
        return tool == null || tool.isEnabled();
    }
}
