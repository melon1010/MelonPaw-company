/**
 * builtinRoutes.ts — host's built-in routes as data.
 *
 * Importing self-registers all builtins into routeRegistry. MainLayout's
 * `useRoutes()` snapshot returns them. Reviewed local extensions can register
 * routes into the same registry and are treated uniformly.
 *
 * Route components use `lazyWithRetry`. The `/` redirect is a
 * named route with a tiny DefaultRedirect component so routeRegistry has a
 * single uniform shape.
 *
 * Naming convention mirrors builtinMenu: `core.<key>`.
 */
import { Navigate } from "react-router-dom";
import { lazyWithRetry } from "../../utils/lazyWithRetry";
import { routeRegistry } from "../../plugins/registry/store";
import type { Route } from "../../plugins/registry/types";

// Lazy pages
const Chat = lazyWithRetry(() => import("../../pages/Chat"));
const ChannelsPage = lazyWithRetry(() => import("../../pages/Control/Channels"));
const SessionsPage = lazyWithRetry(() => import("../../pages/Control/Sessions"));
const InboxPage = lazyWithRetry(() => import("../../pages/Inbox"));
const CronJobsPage = lazyWithRetry(() => import("../../pages/Control/CronJobs"));
const HeartbeatPage = lazyWithRetry(() => import("../../pages/Control/Heartbeat"));
const AgentConfigPage = lazyWithRetry(() => import("../../pages/Agent/Config"));
const SkillsPage = lazyWithRetry(() => import("../../pages/Agent/Skills"));
const SkillPoolPage = lazyWithRetry(() => import("../../pages/Settings/SkillPool"));
const ToolsPage = lazyWithRetry(() => import("../../pages/Agent/Tools"));
const WorkspacePage = lazyWithRetry(() => import("../../pages/Agent/Workspace"));
const MCPPage = lazyWithRetry(() => import("../../pages/Agent/MCP"));
const ModelsPage = lazyWithRetry(() => import("../../pages/Settings/Models"));
const EnvironmentsPage = lazyWithRetry(() => import("../../pages/Settings/Environments"));
const SecurityPage = lazyWithRetry(() => import("../../pages/Settings/Security"));
const TokenUsagePage = lazyWithRetry(() => import("../../pages/Settings/TokenUsage"));
const AgentStatsPage = lazyWithRetry(() => import("../../pages/Settings/AgentStats"));
const VoiceTranscriptionPage = lazyWithRetry(() => import("../../pages/Settings/VoiceTranscription"));
const AgentsPage = lazyWithRetry(() => import("../../pages/Settings/Agents"));
const DebugPage = lazyWithRetry(() => import("../../pages/Settings/Debug"));
const BackupsPage = lazyWithRetry(() => import("../../pages/Settings/Backups"));
const PluginManagerPage = lazyWithRetry(() => import("../../pages/Settings/PluginManager"));
const CompanyWorkbenchPage = lazyWithRetry(() => import("../../pages/Company/Workbench"));
const ProjectSpacesPage = lazyWithRetry(() => import("../../pages/Company/Projects"));
const ProjectWorkspacePage = lazyWithRetry(() => import("../../pages/Company/ProjectWorkspace"));
const EnterpriseMembersPage = lazyWithRetry(() => import("../../pages/Company/Members"));
const TaskCenterPage = lazyWithRetry(() => import("../../pages/Company/TaskCenter"));
const TaskResultPage = lazyWithRetry(() => import("../../pages/Company/TaskResult"));

/** The company workbench is the default authenticated landing page. */
function DefaultRedirect() {
  return <Navigate to="/workbench" replace />;
}

export const BUILTIN_ROUTES: Route[] = [
  { id: "core.root", path: "/", component: DefaultRedirect },
  {
    id: "company.workbench",
    path: "/workbench",
    component: CompanyWorkbenchPage,
  },
  {
    id: "company.projects",
    path: "/projects",
    component: ProjectSpacesPage,
  },
  {
    id: "company.project-workspace",
    path: "/projects/:projectId",
    component: ProjectWorkspacePage,
  },
  {
    id: "company.members",
    path: "/members",
    component: EnterpriseMembersPage,
  },
  { id: "company.tasks", path: "/tasks", component: TaskCenterPage },
  {
    id: "company.task-result",
    path: "/tasks/:taskId/result",
    component: TaskResultPage,
  },
  { id: "core.chat", path: "/chat/*", component: Chat },
  { id: "core.channels", path: "/channels", component: ChannelsPage },
  { id: "core.sessions", path: "/sessions", component: SessionsPage },
  { id: "core.inbox", path: "/inbox", component: InboxPage },
  { id: "core.cron-jobs", path: "/cron-jobs", component: CronJobsPage },
  { id: "core.heartbeat", path: "/heartbeat", component: HeartbeatPage },
  { id: "core.skills", path: "/skills", component: SkillsPage },
  { id: "core.skill-pool", path: "/skill-pool", component: SkillPoolPage },
  { id: "core.tools", path: "/tools", component: ToolsPage },
  { id: "core.mcp", path: "/mcp", component: MCPPage },
  { id: "core.workspace", path: "/workspace", component: WorkspacePage },
  { id: "core.agents", path: "/agents", component: AgentsPage },
  { id: "core.models", path: "/models", component: ModelsPage },
  {
    id: "core.environments",
    path: "/environments",
    component: EnvironmentsPage,
  },
  {
    id: "core.agent-config",
    path: "/agent-config",
    component: AgentConfigPage,
  },
  { id: "core.security", path: "/security", component: SecurityPage },
  { id: "core.token-usage", path: "/token-usage", component: TokenUsagePage },
  { id: "core.agent-stats", path: "/agent-stats", component: AgentStatsPage },
  {
    id: "core.voice-transcription",
    path: "/voice-transcription",
    component: VoiceTranscriptionPage,
  },
  { id: "core.debug", path: "/debug", component: DebugPage },
  { id: "core.backups", path: "/backups", component: BackupsPage },
  {
    id: "core.plugin-manager",
    path: "/plugin-manager",
    component: PluginManagerPage,
  },
];

routeRegistry.addBuiltin(BUILTIN_ROUTES);
