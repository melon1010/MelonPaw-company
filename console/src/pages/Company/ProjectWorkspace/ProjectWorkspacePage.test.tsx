import { fireEvent, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import ProjectWorkspacePage from "./index";

const mocks = vi.hoisted(() => ({
  getProject: vi.fn(),
  getProjectWorkspace: vi.fn(),
  saveProjectWorkspaceDocument: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock("../../../api", () => ({
  companyApi: {
    getProject: mocks.getProject,
    getProjectWorkspace: mocks.getProjectWorkspace,
    saveProjectWorkspaceDocument: mocks.saveProjectWorkspaceDocument,
  },
}));

vi.mock("../../../hooks/useAppMessage", () => ({
  useAppMessage: () => ({
    message: { success: mocks.success, error: mocks.error },
  }),
}));

const documents = [
  {
    name: "PROJECT.md",
    content: "# Project\n",
    updated_at: "2026-07-14T00:00:00Z",
  },
  {
    name: "KNOWLEDGE.md",
    content: "# Knowledge\n",
    updated_at: "2026-07-14T00:00:00Z",
  },
  {
    name: "POLICY.md",
    content: "# Policy\n",
    updated_at: "2026-07-14T00:00:00Z",
  },
];

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
    </Routes>,
    { initialEntries: ["/projects/project-1"] },
  );
}

describe("ProjectWorkspacePage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getProject.mockResolvedValue({
      id: "project-1",
      code: "project-1",
      name: "Project One",
      enabled: true,
      member_role: "MEMBER",
    });
    mocks.getProjectWorkspace.mockResolvedValue({
      project_id: "project-1",
      editable: true,
      documents,
    });
    mocks.saveProjectWorkspaceDocument.mockImplementation(
      async (_projectId: string, name: string, content: string) => ({
        name,
        content,
        updated_at: "2026-07-14T01:00:00Z",
      }),
    );
  });

  it("loads, edits, and saves the active project document", async () => {
    renderPage();

    const editor = await screen.findByLabelText("PROJECT.md");
    fireEvent.change(editor, { target: { value: "# Updated project\n" } });
    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() =>
      expect(mocks.saveProjectWorkspaceDocument).toHaveBeenCalledWith(
        "project-1",
        "PROJECT.md",
        "# Updated project\n",
      ),
    );
  });

  it("renders a server-controlled read-only workspace", async () => {
    mocks.getProjectWorkspace.mockResolvedValue({
      project_id: "project-1",
      editable: false,
      documents,
    });

    renderPage();

    const editor = await screen.findByLabelText("PROJECT.md");
    expect(editor).toHaveAttribute("readonly");
    expect(screen.queryByRole("button", { name: /save/i })).toBeNull();
  });
});
