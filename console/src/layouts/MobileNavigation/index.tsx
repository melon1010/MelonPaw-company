import { Drawer } from "antd";
import {
  BriefcaseBusiness,
  FolderKanban,
  Inbox,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Menu,
  MessageSquareText,
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

  const primary = useMemo<NavItem[]>(
    () => [
      {
        id: "company.workbench",
        path: "/workbench",
        label: t("enterprise.nav.workbench"),
        icon: LayoutDashboard,
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
    ],
    [t],
  );

  const more = useMemo<NavItem[]>(
    () =>
      [
        {
          id: "core.chat",
          path: "/chat",
          label: t("nav.chat"),
          icon: MessageSquareText,
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
          id: "core.channels",
          path: "/channels",
          label: t("nav.channels"),
          icon: Radio,
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
          id: "core.models",
          path: "/models",
          label: t("nav.models"),
          icon: BriefcaseBusiness,
        },
        {
          id: "core.security",
          path: "/security",
          label: t("nav.security"),
          icon: Shield,
        },
      ].filter((item) => canAccessRoute(item.id, user)),
    [t, user],
  );

  const go = (path: string) => {
    setOpen(false);
    navigate(path);
  };

  return (
    <>
      <nav className={styles.mobileNav} aria-label={t("nav.menu", "Navigation")}>
        {primary
          .filter((item) => canAccessRoute(item.id, user))
          .map((item) => {
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
        title={t("enterprise.nav.mySpace", "My space")}
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
