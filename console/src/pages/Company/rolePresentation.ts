import type { TFunction } from "i18next";

const SYSTEM_ROLE_KEYS: Record<string, string> = {
  SYSTEM_ADMIN: "systemAdmin",
  USER: "user",
  SERVICE_OPERATOR: "serviceOperator",
  APPROVER: "approver",
  EMPLOYEE: "employee",
  AUDITOR: "auditor",
  ORG_ADMIN: "orgAdmin",
};

const PERMISSION_NAV_KEYS: Record<string, string> = {
  "projects:manage": "enterprise.nav.projects",
  "users:manage": "enterprise.nav.members",
  "roles:manage": "enterprise.nav.roles",
  "models:manage": "nav.models",
  "skill-pool:manage": "nav.skillPool",
  "environment:manage": "nav.environments",
  "security:manage": "nav.security",
  "token-usage:read": "nav.tokenUsage",
  "audit:read": "enterprise.nav.audit",
  "backups:manage": "nav.backups",
  "quotas:manage": "enterprise.nav.quotas",
  "transcription:manage": "nav.voiceTranscription",
  "debug:manage": "nav.debug",
  "plugins:manage": "nav.pluginManager",
};

const PERMISSION_KEYS: Record<string, string> = {
  "users:read": "usersRead",
  "users:manage": "usersManage",
  "roles:read": "rolesRead",
  "roles:manage": "rolesManage",
  "projects:read": "projectsRead",
  "projects:manage": "projectsManage",
  "agents:use": "agentsUse",
  "agents:manage": "agentsManage",
  "approvals:decide": "approvalsDecide",
  "audit:read": "auditRead",
  "system:manage": "systemManage",
  "data:breakglass": "dataBreakglass",
  "workspace:personal": "workspacePersonal",
  "skills:personal": "skillsPersonal",
  "channels:personal": "channelsPersonal",
  "automations:personal": "automationsPersonal",
  "mcp:personal": "mcpPersonal",
};

export type PermissionGroupKey =
  | "members"
  | "projects"
  | "agents"
  | "governance"
  | "system"
  | "personal"
  | "other";

export const PERMISSION_GROUP_ORDER: PermissionGroupKey[] = [
  "members",
  "projects",
  "agents",
  "governance",
  "system",
  "personal",
  "other",
];

export function getPermissionGroup(permission: string): PermissionGroupKey {
  const namespace = permission.split(":", 1)[0];
  if (namespace === "users" || namespace === "roles") return "members";
  if (namespace === "projects") return "projects";
  if (namespace === "agents") return "agents";
  if (namespace === "approvals" || namespace === "audit") return "governance";
  if (namespace === "system" || namespace === "data") return "system";
  if (
    namespace === "workspace" ||
    namespace === "skills" ||
    namespace === "channels" ||
    namespace === "automations" ||
    namespace === "mcp"
  ) {
    return "personal";
  }
  return "other";
}

export function getRoleName(
  code: string,
  fallback: string | undefined,
  t: TFunction,
): string {
  const key = SYSTEM_ROLE_KEYS[code.toUpperCase()];
  if (!key) return fallback || code;
  return t(`enterprise.roles.roleNames.${key}`, {
    defaultValue: fallback || code,
  });
}

export function getRoleDescription(
  code: string,
  fallback: string | undefined,
  t: TFunction,
): string {
  const key = SYSTEM_ROLE_KEYS[code.toUpperCase()];
  if (!key) return fallback || "-";
  return t(`enterprise.roles.roleDescriptions.${key}`, {
    defaultValue: fallback || "-",
  });
}

export function getPermissionName(permission: string, t: TFunction): string {
  const navKey = PERMISSION_NAV_KEYS[permission];
  if (navKey) return t(navKey, { defaultValue: permission });
  const key = PERMISSION_KEYS[permission];
  if (!key) return permission;
  return t(`enterprise.roles.permissionNames.${key}`, {
    defaultValue: permission,
  });
}

export function getPermissionGroupName(
  group: PermissionGroupKey,
  t: TFunction,
): string {
  return t(`enterprise.roles.permissionGroups.${group}`, {
    defaultValue: group,
  });
}
