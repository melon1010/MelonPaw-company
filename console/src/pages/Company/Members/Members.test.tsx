import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import EnterpriseMembers from "./index";

const {
  mockCreateUser,
  mockDeleteUser,
  mockListRoles,
  mockListSkillPoolSkills,
  mockListUsers,
  mockMessage,
  mockReplaceUserRoles,
  mockT,
  mockUpdateUser,
} = vi.hoisted(() => ({
  mockCreateUser: vi.fn(),
  mockDeleteUser: vi.fn(),
  mockListRoles: vi.fn(),
  mockListSkillPoolSkills: vi.fn(),
  mockListUsers: vi.fn(),
  mockMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  mockReplaceUserRoles: vi.fn(),
  mockT: vi.fn((key: string) => key),
  mockUpdateUser: vi.fn(),
}));

vi.mock("@/api", () => ({
  companyApi: {
    createUser: mockCreateUser,
    deleteUser: mockDeleteUser,
    listRoles: mockListRoles,
    listUsers: mockListUsers,
    replaceUserRoles: mockReplaceUserRoles,
    updateUser: mockUpdateUser,
  },
}));

vi.mock("@/api/modules/skill", () => ({
  skillApi: { listSkillPoolSkills: mockListSkillPoolSkills },
}));

vi.mock("@/contexts/AuthContext", () => ({
  useAuth: () => ({
    user: {
      id: "current-user",
      username: "admin",
      enabled: true,
      roles: ["SYSTEM_ADMIN"],
      permissions: ["users:manage"],
    },
  }),
}));

vi.mock("@/hooks/useAppMessage", () => ({
  useAppMessage: () => ({
    message: mockMessage,
  }),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: mockT }),
}));

describe("enterprise member management", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListUsers.mockResolvedValue([
      {
        id: "current-user",
        username: "admin",
        enabled: true,
        roles: ["SYSTEM_ADMIN"],
        permissions: ["users:manage"],
      },
      {
        id: "employee-user",
        username: "employee",
        enabled: true,
        roles: ["EMPLOYEE"],
        permissions: [],
      },
    ]);
    mockListRoles.mockResolvedValue({
      roles: [
        {
          code: "EMPLOYEE",
          name: "Employee",
          description: "",
          system_role: true,
          permissions: [],
        },
      ],
      permissions: [],
    });
    mockListSkillPoolSkills.mockResolvedValue([
      { name: "documents", description: "Document processing" },
      { name: "web-search", description: "Web search" },
    ]);
    mockDeleteUser.mockResolvedValue(undefined);
  });

  it("loads the shared skill pool and shows optional default skills on create", async () => {
    renderWithProviders(<EnterpriseMembers />, {
      initialEntries: ["/members"],
    });

    fireEvent.click(
      await screen.findByRole("button", {
        name: "enterprise.members.create",
      }),
    );

    expect(
      await screen.findByText("enterprise.members.defaultSkills"),
    ).toBeInTheDocument();
    expect(mockListSkillPoolSkills).toHaveBeenCalledOnce();
  });

  it("keeps the member list available when the shared skill pool is denied", async () => {
    mockListSkillPoolSkills.mockRejectedValueOnce(new Error("Forbidden"));

    renderWithProviders(<EnterpriseMembers />, {
      initialEntries: ["/members"],
    });

    expect(await screen.findAllByText("employee")).toHaveLength(2);
    expect(mockMessage.warning).toHaveBeenCalledWith(
      "enterprise.members.skillsLoadFailed",
    );
  });

  it(
    "prevents self deletion and deletes another member after confirmation",
    async () => {
      renderWithProviders(<EnterpriseMembers />, {
        initialEntries: ["/members"],
      });

      const deleteButtons = await screen.findAllByRole("button", {
        name: "common.delete",
      });
      expect(deleteButtons[0]).toBeDisabled();
      expect(deleteButtons[1]).toBeEnabled();

      fireEvent.click(deleteButtons[1]);
      const dialog = await screen.findByRole("dialog");
      fireEvent.click(
        within(dialog).getByRole("button", { name: "common.delete" }),
      );

      await waitFor(() => {
        expect(mockDeleteUser).toHaveBeenCalledWith("employee-user");
      });
    },
    10_000,
  );
});
