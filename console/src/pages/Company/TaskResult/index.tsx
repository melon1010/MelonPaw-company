import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ComponentProps,
} from "react";
import { Button, Empty, Result, Space, Spin, Tag } from "antd";
import { ArrowLeft, MessageSquareText, RefreshCw } from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { IAgentScopeRuntimeWebUIMessage } from "@agentscope-ai/chat";
// The provider is not exported by the package root, but is the same context
// used by the existing conversation response card and tool renderers.
import { ChatAnywhereOptionsContextProvider } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/Context/ChatAnywhereOptionsContext";
import api, { type ChatHistory, type ChatSpec } from "../../../api";
import { PageHeader } from "../../../components/PageHeader";
import { useTheme } from "../../../contexts/ThemeContext";
import { usePlugins } from "../../../plugins/PluginContext";
import { withGenericFallback } from "../../../components/Chat/ToolCards/adapters/v1Adapter";
import { HostRequestCard, HostResponseCard } from "../../Chat/HostBubbles";
import { convertMessages } from "../../Chat/sessionApi";
import styles from "../company.module.less";

type TaskResultCardOptions = ComponentProps<
  typeof ChatAnywhereOptionsContextProvider
>["options"];

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function TaskResultPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { taskId = "" } = useParams();
  const { isDark } = useTheme();
  const { toolRenderConfig } = usePlugins();
  const [history, setHistory] = useState<ChatHistory | null>(null);
  const [task, setTask] = useState<ChatSpec | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    setError(null);
    try {
      const [historyResult, listResult] = await Promise.all([
        api.getChat(taskId),
        api.listChats(),
      ]);
      setHistory(historyResult);
      setTask(listResult.find((item) => item.id === taskId) || null);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t("enterprise.tasks.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [taskId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const messages = useMemo(
    () => convertMessages(history?.messages || []),
    [history?.messages],
  );

  const cardOptions = useMemo(
    () =>
      ({
        welcome: { nick: "MelonPaw-Company", avatar: "/online.svg" },
        theme: {
          darkMode: isDark,
          colorPrimary: "#1677ff",
          prefix: "qwenpaw",
        },
        request: {},
        response: {},
        customToolRenderConfig: withGenericFallback(toolRenderConfig),
      }) as unknown as TaskResultCardOptions,
    [isDark, toolRenderConfig],
  );

  const renderMessage = (message: IAgentScopeRuntimeWebUIMessage, index: number) => {
    const card = message.cards?.[0];
    if (!card?.data) return null;
    return (
      <div className={styles.messageItem} key={message.id || index}>
        {message.role === "user" ? (
          <HostRequestCard data={card.data as Record<string, unknown>} />
        ) : (
          <HostResponseCard
            data={card.data as Record<string, unknown>}
            isLast={index === messages.length - 1}
          />
        )}
      </div>
    );
  };

  return (
    <div className={styles.page}>
      <PageHeader
        parent={t("enterprise.tasks.title")}
        current={task?.name || t("enterprise.tasks.result")}
        extra={
          <Space>
            <Button icon={<ArrowLeft size={15} />} onClick={() => navigate("/tasks")}>
              {t("common.back")}
            </Button>
            <Button icon={<RefreshCw size={15} />} onClick={() => void load()}>
              {t("common.refresh")}
            </Button>
            {taskId && (
              <Button
                type="primary"
                icon={<MessageSquareText size={16} />}
                onClick={() => navigate(`/chat/${encodeURIComponent(taskId)}`)}
              >
                {t("enterprise.tasks.conversation")}
              </Button>
            )}
          </Space>
        }
      />
      <div className={styles.body}>
        {loading ? (
          <Spin style={{ display: "block", margin: "15vh auto" }} />
        ) : error ? (
          <Result
            status="error"
            title={t("enterprise.tasks.resultLoadFailed")}
            subTitle={error}
            extra={<Button onClick={() => void load()}>{t("common.refresh")}</Button>}
          />
        ) : (
          <div className={styles.resultShell}>
            <div className={styles.resultMeta}>
              <Tag color={task?.status === "running" ? "processing" : "success"}>
                {task?.status === "running"
                  ? t("enterprise.tasks.running")
                  : t("enterprise.tasks.completed")}
              </Tag>
              <span>{t("enterprise.tasks.channel")}: {task?.channel || "console"}</span>
              <span>{t("enterprise.tasks.updatedAt")}: {formatTime(task?.updated_at)}</span>
              <span>ID: {taskId}</span>
            </div>
            {messages.length > 0 ? (
              <ChatAnywhereOptionsContextProvider options={cardOptions}>
                <div className={styles.messageList}>{messages.map(renderMessage)}</div>
              </ChatAnywhereOptionsContextProvider>
            ) : (
              <Empty description={t("enterprise.tasks.noResult")} />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
