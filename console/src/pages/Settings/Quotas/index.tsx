import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { Gauge, Plus, RefreshCw, Save, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { quotaApi, type QuotaPolicy, type QuotaPolicyInput, type QuotaScope } from "../../../api";
import { PageHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import { hasPermission } from "../../../auth/authorization";
import { useAuth } from "../../../contexts/AuthContext";
import styles from "./index.module.less";

const SCOPE_OPTIONS: Array<{ value: QuotaScope; label: string }> = [
  { value: "ENTERPRISE", label: "Enterprise" },
  { value: "USER", label: "User" },
  { value: "PROJECT", label: "Project" },
  { value: "AGENT", label: "Agent" },
];

const emptyPolicy: QuotaPolicyInput = {
  max_concurrent: 0,
  daily_task_limit: 0,
  daily_token_limit: 0,
  task_token_limit: 0,
  max_runtime_seconds: 3600,
};

type QuotaRow = QuotaPolicy & { isDefault?: boolean };

function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function QuotasPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { message } = useAppMessage();
  const [form] = Form.useForm<QuotaPolicyInput>();
  const [policies, setPolicies] = useState<QuotaPolicy[]>([]);
  const [defaults, setDefaults] = useState<Record<QuotaScope, QuotaPolicy>>({} as Record<QuotaScope, QuotaPolicy>);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [scopeType, setScopeType] = useState<QuotaScope>("USER");
  const [scopeId, setScopeId] = useState("");
  const [editing, setEditing] = useState<QuotaPolicy | null>(null);
  const canManage = hasPermission(user, "system:manage");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await quotaApi.list();
      setPolicies(result.policies || []);
      setDefaults(result.defaults || ({} as Record<QuotaScope, QuotaPolicy>));
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.quotas.loadFailed", { defaultValue: "Failed to load quota policies" }));
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { void load(); }, [load]);

  const rows = useMemo(() => {
    const configured = new Set(policies.map((policy) => `${policy.scope_type}:${policy.scope_id}`));
    return [
      ...SCOPE_OPTIONS
        .map(({ value }) => defaults[value])
        .filter(Boolean)
        .filter((policy) => !configured.has(`${policy.scope_type}:${policy.scope_id}`))
        .map((policy) => ({ ...policy, isDefault: true })),
      ...policies.map((policy) => ({ ...policy, isDefault: false })),
    ];
  }, [defaults, policies]);

  const openCreate = () => {
    setEditing(null);
    setScopeType("USER");
    setScopeId("");
    form.setFieldsValue(emptyPolicy);
    setModalOpen(true);
  };

  const openEdit = (policy: QuotaRow) => {
    setEditing(policy.isDefault ? null : policy);
    setScopeType(policy.scope_type);
    setScopeId(policy.isDefault && policy.scope_type !== "ENTERPRISE" ? "" : policy.scope_id);
    form.setFieldsValue({
      max_concurrent: policy.max_concurrent,
      daily_task_limit: policy.daily_task_limit,
      daily_token_limit: policy.daily_token_limit,
      task_token_limit: policy.task_token_limit,
      max_runtime_seconds: policy.max_runtime_seconds,
    });
    setModalOpen(true);
  };

  const save = async (values: QuotaPolicyInput) => {
    const id = scopeType === "ENTERPRISE" ? "default" : scopeId.trim();
    if (!id) {
      message.error(t("enterprise.quotas.scopeRequired", { defaultValue: "A scope identifier is required" }));
      return;
    }
    setSaving(true);
    try {
      await quotaApi.save(scopeType, id, values);
      message.success(t("enterprise.quotas.saved", { defaultValue: "Quota policy saved" }));
      setModalOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.quotas.saveFailed", { defaultValue: "Failed to save quota policy" }));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (policy: QuotaPolicy) => {
    Modal.confirm({
      title: t("enterprise.quotas.deleteTitle", { defaultValue: "Remove quota override?" }),
      content: `${policy.scope_type}:${policy.scope_id}`,
      okButtonProps: { danger: true },
      onOk: async () => {
        await quotaApi.remove(policy.scope_type, policy.scope_id);
        message.success(t("enterprise.quotas.deleted", { defaultValue: "Quota override removed" }));
        await load();
      },
    });
  };

  const columns: ColumnsType<QuotaRow> = [
    {
      title: t("enterprise.quotas.scope", { defaultValue: "Scope" }),
      key: "scope",
      render: (_, row) => <Space size={6}><Tag>{row.scope_type}</Tag><span>{row.scope_id}</span>{row.isDefault && <Tag bordered={false}>default</Tag>}</Space>,
    },
    { title: t("enterprise.quotas.concurrent", { defaultValue: "Concurrent" }), dataIndex: "max_concurrent", width: 115, render: (value: number) => value || t("enterprise.quotas.unlimited", { defaultValue: "Unlimited" }) },
    { title: t("enterprise.quotas.dailyTasks", { defaultValue: "Daily tasks" }), dataIndex: "daily_task_limit", width: 120, render: (value: number) => value || t("enterprise.quotas.unlimited", { defaultValue: "Unlimited" }) },
    { title: t("enterprise.quotas.dailyTokens", { defaultValue: "Daily tokens" }), dataIndex: "daily_token_limit", width: 125, render: (value: number) => value || t("enterprise.quotas.unlimited", { defaultValue: "Unlimited" }) },
    { title: t("enterprise.quotas.taskTokens", { defaultValue: "Task tokens" }), dataIndex: "task_token_limit", width: 120, render: (value: number) => value || t("enterprise.quotas.unlimited", { defaultValue: "Unlimited" }) },
    { title: t("enterprise.quotas.runtime", { defaultValue: "Runtime (s)" }), dataIndex: "max_runtime_seconds", width: 120 },
    { title: t("enterprise.quotas.updatedAt", { defaultValue: "Updated" }), dataIndex: "updated_at", width: 180, render: formatTime },
    {
      title: t("common.actions"), key: "actions", width: 180,
      render: (_, row) => canManage && <Space size={2}><Button type="link" icon={<Save size={15} />} onClick={() => openEdit(row)}>{t("common.edit")}</Button>{!row.isDefault && <Button type="link" danger icon={<Trash2 size={15} />} onClick={() => void remove(row)}>{t("common.delete")}</Button>}</Space>,
    },
  ];

  return <div className={styles.page}>
    <PageHeader parent={t("nav.settings")} current={t("enterprise.quotas.title", { defaultValue: "Quota policies" })} extra={<Space><Button icon={<RefreshCw size={15} />} onClick={() => void load()}>{t("common.refresh")}</Button>{canManage && <Button type="primary" icon={<Plus size={15} />} onClick={openCreate}>{t("enterprise.quotas.create", { defaultValue: "Add override" })}</Button>}</Space>} />
    <div className={styles.content}>
      <div className={styles.intro}><Gauge size={18} /><span>{t("enterprise.quotas.hint", { defaultValue: "Limits are applied in order: enterprise, user, project and Agent. Zero means unlimited." })}</span></div>
      <Table rowKey={(row) => `${row.scope_type}:${row.scope_id}`} loading={loading} columns={columns} dataSource={rows} pagination={{ pageSize: 20, showSizeChanger: false }} />
    </div>
    <Modal open={modalOpen} title={editing ? t("enterprise.quotas.edit", { defaultValue: "Edit quota override" }) : t("enterprise.quotas.create", { defaultValue: "Add quota override" })} okText={t("common.save")} confirmLoading={saving} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={save} initialValues={emptyPolicy}>
        <Space.Compact block><Form.Item label={t("enterprise.quotas.scopeType", { defaultValue: "Scope type" })} required><Select value={scopeType} onChange={setScopeType} options={SCOPE_OPTIONS} disabled={Boolean(editing)} /></Form.Item><Form.Item label={t("enterprise.quotas.scopeId", { defaultValue: "Scope ID" })} required><Input value={scopeType === "ENTERPRISE" ? "default" : scopeId} onChange={(event) => setScopeId(event.target.value)} disabled={scopeType === "ENTERPRISE" || Boolean(editing)} placeholder="user/project/agent id" /></Form.Item></Space.Compact>
        <div className={styles.formGrid}>
          {(["max_concurrent", "daily_task_limit", "daily_token_limit", "task_token_limit", "max_runtime_seconds"] as const).map((field) => <Form.Item key={field} name={field} label={t(`enterprise.quotas.fields.${field}`, { defaultValue: field.replaceAll("_", " ") })} rules={[{ type: "number", min: 0 }]}><InputNumber min={0} precision={0} style={{ width: "100%" }} /></Form.Item>)}
        </div>
      </Form>
    </Modal>
  </div>;
}
