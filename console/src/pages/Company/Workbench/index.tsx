import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Empty, Spin, Tag } from "antd";
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
  type EnterpriseTask,
  type EnterpriseTaskStatus,
  type ProjectSpace,
} from "../../../api";
import type { AgentSummary } from "../../../api/types/agents";
import { useAuth } from "../../../contexts/AuthContext";
import { canAccessRoute } from "../../../auth/authorization";
import { PageHeader, SectionHeader } from "../../../components/PageHeader";
import ResponsiveTable from "../../../components/ResponsiveTable";
import { isActiveTask, taskStatusColor } from "../taskPresentation";
import { useAgentStore } from "../../../stores/agentStore";
import styles from "../company.module.less";

interface DashboardData {
  projects: ProjectSpace[];
  tasks: EnterpriseTask[];
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
  const setSelectedAgent = useAgentStore((state) => state.setSelectedAgent);
  const [data, setData] = useState<DashboardData>(EMPTY_DATA);
  const [loading, setLoading] = useState(true);
  const [warning, setWarning] = useState<string | null>(null);
  const canStartTask = canAccessRoute("core.chat", user);

  const load = useCallback(async () => {
    setLoading(true);
    setWarning(null);
    const results = await Promise.allSettled([
      companyApi.listProjects(),
      canStartTask
        ? api.listTasks({ limit: 200 })
        : Promise.resolve([] as EnterpriseTask[]),
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

  const columns: ColumnsType<EnterpriseTask> = [
    {
      title: t("enterprise.tasks.name"),
      dataIndex: "title",
      render: (title: string | undefined, row) => (
        <div className={styles.nameCell}>
          <div className={styles.primaryText}>{title || row.session_id}</div>
          <div className={styles.secondaryText}>{row.task_id}</div>
        </div>
      ),
    },
    {
      title: t("enterprise.tasks.channel"),
      dataIndex: "trigger_type",
      width: 120,
      render: (value: string) => <Tag>{value || "console"}</Tag>,
    },
    {
      title: t("enterprise.tasks.status"),
      dataIndex: "status",
      width: 130,
      render: (value: EnterpriseTaskStatus) => (
        <Tag color={taskStatusColor(value)}>
          {t(`enterprise.tasks.statuses.${value}`, { defaultValue: value })}
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
            navigate(`/tasks/${encodeURIComponent(row.task_id)}/result`)
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
      data.tasks.filter((task) => isActiveTask(task.status)).length,
    ],
    [t("enterprise.workbench.channels"), data.enabledChannels],
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        parent={t("nav.settings")}
        current={t("enterprise.nav.workbench")}
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
                    onClick={() => {
                      const personalAgent = data.agents.find(
                        (agent) => agent.enabled && agent.scope === "PERSONAL",
                      );
                      if (personalAgent) setSelectedAgent(personalAgent.id);
                      navigate("/chat");
                    }}
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
              <SectionHeader
                title={t("enterprise.workbench.recentTasks")}
                extra={
                  <Button type="link" onClick={() => navigate("/tasks")}>
                    {t("enterprise.workbench.viewAll")}
                  </Button>
                }
              />
              {recentTasks.length > 0 ? (
                <div className={styles.tableWrap}>
                  <ResponsiveTable
                    rowKey="task_id"
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
