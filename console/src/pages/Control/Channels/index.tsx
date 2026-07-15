import { useCallback, useEffect, useMemo, useState } from "react";
import { Form } from "@agentscope-ai/design";
import { Badge, Button, Space } from "antd";
import { SafetyOutlined, AuditOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import api from "../../../api";
import {
  ChannelCard,
  ChannelDrawer,
  AccessControlDrawer,
  PendingApprovalsDrawer,
  useChannels,
  getChannelLabel,
  ChannelAvailableItem,
  type ChannelKey,
} from "./components";
import { PageHeader } from "@/components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import { useAuth } from "../../../contexts/AuthContext";
import { hasPermission } from "../../../auth/authorization";
import styles from "./index.module.less";

function ChannelsPage() {
  const { t } = useTranslation();
  const { message } = useAppMessage();
  const { user } = useAuth();
  const canManageAccess =
    hasPermission(user, "channels:personal") ||
    hasPermission(user, "agents:manage");
  const { channels, orderedKeys, isBuiltin, loading, fetchChannels } =
    useChannels();
  const [saving, setSaving] = useState(false);
  const [activeKey, setActiveKey] = useState<ChannelKey | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [aclDrawerOpen, setAclDrawerOpen] = useState(false);
  const [pendingDrawerOpen, setPendingDrawerOpen] = useState(false);
  const [pendingCount, setPendingCount] = useState(0);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [form] = Form.useForm<any>();

  const fetchPendingCount = useCallback(async () => {
    if (!canManageAccess) return;
    try {
      const data = await api.getAclAllPending();
      setPendingCount(data.length);
    } catch {
      // ignore
    }
  }, [canManageAccess]);

  useEffect(() => {
    fetchPendingCount();
  }, [fetchPendingCount]);

  // Sort cards: enabled first, then disabled (preserve orderedKeys order within each group)
  const { enabledCards, disabledCards } = useMemo(() => {
    const enabledCards: { key: ChannelKey; config: Record<string, unknown> }[] =
      [];
    const disabledCards: {
      key: ChannelKey;
      config: Record<string, unknown>;
    }[] = [];

    orderedKeys.forEach((key) => {
      const config = channels[key] || { enabled: false, bot_prefix: "" };
      if (config.enabled) {
        enabledCards.push({ key, config });
      } else {
        disabledCards.push({ key, config });
      }
    });

    return { enabledCards, disabledCards };
  }, [channels, orderedKeys]);

  const handleCardClick = useCallback(
    (key: ChannelKey) => {
      setActiveKey(key);
      setDrawerOpen(true);
      const channelConfig = channels[key] || { enabled: false, bot_prefix: "" };
      // Migrate legacy allowlist policy to new access control fields
      const accessControlDm =
        channelConfig.access_control_dm ||
        channelConfig.dm_policy === "allowlist";
      const accessControlGroup =
        channelConfig.access_control_group ||
        channelConfig.group_policy === "allowlist";
      form.setFieldsValue({
        ...channelConfig,
        access_control_dm: accessControlDm,
        access_control_group: accessControlGroup,
        filter_tool_messages: !channelConfig.filter_tool_messages,
        filter_thinking: !channelConfig.filter_thinking,
      });
    },
    [channels, form],
  );

  const handleDrawerClose = () => {
    setDrawerOpen(false);
    setActiveKey(null);
  };

  const handleSubmit = async (values: Record<string, unknown>) => {
    if (!activeKey) return;

    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { isBuiltin: _isBuiltin, ...savedConfig } = channels[activeKey] || {};
    const updatedChannel: Record<string, unknown> = {
      ...savedConfig,
      ...values,
      filter_tool_messages: !values.filter_tool_messages,
      filter_thinking: !values.filter_thinking,
    };

    setSaving(true);
    try {
      await api.updateChannelConfig(
        activeKey,
        updatedChannel as unknown as Parameters<
          typeof api.updateChannelConfig
        >[1],
      );
      await fetchChannels();

      setDrawerOpen(false);
      message.success(t("channels.configSaved"));
    } catch (error) {
      console.error("❌ Failed to update channel config:", error);
      message.error(t("channels.configFailed"));
    } finally {
      setSaving(false);
    }
  };

  const activeLabel = activeKey ? getChannelLabel(activeKey, t) : "";

  return (
    <div className={styles.channelsPage}>
      <PageHeader
        className={styles.pageHeader}
        items={[
          {
            title: canManageAccess
              ? t("nav.control")
              : t("enterprise.nav.mySpace", "我的空间"),
          },
          { title: t("channels.title") },
        ]}
        extra={canManageAccess ? (
          <Space size={8}>
            <Badge dot={pendingCount > 0} offset={[-4, 4]}>
              <Button
                icon={<AuditOutlined />}
                onClick={() => setPendingDrawerOpen(true)}
              >
                {t("channels.pendingApprovals")}
              </Button>
            </Badge>
            <Button
              icon={<SafetyOutlined />}
              onClick={() => setAclDrawerOpen(true)}
            >
              {t("channels.manageAccessControl")}
            </Button>
          </Space>
        ) : undefined}
      />
      <div className={styles.channelsContainer}>
        {loading ? (
          <div className={styles.loading}>
            <span className={styles.loadingText}>{t("channels.loading")}</span>
          </div>
        ) : (
          <>
            {/* Enabled Channels Section */}
            <div className={styles.panelSection}>
              <div className={styles.panelTitle}>
                <span className={styles.panelDotGreen} />
                {t("channels.enabledSection")}
                <span className={styles.panelCount}>
                  {t("channels.enabledCount", { count: enabledCards.length })}
                </span>
              </div>

              {enabledCards.length > 0 ? (
                <div className={styles.channelsGrid}>
                  {enabledCards.map(({ key, config }) => (
                    <ChannelCard
                      key={key}
                      channelKey={key}
                      config={config}
                      onClick={() => handleCardClick(key)}
                    />
                  ))}
                </div>
              ) : (
                <div className={styles.emptyConfigured}>
                  <p>{t("channels.noEnabledChannels")}</p>
                  {disabledCards.length > 0 && (
                    <Button
                      type="primary"
                      onClick={() => {
                        document
                          .getElementById("available-channels")
                          ?.scrollIntoView({ behavior: "smooth" });
                      }}
                    >
                      {t("channels.goEnableChannels")}
                    </Button>
                  )}
                </div>
              )}
            </div>

            {/* Available Channels Section */}
            {disabledCards.length > 0 && (
              <div
                id="available-channels"
                className={styles.panelSectionDashed}
              >
                <div className={styles.panelTitle}>
                  <span className={styles.panelDotGray} />
                  {t("channels.availableSection")}
                </div>
                <div className={styles.availableGrid}>
                  {disabledCards.map(({ key }) => (
                    <ChannelAvailableItem
                      key={key}
                      channelKey={key}
                      onClick={() => handleCardClick(key)}
                    />
                  ))}
                </div>
              </div>
            )}
          </>
        )}
      </div>
      <ChannelDrawer
        open={drawerOpen}
        activeKey={activeKey}
        activeLabel={activeLabel}
        form={form}
        saving={saving}
        initialValues={activeKey ? channels[activeKey] : undefined}
        isBuiltin={activeKey ? isBuiltin(activeKey) : true}
        onClose={handleDrawerClose}
        onSubmit={handleSubmit}
      />
      {canManageAccess && (
        <>
          <AccessControlDrawer
            open={aclDrawerOpen}
            onClose={() => setAclDrawerOpen(false)}
          />
          <PendingApprovalsDrawer
            open={pendingDrawerOpen}
            onClose={() => {
              setPendingDrawerOpen(false);
              fetchPendingCount();
            }}
          />
        </>
      )}
    </div>
  );
}

export default ChannelsPage;
