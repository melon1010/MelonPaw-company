import { screen } from "@testing-library/react";
import type { TFunction } from "i18next";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import {
  getPermissionGroup,
  getPermissionGroupName,
  getPermissionName,
} from "../rolePresentation";
import RolesPage from "./index";

const { mockListRoles, mockMessage, mockT } = vi.hoisted(() => ({
  mockListRoles: vi.fn(),
  mockMessage: { error: vi.fn(), success: vi.fn() },
  mockT: vi.fn((key: string) => key),
}));

vi.mock("antd", async (importOriginal) => {
  const actual = await importOriginal<typeof import("antd")>();
  return {
    ...actual,
    Table: ({
      columns = [],
      dataSource = [],
    }: {
      columns?: Array<{
        key?: React.Key;
        render?: (value: unknown, row: { code: string }, index: number) => unknown;
      }>;
      dataSource?: Array<{ code: string }>;
    }) => {
      const actions = columns.find((column) => column.key === "actions");
      return (
        <div>
          {dataSource.map((row, index) => (
            <div key={row.code}>
              {actions?.render?.(undefined, row, index) as ReactNode}
            </div>
          ))}
        </div>
      );
    },
  };
});

vi.mock("@/api", () => ({
  companyApi: {
    createRole: vi.fn(),
    deleteRole: vi.fn(),
    listRoles: mockListRoles,
    updateRole: vi.fn(),
  },
}));

vi.mock("@/contexts/AuthContext", () => ({
  useAuth: () => ({
    user: {
      id: "admin-user",
      username: "admin",
      enabled: true,
      roles: ["SYSTEM_ADMIN"],
      permissions: ["roles:read", "roles:manage"],
    },
  }),
}));

vi.mock("@/hooks/useAppMessage", () => ({
  useAppMessage: () => ({ message: mockMessage }),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: mockT }),
}));

describe("enterprise role management", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListRoles.mockResolvedValue({
      roles: [
        {
          code: "SYSTEM_ADMIN",
          name: "System administrator",
          description: "Full platform administration",
          system_role: true,
          permissions: ["users:read", "roles:manage"],
          user_count: 1,
        },
        {
          code: "SHIFT_MANAGER",
          name: "Shift manager",
          description: "Custom role",
          system_role: false,
          permissions: ["projects:read"],
          user_count: 0,
        },
      ],
      permissions: ["users:read", "roles:manage", "projects:read"],
    });
  });

  it("keeps system roles read-only and maps permissions into named groups", async () => {
    renderWithProviders(<RolesPage />, { initialEntries: ["/roles"] });

    expect(
      await screen.findAllByRole("button", {
        name: "common.view",
      }),
    ).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "common.edit" })).toHaveLength(
      1,
    );

    expect(getPermissionGroup("users:read")).toBe("members");
    const t = mockT as unknown as TFunction;
    expect(getPermissionGroupName("members", t)).toBe(
      "enterprise.roles.permissionGroups.members",
    );
    expect(getPermissionName("users:read", t)).toBe(
      "enterprise.roles.permissionNames.usersRead",
    );
  });
});
