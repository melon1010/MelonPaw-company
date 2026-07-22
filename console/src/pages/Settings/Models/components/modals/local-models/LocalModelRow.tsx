import { memo } from "react";
import { Button } from "@agentscope-ai/design";
import {
  CheckCircleOutlined,
  DeleteOutlined,
  DownloadOutlined,
} from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import type { LocalModelInfo } from "../../../../../../api/types";
import styles from "../../../index.module.less";
import prettyBytes from "pretty-bytes";

interface LocalModelRowProps {
  model: LocalModelInfo;
  currentRunningModelName: string | null;
  isModelDownloading: boolean;
  isServerBusy: boolean;
  startingModelName: string | null;
  deletingModelName: string | null;
  onStartDownload: (model: LocalModelInfo) => void;
  onSelectModel: (model: LocalModelInfo) => void;
  onDeleteModel: (model: LocalModelInfo) => void;
}

export const LocalModelRow = memo(function LocalModelRow({
  model,
  currentRunningModelName,
  isModelDownloading,
  isServerBusy,
  startingModelName,
  deletingModelName,
  onStartDownload,
  onSelectModel,
  onDeleteModel,
}: LocalModelRowProps) {
  const { t } = useTranslation();
  const isRunning = currentRunningModelName === model.id;
  const isStarting = startingModelName === model.id;
  const isDeleting = deletingModelName === model.id;

  return (
    <div className={styles.modelListItem}>
      <div className={styles.modelListItemInfo}>
        <span className={styles.modelListItemName}>{model.name}</span>
        <span className={styles.modelListItemId}>
          {model.id} · {prettyBytes(model.size_bytes)}
        </span>
      </div>
      <div className={styles.modelListItemActions}>
        {!model.downloaded ? (
          <Button
            type="primary"
            size="small"
            icon={<DownloadOutlined />}
            onClick={() => onStartDownload(model)}
            disabled={isModelDownloading || isServerBusy}
          >
            {t("common.download")}
          </Button>
        ) : isRunning ? (
          <>
            <Button
              size="small"
              icon={<CheckCircleOutlined />}
              disabled
            >
              {t("models.localModelInUse")}
            </Button>
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              loading={isDeleting}
              disabled
              onClick={() => onDeleteModel(model)}
            >
              {t("common.delete")}
            </Button>
          </>
        ) : (
          <>
            <Button
              type="primary"
              size="small"
              loading={isStarting}
              onClick={() => onSelectModel(model)}
              disabled={isServerBusy || isDeleting}
            >
              {t("models.localUseModel")}
            </Button>
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
              loading={isDeleting}
              onClick={() => onDeleteModel(model)}
              disabled={isDeleting || isServerBusy}
            >
              {t("common.delete")}
            </Button>
          </>
        )}
      </div>
    </div>
  );
});
