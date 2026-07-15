import { Drawer } from "antd";
import {
  BarChart3,
  BriefcaseBusiness,
  Bug,
  CalendarClock,
  DatabaseBackup,
  FolderKanban,
  Globe2,
  HeartPulse,
  Inbox,
  Library,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Menu,
  MessageSquareText,
  Mic,
  Package,
  Radio,
  Shield,
  Sparkles,
  Users,
  Files,
  Bot,
  type LucideIcon,
} from "lucide-react";
import { useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "../../contexts/AuthContext";
import { canAccessRoute } from "../../auth/authorization";
import { authApi } from "../../api/modules/auth";
import { clearAuthToken } from "../../api/config";
import styles from "./index.module.less";

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: LucideIcon;
}

export default function MobileNavigation() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const { user, clearSession } = useAuth();
  const [open, setOpen] = useState(false);

  const { primary, more } = useMemo(() => {
    const primaryCandidates: NavItem[] = [
      {
        id: "company.workbench",
        path: "/workbench",
        label: t("enterprise.nav.workbench"),
        icon: LayoutDashboard,
      },
      {
        id: "core.chat",
        path: "/chat",
        label: t("nav.chat"),
        icon: MessageSquareText,
      },
      {
        id: "company.tasks",
        path: "/tasks",
        label: t("enterprise.nav.tasks"),
        icon: ListChecks,
      },
      {
        id: "company.projects",
        path: "/projects",
        label: t("enterprise.nav.projects"),
        icon: FolderKanban,
      },
    ];
    const accessiblePrimary = primaryCandidates.filter((item) =>
      canAccessRoute(item.id, user),
    );

    const moreCandidates: NavItem[] = [
      ...accessiblePrimary.slice(3),
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
        id: "core.channels",
        path: "/channels",
        label: t("nav.channels"),
        icon: Radio,
      },
      {
        id: "core.cron-jobs",
        path: "/cron-jobs",
        label: t("nav.cronJobs"),
        icon: CalendarClock,
      },
      {
        id: "core.heartbeat",
        path: "/heartbeat",
        label: t("nav.heartbeat"),
        icon: HeartPulse,
      },
      {
        id: "core.inbox",
        path: "/inbox",
        label: t("nav.inbox"),
        icon: Inbox,
      },
      {
        id: "company.members",
        path: "/members",
        label: t("enterprise.nav.members"),
        icon: Users,
      },
      {
        id: "core.agents",
        path: "/agents",
        label: t("nav.agents"),
        icon: Bot,
      },
      {
        id: "core.skill-pool",
        path: "/skill-pool",
        label: t("nav.skillPool", "Skill Pool"),
        icon: Library,
      },
      {
        id: "core.models",
        path: "/models",
        label: t("nav.models"),
        icon: BriefcaseBusiness,
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
        id: "core.backups",
        path: "/backups",
        label: t("nav.backups"),
        icon: DatabaseBackup,
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

    return {
      primary: accessiblePrimary.slice(0, 3),
      more: moreCandidates.filter((item) => canAccessRoute(item.id, user)),
    };
  }, [t, user]);

  const go = (path: string) => {
    setOpen(false);
    navigate(path);
  };

  return (
    <>
      <nav
        className={styles.mobileNav}
        aria-label={t("nav.menu", "Navigation")}
      >
        {primary.map((item) => {
          const Icon = item.icon;
          const active = location.pathname.startsWith(item.path);
          return (
            <button
              type="button"
              key={item.id}
              className={active ? styles.navItemActive : styles.navItem}
              onClick={() => go(item.path)}
            >
              <Icon size={20} />
              <span>{item.label}</span>
            </button>
          );
        })}
        <button
          type="button"
          className={open ? styles.navItemActive : styles.navItem}
          onClick={() => setOpen(true)}
        >
          <Menu size={20} />
          <span>{t("common.more", "More")}</span>
        </button>
      </nav>

      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        placement="bottom"
        height="min(76vh, 640px)"
        title={t("nav.menu", "Navigation")}
        className={styles.moreDrawer}
      >
        <div className={styles.drawerGrid}>
          {more.map((item) => {
            const Icon = item.icon;
            return (
              <button
                type="button"
                key={item.id}
                className={styles.drawerItem}
                onClick={() => go(item.path)}
              >
                <Icon size={19} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </div>
        <div className={styles.drawerFooter}>
          <div className={styles.identity}>
            <span>{user?.display_name || user?.username}</span>
            <small>{user?.roles?.[0] || ""}</small>
          </div>
          <button
            type="button"
            className={styles.logout}
            aria-label={t("login.logout")}
            onClick={() => {
              void authApi.logout().finally(() => {
                clearAuthToken();
                clearSession();
                window.location.href = "/login";
              });
            }}
          >
            <LogOut size={18} />
          </button>
        </div>
      </Drawer>
    </>
  );
}
