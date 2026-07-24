import { useCallback, useEffect, useMemo, useState } from "react";
import { Dropdown, type DropdownProps, type MenuProps } from "antd";
import { useTranslation } from "react-i18next";
import {
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  FolderKanban,
  UserRound,
} from "lucide-react";
import { agentsApi } from "../../api/modules/agents";
import type { AgentSummary } from "../../api/types/agents";
import { useAgentStore } from "../../stores/agentStore";
import styles from "./index.module.less";

type AgentScope = NonNullable<AgentSummary["scope"]>;

const SCOPE_ORDER: AgentScope[] = ["PERSONAL", "PROJECT", "SERVICE"];
const ACCOUNT_SUBMENU_ALIGN = {
  points: ["tl", "tr"],
  offset: [8, 0],
  overflow: { adjustX: false, adjustY: true },
} satisfies NonNullable<DropdownProps["align"]>;
let agentsRequest: Promise<AgentSummary[]> | null = null;

function listAccessibleAgents(): Promise<AgentSummary[]> {
  if (!agentsRequest) {
    agentsRequest = agentsApi
      .listAgents()
      .then((response) => response.agents || [])
      .finally(() => {
        agentsRequest = null;
      });
  }
  return agentsRequest;
}

function resolveScope(agent: Pick<AgentSummary, "id" | "scope">): AgentScope {
  if (agent.scope) return agent.scope;
  if (agent.id.startsWith("project-")) return "PROJECT";
  if (agent.id.startsWith("personal-")) return "PERSONAL";
  return "SERVICE";
}

function ScopeIcon({ scope, size = 16 }: { scope: AgentScope; size?: number }) {
  if (scope === "PROJECT") {
    return <FolderKanban size={size} aria-hidden="true" />;
  }
  if (scope === "PERSONAL") {
    return <UserRound size={size} aria-hidden="true" />;
  }
  return <Bot size={size} aria-hidden="true" />;
}

interface AgentScopeSwitcherProps {
  variant?: "header" | "sidebar" | "menu" | "panel";
  collapsed?: boolean;
  mobile?: boolean;
  onSelect?: () => void;
}

export default function AgentScopeSwitcher({
  variant = "header",
  collapsed = false,
  mobile = false,
  onSelect,
}: AgentScopeSwitcherProps) {
  const { t } = useTranslation();
  const selectedAgent = useAgentStore((state) => state.selectedAgent);
  const agents = useAgentStore((state) => state.agents);
  const setAgents = useAgentStore((state) => state.setAgents);
  const setSelectedAgent = useAgentStore((state) => state.setSelectedAgent);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const loadAgents = useCallback(async () => {
    setLoading(true);
    setLoadFailed(false);
    try {
      const accessibleAgents = (await listAccessibleAgents()).filter(
        (agent) => agent.id !== "default",
      );
      const activeAgents = accessibleAgents.filter((agent) => agent.enabled);
      setAgents(accessibleAgents);

      const currentAgentId = useAgentStore.getState().selectedAgent;
      if (!activeAgents.some((agent) => agent.id === currentAgentId)) {
        const fallback =
          activeAgents.find((agent) => resolveScope(agent) === "PERSONAL") ||
          activeAgents[0];
        if (fallback) setSelectedAgent(fallback.id);
      }
    } catch {
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [setAgents, setSelectedAgent]);

  const selectedSummary = useMemo(
    () => agents.find((agent) => agent.id === selectedAgent),
    [agents, selectedAgent],
  );

  useEffect(() => {
    void loadAgents();
  }, [loadAgents]);

  const selectedScope = resolveScope({
    id: selectedAgent,
    scope: selectedSummary?.scope,
  });
  const scopeLabel = useCallback(
    (scope: AgentScope) => {
      if (scope === "PROJECT") return t("chat.projectScope");
      if (scope === "PERSONAL") return t("chat.personalScope");
      return t("chat.agentScope");
    },
    [t],
  );
  const selectedName =
    selectedSummary?.name?.trim() || scopeLabel(selectedScope);

  const menuItems = useMemo<MenuProps["items"]>(() => {
    const groups = SCOPE_ORDER.flatMap((scope) => {
      const scopedAgents = agents.filter(
        (agent) =>
          agent.id !== "default" &&
          agent.enabled &&
          resolveScope(agent) === scope,
      );
      if (scopedAgents.length === 0) return [];
      return [
        {
          type: "group" as const,
          key: `scope:${scope}`,
          label: scopeLabel(scope),
          children: scopedAgents.map((agent) => ({
            key: agent.id,
            icon: (
              <span
                className={`${styles.menuIcon} ${styles[scope.toLowerCase()]}`}
              >
                <ScopeIcon scope={scope} size={15} />
              </span>
            ),
            label: (
              <span className={styles.menuItem}>
                <strong
                  className={styles.menuItemName}
                  title={agent.name?.trim() || scopeLabel(scope)}
                >
                  {agent.name?.trim() || scopeLabel(scope)}
                </strong>
                {agent.id === selectedAgent && (
                  <Check
                    className={styles.selectedMark}
                    size={15}
                    aria-hidden
                  />
                )}
              </span>
            ),
          })),
        },
      ];
    });

    if (groups.length > 0) return groups;
    return [
      {
        key: "scope-state",
        disabled: true,
        label: loadFailed
          ? t("agent.loadFailed")
          : loading
          ? t("common.loading")
          : t("chat.scopeEmpty"),
      },
    ];
  }, [agents, loadFailed, loading, scopeLabel, selectedAgent, t]);

  if (variant === "panel") {
    const visibleGroups = SCOPE_ORDER.map((scope) => ({
      scope,
      agents: agents.filter(
        (agent) =>
          agent.id !== "default" &&
          agent.enabled &&
          resolveScope(agent) === scope,
      ),
    })).filter((group) => group.agents.length > 0);

    return (
      <div className={styles.scopePanel} aria-busy={loading}>
        {visibleGroups.map((group) => (
          <section key={group.scope} className={styles.scopePanelGroup}>
            <div className={styles.scopePanelLabel}>
              {scopeLabel(group.scope)}
            </div>
            {group.agents.map((agent) => {
              const selected = agent.id === selectedAgent;
              return (
                <button
                  key={agent.id}
                  type="button"
                  className={
                    selected
                      ? styles.scopePanelItemSelected
                      : styles.scopePanelItem
                  }
                  onClick={() => {
                    if (!selected) setSelectedAgent(agent.id);
                    onSelect?.();
                  }}
                >
                  <span
                    className={`${styles.menuIcon} ${
                      styles[group.scope.toLowerCase()]
                    }`}
                  >
                    <ScopeIcon scope={group.scope} size={16} />
                  </span>
                  <span className={styles.scopePanelName}>
                    {agent.name?.trim() || scopeLabel(group.scope)}
                  </span>
                  {selected ? <Check size={16} aria-hidden /> : null}
                </button>
              );
            })}
          </section>
        ))}
        {visibleGroups.length === 0 ? (
          <div className={styles.scopePanelState}>
            {loadFailed
              ? t("agent.loadFailed")
              : loading
              ? t("common.loading")
              : t("chat.scopeEmpty")}
          </div>
        ) : null}
      </div>
    );
  }

  const trigger =
    variant === "menu" ? (
      <span
        className={styles.accountMenuTrigger}
        onClick={(event) => event.stopPropagation()}
      >
        <span>{t("chat.switchScope", "Switch workspace")}</span>
        <ChevronRight size={14} aria-hidden />
      </span>
    ) : (
      <button
        type="button"
        className={`${styles.trigger} ${
          variant === "sidebar" ? styles.sidebarTrigger : styles.headerTrigger
        } ${collapsed ? styles.collapsedTrigger : ""} ${
          styles[selectedScope.toLowerCase()]
        }`}
        aria-label={`${t("chat.switchScope")}: ${scopeLabel(
          selectedScope,
        )} ${selectedName}`}
        aria-busy={loading}
      >
        <span className={styles.triggerIcon}>
          <ScopeIcon scope={selectedScope} size={14} />
        </span>
        {!collapsed ? (
          <>
            <span className={styles.triggerCopy}>
              <strong title={selectedName}>{selectedName}</strong>
            </span>
            <ChevronDown className={styles.chevron} size={14} aria-hidden />
          </>
        ) : null}
      </button>
    );

  return (
    <Dropdown
      trigger={variant === "menu" && !mobile ? ["hover", "click"] : ["click"]}
      placement={
        mobile ? "topLeft" : variant === "sidebar" ? "topLeft" : "bottomLeft"
      }
      align={variant === "menu" && !mobile ? ACCOUNT_SUBMENU_ALIGN : undefined}
      overlayClassName={`${styles.scopeMenuPopup} ${
        variant === "menu" ? styles.accountScopeMenuPopup : ""
      }`}
      menu={{
        items: menuItems,
        selectable: true,
        selectedKeys: [selectedAgent],
        onClick: ({ key }) => {
          const agentId = String(key);
          if (agentId !== selectedAgent) setSelectedAgent(agentId);
        },
      }}
      onOpenChange={(open) => {
        if (open) void loadAgents();
      }}
    >
      {trigger}
    </Dropdown>
  );
}
