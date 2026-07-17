import { describe, expect, it } from "vitest";
import type { AuthUser } from "../api/modules/auth";
import {
  canAccessRoute,
  canManageProjects,
  filterMenuForUser,
} from "./authorization";

function user(roles: string[], permissions: string[]): AuthUser {
  return {
    id: "user-1",
    username: "user",
    enabled: true,
    roles,
    permissions,
  };
}

describe("enterprise route authorization", () => {
  it("allows a system administrator to access all built-in management pages", () => {
    const administrator = user(["SYSTEM_ADMIN"], []);
    expect(canAccessRoute("company.members", administrator)).toBe(true);
    expect(canAccessRoute("core.security", administrator)).toBe(true);
    expect(canManageProjects(administrator)).toBe(true);
  });

  it("gives an employee isolated personal resources without operator controls", () => {
    const employee = user(
      ["EMPLOYEE"],
      [
        "projects:read",
        "agents:use",
        "workspace:personal",
        "skills:personal",
        "channels:personal",
        "automations:personal",
        "mcp:personal",
      ],
    );
    expect(canAccessRoute("core.root", employee)).toBe(true);
    expect(canAccessRoute("company.workbench", employee)).toBe(true);
    expect(canAccessRoute("company.projects", employee)).toBe(true);
    expect(canAccessRoute("company.project-workspace", employee)).toBe(true);
    expect(canAccessRoute("company.tasks", employee)).toBe(true);
    expect(canAccessRoute("company.members", employee)).toBe(false);
    expect(canAccessRoute("core.channels", employee)).toBe(true);
    expect(canAccessRoute("core.workspace", employee)).toBe(true);
    expect(canAccessRoute("core.skills", employee)).toBe(true);
    expect(canAccessRoute("core.cron-jobs", employee)).toBe(true);
    expect(canAccessRoute("core.heartbeat", employee)).toBe(true);
    expect(canAccessRoute("core.tools", employee)).toBe(false);
    expect(
      filterMenuForUser(
        [
          {
            id: "core.tools",
            location: "primary.agentScoped",
            label: () => "Tools",
            route: "core.tools",
          },
        ],
        employee,
      ),
    ).toEqual([]);
    expect(canAccessRoute("core.mcp", employee)).toBe(true);
    expect(canAccessRoute("core.agent-config", employee)).toBe(true);
    expect(canAccessRoute("core.agent-stats", employee)).toBe(true);
    expect(canAccessRoute("core.agents", employee)).toBe(false);
  });

  it("supports non-conversational personal automation roles", () => {
    const automationUser = user(["AUTOMATION_USER"], [
      "automations:personal",
    ]);
    expect(canAccessRoute("core.cron-jobs", automationUser)).toBe(true);
    expect(canAccessRoute("core.heartbeat", automationUser)).toBe(true);
    expect(canAccessRoute("core.chat", automationUser)).toBe(false);
    expect(canAccessRoute("core.workspace", automationUser)).toBe(false);
  });

  it("uses backend permission codes for service operators", () => {
    const operator = user(
      ["SERVICE_OPERATOR"],
      ["projects:read", "agents:use", "agents:manage"],
    );
    expect(canAccessRoute("core.channels", operator)).toBe(true);
    expect(canAccessRoute("core.models", operator)).toBe(false);
    expect(canAccessRoute("core.tools", operator)).toBe(false);
  });

  it("reserves the system tools page for system management permission", () => {
    const manager = user(["SERVICE_OPERATOR"], ["system:manage"]);
    expect(canAccessRoute("core.tools", manager)).toBe(true);
  });
});
