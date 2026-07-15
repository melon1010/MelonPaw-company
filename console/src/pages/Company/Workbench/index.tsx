import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Empty, Spin, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  ArrowRight,
  Bot,
  FolderKanban,
  ListChecks,
  RefreshCw,
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, {
  companyApi,
  type ChatSpec,
  type ProjectSpace,
} from "../../../api";
import type { AgentSummary } from "../../../api/types/agents";
import { useAuth } from "../../../contexts/AuthContext";
import { canAccessRoute } from "../../../auth/authorization";
import { PageHeader } from "../../../components/PageHeader";
import styles from "../company.module.less";

interface DashboardData {
  projects: ProjectSpace[];
  tasks: ChatSpec[];
  agents: AgentSummary[];
  enabledChannels: number;
}

const EMPTY_DATA: DashboardData = {
  projects: [],
  tasks: [],
  agents: [],
  enabledChannels: 0,
};

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function CompanyWorkbench() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const [data, setData] = useState<DashboardData>(EMPTY_DATA);
  const [loading, setLoading] = useState(true);
  const [warning, setWarning] = useState<string | null>(null);
  const canStartTask = canAccessRoute("core.chat", user);

  const load = useCallback(async () => {
    setLoading(true);
    setWarning(null);
    const results = await Promise.allSettled([
      companyApi.listProjects(),
      canStartTask ? api.listChats() : Promise.resolve([] as ChatSpec[]),
      canStartTask
        ? api.listAgents()
        : Promise.resolve({ agents: [] as AgentSummary[] }),
      canStartTask ? api.listChannels() : Promise.resolve(null),
    ]);

    const failed = results.filter(
      (result) => result.status === "rejected",
    ).length;
    const projects = results[0].status === "fulfilled" ? results[0].value : [];
    const tasks = results[1].status === "fulfilled" ? results[1].value : [];
    const agents =
      results[2].status === "fulfilled" ? results[2].value.agents || [] : [];
    const channelConfig =
      results[3].status === "fulfilled" ? results[3].value : null;
    const domesticChannels = new Set([
      "dingtalk",
      "feishu",
      "wecom",
      "qq",
      "wechat",
      "onebot",
      "yuanbao",
      "xiaoyi",
      "console",
    ]);
    const enabledChannels = channelConfig
      ? Object.entries(channelConfig).filter(
          ([name, config]) =>
            domesticChannels.has(name) && Boolean(config?.enabled),
        ).length
      : 0;

    setData({ projects, tasks, agents, enabledChannels });
    if (failed > 0) {
      setWarning(t("enterprise.workbench.partialData", { count: failed }));
    }
    setLoading(false);
  }, [canStartTask, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const recentTasks = useMemo(
    () =>
      [...data.tasks]
        .sort(
          (a, b) =>
            new Date(b.updated_at || b.created_at || 0).getTime() -
            new Date(a.updated_at || a.created_at || 0).getTime(),
        )
        .slice(0, 6),
    [data.tasks],
  );

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
      width: 120,
      render: (value: string) => <Tag>{value || "console"}</Tag>,
    },
    {
      title: t("enterprise.tasks.status"),
      dataIndex: "status",
      width: 110,
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
      width: 180,
      render: formatTime,
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: 90,
      render: (_, row) => (
        <Button
          type="link"
          icon={<ArrowRight size={15} />}
          onClick={() =>
            navigate(`/tasks/${encodeURIComponent(row.id)}/result`)
          }
        >
          {t("enterprise.tasks.result")}
        </Button>
      ),
    },
  ];

  const metrics = [
    [t("enterprise.workbench.projects"), data.projects.length],
    [
      t("enterprise.workbench.enabledAgents"),
      data.agents.filter((a) => a.enabled).length,
    ],
    [t("enterprise.workbench.totalTasks"), data.tasks.length],
    [
      t("enterprise.workbench.runningTasks"),
      data.tasks.filter((c) => c.status === "running").length,
    ],
    [t("enterprise.workbench.channels"), data.enabledChannels],
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        current={t("enterprise.workbench.title")}
        subRow={
          <span className={styles.toolbarMeta}>
            {user?.display_name || user?.username}
          </span>
        }
        extra={
          <Button icon={<RefreshCw size={15} />} onClick={() => void load()}>
            {t("common.refresh")}
          </Button>
        }
      />
      <div className={styles.body}>
        {warning && (
          <Alert
            type="warning"
            showIcon
            closable
            message={warning}
            onClose={() => setWarning(null)}
            style={{ marginBottom: 16 }}
          />
        )}
        {loading ? (
          <Spin style={{ display: "block", margin: "15vh auto" }} />
        ) : (
          <>
            <div className={styles.toolbar}>
              <div className={styles.quickActions}>
                {canStartTask && (
                  <Button
                    type="primary"
                    icon={<Bot size={16} />}
                    onClick={() => navigate("/chat")}
                  >
                    {t("enterprise.workbench.startTask")}
                  </Button>
                )}
                <Button
                  icon={<FolderKanban size={16} />}
                  onClick={() => navigate("/projects")}
                >
                  {t("enterprise.projects.title")}
                </Button>
                <Button
                  icon={<ListChecks size={16} />}
                  onClick={() => navigate("/tasks")}
                >
                  {t("enterprise.tasks.title")}
                </Button>
                {canAccessRoute("core.channels", user) && (
                  <Button onClick={() => navigate("/channels")}>
                    {t("nav.channels")}
                  </Button>
                )}
              </div>
            </div>

            <div className={styles.metrics}>
              {metrics.map(([label, value]) => (
                <div className={styles.metric} key={String(label)}>
                  <span className={styles.metricLabel}>{label}</span>
                  <span className={styles.metricValue}>{value}</span>
                </div>
              ))}
            </div>

            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>
                  {t("enterprise.workbench.recentTasks")}
                </h2>
                <Button type="link" onClick={() => navigate("/tasks")}>
                  {t("enterprise.workbench.viewAll")}
                </Button>
              </div>
              {recentTasks.length > 0 ? (
                <div className={styles.tableWrap}>
                  <Table
                    rowKey="id"
                    size="small"
                    pagination={false}
                    columns={columns}
                    dataSource={recentTasks}
                    scroll={{ x: 760 }}
                  />
                </div>
              ) : (
                <Empty description={t("enterprise.tasks.empty")} />
              )}
            </section>
          </>
        )}
      </div>
    </div>
  );
}
