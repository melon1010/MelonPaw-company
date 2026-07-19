import React from "react";

import { IconButton } from "@agentscope-ai/design";
import { SparkHistoryLine, SparkNewChatFill } from "@agentscope-ai/icons";
import {
  ExpandAltOutlined,
  CompressOutlined,
  MoreOutlined,
  ShareAltOutlined,
} from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import { Dropdown, Flex, Tooltip } from "antd";
import type { MenuProps } from "antd";
import { useCreateNewSession } from "../../hooks/useCreateNewSession";
import { useIsMobile } from "../../../../hooks/useIsMobile";
import styles from "./index.module.less";

interface ChatActionGroupProps {
  /** Callback to toggle the right-side history panel */
  onToggleHistory?: () => void;
  /** Whether the history panel is currently visible */
  historyOpen?: boolean;
  isWideMode?: boolean;
  onToggleWideMode?: () => void;
  onShare?: () => void;
}

const ChatActionGroup: React.FC<ChatActionGroupProps> = ({
  onToggleHistory,
  historyOpen = false,
  isWideMode = false,
  onToggleWideMode,
  onShare,
}) => {
  const { t } = useTranslation();

  const createNewSession = useCreateNewSession();

  // Compact mode follows the viewport: collapse secondary actions only on
  // mobile. This saves space on phones while keeping actions visible on desktop.
  const isCompact = useIsMobile();

  // Build the compact menu from actions that do not need to remain visible.
  const moreItems: MenuProps["items"] = [];
  if (onShare) {
    moreItems.push({
      key: "share",
      icon: <ShareAltOutlined />,
      label: t("chat.shareConversationTooltip"),
      onClick: onShare,
    });
  }
  if (onToggleHistory) {
    moreItems.push({
      key: "history",
      icon: <SparkHistoryLine />,
      label: (
        <div style={{ textAlign: "center" }}>
          {t("chat.chatHistoryTooltip")}
        </div>
      ),
      onClick: () => onToggleHistory(),
    });
  }
  if (onToggleWideMode) {
    moreItems.push({
      key: "wideMode",
      icon: isWideMode ? <CompressOutlined /> : <ExpandAltOutlined />,
      label: (
        <div style={{ textAlign: "center" }}>
          {isWideMode ? t("chat.normalModeTooltip") : t("chat.wideModeTooltip")}
        </div>
      ),
      onClick: () => onToggleWideMode(),
    });
  }

  return (
    <Flex className={styles.actionGroup} gap={2} align="center">
      {/* Essential actions always visible */}
      <Tooltip title={t("chat.newChatTooltip")} mouseEnterDelay={0.5}>
        <IconButton
          bordered={false}
          icon={<SparkNewChatFill />}
          aria-label={t("chat.newChatTooltip")}
          onClick={createNewSession}
        />
      </Tooltip>

      {!isCompact && onShare && (
        <Tooltip
          title={t("chat.shareConversationTooltip")}
          mouseEnterDelay={0.5}
        >
          <IconButton
            bordered={false}
            icon={<ShareAltOutlined />}
            aria-label={t("chat.shareConversationTooltip")}
            onClick={onShare}
          />
        </Tooltip>
      )}

      {/* History + WideMode: inline when NOT compact */}
      {!isCompact && onToggleHistory && (
        <Tooltip title={t("chat.chatHistoryTooltip")} mouseEnterDelay={0.5}>
          <IconButton
            bordered={false}
            icon={<SparkHistoryLine />}
            aria-label={t("chat.chatHistoryTooltip")}
            style={
              historyOpen
                ? { color: "var(--color-primary, var(--mp-primary))" }
                : undefined
            }
            onClick={onToggleHistory}
          />
        </Tooltip>
      )}
      {!isCompact && onToggleWideMode && (
        <Tooltip
          title={
            isWideMode ? t("chat.normalModeTooltip") : t("chat.wideModeTooltip")
          }
          mouseEnterDelay={0.5}
        >
          <IconButton
            bordered={false}
            icon={isWideMode ? <CompressOutlined /> : <ExpandAltOutlined />}
            aria-label={
              isWideMode
                ? t("chat.normalModeTooltip")
                : t("chat.wideModeTooltip")
            }
            onClick={onToggleWideMode}
          />
        </Tooltip>
      )}

      {/* Compact mode: collapse History/WideMode into more dropdown */}
      {isCompact && moreItems.length > 0 && (
        <Dropdown
          menu={{ items: moreItems }}
          trigger={["click"]}
          placement="bottomRight"
        >
          <IconButton
            bordered={false}
            icon={<MoreOutlined />}
            aria-label={t("common.more", "More")}
          />
        </Dropdown>
      )}
    </Flex>
  );
};

export default ChatActionGroup;
