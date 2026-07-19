import React from "react";
import { Terminal, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useLoopStore } from "../../stores/loopStore";
import styles from "./index.module.less";

/**
 * Atomic command chip shown in the sender prefix area when a loop skill
 * is selected. Supports single-backspace highlight → double-backspace delete.
 */
export const LoopCommandChip: React.FC = () => {
  const { t } = useTranslation();
  const { selectedSkill, chipHighlighted, setSelectedSkill } = useLoopStore();

  if (!selectedSkill) return null;

  const cls = [
    styles.loopChip,
    chipHighlighted ? styles.loopChipHighlighted : "",
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={cls} data-loop-command>
      <Terminal className={styles.chipIcon} size={13} aria-hidden />
      <span className={styles.chipLabel} title={`/${selectedSkill.name}`}>
        /{selectedSkill.name}
      </span>
      <button
        type="button"
        className={styles.chipClose}
        aria-label={t("common.close", "Close")}
        onClick={(e) => {
          e.stopPropagation();
          setSelectedSkill(null);
        }}
      >
        <X size={11} aria-hidden />
      </button>
    </div>
  );
};
