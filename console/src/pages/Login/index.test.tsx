import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import LoginPage from ".";

const mocks = vi.hoisted(() => ({
  bootstrap: vi.fn(),
  getStatus: vi.fn(),
  login: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  navigate: vi.fn(),
  setAuthToken: vi.fn(),
  setSession: vi.fn(),
  toggleTheme: vi.fn(),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router-dom")>();
  return {
    ...actual,
    useNavigate: () => mocks.navigate,
  };
});

vi.mock("../../api/modules/auth", () => ({
  authApi: {
    bootstrap: mocks.bootstrap,
    getStatus: mocks.getStatus,
    login: mocks.login,
  },
}));

vi.mock("../../api/config", () => ({
  setAuthToken: mocks.setAuthToken,
}));

vi.mock("../../hooks/useAppMessage", () => ({
  useAppMessage: () => ({
    message: {
      error: mocks.messageError,
      success: mocks.messageSuccess,
    },
  }),
}));

vi.mock("../../contexts/ThemeContext", () => ({
  useTheme: () => ({ isDark: false, toggleTheme: mocks.toggleTheme }),
}));

vi.mock("../../contexts/AuthContext", () => ({
  useAuth: () => ({ setSession: mocks.setSession }),
}));

function renderLogin(path = "/login") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe("LoginPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getStatus.mockResolvedValue({ enabled: true, has_users: true });
  });

  it("renders the standard sign-in form and accessible theme control", async () => {
    renderLogin();

    expect(
      screen.getByRole("heading", { name: "enterprise.auth.loginTitle" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("login.usernameLabel")).toBeInTheDocument();
    expect(screen.getByLabelText("login.passwordLabel")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "theme.dark" }),
    ).toBeInTheDocument();

    await waitFor(() => expect(mocks.getStatus).toHaveBeenCalledOnce());
  });

  it("renders bootstrap-only fields when the deployment has no users", async () => {
    mocks.getStatus.mockResolvedValue({
      enabled: true,
      has_users: false,
      bootstrap_required: true,
      bootstrap_token_required: true,
    });

    renderLogin();

    expect(
      await screen.findByRole("heading", {
        name: "enterprise.auth.bootstrapTitle",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText("enterprise.auth.displayName"),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText("enterprise.auth.bootstrapToken"),
    ).toBeInTheDocument();
  });

  it("keeps the existing authentication and redirect behavior", async () => {
    const user = userEvent.setup();
    const authUser = {
      id: "user-1",
      username: "operator",
      enabled: true,
      roles: [],
      permissions: [],
    };
    mocks.login.mockResolvedValue({ token: "token-1", user: authUser });

    renderLogin("/login?redirect=/projects");

    await user.type(screen.getByLabelText("login.usernameLabel"), "operator");
    await user.type(screen.getByLabelText("login.passwordLabel"), "secret");
    await user.click(
      screen.getByRole("button", { name: "enterprise.auth.loginAction" }),
    );

    await waitFor(() => {
      expect(mocks.login).toHaveBeenCalledWith("operator", "secret");
    });
    expect(mocks.setAuthToken).toHaveBeenCalledWith("token-1");
    expect(mocks.setSession).toHaveBeenCalledWith(authUser);
    expect(mocks.navigate).toHaveBeenCalledWith("/projects", { replace: true });
  });
});
