import { useState, type ReactNode } from "react";
import { Dropdown, Popover, Tooltip } from "antd";
import type { MenuProps } from "antd";
import { Button } from "@agentscope-ai/design";
import {
  Check,
  ChevronDown,
  ChevronRight,
  Layers3,
  Languages,
  LogOut,
  Monitor,
  Moon,
  Palette,
  Settings,
  Sun,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { primaryRole } from "../auth/authorization";
import { authApi } from "../api/modules/auth";
import { languageApi } from "../api/modules/language";
import { clearAuthToken } from "../api/config";
import { useAuth } from "../contexts/AuthContext";
import { useTheme, type ThemeMode } from "../contexts/ThemeContext";
import AgentScopeSwitcher from "../components/AgentScopeSwitcher";
import { useAgentStore } from "../stores/agentStore";
import SidebarSettingsPanel from "./SidebarSettingsPanel";
import styles from "./index.module.less";

const LANGUAGES = [
  { key: "en", label: "English" },
  { key: "zh", label: "简体中文" },
  { key: "ja", label: "日本語" },
  { key: "ru", label: "Русский" },
  { key: "pt-BR", label: "Português" },
  { key: "id", label: "Bahasa Indonesia" },
  { key: "vi", label: "Tiếng Việt" },
] as const;

const KNOWN_LANGUAGE_KEYS = new Set<string>(
  LANGUAGES.map((language) => language.key),
);

interface AccountControlProps {
  variant: "header" | "sidebar";
  collapsed?: boolean;
  mobile?: boolean;
  placement?: "bottomRight" | "topLeft";
  navigationItems?: MenuProps["items"];
  onNavigationClick?: MenuProps["onClick"];
  onOpenSettings?: () => void;
}

export function AccountControl({
  variant,
  collapsed = false,
  mobile = false,
  placement = "bottomRight",
  navigationItems = [],
  onNavigationClick,
  onOpenSettings,
}: AccountControlProps) {
  const { t, i18n } = useTranslation();
  const { user, status: authStatus, clearSession } = useAuth();
  const { themeMode, setThemeMode } = useTheme();
  const selectedAgent = useAgentStore((state) => state.selectedAgent);
  const agents = useAgentStore((state) => state.agents);
  const role = user ? primaryRole(user) : "";
  const displayName = user?.display_name || user?.username || "";
  const selectedAgentSummary = agents.find(
    (agent) => agent.id === selectedAgent,
  );
  const selectedScope =
    selectedAgentSummary?.scope ||
    (selectedAgent === "default" || selectedAgent.startsWith("personal-")
      ? "PERSONAL"
      : selectedAgent.startsWith("project-")
      ? "PROJECT"
      : "SERVICE");
  const workspaceName =
    selectedAgentSummary?.name?.trim() ||
    (selectedScope === "PROJECT"
      ? t("chat.projectScope", "Project space")
      : selectedScope === "PERSONAL"
      ? t("chat.personalScope", "Personal space")
      : t("chat.agentScope", "Agent space"));
  const identityTitle = `${displayName} · ${workspaceName}`;
  const rawLanguage = i18n.resolvedLanguage || i18n.language;
  const shortLanguage = rawLanguage.split("-")[0];
  const currentLanguage = KNOWN_LANGUAGE_KEYS.has(rawLanguage)
    ? rawLanguage
    : shortLanguage;

  const themeOptions: Array<{
    key: ThemeMode;
    label: string;
    icon: ReactNode;
  }> = [
    { key: "light", label: t("theme.light", "Light"), icon: <Sun size={15} /> },
    { key: "dark", label: t("theme.dark", "Dark"), icon: <Moon size={15} /> },
    {
      key: "system",
      label: t("theme.system", "System"),
      icon: <Monitor size={15} />,
    },
  ];

  const optionLabel = (label: string, selected: boolean) => (
    <span className={styles.accountSubmenuLabel}>
      <span>{label}</span>
      {selected && <Check size={14} aria-hidden />}
    </span>
  );

  const themeSubmenu = (
    <Dropdown
      trigger={mobile ? ["click"] : ["hover", "click"]}
      placement={mobile ? "topLeft" : "bottomRight"}
      align={mobile ? undefined : { offset: [210, -30] }}
      overlayClassName={`${styles.accountSubmenuPopup} ${
        mobile ? styles.accountSubmenuPopupMobile : ""
      }`}
      menu={{
        items: themeOptions.map((option) => ({
          key: option.key,
          icon: option.icon,
          label: optionLabel(option.label, themeMode === option.key),
        })),
        onClick: ({ key }) => setThemeMode(String(key) as ThemeMode),
      }}
    >
      <span
        className={styles.accountSubmenuTrigger}
        onClick={(event) => event.stopPropagation()}
      >
        <span>{t("sidebar.settings.theme", "Theme")}</span>
        <ChevronRight size={14} aria-hidden />
      </span>
    </Dropdown>
  );

  const languageSubmenu = (
    <Dropdown
      trigger={mobile ? ["click"] : ["hover", "click"]}
      placement={mobile ? "topLeft" : "bottomRight"}
      align={mobile ? undefined : { offset: [210, -30] }}
      overlayClassName={`${styles.accountSubmenuPopup} ${
        mobile ? styles.accountSubmenuPopupMobile : ""
      }`}
      menu={{
        items: LANGUAGES.map((language) => ({
          key: language.key,
          label: optionLabel(language.label, currentLanguage === language.key),
        })),
        onClick: ({ key }) => {
          const language = String(key);
          void i18n.changeLanguage(language);
          localStorage.setItem("language", language);
          void languageApi.updateLanguage(language).catch(() => undefined);
        },
      }}
    >
      <span
        className={styles.accountSubmenuTrigger}
        onClick={(event) => event.stopPropagation()}
      >
        <span>{t("sidebar.settings.language", "Language")}</span>
        <ChevronRight size={14} aria-hidden />
      </span>
    </Dropdown>
  );

  const accountItems: MenuProps["items"] = [];
  if (user) {
    accountItems.push(
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
      { type: "divider" },
    );
    if (onOpenSettings) {
      accountItems.push({
        key: "open-settings",
        icon: <Settings size={15} />,
        label: t("nav.settings", "Settings"),
      });
    }
    accountItems.push(
      {
        key: "workspace",
        icon: <Layers3 size={15} />,
        label: <AgentScopeSwitcher variant="menu" mobile={mobile} />,
      },
      {
        key: "theme",
        icon: <Palette size={15} />,
        label: themeSubmenu,
      },
      {
        key: "language",
        icon: <Languages size={15} />,
        label: languageSubmenu,
      },
    );
    if (navigationItems.length > 0) {
      accountItems.push({ type: "divider" }, ...navigationItems);
    }
    if (authStatus?.enabled) {
      accountItems.push(
        { type: "divider" },
        {
          key: "logout",
          icon: <LogOut size={15} />,
          label: t("login.logout"),
        },
      );
    }
  }

  if (!user) return null;

  const handleAccountAction: MenuProps["onClick"] = ({ key }) => {
    const actionKey = String(key);
    if (actionKey === "open-settings") {
      onOpenSettings?.();
      return;
    }
    if (actionKey === "workspace") return;
    if (actionKey.startsWith("theme:")) {
      setThemeMode(actionKey.slice("theme:".length) as ThemeMode);
      return;
    }
    if (actionKey.startsWith("language:")) {
      const language = actionKey.slice("language:".length);
      void i18n.changeLanguage(language);
      localStorage.setItem("language", language);
      void languageApi.updateLanguage(language).catch(() => undefined);
      return;
    }
    if (actionKey !== "logout") {
      onNavigationClick?.({ key: actionKey } as Parameters<
        NonNullable<MenuProps["onClick"]>
      >[0]);
      return;
    }
    void authApi.logout().finally(() => {
      clearAuthToken();
      clearSession();
      window.location.href = "/login";
    });
  };

  const isHeader = variant === "header";
  return (
    <Dropdown
      menu={{
        items: accountItems,
        onClick: handleAccountAction,
      }}
      placement={placement}
      trigger={mobile ? ["click"] : ["hover", "click"]}
      overlayClassName={`${styles.accountMenuPopup} ${
        mobile ? styles.accountMenuPopupMobile : ""
      }`}
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
              <strong title={identityTitle}>
                {displayName}
                {!isHeader && (
                  <span className={styles.sidebarAccountWorkspace}>
                    {" · "}
                    {workspaceName}
                  </span>
                )}
              </strong>
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
