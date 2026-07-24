# 企业能力与权限矩阵

本文是 MelonPaw-Company 的授权基线。新增页面、接口、后台任务或存储目录必须先确定资源所有者，再选择权限；不能仅按“是否属于 Agent 功能”统一归入管理员。

## 分层原则

1. 个人资源由用户本人管理，数据落在该用户绑定的 PERSONAL Agent 工作区。
2. 项目资源按项目成员角色授权，不复用 PERSONAL Agent，也不允许通过请求头切换到他人空间。
3. 企业管理是非对话型能力。组织管理员和审计员不需要 `agents:use`，界面也不得自动发起聊天、会话或收件箱轮询。
4. AI 服务运维管理共享 Agent、工具和运行配置，但不能进入任何用户的 PERSONAL Agent。
5. 系统管理才允许接触宿主机环境、模型密钥、插件、备份和全局安全策略。

## 企业角色

| 角色 | 能力 | 明确不包含 |
| --- | --- | --- |
| `SYSTEM_ADMIN` | 平台配置、身份、模型、插件、备份和应急管理 | 默认不含他人 PERSONAL 内容；须显式授予 `data:breakglass` |
| `ORG_ADMIN` | 用户、角色、项目空间和企业审计管理 | 聊天、个人空间、Agent 运维、模型密钥、插件、备份 |
| `SERVICE_OPERATOR` | 非个人 Agent、Agent 级工具/MCP、运行配置和高风险审批 | 用户与角色管理、MCP Server/OAuth、宿主机与全局密钥管理、他人 PERSONAL Agent |
| `APPROVER` | 使用个人 Agent，并审批自己或被授权范围内的高风险操作 | 企业配置、共享 Agent 配置 |
| `EMPLOYEE` | 工作台、任务与结果、个人对话及全部个人资源 | 用户、角色、共享 Agent、系统配置和企业审计 |
| `AUDITOR` | 项目只读信息、用量和审计记录 | 对话、个人资源写入、任何管理写操作 |
| 自定义角色 | 由下列稳定权限码组合 | 不继承未明确授予的能力 |

## 个人能力

| 能力 | 权限码 | 页面 | 主要接口 | 所有权规则 |
| --- | --- | --- | --- | --- |
| 对话、任务、结果、会话 | `agents:use` | 工作台、任务中心、聊天、会话、收件箱 | `/api/chats/**`、`/api/console/chat/**`、`/api/console/inbox/**` | 强制解析到本人 PERSONAL Agent |
| 工作区、Markdown、记忆 | `workspace:personal` | `/workspace` | `/api/workspace/**`、`/api/files/preview/**` | 只允许本人工作区，包含上传、下载、MD、memory 和个人偏好 |
| 个人技能 | `skills:personal` | `/skills` | `/api/skills/**` | 只允许本人 `skills` 与 `_drafts`；共享技能池除外 |
| 个人渠道 | `channels:personal` | `/channels` | `/api/config/channels/**`、`/api/channels/**`、`/api/access-control/**` | 配置、启停、二维码和访问名单均属于本人渠道 |
| 个人自动化 | `automations:personal` | `/cron-jobs`、`/heartbeat` | `/api/cron/**`、`/api/config/heartbeat/**` | 任务定义、执行历史和结果写入本人工作区与收件箱 |
| 个人资料 | 已认证用户 | 账号菜单 | `/api/auth/profile`、`/api/auth/update-profile` | 只能修改本人资料与密码 |

`agents:use` 与四项个人管理权限相互独立。自定义角色可以只管理定时任务而不能聊天，也可以只维护渠道或知识文件。

## 项目空间

| 项目角色 | 项目元数据 | 项目文档 | 成员管理 |
| --- | --- | --- | --- |
| `OWNER` | 读写 | 读写 | 允许 |
| `MANAGER` | 读写 | 读写 | 允许 |
| `MEMBER` | 只读 | 仅可编辑 `KNOWLEDGE.md` | 禁止 |
| `VIEWER` | 只读 | 只读 | 禁止 |

`projects:manage` 可以创建和管理全部项目；`projects:read` 只能查看自己被分配的项目。项目工作区当前包含 `PROJECT.md`、`KNOWLEDGE.md` 和 `POLICY.md`。

每个项目当前最多绑定一个已发布 Agent。`OWNER`、`MANAGER` 可以发布/回滚项目 Agent；二者和具有全局 `agents:manage` 的运营人员可以维护其技能和 MCP，运行中禁止修改这些资源。`OWNER`、`MANAGER`、`MEMBER` 可以发起独立的项目 Agent 会话，`VIEWER` 不能执行但可以读取仍有成员资格项目的任务结果。项目 Agent 不共享用户会话、个人 MCP、个人渠道或个人记忆。

## 企业与系统管理

| 范围 | 权限 | 页面或接口 |
| --- | --- | --- |
| 用户 | `users:read` / `users:manage` | 成员、账号状态、密码重置、角色分配 |
| 角色 | `roles:read` / `roles:manage` | 角色与权限目录、自定义角色 |
| 项目 | `projects:read` / `projects:manage` | 项目、成员、项目文档 |
| 共享 Agent | `agents:manage` | 非个人 Agent 发布、Agent 级工具/MCP、运行配置、运行统计、共享技能池 |
| 审批 | `approvals:decide` | 高风险工具与自动化审批 |
| 审计 | `audit:read` | 用量和安全审计，只读 |
| 系统 | `system:manage` | 模型与供应商、环境变量、全局安全、插件、备份、本地模型、调试、MCP Server/OAuth、宿主机路径、全局 Agent 排序 |

## 强制约束

- 客户端传入的 `X-Agent-Id` 不是授权依据，服务端必须按登录用户重新解析。
- `agents:manage` 允许管理共享 Agent，但不能读取或修改他人的 PERSONAL Agent。
- 普通用户的 `execute_shell_command` 显示名始终可用，但实际执行只走 AgentScope 官方 `ShellExecuteTool`；每个用户使用自己的 Docker 沙箱和自己的工作区挂载，技能提升、写入和高风险动作继续经过 AgentScope 权限确认。容器访问外部资料时，生产网络必须通过受控出口阻断企业内网和云元数据地址。
- 渠道配置、二维码状态和访问控制均绑定个人 Agent；渠道客户端提交的 Agent 标识不能单独作为授权依据。
- 定时任务、渠道回调和心跳都必须携带创建者/执行者身份；不能使用 `default` 作为无人值守任务的共享用户槽位。
- 系统管理员拥有平台管理权，不自动拥有他人 PERSONAL 内容；跨用户内容访问必须单独授予 `data:breakglass` 并记录审计。
- 页面隐藏只改善体验，最终授权必须由后端 SecurityFilter、方法权限和资源所有权共同执行。
- 管理账号没有对话权限时，不渲染聊天入口，也不启动会话、收件箱和推送轮询。
