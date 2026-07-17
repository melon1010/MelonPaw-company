import { useMemo, useState, type ReactNode } from "react";
import { Dropdown, Popover, Tooltip } from "antd";
import type { MenuProps } from "antd";
import { Button } from "@agentscope-ai/design";
import { ChevronDown, LogOut, Settings } from "lucide-react";
import { useTranslation } from "react-i18next";
import { primaryRole } from "../auth/authorization";
import { authApi } from "../api/modules/auth";
import { clearAuthToken } from "../api/config";
import { useAuth } from "../contexts/AuthContext";
import SidebarSettingsPanel from "./SidebarSettingsPanel";
import styles from "./index.module.less";

interface AccountControlProps {
  variant: "header" | "sidebar";
  collapsed?: boolean;
  placement?: "bottomRight" | "topLeft";
}

export function AccountControl({
  variant,
  collapsed = false,
  placement = "bottomRight",
}: AccountControlProps) {
  const { t } = useTranslation();
  const { user, status: authStatus, clearSession } = useAuth();
  const role = user ? primaryRole(user) : "";
  const displayName = user?.display_name || user?.username || "";

  const accountItems = useMemo<MenuProps["items"]>(() => {
    if (!user) return [];
    const items: MenuProps["items"] = [
      {
        key: "identity",
        disabled: true,
        label: (
          <div className={styles.accountMenuIdentity}>
            <strong>{displayName}</strong>
            <span>{role}</span>
          </div>
        ),
      },
    ];
    if (authStatus?.enabled) {
      items.push(
        { type: "divider" },
        {
          key: "logout",
          icon: <LogOut size={15} />,
          label: t("login.logout"),
        },
      );
    }
    return items;
  }, [authStatus?.enabled, displayName, role, t, user]);

  if (!user) return null;

  const handleAccountAction: MenuProps["onClick"] = ({ key }) => {
    if (key !== "logout") return;
    void authApi.logout().finally(() => {
      clearAuthToken();
      clearSession();
      window.location.href = "/login";
    });
  };

  const isHeader = variant === "header";
  return (
    <Dropdown
      menu={{ items: accountItems, onClick: handleAccountAction }}
      placement={placement}
      trigger={["click"]}
    >
      <button
        className={isHeader ? styles.headerAccount : styles.sidebarAccount}
        type="button"
      >
        <span
          className={
            isHeader ? styles.headerAccountAvatar : styles.sidebarAccountAvatar
          }
        >
          {displayName.slice(0, 1).toUpperCase()}
        </span>
        {!collapsed && (
          <>
            <span
              className={
                isHeader ? styles.headerAccountText : styles.sidebarAccountText
              }
            >
              <strong>{displayName}</strong>
              <span>{role}</span>
            </span>
            <ChevronDown
              size={14}
              className={
                isHeader
                  ? styles.headerAccountChevron
                  : styles.sidebarAccountChevron
              }
            />
          </>
        )}
      </button>
    </Dropdown>
  );
}

interface SettingsControlProps {
  className: string;
  placement: "bottomRight" | "topLeft";
  tooltipPlacement?: "right" | "bottom";
  children?: ReactNode;
}

export function SettingsControl({
  className,
  placement,
  tooltipPlacement = "bottom",
}: SettingsControlProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      placement={placement}
      trigger="click"
      content={<SidebarSettingsPanel />}
    >
      <Tooltip title={t("nav.settings")} placement={tooltipPlacement}>
        <Button
          type="text"
          className={className}
          aria-label={t("nav.settings")}
          icon={<Settings size={16} />}
        />
      </Tooltip>
    </Popover>
  );
}
