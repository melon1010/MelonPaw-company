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

vi.mock("../AccountControls", () => ({
  AccountControl: ({
    mobile,
    onOpenSettings,
  }: {
    mobile?: boolean;
    onOpenSettings?: () => void;
  }) => (
    <button
      type="button"
      data-mobile={mobile ? "true" : "false"}
      onClick={onOpenSettings}
    >
      account-control
    </button>
  ),
}));

vi.mock("../SidebarSessionList", () => ({
  default: ({ historyOnly }: { historyOnly?: boolean }) => (
    <div data-history-only={historyOnly ? "true" : "false"}>
      sidebar-session-list
    </div>
  ),
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
      initialEntries: ["/chat"],
    });

    const trigger = screen.getByLabelText("nav.menu", { selector: "button" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(trigger);

    const navigation = await screen.findByRole("navigation", {
      name: "nav.menu",
      hidden: true,
    });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const chatNavigationItem = within(navigation).getByRole("button", {
      name: "nav.chat",
    });
    expect(chatNavigationItem).toBeInTheDocument();
    expect(
      chatNavigationItem.querySelector(".lucide-square-pen"),
    ).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", {
        name: "enterprise.nav.projects",
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", {
        name: "nav.cronJobs",
      }),
    ).toBeInTheDocument();

    expect(
      within(navigation).queryByRole("button", {
        name: "nav.workspace",
      }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("sidebar-session-list")).toHaveAttribute(
      "data-history-only",
      "true",
    );

    const accountControl = screen.getByRole("button", {
      name: "account-control",
    });
    expect(accountControl).toHaveAttribute("data-mobile", "true");
    fireEvent.click(accountControl);

    expect(
      within(navigation).getByRole("button", {
        name: "nav.workspace",
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", {
        name: "sidebar.settings.returnApp",
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).queryByRole("button", { name: "nav.models" }),
    ).not.toBeInTheDocument();
  });

  it("closes from the mask without rendering a drawer close button", async () => {
    renderWithProviders(<MobileNavigation />, {
      initialEntries: ["/chat"],
    });

    const trigger = screen.getByLabelText("nav.menu", { selector: "button" });
    fireEvent.click(trigger);

    await screen.findByRole("navigation", {
      name: "nav.menu",
      hidden: true,
    });
    expect(document.querySelector(".ant-drawer-close")).not.toBeInTheDocument();

    const mask = document.querySelector(".ant-drawer-mask");
    expect(mask).not.toBeNull();
    fireEvent.click(mask!);

    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("shows administration entries only when their route permission allows it", async () => {
    mockAuthState.roles = ["AUDITOR"];
    mockAuthState.permissions = ["audit:read"];

    renderWithProviders(<MobileNavigation />, {
      initialEntries: ["/chat"],
    });

    fireEvent.click(screen.getByLabelText("nav.menu", { selector: "button" }));
    const navigation = await screen.findByRole("navigation", {
      name: "nav.menu",
      hidden: true,
    });
    fireEvent.click(
      screen.getByRole("button", {
        name: "account-control",
      }),
    );
    expect(
      within(navigation).getByRole("button", {
        name: "nav.tokenUsage",
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).queryByRole("button", { name: "nav.models" }),
    ).not.toBeInTheDocument();
    expect(
      within(navigation).queryByRole("button", { name: "nav.security" }),
    ).not.toBeInTheDocument();
  });

  it("opens settings navigation directly on a settings route", async () => {
    renderWithProviders(<MobileNavigation />, {
      initialEntries: ["/workspace"],
    });

    fireEvent.click(screen.getByLabelText("nav.menu", { selector: "button" }));
    const navigation = await screen.findByRole("navigation", {
      name: "nav.menu",
      hidden: true,
    });

    expect(
      within(navigation).getByRole("button", {
        name: "sidebar.settings.returnApp",
      }),
    ).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", { name: "nav.workspace" }),
    ).toBeInTheDocument();
    expect(
      within(navigation).queryByRole("button", { name: "nav.chat" }),
    ).not.toBeInTheDocument();
  });
});
