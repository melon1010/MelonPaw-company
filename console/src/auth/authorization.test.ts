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
  it("allows the built-in administrator to access every management menu", () => {
    const administrator = user(["SYSTEM_ADMIN"], []);
    expect(canAccessRoute("company.members", administrator)).toBe(true);
    expect(canAccessRoute("core.security", administrator)).toBe(true);
    expect(canAccessRoute("core.plugin-manager", administrator)).toBe(true);
    expect(canManageProjects(administrator)).toBe(true);
  });

  it("makes the operations center available to every signed-in user", () => {
    const member = user(["USER"], []);
    for (const route of [
      "company.workbench",
      "company.projects",
      "company.project-workspace",
      "company.tasks",
      "core.chat",
      "core.sessions",
      "core.channels",
      "core.workspace",
      "core.skills",
      "core.cron-jobs",
      "core.heartbeat",
      "core.tools",
      "core.mcp",
      "core.agent-config",
      "core.agent-stats",
    ]) {
      expect(canAccessRoute(route, member)).toBe(true);
    }
    expect(canAccessRoute("company.members", member)).toBe(false);
    expect(canAccessRoute("core.models", member)).toBe(false);
  });

  it("authorizes management menus independently", () => {
    const modelManager = user(["USER", "MODEL_MANAGER"], [
      "models:manage",
    ]);
    expect(canAccessRoute("core.models", modelManager)).toBe(true);
    expect(canAccessRoute("core.environments", modelManager)).toBe(false);
    expect(canAccessRoute("company.roles", modelManager)).toBe(false);
  });

  it("keeps project creation separate from assigned-project access", () => {
    const member = user(["USER"], []);
    const projectManager = user(["USER", "PROJECT_MANAGER"], [
      "projects:manage",
    ]);
    expect(canAccessRoute("company.projects", member)).toBe(true);
    expect(canManageProjects(member)).toBe(false);
    expect(canManageProjects(projectManager)).toBe(true);
  });

  it("filters only unauthorized administration items", () => {
    const member = user(["USER"], []);
    expect(
      filterMenuForUser(
        [
          {
            id: "core.tools",
            location: "primary.agentScoped",
            label: () => "Tools",
            route: "core.tools",
          },
          {
            id: "core.models",
            location: "primary.settings",
            label: () => "Models",
            route: "core.models",
          },
        ],
        member,
      ).map((item) => item.id),
    ).toEqual(["core.tools"]);
  });

  it("keeps legacy system management as an umbrella during configuration rollout", () => {
    const legacyManager = user(["USER"], ["system:manage"]);
    expect(canAccessRoute("core.models", legacyManager)).toBe(true);
    expect(canAccessRoute("core.security", legacyManager)).toBe(true);
  });
});
