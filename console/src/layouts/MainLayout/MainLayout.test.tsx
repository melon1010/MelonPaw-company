import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import MainLayout from ".";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

vi.mock("../Sidebar", () => ({
  default: ({ settingsMode }: { settingsMode: boolean }) => (
    <div
      data-testid="desktop-sidebar"
      data-navigation-mode={settingsMode ? "settings" : "app"}
    />
  ),
}));

vi.mock("../MobileNavigation", () => ({ default: () => null }));
vi.mock("../../components/ConsolePollService", () => ({
  default: () => null,
}));
vi.mock("../../components/ChunkErrorBoundary", () => ({
  ChunkErrorBoundary: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("../../plugins/registry/Slot", () => ({ Slot: () => null }));
vi.mock("../../contexts/AuthContext", () => ({
  useAuth: () => ({ user: null }),
}));
vi.mock("../../auth/authorization", () => ({
  canAccessRoute: () => true,
}));
vi.mock("../../plugins/registry/hooks", () => ({
  useMenuItems: () => [
    {
      id: "company.projects",
      label: "Projects",
      route: "company.projects",
    },
    {
      id: "core.cron-jobs",
      label: "Cron jobs",
      route: "core.cron-jobs",
    },
    { id: "core.skills", label: "Skills", route: "core.skills" },
  ],
  useRoutes: () => [
    {
      id: "company.projects",
      path: "/projects",
      Component: () => <div>Projects</div>,
    },
    {
      id: "core.cron-jobs",
      path: "/cron-jobs",
      Component: () => <div>Cron jobs</div>,
    },
    {
      id: "core.skills",
      path: "/skills",
      Component: () => <div>Skills</div>,
    },
  ],
}));

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <MainLayout />
    </MemoryRouter>,
  );
}

describe("MainLayout navigation mode", () => {
  it("starts in settings mode when a settings page is refreshed", () => {
    renderAt("/skills");

    expect(screen.getByTestId("desktop-sidebar")).toHaveAttribute(
      "data-navigation-mode",
      "settings",
    );
  });

  it("keeps the application sidebar on a primary application page", () => {
    renderAt("/projects");

    expect(screen.getByTestId("desktop-sidebar")).toHaveAttribute(
      "data-navigation-mode",
      "app",
    );
  });
});
