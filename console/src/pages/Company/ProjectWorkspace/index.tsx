import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Empty,
  Input,
  Modal,
  Result,
  Space,
  Spin,
  Tag,
} from "antd";
import {
  ArrowLeft,
  BookOpenText,
  FileText,
  RefreshCw,
  RotateCcw,
  Save,
  ShieldCheck,
} from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  companyApi,
  type ProjectSpace,
  type ProjectWorkspace,
  type ProjectWorkspaceDocumentName,
} from "../../../api";
import { PageHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
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
  const { projectId = "" } = useParams();
  const [project, setProject] = useState<ProjectSpace | null>(null);
  const [workspace, setWorkspace] = useState<ProjectWorkspace | null>(null);
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
      const [nextProject, nextWorkspace] = await Promise.all([
        companyApi.getProject(projectId),
        companyApi.getProjectWorkspace(projectId),
      ]);
      const nextDrafts = { ...EMPTY_DRAFTS };
      for (const document of nextWorkspace.documents) {
        nextDrafts[document.name] = document.content;
      }
      setProject(nextProject);
      setWorkspace(nextWorkspace);
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
                <div className={styles.projectDocumentEditorHeader}>
                  <div className={styles.projectDocumentHeading}>
                    <span className={styles.projectDocumentHeadingIcon}>
                      {documentMeta[activeName].icon}
                    </span>
                    <div>
                      <h2>{documentMeta[activeName].label}</h2>
                      <p>{documentMeta[activeName].description}</p>
                    </div>
                  </div>
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
                </div>
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
    </div>
  );
}
