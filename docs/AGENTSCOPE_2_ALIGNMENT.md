# AgentScope Java 2.0 对齐说明

本文定义 MelonPaw-Company 对 AgentScope Java 2.0.0 GA 的使用边界。目标是优先使用官方运行时、模型、工作区、文件、技能、状态和权限能力，把本项目的自有代码集中在企业身份、资源所有权、审批、审计和渠道治理上。

## 版本基线

- Maven 属性 `agentscope.version` 固定为 `2.0.0`。
- 使用 `agentscope-core` 与 `agentscope-harness`。
- 模型实现来自官方扩展：`agentscope-extensions-model-openai`、`dashscope`、`anthropic`、`gemini` 和 `ollama`。
- DeepSeek 与其他 OpenAI 兼容服务复用官方 OpenAI 扩展，通过创建上下文传入地址、密钥和 formatter，不维护独立模型客户端。

升级 AgentScope 时必须先阅读官方 release notes，再执行本文末尾的回归清单。不得只修改版本号后直接发布。

## 职责边界

| 范围 | AgentScope 官方能力 | MelonPaw-Company 企业层责任 |
| --- | --- | --- |
| 模型 | Provider extensions、`ModelRegistry`、`ModelCreationContext`、`GenerateOptions` | 供应商配置、密钥读取、可用模型清单、管理员探测接口 |
| 调用上下文 | `RuntimeContext` 中的 `userId`、`sessionId` 与扩展属性 | 从认证身份生成上下文，禁止客户端伪造资源所有者 |
| 状态 | `AgentStateStore`、`JsonFileAgentStateStore`、Harness 自动加载和保存 | 为每个 Agent 增加内部命名空间、目录生命周期、备份与恢复 |
| 工作区 | Harness Workspace、上下文文件注入、记忆和技能发现 | 为用户或项目解析已授权的工作区根目录，维护 MD 文件访问权限 |
| 文件工具 | Harness 官方 `read_file`、`write_file`、`edit_file`、`grep_files`、`glob_files`、`list_files` | 按角色决定工具开关，审计写操作，禁止越过授权工作区 |
| 技能 | Harness 动态技能与 `SkillManageTool` | 技能归属、CRUD、启停、上传检查、管理员全局技能池 |
| 工具权限 | `PermissionContextState`、`PermissionEngine`、ALLOW/ASK/DENY 和确认事件 | 企业审批单、审批人权限、超时、记录留存和前端交互 |
| Subagent | 官方 Subagent 工具和事件协议 | 委派 RBAC、目标 Agent 可见性、能力降级、额度、审批传播和完整审计 |

AgentScope 的工具权限只回答“本次 Agent 调用能否执行某个工具”，不能替代 HTTP 接口认证、角色权限、项目成员关系和数据所有权检查。

## 模型接入

模型创建集中在 `melon-core` 的 `ProviderManager`：

1. 企业配置使用 `<provider>:<model>` 标识模型。
2. `ProviderManager` 把供应商映射到官方扩展的 provider ID。
3. 每次创建模型时构造 `ModelCreationContext`，传入 API Key、Base URL、流式选项和 provider 特定组件。
4. 调用 `ModelRegistry.resolve(modelId, context)` 获取官方模型实现。

这条路径代替自建模型 builder、全局工厂注册和供应商 HTTP 客户端。新增兼容供应商时应先确认官方 extension 或 OpenAI 兼容模式是否覆盖；只有官方接口无法表达且有明确测试时，才考虑扩展适配层。

## 上下文与状态

Web 请求进入 Agent 前，由 `AgentRunner` 创建 `RuntimeContext`：

- `userId` 来自已认证的企业用户。
- `sessionId` 来自该用户有权访问的会话。
- 渠道、工作目录、项目和根会话等信息作为扩展属性传递。

所有 Harness Agent 共享由 Spring 注入的底层 `AgentStateStore`。默认实现是官方 `JsonFileAgentStateStore`，由 `StateStoreConfig` 统一创建。`MultiAgentManager` 在交给每个 Harness Agent 前使用 `AgentScopedStateStore` 包装底层存储，把 `agentId` 纳入内部用户命名空间；外部 `RuntimeContext` 仍保留真实的 `userId` 和 `sessionId`。这避免不同 Agent 在同一用户、同一会话 ID 下覆盖固定的 `agent_state` 键。

历史恢复通过官方 `AgentStateStore.get(...)` 读取，不推导 JSON 文件路径，也不扫描同名 session 目录。旧的未分区状态仅作只读兼容回退。调用方在构造 `RuntimeContext` 前必须完成身份与会话所有权校验，不能把前端提交的用户 ID 直接作为可信值。

## Workspace 与文件工具

`MelonAgentFactory` 使用 Harness Workspace，并对生产文件系统应用以下约束：

- 使用官方 `DockerFilesystemSpec` 与 `IsolationScope.USER`，不在宿主机直接执行模型生成的命令。
- 只把 `ConfigManager` 已校验位于受控根目录下、且不包含符号链接的当前 Agent 工作区，以读写 bind mount 映射到容器 `/workspace`。
- 使用 Harness 官方文件工具和官方 `execute`；前端的 `execute_shell_command` 只是兼容展示名，不再对应自制 `ProcessBuilder` Shell 工具。
- 容器丢弃 Linux capabilities、启用 `no-new-privileges`、限制进程数、CPU 和内存，并以镜像内非 root 用户运行。
- 默认使用 Docker `bridge` 以支持技能脚本获取外部资料；企业可通过 `MELON_SANDBOX_NETWORK` 指向受限出口网络或设为 `none`。
- 本地文件系统模式只允许显式设置 `MELON_EXECUTION_SANDBOX=local` 的开发环境使用，不能作为企业部署方式。

`AGENTS.md` 与 `MEMORY.md` 由 Harness Workspace 管理。其他允许的单层上下文文件使用 `additionalContextFile` 注入，并拒绝绝对路径、父目录跳转和多层任意路径。

Web 工作区的 `memory/` 接口读写同一 Harness 日记忆目录，支持受限深度的 Markdown 子目录，并拒绝绝对路径、`..`、非 Markdown、符号链接、敏感路径和超过 1 MiB 的内容。

文件读取、写入、编辑、搜索和列表使用 Harness 官方文件工具。项目配置只负责把企业工具开关映射到官方工具名，不再维护同功能的自制文件工具。工作区根目录隔离仍是企业层责任：沙箱不能弥补错误地把其他用户目录当成项目根目录。

## 技能

Harness 负责工作区技能发现、动态加载和技能管理工具。`MelonAgentFactory` 通过 `enableSkillManageTool` 启用官方 `SkillManageTool`，配置 `autoPromote=true`、`securityScan=true`；`skill_manage`/`propose_skill` 仍由 ASK 规则要求用户确认，确认后才进入正式 `skills/` 目录。技能中心忽略 `_drafts` 和缺少 `SKILL.md` 的目录。不再使用自制的 `materialize_skill` 工具或自制提示词去模拟技能生成。

企业层继续负责：

- 普通用户只能查看和修改自己的技能。
- 管理员管理全局技能池、Hub 导入和工作区分配。
- ZIP 或外部技能导入必须经过路径、内容和来源检查。
- Agent 提议或修改技能时仍需遵守工具审批与审计策略。

## 权限与人工确认

Agent 构建时通过 `PermissionContextState` 配置工具规则，由 AgentScope `PermissionEngine` 执行 ALLOW、ASK 或 DENY 决策。

当规则返回 ASK 时，官方事件流产生 `RequireUserConfirmEvent`。`AgentRunner` 将事件转换为企业审批请求，等待有权审批的用户操作，再把 `ConfirmResult` 送回同一会话继续执行。这个流程必须保留以下关联信息：

- 用户、会话、根会话和 Agent 标识。
- 实际工具名、展示工具名和参数摘要。
- 审批创建时间、超时时间、结果和审批人。
- 继续执行后的工具结果与审计事件。

企业接口 RBAC 应在事件进入审批服务前已经完成；不能因为 AgentScope 返回 ASK 就默认当前用户有审批权。

## Subagent 暂停策略

当前 `HarnessAgent` 构建时显式调用 `disableSubagents()`。前端和后端不应暴露 `agent_spawn`、`agent_send`、`agent_list` 或旧式自制多 Agent 任务入口。

重新启用官方 Subagent 前，至少完成以下能力：

1. 明确任务所有者、发起 Agent、目标 Agent 和根会话的不可伪造关联。
2. 根据企业角色、项目成员关系和目标 Agent 可见性校验每次委派。
3. 子 Agent 只能获得父任务授权能力的子集，不能借委派提升工具或数据权限。
4. 模型额度、最大并发、最大深度、超时和取消信号能够沿调用树传播。
5. ASK 审批能够回传到正确审批人，并在父子会话之间保持完整上下文。
6. 所有 spawn、send、tool、result、cancel 和 failure 事件进入统一审计链。
7. Web 端能展示父子任务关系、执行状态、审批与结果，移动端同样可用。
8. 覆盖越权委派、循环委派、跨项目访问、取消、重试和服务重启恢复测试。

在这些条件完成前，保留官方事件映射代码不等于允许执行 Subagent。

## 不再维护的重复实现

下列能力不得以兼容旧界面为理由重新引入：

- 自制文件读写、编辑、glob 和 grep 工具。
- 自制系统提示词中间件对 Workspace 上下文的重复拼接。
- `materialize_skill` 及其专用前端卡片。
- 自制 Agent spawn/send/list/task 工具和跨 Agent 会话协议。
- CLI、Coding 模式和桌面桥接代码。

确需偏离官方能力时，应先记录具体缺口、风险、替代方案和回退策略，并给新增适配层补充契约测试。

## 升级与回归清单

每次升级 AgentScope 后至少检查：

- Maven 依赖树只保留需要的 core、harness 和 provider extensions，没有混用 RC 与 GA 版本。
- 所有 provider 都能通过 `ModelRegistry` 创建，动态密钥和 Base URL 不串用。
- 相同用户会话能恢复状态；不同用户、会话或 Agent 不能读取彼此状态。
- `AGENTS.md`、`MEMORY.md`、附加上下文和用户技能只从授权工作区加载。
- 绝对路径、`..`、符号链接和跨工作区文件访问受到拒绝或约束。
- ALLOW、ASK、DENY 三类权限决策及确认恢复流程正常。
- Subagent 仍保持关闭，除非本文的开放条件已经全部完成并评审。
- 后端测试、前端测试、前端生产构建和打包后的 Web 启动检查全部通过。

## 官方资料

- [AgentScope Java 2.0 文档](https://java.agentscope.io/v2/zh/docs/index.html)
- [版本发布说明](https://java.agentscope.io/v2/zh/docs/others/release-notes.html)
- [模型与 ModelRegistry](https://java.agentscope.io/v2/zh/docs/building-blocks/model.html)
- [RuntimeContext 与状态管理](https://java.agentscope.io/v2/zh/docs/building-blocks/context.html)
- [权限系统](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html)
- [Harness Workspace](https://java.agentscope.io/v2/zh/docs/harness/workspace.html)
- [Harness 文件系统](https://java.agentscope.io/v2/zh/docs/harness/filesystem.html)
- [Harness Subagent](https://java.agentscope.io/v2/zh/docs/harness/subagent.html)
