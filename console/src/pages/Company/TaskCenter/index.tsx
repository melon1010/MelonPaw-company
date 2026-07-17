import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Empty, Input, Popconfirm, Space, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  Bot,
  ExternalLink,
  RefreshCw,
  RotateCcw,
  Search,
  Square,
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, {
  type EnterpriseTask,
  type EnterpriseTaskStatus,
} from "../../../api";
import { PageHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import { isActiveTask, taskStatusColor } from "../taskPresentation";
import styles from "../company.module.less";

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function TaskCenter() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { message } = useAppMessage();
  const [tasks, setTasks] = useState<EnterpriseTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [retryingTaskId, setRetryingTaskId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setTasks(await api.listTasks({ limit: 200 }));
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.tasks.loadFailed"),
      );
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return [...tasks]
      .filter((task) => {
        if (!normalized) return true;
        return [
          task.title,
          task.task_id,
          task.session_id,
          task.trigger_type,
          task.agent_id,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(normalized));
      })
      .sort(
        (a, b) =>
          new Date(b.updated_at || b.created_at || 0).getTime() -
          new Date(a.updated_at || a.created_at || 0).getTime(),
      );
  }, [query, tasks]);

  const cancelTask = useCallback(
    async (taskId: string) => {
      try {
        await api.cancelTask(taskId);
        message.success(t("enterprise.tasks.cancelled"));
        await load();
      } catch (error) {
        message.error(
          error instanceof Error
            ? error.message
            : t("enterprise.tasks.cancelFailed"),
        );
      }
    },
    [load, message, t],
  );

  const retryTask = useCallback(
    async (taskId: string) => {
      setRetryingTaskId(taskId);
      try {
        await api.retryTask(taskId);
        message.success(t("enterprise.tasks.retryStarted"));
        await load();
      } catch (error) {
        message.error(
          error instanceof Error
            ? error.message
            : t("enterprise.tasks.retryFailed"),
        );
      } finally {
        setRetryingTaskId(null);
      }
    },
    [load, message, t],
  );

  const columns: ColumnsType<EnterpriseTask> = [
    {
      title: t("enterprise.tasks.name"),
      dataIndex: "title",
      render: (title: string | undefined, row) => (
        <div className={styles.nameCell}>
          <div className={styles.primaryText}>{title || row.session_id}</div>
          <div className={styles.secondaryText}>
            {row.task_id} ·{" "}
            {t("enterprise.tasks.attempt", { count: row.attempt })}
          </div>
        </div>
      ),
    },
    {
      title: t("enterprise.tasks.channel"),
      dataIndex: "trigger_type",
      width: 190,
      render: (value: string, row) => (
        <Space size={4}>
          <Tag>{value || "console"}</Tag>
          <Tag bordered={false}>{row.scope_type}</Tag>
        </Space>
      ),
    },
    {
      title: t("enterprise.tasks.status"),
      dataIndex: "status",
      width: 120,
      filters: [
        { text: t("enterprise.tasks.statuses.RUNNING"), value: "RUNNING" },
        {
          text: t("enterprise.tasks.statuses.WAITING_APPROVAL"),
          value: "WAITING_APPROVAL",
        },
        { text: t("enterprise.tasks.statuses.SUCCEEDED"), value: "SUCCEEDED" },
        { text: t("enterprise.tasks.statuses.FAILED"), value: "FAILED" },
        { text: t("enterprise.tasks.statuses.CANCELLED"), value: "CANCELLED" },
      ],
      onFilter: (value, row) => row.status === value,
      render: (value: EnterpriseTaskStatus) => (
        <Tag color={taskStatusColor(value)}>
          {t(`enterprise.tasks.statuses.${value}`, { defaultValue: value })}
        </Tag>
      ),
    },
    {
      title: t("enterprise.tasks.updatedAt"),
      dataIndex: "updated_at",
      width: 190,
      sorter: (a, b) =>
        new Date(a.updated_at || 0).getTime() -
        new Date(b.updated_at || 0).getTime(),
      render: formatTime,
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: 340,
      render: (_, row) => (
        <Space size={4}>
          <Button
            type="link"
            icon={<ExternalLink size={15} />}
            onClick={() =>
              navigate(`/tasks/${encodeURIComponent(row.task_id)}/result`)
            }
          >
            {t("enterprise.tasks.result")}
          </Button>
          {row.chat_id ? (
            <Button
              type="link"
              onClick={() =>
                navigate(`/chat/${encodeURIComponent(row.chat_id!)}`)
              }
            >
              {t("enterprise.tasks.conversation")}
            </Button>
          ) : null}
          {isActiveTask(row.status) ? (
            <Popconfirm
              title={t("enterprise.tasks.cancelConfirm")}
              onConfirm={() => cancelTask(row.task_id)}
            >
              <Button type="link" danger icon={<Square size={14} />}>
                {t("common.cancel")}
              </Button>
            </Popconfirm>
          ) : null}
          {row.retryable ? (
            <Button
              type="link"
              icon={<RotateCcw size={14} />}
              loading={retryingTaskId === row.task_id}
              onClick={() => void retryTask(row.task_id)}
            >
              {t("enterprise.tasks.retry")}
            </Button>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        current={t("enterprise.tasks.title")}
        afterBreadcrumb={
          <Tag>{t("common.total", { count: filtered.length })}</Tag>
        }
        extra={
          <Space>
            <Button icon={<RefreshCw size={15} />} onClick={() => void load()}>
              {t("common.refresh")}
            </Button>
            <Button
              type="primary"
              icon={<Bot size={16} />}
              onClick={() => navigate("/chat")}
            >
              {t("enterprise.workbench.startTask")}
            </Button>
          </Space>
        }
      />
      <div className={styles.body}>
        <div className={styles.toolbar}>
          <Input
            allowClear
            prefix={<Search size={15} />}
            placeholder={t("enterprise.tasks.search")}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            style={{ width: 320, maxWidth: "100%" }}
          />
        </div>
        <Table
          rowKey="task_id"
          size="middle"
          loading={loading}
          columns={columns}
          dataSource={filtered}
          locale={{
            emptyText: <Empty description={t("enterprise.tasks.empty")} />,
          }}
          pagination={{ pageSize: 20, showSizeChanger: true }}
          scroll={{ x: 920 }}
        />
      </div>
    </div>
  );
}
