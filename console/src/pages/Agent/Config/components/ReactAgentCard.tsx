import {
  Form,
  InputNumber,
  Select,
  Card,
  Switch,
} from "@agentscope-ai/design";
import { useTranslation } from "react-i18next";
import { useTimezoneOptions } from "../../../../hooks/useTimezoneOptions";
import {
  CONTEXT_MANAGER_BACKEND_OPTIONS,
  MEMORY_MANAGER_BACKEND_OPTIONS,
} from "../../../../constants/backendMappings";
import styles from "../index.module.less";

interface ReactAgentCardProps {
  timezone: string;
  savingTimezone: boolean;
  onTimezoneChange: (value: string) => void;
}

export function ReactAgentCard({
  timezone,
  savingTimezone,
  onTimezoneChange,
}: ReactAgentCardProps) {
  const { t } = useTranslation();

  return (
    <Card className={styles.formCard} title={t("agentConfig.reactAgentTitle")}>
      <div className={styles.reactAgentRow}>
        <Form.Item
          label={t("agentConfig.timezone")}
          tooltip={t("agentConfig.timezoneTooltip")}
          className={styles.reactAgentField}
        >
          <Select
            showSearch
            value={timezone}
            placeholder={t("agentConfig.selectTimezone")}
            filterOption={(input, option) =>
              (option?.label?.toString() || "")
                .toLowerCase()
                .includes(input.toLowerCase())
            }
            options={useTimezoneOptions()}
            onChange={onTimezoneChange}
            loading={savingTimezone}
            disabled={savingTimezone}
            style={{ width: "100%" }}
          />
        </Form.Item>

        <Form.Item
          label={t("agentConfig.shellCommandTimeout")}
          name="shell_command_timeout"
          rules={[
            {
              required: true,
              message: t("agentConfig.shellCommandTimeoutRequired"),
            },
            {
              type: "number",
              min: 1,
              message: t("agentConfig.shellCommandTimeoutMin"),
            },
          ]}
          tooltip={t("agentConfig.shellCommandTimeoutTooltip")}
          className={styles.reactAgentField}
        >
          <InputNumber
            style={{ width: "100%" }}
            min={1}
            step={10}
            placeholder={t("agentConfig.shellCommandTimeoutPlaceholder")}
          />
        </Form.Item>

      </div>

      <Form.Item
        label={t("agentConfig.autoGenerateSessionTitle")}
        name={["auto_title_config", "enabled"]}
        valuePropName="checked"
        tooltip={t("agentConfig.autoGenerateSessionTitleTooltip")}
      >
        <Switch />
      </Form.Item>

      <div className={styles.reactAgentRow}>
        <Form.Item
          label={t("agentConfig.contextManagerBackend")}
          name="context_manager_backend"
          tooltip={t("agentConfig.contextManagerBackendTooltip")}
          className={styles.reactAgentField}
        >
          <Select
            options={CONTEXT_MANAGER_BACKEND_OPTIONS}
            style={{ width: "100%" }}
          />
        </Form.Item>

        <Form.Item
          label={t("agentConfig.contextStrategy")}
          name={["light_context_config", "strategy"]}
          tooltip={t("agentConfig.contextStrategyTooltip")}
          className={styles.reactAgentField}
        >
          <Select
            options={[
              {
                value: "scroll",
                label: t("agentConfig.contextStrategyScroll"),
              },
              {
                value: "native",
                label: t("agentConfig.contextStrategyNative"),
              },
            ]}
            style={{ width: "100%" }}
          />
        </Form.Item>

        <Form.Item
          label={t("agentConfig.memoryManagerBackend")}
          name="memory_manager_backend"
          tooltip={t("agentConfig.memoryManagerBackendTooltip")}
          className={styles.reactAgentField}
        >
          <Select
            options={MEMORY_MANAGER_BACKEND_OPTIONS.map((option) => ({
              ...option,
              label:
                option.value === "file"
                  ? t("agentConfig.memoryFileBackend", "Built-in file memory")
                  : t("agentConfig.memoryNoneBackend", "None (disabled)"),
            }))}
            style={{ width: "100%" }}
          />
        </Form.Item>
      </div>
    </Card>
  );
}
