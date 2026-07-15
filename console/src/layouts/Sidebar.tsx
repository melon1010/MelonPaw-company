import { Layout, Menu, Tooltip, Badge } from "antd";
import { useState, useEffect, useMemo, useCallback } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { SparkEmailLine } from "@agentscope-ai/icons";
import { MessageSquareText } from "lucide-react";
import SidebarSessionList from "./SidebarSessionList";
import api from "../api";
import {
  syncSessionsGlobal,
  type ExtendedSession,
} from "../stores/sessionListStore";
import { useSidebarModeStore } from "../stores/sidebarModeStore";
import { buildSessionPath, getSessionIdFromPath } from "../utils/sessionRoute";
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
import {
  deriveOpenKeys,
  findMenuItem,
  flattenMenu,
  renderIcon,
  routeIdToPath,
  toAntdItems,
} from "./registry/adapter";
import type { FlatMenuEntry } from "./registry/adapter";
import type { MenuItem } from "../plugins/registry/types";
import type { ReactNode } from "react";

// ── Layout ────────────────────────────────────────────────────────────────

const { Sider } = Layout;
const MOBILE_SIDEBAR_QUERY = "(max-width: 768px)";

function isMobileSidebarViewport() {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia(MOBILE_SIDEBAR_QUERY).matches
  );
}
const INBOX_BADGE_POLLING_MS = 6000;

// ── Simple mode whitelist ─────────────────────────────────────────────────

/** Menu item IDs that remain visible in simple sidebar mode (no groups). */
const SIMPLE_MODE_WHITELIST = new Set([
  "company.workbench",
  "company.projects",
  "company.members",
  "company.tasks",
  "core.inbox",
  "core.workspace",
  "core.skills",
  "core.channels",
  "core.cron-jobs",
  "core.agent-config",
  "core.models",
]);

/**
 * Flatten a MenuItem tree into a leaf-only list for simple sidebar mode.
 * Groups are eliminated entirely — only whitelisted children survive
 * as top-level items.
 */
function flattenMenuForSimpleMode(items: MenuItem[]): MenuItem[] {
  const result: MenuItem[] = [];
  for (const rawItem of items) {
    const item = rawItem as MenuItem & { __children?: MenuItem[] };
    if (item.__children && item.__children.length > 0) {
      for (const child of item.__children) {
        if (SIMPLE_MODE_WHITELIST.has(child.id)) {
          result.push(child);
        }
      }
    } else if (SIMPLE_MODE_WHITELIST.has(item.id)) {
      result.push(item);
    }
  }
  return result;
}

// ── Types ─────────────────────────────────────────────────────────────────

interface SidebarProps {
  /** Route id of the currently active page (e.g. "core.workspace"). */
  selectedKey: string;
  collapsed: boolean;
}

// ── Sidebar ───────────────────────────────────────────────────────────────

export default function Sidebar({ selectedKey, collapsed }: SidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();
  const { isDark } = useTheme();
  const { user } = useAuth();
  const canUseAgents = hasPermission(user, "agents:use");
  const canUseInbox = canAccessRoute("core.inbox", user);
  const currentSessionId = getSessionIdFromPath(location.pathname);
  const chatPath = buildSessionPath(currentSessionId);
  // Start collapsed on mobile so the first paint does not overlay/obscure
  // the main content on narrow viewports.
  const [isMobile, setIsMobile] = useState(isMobileSidebarViewport);
  const [hasInboxUnread, setHasInboxUnread] = useState(false);

  // Sidebar mode: "simple" (only core items) or "full" (everything)
  const { mode: sidebarMode } = useSidebarModeStore();

  // Menu + route snapshots from registry (builtin + plugin registrations merged).
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

  // Apply simple-mode filtering when enabled
  const agentMenu = useMemo(
    () =>
      sidebarMode === "simple"
        ? flattenMenuForSimpleMode(authorizedAgentMenu)
        : authorizedAgentMenu,
    [authorizedAgentMenu, sidebarMode],
  );
  const settingsMenu = useMemo(
    () =>
      sidebarMode === "simple"
        ? flattenMenuForSimpleMode(authorizedSettingsMenu)
        : authorizedSettingsMenu,
    [authorizedSettingsMenu, sidebarMode],
  );

  // Flat nav entries for simple mode (icon + label + path)
  const simpleFlatNav = useMemo(() => {
    if (sidebarMode !== "simple" && !isMobile) return [];
    const flatAgentMenu = isMobile
      ? flattenMenuForSimpleMode(authorizedAgentMenu)
      : agentMenu;
    const flatSettingsMenu = isMobile
      ? flattenMenuForSimpleMode(authorizedSettingsMenu)
      : settingsMenu;
    return [
      ...flattenMenu(flatAgentMenu, routes, 16),
      ...flattenMenu(flatSettingsMenu, routes, 16),
    ];
  }, [
    agentMenu,
    settingsMenu,
    authorizedAgentMenu,
    authorizedSettingsMenu,
    routes,
    sidebarMode,
    isMobile,
  ]);

  // ── Effects ──────────────────────────────────────────────────────────────

  useEffect(() => {
    if (
      typeof window === "undefined" ||
      typeof window.matchMedia !== "function"
    ) {
      return;
    }

    const mediaQuery = window.matchMedia(MOBILE_SIDEBAR_QUERY);
    const syncMobileSidebar = () => {
      setIsMobile(mediaQuery.matches);
    };

    syncMobileSidebar();
    mediaQuery.addEventListener("change", syncMobileSidebar);

    return () => {
      mediaQuery.removeEventListener("change", syncMobileSidebar);
    };
  }, []);
  useEffect(() => {
    if (!canUseInbox) {
      setHasInboxUnread(false);
      return;
    }
    const loadUnreadState = async () => {
      try {
        const [inboxRes, pushRes] = await Promise.all([
          api.getInboxEvents({
            unread_only: true,
            limit: 1,
          }),
          api.getPushMessages(),
        ]);
        const hasUnreadEvents = (inboxRes?.events?.length || 0) > 0;
        const hasPendingApprovals =
          (pushRes?.pending_approvals?.length || 0) > 0;
        setHasInboxUnread(hasUnreadEvents || hasPendingApprovals);
      } catch {
        // Keep previous state when polling fails.
      }
    };
    void loadUnreadState();
    const timer = window.setInterval(() => {
      void loadUnreadState();
    }, INBOX_BADGE_POLLING_MS);
    return () => window.clearInterval(timer);
  }, [canUseInbox]);

  // ── Pre-fetch sessions on mount ───────────────────────────────────────────
  // On mobile the sidebar starts collapsed so SidebarSessionList is unmounted
  // and never fetches.  When the user expands the sidebar the list mounts fresh
  // but the Zustand store may still be empty (ChatSessionInitializer may not
  // have synced yet).  Proactively fetch sessions into the store so the data
  // is ready the moment the user expands.  Fire on mount regardless of
  // sidebar mode (the default "full" mode also benefits from this).
  // Uses sessionApi.getSessionList() instead of raw api.listChats() to ensure
  // the same data processing pipeline (dedup, realId, generating state) as
  // the desktop ChatSessionDrawer.
  useEffect(() => {
    if (!canUseAgents) return;
    let cancelled = false;
    void (async () => {
      try {
        const list = await sessionApi.getSessionList();
        if (!cancelled && list.length > 0) {
          syncSessionsGlobal(list as ExtendedSession[]);
        }
      } catch {
        // Best-effort: let SidebarSessionList retry on its own.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [canUseAgents]);

  // ── Adapter: convert MenuItem trees to antd, with inbox badge decoration.

  /** Wrap the inbox label with the unread-Badge while keeping all other labels intact. */
  const decorateLabel = (item: MenuItem, label: ReactNode): ReactNode => {
    if (item.id !== "core.inbox" || label == null) return label;
    return (
      <Badge dot={hasInboxUnread} color="var(--mp-accent)" offset={[5, 7]}>
        <span>{label}</span>
      </Badge>
    );
  };

  const agentMenuItems = useMemo(
    () => toAntdItems(agentMenu, { collapsed, decorateLabel }),
    // hasInboxUnread closure inside decorateLabel — listed as dep explicitly.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [agentMenu, collapsed, hasInboxUnread],
  );

  const settingsMenuItems = useMemo(
    () => toAntdItems(settingsMenu, { collapsed }),
    [settingsMenu, collapsed],
  );

  const openKeys = useMemo(
    () => [...deriveOpenKeys(agentMenu), ...deriveOpenKeys(settingsMenu)],
    [agentMenu, settingsMenu],
  );

  const collapsedNavItems = useMemo(() => {
    // Sticky chat is its own carve-out (lives outside menu data — see builtinMenu.ts).
    const stickyChat: FlatMenuEntry = {
      key: "core.chat",
      icon: <MessageSquareText size={18} />,
      path: chatPath,
      label: t("nav.chat"),
    };
    // Inbox in collapsed mode shows a dot overlay on its icon (kept Sidebar-local
    // for the same reason as decorateLabel: live state isn't menu data).
    const decorateInboxIcon = (icon: ReactNode): ReactNode => (
      <span style={{ position: "relative", display: "inline-flex" }}>
        {icon ?? <SparkEmailLine size={18} />}
        {hasInboxUnread && (
          <span
            style={{
              position: "absolute",
              top: -1,
              right: -3,
              width: 6,
              height: 6,
              borderRadius: "50%",
              background: "var(--mp-accent)",
            }}
          />
        )}
      </span>
    );
    const flat = [
      ...(canUseAgents ? [stickyChat] : []),
      ...flattenMenu(agentMenu, routes, 18),
      ...flattenMenu(settingsMenu, routes, 18),
    ];
    return flat.map((entry) =>
      entry.key === "core.inbox"
        ? { ...entry, icon: decorateInboxIcon(entry.icon) }
        : entry,
    );
  }, [agentMenu, settingsMenu, routes, chatPath, t, hasInboxUnread, canUseAgents]);

  // ── Handlers ──────────────────────────────────────────────────────────────

  const handleMenuClick = (key: string, allItems: MenuItem[]) => {
    const item = findMenuItem(allItems, key);
    if (item?.href) {
      window.open(item.href, "_blank", "noopener,noreferrer");
      return;
    }
    const path = routeIdToPath(item?.route, routes);
    if (path) navigate(path);
  };

  /**
   * New chat: if we're already on the chat page, dispatch the event so
   * ChatSessionInitializer (which is mounted) creates the session.
   * If we're on another page, navigate to /chat without a session id —
   * the chat page will auto-create a new session on mount.
   */
  const handleNewChat = useCallback(() => {
    const onChatPage = location.pathname.startsWith("/chat");
    if (onChatPage) {
      window.dispatchEvent(new CustomEvent("qwenpaw:sidebar-new-chat"));
    } else {
      sessionStorage.setItem("qwenpaw_pending_new_chat", "1");
      navigate("/chat");
    }
  }, [location.pathname, navigate]);

  /**
   * Session click: navigate directly without relying on ChatSessionInitializer.
   * Resolve realId (backend UUID) to avoid exposing local timestamp in URL.
   */
  const handleSidebarSessionClick = useCallback(
    (sessionId: string) => {
      const effectiveId = sessionApi.getEffectiveSessionId(sessionId);
      const targetPath = buildSessionPath(effectiveId);
      navigate(targetPath);
    },
    [navigate],
  );

  // ── Render ────────────────────────────────────────────────────────────────

  const siderWidth = collapsed ? (isMobile ? 56 : 68) : 252;
  const isChatActive = selectedKey === "core.chat";
  // `renderIcon` retained for tree-shaking awareness.
  void renderIcon;

  // On mobile, the expanded sidebar shows sessions (like simple mode) instead
  // of the full menu — matching the desktop history panel UX.
  const isSimpleExpanded = (sidebarMode === "simple" || isMobile) && !collapsed;

  return (
    <Sider
      width={siderWidth}
      className={`${styles.sider}${
        collapsed ? ` ${styles.siderCollapsed}` : ""
      }${isDark ? ` ${styles.siderDark}` : ""}${
        isSimpleExpanded ? ` ${styles.siderSimple}` : ""
      }`}
    >
      {collapsed ? (
        <nav className={styles.collapsedNav}>
          {collapsedNavItems.map((item) => {
            const isActive =
              item.key === "core.chat"
                ? isChatActive
                : selectedKey === item.key;
            return (
              <Tooltip
                key={item.key}
                title={item.label}
                placement="right"
                overlayInnerStyle={{
                  background: "rgba(0,0,0,0.75)",
                  color: "#fff",
                }}
              >
                <button
                  aria-label={
                    typeof item.label === "string" ? item.label : undefined
                  }
                  className={`${styles.collapsedNavItem} ${
                    isActive ? styles.collapsedNavItemActive : ""
                  }`}
                  onClick={() =>
                    item.href
                      ? window.open(item.href, "_blank", "noopener,noreferrer")
                      : navigate(item.path)
                  }
                >
                  {item.icon}
                </button>
              </Tooltip>
            );
          })}
        </nav>
      ) : isSimpleExpanded ? (
        <>
          {/* Simple mode: flat nav items + session list */}
          <div className={styles.agentScopedSection}>
            {/* Flat nav items (no groups) */}
            <div className={styles.simpleNavItems}>
              {simpleFlatNav.map((entry) => {
                const isInbox = entry.key === "core.inbox";
                const isActive = selectedKey === entry.key;
                return (
                  <button
                    key={entry.key}
                    className={`${styles.simpleNavItem} ${
                      isActive ? styles.simpleNavItemActive : ""
                    }`}
                    onClick={() =>
                      entry.href
                        ? window.open(
                            entry.href,
                            "_blank",
                            "noopener,noreferrer",
                          )
                        : navigate(entry.path)
                    }
                  >
                    {isInbox ? (
                      <span
                        style={{
                          position: "relative",
                          display: "inline-flex",
                        }}
                      >
                        {entry.icon ?? <SparkEmailLine size={16} />}
                        {hasInboxUnread && (
                          <span
                            style={{
                              position: "absolute",
                              top: -1,
                              right: -3,
                              width: 6,
                              height: 6,
                              borderRadius: "50%",
                              background: "var(--mp-accent)",
                            }}
                          />
                        )}
                      </span>
                    ) : (
                      entry.icon
                    )}
                    <span>{entry.label}</span>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Session list — fills remaining space */}
          {canUseAgents && (
            <SidebarSessionList
              onNewChat={handleNewChat}
              onSessionClick={handleSidebarSessionClick}
            />
          )}
        </>
      ) : (
        <>
          {/* Agent-scoped section: selector + Chat + Control + Workspace */}
          <div className={styles.agentScopedSection}>
            {canUseAgents && (
              <div className={styles.agentSelectorContainer}>
                <button
                  className={`${styles.stickyChatButton}${
                    isChatActive ? ` ${styles.stickyChatButtonActive}` : ""
                  }`}
                  onClick={() => navigate(chatPath)}
                >
                  <MessageSquareText size={16} />
                  <span>{t("nav.chat")}</span>
                </button>
              </div>
            )}
            <Slot name="sider.top" kind="fill" />
            <Menu
              mode="inline"
              selectedKeys={[selectedKey]}
              openKeys={openKeys}
              onClick={({ key }) => handleMenuClick(String(key), agentMenu)}
              items={agentMenuItems}
              theme={isDark ? "dark" : "light"}
              className={styles.sideMenu}
            />
          </div>

          {/* Global settings section */}
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            openKeys={openKeys}
            onClick={({ key }) => handleMenuClick(String(key), settingsMenu)}
            items={settingsMenuItems}
            theme={isDark ? "dark" : "light"}
            className={styles.sideMenu}
          />
          <Slot name="sider.bottom" kind="fill" />
        </>
      )}

    </Sider>
  );
}
