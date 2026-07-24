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

  it("switches workspaces from the nested account menu", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentScopeSwitcher variant="menu" />);

    await user.click(screen.getByText("chat.switchScope"));
    await user.click(await screen.findByText("Personal workspace"));

    expect(useAgentStore.getState().selectedAgent).toBe("personal-1");
  });

  it("renders direct workspace choices for the mobile account sheet", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    renderWithProviders(
      <AgentScopeSwitcher variant="panel" onSelect={onSelect} />,
    );

    await user.click(
      screen.getByRole("button", { name: "Personal workspace" }),
    );

    expect(useAgentStore.getState().selectedAgent).toBe("personal-1");
    expect(onSelect).toHaveBeenCalledOnce();
  });

  it("never exposes the internal default template", async () => {
    const user = userEvent.setup();
    mockListAgents.mockResolvedValue({
      agents: [
        ...agents,
        {
          id: "default",
          name: "Default",
          description: "",
          workspace_dir: "/default",
          enabled: true,
        },
      ],
    });
    useAgentStore.setState({
      selectedAgent: "default",
      agents: [
        {
          id: "default",
          name: "Default",
          description: "",
          workspace_dir: "/default",
          enabled: true,
        },
      ],
    });

    renderWithProviders(<AgentScopeSwitcher variant="sidebar" />);
    await user.click(
      await screen.findByRole("button", { name: /chat\.switchScope/ }),
    );

    expect(screen.queryByText("Default")).not.toBeInTheDocument();
    expect(useAgentStore.getState().selectedAgent).toBe("personal-1");
  });
});
