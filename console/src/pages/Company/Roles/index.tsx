import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tree,
  type TreeDataNode,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  Edit3,
  Eye,
  KeyRound,
  LockKeyhole,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  companyApi,
  type EnterpriseRole,
  type EnterpriseRoleInput,
} from "../../../api";
import {
  CONFIGURABLE_MENU_PERMISSIONS,
  hasPermission,
} from "../../../auth/authorization";
import { PageHeader } from "../../../components/PageHeader";
import ResponsiveTable from "../../../components/ResponsiveTable";
import { useAuth } from "../../../contexts/AuthContext";
import { useAppMessage } from "../../../hooks/useAppMessage";
import { renderIcon, resolveLabel } from "../../../layouts/registry/adapter";
import { BUILTIN_MENU } from "../../../layouts/registry/builtinMenu";
import accessStyles from "../accessControl.module.less";
import {
  getPermissionName,
  getRoleDescription,
  getRoleName,
} from "../rolePresentation";

type EditorMode = "create" | "edit" | "view";
type RoleFilter = "all" | "system" | "custom";

const CONFIGURABLE_PERMISSION_CODES = new Set(
  CONFIGURABLE_MENU_PERMISSIONS.map((item) => item.permission),
);

function configurablePermissions(permissions: string[]): string[] {
  return permissions.filter((permission) =>
    CONFIGURABLE_PERMISSION_CODES.has(permission),
  );
}

interface PermissionTreeProps {
  permissions: string[];
  value?: string[];
  onChange?: (value: string[]) => void;
  disabled?: boolean;
}

function PermissionTree({
  permissions,
  value = [],
  onChange,
  disabled = false,
}: PermissionTreeProps) {
  const { t } = useTranslation();
  const permissionSet = useMemo(() => new Set(permissions), [permissions]);
  const selectedPermissions = useMemo(
    () => value.filter((permission) => permissionSet.has(permission)),
    [permissionSet, value],
  );
  const treeData = useMemo<TreeDataNode[]>(() => {
    return (["projects", "administration"] as const).map((section) => {
      const entries = CONFIGURABLE_MENU_PERMISSIONS.filter(
        (entry) => entry.section === section && permissionSet.has(entry.permission),
      );
      return {
        key: `section:${section}`,
        checkable: false,
        selectable: false,
        title: (
          <span className={accessStyles.treeGroupTitle}>
            {section === "projects"
              ? t("enterprise.nav.projects")
              : t("enterprise.nav.administration")}
            <span className={accessStyles.treeGroupCount}>{entries.length}</span>
          </span>
        ),
        children: entries.map((entry) => {
          const menu = BUILTIN_MENU.find((item) => item.route === entry.routeId);
          return {
            key: entry.permission,
            disabled,
            icon: renderIcon(menu?.icon, 16),
            title: (
              <span
                className={accessStyles.permissionTitle}
                title={entry.permission}
              >
                {menu ? resolveLabel(menu.label) : getPermissionName(entry.permission, t)}
              </span>
            ),
          };
        }),
      };
    });
  }, [disabled, permissionSet, t]);

  const handleCheck = (
    checked: React.Key[] | { checked: React.Key[]; halfChecked: React.Key[] },
  ) => {
    const checkedKeys = Array.isArray(checked) ? checked : checked.checked;
    onChange?.(
      checkedKeys
        .map(String)
        .filter((permission) => permissionSet.has(permission)),
    );
  };

  return (
    <div className={accessStyles.permissionTreeShell}>
      <div className={accessStyles.permissionTreeHeader}>
        <span>{t("enterprise.roles.permissionTreeHint")}</span>
        <span>
          {t("enterprise.roles.selectedPermissions", {
            count: selectedPermissions.length,
          })}
        </span>
      </div>
      <Tree
        className={accessStyles.permissionTree}
        blockNode
        checkable
        checkStrictly
        defaultExpandAll
        showIcon
        selectable={false}
        checkedKeys={selectedPermissions}
        treeData={treeData}
        onCheck={handleCheck}
      />
    </div>
  );
}

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
  const [mode, setMode] = useState<EditorMode>("create");
  const [editing, setEditing] = useState<EnterpriseRole | null>(null);
  const [query, setQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState<RoleFilter>("all");
  const canManage = hasPermission(user, "roles:manage");
  const viewOnly = mode === "view";

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await companyApi.listRoles();
      setRoles(result.roles);
      setPermissions(result.permissions);
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.roles.loadFailed"),
      );
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const filteredRoles = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return roles.filter((role) => {
      if (roleFilter === "system" && !role.system_role) return false;
      if (roleFilter === "custom" && role.system_role) return false;
      if (!normalized) return true;
      return [
        role.code,
        getRoleName(role.code, role.name, t),
        getRoleDescription(role.code, role.description, t),
        ...configurablePermissions(role.permissions).map((permission) =>
          getPermissionName(permission, t),
        ),
      ].some((value) => value.toLowerCase().includes(normalized));
    });
  }, [query, roleFilter, roles, t]);

  const openCreate = () => {
    setEditing(null);
    setMode("create");
    form.resetFields();
    form.setFieldsValue({ permissions: [] });
    setOpen(true);
  };

  const openRole = (role: EnterpriseRole, nextMode: EditorMode) => {
    setEditing(role);
    setMode(role.system_role || !canManage ? "view" : nextMode);
    form.setFieldsValue({
      code: role.code,
      name: getRoleName(role.code, role.name, t),
      description: getRoleDescription(role.code, role.description, t),
      permissions: configurablePermissions(role.permissions),
    });
    setOpen(true);
  };

  const save = async (values: EnterpriseRoleInput) => {
    if (viewOnly) return;
    setSaving(true);
    try {
      if (editing) {
        await companyApi.updateRole(editing.code, {
          name: values.name.trim(),
          description: values.description?.trim(),
          permissions: values.permissions || [],
        });
      } else {
        await companyApi.createRole({
          ...values,
          code: values.code.trim().toUpperCase(),
          name: values.name.trim(),
          description: values.description?.trim(),
          permissions: values.permissions || [],
        });
      }
      message.success(t("enterprise.roles.saved"));
      setOpen(false);
      await load();
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.roles.saveFailed"),
      );
    } finally {
      setSaving(false);
    }
  };

  const remove = async (role: EnterpriseRole) => {
    try {
      await companyApi.deleteRole(role.code);
      message.success(t("enterprise.roles.deleted"));
      await load();
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.roles.deleteFailed"),
      );
    }
  };

  const columns: ColumnsType<EnterpriseRole> = [
    {
      title: t("enterprise.roles.code"),
      dataIndex: "code",
      width: 170,
      render: (value: string, role) => (
        <Space size={6} wrap>
          <Tag className={accessStyles.codeTag}>{value}</Tag>
          {role.system_role ? (
            <Tag className={accessStyles.systemTag}>
              {t("enterprise.roles.system")}
            </Tag>
          ) : null}
        </Space>
      ),
    },
    {
      title: t("enterprise.roles.name"),
      dataIndex: "name",
      width: 210,
      render: (_: string, role) => (
        <div className={accessStyles.nameCell}>
          <div className={accessStyles.primaryText}>
            {getRoleName(role.code, role.name, t)}
          </div>
          <div className={accessStyles.secondaryText}>
            {getRoleDescription(role.code, role.description, t)}
          </div>
        </div>
      ),
    },
    {
      title: t("enterprise.roles.permissions"),
      dataIndex: "permissions",
      render: (items: string[]) => (
        <div className={accessStyles.permissionSummary}>
          <KeyRound size={14} />
          <span>
            {t("enterprise.roles.permissionCount", {
              count: configurablePermissions(items).length,
            })}
          </span>
        </div>
      ),
    },
    {
      title: t("enterprise.roles.users"),
      dataIndex: "user_count",
      width: 100,
      render: (count: number) =>
        t("enterprise.roles.userCount", { count: count || 0 }),
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: canManage ? 210 : 90,
      fixed: "right",
      render: (_, role) => (
        <Space size={2}>
          <Button
            type="link"
            icon={<Eye size={15} />}
            onClick={() => openRole(role, "view")}
          >
            {t("common.view")}
          </Button>
          {canManage && !role.system_role ? (
            <>
              <Button
                type="link"
                icon={<Edit3 size={15} />}
                onClick={() => openRole(role, "edit")}
              >
                {t("common.edit")}
              </Button>
              <Popconfirm
                title={
                  role.user_count > 0
                    ? t("enterprise.roles.inUse", {
                        count: role.user_count,
                      })
                    : t("enterprise.roles.deleteConfirm")
                }
                disabled={role.user_count > 0}
                onConfirm={() => void remove(role)}
              >
                <Button
                  type="link"
                  danger
                  disabled={role.user_count > 0}
                  icon={<Trash2 size={15} />}
                >
                  {t("common.delete")}
                </Button>
              </Popconfirm>
            </>
          ) : null}
        </Space>
      ),
    },
  ];

  const modalTitle =
    mode === "create"
      ? t("enterprise.roles.create")
      : mode === "edit"
      ? t("enterprise.roles.edit")
      : t("enterprise.roles.view");

  return (
    <div className={accessStyles.accessPage}>
      <PageHeader
        parent={t("nav.settings")}
        current={t("enterprise.nav.roles")}
        afterBreadcrumb={
          <Tag>{t("common.total", { count: filteredRoles.length })}</Tag>
        }
        extra={
          <Space>
            <Button icon={<RefreshCw size={15} />} onClick={() => void load()}>
              {t("common.refresh")}
            </Button>
            {canManage ? (
              <Button
                type="primary"
                icon={<Plus size={15} />}
                onClick={openCreate}
              >
                {t("enterprise.roles.create")}
              </Button>
            ) : null}
          </Space>
        }
      />
      <div className={accessStyles.accessBody}>
        <div className={accessStyles.noticeBar}>
          <ShieldCheck size={17} />
          <span>{t("enterprise.roles.hint")}</span>
        </div>
        <div className={accessStyles.accessToolbar}>
          <div className={accessStyles.filterGroup}>
            <Input
              className={accessStyles.searchInput}
              allowClear
              prefix={<Search size={15} />}
              placeholder={t("enterprise.roles.search")}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
            <Select<RoleFilter>
              className={accessStyles.typeSelect}
              value={roleFilter}
              onChange={setRoleFilter}
              options={[
                { value: "all", label: t("enterprise.roles.allRoles") },
                { value: "system", label: t("enterprise.roles.systemRoles") },
                { value: "custom", label: t("enterprise.roles.customRoles") },
              ]}
            />
          </div>
          <span className={accessStyles.resultCount}>
            {t("enterprise.roles.filteredCount", {
              visible: filteredRoles.length,
              total: roles.length,
            })}
          </span>
        </div>
        <ResponsiveTable
          className={accessStyles.accessTable}
          rowKey="code"
          loading={loading}
          columns={columns}
          dataSource={filteredRoles}
          locale={{
            emptyText: <Empty description={t("enterprise.roles.empty")} />,
          }}
          pagination={{
            pageSize: 20,
            showSizeChanger: false,
            hideOnSinglePage: true,
          }}
          scroll={{ x: 860 }}
        />
      </div>
      <Modal
        open={open}
        width={680}
        title={modalTitle}
        okText={t("common.save")}
        cancelText={viewOnly ? t("common.close") : t("common.cancel")}
        confirmLoading={saving}
        footer={
          viewOnly ? (
            <Button onClick={() => setOpen(false)}>{t("common.close")}</Button>
          ) : undefined
        }
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        {viewOnly ? (
          <div className={accessStyles.readOnlyNote}>
            <LockKeyhole size={15} />
            <span>
              {editing?.system_role
                ? t("enterprise.roles.systemReadOnly")
                : t("enterprise.roles.viewOnly")}
            </span>
          </div>
        ) : null}
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => void save(values)}
        >
          <Form.Item
            name="code"
            label={t("enterprise.roles.code")}
            rules={[
              { required: true, message: t("enterprise.roles.codeRequired") },
              {
                pattern: /^[A-Za-z][A-Za-z0-9_-]{1,63}$/,
                message: t("enterprise.roles.codeRule"),
              },
            ]}
          >
            <Input disabled={Boolean(editing) || viewOnly} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t("enterprise.roles.name")}
            rules={[
              { required: true, message: t("enterprise.roles.nameRequired") },
            ]}
          >
            <Input disabled={viewOnly} maxLength={100} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t("enterprise.roles.description")}
          >
            <Input.TextArea disabled={viewOnly} rows={2} maxLength={300} />
          </Form.Item>
          <Form.Item
            name="permissions"
            label={t("enterprise.roles.permissions")}
          >
            <PermissionTree permissions={permissions} disabled={viewOnly} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
