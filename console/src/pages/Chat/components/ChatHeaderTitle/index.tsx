import React, { useState } from "react";
import { Dropdown } from "antd";
import { useChatAnywhereSessionsState } from "@agentscope-ai/chat";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronDown, MessageSquareText } from "lucide-react";
import { useSessionListStore } from "../../../../stores/sessionListStore";
import { getSessionIdFromPath } from "../../../../utils/sessionRoute";
import styles from "./index.module.less";

interface ChatHeaderTitleProps {
  hideUntitled?: boolean;
}

const ChatHeaderTitle: React.FC<ChatHeaderTitleProps> = ({
  hideUntitled = false,
}) => {
  const { t } = useTranslation();
  const location = useLocation();
  const { sessions, currentSessionId, setCurrentSessionId } =
    useChatAnywhereSessionsState();
  const sidebarSessions = useSessionListStore((state) => state.sessions);
  const routeSessionId = getSessionIdFromPath(location.pathname);
  const currentSession = sessions.find((s) => s.id === currentSessionId);
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

  const [open, setOpen] = useState(false);

  const handleSessionClick = (sessionId: string) => {
    setCurrentSessionId(sessionId);
    setOpen(false);
  };

  const menuItems = sessions.map((session) => ({
    key: session.id,
    label: (
      <div className={styles.menuItem}>
        <span className={styles.menuItemName}>
          {session.name || "New Chat"}
        </span>
        {session.id === currentSessionId && (
          <span className={styles.menuItemActive}>✓</span>
        )}
      </div>
    ),
    onClick: () => handleSessionClick(session.id),
  }));

  const titleContent = <span className={styles.chatName}>{chatName}</span>;

  if (hideUntitled && !resolvedChatName.trim()) return null;

  if (sessions.length === 0) {
    return <span className={styles.singleTitle}>{titleContent}</span>;
  }

  const dropdownLabel = t("chat.recentConversations", "Recent chats");

  return (
    <Dropdown
      menu={{ items: menuItems }}
      open={open}
      onOpenChange={setOpen}
      trigger={["click"]}
      placement="bottomLeft"
    >
      <button
        type="button"
        className={styles.trigger}
        aria-label={dropdownLabel}
        aria-expanded={open}
        aria-haspopup="menu"
        title={dropdownLabel}
      >
        {titleContent}
        <MessageSquareText
          className={styles.mobileConversationIcon}
          size={17}
          strokeWidth={1.8}
          aria-hidden
        />
        <ChevronDown
          className={styles.dropdownChevron}
          size={15}
          strokeWidth={1.9}
          aria-hidden
        />
      </button>
    </Dropdown>
  );
};

export default ChatHeaderTitle;
