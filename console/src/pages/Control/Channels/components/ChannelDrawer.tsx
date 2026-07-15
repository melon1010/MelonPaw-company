import {
  Drawer,
  Form,
  Input,
  InputNumber,
  Switch,
  Button,
  Select,
} from "@agentscope-ai/design";
import { useAppMessage } from "../../../../hooks/useAppMessage";
import { Alert, ConfigProvider } from "antd";
import { LinkOutlined } from "@ant-design/icons";
import { useTranslation } from "react-i18next";
import type { FormInstance } from "antd";
import { getChannelLabel, type ChannelKey } from "./constants";
import { QrcodeAuthBlock } from "./QrcodeAuthBlock";
import styles from "../index.module.less";
import { openExternalLink } from "../../../../utils/openExternalLink";
import { useAgentStore } from "../../../../stores/agentStore";

const CHANNELS_WITH_ACCESS_CONTROL: ChannelKey[] = [
  "dingtalk",
  "feishu",
  "wecom",
  "wechat",
  "onebot",
  "qq",
  "xiaoyi",
  "yuanbao",
];

const CHANNEL_VENDOR_DOC_URLS: Partial<Record<ChannelKey, string>> = {
  xiaoyi:
    "https://developer.huawei.com/consumer/cn/doc/service/openclaw-0000002518410344",
};

const BASE_FIELDS = [
  "enabled",
  "bot_prefix",
  "filter_tool_messages",
  "filter_thinking",
  "isBuiltin",
];

interface ChannelDrawerProps {
  open: boolean;
  activeKey: ChannelKey | null;
  activeLabel: string;
  form: FormInstance<Record<string, unknown>>;
  saving: boolean;
  initialValues: Record<string, unknown> | undefined;
  isBuiltin: boolean;
  onClose: () => void;
  onSubmit: (values: Record<string, unknown>) => void;
}

export function ChannelDrawer({
  open,
  activeKey,
  activeLabel,
  form,
  saving,
  initialValues,
  isBuiltin,
  onClose,
  onSubmit,
}: ChannelDrawerProps) {
  const { t } = useTranslation();
  const label = activeKey ? getChannelLabel(activeKey, t) : activeLabel;
  const { message } = useAppMessage();
  const selectedAgent = useAgentStore((state) => state.selectedAgent);
  const oneBotWebSocketUrl = `${window.location.origin.replace(
    /^http/i,
    "ws",
  )}/api/channels/onebot/websocket?agent_id=${encodeURIComponent(
    selectedAgent,
  )}`;

  // ── Access control fields (shared across multiple channels) ──────────────

  const renderAccessControlFields = () => (
    <>
      <Form.Item
        name="access_control_dm"
        label={t("channels.accessControlDm")}
        valuePropName="checked"
        tooltip={t("channels.accessControlDmTooltip")}
      >
        <Switch />
      </Form.Item>
      <Form.Item
        name="access_control_group"
        label={t("channels.accessControlGroup")}
        valuePropName="checked"
        tooltip={t("channels.accessControlGroupTooltip")}
      >
        <Switch />
      </Form.Item>
      <Form.Item
        name="require_mention"
        label={t("channels.requireMention")}
        valuePropName="checked"
        tooltip={t("channels.requireMentionTooltip")}
      >
        <Switch />
      </Form.Item>
    </>
  );

  // ── Builtin channel-specific fields ─────────────────────────────────────

  const renderBuiltinExtraFields = (key: ChannelKey) => {
    switch (key) {
      case "dingtalk":
        return (
          <>
            <ConfigProvider prefixCls="ant">
              <Alert
                type="info"
                showIcon
                message={t("channels.dingtalkSetupGuide")}
                style={{ marginBottom: 16 }}
              />
            </ConfigProvider>
            <QrcodeAuthBlock
              label={t("channels.dingtalkScanAuth")}
              buttonText={t("channels.dingtalkGetQrcode")}
              imageAlt="DingTalk QR Code"
              hintText={t("channels.dingtalkScanHint")}
              channel="dingtalk"
              successStatus="success"
              successCredentialKey="client_id"
              pollInterval={5000}
              onSuccess={(credentials) => {
                form.setFieldsValue({
                  client_id: credentials.client_id,
                  client_secret: credentials.client_secret,
                });
                message.success(t("channels.dingtalkAuthSuccess"));
              }}
              onError={(type) => {
                if (type === "expired") {
                  message.warning(t("channels.dingtalkQrcodeExpired"));
                } else {
                  message.error(t("channels.dingtalkQrcodeFailed"));
                }
              }}
            />
            <Form.Item
              name="client_id"
              label="Client ID"
              rules={[{ required: true }]}
            >
              <Input placeholder="dingxxxxx" />
            </Form.Item>
            <Form.Item
              name="client_secret"
              label="Client Secret"
              rules={[{ required: true }]}
            >
              <Input.Password />
            </Form.Item>
            <Form.Item
              name="message_type"
              label="Message Type"
              tooltip="markdown: regular messages; card: AI interactive card"
            >
              <Select
                options={[
                  { label: "markdown", value: "markdown" },
                  { label: "card", value: "card" },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="cron_message_type"
              label="Cron Message Type"
              tooltip="Message type for cron/scheduled task sends. Independent from the chat message type above."
            >
              <Select
                options={[
                  { label: "markdown", value: "markdown" },
                  { label: "card", value: "card" },
                ]}
              />
            </Form.Item>
            <Form.Item
              noStyle
              shouldUpdate={(prev, cur) =>
                prev.message_type !== cur.message_type ||
                prev.cron_message_type !== cur.cron_message_type
              }
            >
              {({ getFieldValue }) => {
                const needsCard =
                  getFieldValue("message_type") === "card" ||
                  getFieldValue("cron_message_type") === "card";
                if (!needsCard) return null;
                return (
                  <>
                    <Form.Item
                      name="card_template_id"
                      label="Card Template ID"
                      rules={[
                        {
                          required: true,
                          message:
                            "Please input card template id when message_type=card",
                        },
                      ]}
                    >
                      <Input placeholder="dt_card_template_xxx" />
                    </Form.Item>
                    <Form.Item
                      name="card_template_key"
                      label="Card Template Key"
                      tooltip="Must exactly match the template variable name"
                    >
                      <Input placeholder="content" />
                    </Form.Item>
                    <Form.Item
                      name="robot_code"
                      label="Robot Code"
                      tooltip="Recommended to configure explicitly for group chats"
                    >
                      <Input placeholder="robot code (default client_id)" />
                    </Form.Item>
                  </>
                );
              }}
            </Form.Item>
            <Form.Item
              name="at_sender_on_reply"
              label={t("channels.atSenderOnReply")}
              tooltip={t("channels.atSenderOnReplyTooltip")}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </>
        );

      case "feishu":
        return (
          <>
            <ConfigProvider prefixCls="ant">
              <Alert
                type="info"
                showIcon
                message={t("channels.feishuScanGuide")}
                style={{ marginBottom: 16 }}
              />
            </ConfigProvider>
            <QrcodeAuthBlock
              label={t("channels.feishuScanLogin")}
              buttonText={t("channels.feishuGetQrcode")}
              imageAlt="Feishu QR Code"
              hintText={t("channels.feishuScanHint")}
              channel="feishu"
              successStatus="success"
              successCredentialKey="app_id"
              pollInterval={2000}
              onSuccess={(credentials) => {
                form.setFieldsValue({
                  app_id: credentials.app_id,
                  app_secret: credentials.app_secret,
                });
                message.success(t("channels.feishuAuthSuccess"));
              }}
              onError={(type) => {
                if (type === "expired") {
                  message.warning(t("channels.feishuQrcodeExpired"));
                } else {
                  message.error(t("channels.feishuQrcodeFailed"));
                }
              }}
            />
            <Form.Item
              name="app_id"
              label="App ID"
              rules={[{ required: true }]}
            >
              <Input placeholder="cli_xxx" />
            </Form.Item>
            <Form.Item
              name="app_secret"
              label="App Secret"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="App Secret" />
            </Form.Item>
            <Form.Item name="encrypt_key" label="Encrypt Key">
              <Input placeholder="Optional, for event encryption" />
            </Form.Item>
            <Form.Item name="verification_token" label="Verification Token">
              <Input placeholder="Optional" />
            </Form.Item>
            <Form.Item
              name="share_session_in_group"
              label={t("channels.shareSessionInGroup")}
              valuePropName="checked"
              tooltip={t("channels.shareSessionInGroupTooltip")}
            >
              <Switch />
            </Form.Item>
          </>
        );

      case "qq":
        return (
          <>
            <ConfigProvider prefixCls="ant">
              <Alert
                type="info"
                showIcon
                message={t("channels.qqSetupGuide")}
                style={{ marginBottom: 16 }}
              />
            </ConfigProvider>
            <QrcodeAuthBlock
              label={t("channels.qqScanAuth")}
              buttonText={t("channels.qqGetQrcode")}
              imageAlt="QQ QR Code"
              hintText={t("channels.qqScanHint")}
              channel="qq"
              successStatus="success"
              successCredentialKey="app_id"
              pollInterval={2000}
              pollTimeout={300000}
              maxPollCount={180}
              onSuccess={(credentials) => {
                form.setFieldsValue({
                  app_id: credentials.app_id,
                  client_secret: credentials.client_secret,
                  user_openid: credentials.user_openid,
                });
                message.success(t("channels.qqAuthSuccess"));
              }}
              onError={(type) => {
                if (type === "expired") {
                  message.warning(t("channels.qqQrcodeExpired"));
                } else {
                  message.error(t("channels.qqQrcodeFailed"));
                }
              }}
            />
            <Form.Item
              name="app_id"
              label="App ID"
              rules={[{ required: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="client_secret"
              label="Client Secret"
              rules={[{ required: true }]}
            >
              <Input.Password />
            </Form.Item>
            <Form.Item name="user_openid" hidden>
              <Input />
            </Form.Item>
            <Form.Item
              name="ack_message"
              label={t("channels.ackMessage")}
              tooltip={t("channels.ackMessageTooltip")}
            >
              <Input placeholder={t("channels.ackMessagePlaceholder")} />
            </Form.Item>
          </>
        );

      case "wecom":
        return (
          <>
            <ConfigProvider prefixCls="ant">
              <Alert
                type="warning"
                showIcon
                message={t("channels.wecomSetupGuide")}
                style={{ marginBottom: 16 }}
              />
            </ConfigProvider>
            <QrcodeAuthBlock
              label={t("channels.wecomScanAuth")}
              buttonText={t("channels.loginWeCom")}
              imageAlt="WeCom QR Code"
              hintText={t("channels.wecomAuthHint")}
              channel="wecom"
              successStatus="success"
              successCredentialKey="bot_id"
              pollInterval={3000}
              onSuccess={(credentials) => {
                form.setFieldsValue({
                  bot_id: credentials.bot_id,
                  secret: credentials.secret,
                });
                message.success(t("channels.wecomAuthSuccess"));
              }}
              onError={() => {
                message.error(t("channels.wecomQrcodeFailed"));
              }}
            />
            <Form.Item
              name="bot_id"
              label="Bot ID"
              rules={[{ required: true, message: "Please input Bot ID" }]}
            >
              <Input placeholder="Bot ID from WeCom backend" />
            </Form.Item>
            <Form.Item
              name="secret"
              label="Secret"
              rules={[{ required: true, message: "Please input Secret" }]}
            >
              <Input.Password placeholder="Secret from WeCom backend" />
            </Form.Item>
            <Form.Item
              name="welcome_text"
              label={t("channels.welcomeText")}
              tooltip={t("channels.welcomeTextTooltip")}
            >
              <Input placeholder={t("channels.welcomeTextPlaceholder")} />
            </Form.Item>
            <Form.Item
              name="share_session_in_group"
              label={t("channels.shareSessionInGroup")}
              valuePropName="checked"
              tooltip={t("channels.shareSessionInGroupTooltip")}
            >
              <Switch />
            </Form.Item>
          </>
        );

      case "xiaoyi":
        return (
          <>
            <ConfigProvider prefixCls="ant">
              <Alert
                type="info"
                showIcon
                message={t("channels.xiaoyiSetupGuide")}
                style={{ marginBottom: 16 }}
              />
            </ConfigProvider>
            <Form.Item
              name="ak"
              label="Access Key (AK)"
              rules={[{ required: true, message: "Please input Access Key" }]}
            >
              <Input placeholder="Access Key from Huawei Developer Platform" />
            </Form.Item>
            <Form.Item
              name="sk"
              label="Secret Key (SK)"
              rules={[{ required: true, message: "Please input Secret Key" }]}
            >
              <Input.Password placeholder="Secret Key from Huawei Developer Platform" />
            </Form.Item>
            <Form.Item
              name="agent_id"
              label="Agent ID"
              rules={[{ required: true, message: "Please input Agent ID" }]}
            >
              <Input placeholder="Agent ID from XiaoYi platform" />
            </Form.Item>
          </>
        );

      case "wechat":
        return (
          <>
            <ConfigProvider prefixCls="ant">
              <Alert
                type="info"
                showIcon
                message={t("channels.wechatSetupGuide")}
                style={{ marginBottom: 16 }}
              />
              <Alert
                type="warning"
                showIcon
                message={t("channels.wechatContextTokenLimit")}
                style={{ marginBottom: 16 }}
              />
            </ConfigProvider>
            <QrcodeAuthBlock
              label={t("channels.wechatScanLogin")}
              buttonText={t("channels.wechatGetQrcode")}
              imageAlt="WeChat QR Code"
              hintText={t("channels.wechatScanHint")}
              channel="wechat"
              successStatus="confirmed"
              successCredentialKey="bot_token"
              pollInterval={2000}
              onSuccess={(credentials) => {
                form.setFieldsValue({ bot_token: credentials.bot_token });
                message.success(t("channels.wechatLoginSuccess"));
              }}
              onError={(type) => {
                if (type === "expired") {
                  message.warning(t("channels.wechatQrcodeExpired"));
                } else {
                  message.error(t("channels.wechatQrcodeFailed"));
                }
              }}
            />
            <Form.Item
              name="bot_token"
              label={t("channels.wechatBotToken")}
              tooltip={t("channels.wechatBotTokenTooltip")}
            >
              <Input.Password
                placeholder={t("channels.wechatBotTokenPlaceholder")}
              />
            </Form.Item>
            <Form.Item
              name="message_merge_enabled"
              label={t("channels.wechatMessageMerge")}
              valuePropName="checked"
              tooltip={t("channels.wechatMessageMergeTooltip")}
            >
              <Switch />
            </Form.Item>
            <Form.Item
              noStyle
              shouldUpdate={(prev, cur) =>
                prev.message_merge_enabled !== cur.message_merge_enabled
              }
            >
              {({ getFieldValue }) =>
                getFieldValue("message_merge_enabled") ? (
                  <Form.Item
                    name="message_merge_delay_ms"
                    label={t("channels.wechatMessageMergeDelayMs")}
                    tooltip={t("channels.wechatMessageMergeDelayMsTooltip")}
                    initialValue={0}
                    rules={[
                      {
                        validator: (_: unknown, value: unknown) => {
                          if (
                            value === null ||
                            value === undefined ||
                            value === ""
                          ) {
                            return Promise.resolve();
                          }
                          const num = Number(value);
                          if (!Number.isInteger(num) || num < 0) {
                            return Promise.reject(
                              new Error(
                                t(
                                  "channels.wechatMessageMergeDelayMsValidation",
                                ),
                              ),
                            );
                          }
                          return Promise.resolve();
                        },
                      },
                    ]}
                  >
                    <InputNumber
                      min={0}
                      step={100}
                      style={{ width: "100%" }}
                      placeholder="0"
                    />
                  </Form.Item>
                ) : null
              }
            </Form.Item>
          </>
        );

      case "yuanbao":
        return (
          <>
            <Form.Item
              name="app_id"
              label="App ID"
              rules={[{ required: true, message: "Please input App ID" }]}
            >
              <Input placeholder="App ID from Yuanbao platform" />
            </Form.Item>
            <Form.Item
              name="app_secret"
              label="App Secret"
              rules={[{ required: true, message: "Please input App Secret" }]}
            >
              <Input.Password placeholder="App Secret from Yuanbao platform" />
            </Form.Item>
            <Form.Item
              name="accept_bot_messages"
              label={t("channels.acceptBotMessages")}
              valuePropName="checked"
              tooltip={t("channels.acceptBotMessagesTooltip")}
            >
              <Switch />
            </Form.Item>
          </>
        );

      case "onebot":
        return (
          <>
            <Form.Item label="Reverse WebSocket URL">
              <Input readOnly value={oneBotWebSocketUrl} />
            </Form.Item>
            <Form.Item
              name="access_token"
              label="Access Token"
              rules={[{ required: true }]}
            >
              <Input.Password placeholder="Access token for authentication" />
            </Form.Item>
            <Form.Item
              name="share_session_in_group"
              label={t("channels.shareSessionInGroup")}
              valuePropName="checked"
              tooltip={t("channels.shareSessionInGroupTooltip")}
            >
              <Switch />
            </Form.Item>
          </>
        );

      default:
        return null;
    }
  };

  // ── Custom channel fields (key-value editor) ─────────────────────────────

  const renderCustomExtraFields = (
    values: Record<string, unknown> | undefined,
  ) => {
    if (!values) return null;
    const extraKeys = Object.keys(values).filter(
      (k) => !BASE_FIELDS.includes(k),
    );
    if (extraKeys.length === 0) return null;

    return (
      <>
        <div style={{ marginBottom: 8, fontWeight: 500 }}>Custom Fields</div>
        {extraKeys.map((fieldKey) => {
          const value = values[fieldKey];
          return (
            <Form.Item key={fieldKey} name={fieldKey} label={fieldKey}>
              {typeof value === "boolean" ? (
                <Switch />
              ) : typeof value === "number" ? (
                <InputNumber style={{ width: "100%" }} />
              ) : (
                <Input />
              )}
            </Form.Item>
          );
        })}
      </>
    );
  };

  // ── Drawer title ─────────────────────────────────────────────────────────

  const drawerTitle = (
    <div className={styles.drawerTitle}>
      <span>
        {label
          ? `${label} ${t("channels.settings")}`
          : t("channels.channelSettings")}
      </span>
      {activeKey && CHANNEL_VENDOR_DOC_URLS[activeKey] && (
          <Button
            type="text"
            size="small"
            icon={<LinkOutlined />}
            onClick={() =>
              openExternalLink(CHANNEL_VENDOR_DOC_URLS[activeKey]!)
            }
            className={styles.dingtalkDocBtn}
            style={{ color: "var(--mp-primary)" }}
          >
            {label} Doc
          </Button>
        )}
    </div>
  );

  // ── Render ───────────────────────────────────────────────────────────────

  const drawerFooter = (
    <div className={styles.formActions}>
      <Button onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="primary" loading={saving} onClick={() => form.submit()}>
        {t("common.save")}
      </Button>
    </div>
  );

  return (
    <Drawer
      width={420}
      placement="right"
      title={drawerTitle}
      open={open}
      onClose={onClose}
      destroyOnHidden
      footer={drawerFooter}
      key={activeKey} // Force remount when switching channels
    >
      {activeKey && (
        <Form
          form={form}
          layout="vertical"
          initialValues={initialValues}
          onFinish={onSubmit}
        >
          <Form.Item
            name="enabled"
            label={t("common.enabled")}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item name="bot_prefix" label="Bot Prefix">
            <Input placeholder="@bot" />
          </Form.Item>

          {activeKey !== "console" && (
            <>
              <Form.Item
                name="filter_tool_messages"
                label={t("channels.filterToolMessages")}
                valuePropName="checked"
                tooltip={t("channels.filterToolMessagesTooltip")}
              >
                <Switch />
              </Form.Item>
              <Form.Item
                name="filter_thinking"
                label={t("channels.filterThinking")}
                valuePropName="checked"
                tooltip={t("channels.filterThinkingTooltip")}
              >
                <Switch />
              </Form.Item>
            </>
          )}

          {(activeKey === "wecom" ||
            activeKey === "dingtalk" ||
            activeKey === "feishu") && (
            <Form.Item
              name="streaming_enabled"
              label={t("channels.streamingEnabled")}
              valuePropName="checked"
              tooltip={
                activeKey === "dingtalk"
                  ? t("channels.streamingEnabledDingtalkHint")
                  : activeKey === "feishu"
                  ? t("channels.streamingEnabledFeishuHint")
                  : undefined
              }
            >
              <Switch />
            </Form.Item>
          )}

          {isBuiltin
            ? renderBuiltinExtraFields(activeKey)
            : renderCustomExtraFields(initialValues)}

          {CHANNELS_WITH_ACCESS_CONTROL.includes(activeKey) &&
            renderAccessControlFields()}

          {activeKey !== "console" && (
            <Form.Item
              name="no_text_debounce"
              label={t("channels.noTextDebounce")}
              valuePropName="checked"
              tooltip={t("channels.noTextDebounceTooltip")}
              initialValue={true}
            >
              <Switch />
            </Form.Item>
          )}
        </Form>
      )}
    </Drawer>
  );
}
