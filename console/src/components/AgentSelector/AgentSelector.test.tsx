import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/common_setup";
import AgentSelector from "./index";

const {
  mockSetSelectedAgent,
  mockSetAgents,
  mockListAgents,
  mockNavigate,
  mockStoreState,
  mockAuthState,
  mockT,
} = vi.hoisted(() => ({
  mockSetSelectedAgent: vi.fn(),
  mockSetAgents: vi.fn(),
  mockListAgents: vi.fn(),
  mockNavigate: vi.fn(),
  mockStoreState: {
    selectedAgent: "default",
    agents: [] as Array<{
      id: string;
      name: string;
      enabled: boolean;
      description: string;
    }>,
  },
  mockAuthState: {
    roles: ["EMPLOYEE"] as string[],
    permissions: ["agents:use"] as string[],
  },
  mockT: vi.fn((key: string) => key),
}));

vi.mock("@/api/modules/agents", () => ({
  agentsApi: { listAgents: mockListAgents },
}));

vi.mock("@/stores/agentStore", () => ({
  useAgentStore: vi.fn(() => ({
    selectedAgent: mockStoreState.selectedAgent,
    agents: mockStoreState.agents,
    setSelectedAgent: mockSetSelectedAgent,
    setAgents: mockSetAgents,
  })),
}));

vi.mock("@/contexts/AuthContext", () => ({
  useAuth: () => ({
    user: {
      id: "user-1",
      username: "employee",
      enabled: true,
      roles: mockAuthState.roles,
      permissions: mockAuthState.permissions,
    },
  }),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: mockT }),
}));

vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return { ...actual, useNavigate: () => mockNavigate };
});

const mockAgentsData = {
  agents: [
    { id: "agent-1", name: "Agent One", enabled: true, description: "desc" },
    { id: "agent-2", name: "Agent Two", enabled: false, description: "" },
  ],
};

describe("AgentSelector", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockStoreState.selectedAgent = "default";
    mockStoreState.agents = [];
    mockAuthState.roles = ["EMPLOYEE"];
    mockAuthState.permissions = ["agents:use"];
    mockListAgents.mockResolvedValue(mockAgentsData);
  });

  afterEach(() => vi.clearAllMocks());

  it("calls listAgents on mount", async () => {
    renderWithProviders(<AgentSelector />);
    await waitFor(() => expect(mockListAgents).toHaveBeenCalledOnce());
  });

  it("after loading, setAgents receives the list with enabled agents first", async () => {
    renderWithProviders(<AgentSelector />);
    await waitFor(() => expect(mockSetAgents).toHaveBeenCalled());
    const sortedAgents = mockSetAgents.mock.calls[0][0];
    expect(sortedAgents[0].enabled).toBe(true);
    expect(sortedAgents[1].enabled).toBe(false);
  });

  it("does not render Select in collapsed mode", async () => {
    renderWithProviders(<AgentSelector collapsed />);
    await waitFor(() => expect(mockListAgents).toHaveBeenCalled());
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("renders Select in non-collapsed mode", async () => {
    renderWithProviders(<AgentSelector />);
    await waitFor(() => expect(mockListAgents).toHaveBeenCalled());
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("does not crash when listAgents fails", async () => {
    mockListAgents.mockRejectedValue(new Error("network error"));
    expect(() => renderWithProviders(<AgentSelector />)).not.toThrow();
    await waitFor(() => expect(mockListAgents).toHaveBeenCalled());
  });

  it("replaces a persisted inaccessible agent with the first enabled authorized agent", async () => {
    mockStoreState.selectedAgent = "agent-from-another-user";
    mockStoreState.agents = mockAgentsData.agents;

    renderWithProviders(<AgentSelector />);

    await waitFor(() =>
      expect(mockSetSelectedAgent).toHaveBeenCalledWith("agent-1"),
    );
  });

  it("only shows the management link to users who can manage agents", async () => {
    mockStoreState.selectedAgent = "agent-1";
    mockStoreState.agents = mockAgentsData.agents;
    const { unmount } = renderWithProviders(<AgentSelector />);
    fireEvent.mouseDown(screen.getByRole("combobox"));
    await waitFor(() =>
      expect(screen.getAllByText("Agent One")).not.toHaveLength(0),
    );
    expect(
      screen.queryByRole("button", { name: "agent.management" }),
    ).not.toBeInTheDocument();
    unmount();

    mockAuthState.permissions = ["agents:manage"];
    renderWithProviders(<AgentSelector />);
    fireEvent.mouseDown(screen.getByRole("combobox"));

    expect(
      await screen.findByRole("button", { name: "agent.management" }),
    ).toBeInTheDocument();
  });
});
