import { request } from "../request";
import type { AuthUser } from "./auth";

export interface ProjectSpace {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  enabled: boolean;
  member_role?: "OWNER" | "MANAGER" | "MEMBER" | "VIEWER" | null;
  created_at?: string | null;
  updated_at?: string | null;
}

export interface ProjectSpaceMember {
  project_id: string;
  user_id: string;
  username: string;
  display_name?: string | null;
  project_role: "OWNER" | "MANAGER" | "MEMBER" | "VIEWER";
  created_at?: string | null;
}

export interface ProjectSpaceInput {
  code: string;
  name: string;
  description?: string;
  enabled?: boolean;
}

export type ProjectWorkspaceDocumentName =
  | "PROJECT.md"
  | "KNOWLEDGE.md"
  | "POLICY.md";

export interface ProjectWorkspaceDocument {
  name: ProjectWorkspaceDocumentName;
  content: string;
  updated_at?: string | null;
}

export interface ProjectWorkspace {
  project_id: string;
  editable: boolean;
  documents: ProjectWorkspaceDocument[];
}

export interface EnterpriseRole {
  code: string;
  name: string;
  description: string;
  system_role: boolean;
  permissions: string[];
}

export interface RoleCatalogue {
  roles: EnterpriseRole[];
  permissions: string[];
}

export interface EnterpriseUserInput {
  username: string;
  display_name?: string;
  password: string;
  enabled: boolean;
  roles: string[];
  default_skills?: string[];
}

export const companyApi = {
  listProjects: () => request<ProjectSpace[]>("/project-spaces"),
  createProject: (body: ProjectSpaceInput) =>
    request<ProjectSpace>("/project-spaces", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  getProject: (projectId: string) =>
    request<ProjectSpace>(`/project-spaces/${encodeURIComponent(projectId)}`),
  updateProject: (projectId: string, body: Partial<ProjectSpaceInput>) =>
    request<ProjectSpace>(`/project-spaces/${encodeURIComponent(projectId)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  listProjectMembers: (projectId: string) =>
    request<ProjectSpaceMember[]>(
      `/project-spaces/${encodeURIComponent(projectId)}/members`,
    ),
  putProjectMember: (
    projectId: string,
    userId: string,
    projectRole: ProjectSpaceMember["project_role"],
  ) =>
    request<ProjectSpaceMember>(
      `/project-spaces/${encodeURIComponent(
        projectId,
      )}/members/${encodeURIComponent(userId)}`,
      {
        method: "PUT",
        body: JSON.stringify({ project_role: projectRole }),
      },
    ),
  removeProjectMember: (projectId: string, userId: string) =>
    request<void>(
      `/project-spaces/${encodeURIComponent(
        projectId,
      )}/members/${encodeURIComponent(userId)}`,
      { method: "DELETE" },
    ),
  getProjectWorkspace: (projectId: string) =>
    request<ProjectWorkspace>(
      `/project-spaces/${encodeURIComponent(projectId)}/workspace`,
    ),
  saveProjectWorkspaceDocument: (
    projectId: string,
    fileName: ProjectWorkspaceDocumentName,
    content: string,
  ) =>
    request<ProjectWorkspaceDocument>(
      `/project-spaces/${encodeURIComponent(
        projectId,
      )}/workspace/${encodeURIComponent(fileName)}`,
      {
        method: "PUT",
        body: JSON.stringify({ content }),
      },
    ),
  listUsers: () => request<AuthUser[]>("/admin/users"),
  createUser: (body: EnterpriseUserInput) =>
    request<AuthUser>("/admin/users", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  updateUser: (
    userId: string,
    body: Partial<
      Pick<EnterpriseUserInput, "display_name" | "password" | "enabled">
    >,
  ) =>
    request<AuthUser>(`/admin/users/${encodeURIComponent(userId)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  replaceUserRoles: (userId: string, roles: string[]) =>
    request<AuthUser>(`/admin/users/${encodeURIComponent(userId)}/roles`, {
      method: "PUT",
      body: JSON.stringify({ roles }),
    }),
  deleteUser: (userId: string) =>
    request<void>(`/admin/users/${encodeURIComponent(userId)}`, {
      method: "DELETE",
    }),
  listRoles: () => request<RoleCatalogue>("/roles"),
};
