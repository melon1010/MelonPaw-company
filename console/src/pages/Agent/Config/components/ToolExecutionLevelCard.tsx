import { Card, Alert, Space, Typography } from "antd";
import { Shield, CheckCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import styles from "../index.module.less";

const { Text, Paragraph } = Typography;

export function ToolExecutionLevelCard() {
  const { t } = useTranslation();

  return (
    <Card
      className={styles.formCard}
      title={
        <Space>
          <Shield size={18} />
          {t("agentConfig.toolExecutionLevel.title")}
        </Space>
      }
    >
      <Alert
        type="success"
        message={t(
          "agentConfig.toolExecutionLevel.runtimeTitle",
          "Runtime risk policy is active",
        )}
        description={t(
          "agentConfig.toolExecutionLevel.runtimeDesc",
          "The runtime classifies each tool call. Safe workspace operations run automatically; destructive actions, protected data access, and external side effects can require approval.",
        )}
        style={{ marginBottom: 24 }}
        showIcon
      />
      <Space align="start" size={12}>
        <CheckCircle size={18} color="#52c41a" />
        <div>
          <Text strong>{t("agentConfig.toolExecutionLevel.defaultTools", "Default tool access")}</Text>
          <Paragraph type="secondary" style={{ margin: "4px 0 0", fontSize: 13 }}>
            {t(
              "agentConfig.toolExecutionLevel.defaultToolsDesc",
              "Reading, searching, workspace editing, safe shell commands, skills, and restricted subagents are handled by the runtime policy.",
            )}
          </Paragraph>
        </div>
      </Space>
    </Card>
  );
}
