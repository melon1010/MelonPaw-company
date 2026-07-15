import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import CompanyWorkbench from "./index";

const {
  mockAuthState,
  mockListProjects,
  mockListChats,
  mockListAgents,
  mockListChannels,
  mockT,
} = vi.hoisted(() => ({
  mockAuthState: {
    roles: ["ORG_ADMIN"] as string[],
    permissions: ["projects:read"] as string[],
  },
  mockListProjects: vi.fn(),
  mockListChats: vi.fn(),
  mockListAgents: vi.fn(),
  mockListChannels: vi.fn(),
  mockT: vi.fn((key: string) => key),
}));

vi.mock("@/api", () => ({
  default: {
    listChats: mockListChats,
    listAgents: mockListAgents,
    listChannels: mockListChannels,
  },
  companyApi: {
    listProjects: mockListProjects,
  },
}));

vi.mock("@/contexts/AuthContext", () => ({
  useAuth: () => ({
    user: {
      id: "user-1",
      username: "user",
      enabled: true,
      roles: mockAuthState.roles,
      permissions: mockAuthState.permissions,
    },
  }),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: mockT }),
}));

describe("CompanyWorkbench permissions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthState.roles = ["ORG_ADMIN"];
    mockAuthState.permissions = ["projects:read"];
    mockListProjects.mockResolvedValue([]);
    mockListChats.mockResolvedValue([]);
    mockListAgents.mockResolvedValue({ agents: [] });
    mockListChannels.mockResolvedValue({});
  });

  it("does not render the start task action without chat access", async () => {
    renderWithProviders(<CompanyWorkbench />, {
      initialEntries: ["/workbench"],
    });

    await screen.findByRole("button", {
      name: "enterprise.projects.title",
    });
    expect(
      screen.queryByRole("button", {
        name: "enterprise.workbench.startTask",
      }),
    ).not.toBeInTheDocument();
    expect(mockListChats).not.toHaveBeenCalled();
  });

  it("renders the start task action when chat access is granted", async () => {
    mockAuthState.permissions = ["projects:read", "agents:use"];

    renderWithProviders(<CompanyWorkbench />, {
      initialEntries: ["/workbench"],
    });

    expect(
      await screen.findByRole("button", {
        name: "enterprise.workbench.startTask",
      }),
    ).toBeInTheDocument();
    expect(mockListChats).toHaveBeenCalledOnce();
  });
});
