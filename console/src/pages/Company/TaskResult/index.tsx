import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ComponentProps,
} from "react";
import { Button, Empty, Result, Space, Spin, Tag, Timeline } from "antd";
import {
  ArrowLeft,
  MessageSquareText,
  RefreshCw,
  RotateCcw,
} from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { IAgentScopeRuntimeWebUIMessage } from "@agentscope-ai/chat";
// The provider is not exported by the package root, but is the same context
// used by the existing conversation response card and tool renderers.
import { ChatAnywhereOptionsContextProvider } from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/Context/ChatAnywhereOptionsContext";
import api, {
  type ChatHistory,
  type EnterpriseTask,
  type EnterpriseTaskEvent,
  type EnterpriseTaskRun,
} from "../../../api";
import { PageHeader, SectionHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import { useTheme } from "../../../contexts/ThemeContext";
import { usePlugins } from "../../../plugins/PluginContext";
import { withGenericFallback } from "../../../components/Chat/ToolCards/adapters/v1Adapter";
import { HostRequestCard, HostResponseCard } from "../../Chat/HostBubbles";
import { convertMessages } from "../../Chat/sessionApi";
import { taskStatusColor } from "../taskPresentation";
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
  const { message } = useAppMessage();
  const [history, setHistory] = useState<ChatHistory | null>(null);
  const [task, setTask] = useState<EnterpriseTask | null>(null);
  const [events, setEvents] = useState<EnterpriseTaskEvent[]>([]);
  const [runs, setRuns] = useState<EnterpriseTaskRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    setError(null);
    try {
      const result = await api.getTaskResult(taskId);
      setTask(result.task);
      setEvents(result.events);
      setRuns(result.runs);
      if (result.task.chat_id) {
        try {
          setHistory(await api.getChat(result.task.chat_id));
        } catch {
          setHistory(null);
        }
      } else {
        setHistory(null);
      }
    } catch (caught) {
      setError(
        caught instanceof Error
          ? caught.message
          : t("enterprise.tasks.loadFailed"),
      );
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

  const attemptByRun = useMemo(
    () => new Map(runs.map((run) => [run.run_id, run.attempt])),
    [runs],
  );

  const retry = useCallback(async () => {
    if (!taskId) return;
    setRetrying(true);
    try {
      await api.retryTask(taskId);
      message.success(t("enterprise.tasks.retryStarted"));
      await load();
    } catch (caught) {
      message.error(
        caught instanceof Error
          ? caught.message
          : t("enterprise.tasks.retryFailed"),
      );
    } finally {
      setRetrying(false);
    }
  }, [load, message, t, taskId]);

  const cardOptions = useMemo(
    () =>
      ({
        welcome: { nick: "MelonPaw-Company", avatar: "/company-mark.svg" },
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

  const renderMessage = (
    message: IAgentScopeRuntimeWebUIMessage,
    index: number,
  ) => {
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
        current={task?.title || t("enterprise.tasks.result")}
        extra={
          <Space>
            <Button
              icon={<ArrowLeft size={15} />}
              onClick={() => navigate("/tasks")}
            >
              {t("common.back")}
            </Button>
            <Button icon={<RefreshCw size={15} />} onClick={() => void load()}>
              {t("common.refresh")}
            </Button>
            {task?.retryable ? (
              <Button
                icon={<RotateCcw size={15} />}
                loading={retrying}
                onClick={() => void retry()}
              >
                {t("enterprise.tasks.retry")}
              </Button>
            ) : null}
            {task?.chat_id && (
              <Button
                type="primary"
                icon={<MessageSquareText size={16} />}
                onClick={() =>
                  navigate(`/chat/${encodeURIComponent(task.chat_id!)}`)
                }
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
            extra={
              <Button onClick={() => void load()}>{t("common.refresh")}</Button>
            }
          />
        ) : (
          <div className={styles.resultShell}>
            <div className={styles.resultMeta}>
              <Tag color={task ? taskStatusColor(task.status) : "default"}>
                {task
                  ? t(`enterprise.tasks.statuses.${task.status}`, {
                      defaultValue: task.status,
                    })
                  : "-"}
              </Tag>
              <span>
                {t("enterprise.tasks.channel")}:{" "}
                {task?.trigger_type || "console"}
              </span>
              <span>Agent: {task?.agent_id || "-"}</span>
              <span>{task?.scope_type || "PERSONAL"}</span>
              {task?.agent_version_id ? (
                <span>Version: {task.agent_version_id.slice(0, 8)}</span>
              ) : null}
              <span>
                {t("enterprise.tasks.updatedAt")}:{" "}
                {formatTime(task?.updated_at)}
              </span>
              <span>ID: {taskId}</span>
              <span>
                {t("enterprise.tasks.attempt", { count: task?.attempt || 1 })}
              </span>
            </div>
            {messages.length > 0 ? (
              <ChatAnywhereOptionsContextProvider options={cardOptions}>
                <div className={styles.messageList}>
                  {messages.map(renderMessage)}
                </div>
              </ChatAnywhereOptionsContextProvider>
            ) : (
              <div className={styles.taskSummary}>
                {task?.result_summary || task?.safe_error || (
                  <Empty description={t("enterprise.tasks.noResult")} />
                )}
              </div>
            )}
            <section className={styles.taskTimeline}>
              <SectionHeader title={t("enterprise.tasks.timeline")} />
              <Timeline
                items={events.map((event) => ({
                  color: eventColor(event.event_type),
                  children: (
                    <div className={styles.taskEvent}>
                      <strong>
                        {t(`enterprise.tasks.events.${event.event_type}`, {
                          defaultValue: event.event_type,
                        })}
                      </strong>
                      <span>{formatTime(event.occurred_at)}</span>
                      <span>
                        {t("enterprise.tasks.attempt", {
                          count: attemptByRun.get(event.run_id) || 1,
                        })}
                      </span>
                      {eventSummary(event) ? (
                        <small>{eventSummary(event)}</small>
                      ) : null}
                    </div>
                  ),
                }))}
              />
            </section>
          </div>
        )}
      </div>
    </div>
  );
}

function eventColor(type: string): string {
  if (type.includes("failed") || type.includes("denied")) return "red";
  if (type.includes("approval")) return "orange";
  if (type.includes("succeeded") || type.includes("completed")) return "green";
  return "blue";
}

function eventSummary(event: EnterpriseTaskEvent): string {
  const payload = event.payload || {};
  return [
    payload.tool_name,
    payload.decision,
    payload.state,
    payload.reason,
    payload.error,
  ]
    .filter((value) => typeof value === "string" && value)
    .join(" · ");
}
