import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../request", () => ({ request: vi.fn() }));

import { request } from "../request";
import { companyApi } from "./company";

describe("companyApi", () => {
  beforeEach(() => {
    vi.mocked(request).mockReset();
    vi.mocked(request).mockResolvedValue(undefined);
  });

  it("loads the persistent member and role catalogues", async () => {
    await companyApi.listUsers();
    await companyApi.listRoles();

    expect(request).toHaveBeenNthCalledWith(1, "/admin/users");
    expect(request).toHaveBeenNthCalledWith(2, "/roles");
  });

  it("creates an enterprise member with roles", async () => {
    const input = {
      username: "employee",
      display_name: "Employee",
      password: "EmployeePassword123",
      enabled: true,
      roles: ["USER"],
      default_skills: ["web-search", "documents"],
    };

    await companyApi.createUser(input);

    expect(request).toHaveBeenCalledWith("/admin/users", {
      method: "POST",
      body: JSON.stringify(input),
    });
  });

  it("deletes an enterprise member by encoded id", async () => {
    await companyApi.deleteUser("user/id");

    expect(request).toHaveBeenCalledWith("/admin/users/user%2Fid", {
      method: "DELETE",
    });
  });

  it("updates account state and replaces roles", async () => {
    await companyApi.updateUser("user/id", { enabled: false });
    await companyApi.replaceUserRoles("user/id", ["AUDITOR"]);

    expect(request).toHaveBeenNthCalledWith(1, "/admin/users/user%2Fid", {
      method: "PUT",
      body: JSON.stringify({ enabled: false }),
    });
    expect(request).toHaveBeenNthCalledWith(2, "/admin/users/user%2Fid/roles", {
      method: "PUT",
      body: JSON.stringify({ roles: ["AUDITOR"] }),
    });
  });

  it("loads and saves project workspace documents", async () => {
    await companyApi.getProjectWorkspace("project/id");
    await companyApi.saveProjectWorkspaceDocument(
      "project/id",
      "PROJECT.md",
      "# Project\n",
    );

    expect(request).toHaveBeenNthCalledWith(
      1,
      "/project-spaces/project%2Fid/workspace",
    );
    expect(request).toHaveBeenNthCalledWith(
      2,
      "/project-spaces/project%2Fid/workspace/PROJECT.md",
      {
        method: "PUT",
        body: JSON.stringify({ content: "# Project\n" }),
      },
    );
  });
});
