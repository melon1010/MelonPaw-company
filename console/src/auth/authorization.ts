import type { AuthUser } from "../api/modules/auth";
import type { MenuItem } from "../plugins/registry/types";

export const SYSTEM_ADMIN_ROLES = new Set(["SYSTEM_ADMIN"]);

export type ConfigurablePermissionSection = "projects" | "administration";

export interface ConfigurableMenuPermission {
  routeId: string;
  permission: string;
  section: ConfigurablePermissionSection;
}

/** The only permissions exposed by the custom-role editor. */
export const CONFIGURABLE_MENU_PERMISSIONS: ConfigurableMenuPermission[] = [
  {
    routeId: "company.projects",
    permission: "projects:manage",
    section: "projects",
  },
  {
    routeId: "company.members",
    permission: "users:manage",
    section: "administration",
  },
  {
    routeId: "company.roles",
    permission: "roles:manage",
    section: "administration",
  },
  {
    routeId: "core.models",
    permission: "models:manage",
    section: "administration",
  },
  {
    routeId: "core.skill-pool",
    permission: "skill-pool:manage",
    section: "administration",
  },
  {
    routeId: "core.environments",
    permission: "environment:manage",
    section: "administration",
  },
  {
    routeId: "core.security",
    permission: "security:manage",
    section: "administration",
  },
  {
    routeId: "core.token-usage",
    permission: "token-usage:read",
    section: "administration",
  },
  {
    routeId: "core.management-audit",
    permission: "audit:read",
    section: "administration",
  },
  {
    routeId: "core.backups",
    permission: "backups:manage",
    section: "administration",
  },
  {
    routeId: "core.quotas",
    permission: "quotas:manage",
    section: "administration",
  },
  {
    routeId: "core.voice-transcription",
    permission: "transcription:manage",
    section: "administration",
  },
  {
    routeId: "core.debug",
    permission: "debug:manage",
    section: "administration",
  },
  {
    routeId: "core.plugin-manager",
    permission: "plugins:manage",
    section: "administration",
  },
];

const MANAGEMENT_ROUTE_PERMISSIONS = new Map(
  CONFIGURABLE_MENU_PERMISSIONS.filter(
    ({ section }) => section === "administration",
  ).map(({ routeId, permission }) => [routeId, permission]),
);

const DEFAULT_ROUTES = new Set([
  "core.root",
  "company.workbench",
  "company.projects",
  "company.project-workspace",
  "company.tasks",
  "company.task-result",
  "core.chat",
  "core.sessions",
  "core.workspace",
  "core.skills",
  "core.tools",
  "core.channels",
  "core.cron-jobs",
  "core.heartbeat",
  "core.inbox",
  "core.mcp",
  "core.agent-config",
  "core.agent-stats",
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
    (user.permissions.includes("system:manage") &&
      CONFIGURABLE_MENU_PERMISSIONS.some(
        (item) => item.permission === permission,
      )) ||
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
  if (isSystemAdmin(user) || DEFAULT_ROUTES.has(routeId)) return true;
  const permission = MANAGEMENT_ROUTE_PERMISSIONS.get(routeId);
  if (permission) return hasPermission(user, permission);
  if (routeId.startsWith("plugin.")) return false;
  return false;
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
  return user?.roles?.[0] || "USER";
}
