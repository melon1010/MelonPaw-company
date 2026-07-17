import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Result,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  ArrowLeft,
  Bot,
  BookOpenText,
  FileText,
  History,
  Network,
  Play,
  RefreshCw,
  RotateCcw,
  Save,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import api, {
  companyApi,
  type ProjectSpace,
  type ProjectAgent,
  type ProjectAgentInput,
  type ProjectAgentVersion,
  type ProjectWorkspace,
  type ProjectWorkspaceDocumentName,
} from "../../../api";
import type { ProviderInfo } from "../../../api/types";
import { PageHeader, SectionHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import { useAgentStore } from "../../../stores/agentStore";
import styles from "../company.module.less";

const DOCUMENT_NAMES: ProjectWorkspaceDocumentName[] = [
  "PROJECT.md",
  "KNOWLEDGE.md",
  "POLICY.md",
];

const EMPTY_DRAFTS: Record<ProjectWorkspaceDocumentName, string> = {
  "PROJECT.md": "",
  "KNOWLEDGE.md": "",
  "POLICY.md": "",
};

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function ProjectWorkspacePage() {
  const { t } = useTranslation();
  const { message } = useAppMessage();
  const navigate = useNavigate();
  const setSelectedAgent = useAgentStore((state) => state.setSelectedAgent);
  const { projectId = "" } = useParams();
  const [project, setProject] = useState<ProjectSpace | null>(null);
  const [workspace, setWorkspace] = useState<ProjectWorkspace | null>(null);
  const [projectAgent, setProjectAgent] = useState<ProjectAgent | null>(null);
  const [agentModalOpen, setAgentModalOpen] = useState(false);
  const [agentSaving, setAgentSaving] = useState(false);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelOptions, setModelOptions] = useState<
    Array<{ label: string; value: string }>
  >([]);
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versions, setVersions] = useState<ProjectAgentVersion[]>([]);
  const [agentForm] = Form.useForm<ProjectAgentInput>();
  const [drafts, setDrafts] = useState(EMPTY_DRAFTS);
  const [savedContents, setSavedContents] = useState(EMPTY_DRAFTS);
  const [activeName, setActiveName] =
    useState<ProjectWorkspaceDocumentName>("PROJECT.md");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    setLoadError(null);
    try {
      const [nextProject, nextWorkspace, nextAgent] = await Promise.all([
        companyApi.getProject(projectId),
        companyApi.getProjectWorkspace(projectId),
        companyApi.getProjectAgent(projectId),
      ]);
      const nextDrafts = { ...EMPTY_DRAFTS };
      for (const document of nextWorkspace.documents) {
        nextDrafts[document.name] = document.content;
      }
      setProject(nextProject);
      setWorkspace(nextWorkspace);
      setProjectAgent(nextAgent);
      setDrafts(nextDrafts);
      setSavedContents(nextDrafts);
    } catch (error) {
      setLoadError(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.workspaceLoadFailed"),
      );
    } finally {
      setLoading(false);
    }
  }, [projectId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const dirtyNames = useMemo(
    () => DOCUMENT_NAMES.filter((name) => drafts[name] !== savedContents[name]),
    [drafts, savedContents],
  );
  const activeDirty = dirtyNames.includes(activeName);
  const activeDocument = workspace?.documents.find(
    (document) => document.name === activeName,
  );

  useEffect(() => {
    if (dirtyNames.length === 0) return;
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [dirtyNames.length]);

  const confirmDiscard = (onConfirm: () => void) => {
    if (dirtyNames.length === 0) {
      onConfirm();
      return;
    }
    Modal.confirm({
      title: t("enterprise.projects.unsavedTitle"),
      content: t("enterprise.projects.unsavedContent", {
        count: dirtyNames.length,
      }),
      okText: t("enterprise.projects.discardChanges"),
      cancelText: t("common.cancel"),
      okButtonProps: { danger: true },
      onOk: onConfirm,
    });
  };

  const saveActiveDocument = async () => {
    if (!projectId || !workspace?.editable || !activeDirty) return;
    setSaving(true);
    try {
      const saved = await companyApi.saveProjectWorkspaceDocument(
        projectId,
        activeName,
        drafts[activeName],
      );
      setSavedContents((current) => ({
        ...current,
        [activeName]: saved.content,
      }));
      setDrafts((current) => ({ ...current, [activeName]: saved.content }));
      setWorkspace((current) =>
        current
          ? {
              ...current,
              documents: current.documents.map((document) =>
                document.name === activeName ? saved : document,
              ),
            }
          : current,
      );
      message.success(t("enterprise.projects.documentSaved"));
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.documentSaveFailed"),
      );
    } finally {
      setSaving(false);
    }
  };

  const openAgentEditor = async () => {
    agentForm.setFieldsValue({
      name: projectAgent?.name || `${project?.name || "Project"} Agent`,
      description: projectAgent?.description || "",
      enabled: projectAgent?.enabled ?? true,
      active_model: projectAgent?.active_model,
      change_summary: "",
    });
    setAgentModalOpen(true);
    if (modelOptions.length > 0) return;
    setModelsLoading(true);
    try {
      const providers = (await api.listProviders()) as ProviderInfo[];
      setModelOptions(
        providers
          .filter((provider) => {
            if (provider.require_api_key === false) return !!provider.base_url;
            if (provider.is_custom) return !!provider.base_url;
            return !!provider.api_key || !!provider.api_key_configured;
          })
          .flatMap((provider) =>
            [...(provider.models || []), ...(provider.extra_models || [])].map(
              (model) => ({
                value: `${provider.id}:${model.id}`,
                label: `${provider.name} / ${model.name || model.id}`,
              }),
            ),
          ),
      );
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.agentSaveFailed"),
      );
    } finally {
      setModelsLoading(false);
    }
  };

  const saveProjectAgent = async (values: ProjectAgentInput) => {
    if (!projectId) return;
    setAgentSaving(true);
    try {
      const saved = projectAgent?.configured
        ? await companyApi.updateProjectAgent(projectId, values)
        : await companyApi.createProjectAgent(projectId, values);
      setProjectAgent({
        ...saved,
        configured: true,
        can_manage: projectAgent?.can_manage ?? true,
        can_execute: projectAgent?.can_execute ?? true,
      });
      setAgentModalOpen(false);
      message.success(t("enterprise.projects.agentSaved"));
      await load();
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.agentSaveFailed"),
      );
    } finally {
      setAgentSaving(false);
    }
  };

  const openVersions = async () => {
    if (!projectId) return;
    setVersionsOpen(true);
    setVersionsLoading(true);
    try {
      setVersions(await companyApi.listProjectAgentVersions(projectId));
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.versionsLoadFailed"),
      );
    } finally {
      setVersionsLoading(false);
    }
  };

  const rollbackVersion = async (versionId: string) => {
    if (!projectId) return;
    try {
      await companyApi.rollbackProjectAgent(projectId, versionId);
      message.success(t("enterprise.projects.rollbackComplete"));
      await Promise.all([load(), openVersions()]);
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.rollbackFailed"),
      );
    }
  };

  const startProjectChat = () => {
    if (!projectAgent?.agent_id) return;
    setSelectedAgent(projectAgent.agent_id);
    navigate("/chat");
  };

  const openProjectResource = (path: "/skills" | "/mcp") => {
    if (!projectAgent?.agent_id) return;
    setSelectedAgent(projectAgent.agent_id);
    navigate(path);
  };

  const versionColumns: ColumnsType<ProjectAgentVersion> = [
    {
      title: t("enterprise.projects.version"),
      dataIndex: "version",
      width: 90,
      render: (version: number) => <Tag>v{version}</Tag>,
    },
    {
      title: t("enterprise.projects.changeSummary"),
      dataIndex: "change_summary",
    },
    {
      title: t("enterprise.projects.publishedAt"),
      dataIndex: "published_at",
      width: 190,
      render: formatTime,
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: 110,
      render: (_, version) =>
        projectAgent?.can_manage &&
        version.version_id !== projectAgent.current_version_id ? (
          <Popconfirm
            title={t("enterprise.projects.rollbackConfirm", {
              version: version.version,
            })}
            onConfirm={() => rollbackVersion(version.version_id)}
          >
            <Button type="link">{t("enterprise.projects.rollback")}</Button>
          </Popconfirm>
        ) : null,
    },
  ];

  const documentMeta = {
    "PROJECT.md": {
      label: t("enterprise.projects.projectDocument"),
      description: t("enterprise.projects.projectDocumentDesc"),
      icon: <FileText size={18} />,
    },
    "KNOWLEDGE.md": {
      label: t("enterprise.projects.knowledgeDocument"),
      description: t("enterprise.projects.knowledgeDocumentDesc"),
      icon: <BookOpenText size={18} />,
    },
    "POLICY.md": {
      label: t("enterprise.projects.policyDocument"),
      description: t("enterprise.projects.policyDocumentDesc"),
      icon: <ShieldCheck size={18} />,
    },
  } satisfies Record<
    ProjectWorkspaceDocumentName,
    { label: string; description: string; icon: React.ReactNode }
  >;

  return (
    <div className={styles.page}>
      <PageHeader
        parent={t("enterprise.projects.title")}
        current={project?.name || t("enterprise.projects.workspace")}
        afterBreadcrumb={
          project?.member_role ? <Tag>{project.member_role}</Tag> : undefined
        }
        subRow={
          project ? (
            <span className={styles.toolbarMeta}>
              {project.code} · {project.description || project.id}
            </span>
          ) : undefined
        }
        extra={
          <Space wrap>
            <Button
              icon={<ArrowLeft size={15} />}
              onClick={() => confirmDiscard(() => navigate("/projects"))}
            >
              {t("common.back")}
            </Button>
            <Button
              icon={<RefreshCw size={15} />}
              onClick={() => confirmDiscard(() => void load())}
              disabled={loading}
            >
              {t("common.refresh")}
            </Button>
            {workspace?.editable && (
              <Button
                type="primary"
                icon={<Save size={15} />}
                loading={saving}
                disabled={!activeDirty}
                onClick={() => void saveActiveDocument()}
              >
                {t("common.save")}
              </Button>
            )}
          </Space>
        }
      />

      <div className={`${styles.body} ${styles.projectWorkspaceBody}`}>
        {loading ? (
          <Spin style={{ display: "block", margin: "15vh auto" }} />
        ) : loadError ? (
          <Result
            status="error"
            title={t("enterprise.projects.workspaceLoadFailed")}
            subTitle={loadError}
            extra={
              <Button onClick={() => void load()}>{t("common.refresh")}</Button>
            }
          />
        ) : !workspace ? (
          <Empty description={t("enterprise.projects.workspaceEmpty")} />
        ) : (
          <>
            {!workspace.editable && (
              <Alert
                type="info"
                showIcon
                message={t("enterprise.projects.workspaceReadOnly")}
                className={styles.workspaceReadOnlyAlert}
              />
            )}
            <section className={styles.projectAgentSection}>
              <SectionHeader
                icon={<Bot size={18} />}
                title={t("enterprise.projects.agentTitle")}
                description={t("enterprise.projects.agentDescription")}
                extra={
                  <Space wrap>
                    {projectAgent?.configured ? (
                      <>
                        <Button
                          icon={<History size={15} />}
                          onClick={() => void openVersions()}
                        >
                          {t("enterprise.projects.versions")}
                        </Button>
                        {projectAgent.can_manage ? (
                          <>
                            <Button
                              icon={<Sparkles size={15} />}
                              onClick={() => openProjectResource("/skills")}
                            >
                              {t("nav.skills")}
                            </Button>
                            <Button
                              icon={<Network size={15} />}
                              onClick={() => openProjectResource("/mcp")}
                            >
                              {t("nav.mcp")}
                            </Button>
                            <Button onClick={() => void openAgentEditor()}>
                              {t("common.edit")}
                            </Button>
                          </>
                        ) : null}
                        {projectAgent.can_execute && projectAgent.enabled ? (
                          <Button
                            type="primary"
                            icon={<Play size={15} />}
                            onClick={startProjectChat}
                          >
                            {t("enterprise.projects.startAgent")}
                          </Button>
                        ) : null}
                      </>
                    ) : projectAgent?.can_manage ? (
                      <Button
                        type="primary"
                        onClick={() => void openAgentEditor()}
                      >
                        {t("enterprise.projects.createAgent")}
                      </Button>
                    ) : null}
                  </Space>
                }
              />
              {projectAgent?.configured ? (
                <div className={styles.projectAgentMeta}>
                  <strong>{projectAgent.name}</strong>
                  <Tag color={projectAgent.enabled ? "success" : "default"}>
                    {projectAgent.enabled
                      ? t("common.enabled")
                      : t("common.disabled")}
                  </Tag>
                  <Tag color="blue">v{projectAgent.current_version || 1}</Tag>
                  {projectAgent.active_model ? (
                    <Tag>{projectAgent.active_model}</Tag>
                  ) : null}
                  <span>{projectAgent.description}</span>
                  <span>{projectAgent.agent_id}</span>
                </div>
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={t("enterprise.projects.agentEmpty")}
                />
              )}
            </section>
            <div className={styles.projectWorkspaceShell}>
              <aside
                className={styles.projectDocumentList}
                aria-label={t("enterprise.projects.documents")}
              >
                {DOCUMENT_NAMES.map((name) => {
                  const meta = documentMeta[name];
                  const document = workspace.documents.find(
                    (item) => item.name === name,
                  );
                  const dirty = dirtyNames.includes(name);
                  return (
                    <button
                      type="button"
                      key={name}
                      className={`${styles.projectDocumentItem} ${
                        activeName === name
                          ? styles.projectDocumentItemActive
                          : ""
                      }`}
                      onClick={() => setActiveName(name)}
                    >
                      <span className={styles.projectDocumentIcon}>
                        {meta.icon}
                      </span>
                      <span className={styles.projectDocumentText}>
                        <span className={styles.projectDocumentTitleRow}>
                          <strong>{meta.label}</strong>
                          {dirty && (
                            <span className={styles.unsavedDot} aria-hidden />
                          )}
                        </span>
                        <span className={styles.projectDocumentFileName}>
                          {name}
                        </span>
                        <span className={styles.projectDocumentUpdated}>
                          {formatTime(document?.updated_at)}
                        </span>
                      </span>
                    </button>
                  );
                })}
              </aside>

              <section className={styles.projectDocumentEditor}>
                <SectionHeader
                  className={styles.projectDocumentEditorHeader}
                  icon={documentMeta[activeName].icon}
                  title={documentMeta[activeName].label}
                  description={documentMeta[activeName].description}
                  extra={
                    <Space size={8}>
                      {activeDirty && (
                        <Tag color="warning">
                          {t("enterprise.projects.unsaved")}
                        </Tag>
                      )}
                      {workspace.editable && (
                        <Button
                          type="text"
                          icon={<RotateCcw size={15} />}
                          disabled={!activeDirty || saving}
                          onClick={() =>
                            setDrafts((current) => ({
                              ...current,
                              [activeName]: savedContents[activeName],
                            }))
                          }
                        >
                          {t("common.reset")}
                        </Button>
                      )}
                    </Space>
                  }
                />
                <Input.TextArea
                  className={styles.projectDocumentTextarea}
                  aria-label={activeName}
                  value={drafts[activeName]}
                  readOnly={!workspace.editable}
                  onChange={(event) =>
                    setDrafts((current) => ({
                      ...current,
                      [activeName]: event.target.value,
                    }))
                  }
                  placeholder={t("enterprise.projects.documentPlaceholder")}
                  spellCheck={false}
                />
                <div className={styles.projectDocumentFooter}>
                  <span>{activeName}</span>
                  <span>
                    {t("enterprise.projects.characterCount", {
                      count: drafts[activeName].length,
                    })}
                  </span>
                  <span>{formatTime(activeDocument?.updated_at)}</span>
                </div>
              </section>
            </div>
          </>
        )}
      </div>

      <Modal
        open={agentModalOpen}
        title={
          projectAgent?.configured
            ? t("enterprise.projects.editAgent")
            : t("enterprise.projects.createAgent")
        }
        onCancel={() => setAgentModalOpen(false)}
        onOk={() => agentForm.submit()}
        confirmLoading={agentSaving}
        destroyOnHidden
      >
        <Form<ProjectAgentInput>
          form={agentForm}
          layout="vertical"
          onFinish={(values) => void saveProjectAgent(values)}
        >
          <Form.Item
            name="name"
            label={t("enterprise.projects.agentName")}
            rules={[{ required: true }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t("enterprise.projects.agentSummary")}
          >
            <Input.TextArea
              maxLength={500}
              autoSize={{ minRows: 3, maxRows: 6 }}
            />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t("enterprise.projects.agentEnabled")}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item name="active_model" label={t("models.model")}>
            <Select
              showSearch
              optionFilterProp="label"
              loading={modelsLoading}
              options={modelOptions}
              placeholder={t("models.selectModel")}
            />
          </Form.Item>
          {projectAgent?.configured ? (
            <Form.Item
              name="change_summary"
              label={t("enterprise.projects.changeSummary")}
            >
              <Input maxLength={240} />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>

      <Drawer
        open={versionsOpen}
        title={t("enterprise.projects.versions")}
        width={680}
        onClose={() => setVersionsOpen(false)}
      >
        <Table
          rowKey="version_id"
          size="small"
          loading={versionsLoading}
          columns={versionColumns}
          dataSource={versions}
          pagination={false}
          scroll={{ x: 600 }}
        />
      </Drawer>
    </div>
  );
}
