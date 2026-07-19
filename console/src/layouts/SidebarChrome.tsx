import type { MenuProps } from "antd";
import { PanelLeftClose, Search } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Slot } from "../plugins/registry/Slot";
import { AccountControl } from "./AccountControls";
import styles from "./index.module.less";

interface SidebarBrandProps {
  collapsed: boolean;
  onToggle: () => void;
  onSearch: () => void;
}

export function SidebarBrand({
  collapsed,
  onToggle,
  onSearch,
}: SidebarBrandProps) {
  const { t } = useTranslation();
  const expandLabel = t("sidebar.expand", "展开导航");
  const searchLabel = t("chat.searchConversationsAction", "搜索对话");
  const collapseLabel = t("sidebar.collapse", "收起导航");

  return (
    <div
      className={`${styles.sidebarBrand} ${
        collapsed ? styles.sidebarBrandCollapsed : ""
      }`}
    >
      <button
        type="button"
        className={styles.sidebarBrandLogo}
        aria-label={collapsed ? expandLabel : "MelonPaw"}
        onClick={collapsed ? onToggle : undefined}
      >
        <Slot name="header.logo" kind="replace">
          <span className={styles.sidebarBrandMark}>
            <img src="/company-mark.svg" alt="" />
          </span>
        </Slot>
      </button>
      {!collapsed && (
        <div className={styles.sidebarBrandActions}>
          <button
            type="button"
            className={styles.sidebarBrandAction}
            aria-label={searchLabel}
            onClick={onSearch}
          >
            <Search size={21} strokeWidth={1.8} aria-hidden />
          </button>
          <button
            type="button"
            className={styles.sidebarBrandAction}
            aria-label={collapseLabel}
            onClick={onToggle}
          >
            <PanelLeftClose size={21} strokeWidth={1.8} aria-hidden />
          </button>
        </div>
      )}
    </div>
  );
}

export function SidebarAccount({
  collapsed,
  navigationItems,
  onNavigationClick,
  onOpenSettings,
}: {
  collapsed: boolean;
  navigationItems?: MenuProps["items"];
  onNavigationClick?: MenuProps["onClick"];
  onOpenSettings?: () => void;
}) {
  return (
    <div
      className={`${styles.sidebarAccountDock} ${
        collapsed ? styles.sidebarAccountDockCollapsed : ""
      }`}
    >
      <AccountControl
        variant="sidebar"
        collapsed={collapsed}
        placement="topLeft"
        navigationItems={navigationItems}
        onNavigationClick={onNavigationClick}
        onOpenSettings={onOpenSettings}
      />
    </div>
  );
}
