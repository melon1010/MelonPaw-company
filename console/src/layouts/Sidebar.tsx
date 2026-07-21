import { Layout, Tooltip } from "antd";
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ArrowLeft, MessageSquareText, SquarePen } from "lucide-react";
import SidebarSessionList from "./SidebarSessionList";
import api from "../api";
import {
  syncSessionsGlobal,
  type ExtendedSession,
} from "../stores/sessionListStore";
import { buildSessionPath } from "../utils/sessionRoute";
import sessionApi from "../pages/Chat/sessionApi";
import styles from "./index.module.less";
import { useTheme } from "../contexts/ThemeContext";
import { useAuth } from "../contexts/AuthContext";
import {
  canAccessRoute,
  filterMenuForUser,
  hasPermission,
} from "../auth/authorization";
import { useMenuItems, useRoutes } from "../plugins/registry/hooks";
import { Slot } from "../plugins/registry/Slot";
import { flattenMenu, type FlatMenuEntry } from "./registry/adapter";
import { SidebarAccount, SidebarBrand } from "./SidebarChrome";
import { useAgentStore } from "../stores/agentStore";

const { Sider } = Layout;
const MOBILE_SIDEBAR_QUERY = "(max-width: 768px)";
const INBOX_BADGE_POLLING_MS = 6000;

const PRIMARY_NAV_IDS = ["company.projects", "core.cron-jobs"] as const;
const PRIMARY_NAV_ID_SET = new Set<string>(PRIMARY_NAV_IDS);

function isMobileSidebarViewport() {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia(MOBILE_SIDEBAR_QUERY).matches
  );
}

interface SidebarProps {
  selectedKey: string;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  settingsMode: boolean;
  onEnterSettings: () => void;
  onExitSettings: () => void;
}

export default function Sidebar({
  selectedKey,
  collapsed,
  onToggleCollapsed,
  settingsMode,
  onEnterSettings,
  onExitSettings,
}: SidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();
  const { isDark } = useTheme();
  const { user } = useAuth();
  const canUseAgents = hasPermission(user, "agents:use");
  const canUseInbox = canAccessRoute("core.inbox", user);
  const selectedAgent = useAgentStore((state) => state.selectedAgent);
  const [isMobile, setIsMobile] = useState(isMobileSidebarViewport);
  const [hasInboxUnread, setHasInboxUnread] = useState(false);

  const rawAgentMenu = useMenuItems("primary.agentScoped");
  const rawSettingsMenu = useMenuItems("primary.settings");
  const routes = useRoutes();
  const authorizedAgentMenu = useMemo(
    () => filterMenuForUser(rawAgentMenu, user),
    [rawAgentMenu, user],
  );
  const authorizedSettingsMenu = useMemo(
    () => filterMenuForUser(rawSettingsMenu, user),
    [rawSettingsMenu, user],
  );

  const agentEntries = useMemo(
    () => flattenMenu(authorizedAgentMenu, routes, 18),
    [authorizedAgentMenu, routes],
  );
  const settingsEntries = useMemo(
    () => flattenMenu(authorizedSettingsMenu, routes, 18),
    [authorizedSettingsMenu, routes],
  );
  const primaryEntries = useMemo(() => {
    const entriesById = new Map(
      agentEntries.map((entry) => [entry.key, entry]),
    );
    return PRIMARY_NAV_IDS.map((id) => entriesById.get(id)).filter(
      (entry): entry is FlatMenuEntry => Boolean(entry),
    );
  }, [agentEntries]);
  const secondaryAgentEntries = useMemo(
    () => agentEntries.filter((entry) => !PRIMARY_NAV_ID_SET.has(entry.key)),
    [agentEntries],
  );

  useEffect(() => {
    if (
      typeof window === "undefined" ||
      typeof window.matchMedia !== "function"
    ) {
      return;
    }
    const mediaQuery = window.matchMedia(MOBILE_SIDEBAR_QUERY);
    const syncMobileSidebar = () => setIsMobile(mediaQuery.matches);
    syncMobileSidebar();
    mediaQuery.addEventListener("change", syncMobileSidebar);
    return () => mediaQuery.removeEventListener("change", syncMobileSidebar);
  }, []);

  useEffect(() => {
    if (!canUseInbox) {
      setHasInboxUnread(false);
      return;
    }
    const loadUnreadState = async () => {
      try {
        const [inboxRes, pushRes] = await Promise.all([
          api.getInboxEvents({ unread_only: true, limit: 1 }),
          api.getPushMessages(),
        ]);
        setHasInboxUnread(
          (inboxRes?.events?.length || 0) > 0 ||
            (pushRes?.pending_approvals?.length || 0) > 0,
        );
      } catch {
        // Preserve the last known state while polling is unavailable.
      }
    };
    void loadUnreadState();
    const timer = window.setInterval(loadUnreadState, INBOX_BADGE_POLLING_MS);
    return () => window.clearInterval(timer);
  }, [canUseInbox]);

  useEffect(() => {
    if (!canUseAgents) return;
    let cancelled = false;
    syncSessionsGlobal([]);
    void sessionApi
      .getSessionList()
      .then((list) => {
        if (!cancelled) {
          syncSessionsGlobal(list as ExtendedSession[]);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [canUseAgents, selectedAgent]);

  const handleNewChat = useCallback(() => {
    if (location.pathname.startsWith("/chat")) {
      window.dispatchEvent(new CustomEvent("qwenpaw:sidebar-new-chat"));
      return;
    }
    sessionStorage.setItem("qwenpaw_pending_new_chat", "1");
    navigate("/chat");
  }, [location.pathname, navigate]);

  const handleSearch = useCallback(() => {
    if (location.pathname.startsWith("/chat")) {
      window.dispatchEvent(new CustomEvent("qwenpaw:sidebar-search"));
      return;
    }
    sessionStorage.setItem("qwenpaw_pending_chat_search", "1");
    navigate("/chat");
  }, [location.pathname, navigate]);

  const handleSidebarSessionClick = useCallback(
    (sessionId: string) => {
      navigate(buildSessionPath(sessionApi.getEffectiveSessionId(sessionId)));
    },
    [navigate],
  );

  const navigateEntry = useCallback(
    (entry: FlatMenuEntry) => {
      if (entry.href) {
        window.open(entry.href, "_blank", "noopener,noreferrer");
      } else {
        navigate(entry.path);
      }
    },
    [navigate],
  );

  const siderWidth = collapsed ? (isMobile ? 52 : 52) : 260;

  const renderPrimaryButton = (
    entry: FlatMenuEntry,
    iconOverride?: ReactNode,
  ) => {
    const isActive = selectedKey === entry.key;
    const label = typeof entry.label === "string" ? entry.label : undefined;
    const content = (
      <button
        key={entry.key}
        type="button"
        aria-label={label}
        className={`${styles.webuiNavButton} ${
          isActive ? styles.webuiNavButtonActive : ""
        }`}
        onClick={() => navigateEntry(entry)}
      >
        <span className={styles.webuiNavIcon}>
          {iconOverride ?? entry.icon}
        </span>
        {!collapsed && (
          <span className={styles.webuiNavLabel}>{entry.label}</span>
        )}
      </button>
    );
    return collapsed ? (
      <Tooltip key={entry.key} title={entry.label} placement="right">
        {content}
      </Tooltip>
    ) : (
      content
    );
  };

  const actionButton = (
    key: string,
    label: ReactNode,
    icon: ReactNode,
    onClick: () => void,
    active = false,
  ) => {
    const content = (
      <button
        type="button"
        aria-label={typeof label === "string" ? label : undefined}
        className={`${styles.webuiNavButton} ${
          active ? styles.webuiNavButtonActive : ""
        }`}
        onClick={onClick}
      >
        <span className={styles.webuiNavIcon}>{icon}</span>
        {!collapsed && <span className={styles.webuiNavLabel}>{label}</span>}
      </button>
    );
    return collapsed ? (
      <Tooltip key={key} title={label} placement="right">
        {content}
      </Tooltip>
    ) : (
      <span key={key}>{content}</span>
    );
  };

  return (
    <Sider
      width={siderWidth}
      className={`${styles.sider} ${styles.webuiSider} ${
        collapsed ? styles.siderCollapsed : ""
      } ${isDark ? styles.siderDark : ""}`}
    >
      {settingsMode ? (
        <div className={styles.webuiSettingsHeader}>
          <button
            type="button"
            className={styles.webuiSettingsBack}
            onClick={onExitSettings}
          >
            <ArrowLeft size={18} aria-hidden />
            {!collapsed && (
              <span>
                {t("sidebar.settings.returnApp", "Return to application")}
              </span>
            )}
          </button>
        </div>
      ) : (
        <SidebarBrand
          collapsed={collapsed}
          onToggle={onToggleCollapsed}
          onSearch={handleSearch}
        />
      )}

      <div className={styles.webuiSidebarBody}>
        {settingsMode ? (
          <nav
            className={styles.webuiSettingsNav}
            aria-label={t("nav.settings", "Settings")}
          >
            {secondaryAgentEntries.length > 0 && (
              <section className={styles.webuiSettingsSection}>
                {!collapsed && (
                  <div className={styles.webuiSectionLabel}>
                    {t("enterprise.nav.operations", "Operations")}
                  </div>
                )}
                {secondaryAgentEntries.map((entry) =>
                  renderPrimaryButton(entry),
                )}
              </section>
            )}
            {settingsEntries.length > 0 && (
              <section className={styles.webuiSettingsSection}>
                {!collapsed && (
                  <div className={styles.webuiSectionLabel}>
                    {t("enterprise.nav.administration", "Administration")}
                  </div>
                )}
                {settingsEntries.map((entry) => renderPrimaryButton(entry))}
              </section>
            )}
          </nav>
        ) : (
          <>
            <nav className={styles.webuiPrimaryNav} aria-label={t("nav.chat")}>
              {actionButton(
                "new-chat",
                t("chat.newChatTooltip"),
                <SquarePen size={18} />,
                handleNewChat,
                selectedKey === "core.chat",
              )}
              {primaryEntries.map((entry) =>
                renderPrimaryButton(
                  entry,
                  entry.key === "core.inbox" ? (
                    <span className={styles.webuiInboxIcon}>
                      {entry.icon}
                      {hasInboxUnread && (
                        <span className={styles.webuiUnreadDot} />
                      )}
                    </span>
                  ) : undefined,
                ),
              )}
              <Slot name="sider.top" kind="fill" />
            </nav>

            {!collapsed && canUseAgents && (
              <section className={styles.webuiChatsSection}>
                <div className={styles.webuiSectionLabel}>
                  {t("chat.recentConversations", "Recent chats")}
                </div>
                <SidebarSessionList
                  key={selectedAgent}
                  historyOnly
                  onNewChat={handleNewChat}
                  onSessionClick={handleSidebarSessionClick}
                />
              </section>
            )}
            {collapsed && (
              <div className={styles.webuiCollapsedChatMark} aria-hidden>
                <MessageSquareText size={17} />
              </div>
            )}
            <Slot name="sider.bottom" kind="fill" />
          </>
        )}
      </div>

      {!settingsMode && (
        <SidebarAccount
          collapsed={collapsed}
          onOpenSettings={onEnterSettings}
        />
      )}
    </Sider>
  );
}
