import { useEffect, useMemo, useState } from "react";
import { Layout, Dropdown, Popover, Tooltip } from "antd";
import type { MenuProps } from "antd";
import { Button } from "@agentscope-ai/design";
import {
  ChevronDown,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { useAuth } from "../contexts/AuthContext";
import { hasPermission, primaryRole } from "../auth/authorization";
import { Slot } from "../plugins/registry/Slot";
import api from "../api";
import AgentSelector from "../components/AgentSelector";
import SidebarSettingsPanel from "./SidebarSettingsPanel";
import { authApi } from "../api/modules/auth";
import { clearAuthToken } from "../api/config";
import styles from "./index.module.less";

const { Header: AntHeader } = Layout;

interface HeaderProps {
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
}

export default function Header({
  sidebarCollapsed,
  onToggleSidebar,
}: HeaderProps) {
  const { t } = useTranslation();
  const { user, status: authStatus, clearSession } = useAuth();
  const [version, setVersion] = useState("");
  const [settingsOpen, setSettingsOpen] = useState(false);

  useEffect(() => {
    api
      .getVersion()
      .then((response) => setVersion(response?.version || ""))
      .catch(() => setVersion(""));
  }, []);

  const role = user ? primaryRole(user) : "";
  const canUseAgents =
    hasPermission(user, "agents:use") || hasPermission(user, "agents:manage");

  const accountItems = useMemo<MenuProps["items"]>(() => {
    if (!user) return [];
    const items: MenuProps["items"] = [
      {
        key: "identity",
        disabled: true,
        label: (
          <div className={styles.accountMenuIdentity}>
            <strong>{user.display_name || user.username}</strong>
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
  }, [authStatus?.enabled, role, t, user]);

  const handleAccountAction: MenuProps["onClick"] = ({ key }) => {
    if (key !== "logout") return;
    void authApi.logout().finally(() => {
      clearAuthToken();
      clearSession();
      window.location.href = "/login";
    });
  };

  return (
    <AntHeader className={styles.header}>
      <div className={styles.headerLeft}>
        <div className={styles.logoWrapper}>
          <Slot name="header.logo" kind="replace">
            <div className={styles.companyBrand}>
              <span className={styles.companyBrandMark}>
                <img src="/company-mark.svg" alt="" />
              </span>
              <span className={styles.companyBrandCopy}>
                <span className={styles.companyBrandName}>MelonPaw-Company</span>
                <span className={styles.companyBrandMeta}>Company Console</span>
              </span>
            </div>
          </Slot>
          {version && <span className={styles.companyVersion}>v{version}</span>}
        </div>

        <Tooltip
          title={
            sidebarCollapsed
              ? t("sidebar.expand", "展开导航")
              : t("sidebar.collapse", "收起导航")
          }
        >
          <Button
            type="text"
            aria-label={
              sidebarCollapsed
                ? t("sidebar.expand", "展开导航")
                : t("sidebar.collapse", "收起导航")
            }
            icon={
              sidebarCollapsed ? (
                <PanelLeftOpen size={18} />
              ) : (
                <PanelLeftClose size={18} />
              )
            }
            onClick={onToggleSidebar}
            className={`${styles.headerIconButton} ${styles.sidebarTrigger}`}
          />
        </Tooltip>

        {canUseAgents && (
          <div className={styles.headerAgentContext}>
            <span className={styles.headerContextLabel}>
              {t("agent.currentWorkspace")}
            </span>
            <AgentSelector variant="header" />
          </div>
        )}
      </div>

      <Slot name="header.left" kind="fill" />

      <div className={styles.headerRight}>
        <Slot name="header.right" kind="fill" />
        <Popover
          open={settingsOpen}
          onOpenChange={setSettingsOpen}
          placement="bottomRight"
          trigger="click"
          content={
            <SidebarSettingsPanel onClose={() => setSettingsOpen(false)} />
          }
        >
          <Button
            type="text"
            aria-label={t("nav.settings")}
            icon={<Settings size={18} />}
            className={styles.headerIconButton}
          />
        </Popover>
        {user && (
          <Dropdown
            menu={{ items: accountItems, onClick: handleAccountAction }}
            placement="bottomRight"
            trigger={["click"]}
          >
            <button className={styles.headerAccount} type="button">
              <span className={styles.headerAccountAvatar}>
                {(user.display_name || user.username).slice(0, 1).toUpperCase()}
              </span>
              <span className={styles.headerAccountText}>
                <strong>{user.display_name || user.username}</strong>
                <span>{role}</span>
              </span>
              <ChevronDown size={14} className={styles.headerAccountChevron} />
            </button>
          </Dropdown>
        )}
      </div>
    </AntHeader>
  );
}
