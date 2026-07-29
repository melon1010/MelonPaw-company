import { useEffect, useMemo, useState } from "react";
import api from "../api";
import { useAgentStore } from "../stores/agentStore";

/**
 * Resolves write access for configuration shared by skills, MCP and other
 * Agent-scoped settings. A personal workspace belongs to the signed-in user;
 * project workspaces use the server-side project role decision.
 */
export function useAgentConfigurationAccess() {
  const { selectedAgent, agents } = useAgentStore();
  const selectedAgentSummary = useMemo(
    () => agents.find((agent) => agent.id === selectedAgent),
    [agents, selectedAgent],
  );
  const isPersonalScope =
    selectedAgent === "default" ||
    selectedAgentSummary?.scope === "PERSONAL" ||
    (!selectedAgentSummary && selectedAgent.startsWith("personal-"));
  const [resolvedCapability, setResolvedCapability] = useState({
    agentId: selectedAgent,
    canManage: isPersonalScope,
  });
  const canManageConfiguration =
    isPersonalScope ||
    (resolvedCapability.agentId === selectedAgent &&
      resolvedCapability.canManage);

  useEffect(() => {
    let cancelled = false;

    if (isPersonalScope) {
      setResolvedCapability({ agentId: selectedAgent, canManage: true });
      return () => undefined;
    }

    setResolvedCapability({ agentId: selectedAgent, canManage: false });

    void api
      .getAgentCapabilities()
      .then((capabilities) => {
        if (!cancelled) {
          setResolvedCapability({
            agentId: selectedAgent,
            canManage: capabilities.can_manage === true,
          });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setResolvedCapability({ agentId: selectedAgent, canManage: false });
          console.warn("Failed to load Agent capabilities:", error);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [isPersonalScope, selectedAgent]);

  return { canManageConfiguration, isPersonalScope };
}
