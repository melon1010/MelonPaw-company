import { useEffect, useState } from "react";
import { Layout, Dropdown, Space, Tag } from "antd";
import type { MenuProps } from "antd";
import { Button } from "@agentscope-ai/design";
import { Building2, Menu as MenuIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import LanguageSwitcher, {
  LANGUAGE_LIST,
} from "../components/LanguageSwitcher";
import ThemeToggleButton from "../components/ThemeToggleButton";
import { useTheme } from "../contexts/ThemeContext";
import { useAuth } from "../contexts/AuthContext";
import { primaryRole } from "../auth/authorization";
import { Slot } from "../plugins/registry/Slot";
import api from "../api";
import styles from "./index.module.less";

const { Header: AntHeader } = Layout;

export default function Header() {
  const { t, i18n } = useTranslation();
  const { setThemeMode } = useTheme();
  const { user } = useAuth();
  const [version, setVersion] = useState("");

  useEffect(() => {
    api
      .getVersion()
      .then((response) => setVersion(response?.version || ""))
      .catch(() => setVersion(""));
  }, []);

  const mobileMenuItems: MenuProps["items"] = [
    {
      key: "language",
      label: t("sidebar.settings.language"),
      children: LANGUAGE_LIST.map(({ key, label }) => ({
        key,
        label,
        onClick: () => {
          i18n.changeLanguage(key);
          localStorage.setItem("language", key);
        },
      })),
    },
    {
      key: "theme",
      label: t("sidebar.settings.theme"),
      children: [
        { key: "light", label: t("theme.light"), onClick: () => setThemeMode("light") },
        { key: "dark", label: t("theme.dark"), onClick: () => setThemeMode("dark") },
        { key: "system", label: t("theme.system"), onClick: () => setThemeMode("system") },
      ],
    },
  ];

  return (
    <AntHeader className={styles.header}>
      <div className={styles.logoWrapper}>
        <Slot name="header.logo" kind="replace">
          <div className={styles.companyBrand}>
            <span className={styles.companyBrandMark}>
              <Building2 size={16} />
            </span>
            <span className={styles.companyBrandName}>MelonPaw-Company</span>
          </div>
        </Slot>
        {version && <span className={styles.companyVersion}>v{version}</span>}
      </div>

      <Slot name="header.left" kind="fill" />

      <Space size="middle">
        <Slot name="header.right" kind="fill" />
        {user && (
          <Tag className={styles.headerIdentity}>
            {user.display_name || user.username} · {primaryRole(user)}
          </Tag>
        )}
        <div className={styles.headerDivider} />
        <span className={styles.hideOnMobile}>
          <LanguageSwitcher />
        </span>
        <span className={styles.hideOnMobile}>
          <ThemeToggleButton />
        </span>
        <Dropdown menu={{ items: mobileMenuItems }} placement="bottomRight">
          <Button
            type="text"
            icon={<MenuIcon size={18} />}
            className={styles.showOnMobile}
            title={t("header.resources")}
          />
        </Dropdown>
      </Space>
    </AntHeader>
  );
}
