import { useState, useEffect, useCallback, useRef, useMemo } from "react";
import {
  Button,
  Input,
  InputNumber,
  Modal,
  Tooltip,
} from "@agentscope-ai/design";
import { useAppMessage } from "../../../../../hooks/useAppMessage.ts";
import {
  CloseOutlined,
  DownloadOutlined,
  DownOutlined,
  SaveOutlined,
} from "@ant-design/icons";
import { Progress } from "antd";
import type {
  LocalModelConfig,
  ProviderInfo,
  LocalDownloadProgress,
  LocalModelInfo,
  LocalServerStatus,
} from "../../../../../api/types";
import api from "../../../../../api";
import { useTranslation } from "react-i18next";
import styles from "../../index.module.less";
import { JsonConfigEditor } from "./JsonConfigEditor.tsx";
import { LocalModelRow } from "./local-models/LocalModelRow";
import { LocalRuntimePanel } from "./local-models/LocalRuntimePanel";
import {
  formatProgressText,
  getProgressPercent,
  isDownloadActive,
} from "./local-models/shared";

const POLL_INTERVAL_MS = 3000;
const MIN_LOCAL_SERVER_PORT = 1;
const MAX_LOCAL_SERVER_PORT = 65535;

type LocalDownloadStatus = LocalDownloadProgress["status"];

function getInitialLocalModelConfig(config?: LocalModelConfig | null): {
  port: number | null;
} {
  return {
    port:
      typeof config?.port === "number" &&
      Number.isInteger(config.port) &&
      config.port >= MIN_LOCAL_SERVER_PORT &&
      config.port <= MAX_LOCAL_SERVER_PORT
        ? config.port
        : null,
  };
}

function isSameServerStatus(
  left: LocalServerStatus | null,
  right: LocalServerStatus | null,
): boolean {
  return (
    left?.available === right?.available &&
    left?.installable === right?.installable &&
    left?.installed === right?.installed &&
    left?.port === right?.port &&
    left?.model_name === right?.model_name &&
    left?.message === right?.message
  );
}

function isSameDownloadProgress(
  left: LocalDownloadProgress | null,
  right: LocalDownloadProgress | null,
): boolean {
  return (
    left?.status === right?.status &&
    left?.model_name === right?.model_name &&
    left?.downloaded_bytes === right?.downloaded_bytes &&
    left?.total_bytes === right?.total_bytes &&
    left?.speed_bytes_per_sec === right?.speed_bytes_per_sec &&
    left?.source === right?.source &&
    left?.error === right?.error
  );
}

interface LocalStatusSnapshot {
  server: LocalServerStatus;
  model: LocalDownloadProgress;
}

function isBusyDownloadStatus(status: LocalDownloadStatus | null | undefined) {
  return (
    status === "pending" || status === "downloading" || status === "canceling"
  );
}

interface LocalModelManageModalProps {
  provider: ProviderInfo;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

export function LocalModelManageModal({
  provider,
  open,
  onClose,
  onSaved,
}: LocalModelManageModalProps) {
  const { t } = useTranslation();
  const [localModels, setLocalModels] = useState<LocalModelInfo[]>([]);
  const [customModelRepoId, setCustomModelRepoId] = useState("");
  const [loadingLocal, setLoadingLocal] = useState(false);
  const [loadingStatus, setLoadingStatus] = useState(false);
  const [serverStatus, setServerStatus] = useState<LocalServerStatus | null>(
    null,
  );
  const [modelDownload, setModelDownload] =
    useState<LocalDownloadProgress | null>(null);
  const [startingModelName, setStartingModelName] = useState<string | null>(
    null,
  );
  const [deletingModelName, setDeletingModelName] = useState<string | null>(
    null,
  );
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [advancedSaving, setAdvancedSaving] = useState(false);
  const [loadingLocalConfig, setLoadingLocalConfig] = useState(false);
  const [serverPort, setServerPort] = useState<number | null>(null);
  const [savedServerPort, setSavedServerPort] = useState<number | null>(null);
  const [generateKwargsText, setGenerateKwargsText] = useState("");
  const [savedGenerateKwargsText, setSavedGenerateKwargsText] = useState("");
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const modelDownloadRef = useRef<LocalDownloadProgress | null>(null);
  const previousModelStatusRef = useRef<string | null>(null);
  const onSavedRef = useRef(onSaved);
  const initializedOpenRef = useRef(false);

  const { message } = useAppMessage();

  useEffect(() => {
    onSavedRef.current = onSaved;
  }, [onSaved]);

  const initialLocalConfig = useMemo(
    () => ({
      generateKwargsText:
        Object.keys(provider.generate_kwargs ?? {}).length > 0
          ? JSON.stringify(provider.generate_kwargs, null, 2)
          : "",
    }),
    [provider.generate_kwargs],
  );

  const parseGenerateConfig = useCallback(
    (value?: string) => {
      const trimmed = value?.trim();
      if (!trimmed) {
        return undefined;
      }

      let parsed: unknown;
      try {
        parsed = JSON.parse(trimmed);
      } catch {
        throw new Error(t("models.generateConfigInvalidJson"));
      }

      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error(t("models.generateConfigMustBeObject"));
      }

      return parsed as Record<string, unknown>;
    },
    [t],
  );

  const generateKwargsError = useMemo(() => {
    try {
      parseGenerateConfig(generateKwargsText);
      return null;
    } catch (error) {
      return error instanceof Error
        ? error.message
        : t("models.generateConfigInvalidJson");
    }
  }, [generateKwargsText, parseGenerateConfig, t]);

  const getLocalModelDisplayName = (modelId: string | null) => {
    if (!modelId) {
      return null;
    }
    return localModels.find((model) => model.id === modelId)?.name ?? modelId;
  };

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const fetchLocalModels = useCallback(async () => {
    setLoadingLocal(true);
    try {
      const data = await api.listRecommendedLocalModels();
      setLocalModels(Array.isArray(data) ? data : []);
    } catch {
      setLocalModels([]);
    } finally {
      setLoadingLocal(false);
    }
  }, []);

  const fetchLocalConfig = useCallback(async () => {
    setLoadingLocalConfig(true);
    try {
      const config = await api.getLocalModelConfig();
      const normalizedConfig = getInitialLocalModelConfig(config);
      setServerPort(normalizedConfig.port);
      setSavedServerPort(normalizedConfig.port);
      return normalizedConfig;
    } catch {
      const fallbackConfig = getInitialLocalModelConfig();
      setServerPort(fallbackConfig.port);
      setSavedServerPort(fallbackConfig.port);
      return fallbackConfig;
    } finally {
      setLoadingLocalConfig(false);
    }
  }, []);

  const setModelDownloadState = useCallback(
    (
      value:
        | LocalDownloadProgress
        | null
        | ((
            prev: LocalDownloadProgress | null,
          ) => LocalDownloadProgress | null),
    ) => {
      setModelDownload((prev) => {
        const next = typeof value === "function" ? value(prev) : value;
        modelDownloadRef.current = next;
        return next;
      });
    },
    [],
  );

  const refreshStatus = useCallback(
    async (showLoading = false) => {
      if (showLoading) {
        setLoadingStatus(true);
      }
      try {
        const [nextServerStatus, nextModelDownload] = await Promise.all([
          api.getLocalServerStatus(),
          api.getLocalModelDownloadProgress(),
        ]);

        setServerStatus((prev) =>
          isSameServerStatus(prev, nextServerStatus) ? prev : nextServerStatus,
        );
        setModelDownloadState((prev) =>
          isSameDownloadProgress(prev, nextModelDownload)
            ? prev
            : nextModelDownload,
        );

        if (
          (previousModelStatusRef.current === "pending" ||
            previousModelStatusRef.current === "downloading") &&
          nextModelDownload.status === "completed"
        ) {
          message.success(t("models.localDownloadSuccess"));
          onSavedRef.current();
          void fetchLocalModels();
        }

        if (
          previousModelStatusRef.current !== "failed" &&
          nextModelDownload.status === "failed" &&
          nextModelDownload.error
        ) {
          message.error(nextModelDownload.error);
        }

        previousModelStatusRef.current = nextModelDownload.status;

        if (!isBusyDownloadStatus(nextModelDownload.status)) {
          stopPolling();
        }

        return {
          server: nextServerStatus,
          model: nextModelDownload,
        } satisfies LocalStatusSnapshot;
      } catch {
        return null;
      } finally {
        if (showLoading) {
          setLoadingStatus(false);
        }
      }
    },
    [fetchLocalModels, setModelDownloadState, stopPolling, t],
  );

  const startPolling = useCallback(() => {
    if (pollRef.current) return;
    pollRef.current = setInterval(() => {
      void refreshStatus();
    }, POLL_INTERVAL_MS);
  }, [refreshStatus]);

  useEffect(() => {
    if (!open) {
      initializedOpenRef.current = false;
      return;
    }

    if (initializedOpenRef.current) {
      return;
    }
    initializedOpenRef.current = true;

    setAdvancedOpen(false);
    setServerPort(null);
    setSavedServerPort(null);
    setGenerateKwargsText(initialLocalConfig.generateKwargsText);
    setSavedGenerateKwargsText(initialLocalConfig.generateKwargsText);

    void Promise.all([
      fetchLocalConfig(),
      fetchLocalModels(),
      refreshStatus(true),
    ]).then(([, , statuses]) => {
      if (statuses && isBusyDownloadStatus(statuses.model.status)) {
        startPolling();
      }
    });

    return () => stopPolling();
  }, [
    fetchLocalConfig,
    fetchLocalModels,
    open,
    refreshStatus,
    startPolling,
    stopPolling,
    initialLocalConfig,
  ]);

  const handleSaveServerPort = useCallback(async () => {
    if (
      serverPort !== null &&
      (!Number.isInteger(serverPort) ||
        serverPort < MIN_LOCAL_SERVER_PORT ||
        serverPort > MAX_LOCAL_SERVER_PORT)
    ) {
      message.error(
        t("models.localServerPortRange", {
          min: MIN_LOCAL_SERVER_PORT,
          max: MAX_LOCAL_SERVER_PORT,
        }),
      );
      return;
    }

    setAdvancedSaving(true);
    try {
      await api.configureLocalModelSettings({
        port: serverPort,
      });
      setSavedServerPort(serverPort);
      message.success(t("models.localAdvancedConfigSaved"));
      await onSavedRef.current();
    } catch (error) {
      const errMsg =
        error instanceof Error
          ? error.message
          : t("models.localAdvancedConfigSaveFailed");
      message.error(errMsg);
    } finally {
      setAdvancedSaving(false);
    }
  }, [message, serverPort, t]);

  const handleSaveGenerateKwargs = useCallback(async () => {
    let parsed: Record<string, unknown> = {};

    try {
      parsed = parseGenerateConfig(generateKwargsText) ?? {};
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("models.generateConfigInvalidJson"),
      );
      return;
    }

    const trimmed = generateKwargsText.trim();
    const normalizedText = trimmed ? JSON.stringify(parsed, null, 2) : "";

    setAdvancedSaving(true);
    try {
      await api.configureLocalModelSettings({
        generate_kwargs: parsed,
      });
      setGenerateKwargsText(normalizedText);
      setSavedGenerateKwargsText(normalizedText);
      message.success(t("models.localAdvancedConfigSaved"));
      await onSavedRef.current();
    } catch (error) {
      const errMsg =
        error instanceof Error
          ? error.message
          : t("models.localAdvancedConfigSaveFailed");
      message.error(errMsg);
    } finally {
      setAdvancedSaving(false);
    }
  }, [generateKwargsText, message, parseGenerateConfig, t]);

  const handleStartModelDownload = useCallback(
    async (model: LocalModelInfo) => {
      const previousModelDownload = modelDownloadRef.current;
      const previousModelStatus = previousModelStatusRef.current;

      setModelDownloadState({
        status: "pending",
        model_name: model.id,
        downloaded_bytes: 0,
        total_bytes: null,
        speed_bytes_per_sec: 0,
        source: model.source,
        error: null,
        local_path: null,
      });
      previousModelStatusRef.current = "pending";

      try {
        await api.startLocalModelDownload(model.id);
        await refreshStatus();
        startPolling();
      } catch (error) {
        setModelDownloadState(previousModelDownload);
        previousModelStatusRef.current = previousModelStatus;
        const errMsg =
          error instanceof Error
            ? error.message
            : t("models.localDownloadFailed");
        message.error(errMsg);
      }
    },
    [refreshStatus, setModelDownloadState, startPolling, t],
  );

  const handleStartCustomModelDownload = useCallback(async () => {
    const trimmedRepoId = customModelRepoId.trim();

    if (!trimmedRepoId) {
      message.warning(t("models.localRepoIdRequired"));
      return;
    }

    await handleStartModelDownload({
      id: trimmedRepoId,
      name: trimmedRepoId,
      size_bytes: 0,
      downloaded: false,
      source: "ollama",
    });
  }, [customModelRepoId, handleStartModelDownload, t]);

  const handleCancelModelDownload = useCallback(
    (modelName: string) => {
      Modal.confirm({
        title: t("models.localCancelDownloadTitle"),
        content: t("models.localCancelDownloadConfirm", { repo: modelName }),
        okText: t("models.localCancelDownloadAction"),
        okButtonProps: { danger: true },
        cancelText: t("common.close"),
        onOk: async () => {
          try {
            setModelDownloadState((prev) =>
              prev
                ? {
                    ...prev,
                    status: "canceling",
                  }
                : prev,
            );
            await api.cancelLocalModelDownload();
            message.success(t("models.localDownloadCancelled"));
            await refreshStatus();
            startPolling();
          } catch (error) {
            const errMsg =
              error instanceof Error
                ? error.message
                : t("models.localCancelDownloadFailed");
            message.error(errMsg);
          }
        },
      });
    },
    [refreshStatus, setModelDownloadState, startPolling, t],
  );

  const handleSelectModel = useCallback(
    async (model: LocalModelInfo) => {
      const run = async () => {
        setStartingModelName(model.id);
        try {
          await api.selectLocalModel({
            model_id: model.id,
          });
          await refreshStatus();
          onSaved();
        } catch (error) {
          const errMsg =
            error instanceof Error
              ? error.message
              : t("models.localServerStartFailed");
          message.error(errMsg);
        } finally {
          setStartingModelName(null);
        }
      };

      if (
        serverStatus?.available &&
        serverStatus.model_name &&
        serverStatus.model_name !== model.id
      ) {
        Modal.confirm({
          title: t("models.localServerSwitchTitle"),
          content: t("models.localServerSwitchConfirm", {
            current: getLocalModelDisplayName(serverStatus.model_name),
            next: model.name,
          }),
          okText: t("models.localSwitchModel"),
          cancelText: t("models.cancel"),
          onOk: run,
        });
        return;
      }

      await run();
    },
    [localModels, onSaved, refreshStatus, serverStatus, t],
  );

  const handleDeleteModel = useCallback(
    (model: LocalModelInfo) => {
      Modal.confirm({
        title: t("models.localDeleteModel"),
        content: t("models.localDeleteConfirm", { name: model.name }),
        okText: t("common.delete"),
        okButtonProps: { danger: true },
        cancelText: t("common.cancel"),
        onOk: async () => {
          setDeletingModelName(model.id);
          try {
            await api.deleteLocalModel(model.id);
            message.success(
              t("models.localModelDeleted", { name: model.name }),
            );
            await fetchLocalModels();
            onSaved();
          } catch (error) {
            const errMsg =
              error instanceof Error
                ? error.message
                : t("models.localDeleteFailed");
            message.error(errMsg);
          } finally {
            setDeletingModelName(null);
          }
        },
      });
    },
    [fetchLocalModels, message, onSaved, t],
  );

  const handleClose = () => {
    onClose();
  };

  const isModelDownloading = isDownloadActive(modelDownload);
  const isServerBusy =
    startingModelName !== null || deletingModelName !== null;
  const isRuntimeInstalled = Boolean(serverStatus?.installed);
  const runtimeLockedMessage =
    serverStatus?.message || t("models.localModelsLockedHint");
  const isCustomDownloadDisabled =
    customModelRepoId.trim().length === 0 || isModelDownloading || isServerBusy;
  const downloadedModelCount = localModels.filter(
    (model) => model.downloaded,
  ).length;

  const currentRunningModelName = serverStatus?.model_name ?? null;
  const currentRunningModelDisplayName = getLocalModelDisplayName(
    currentRunningModelName,
  );
  const currentModelDownloadName =
    getLocalModelDisplayName(modelDownload?.model_name ?? null) ||
    t("models.localDownloadPending");
  const currentModelDownloadPercent = getProgressPercent(modelDownload);
  // Removed isAdvancedDirty, now handled per-field

  return (
    <Modal
      title={t("models.localModelsTitle", { provider: provider.name })}
      open={open}
      onCancel={handleClose}
      footer={null}
      width={800}
      className={styles.modelManageModal}
      destroyOnHidden
    >
      {(loadingLocal || loadingStatus || loadingLocalConfig) &&
      localModels.length === 0 ? (
        <div className={styles.modelListEmpty}>{t("common.loading")}</div>
      ) : null}

      <section className={styles.localSection}>
        <LocalRuntimePanel serverStatus={serverStatus} />
        {!isRuntimeInstalled ? (
          <div className={styles.localLockedPanel}>
            <div className={styles.localLockedPanelTitle}>
              {t("models.localRuntimeUnsupported")}
            </div>
            <div className={styles.localLockedPanelDescription}>
              <div>{runtimeLockedMessage}</div>
              <div>{t("models.localAlternativeRuntimeHint")}</div>
            </div>
          </div>
        ) : null}
      </section>

      {isRuntimeInstalled ? (
        <section className={styles.localSection}>
          <div className={styles.localSectionHeader}>
            <div>
              <div className={styles.localSectionTitle}>
                {t("models.localModelsSectionTitle")}
              </div>
            </div>
          </div>

          {isRuntimeInstalled && isModelDownloading ? (
            <div className={styles.localSectionInfoRow}>
              <div className={styles.localSectionInfoContent}>
                <span className={styles.localSectionInfoLabel}>
                  {t("models.localCurrentDownloadTitle")}
                </span>
                <span className={styles.localSectionInfoValue}>
                  {currentModelDownloadName}
                </span>
                <div className={styles.localRuntimeDownloadRow}>
                  <div className={styles.localRuntimeProgressBlock}>
                    <div className={styles.localRuntimeProgressBarRow}>
                      <Progress
                        className={styles.localRuntimeProgress}
                        percent={currentModelDownloadPercent ?? 0}
                        showInfo={false}
                        status="active"
                        strokeColor="var(--mp-primary)"
                        strokeWidth={10}
                      />
                      <Tooltip title={t("models.localCancelDownloadAction")}>
                        <Button
                          danger
                          size="small"
                          icon={<CloseOutlined />}
                          onClick={() =>
                            handleCancelModelDownload(currentModelDownloadName)
                          }
                        />
                      </Tooltip>
                    </div>
                    <span className={styles.localRuntimeProgressMeta}>
                      {formatProgressText(modelDownload)}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          ) : null}

          {isRuntimeInstalled && currentRunningModelName ? (
            <div className={styles.localSectionInfoRow}>
              <span className={styles.localSectionInfoLabel}>
                {t("models.localEngineCurrentModelLabel")}
              </span>
              <span className={styles.localSectionInfoValue}>
                {currentRunningModelDisplayName}
              </span>
            </div>
          ) : null}

          {isRuntimeInstalled && downloadedModelCount === 0 ? (
            <div className={styles.localSectionNotice}>
              {t("models.localNoDownloadedModelsHint")}
            </div>
          ) : null}

          <div className={styles.modelList}>
            {serverStatus?.installed && loadingLocal ? (
              <div className={styles.modelListEmpty}>{t("common.loading")}</div>
            ) : serverStatus?.installed && localModels.length === 0 ? (
              <div className={styles.modelListEmpty}>
                {t("models.localNoRecommendedModels")}
              </div>
            ) : null}

            {serverStatus?.installed
              ? localModels.map((model) => (
                  <LocalModelRow
                    key={model.id}
                    model={model}
                    currentRunningModelName={currentRunningModelName}
                    isModelDownloading={isModelDownloading}
                    isServerBusy={isServerBusy}
                    startingModelName={startingModelName}
                    deletingModelName={deletingModelName}
                    onStartDownload={handleStartModelDownload}
                    onSelectModel={handleSelectModel}
                    onDeleteModel={handleDeleteModel}
                  />
                ))
              : null}

            {serverStatus?.installed ? (
              <div
                className={`${styles.modelListItem} ${styles.customModelListItem}`}
              >
                <div className={styles.customModelHeader}>
                  <div className={styles.customModelListItemInfo}>
                    <span className={styles.modelListItemName}>
                      {t("models.localCustomModelTitle")}
                    </span>
                    <span className={styles.customModelHint}>
                      {t("models.localCustomModelHint")}
                    </span>
                  </div>
                  <Button
                    type="primary"
                    size="small"
                    icon={<DownloadOutlined />}
                    onClick={() => {
                      void handleStartCustomModelDownload();
                    }}
                    disabled={isCustomDownloadDisabled}
                  >
                    {t("common.download")}
                  </Button>
                </div>
                <div className={styles.customModelInputRow}>
                  <Input
                    value={customModelRepoId}
                    onChange={(e) => setCustomModelRepoId(e.target.value)}
                    onPressEnter={() => {
                      void handleStartCustomModelDownload();
                    }}
                    placeholder={t("models.localRepoIdPlaceholder")}
                    className={styles.customModelRepoInput}
                  />
                </div>
              </div>
            ) : null}
          </div>
        </section>
      ) : null}

      <section className={styles.localAdvancedConfigSection}>
        <div className={styles.localAdvancedConfigHeader}>
          <button
            type="button"
            className={styles.advancedConfigToggle}
            onClick={() => setAdvancedOpen((prev) => !prev)}
          >
            <span className={styles.advancedConfigToggleLabel}>
              {t("models.localAdvancedConfigTitle")}
            </span>
            <DownOutlined
              className={
                advancedOpen
                  ? styles.localAdvancedConfigChevronOpen
                  : styles.localAdvancedConfigChevronClosed
              }
            />
          </button>
        </div>

        {advancedOpen ? (
          <div className={styles.localAdvancedConfigFields}>
            <div
              className={`${styles.localAdvancedConfigField} ${styles.localAdvancedConfigFieldRow}`}
            >
              <div
                className={`${styles.localAdvancedConfigLabel} ${styles.localAdvancedConfigLabelRow}`}
              >
                <span>{t("models.localServerPortLabel")}</span>
                <Button
                  type="primary"
                  size="small"
                  icon={<SaveOutlined />}
                  loading={advancedSaving}
                  disabled={serverPort === savedServerPort}
                  onClick={() => {
                    if (serverPort !== savedServerPort) {
                      void handleSaveServerPort();
                    }
                  }}
                >
                  {t("models.save")}
                </Button>
              </div>
              <InputNumber
                min={MIN_LOCAL_SERVER_PORT}
                max={MAX_LOCAL_SERVER_PORT}
                step={1}
                precision={0}
                value={serverPort}
                onChange={(value) =>
                  setServerPort(
                    typeof value === "number" ? Math.trunc(value) : null,
                  )
                }
                className={styles.localAdvancedConfigInput}
                placeholder={t("models.localServerPortPlaceholder")}
              />
              <div className={styles.localAdvancedConfigHint}>
                {t("models.localServerPortHint")}
              </div>
            </div>

            <div className={styles.localAdvancedConfigField}>
              <div
                className={`${styles.localAdvancedConfigLabel} ${styles.localAdvancedConfigLabelRow}`}
              >
                <span>{t("models.modelGenerateConfig")}</span>
                <Button
                  type="primary"
                  size="small"
                  icon={<SaveOutlined />}
                  loading={advancedSaving}
                  disabled={
                    generateKwargsText === savedGenerateKwargsText ||
                    Boolean(generateKwargsError)
                  }
                  onClick={() => {
                    if (
                      generateKwargsText !== savedGenerateKwargsText &&
                      !generateKwargsError
                    ) {
                      void handleSaveGenerateKwargs();
                    }
                  }}
                >
                  {t("models.save")}
                </Button>
              </div>
              <JsonConfigEditor
                value={generateKwargsText}
                onChange={setGenerateKwargsText}
                placeholder={`Example:\n{\n  "temperature": 0.7,\n  "top_p": 0.95\n}`}
                variant="expanded"
              />
              {generateKwargsError ? (
                <div className={styles.localAdvancedConfigError}>
                  {generateKwargsError}
                </div>
              ) : null}
              <div className={styles.localAdvancedConfigHint}>
                {t("models.localGenerateConfigHint")}
              </div>
            </div>
          </div>
        ) : null}
      </section>
    </Modal>
  );
}
