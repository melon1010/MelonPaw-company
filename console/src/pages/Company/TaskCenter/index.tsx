import { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Empty, Input, Space, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Bot, ExternalLink, RefreshCw, Search } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, { type ChatSpec } from "../../../api";
import { PageHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
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
  const [tasks, setTasks] = useState<ChatSpec[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setTasks(await api.listChats());
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.tasks.loadFailed"));
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
        return [task.name, task.id, task.session_id, task.channel]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(normalized));
      })
      .sort(
        (a, b) =>
          new Date(b.updated_at || b.created_at || 0).getTime() -
          new Date(a.updated_at || a.created_at || 0).getTime(),
      );
  }, [query, tasks]);

  const columns: ColumnsType<ChatSpec> = [
    {
      title: t("enterprise.tasks.name"),
      dataIndex: "name",
      render: (name: string | undefined, row) => (
        <div className={styles.nameCell}>
          <div className={styles.primaryText}>{name || row.session_id}</div>
          <div className={styles.secondaryText}>{row.id}</div>
        </div>
      ),
    },
    {
      title: t("enterprise.tasks.channel"),
      dataIndex: "channel",
      width: 130,
      render: (value: string) => <Tag>{value || "console"}</Tag>,
    },
    {
      title: t("enterprise.tasks.status"),
      dataIndex: "status",
      width: 120,
      filters: [
        { text: t("enterprise.tasks.running"), value: "running" },
        { text: t("enterprise.tasks.completed"), value: "idle" },
      ],
      onFilter: (value, row) => (row.status || "idle") === value,
      render: (value) => (
        <Tag color={value === "running" ? "processing" : "success"}>
          {value === "running"
            ? t("enterprise.tasks.running")
            : t("enterprise.tasks.completed")}
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
      width: 190,
      render: (_, row) => (
        <Space size={4}>
          <Button
            type="link"
            icon={<ExternalLink size={15} />}
            onClick={() => navigate(`/tasks/${encodeURIComponent(row.id)}/result`)}
          >
            {t("enterprise.tasks.result")}
          </Button>
          <Button type="link" onClick={() => navigate(`/chat/${encodeURIComponent(row.id)}`)}>
            {t("enterprise.tasks.conversation")}
          </Button>
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
            <Button type="primary" icon={<Bot size={16} />} onClick={() => navigate("/chat")}>
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
          rowKey="id"
          size="middle"
          loading={loading}
          columns={columns}
          dataSource={filtered}
          locale={{ emptyText: <Empty description={t("enterprise.tasks.empty")} /> }}
          pagination={{ pageSize: 20, showSizeChanger: true }}
          scroll={{ x: 860 }}
        />
      </div>
    </div>
  );
}
