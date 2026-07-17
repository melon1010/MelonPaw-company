import { Layout, Tooltip } from "antd";
import { PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Slot } from "../plugins/registry/Slot";
import { AccountControl, SettingsControl } from "./AccountControls";
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

  return (
    <AntHeader className={styles.header}>
      <div className={styles.headerLeft}>
        <Tooltip
          title={
            sidebarCollapsed
              ? t("sidebar.expand", "展开导航")
              : t("sidebar.collapse", "收起导航")
          }
        >
          <button
            type="button"
            aria-label={
              sidebarCollapsed
                ? t("sidebar.expand", "展开导航")
                : t("sidebar.collapse", "收起导航")
            }
            className={styles.logoWrapper}
            onClick={onToggleSidebar}
          >
            <span className={styles.logoDefault}>
              <Slot name="header.logo" kind="replace">
                <span className={styles.companyBrandMark}>
                  <img src="/company-mark.svg" alt="" />
                </span>
              </Slot>
            </span>
            <span className={styles.logoToggleIcon} aria-hidden="true">
              {sidebarCollapsed ? (
                <PanelLeftOpen size={18} />
              ) : (
                <PanelLeftClose size={18} />
              )}
            </span>
          </button>
        </Tooltip>
      </div>

      <Slot name="header.left" kind="fill" />

      <div className={styles.headerRight}>
        <Slot name="header.right" kind="fill" />
        <SettingsControl
          className={styles.headerIconButton}
          placement="bottomRight"
        />
        <AccountControl variant="header" placement="bottomRight" />
      </div>
    </AntHeader>
  );
}
