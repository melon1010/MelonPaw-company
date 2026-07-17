import { useEffect, useMemo, useState } from "react";
import { Button, Modal, Select } from "@agentscope-ai/design";
import { useTranslation } from "react-i18next";
import type {
  PoolSkillSpec,
  WorkspaceSkillSummary,
} from "../../../../api/types";
import { getAgentDisplayName } from "../../../../utils/agentDisplayName";
import styles from "../index.module.less";

interface SkillDistributionModalProps {
  open: boolean;
  skills: PoolSkillSpec[];
  workspaces: WorkspaceSkillSummary[];
  initialSkillNames?: string[];
  onCancel: () => void;
  onConfirm: (skillNames: string[], workspaceIds: string[]) => Promise<void>;
}

export function SkillDistributionModal({
  open,
  skills,
  workspaces,
  initialSkillNames = [],
  onCancel,
  onConfirm,
}: SkillDistributionModalProps) {
  const { t } = useTranslation();
  const [skillNames, setSkillNames] = useState<string[]>([]);
  const [workspaceIds, setWorkspaceIds] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const skillOptions = useMemo(
    () => skills.map((skill) => ({ label: skill.name, value: skill.name })),
    [skills],
  );
  const workspaceOptions = useMemo(
    () =>
      workspaces.map((workspace) => ({
        label: getAgentDisplayName(
          { id: workspace.agent_id, name: workspace.agent_name ?? "" },
          t,
        ),
        value: workspace.agent_id,
      })),
    [t, workspaces],
  );

  useEffect(() => {
    if (!open) return;
    setSkillNames(
      initialSkillNames.filter((name) =>
        skills.some((skill) => skill.name === name),
      ),
    );
    setWorkspaceIds([]);
    setSubmitting(false);
  }, [initialSkillNames, open, skills]);

  const handleConfirm = async () => {
    if (skillNames.length === 0 || workspaceIds.length === 0) return;
    setSubmitting(true);
    try {
      await onConfirm(skillNames, workspaceIds);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={t("skillPool.broadcast")}
      onCancel={submitting ? undefined : onCancel}
      maskClosable={!submitting}
      width={620}
      footer={
        <div className={styles.distributionFooter}>
          <Button onClick={onCancel} disabled={submitting}>
            {t("common.cancel")}
          </Button>
          <Button
            type="primary"
            loading={submitting}
            disabled={skillNames.length === 0 || workspaceIds.length === 0}
            onClick={() => void handleConfirm()}
          >
            {t("skillPool.broadcast")}
          </Button>
        </div>
      }
    >
      <div className={styles.distributionBody}>
        <section className={styles.distributionSection}>
          <div className={styles.distributionSectionHeader}>
            <strong>{t("skills.selectPoolItem")}</strong>
            <div className={styles.distributionBulkActions}>
              <Button
                size="small"
                onClick={() =>
                  setSkillNames(skillOptions.map((item) => item.value))
                }
              >
                {t("skills.selectAll")}
              </Button>
              <Button size="small" onClick={() => setSkillNames([])}>
                {t("skills.clearSelection")}
              </Button>
            </div>
          </div>
          <Select
            mode="multiple"
            value={skillNames}
            options={skillOptions}
            onChange={setSkillNames}
            placeholder={t("skills.selectPoolItem")}
            maxTagCount="responsive"
            showSearch
            optionFilterProp="label"
          />
        </section>

        <section className={styles.distributionSection}>
          <div className={styles.distributionSectionHeader}>
            <strong>{t("skillPool.selectWorkspaces")}</strong>
            <div className={styles.distributionBulkActions}>
              <Button
                size="small"
                onClick={() =>
                  setWorkspaceIds(workspaceOptions.map((item) => item.value))
                }
              >
                {t("skillPool.allWorkspaces")}
              </Button>
              <Button size="small" onClick={() => setWorkspaceIds([])}>
                {t("skills.clearSelection")}
              </Button>
            </div>
          </div>
          <Select
            mode="multiple"
            value={workspaceIds}
            options={workspaceOptions}
            onChange={setWorkspaceIds}
            placeholder={t("skillPool.selectWorkspaces")}
            maxTagCount="responsive"
            showSearch
            optionFilterProp="label"
          />
        </section>
      </div>
    </Modal>
  );
}
