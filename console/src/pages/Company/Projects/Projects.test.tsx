import { describe, expect, it } from "vitest";
import type { AuthUser } from "@/api/modules/auth";
import { canManageProject } from "./index";

function user(permissions: string[]): AuthUser {
  return {
    id: "user-1",
    username: "user",
    enabled: true,
    roles: ["ORG_ADMIN"],
    permissions,
  };
}

describe("project management permissions", () => {
  it("uses projects:manage even when no project role is present", () => {
    expect(
      canManageProject(user(["projects:manage"]), { member_role: null }),
    ).toBe(true);
  });

  it("keeps project-level owner and manager permissions", () => {
    const employee = user(["projects:read"]);
    expect(canManageProject(employee, { member_role: "OWNER" })).toBe(true);
    expect(canManageProject(employee, { member_role: "MANAGER" })).toBe(true);
    expect(canManageProject(employee, { member_role: "MEMBER" })).toBe(false);
  });
});
