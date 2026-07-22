import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import { useAgentStore } from "@/stores/agentStore";
import AgentScopeSwitcher from "./index";

const { mockListAgents, mockT } = vi.hoisted(() => ({
  mockListAgents: vi.fn(),
  mockT: vi.fn((key: string) => key),
}));

vi.mock("@/api/modules/agents", () => ({
  agentsApi: { listAgents: mockListAgents },
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: mockT }),
}));

const agents = [
  {
    id: "personal-1",
    name: "Personal workspace",
    description: "",
    workspace_dir: "/personal",
    scope: "PERSONAL" as const,
    enabled: true,
  },
  {
    id: "project-1",
    name: "Project workspace",
    description: "",
    workspace_dir: "/project",
    scope: "PROJECT" as const,
    enabled: true,
  },
];

describe("AgentScopeSwitcher", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListAgents.mockResolvedValue({ agents });
    useAgentStore.setState({ selectedAgent: "project-1", agents });
  });

  it("switches back to a personal workspace from the sidebar", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentScopeSwitcher variant="sidebar" />);

    await user.click(screen.getByRole("button", { name: /chat\.switchScope/ }));
    await user.click(await screen.findByText("Personal workspace"));

    expect(useAgentStore.getState().selectedAgent).toBe("personal-1");
  });
});
