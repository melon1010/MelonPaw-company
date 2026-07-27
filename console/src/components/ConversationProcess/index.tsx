import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import type {
  IAgentScopeRuntimeMessage,
  AgentScopeRuntimeRunStatus,
} from "@agentscope-ai/chat/lib/AgentScopeRuntimeWebUI/core/AgentScopeRuntime/types";
import {
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock3,
  Database,
  Edit3,
  FilePlus2,
  FileSearch,
  FileText,
  FolderSearch2,
  Gauge,
  Globe2,
  Image as ImageIcon,
  Monitor,
  PackageCheck,
  Search,
  Send,
  ShieldCheck,
  Terminal,
  Video,
  Wrench,
} from "lucide-react";
import {
  ConversationMessageType,
  ConversationRunStatus,
  getMessageRunStatus,
  getMessageText,
  getToolData,
  getToolName,
  isApprovalRequestMessage,
  isProgressMessage,
  isToolInputMessage,
  isToolOutputMessage,
} from "./model";
import styles from "./index.module.less";

const COLLAPSED_STEP_LIMIT = 4;

export interface ConversationProcessProps {
  messages: IAgentScopeRuntimeMessage[];
  hasAnswerContent: boolean;
  isGenerating: boolean;
  renderToolDetails?: (
    message: IAgentScopeRuntimeMessage,
    context: { isApprovalRequest: boolean },
  ) => ReactNode;
}

function parseToolArguments(message: IAgentScopeRuntimeMessage) {
  const inputData = getToolData(message, 0);
  const raw = inputData.arguments ?? inputData.input;
  if (raw && typeof raw === "object") return raw as Record<string, unknown>;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === "object"
        ? (parsed as Record<string, unknown>)
        : {};
    } catch {
      return { value: raw };
    }
  }
  return {};
}

function compactValue(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return "";
}

function toolDisplayName(
  message: IAgentScopeRuntimeMessage,
  t: TFunction,
): string {
  const name = getToolName(message);
  const args = parseToolArguments(message);
  const file = compactValue(args.file_path ?? args.path ?? args.filename);
  const pattern = compactValue(args.pattern ?? args.query);

  switch (name) {
    case "execute_shell_command":
    case "shell":
    case "bash":
    case "terminal":
    case "run_command": {
      const command = compactValue(args.command ?? args.cmd ?? args.value);
      return command ? t("tool.shell", { command }) : t("tool.shellDefault");
    }
    case "read_file":
      return file ? t("tool.readFile", { file }) : t("tool.readFileDefault");
    case "write_file":
      return file ? t("tool.writeFile", { file }) : t("tool.writeFileDefault");
    case "edit_file":
      return file ? t("tool.editFile", { file }) : t("tool.editFileDefault");
    case "append_file":
      return file
        ? t("tool.appendFile", { file })
        : t("tool.appendFileDefault");
    case "grep_search":
    case "grep_files":
      return pattern
        ? t("tool.grepSearch", { pattern })
        : t("tool.grepSearchDefault");
    case "glob_search":
    case "glob_files":
      return pattern
        ? t("tool.globSearch", { pattern })
        : t("tool.globSearchDefault");
    case "view_image":
      return file ? t("tool.viewImage", { file }) : t("tool.viewImageDefault");
    case "view_video":
      return file ? t("tool.viewVideo", { file }) : t("tool.viewVideoDefault");
    case "send_file_to_user":
      return file ? t("tool.sendFile", { file }) : t("tool.sendFileDefault");
    case "get_current_time":
      return t("tool.getCurrentTime");
    case "set_user_timezone":
      return t("tool.setTimezone", {
        timezone: compactValue(args.timezone) || "",
      }).trim();
    case "get_token_usage":
      return t("tool.getTokenUsage");
    case "memory_search":
    case "knowledge_rag_query":
      return pattern
        ? t("tool.memorySearch", { query: pattern })
        : t("tool.memorySearchDefault");
    case "mcp_approval_request":
      return t("chat.executionProcess.approval");
    default:
      return name.replace(/[_-]+/g, " ");
  }
}

function toolIcon(name: string) {
  switch (name) {
    case "execute_shell_command":
    case "shell":
    case "bash":
    case "terminal":
    case "run_command":
      return <Terminal size={15} />;
    case "read_file":
      return <FileText size={15} />;
    case "write_file":
    case "append_file":
      return <FilePlus2 size={15} />;
    case "edit_file":
      return <Edit3 size={15} />;
    case "grep_search":
    case "grep_files":
      return <Search size={15} />;
    case "glob_search":
    case "glob_files":
      return <FolderSearch2 size={15} />;
    case "browser_use":
      return <Globe2 size={15} />;
    case "desktop_screenshot":
      return <Monitor size={15} />;
    case "view_image":
      return <ImageIcon size={15} />;
    case "view_video":
      return <Video size={15} />;
    case "send_file_to_user":
      return <Send size={15} />;
    case "get_current_time":
    case "set_user_timezone":
      return <Clock3 size={15} />;
    case "get_token_usage":
      return <Gauge size={15} />;
    case "memory_search":
    case "knowledge_rag_query":
      return <Database size={15} />;
    case "finalize_artifacts":
      return <PackageCheck size={15} />;
    case "mcp_approval_request":
      return <ShieldCheck size={15} />;
    default:
      if (name.includes("search")) return <FileSearch size={15} />;
      return <Wrench size={15} />;
  }
}

function statusTranslationKey(status: AgentScopeRuntimeRunStatus): string {
  switch (status) {
    case ConversationRunStatus.CREATED:
      return "created";
    case ConversationRunStatus.IN_PROGRESS:
      return "inProgress";
    case ConversationRunStatus.COMPLETED:
      return "completed";
    case ConversationRunStatus.CANCELED:
      return "canceled";
    case ConversationRunStatus.FAILED:
      return "failed";
    case ConversationRunStatus.REJECTED:
      return "rejected";
    default:
      return "unknown";
  }
}

function ReasoningStep({ message }: { message: IAgentScopeRuntimeMessage }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const content = getMessageText(message).trim();
  const preview = content.replace(/\s+/g, " ");
  const status = getMessageRunStatus(message);
  const canExpand = content.length > 80 || content.includes("\n");

  if (!content && status === ConversationRunStatus.CREATED) return null;

  return (
    <div className={styles.step}>
      <span className={styles.reasoningMarker} aria-hidden="true" />
      <button
        type="button"
        className={styles.stepButton}
        disabled={!canExpand}
        aria-expanded={canExpand ? open : undefined}
        onClick={() => canExpand && setOpen((value) => !value)}
      >
        <span className={styles.stepName}>
          {preview || t("chat.executionProcess.reasoning")}
        </span>
        {canExpand ? (
          <span className={styles.stepMeta} aria-hidden="true">
            {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          </span>
        ) : null}
      </button>
      {open && canExpand ? (
        <div className={styles.reasoningDetails}>
          <pre>{content}</pre>
        </div>
      ) : null}
    </div>
  );
}

function ToolStep({
  message,
  renderToolDetails,
}: {
  message: IAgentScopeRuntimeMessage;
  renderToolDetails?: ConversationProcessProps["renderToolDetails"];
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const isApprovalRequest = isApprovalRequestMessage(message);
  const name = getToolName(message);
  const inputData = getToolData(message, 0);
  const status = getMessageRunStatus(message);
  const serverLabel =
    typeof inputData.server_label === "string" ? inputData.server_label : "";
  const details = renderToolDetails?.(message, { isApprovalRequest });
  const canExpand = !isApprovalRequest && Boolean(details);
  const isFailure = (
    [
      ConversationRunStatus.FAILED,
      ConversationRunStatus.REJECTED,
      ConversationRunStatus.CANCELED,
    ] as AgentScopeRuntimeRunStatus[]
  ).includes(status);

  return (
    <div className={styles.step}>
      <span className={styles.toolMarker} aria-hidden="true">
        {toolIcon(name)}
      </span>
      <button
        type="button"
        className={styles.stepButton}
        disabled={!canExpand}
        aria-expanded={canExpand ? open : undefined}
        onClick={() => canExpand && setOpen((value) => !value)}
      >
        <span className={styles.stepMain}>
          <span className={styles.stepName}>{toolDisplayName(message, t)}</span>
          {serverLabel ? (
            <span className={styles.serverLabel}>{serverLabel}</span>
          ) : null}
        </span>
        <span className={styles.stepMeta}>
          <span
            className={`${styles.status} ${
              status === ConversationRunStatus.IN_PROGRESS
                ? styles.statusRunning
                : ""
            } ${isFailure ? styles.statusFailed : ""}`.trim()}
          >
            {t(`chat.executionProcess.status.${statusTranslationKey(status)}`)}
          </span>
          {canExpand ? (
            <span aria-hidden="true">
              {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            </span>
          ) : null}
        </span>
      </button>
      {isApprovalRequest && details ? (
        <div className={styles.approvalDetails}>{details}</div>
      ) : null}
      {open && canExpand ? (
        <div className={styles.toolDetails}>{details}</div>
      ) : null}
    </div>
  );
}

export default function ConversationProcess({
  messages,
  hasAnswerContent,
  isGenerating,
  renderToolDetails,
}: ConversationProcessProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [showAllSteps, setShowAllSteps] = useState(false);
  const started = messages.some(
    (message) => getMessageRunStatus(message) !== ConversationRunStatus.CREATED,
  );
  const canExpand = messages.length > 0;
  const toolCount = useMemo(
    () =>
      messages.filter(
        (message) =>
          isApprovalRequestMessage(message) ||
          isToolInputMessage(message) ||
          isToolOutputMessage(message),
      ).length,
    [messages],
  );
  const hasHiddenSteps =
    hasAnswerContent && messages.length > COLLAPSED_STEP_LIMIT;
  const visibleMessages =
    !hasHiddenSteps || showAllSteps
      ? messages
      : messages.slice(0, COLLAPSED_STEP_LIMIT);
  const title = hasAnswerContent
    ? t("chat.executionProcess.completed")
    : started
    ? t("chat.executionProcess.active")
    : t("chat.executionProcess.starting");

  useEffect(() => {
    setOpen(canExpand && !hasAnswerContent);
    setShowAllSteps(false);
  }, [canExpand, hasAnswerContent]);

  if (!messages.length && !isGenerating) return null;

  return (
    <section className={styles.process} aria-label={title}>
      <button
        type="button"
        className={styles.processHeader}
        disabled={!canExpand}
        aria-expanded={canExpand ? open : undefined}
        onClick={() => {
          if (!canExpand) return;
          setOpen((value) => !value);
          setShowAllSteps(false);
        }}
      >
        <span className={styles.processTitle}>
          <span>{title}</span>
          {hasAnswerContent && messages.length ? (
            <span className={styles.summary}>
              {t("chat.executionProcess.steps", { count: messages.length })}
              {toolCount
                ? ` · ${t("chat.executionProcess.tools", { count: toolCount })}`
                : ""}
            </span>
          ) : null}
        </span>
        {canExpand ? (
          <span className={styles.headerIcon} aria-hidden="true">
            {open ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
          </span>
        ) : null}
      </button>

      {open && messages.length ? (
        <div className={styles.timeline}>
          {visibleMessages.map((message) =>
            message.type === ConversationMessageType.REASONING ||
            isProgressMessage(message) ? (
              <ReasoningStep key={message.id} message={message} />
            ) : (
              <ToolStep
                key={message.id}
                message={message}
                renderToolDetails={renderToolDetails}
              />
            ),
          )}

          {hasHiddenSteps ? (
            <button
              type="button"
              className={styles.moreButton}
              onClick={() => setShowAllSteps((value) => !value)}
            >
              {t(
                showAllSteps
                  ? "chat.executionProcess.showLess"
                  : "chat.executionProcess.showMore",
              )}
            </button>
          ) : null}

          {hasAnswerContent ? (
            <div className={styles.completedStep}>
              <span className={styles.completedMarker} aria-hidden="true">
                <CheckCircle2 size={15} />
              </span>
              <span>{t("chat.executionProcess.finished")}</span>
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

export {
  buildConversationRenderSegments,
  ConversationRunStatus,
  getMessageRunStatus,
  hasVisibleAnswerContent,
  isPersistentToolResultMessage,
  pairToolMessages,
  recoverCompletedAnswer,
} from "./model";
