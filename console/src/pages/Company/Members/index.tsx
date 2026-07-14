import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { Edit3, RefreshCw, Search, UserPlus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { companyApi, type EnterpriseUserInput } from "../../../api";
import type { AuthUser } from "../../../api/modules/auth";
import { hasPermission } from "../../../auth/authorization";
import { PageHeader } from "../../../components/PageHeader";
import { useAuth } from "../../../contexts/AuthContext";
import { useAppMessage } from "../../../hooks/useAppMessage";
import styles from "../company.module.less";

type UserFormValues = EnterpriseUserInput;

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function EnterpriseMembers() {
  const { t } = useTranslation();
  const { user: currentUser } = useAuth();
  const { message } = useAppMessage();
  const [form] = Form.useForm<UserFormValues>();
  const [users, setUsers] = useState<AuthUser[]>([]);
  const [roleOptions, setRoleOptions] = useState<
    Array<{ label: string; value: string }>
  >([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AuthUser | null>(null);
  const canManage = hasPermission(currentUser, "users:manage");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [userList, catalogue] = await Promise.all([
        companyApi.listUsers(),
        companyApi.listRoles(),
      ]);
      setUsers(userList);
      setRoleOptions(
        catalogue.roles.map((role) => ({
          value: role.code,
          label: `${role.name} (${role.code})`,
        })),
      );
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.members.loadFailed"),
      );
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return users;
    return users.filter((entry) =>
      [entry.username, entry.display_name, ...entry.roles]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(normalized)),
    );
  }, [query, users]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ enabled: true, roles: ["EMPLOYEE"] });
    setModalOpen(true);
  };

  const openEdit = (entry: AuthUser) => {
    setEditing(entry);
    form.setFieldsValue({
      username: entry.username,
      display_name: entry.display_name || "",
      password: "",
      enabled: entry.enabled,
      roles: entry.roles,
    });
    setModalOpen(true);
  };

  const save = async (values: UserFormValues) => {
    setSaving(true);
    try {
      if (editing) {
        await companyApi.updateUser(editing.id, {
          display_name: values.display_name?.trim(),
          enabled: values.enabled,
          ...(values.password ? { password: values.password } : {}),
        });
        await companyApi.replaceUserRoles(editing.id, values.roles);
      } else {
        await companyApi.createUser({
          ...values,
          username: values.username.trim(),
          display_name: values.display_name?.trim(),
        });
      }
      message.success(t("enterprise.members.saved"));
      setModalOpen(false);
      await load();
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.members.saveFailed"),
      );
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<AuthUser> = [
    {
      title: t("enterprise.members.account"),
      dataIndex: "username",
      render: (username: string, entry) => (
        <div className={styles.nameCell}>
          <div className={styles.primaryText}>
            {entry.display_name || username}
          </div>
          <div className={styles.secondaryText}>{username}</div>
        </div>
      ),
    },
    {
      title: t("enterprise.members.roles"),
      dataIndex: "roles",
      render: (roles: string[]) => (
        <Space size={[4, 4]} wrap>
          {roles.map((role) => (
            <Tag key={role}>{role}</Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t("enterprise.members.status"),
      dataIndex: "enabled",
      width: 110,
      render: (enabled: boolean) => (
        <Tag color={enabled ? "success" : "default"}>
          {enabled ? t("common.enabled") : t("common.disabled")}
        </Tag>
      ),
    },
    {
      title: t("enterprise.members.createdAt"),
      dataIndex: "created_at",
      width: 190,
      render: formatTime,
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: 90,
      render: (_, entry) =>
        canManage ? (
          <Button
            type="link"
            icon={<Edit3 size={15} />}
            onClick={() => openEdit(entry)}
          >
            {t("common.edit")}
          </Button>
        ) : null,
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        current={t("enterprise.members.title")}
        afterBreadcrumb={
          <Tag>{t("common.total", { count: filtered.length })}</Tag>
        }
        extra={
          <Space>
            <Button icon={<RefreshCw size={15} />} onClick={() => void load()}>
              {t("common.refresh")}
            </Button>
            {canManage && (
              <Button
                type="primary"
                icon={<UserPlus size={16} />}
                onClick={openCreate}
              >
                {t("enterprise.members.create")}
              </Button>
            )}
          </Space>
        }
      />
      <div className={styles.body}>
        <div className={styles.toolbar}>
          <Input
            allowClear
            prefix={<Search size={15} />}
            placeholder={t("enterprise.members.search")}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            style={{ width: 340, maxWidth: "100%" }}
          />
        </div>
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={filtered}
          locale={{
            emptyText: <Empty description={t("enterprise.members.empty")} />,
          }}
          pagination={{ pageSize: 20, showSizeChanger: true }}
          scroll={{ x: 820 }}
        />
      </div>

      <Modal
        open={modalOpen}
        title={
          editing
            ? t("enterprise.members.edit")
            : t("enterprise.members.create")
        }
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => void save(values)}
        >
          <Form.Item
            name="username"
            label={t("enterprise.members.username")}
            rules={[
              { required: true },
              {
                pattern: /^[a-z0-9][a-z0-9._-]{2,63}$/,
                message: t("enterprise.members.usernameRule"),
              },
            ]}
          >
            <Input
              disabled={Boolean(editing)}
              maxLength={64}
              autoComplete="off"
            />
          </Form.Item>
          <Form.Item
            name="display_name"
            label={t("enterprise.members.displayName")}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="password"
            label={
              editing
                ? t("enterprise.members.newPassword")
                : t("enterprise.members.password")
            }
            rules={[
              { required: !editing },
              {
                validator: (_, value?: string) =>
                  !value ||
                  (value.length >= 12 && /[A-Za-z]/.test(value) && /\d/.test(value))
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(t("enterprise.members.passwordRule")),
                      ),
              },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="roles"
            label={t("enterprise.members.roles")}
            rules={[{ required: true, type: "array", min: 1 }]}
          >
            <Select mode="multiple" options={roleOptions} />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t("enterprise.members.status")}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
