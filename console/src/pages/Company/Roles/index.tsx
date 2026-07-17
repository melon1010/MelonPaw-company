import { useCallback, useEffect, useState } from "react";
import { Button, Checkbox, Form, Input, Modal, Space, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import { Edit3, Plus, RefreshCw, ShieldCheck } from "lucide-react";
import { useTranslation } from "react-i18next";
import { companyApi, type EnterpriseRole, type EnterpriseRoleInput } from "../../../api";
import { hasPermission } from "../../../auth/authorization";
import { PageHeader } from "../../../components/PageHeader";
import { useAuth } from "../../../contexts/AuthContext";
import { useAppMessage } from "../../../hooks/useAppMessage";
import styles from "../company.module.less";

export default function RolesPage() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const { message } = useAppMessage();
  const [form] = Form.useForm<EnterpriseRoleInput>();
  const [roles, setRoles] = useState<EnterpriseRole[]>([]);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<EnterpriseRole | null>(null);
  const canManage = hasPermission(user, "roles:manage");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await companyApi.listRoles();
      setRoles(result.roles);
      setPermissions(result.permissions);
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.roles.loadFailed", { defaultValue: "Failed to load roles" }));
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => { void load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ permissions: [] });
    setOpen(true);
  };

  const openEdit = (role: EnterpriseRole) => {
    if (role.system_role) return;
    setEditing(role);
    form.setFieldsValue({ code: role.code, name: role.name, description: role.description, permissions: role.permissions });
    setOpen(true);
  };

  const save = async (values: EnterpriseRoleInput) => {
    setSaving(true);
    try {
      if (editing) await companyApi.updateRole(editing.code, { name: values.name, description: values.description, permissions: values.permissions || [] });
      else await companyApi.createRole({ ...values, code: values.code.trim().toUpperCase(), permissions: values.permissions || [] });
      message.success(t("enterprise.roles.saved", { defaultValue: "Role saved" }));
      setOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : t("enterprise.roles.saveFailed", { defaultValue: "Failed to save role" }));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<EnterpriseRole> = [
    { title: t("enterprise.roles.code", { defaultValue: "Code" }), dataIndex: "code", width: 180, render: (value: string, row) => <Space><Tag>{value}</Tag>{row.system_role && <Tag color="blue">{t("enterprise.roles.system", { defaultValue: "System" })}</Tag>}</Space> },
    { title: t("enterprise.roles.name", { defaultValue: "Name" }), dataIndex: "name" },
    { title: t("enterprise.roles.description", { defaultValue: "Description" }), dataIndex: "description" },
    { title: t("enterprise.roles.permissions", { defaultValue: "Permissions" }), dataIndex: "permissions", render: (items: string[]) => <Space size={[4, 4]} wrap>{items.map((item) => <Tag key={item}>{item}</Tag>)}</Space> },
    { title: t("common.actions"), key: "actions", width: 120, render: (_, row) => canManage && !row.system_role ? <Button type="link" icon={<Edit3 size={15} />} onClick={() => openEdit(row)}>{t("common.edit")}</Button> : null },
  ];

  return <div className={styles.page}>
    <PageHeader current={t("enterprise.roles.title", { defaultValue: "Roles & permissions" })} extra={<Space><Button icon={<RefreshCw size={15} />} onClick={() => void load()}>{t("common.refresh")}</Button>{canManage && <Button type="primary" icon={<Plus size={15} />} onClick={openCreate}>{t("enterprise.roles.create", { defaultValue: "Create role" })}</Button>}</Space>} />
    <div className={styles.body}>
      <div className={styles.toolbar}><Space><ShieldCheck size={18} /><span>{t("enterprise.roles.hint", { defaultValue: "System roles are read-only. Custom roles can grant only permissions available to the editor." })}</span></Space></div>
      <Table rowKey="code" loading={loading} columns={columns} dataSource={roles} pagination={{ pageSize: 20, showSizeChanger: false }} scroll={{ x: 760 }} />
    </div>
    <Modal open={open} title={editing ? t("enterprise.roles.edit", { defaultValue: "Edit role" }) : t("enterprise.roles.create", { defaultValue: "Create role" })} okText={t("common.save")} confirmLoading={saving} onCancel={() => setOpen(false)} onOk={() => form.submit()} destroyOnClose>
      <Form form={form} layout="vertical" onFinish={save}>
        <Form.Item name="code" label={t("enterprise.roles.code", { defaultValue: "Code" })} rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{1,63}$/ }]}><Input disabled={Boolean(editing)} /></Form.Item>
        <Form.Item name="name" label={t("enterprise.roles.name", { defaultValue: "Name" })} rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="description" label={t("enterprise.roles.description", { defaultValue: "Description" })}><Input.TextArea rows={3} /></Form.Item>
        <Form.Item name="permissions" label={t("enterprise.roles.permissions", { defaultValue: "Permissions" })}><Checkbox.Group options={permissions} /></Form.Item>
      </Form>
    </Modal>
  </div>;
}
