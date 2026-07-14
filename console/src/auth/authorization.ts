import type { AuthUser } from "../api/modules/auth";
import type { MenuItem } from "../plugins/registry/types";

export const SYSTEM_ADMIN_ROLES = new Set([
  "SYSTEM_ADMIN",
  "ADMIN",
  "SUPER_ADMIN",
]);

export const OPERATOR_ROLES = new Set([
  ...SYSTEM_ADMIN_ROLES,
  "SERVICE_OPERATOR",
  "AI_OPERATOR",
  "AI_SERVICE_OPERATOR",
  "AGENT_OPERATOR",
]);

export const PROJECT_MANAGER_ROLES = new Set([
  ...OPERATOR_ROLES,
  "PROJECT_ADMIN",
  "PROJECT_MANAGER",
]);

const AUDIT_ROLES = new Set([...SYSTEM_ADMIN_ROLES, "AUDITOR"]);

const OPERATOR_ROUTES = new Set([
  "core.cron-jobs",
  "core.heartbeat",
  "core.tools",
  "core.mcp",
  "core.agent-config",
  "core.agent-stats",
]);

const ADMIN_ROUTES = new Set([
  "core.models",
  "core.environments",
  "core.security",
  "core.backups",
  "core.voice-transcription",
  "core.debug",
  "core.plugin-manager",
]);

function normalize(value: string): string {
  return value
    .trim()
    .replace(/[.\-:]/g, "_")
    .toUpperCase();
}

export function hasPermission(
  user: AuthUser | null,
  permission: string,
): boolean {
  if (!user) return false;
  return (
    user.permissions.includes("*") ||
    user.permissions.some(
      (value) => value.toLowerCase() === permission.toLowerCase(),
    ) ||
    isSystemAdmin(user)
  );
}

export function hasAnyRole(user: AuthUser | null, roles: Set<string>): boolean {
  if (!user) return false;
  return user.roles.some((role) => roles.has(normalize(role)));
}

export function isSystemAdmin(user: AuthUser | null): boolean {
  return hasAnyRole(user, SYSTEM_ADMIN_ROLES);
}

export function canManageProjects(user: AuthUser | null): boolean {
  return hasPermission(user, "projects:manage");
}

export function canAccessRoute(
  routeId: string,
  user: AuthUser | null,
): boolean {
  if (!user) return false;
  if (isSystemAdmin(user)) return true;
  if (routeId === "company.members") {
    return (
      hasPermission(user, "users:read") || hasPermission(user, "users:manage")
    );
  }
  if (
    routeId === "company.projects" ||
    routeId === "company.project-workspace"
  ) {
    return hasPermission(user, "projects:read");
  }
  if (
    routeId === "company.tasks" ||
    routeId === "company.task-result" ||
    routeId === "core.chat" ||
    routeId === "core.sessions" ||
    routeId === "core.channels" ||
    routeId === "core.workspace" ||
    routeId === "core.skills"
  ) {
    return hasPermission(user, "agents:use");
  }
  if (routeId === "core.inbox") {
    return (
      hasPermission(user, "agents:use") ||
      hasPermission(user, "approvals:decide")
    );
  }
  if (routeId === "core.agents" || routeId === "core.skill-pool") {
    return hasPermission(user, "agents:manage");
  }
  if (ADMIN_ROUTES.has(routeId)) return hasPermission(user, "system:manage");
  if (OPERATOR_ROUTES.has(routeId)) {
    return (
      hasPermission(user, "agents:manage") || hasAnyRole(user, OPERATOR_ROLES)
    );
  }
  if (routeId === "core.token-usage") {
    return (
      hasPermission(user, "audit:read") ||
      hasPermission(user, "agents:manage") ||
      hasAnyRole(user, new Set([...OPERATOR_ROLES, ...AUDIT_ROLES]))
    );
  }
  if (routeId.startsWith("plugin.")) return false;
  return true;
}

type ItemWithChildren = MenuItem & { __children?: MenuItem[] };

export function filterMenuForUser(
  items: MenuItem[],
  user: AuthUser | null,
): MenuItem[] {
  const result: MenuItem[] = [];
  for (const raw of items) {
    const item = raw as ItemWithChildren;
    const children = item.__children
      ? filterMenuForUser(item.__children, user)
      : undefined;
    if (item.route && !canAccessRoute(item.route, user)) continue;
    if (
      (item.isGroup || item.__children) &&
      (!children || children.length === 0)
    ) {
      continue;
    }
    result.push(children ? { ...item, __children: children } : item);
  }
  return result;
}

export function primaryRole(user: AuthUser | null): string {
  return user?.roles?.[0] || "EMPLOYEE";
}
