import { fireEvent, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import MobileNavigation from "./index";

const { mockAuthState, mockClearSession, mockT } = vi.hoisted(() => ({
  mockAuthState: {
    roles: ["EMPLOYEE"] as string[],
    permissions: [
      "projects:read",
      "agents:use",
      "workspace:personal",
      "skills:personal",
      "channels:personal",
      "automations:personal",
    ] as string[],
  },
  mockClearSession: vi.fn(),
  mockT: vi.fn((key: string) => key),
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
    clearSession: mockClearSession,
  }),
}));

vi.mock("@/api/modules/auth", () => ({
  authApi: { logout: vi.fn().mockResolvedValue(undefined) },
}));

vi.mock("@/api/config", () => ({
  clearAuthToken: vi.fn(),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: mockT }),
}));

describe("MobileNavigation permissions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthState.roles = ["EMPLOYEE"];
    mockAuthState.permissions = [
      "projects:read",
      "agents:use",
      "workspace:personal",
      "skills:personal",
      "channels:personal",
      "automations:personal",
    ];
  });

  it("keeps chat, project spaces, and scheduled tasks in primary navigation", async () => {
    renderWithProviders(<MobileNavigation />, {
      initialEntries: ["/workbench"],
    });

    const navigation = screen.getByRole("navigation", { hidden: true });
    expect(
      within(navigation).getByRole("button", {
        name: "nav.chat",
        hidden: true,
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", {
        name: "enterprise.nav.projects",
        hidden: true,
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", {
        name: "nav.cronJobs",
        hidden: true,
      }),
    ).toBeInTheDocument();

    fireEvent.click(
      within(navigation).getByRole("button", {
        name: "common.more",
        hidden: true,
      }),
    );
    expect(
      await screen.findByRole("button", {
        name: "nav.workspace",
        hidden: true,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "nav.models", hidden: true }),
    ).not.toBeInTheDocument();
  });

  it("shows administration entries only when their route permission allows it", async () => {
    mockAuthState.roles = ["AUDITOR"];
    mockAuthState.permissions = ["audit:read"];

    renderWithProviders(<MobileNavigation />, {
      initialEntries: ["/workbench"],
    });

    fireEvent.click(
      screen.getByRole("button", { name: "common.more", hidden: true }),
    );
    expect(
      await screen.findByRole("button", {
        name: "nav.tokenUsage",
        hidden: true,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "nav.models", hidden: true }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "nav.security", hidden: true }),
    ).not.toBeInTheDocument();
  });
});
