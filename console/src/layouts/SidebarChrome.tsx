import { Tooltip } from "antd";
import { Button } from "@agentscope-ai/design";
import { PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Slot } from "../plugins/registry/Slot";
import { AccountControl, SettingsControl } from "./AccountControls";
import styles from "./index.module.less";

interface SidebarBrandProps {
  collapsed: boolean;
  onToggle: () => void;
}

export function SidebarBrand({ collapsed, onToggle }: SidebarBrandProps) {
  const { t } = useTranslation();
  const toggleLabel = collapsed
    ? t("sidebar.expand", "展开导航")
    : t("sidebar.collapse", "收起导航");

  return (
    <div
      className={`${styles.sidebarBrand} ${
        collapsed ? styles.sidebarBrandCollapsed : ""
      }`}
    >
      <Tooltip title={toggleLabel} placement="right">
        <Button
          type="text"
          className={styles.sidebarBrandToggle}
          aria-label={toggleLabel}
          onClick={onToggle}
        >
          <Slot name="header.logo" kind="replace">
            <span className={styles.sidebarBrandMark}>
              <img src="/company-mark.svg" alt="" />
            </span>
          </Slot>
          <span className={styles.sidebarBrandToggleIcon} aria-hidden>
            {collapsed ? (
              <PanelLeftOpen size={17} />
            ) : (
              <PanelLeftClose size={17} />
            )}
          </span>
        </Button>
      </Tooltip>
      {!collapsed && (
        <span className={styles.sidebarBrandCopy}>
          <strong>MelonPaw</strong>
          <span>Company Console</span>
        </span>
      )}
    </div>
  );
}

export function SidebarAccount({ collapsed }: { collapsed: boolean }) {
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
      />
      <SettingsControl
        className={styles.sidebarAccountSettings}
        placement="topLeft"
        tooltipPlacement="right"
      />
    </div>
  );
}
