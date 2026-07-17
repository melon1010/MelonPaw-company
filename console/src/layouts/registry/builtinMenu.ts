/**
 * builtinMenu.ts — host's built-in sidebar menu entries as data.
 *
 * Importing this module self-registers all builtins into menuRegistry, so the
 * Sidebar's `useMenuItems()` snapshot returns them on first render. Plugins
 * register via `QwenPaw.menu.add(...)` which lands in the same registry, so
 * Sidebar treats core + plugin items uniformly.
 *
 * ── Naming convention ──────────────────────────────────────────────────────
 *  Group ids: `core.<name>-group` (e.g. core.control-group)
 *  Item ids:  `core.<key>`        (e.g. core.workspace)
 *  Plugin items use their own prefix (e.g. cloudpaw.a2a) — no clash possible.
 *
 * ── Sticky chat button carve-out ───────────────────────────────────────────
 *  `core.chat` is NOT in this data. The sticky chat button lives outside the
 *  antd <Menu> (rendered as a dedicated sticky action); see
 *  Sidebar.tsx. We don't model it as menu data because it has zero antd-Menu
 *  semantics in common with the rest of the sidebar entries.
 *
 * ── Order convention ───────────────────────────────────────────────────────
 *  Within each group, items use order = 10/20/30/… in their natural sequence
 *  so plugins can insert with order 15/25 without colliding.
 */
import {
  Activity,
  BarChart3,
  Bug,
  CalendarClock,
  Cpu,
  DatabaseBackup,
  Files,
  Gauge,
  Globe2,
  HeartPulse,
  History,
  Inbox,
  LayoutDashboard,
  Library,
  Mic,
  Network,
  Package,
  Radio,
  ScrollText,
  Settings2,
  Shield,
  Sparkles,
  Users,
  Wrench,
} from "lucide-react";
import i18next from "i18next";
import { menuRegistry } from "../../plugins/registry/store";
import type { MenuItem } from "../../plugins/registry/types";

/** Translate a nav key. Falls back to defaultValue when i18n hasn't loaded. */
const navLabel = (key: string, defaultValue?: string) => (): string =>
  i18next.t(key, defaultValue ?? key);

export const BUILTIN_MENU: MenuItem[] = [
  {
    id: "company.enterprise-group",
    location: "primary.agentScoped",
    label: navLabel("enterprise.nav.work", "Work"),
    isGroup: true,
    order: 1,
  },
  {
    id: "company.workbench",
    location: "primary.agentScoped",
    parentId: "company.enterprise-group",
    label: navLabel("enterprise.nav.workbench"),
    icon: LayoutDashboard,
    route: "company.workbench",
    order: 10,
  },
  // ── Agent-scoped (Sidebar Menu #1) ───────────────────────────────────────
  {
    id: "core.inbox",
    location: "primary.agentScoped",
    parentId: "company.enterprise-group",
    label: navLabel("nav.inbox"),
    icon: Inbox,
    route: "core.inbox",
    order: 20,
  },

  // control-group
  {
    id: "core.control-group",
    location: "primary.agentScoped",
    label: navLabel("enterprise.nav.mySpace", "My space"),
    isGroup: true,
    order: 20,
  },
  {
    id: "core.channels",
    location: "primary.agentScoped",
    parentId: "core.control-group",
    label: navLabel("nav.channels"),
    icon: Radio,
    route: "core.channels",
    order: 10,
  },
  {
    id: "core.workspace",
    location: "primary.agentScoped",
    parentId: "core.control-group",
    label: navLabel("nav.workspace"),
    icon: Files,
    route: "core.workspace",
    order: 20,
  },
  {
    id: "core.skills",
    location: "primary.agentScoped",
    parentId: "core.control-group",
    label: navLabel("nav.skills"),
    icon: Sparkles,
    route: "core.skills",
    order: 30,
  },
  {
    id: "core.cron-jobs",
    location: "primary.agentScoped",
    parentId: "core.control-group",
    label: navLabel("nav.cronJobs"),
    icon: CalendarClock,
    route: "core.cron-jobs",
    order: 40,
  },
  {
    id: "core.heartbeat",
    location: "primary.agentScoped",
    parentId: "core.control-group",
    label: navLabel("nav.heartbeat"),
    icon: HeartPulse,
    route: "core.heartbeat",
    order: 50,
  },

  // operations-group
  {
    id: "core.agent-group",
    location: "primary.agentScoped",
    label: navLabel("enterprise.nav.operations", "Operations"),
    isGroup: true,
    order: 30,
  },
  {
    id: "core.sessions",
    location: "primary.agentScoped",
    parentId: "core.agent-group",
    label: navLabel("nav.sessions"),
    icon: History,
    route: "core.sessions",
    order: 10,
  },
  {
    id: "core.tools",
    location: "primary.agentScoped",
    parentId: "core.agent-group",
    label: navLabel("nav.tools"),
    icon: Wrench,
    route: "core.tools",
    order: 20,
  },
  {
    id: "core.mcp",
    location: "primary.agentScoped",
    parentId: "core.agent-group",
    label: navLabel("nav.mcp"),
    icon: Network,
    route: "core.mcp",
    order: 30,
  },
  {
    id: "core.agent-config",
    location: "primary.agentScoped",
    parentId: "core.agent-group",
    label: navLabel("nav.agentConfig"),
    icon: Settings2,
    route: "core.agent-config",
    order: 40,
  },
  {
    id: "core.agent-stats",
    location: "primary.agentScoped",
    parentId: "core.agent-group",
    label: navLabel("nav.agentStats"),
    icon: Activity,
    route: "core.agent-stats",
    order: 50,
  },

  // ── Settings (Sidebar Menu #2) ───────────────────────────────────────────
  {
    id: "core.settings-group",
    location: "primary.settings",
    label: navLabel("enterprise.nav.administration", "Administration"),
    isGroup: true,
    order: 10,
  },
  {
    id: "company.members",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("enterprise.nav.members"),
    icon: Users,
    route: "company.members",
    order: 5,
  },
  {
    id: "company.roles",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("enterprise.nav.roles", "Roles & permissions"),
    icon: Shield,
    route: "company.roles",
    order: 10,
  },
  {
    id: "core.models",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.models"),
    icon: Cpu,
    route: "core.models",
    order: 20,
  },
  {
    id: "core.skill-pool",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.skillPool", "Skill Pool"),
    icon: Library,
    route: "core.skill-pool",
    order: 30,
  },
  {
    id: "core.environments",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.environments"),
    icon: Globe2,
    route: "core.environments",
    order: 50,
  },
  {
    id: "core.security",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.security"),
    icon: Shield,
    route: "core.security",
    order: 60,
  },
  {
    id: "core.token-usage",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.tokenUsage"),
    icon: BarChart3,
    route: "core.token-usage",
    order: 70,
  },
  {
    id: "core.management-audit",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("enterprise.nav.audit", "Management audit"),
    icon: ScrollText,
    route: "core.management-audit",
    order: 75,
  },
  {
    id: "core.backups",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.backups"),
    icon: DatabaseBackup,
    route: "core.backups",
    order: 80,
  },
  {
    id: "core.quotas",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("enterprise.nav.quotas", "Quotas"),
    icon: Gauge,
    route: "core.quotas",
    order: 85,
  },
  {
    id: "core.voice-transcription",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.voiceTranscription"),
    icon: Mic,
    route: "core.voice-transcription",
    order: 90,
  },
  {
    id: "core.debug",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.debug", "Debug"),
    icon: Bug,
    route: "core.debug",
    order: 100,
  },
  {
    id: "core.plugin-manager",
    location: "primary.settings",
    parentId: "core.settings-group",
    label: navLabel("nav.pluginManager", "Plugin Manager"),
    icon: Package,
    route: "core.plugin-manager",
    order: 110,
  },
];

// Self-register at module load. main.tsx imports this file as a side-effect.
menuRegistry.addBuiltin(BUILTIN_MENU);
