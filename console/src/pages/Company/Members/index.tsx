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
  Tooltip,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { Edit3, RefreshCw, Search, Trash2, UserPlus } from "lucide-react";
import { useTranslation } from "react-i18next";
import { companyApi, type EnterpriseUserInput } from "../../../api";
import type { AuthUser } from "../../../api/modules/auth";
import { skillApi } from "../../../api/modules/skill";
import { hasPermission } from "../../../auth/authorization";
import { PageHeader } from "../../../components/PageHeader";
import { useAuth } from "../../../contexts/AuthContext";
import { useAppMessage } from "../../../hooks/useAppMessage";
import accessStyles from "../accessControl.module.less";
import { getRoleName } from "../rolePresentation";

type UserFormValues = EnterpriseUserInput;
type StatusFilter = "all" | "enabled" | "disabled";

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export default function EnterpriseMembers() {
  const { t } = useTranslation();
  const { user: currentUser } = useAuth();
  const { message } = useAppMessage();
  const [modal, modalContextHolder] = Modal.useModal();
  const [form] = Form.useForm<UserFormValues>();
  const [users, setUsers] = useState<AuthUser[]>([]);
  const [roleOptions, setRoleOptions] = useState<
    Array<{ label: string; value: string }>
  >([]);
  const [roleLabels, setRoleLabels] = useState<Record<string, string>>({});
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deletingUserId, setDeletingUserId] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AuthUser | null>(null);
  const [skillOptions, setSkillOptions] = useState<
    Array<{ label: string; value: string; title?: string }>
  >([]);
  const canManage = hasPermission(currentUser, "users:manage");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const poolSkillsPromise = canManage
        ? skillApi.listSkillPoolSkills().catch(() => {
            message.warning(t("enterprise.members.skillsLoadFailed"));
            return [];
          })
        : Promise.resolve([]);
      const [userList, catalogue, poolSkills] = await Promise.all([
        companyApi.listUsers(),
        companyApi.listRoles(),
        poolSkillsPromise,
      ]);
      setUsers(userList);
      const nextRoleOptions = catalogue.roles.map((role) => ({
        value: role.code,
        label: getRoleName(role.code, role.name, t),
      }));
      setRoleOptions(nextRoleOptions);
      setRoleLabels(
        Object.fromEntries(
          nextRoleOptions.map((option) => [option.value, option.label]),
        ),
      );
      setSkillOptions(
        poolSkills
          .map((skill) => ({
            value: skill.name,
            label: skill.name,
            title: skill.description,
          }))
          .sort((left, right) => left.label.localeCompare(right.label)),
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
  }, [canManage, message, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return users.filter((entry) => {
      if (statusFilter === "enabled" && !entry.enabled) return false;
      if (statusFilter === "disabled" && entry.enabled) return false;
      if (!normalized) return true;
      return [
        entry.username,
        entry.display_name,
        ...entry.roles,
        ...entry.roles.map((role) => roleLabels[role] || role),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(normalized));
    });
  }, [query, roleLabels, statusFilter, users]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      enabled: true,
      roles: ["EMPLOYEE"],
      default_skills: [],
    });
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

  const confirmDelete = (entry: AuthUser) => {
    if (!canManage || entry.id === currentUser?.id) return;
    modal.confirm({
      title: t("enterprise.members.deleteTitle", {
        name: entry.display_name || entry.username,
      }),
      content: t("enterprise.members.deleteConfirm", {
        username: entry.username,
      }),
      okText: t("common.delete"),
      cancelText: t("common.cancel"),
      okButtonProps: { danger: true },
      onOk: async () => {
        setDeletingUserId(entry.id);
        try {
          await companyApi.deleteUser(entry.id);
          if (editing?.id === entry.id) {
            setModalOpen(false);
            setEditing(null);
          }
          message.success(t("enterprise.members.deleted"));
          await load();
        } catch (error) {
          message.error(
            error instanceof Error
              ? error.message
              : t("enterprise.members.deleteFailed"),
          );
          throw error;
        } finally {
          setDeletingUserId(null);
        }
      },
    });
  };

  const columns: ColumnsType<AuthUser> = [
    {
      title: t("enterprise.members.account"),
      dataIndex: "username",
      render: (username: string, entry) => (
        <div className={accessStyles.nameCell}>
          <div className={accessStyles.primaryText}>
            {entry.display_name || username}
          </div>
          <div className={accessStyles.secondaryText}>{username}</div>
        </div>
      ),
    },
    {
      title: t("enterprise.members.roles"),
      dataIndex: "roles",
      render: (roles: string[]) => (
        <Space size={[4, 4]} wrap>
          {roles.map((role) => (
            <Tag className={accessStyles.roleTag} key={role} title={role}>
              {roleLabels[role] || getRoleName(role, role, t)}
            </Tag>
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
      width: 190,
      render: (_, entry) =>
        canManage ? (
          <Space size={2}>
            <Button
              type="link"
              icon={<Edit3 size={15} />}
              onClick={() => openEdit(entry)}
            >
              {t("common.edit")}
            </Button>
            <Tooltip
              title={
                entry.id === currentUser?.id
                  ? t("enterprise.members.cannotDeleteSelf")
                  : t("common.delete")
              }
            >
              <span>
                <Button
                  type="link"
                  danger
                  disabled={entry.id === currentUser?.id}
                  loading={deletingUserId === entry.id}
                  icon={<Trash2 size={15} />}
                  onClick={() => confirmDelete(entry)}
                >
                  {t("common.delete")}
                </Button>
              </span>
            </Tooltip>
          </Space>
        ) : null,
    },
  ];

  return (
    <div className={accessStyles.accessPage}>
      {modalContextHolder}
      <PageHeader
        items={[
          { title: t("enterprise.nav.administration") },
          { title: t("enterprise.members.title") },
        ]}
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
      <div className={accessStyles.accessBody}>
        <div className={accessStyles.accessToolbar}>
          <div className={accessStyles.filterGroup}>
            <Input
              className={accessStyles.searchInput}
              allowClear
              prefix={<Search size={15} />}
              placeholder={t("enterprise.members.search")}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
            <Select<StatusFilter>
              className={accessStyles.typeSelect}
              value={statusFilter}
              onChange={setStatusFilter}
              options={[
                { value: "all", label: t("enterprise.members.allStatuses") },
                { value: "enabled", label: t("common.enabled") },
                { value: "disabled", label: t("common.disabled") },
              ]}
            />
          </div>
          <span className={accessStyles.resultCount}>
            {t("enterprise.members.filteredCount", {
              visible: filtered.length,
              total: users.length,
            })}
          </span>
        </div>
        <Table
          className={accessStyles.accessTable}
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={filtered}
          locale={{
            emptyText: <Empty description={t("enterprise.members.empty")} />,
          }}
          pagination={{
            pageSize: 20,
            showSizeChanger: true,
            hideOnSinglePage: true,
          }}
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
                  (value.length >= 12 &&
                    /[A-Za-z]/.test(value) &&
                    /\d/.test(value))
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
          {!editing && (
            <Form.Item
              name="default_skills"
              label={t("enterprise.members.defaultSkills")}
              extra={t("enterprise.members.defaultSkillsHint")}
            >
              <Select
                mode="multiple"
                allowClear
                showSearch
                optionFilterProp="label"
                maxTagCount="responsive"
                options={skillOptions}
                placeholder={t("enterprise.members.defaultSkillsPlaceholder")}
              />
            </Form.Item>
          )}
          <Form.Item
            name="enabled"
            label={t("enterprise.members.status")}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          {editing && canManage && (
            <Form.Item>
              <Tooltip
                title={
                  editing.id === currentUser?.id
                    ? t("enterprise.members.cannotDeleteSelf")
                    : undefined
                }
              >
                <Button
                  block
                  danger
                  disabled={editing.id === currentUser?.id}
                  loading={deletingUserId === editing.id}
                  icon={<Trash2 size={15} />}
                  onClick={() => confirmDelete(editing)}
                >
                  {t("enterprise.members.delete")}
                </Button>
              </Tooltip>
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
}
