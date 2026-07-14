import { describe, expect, it } from "vitest";
import type { AuthUser } from "../api/modules/auth";
import { canAccessRoute, canManageProjects } from "./authorization";

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
    const employee = user(["EMPLOYEE"], ["projects:read", "agents:use"]);
    expect(canAccessRoute("company.projects", employee)).toBe(true);
    expect(canAccessRoute("company.project-workspace", employee)).toBe(true);
    expect(canAccessRoute("company.tasks", employee)).toBe(true);
    expect(canAccessRoute("company.members", employee)).toBe(false);
    expect(canAccessRoute("core.channels", employee)).toBe(true);
    expect(canAccessRoute("core.workspace", employee)).toBe(true);
    expect(canAccessRoute("core.skills", employee)).toBe(true);
    expect(canAccessRoute("core.tools", employee)).toBe(false);
  });

  it("uses backend permission codes for service operators", () => {
    const operator = user(
      ["SERVICE_OPERATOR"],
      ["projects:read", "agents:use", "agents:manage"],
    );
    expect(canAccessRoute("core.channels", operator)).toBe(true);
    expect(canAccessRoute("core.models", operator)).toBe(false);
  });
});
