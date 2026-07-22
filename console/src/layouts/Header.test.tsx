import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import Header from "./Header";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
    i18n: {
      language: "zh",
      resolvedLanguage: "zh",
      changeLanguage: vi.fn(),
    },
  }),
}));

vi.mock("../contexts/AuthContext", () => ({
  useAuth: () => ({
    user: null,
    status: { enabled: false },
    clearSession: vi.fn(),
  }),
}));

describe("Header brand control", () => {
  it("renders only the logo and uses it to collapse the sidebar", () => {
    const onToggleSidebar = vi.fn();
    render(
      <Header sidebarCollapsed={false} onToggleSidebar={onToggleSidebar} />,
    );

    expect(screen.queryByText("MelonPaw-Company")).not.toBeInTheDocument();
    expect(screen.queryByText("Company Console")).not.toBeInTheDocument();
    expect(screen.queryByText("agent.currentWorkspace")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "收起导航" }));
    expect(onToggleSidebar).toHaveBeenCalledOnce();
  });

  it("announces the expand action when the sidebar is collapsed", () => {
    render(<Header sidebarCollapsed onToggleSidebar={vi.fn()} />);
    expect(
      screen.getByRole("button", { name: "展开导航" }),
    ).toBeInTheDocument();
  });
});
