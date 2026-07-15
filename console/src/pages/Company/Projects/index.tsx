import { useCallback, useEffect, useMemo, useState } from "react";
import {
  AutoComplete,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  Edit3,
  FolderOpen,
  FolderPlus,
  RefreshCw,
  Trash2,
  Users,
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  companyApi,
  type ProjectSpace,
  type ProjectSpaceInput,
  type ProjectSpaceMember,
} from "../../../api";
import type { AuthUser } from "../../../api/modules/auth";
import { useAuth } from "../../../contexts/AuthContext";
import { canManageProjects } from "../../../auth/authorization";
import { PageHeader } from "../../../components/PageHeader";
import { useAppMessage } from "../../../hooks/useAppMessage";
import styles from "../company.module.less";

const PROJECT_ROLES: ProjectSpaceMember["project_role"][] = [
  "OWNER",
  "MANAGER",
  "MEMBER",
  "VIEWER",
];

function formatTime(value?: string | null): string {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString();
}

export function canManageProject(
  user: AuthUser | null,
  project: Pick<ProjectSpace, "member_role"> | null | undefined,
): boolean {
  return Boolean(
    project &&
      (canManageProjects(user) ||
        project.member_role === "OWNER" ||
        project.member_role === "MANAGER"),
  );
}

export default function ProjectSpaces() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { message } = useAppMessage();
  const [projects, setProjects] = useState<ProjectSpace[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<ProjectSpace | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [projectForm] = Form.useForm<ProjectSpaceInput>();
  const [memberForm] = Form.useForm<{
    userId: string;
    role: ProjectSpaceMember["project_role"];
  }>();
  const [selectedProject, setSelectedProject] = useState<ProjectSpace | null>(
    null,
  );
  const [members, setMembers] = useState<ProjectSpaceMember[]>([]);
  const [users, setUsers] = useState<AuthUser[]>([]);
  const [membersLoading, setMembersLoading] = useState(false);
  const [memberSaving, setMemberSaving] = useState(false);

  const loadProjects = useCallback(async () => {
    setLoading(true);
    try {
      setProjects(await companyApi.listProjects());
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.loadFailed"),
      );
    } finally {
      setLoading(false);
    }
  }, [message, t]);

  useEffect(() => {
    void loadProjects();
  }, [loadProjects]);

  const canManageAllProjects = canManageProjects(user);
  const canCreate = canManageAllProjects;
  const canManageSelected = canManageProject(user, selectedProject);

  const openCreate = () => {
    setEditing(null);
    projectForm.resetFields();
    projectForm.setFieldsValue({ enabled: true });
    setModalOpen(true);
  };

  const openEdit = (project: ProjectSpace) => {
    setEditing(project);
    projectForm.setFieldsValue({
      code: project.code,
      name: project.name,
      description: project.description || "",
      enabled: project.enabled,
    });
    setModalOpen(true);
  };

  const saveProject = async (values: ProjectSpaceInput) => {
    setSaving(true);
    try {
      if (editing) {
        await companyApi.updateProject(editing.id, values);
      } else {
        await companyApi.createProject(values);
      }
      message.success(t("enterprise.projects.saved"));
      setModalOpen(false);
      await loadProjects();
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.saveFailed"),
      );
    } finally {
      setSaving(false);
    }
  };

  const loadMembers = useCallback(
    async (project: ProjectSpace) => {
      setMembersLoading(true);
      try {
        const [memberResult, userResult] = await Promise.allSettled([
          companyApi.listProjectMembers(project.id),
          companyApi.listUsers(),
        ]);
        if (memberResult.status === "rejected") throw memberResult.reason;
        setMembers(memberResult.value);
        setUsers(userResult.status === "fulfilled" ? userResult.value : []);
      } catch (error) {
        message.error(
          error instanceof Error
            ? error.message
            : t("enterprise.projects.membersLoadFailed"),
        );
      } finally {
        setMembersLoading(false);
      }
    },
    [message, t],
  );

  const openMembers = (project: ProjectSpace) => {
    setSelectedProject(project);
    memberForm.setFieldsValue({ role: "MEMBER" });
    void loadMembers(project);
  };

  const putMember = async (values: {
    userId: string;
    role: ProjectSpaceMember["project_role"];
  }) => {
    if (!selectedProject) return;
    setMemberSaving(true);
    try {
      await companyApi.putProjectMember(
        selectedProject.id,
        values.userId.trim(),
        values.role,
      );
      message.success(t("enterprise.projects.memberSaved"));
      memberForm.resetFields(["userId"]);
      await loadMembers(selectedProject);
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.memberSaveFailed"),
      );
    } finally {
      setMemberSaving(false);
    }
  };

  const updateMemberRole = async (
    member: ProjectSpaceMember,
    role: ProjectSpaceMember["project_role"],
  ) => {
    if (!selectedProject) return;
    try {
      await companyApi.putProjectMember(
        selectedProject.id,
        member.user_id,
        role,
      );
      await loadMembers(selectedProject);
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.memberSaveFailed"),
      );
    }
  };

  const removeMember = async (member: ProjectSpaceMember) => {
    if (!selectedProject) return;
    try {
      await companyApi.removeProjectMember(selectedProject.id, member.user_id);
      message.success(t("enterprise.projects.memberRemoved"));
      await loadMembers(selectedProject);
    } catch (error) {
      message.error(
        error instanceof Error
          ? error.message
          : t("enterprise.projects.memberRemoveFailed"),
      );
    }
  };

  const userOptions = useMemo(
    () =>
      users.map((entry) => ({
        value: entry.id,
        label: `${entry.display_name || entry.username} (${entry.username})`,
      })),
    [users],
  );

  const columns: ColumnsType<ProjectSpace> = [
    {
      title: t("enterprise.projects.name"),
      dataIndex: "name",
      render: (name: string, row) => (
        <div className={styles.nameCell}>
          <button
            type="button"
            className={styles.projectNameLink}
            onClick={() => navigate(`/projects/${encodeURIComponent(row.id)}`)}
          >
            {name}
          </button>
          <div className={styles.secondaryText}>
            {row.description || row.id}
          </div>
        </div>
      ),
    },
    {
      title: t("enterprise.projects.code"),
      dataIndex: "code",
      width: 150,
      render: (code) => <Tag>{code}</Tag>,
    },
    {
      title: t("enterprise.projects.myRole"),
      dataIndex: "member_role",
      width: 130,
      render: (role) => <Tag color="blue">{role || "SYSTEM"}</Tag>,
    },
    {
      title: t("enterprise.projects.status"),
      dataIndex: "enabled",
      width: 100,
      render: (enabled) => (
        <Tag color={enabled ? "success" : "default"}>
          {enabled ? t("common.enabled") : t("common.disabled")}
        </Tag>
      ),
    },
    {
      title: t("enterprise.projects.updatedAt"),
      dataIndex: "updated_at",
      width: 180,
      render: formatTime,
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: 310,
      render: (_, row) => {
        const canManage = canManageProject(user, row);
        return (
          <Space size={4}>
            <Button
              type="link"
              icon={<FolderOpen size={15} />}
              onClick={() =>
                navigate(`/projects/${encodeURIComponent(row.id)}`)
              }
            >
              {t("enterprise.projects.workspace")}
            </Button>
            <Button
              type="link"
              icon={<Users size={15} />}
              onClick={() => openMembers(row)}
            >
              {t("enterprise.projects.members")}
            </Button>
            {canManage && (
              <Button
                type="link"
                icon={<Edit3 size={15} />}
                onClick={() => openEdit(row)}
              >
                {t("common.edit")}
              </Button>
            )}
          </Space>
        );
      },
    },
  ];

  const memberColumns: ColumnsType<ProjectSpaceMember> = [
    {
      title: t("enterprise.projects.member"),
      key: "member",
      render: (_, row) => (
        <div className={styles.nameCell}>
          <div className={styles.primaryText}>
            {row.display_name || row.username}
          </div>
          <div className={styles.secondaryText}>
            {row.username} | {row.user_id}
          </div>
        </div>
      ),
    },
    {
      title: t("enterprise.projects.projectRole"),
      dataIndex: "project_role",
      width: 150,
      render: (role, row) =>
        canManageSelected ? (
          <Select
            value={role}
            style={{ width: 125 }}
            options={PROJECT_ROLES.map((value) => ({ value, label: value }))}
            onChange={(value) => void updateMemberRole(row, value)}
          />
        ) : (
          <Tag>{role}</Tag>
        ),
    },
    {
      title: t("common.actions"),
      key: "actions",
      width: 70,
      render: (_, row) =>
        canManageSelected ? (
          <Popconfirm
            title={t("enterprise.projects.removeMemberConfirm")}
            onConfirm={() => void removeMember(row)}
          >
            <Button type="text" danger icon={<Trash2 size={15} />} />
          </Popconfirm>
        ) : null,
    },
  ];

  return (
    <div className={styles.page}>
      <PageHeader
        current={t("enterprise.projects.title")}
        afterBreadcrumb={
          <Tag>{t("common.total", { count: projects.length })}</Tag>
        }
        extra={
          <Space>
            <Button
              icon={<RefreshCw size={15} />}
              onClick={() => void loadProjects()}
            >
              {t("common.refresh")}
            </Button>
            {canCreate && (
              <Button
                type="primary"
                icon={<FolderPlus size={16} />}
                onClick={openCreate}
              >
                {t("enterprise.projects.create")}
              </Button>
            )}
          </Space>
        }
      />
      <div className={styles.body}>
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={projects}
          locale={{
            emptyText: <Empty description={t("enterprise.projects.empty")} />,
          }}
          pagination={{ pageSize: 20, showSizeChanger: true }}
          scroll={{ x: 1000 }}
        />
      </div>

      <Modal
        open={modalOpen}
        title={
          editing
            ? t("enterprise.projects.edit")
            : t("enterprise.projects.create")
        }
        onCancel={() => setModalOpen(false)}
        onOk={() => projectForm.submit()}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form
          form={projectForm}
          layout="vertical"
          onFinish={(values) => void saveProject(values)}
        >
          <Form.Item
            name="code"
            label={t("enterprise.projects.code")}
            rules={[
              { required: true },
              {
                pattern: /^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])$/,
                message: t("enterprise.projects.codeRule"),
              },
            ]}
          >
            <Input disabled={Boolean(editing)} maxLength={64} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t("enterprise.projects.name")}
            rules={[{ required: true }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t("enterprise.projects.description")}
          >
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t("enterprise.projects.status")}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={Boolean(selectedProject)}
        title={
          selectedProject
            ? `${selectedProject.name} / ${t("enterprise.projects.members")}`
            : ""
        }
        onClose={() => setSelectedProject(null)}
        width="min(720px, 100vw)"
        destroyOnHidden
      >
        <div className={styles.drawerBody}>
          {canManageSelected && (
            <Form
              form={memberForm}
              className={styles.memberForm}
              onFinish={(values) => void putMember(values)}
              initialValues={{ role: "MEMBER" }}
            >
              <Form.Item name="userId" rules={[{ required: true }]}>
                <AutoComplete
                  options={userOptions}
                  placeholder={t("enterprise.projects.userId")}
                  filterOption={(input, option) =>
                    String(option?.label || "")
                      .toLowerCase()
                      .includes(input.toLowerCase()) ||
                    String(option?.value || "")
                      .toLowerCase()
                      .includes(input.toLowerCase())
                  }
                />
              </Form.Item>
              <Form.Item name="role" rules={[{ required: true }]}>
                <Select
                  options={PROJECT_ROLES.map((value) => ({
                    value,
                    label: value,
                  }))}
                />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={memberSaving}>
                {t("enterprise.projects.addMember")}
              </Button>
            </Form>
          )}
          <Table
            rowKey="user_id"
            size="small"
            loading={membersLoading}
            columns={memberColumns}
            dataSource={members}
            pagination={false}
            scroll={{ x: 560 }}
          />
        </div>
      </Drawer>
    </div>
  );
}
