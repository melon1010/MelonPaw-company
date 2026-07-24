import React from "react";
import { useChatAnywhereSessionsState } from "@agentscope-ai/chat";
import { useLocation } from "react-router-dom";
import { useSessionListStore } from "../../../../stores/sessionListStore";
import { getSessionIdFromPath } from "../../../../utils/sessionRoute";
import styles from "./index.module.less";

interface ChatHeaderTitleProps {
  hideUntitled?: boolean;
}

const ChatHeaderTitle: React.FC<ChatHeaderTitleProps> = ({
  hideUntitled = false,
}) => {
  const location = useLocation();
  const { sessions, currentSessionId } = useChatAnywhereSessionsState();
  const sidebarSessions = useSessionListStore((state) => state.sessions);
  const routeSessionId = getSessionIdFromPath(location.pathname);
  const currentSession = sessions.find(
    (session) => session.id === currentSessionId,
  );
  const sidebarSession = sidebarSessions.find((session) =>
    [currentSessionId, routeSessionId].some(
      (sessionId) =>
        Boolean(sessionId) &&
        (session.id === sessionId ||
          session.realId === sessionId ||
          session.sessionId === sessionId),
    ),
  );
  const resolvedChatName = sidebarSession?.name || currentSession?.name || "";
  const chatName = resolvedChatName || "New Chat";

  if (hideUntitled && !resolvedChatName.trim()) return null;

  return <span className={styles.chatName}>{chatName}</span>;
};

export default ChatHeaderTitle;
