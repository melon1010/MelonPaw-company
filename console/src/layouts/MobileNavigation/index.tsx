import { Drawer } from "antd";
import {
  Activity,
  BarChart3,
  BriefcaseBusiness,
  Bug,
  CalendarClock,
  DatabaseBackup,
  Files,
  Folder,
  Gauge,
  Globe2,
  HeartPulse,
  History,
  Inbox,
  LayoutDashboard,
  Library,
  ArrowLeft,
  Menu,
  Mic,
  Network,
  Package,
  Radio,
  ScrollText,
  Settings2,
  Shield,
  Sparkles,
  SquarePen,
  Users,
  Wrench,
  type LucideIcon,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../contexts/AuthContext";
import { canAccessRoute, hasPermission } from "../../auth/authorization";
import sessionApi from "../../pages/Chat/sessionApi";
import { useAgentStore } from "../../stores/agentStore";
import { buildSessionPath } from "../../utils/sessionRoute";
import { AccountControl } from "../AccountControls";
import SidebarSessionList from "../SidebarSessionList";
import styles from "./index.module.less";

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: LucideIcon;
}

const DRAWER_NAV_ID = "mobile-navigation-drawer";

export default function MobileNavigation() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const selectedAgent = useAgentStore((state) => state.selectedAgent);
  const canUseAgents = hasPermission(user, "agents:use");
  const [open, setOpen] = useState(false);
  const [settingsMode, setSettingsMode] = useState(false);

  const { primary, workspace, administration } = useMemo(() => {
    const primaryCandidates: NavItem[] = [
      {
        id: "core.chat",
        path: "/chat",
        label: t("chat.newChatTooltip", "New chat"),
        icon: SquarePen,
      },
      {
        id: "company.projects",
        path: "/projects",
        label: t("enterprise.nav.projects", "Project Spaces"),
        icon: Folder,
      },
      {
        id: "core.cron-jobs",
        path: "/cron-jobs",
        label: t("nav.cronJobs"),
        icon: CalendarClock,
      },
    ];
    const workspaceCandidates: NavItem[] = [
      {
        id: "company.workbench",
        path: "/workbench",
        label: t("enterprise.nav.workbench"),
        icon: LayoutDashboard,
      },
      {
        id: "core.inbox",
        path: "/inbox",
        label: t("nav.inbox"),
        icon: Inbox,
      },
      {
        id: "core.channels",
        path: "/channels",
        label: t("nav.channels"),
        icon: Radio,
      },
      {
        id: "core.workspace",
        path: "/workspace",
        label: t("nav.workspace"),
        icon: Files,
      },
      {
        id: "core.skills",
        path: "/skills",
        label: t("nav.skills"),
        icon: Sparkles,
      },
      {
        id: "core.heartbeat",
        path: "/heartbeat",
        label: t("nav.heartbeat"),
        icon: HeartPulse,
      },
      {
        id: "core.sessions",
        path: "/sessions",
        label: t("nav.sessions"),
        icon: History,
      },
      {
        id: "core.tools",
        path: "/tools",
        label: t("nav.tools"),
        icon: Wrench,
      },
      {
        id: "core.mcp",
        path: "/mcp",
        label: t("nav.mcp"),
        icon: Network,
      },
      {
        id: "core.agent-config",
        path: "/agent-config",
        label: t("nav.agentConfig"),
        icon: Settings2,
      },
      {
        id: "core.agent-stats",
        path: "/agent-stats",
        label: t("nav.agentStats"),
        icon: Activity,
      },
    ];
    const administrationCandidates: NavItem[] = [
      {
        id: "company.members",
        path: "/members",
        label: t("enterprise.nav.members"),
        icon: Users,
      },
      {
        id: "company.roles",
        path: "/roles",
        label: t("enterprise.nav.roles", "Roles & permissions"),
        icon: Shield,
      },
      {
        id: "core.models",
        path: "/models",
        label: t("nav.models"),
        icon: BriefcaseBusiness,
      },
      {
        id: "core.skill-pool",
        path: "/skill-pool",
        label: t("nav.skillPool", "Skill Pool"),
        icon: Library,
      },
      {
        id: "core.environments",
        path: "/environments",
        label: t("nav.environments"),
        icon: Globe2,
      },
      {
        id: "core.security",
        path: "/security",
        label: t("nav.security"),
        icon: Shield,
      },
      {
        id: "core.token-usage",
        path: "/token-usage",
        label: t("nav.tokenUsage"),
        icon: BarChart3,
      },
      {
        id: "core.management-audit",
        path: "/management-audit",
        label: t("enterprise.nav.audit", "Management audit"),
        icon: ScrollText,
      },
      {
        id: "core.backups",
        path: "/backups",
        label: t("nav.backups"),
        icon: DatabaseBackup,
      },
      {
        id: "core.quotas",
        path: "/quotas",
        label: t("enterprise.nav.quotas", "Quotas"),
        icon: Gauge,
      },
      {
        id: "core.voice-transcription",
        path: "/voice-transcription",
        label: t("nav.voiceTranscription"),
        icon: Mic,
      },
      {
        id: "core.debug",
        path: "/debug",
        label: t("nav.debug", "Debug"),
        icon: Bug,
      },
      {
        id: "core.plugin-manager",
        path: "/plugin-manager",
        label: t("nav.pluginManager", "Plugin Manager"),
        icon: Package,
      },
    ];
    const accessible = (items: NavItem[]) =>
      items.filter((item) => canAccessRoute(item.id, user));

    return {
      primary: accessible(primaryCandidates),
      workspace: accessible(workspaceCandidates),
      administration: accessible(administrationCandidates),
    };
  }, [t, user]);

  const routeUsesSettingsNavigation = useMemo(
    () =>
      [...workspace, ...administration].some(
        (item) =>
          location.pathname === item.path ||
          location.pathname.startsWith(`${item.path}/`),
      ),
    [administration, location.pathname, workspace],
  );

  useEffect(() => {
    setOpen(false);
    setSettingsMode(routeUsesSettingsNavigation);
  }, [location.pathname, routeUsesSettingsNavigation]);

  const closeDrawer = () => {
    setOpen(false);
  };

  const go = (path: string) => {
    closeDrawer();
    navigate(path);
  };

  const handleNewChat = () => {
    closeDrawer();
    if (location.pathname.startsWith("/chat")) {
      window.dispatchEvent(new CustomEvent("qwenpaw:sidebar-new-chat"));
      return;
    }

    sessionStorage.setItem("qwenpaw_pending_new_chat", "1");
    navigate("/chat");
  };

  const handleSidebarSessionClick = useCallback(
    (sessionId: string) => {
      setOpen(false);
      setSettingsMode(false);
      navigate(buildSessionPath(sessionApi.getEffectiveSessionId(sessionId)));
    },
    [navigate],
  );

  const renderSection = (label: string, items: NavItem[]) => {
    if (items.length === 0) return null;
    return (
      <section className={styles.drawerSection}>
        <div className={styles.drawerSectionLabel}>{label}</div>
        {items.map((item) => {
          const Icon = item.icon;
          const active =
            location.pathname === item.path ||
            location.pathname.startsWith(`${item.path}/`);
          return (
            <button
              type="button"
              key={item.id}
              className={active ? styles.drawerItemActive : styles.drawerItem}
              aria-current={active ? "page" : undefined}
              onClick={
                item.id === "core.chat" ? handleNewChat : () => go(item.path)
              }
            >
              <span className={styles.drawerItemIcon}>
                <Icon size={19} aria-hidden />
              </span>
              <span>{item.label}</span>
            </button>
          );
        })}
      </section>
    );
  };

  return (
    <>
      <button
        type="button"
        className={styles.menuTrigger}
        aria-label={t("nav.menu", "Navigation")}
        aria-controls={DRAWER_NAV_ID}
        aria-expanded={open}
        onClick={() => {
          setSettingsMode(routeUsesSettingsNavigation);
          setOpen(true);
        }}
      >
        <Menu size={21} strokeWidth={1.9} aria-hidden />
      </button>

      <Drawer
        open={open}
        onClose={closeDrawer}
        placement="left"
        closable={false}
        maskClosable
        destroyOnHidden
        width="min(86vw, 312px)"
        title={
          <span className={styles.drawerBrand}>
            <img src="/company-mark.svg" alt="" />
            <strong>MelonPaw</strong>
          </span>
        }
        rootClassName={styles.mobileDrawerRoot}
      >
        <div className={styles.drawerShell}>
          <nav
            id={DRAWER_NAV_ID}
            className={`${styles.drawerNavigation} ${
              settingsMode ? "" : styles.drawerNavigationApp
            }`}
            aria-label={t("nav.menu", "Navigation")}
          >
            {settingsMode ? (
              <>
                <button
                  type="button"
                  className={styles.settingsBack}
                  onClick={() => setSettingsMode(false)}
                >
                  <ArrowLeft size={18} aria-hidden />
                  <span>
                    {t("sidebar.settings.returnApp", "Return to application")}
                  </span>
                </button>
                {renderSection(
                  t("enterprise.nav.operations", "Operations"),
                  workspace,
                )}
                {renderSection(
                  t("enterprise.nav.administration", "Administration"),
                  administration,
                )}
              </>
            ) : (
              <>
                {renderSection(t("enterprise.nav.work", "Work"), primary)}
                {canUseAgents && (
                  <section className={styles.drawerHistory}>
                    <div
                      className={`${styles.drawerSectionLabel} ${styles.drawerHistoryLabel}`}
                    >
                      {t("chat.recentConversations", "Recent chats")}
                    </div>
                    <SidebarSessionList
                      key={selectedAgent}
                      historyOnly
                      onSessionClick={handleSidebarSessionClick}
                    />
                  </section>
                )}
              </>
            )}
          </nav>

          <div className={styles.drawerFooter}>
            <div className={styles.drawerAccount}>
              <AccountControl
                variant="sidebar"
                mobile
                placement="topLeft"
                onOpenSettings={
                  settingsMode ? undefined : () => setSettingsMode(true)
                }
              />
            </div>
          </div>
        </div>
      </Drawer>
    </>
  );
}
