import { memo } from "react";
import { Tooltip } from "@agentscope-ai/design";
import { useTranslation } from "react-i18next";
import type { LocalServerStatus } from "../../../../../../api/types";
import styles from "../../../index.module.less";

interface LocalRuntimePanelProps {
  serverStatus: LocalServerStatus | null;
}

export const LocalRuntimePanel = memo(function LocalRuntimePanel({
  serverStatus,
}: LocalRuntimePanelProps) {
  const { t } = useTranslation();
  const available = Boolean(serverStatus?.available);
  const statusBadge = available
    ? {
        className: styles.localStatusBadgeRunning,
        label: t("models.localServerOnline"),
      }
    : {
        className: styles.localStatusBadgeDead,
        label: t("models.localServerIdle"),
      };

  return (
    <div className={styles.localRuntimePanel}>
      <div className={styles.localRuntimePanelHeader}>
        <div className={styles.modelListItemInfo}>
          <span className={styles.modelListItemName}>
            {t("models.localLlamacppName")}
          </span>
          <span className={styles.modelListItemId}>
            {t("models.localRuntimeSectionDescription")}
          </span>
        </div>
      </div>

      <div className={styles.localSectionNotice}>
        {t("models.localRuntimeComputeHint")}
      </div>

      <div className={styles.localEngineStatusRow}>
        <div className={styles.localEngineStatusItem}>
          <span className={styles.localEngineMetricLabel}>
            {t("models.localEngineRunStateLabel")}
          </span>
          {serverStatus?.message ? (
            <Tooltip title={serverStatus.message}>
              <span
                className={`${styles.localStatusBadge} ${statusBadge.className}`}
              >
                {statusBadge.label}
              </span>
            </Tooltip>
          ) : (
            <span
              className={`${styles.localStatusBadge} ${statusBadge.className}`}
            >
              {statusBadge.label}
            </span>
          )}
        </div>
      </div>
    </div>
  );
});
