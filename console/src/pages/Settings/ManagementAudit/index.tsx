import { useCallback, useEffect, useState } from "react";
import { Button, Input, Space, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import { RefreshCw, Search, ShieldCheck } from "lucide-react";
import { useTranslation } from "react-i18next";
import { auditApi, type ManagementAuditEvent } from "../../../api";
import { PageHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import styles from "./index.module.less";

function time(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

function json(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2);
}

export default function ManagementAuditPage() {
  const { t } = useTranslation();
  const { message } = useAppMessage();
  const [rows, setRows] = useState<ManagementAuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [verifying, setVerifying] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await auditApi.listManagement({ limit: 200 }));
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.audit.loadFailed", { defaultValue: "Failed to load management audit" }));
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { void load(); }, [load]);

  const verify = async () => {
    setVerifying(true);
    try {
      const result = await auditApi.verifyManagement();
      if (result.valid) message.success(t("enterprise.audit.chainValid", { count: result.events_checked, defaultValue: `Audit chain valid (${result.events_checked} events)` }));
      else message.error(t("enterprise.audit.chainBroken", { id: result.broken_event_id, defaultValue: `Audit chain is broken at ${result.broken_event_id}` }));
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.audit.verifyFailed", { defaultValue: "Failed to verify audit chain" }));
    } finally {
      setVerifying(false);
    }
  };

  const filtered = rows.filter((row) => {
    const needle = query.trim().toLowerCase();
    return !needle || [row.actor_username, row.action, row.target_type, row.target_id, row.request_id]
      .some((value) => String(value || "").toLowerCase().includes(needle));
  });

  const columns: ColumnsType<ManagementAuditEvent> = [
    { title: t("enterprise.audit.time", { defaultValue: "Time" }), dataIndex: "occurred_at", width: 180, render: time },
    { title: t("enterprise.audit.actor", { defaultValue: "Actor" }), dataIndex: "actor_username", width: 150 },
    { title: t("enterprise.audit.action", { defaultValue: "Action" }), dataIndex: "action", width: 90, render: (value: string) => <Tag>{value}</Tag> },
    { title: t("enterprise.audit.target", { defaultValue: "Target" }), key: "target", render: (_, row) => <div><strong>{row.target_type}</strong><div className={styles.secondary}>{row.target_id}</div></div> },
    { title: t("enterprise.audit.result", { defaultValue: "Result" }), dataIndex: "result", width: 120, render: (value: string) => <Tag color={value === "SUCCESS" ? "success" : "error"}>{value}</Tag> },
    { title: t("enterprise.audit.request", { defaultValue: "Request ID" }), dataIndex: "request_id", width: 180, ellipsis: true },
  ];

  return <div className={styles.page}>
    <PageHeader parent={t("nav.settings")} current={t("enterprise.audit.title", { defaultValue: "Management audit" })} extra={<Space><Button icon={<ShieldCheck size={15} />} loading={verifying} onClick={() => void verify()}>{t("enterprise.audit.verify", { defaultValue: "Verify chain" })}</Button><Button icon={<RefreshCw size={15} />} onClick={() => void load()}>{t("common.refresh")}</Button></Space>} />
    <div className={styles.content}>
      <Input allowClear prefix={<Search size={15} />} value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("enterprise.audit.search", { defaultValue: "Search actor, action, target, or request ID" })} className={styles.search} />
      <Table rowKey="event_id" loading={loading} columns={columns} dataSource={filtered} pagination={{ pageSize: 20, showSizeChanger: true }} scroll={{ x: 980 }} expandable={{ expandedRowRender: (row) => <div className={styles.details}><section><strong>Before</strong><pre>{json(row.before)}</pre></section><section><strong>After</strong><pre>{json(row.after)}</pre></section><section><strong>Metadata</strong><pre>{json(row.metadata)}</pre></section></div> }} />
    </div>
  </div>;
}
