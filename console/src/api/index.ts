export * from "./types";

export { request, ApiRequestError } from "./request";

export { getApiUrl, getApiToken } from "./config";

import { rootApi } from "./modules/root";
import { channelApi } from "./modules/channel";
import { heartbeatApi } from "./modules/heartbeat";
import { cronJobApi } from "./modules/cronjob";
import { chatApi, sessionApi } from "./modules/chat";
import { envApi } from "./modules/env";
import { providerApi } from "./modules/provider";
import { marketApi } from "./modules/market";
import { skillApi } from "./modules/skill";
import { agentApi } from "./modules/agent";
import { agentsApi } from "./modules/agents";
import { workspaceApi } from "./modules/workspace";
import { localModelApi } from "./modules/localModel";
import { mcpApi } from "./modules/mcp";
import { tokenUsageApi } from "./modules/tokenUsage";
import { agentStatsApi } from "./modules/agentStats";
import { toolsApi } from "./modules/tools";
import { securityApi } from "./modules/security";
import { userTimezoneApi } from "./modules/userTimezone";
import { languageApi } from "./modules/language";
import { backupApi } from "./modules/backup";
import { consoleApi } from "./modules/console";
import { accessControlApi } from "./modules/accessControl";
import { companyApi } from "./modules/company";
import { taskApi } from "./modules/tasks";
import { quotaApi } from "./modules/quotas";
import { auditApi } from "./modules/audit";

export const api = {
  // Root
  ...rootApi,

  // Channels
  ...channelApi,

  // Heartbeat
  ...heartbeatApi,

  // Cron Jobs
  ...cronJobApi,

  // Chats
  ...chatApi,

  // Sessions（Legacy aliases）
  ...sessionApi,

  // Environment Variables
  ...envApi,

  // Providers
  ...providerApi,

  // Agent
  ...agentApi,

  // Multi-agent profiles
  ...agentsApi,

  // Skills
  ...skillApi,

  // Skill Market
  ...marketApi,

  // Workspace
  ...workspaceApi,

  // Local Models
  ...localModelApi,

  // MCP Clients
  ...mcpApi,

  // Token Usage
  ...tokenUsageApi,
  // Agent Statistics
  ...agentStatsApi,
  // Tools
  ...toolsApi,

  // Security
  ...securityApi,

  // User Timezone
  ...userTimezoneApi,

  // Language
  ...languageApi,

  // Backups
  ...backupApi,

  // Console
  ...consoleApi,

  // Access Control
  ...accessControlApi,

  // Enterprise project spaces and membership
  ...companyApi,
  ...taskApi,
  ...quotaApi,
  ...auditApi,
};

export default api;

// Export individual APIs for direct access
export { agentsApi };
export { companyApi };
export { taskApi };
export { quotaApi };
export { auditApi };
export type {
  AuditChainVerification,
  ManagementAuditEvent,
} from "./modules/audit";
export type {
  QuotaCatalogue,
  QuotaPolicy,
  QuotaPolicyInput,
  QuotaScope,
} from "./modules/quotas";
export type {
  EnterpriseTask,
  EnterpriseTaskEvent,
  EnterpriseTaskResult,
  EnterpriseTaskRun,
  EnterpriseTaskStatus,
  CreateEnterpriseTaskInput,
} from "./modules/tasks";
export type {
  EnterpriseRole,
  EnterpriseRoleInput,
  EnterpriseUserInput,
  ProjectSpace,
  ProjectAgent,
  ProjectAgentInput,
  ProjectAgentVersion,
  ProjectSpaceInput,
  ProjectSpaceMember,
  ProjectWorkspace,
  ProjectWorkspaceDocument,
  ProjectWorkspaceDocumentName,
  RoleCatalogue,
} from "./modules/company";
