# Grok Build 对 MelonPaw-Company 企业级 Agent 建设的参考与改造建议

> 文档日期：2026-07-17<br>
> 目标产品：单个企业一套私有部署、多个企业用户共同使用的 Agent 协作与任务执行平台<br>
> 分析基线：grok-build commit <code>8adf9013a0929e5c7f1d4e849492d2387837a28d</code>，<code>SOURCE_REV=2ec0f0c8488842da03a71eeee3c61154957ca919</code><br>
> 本文性质：架构分析和改造建议，不代表已经完成对应实现

## 1. 结论

Grok Build 对 MelonPaw-Company 的主要价值，不是提供一套可以直接嵌入的 Rust Agent 运行时，而是提供一组已经产品化的执行语义、故障处理方式和测试样本。

MelonPaw-Company 应继续坚持现有方向：

1. 使用 AgentScope Java 2.0.0 GA 负责模型调用、Harness Agent、Workspace、文件工具、命令工具、技能、记忆、权限确认和官方 Subagent 原语。
2. 使用 Spring Boot WebFlux 负责 Web API、SSE、认证和企业控制面。
3. 由 MelonPaw 自己负责企业身份、资源所有权、项目成员关系、任务、委派、审批、配额、审计、数据生命周期和企业可观测性。
4. 不引入 Grok 的 TUI、CLI、ACP、Coding 模式、Git worktree 和本地开发者沙箱。
5. 不把 Grok 的 Rust crate 作为 Java 服务的运行依赖，也不重写 AgentScope 已经提供的能力。

最值得优先借鉴的内容有六项：

| 优先级 | 借鉴内容 | 对 MelonPaw 的直接价值 |
| --- | --- | --- |
| P0 | 统一 Task、Run、Event 执行模型 | 为项目 Agent、任务中心、审批、重试、取消、审计、配额和 Subagent 提供共同主键与状态机 |
| P0 | 版本化的 Agent 执行事件 | 解决 Chat、Cron、渠道、审批和项目任务各自记录、无法统一查询的问题 |
| P1 | MCP 超时、输出限制、存活检测和恢复 | 提升多用户长期运行时的稳定性，防止单个 MCP 卡死或输出撑爆上下文 |
| P1 | 隐私优先的 OpenTelemetry 指标和事件 | 对齐企业可观测性、用量归因和告警，同时避免提示词、路径和密钥默认出站 |
| P1 | 分层记忆与混合检索 | 在个人、项目、会话记忆之间建立明确 ACL 和更好的召回质量 |
| P2 | 单层 Subagent、能力交集和恢复身份校验 | 为未来项目 Agent 委派提供安全边界，避免权限放大和无限递归 |

另外有一个具体的部署风险值得单独处理：当前 HistoryStore 和 AuditLogService 固定启用 SQLite WAL。Grok Build 专门处理了 NFS/NAS 上 WAL 与共享内存文件不兼容的问题。MELON_HOME 如果可能位于网络文件系统，就必须增加启动预检、明确禁止，或者提供受支持的 journal mode。

## 2. 产品目标和边界

### 2.1 目标场景

本文按以下场景设计：

- 一套部署只服务一家企业，不做 SaaS 多租户。
- 企业内存在系统管理员、组织管理员、服务运营、审批人、审计员、项目成员和普通员工。
- 多个用户可以同时执行自己的个人 Agent。
- 项目成员未来可以执行项目 Agent，但不能把项目 Agent 当作无边界的共享账号。
- 用户可以从 Web 控制台、自动化任务和外部渠道发起任务。
- 所有执行都必须能够回答：谁发起、代表谁执行、使用哪个 Agent、访问哪个工作区、消耗多少额度、由谁审批、最终产生什么结果。

### 2.2 明确不做

- 不做企业切换和租户级计费。
- 不做 CLI、桌面客户端和 Coding 模式。
- 不以 Grok Build 替换 AgentScope。
- 不允许模型直接选择任意用户、任意项目或任意 Agent。
- 不允许一个项目共享 Agent 使用某位成员的个人凭据或个人记忆。
- 不允许普通用户通过请求参数、Header、渠道元数据或工具输入伪造资源所有者。

### 2.3 单企业不等于单用户

即使只有一个企业，仍然必须把以下边界视为强隔离边界：

- 用户 A 与用户 B 的 PERSONAL Agent。
- 用户个人空间与项目空间。
- 项目 A 与项目 B。
- 普通项目成员与项目管理者。
- Agent 配置管理权与 Agent 使用权。
- 发起任务的用户与审批任务的用户。
- 企业运维权限与个人内容访问权限。
- 系统管理员权限与 data:breakglass 数据越权访问。

## 3. 当前架构判断

### 3.1 已经正确的基础

当前代码已经具备企业化所需的一部分关键基础：

| 范围 | 当前基础 |
| --- | --- |
| 身份 | 企业账号、角色、权限码和访问令牌均由服务端认证 |
| PERSONAL Agent | 用户绑定独立个人 Agent，普通用户不能通过 X-Agent-Id 切换到他人空间 |
| 状态 | AgentScopedStateStore 把 agentId 纳入 AgentScope 状态命名空间 |
| Workspace | 个人 Workspace 独立，生产文件和命令工具走 DockerFilesystemSpec |
| 权限 | AgentScope PermissionEngine 负责 ALLOW、ASK、DENY，应用层接企业审批 |
| MCP | 已按 Agent 建立 McpClientRegistry，并在每次调用时重新检查用户、渠道和工具策略 |
| 技能 | 个人技能、共享技能池、导入检查和官方 SkillManageTool 已经有明确边界 |
| 记忆 | 使用 AgentScope MemoryConfig、memory_search、memory_get 和 session_search |
| 项目 | 已有项目、成员关系和项目文档 Workspace |
| 审计 | 工具审批已有 AuditLogService 记录 |

这些能力应保留并继续强化，不应因为参考 Grok 而重写。

### 3.2 当前核心缺口

真正阻碍“企业一套部署、多人使用”的不是模型调用能力，而是企业执行控制面尚未统一：

| 缺口 | 当前表现 | 后果 |
| --- | --- | --- |
| 统一任务实体缺失 | TaskCenter 主要读取 Chat；Cron、渠道、审批、技能安装各有自己的 task_id/run_id | 无法统一查状态、归因、取消、重试和审计 |
| 项目 Agent 运营闭环未完成 | 项目 Agent、成员执行和项目任务已开放，但项目预算以及技能/MCP/文档的完整版本回滚仍缺失 | 无法完成项目级成本治理和资源审计恢复 |
| 配额只到 Agent 级 | 缺少用户、项目、Agent、Provider 的分层并发和预算 | 一个用户或自动化可能挤占整个部署 |
| 管理审计不完整 | 审计偏重工具审批，配置、角色、项目、模型和插件变更未统一 | 无法完成企业追责和合规导出 |
| MCP 生命周期不完整 | 有连接和失败重连，但缺少完整的存活、退避、输出治理和统一错误分类 | 长时间运行后容易出现僵死、重复连接或上下文膨胀 |
| 可观测性不足 | 有用量数据和基础健康，缺少统一指标、追踪和关联 ID | 故障和成本无法从企业、项目、用户、任务层定位 |
| 状态与任务元数据分散 | AgentScope JSON 状态、Workspace JSON、SQLite 历史、Inbox 和 Chat 各自保存 | 恢复、备份、删除和离职交接困难 |
| 共享会话语义未定义 | 未来项目 Agent 可能被误解为多人同时写同一会话 | 产生状态竞争、审批归属不清和数据泄漏 |

## 4. Grok Build 的适用边界

### 4.1 值得参考

| Grok 模块或设计 | 借鉴方式 | 企业化适配 |
| --- | --- | --- |
| xai-file-utils 事件类型 | 翻译为 Java 的 TaskEventSchema | 增加企业用户、项目、资源范围、审批人和请求关联 |
| Session 的 summary、updates、signals 分层 | 参考“权威事件流 + 可查询摘要” | TaskEvent 做事实流，Task/Run 表做查询投影 |
| Background task 的 task_id、wait、kill | 参考任务生命周期和取消语义 | 统一 Cron、渠道、Agent、技能导入和 Subagent |
| MCP startup/tool timeout、liveness、restart | 参考恢复状态机和测试 | 加入 Agent/项目凭据所有权、出口网络和审计 |
| OpenTelemetry schema | 参考指标名、事件字段和隐私门控 | 控制高基数标签，默认不导出用户内容 |
| Memory 的 FTS5、向量、时间衰减、MMR | 参考检索算法 | 检索前先做企业 ACL 和命名空间过滤 |
| Subagent capability_mode、resume validation、depth=1 | 参考委派边界 | 增加 RBAC、项目成员、额度、审批传播和审计链 |
| SQLite journal mode 检测 | 参考部署预检 | 单节点本地盘使用 WAL；NAS/NFS 禁止或切换策略 |
| Secret sanitizer | 参考测试用例 | 只能用于日志脱敏，不能替代密钥加密 |

### 4.2 不应引入

| Grok 内容 | 不引入原因 |
| --- | --- |
| Rust Agent runtime | 与 Java/AgentScope 双运行时会制造重复状态、权限和工具协议 |
| TUI 和 Ratatui 组件 | 产品只交付 Web 控制台 |
| ACP 和编辑器嵌入 | 当前产品不做 Coding 或编辑器 Agent |
| Git worktree、hunk tracker、rewind | 面向代码开发，不是通用企业任务的核心能力 |
| Landlock/Seatbelt 本地沙箱 | 当前生产边界是 DockerFilesystemSpec，应强化容器而不是更换模型 |
| Codex/OpenCode 移植工具 | AgentScope 已提供官方文件和命令工具，重复维护收益低 |
| 本地任意 command hook | 企业环境中等同于开放宿主机代码执行，风险过高 |
| 明文 OAuth credential JSON | 只能参考原子写入和权限，不能满足企业密钥落盘要求 |

## 5. 目标架构

### 5.1 控制面与执行面分离

~~~mermaid
flowchart LR
    UI["Web 控制台"]
    CH["外部渠道"]
    CRON["Cron / Heartbeat"]
    API["认证、签名、幂等入口"]
    TASK["Enterprise Task Service"]
    POLICY["RBAC、资源所有权、配额、审批"]
    RUNNER["AgentRunner"]
    AS["AgentScope Harness Agent"]
    TOOLS["Workspace、Docker、MCP、Skills"]
    EVENTS["Task Event Store"]
    AUDIT["Enterprise Audit"]
    USAGE["Usage Ledger"]
    OTEL["Metrics / Traces / Alerts"]

    UI --> API
    CH --> API
    CRON --> API
    API --> TASK
    TASK --> POLICY
    POLICY --> RUNNER
    RUNNER --> AS
    AS --> TOOLS
    RUNNER --> EVENTS
    TOOLS --> EVENTS
    POLICY --> AUDIT
    EVENTS --> USAGE
    EVENTS --> OTEL
    EVENTS --> UI
~~~

### 5.2 职责划分

| 层 | 负责内容 | 不负责内容 |
| --- | --- | --- |
| AgentScope | 模型、Agent loop、工具调用、Workspace、Memory、Permission 原语、Subagent 原语 | 企业身份、项目关系、企业额度、审批人、审计留存 |
| Enterprise Task Service | Task/Run/Event、状态机、幂等、取消、重试、恢复、结果索引 | 模型内部推理 |
| Enterprise Policy | RBAC、资源所有权、项目成员、能力交集、审批路由、配额准入 | 文件和命令的具体执行 |
| AgentRunner Adapter | 把 AgentScope 事件映射为企业 TaskEvent | 自己维护另一套 Agent loop |
| Workspace/Sandbox | 路径、挂载、容器资源、网络、进程回收 | HTTP 接口授权 |
| Audit/Telemetry | 审计、指标、追踪、告警、脱敏、导出 | 保存完整提示词作为默认行为 |

## 6. 企业资源模型

### 6.1 资源范围

建议把 Agent 和 Task 的资源范围统一为以下类型：

| scope_type | owner | Workspace | 典型用途 |
| --- | --- | --- | --- |
| PERSONAL | user_id | users/{userId} | 个人对话、个人自动化、个人渠道、个人技能 |
| PROJECT | project_id | projects/{projectId} | 项目文档、项目 Agent、项目任务和项目共享记忆 |
| SERVICE | enterprise | services/{agentId} | 运维或企业公共 Agent，只允许明确角色使用 |

强制规则：

1. PERSONAL Agent 的配置可以继承企业模型配置，但状态、会话、渠道、MCP 凭据和记忆属于个人。
2. PROJECT Agent 的定义属于项目，执行任务属于具体用户和项目，不能创建一个没有执行者身份的共享会话。
3. SERVICE Agent 不等于 SYSTEM_ADMIN，必须单独授予使用权限。
4. scope_type 和 scope_id 必须由服务端根据认证身份和项目关系生成，不能接受客户端覆盖。
5. Workspace 路径不是授权依据；必须先完成资源授权，再解析路径。

### 6.2 Agent 定义、发布版本和绑定

企业共享 Agent 不应继续只表现为 config.yaml 中一个可变对象。建议引入：

| 实体 | 作用 |
| --- | --- |
| agent_definition | 稳定 Agent 身份、scope_type、scope_id、名称和生命周期 |
| agent_version | 不可变的模型、提示词、技能、工具、MCP、审批和资源策略快照 |
| agent_binding | PERSONAL 用户绑定或 PROJECT 项目绑定 |
| agent_release | 发布人、发布时间、版本、变更摘要和回滚目标 |

任务启动时必须记录 agent_version_id。运行中的 Agent 配置不能因为管理员编辑而静默变化；新配置只影响之后创建的 Run。

## 7. 统一任务模型

### 7.1 为什么 Task 不能继续等同于 Chat

企业任务可能来自：

- Web 对话。
- 非对话表单。
- Cron。
- Heartbeat。
- 钉钉、飞书、企业微信、QQ、微信、OneBot 等渠道。
- 项目工作流。
- 技能导入和安全扫描。
- MCP 操作。
- 父 Agent 委派的 Subagent。
- 管理员触发的批处理。

这些任务不一定有 Chat 页面，也不一定产生自然语言回复。因此 Chat 只能是 Task 的一种交互视图，不能继续充当企业任务主实体。

### 7.2 建议实体

#### enterprise_task

表示用户或系统想完成的一件事，跨重试保持稳定。

| 字段 | 说明 |
| --- | --- |
| task_id | UUIDv7 或等价的时间有序唯一 ID |
| task_type | AGENT、AUTOMATION、CHANNEL、SKILL_IMPORT、ADMIN_OPERATION 等 |
| scope_type / scope_id | PERSONAL、PROJECT、SERVICE 及其所有者 |
| owner_user_id | 任务结果的所有者 |
| initiator_type / initiator_id | USER、CHANNEL、CRON、SYSTEM、SUBAGENT |
| project_id | 项目任务必填 |
| agent_id / agent_version_id | 实际执行 Agent 及不可变版本 |
| title / safe_summary | 可查询且不包含敏感参数的摘要 |
| status | 当前投影状态 |
| root_task_id / parent_task_id | 父子任务关系 |
| session_id / root_session_id | AgentScope 会话关系 |
| trigger_type / trigger_ref | console、channel、cron job、heartbeat、subagent 等 |
| idempotency_key | 防止渠道重放或客户端重试重复创建 |
| created_at / updated_at | 生命周期时间 |
| retention_class | 任务数据保留策略 |

#### enterprise_task_run

表示 Task 的一次执行尝试。

| 字段 | 说明 |
| --- | --- |
| run_id | 每次执行唯一 ID |
| task_id | 所属 Task |
| attempt | 从 1 开始的执行次数 |
| state / phase | 执行状态和当前阶段 |
| executor_node | 当前单节点实例标识，为未来恢复保留 |
| model_provider / model_id | 实际使用模型 |
| started_at / ended_at | 执行时间 |
| timeout_at | 任务总超时 |
| cancel_requested_at | 用户或系统请求取消时间 |
| error_code / safe_error | 结构化错误，不默认保存底层敏感错误正文 |
| input_tokens / output_tokens | 用量 |
| estimated_cost | 按企业价格表计算的估算成本 |
| policy_snapshot_hash | 本次执行的授权、审批和配额策略快照 |

#### enterprise_task_event

表示不可变的生命周期事实。

| 字段 | 说明 |
| --- | --- |
| event_id | 全局唯一 ID |
| task_id / run_id | 关联任务和执行 |
| sequence | Run 内单调递增序号，UNIQUE(run_id, sequence) |
| schema_version | 事件协议版本 |
| event_type | 事件类型 |
| occurred_at | 事件时间 |
| actor_type / actor_id | USER、AGENT、SYSTEM、CHANNEL |
| request_id / trace_id | HTTP 和追踪关联 |
| payload_json | 已经过类型校验、大小限制和脱敏的载荷 |
| visibility | OWNER、PROJECT、APPROVER、AUDITOR、ADMIN |
| payload_hash | 完整性校验和去重辅助 |

Task 和 Run 表是查询投影，Event 表是执行事实。状态更新和事件追加应处于同一数据库事务，或者通过 outbox 确保最终一致。

### 7.3 状态机

~~~mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> QUEUED
    QUEUED --> ADMISSION_CHECK
    ADMISSION_CHECK --> WAITING_APPROVAL
    ADMISSION_CHECK --> RUNNING
    ADMISSION_CHECK --> REJECTED
    WAITING_APPROVAL --> RUNNING
    WAITING_APPROVAL --> REJECTED
    WAITING_APPROVAL --> EXPIRED
    RUNNING --> WAITING_APPROVAL
    RUNNING --> SUCCEEDED
    RUNNING --> FAILED
    RUNNING --> CANCEL_REQUESTED
    RUNNING --> TIMED_OUT
    CANCEL_REQUESTED --> CANCELLED
    CANCEL_REQUESTED --> CANCEL_FAILED
    FAILED --> QUEUED: retry creates next Run
    CANCEL_FAILED --> FAILED
    SUCCEEDED --> [*]
    CANCELLED --> [*]
    REJECTED --> [*]
    EXPIRED --> [*]
    TIMED_OUT --> [*]
~~~

不要使用 idle 表示 completed。idle 只表示当前没有执行，不代表任务成功。终态必须明确区分 SUCCEEDED、FAILED、CANCELLED、TIMED_OUT、REJECTED 和 EXPIRED。

### 7.4 建议事件类型

第一版至少包含：

- task_created
- task_queued
- admission_started
- admission_allowed
- admission_denied
- run_started
- turn_started
- phase_changed
- model_request_started
- model_request_completed
- model_request_failed
- tool_started
- permission_requested
- approval_created
- approval_resolved
- tool_completed
- tool_failed
- usage_recorded
- subtask_spawned
- subtask_completed
- cancel_requested
- cancel_propagated
- run_succeeded
- run_failed
- run_cancelled
- run_timed_out
- result_archived

不要把每一个流式文本 token 写入 enterprise_task_event。文本流继续走 Chat/Session 存储和 SSE；任务事件只记录生命周期里程碑，避免 SQLite 写放大。

### 7.5 关联 ID

所有入口和内部调用统一携带：

| ID | 作用 |
| --- | --- |
| request_id | 一次 HTTP、Webhook 或内部入口请求 |
| trace_id | 分布式追踪 |
| task_id | 用户可见的一件事 |
| run_id | 一次执行尝试 |
| session_id | AgentScope 会话 |
| tool_call_id | 一次工具调用 |
| approval_id | 一次审批 |
| root_task_id | 委派树根任务 |
| parent_task_id | 直接父任务 |

客户端可以提供 idempotency_key，但不能自行指定 owner_user_id、scope_id、agent_id 或审批人。

## 8. 具体改造建议

### 8.1 统一任务中心

#### 借鉴 Grok

Grok 的后台命令、Subagent、Monitor 和 Scheduler 都返回稳定 task_id，并提供查询、等待、取消和完成通知。它的 Session 同时区分权威事件流、摘要、信号和计划状态。

#### MelonPaw 改动

后端：

1. 新建 EnterpriseTaskService，作为 Chat、Cron、Heartbeat、Channel 和 Subagent 的统一入口。
2. 新建 EnterpriseTaskStore，存储 Task、Run、Event 和幂等键。
3. AgentRunner 在 AgentScope 事件边界追加 TaskEvent。
4. CronExecutor 不再自行生成孤立 run_id，而是通过 TaskService 创建 AUTOMATION Task/Run。
5. ChannelManager 将每条通过验签的入站消息映射为 CHANNEL Task，并保存渠道消息 ID 作为幂等键。
6. ApprovalService 使用 task_id、run_id、tool_call_id 和 approval_id，不再只依赖 session_id/request_id。
7. InboxStore 只做用户通知投影，不再承担任务事实存储。
8. ChatManager 保存 task_id 关联，Chat 删除不能删除任务审计和用量记录。

前端：

1. TaskCenter 改为调用 <code>/api/tasks</code>，不再调用 listChats。
2. 支持按状态、来源、项目、Agent、发起人、时间和结果过滤。
3. 任务详情展示时间线、审批、工具、用量、结果、子任务和审计链接。
4. 非对话任务不强制提供“进入会话”按钮。
5. 允许有权限的用户取消、重试和归档。

验收：

- 同一任务重试后 task_id 不变，run_id 和 attempt 递增。
- Cron、渠道和 Web 任务在同一列表可查。
- 用户不能通过枚举 task_id 读取他人任务。
- 任务完成后服务重启，状态和时间线仍可恢复。

### 8.2 审批和审计

#### 借鉴 Grok

Grok 把 permission_requested、permission_resolved、tool outcome 和取消原因作为明确事件，并区分 permission_rejected、permission_cancelled、hook_denied 和 tool error。

#### 企业级改动

审批记录必须包含：

- approval_id、task_id、run_id、session_id、tool_call_id。
- 任务所有者、发起人、Agent、项目和资源范围。
- 工具实际名称、展示名称、MCP Server、参数摘要和参数哈希。
- 风险等级、命中的规则、审批策略版本。
- 候选审批人、实际审批人、代理审批关系。
- 创建、过期、决策和继续执行时间。
- ALLOW、DENY、EXPIRED、CANCELLED。
- 决策理由和继续执行后的结果事件。

安全规则：

1. 参数在等待审批期间发生变化，原审批立即失效。
2. 高风险操作可配置禁止发起人自批。
3. 审批人必须在决策时重新校验角色和项目关系。
4. 父任务审批不能自动批准权限更大的子任务。
5. 审批超时必须产生终态事件，不能永久挂起。
6. 审批 API 必须幂等，重复点击不能重复恢复 Agent。

审计记录必须覆盖：

- 用户、角色和项目管理。
- Agent 创建、修改、发布、下线和回滚。
- 模型、Provider、MCP、技能、插件和安全策略变更。
- 密钥创建、轮换、撤销和恢复，不记录明文。
- Task 创建、委派、取消、重试和结果归档。
- break-glass 访问。
- 备份、恢复和数据删除。

单机 SQLite 中的 append-only 表不能真正提供“不可抵赖”。若验收要求不可抵赖，应增加每日哈希链根、企业签名，并定期导出到外部日志系统或 WORM 存储。

### 8.3 项目 Agent

项目 Agent 应先于开放 Subagent 完成。

建议流程：

1. 项目 OWNER 或 MANAGER 创建 Agent 草稿。
2. 选择模型策略、技能、工具、MCP、知识源和审批策略。
3. 发布为不可变 agent_version。
4. 项目成员创建任务时，由服务端校验项目角色和 Agent 发布状态。
5. 每个任务保存具体执行用户，状态仍按 userId、sessionId、agentId 隔离。
6. 项目 Workspace 只挂载当前项目目录；不能同时挂载用户个人 Workspace。
7. 项目记忆只写项目命名空间，个人偏好只能作为只读且经过明确策略的输入。
8. 结果默认归项目，可按策略给发起人私有草稿区。

第一阶段不要允许多人同时写同一个 AgentScope session。共享的是 Agent 定义和项目知识，不是一个无锁共享会话。每个任务或每个用户会话保持单写者；协作通过任务评论、关注人、结果共享和显式交接完成。

### 8.4 Subagent 和企业委派

#### 借鉴 Grok

- 最大深度固定为 1。
- capability_mode 区分 read-only、read-write、execute 和 all。
- resume_from 必须继承来源类型、persona、模型和会话状态。
- 父任务可以观察、等待和取消子任务。

#### 企业适配

子 Agent 的最终能力必须是以下集合的交集：

<code>effective_capability = parent_delegated_capability ∩ target_agent_published_capability ∩ requester_rbac ∩ project_policy ∩ runtime_safety_policy</code>

不能因为目标 Agent 配置更强而获得父任务没有的工具、数据或网络能力。

必须增加：

- delegation_id、root_task_id、parent_task_id。
- 委派发起用户和父 Agent。
- 目标 Agent 的 scope、版本和可见性。
- 允许的数据范围、工具范围和最大输出。
- 子任务 token 预算、成本预算、并发名额和超时。
- 审批传播策略。
- 父任务取消向全部子任务传播。
- 子任务完成后的结果摘要和可见性。
- 服务重启后的恢复或明确中断状态。

初始开放约束：

- depth=1。
- 每个父任务最多 3 个子任务。
- 每个用户和项目有独立并发上限。
- 只开放平台预定义角色，不允许用户提交任意系统提示词作为目标 Agent。
- 默认 read-only；写入和 execute 需要显式发布能力。
- 不支持子任务再委派。

### 8.5 配额、并发和成本

多用户共享一套部署时，必须在模型调用之前做准入，不能只在调用结束后统计。

建议层级：

| 层级 | 控制内容 |
| --- | --- |
| enterprise | 全局模型并发、每日 token、自动化总频率 |
| provider | Provider 并发、RPM、TPM、熔断 |
| project | 项目预算、项目并发、项目自动化频率 |
| user | 用户预算、并发、每日任务数 |
| agent | Agent 并发、单任务 turn 上限 |
| task | 最大 token、最大时长、最大工具调用、最大子任务 |

执行方式：

1. 创建 Run 时先进行额度预留。
2. 获得并发 permit 后才调用模型。
3. 每次 turn 追加 usage_recorded。
4. Run 结束时按实际用量结算并释放预留。
5. 超限返回结构化 admission_denied，不进入 Agent loop。
6. 自动化和交互任务使用不同队列权重，防止 Cron 占满交互容量。
7. 审计和告警记录管理员调整额度的前后差异。

不要把 user_id、task_id 直接作为 Prometheus 标签，否则会造成高基数。用户、项目和任务归因应保存在 Usage Ledger 和结构化事件中；指标只保留 model、provider、task_type、outcome 等受控维度。

### 8.6 MCP

#### 当前方向

当前 McpClientRegistry 已经按 Agent 隔离管理器，McpAccessPolicyService 在调用时根据 Agent、用户和渠道重新判断，这个方向正确。

#### 建议补齐

连接治理：

- startup_timeout。
- 默认 tool_timeout 和逐工具覆盖。
- liveness 检测。
- stdio 进程退出检测。
- 指数退避和抖动。
- 最大重启次数和熔断。
- 配置变更时保留健康的未变连接。
- 服务关闭时完整停止子进程。

结果治理：

- 内联 MCP 结果默认上限，例如 20 KiB。
- 超限内容落入当前授权 Workspace 的 tool_results 目录。
- Event 只保存摘要、大小、哈希和受控下载引用。
- 明确 text、image、resource、error 等内容类型。
- 对模型输入再做 token 级裁剪。

安全治理：

- PROJECT Agent 使用项目或服务身份凭据，不能复用某位成员的个人 OAuth。
- PERSONAL Agent 的 OAuth 只能绑定本人。
- MCP 凭据必须加密落盘并支持轮换。
- HTTP MCP 做 URL scheme、DNS、重定向、内网地址和云元数据 SSRF 检查。
- stdio MCP 只允许在受控容器内启动，不允许普通用户在宿主机运行任意 command。
- 工具清单变化要产生审计事件；调用使用启动时或刷新后的 schema 快照。
- 默认 ASK 或 DENY，不能因为工具声明 readOnly 就无条件信任第三方 Server。

### 8.7 记忆和知识

#### 借鉴 Grok

Grok 使用 Markdown 作为可审阅事实源，SQLite FTS5 做全文检索，向量索引做语义召回，再叠加来源权重、时间衰减和 MMR 去重。

#### 企业命名空间

建议记忆层级：

| 层级 | 所有者 | 默认可见性 |
| --- | --- | --- |
| enterprise | 企业 | 由管理员发布，全员或指定角色只读 |
| project | 项目 | 项目成员按角色读取，指定角色写入 |
| personal | 用户 | 仅本人 |
| session | 用户或任务 | 任务参与者和授权审计角色 |

强制顺序应为：

1. 根据认证用户、项目成员和任务 scope 过滤允许访问的命名空间。
2. 在允许集合内执行 FTS 和向量检索。
3. 合并分数、来源权重和时间衰减。
4. MMR 去重。
5. 做内容级敏感信息过滤。
6. 注入 Agent 上下文并记录引用来源。

不能先在全企业向量库检索，再在结果阶段过滤。相似度、命中数量和错误信息本身也可能泄漏其他用户或项目的存在。

其他要求：

- Embedding Provider 也受企业模型和数据出境策略控制。
- 删除用户、项目或文档时同步删除向量和全文索引。
- 长期记忆写入需要可审阅、可撤回和保留期。
- Session 记忆随时间衰减，企业和项目发布知识不衰减。
- 自动总结不得把 MCP 密钥、Shell 命令、文件绝对路径和审批参数写入长期记忆。

### 8.8 Session、状态和恢复

建议分层：

| 数据 | 权威存储 |
| --- | --- |
| 企业 Task、Run、Event、Approval、Usage、Audit | enterprise.db 或未来 PostgreSQL |
| AgentScope 会话上下文 | 官方 AgentStateStore |
| Chat 文本和工具展示内容 | Chat/Session 存储 |
| 大型工具结果和附件 | 受控 Workspace 文件 |
| 可查询历史索引 | HistoryStore，可重建 |
| 用户通知 | InboxStore，可由事件投影 |

规则：

- TaskEvent 不复制完整 Chat。
- HistoryStore 和 InboxStore 不是企业任务事实源。
- 服务启动时扫描非终态 Run：能够证明可恢复的才恢复，否则标记 INTERRUPTED 并提供重试。
- 同一 session 只允许一个活动 Run，使用数据库唯一约束或租约。
- AgentStateStore 写入必须保持原子性，损坏时不能静默创建空状态并继续覆盖。
- 删除 Chat 不等于删除审计、用量和 Task。

### 8.9 SQLite 和单实例部署

SQLite 适合当前“单应用实例、单企业、常规并发”的产品边界，但需要明确运行前提：

1. enterprise.db、audit.db、history.db 必须位于本地支持 POSIX/Windows 文件锁的磁盘。
2. NFS、SMB、NAS 同步盘和多主机共享目录默认不支持 WAL。
3. 启动时检测文件系统类型并给出明确错误，而不是运行后出现 SQLITE_BUSY、损坏或 SIGBUS。
4. 所有数据库统一 busy_timeout、journal_mode、synchronous 和 quick_check 策略。
5. 备份使用 SQLite Online Backup API 或协调 checkpoint，不能直接复制正在写入的 db 文件。
6. TaskEvent 高频写入采用短事务和批量写，不保存 token 流。
7. 为 Task、Run、Event、Approval 和 Usage 建立必要索引并设置保留策略。

如果未来出现以下任一需求，应把企业任务、审计和身份库迁移到 PostgreSQL：

- 同时运行多个应用实例。
- 高可用主备自动切换。
- 多节点任务 Worker。
- 需要数据库级细粒度并发和大规模事件分析。

AgentScope 文件状态和 Workspace 可以继续保留文件存储，但企业控制面不能跨多节点继续依赖本地 SQLite。

### 8.10 沙箱、网络和取消

Grok 的价值主要是其故障语义和测试，不是本地沙箱实现。

应补齐：

- 启动预检 Docker daemon、镜像、非 root 用户、CPU、内存、PID、磁盘和网络策略。
- 容器只挂载当前 task scope 允许的 Workspace。
- PROJECT Task 不挂载 PERSONAL Workspace。
- 默认丢弃 capabilities、no-new-privileges、只读根文件系统和受控临时目录。
- 受限出口必须阻断企业内网、localhost、宿主机网关和云元数据地址。
- 取消传播路径：HTTP cancel → TaskService → AgentRunner subscription → AgentScope interrupt → Docker exec process group → MCP/Subagent cancel。
- 先优雅停止，再在 SLA 内强制回收进程或容器。
- cancel_requested 不等于 cancelled，必须等待实际回收结果。
- 进程已退出、重复取消和服务重启后的取消都必须幂等。

### 8.11 可观测性

#### 建议指标

- task_runs_total{task_type,outcome}
- task_run_duration_seconds{task_type,outcome}
- active_task_runs{task_type}
- queue_wait_seconds{task_type}
- model_tokens_total{provider,model,type}
- model_request_duration_seconds{provider,model,outcome}
- tool_calls_total{tool_category,outcome}
- tool_permission_decisions_total{decision,risk}
- approval_wait_seconds{risk,outcome}
- mcp_connections{transport,status}
- mcp_tool_duration_seconds{transport,outcome}
- sandbox_cancellations_total{outcome}
- quota_rejections_total{scope_type,reason}

#### 建议 Trace 结构

<code>HTTP/Webhook span → task admission span → run span → turn span → model span/tool span → approval wait span → result archive span</code>

#### 隐私要求

- Telemetry 默认不包含 prompt、response、文件路径、Shell 命令和工具参数。
- MCP、技能和插件名默认折叠为类别。
- 内容采集必须单独开关并记录管理员审计。
- exporter token 通过环境变量或外部 Secret Provider 注入，不写 config.yaml。
- 所有字符串经过密钥形态、JWT、Bearer、私钥、URL 查询参数和用户目录脱敏。
- 导出前使用闭合 schema 校验，出现未知字段或未脱敏内容时丢弃事件。
- task_id、user_id 和 project_id 不作为常规指标标签；需要时进入受控日志或 Trace baggage。

### 8.12 敏感配置

Grok 的 xai-grok-secrets 只提供日志脱敏；MCP credential store 也主要依赖 0600 权限和原子写，不能解决 MelonPaw 的 P0 密钥加密。

企业实现应包含：

- 主密钥来自环境、操作系统密钥库或外部 KMS，不与密文保存在同一配置文件。
- 数据密钥进行 envelope encryption。
- 每个密文保存 key_version、algorithm、nonce、ciphertext 和认证标签。
- API 返回固定掩码，不返回可判断密钥长度的内容。
- 支持旧明文配置一次性迁移。
- 支持轮换、双读过渡、回滚和备份恢复。
- 密钥使用、轮换和失败进入审计，但不记录明文或完整密文。
- 备份文档明确主密钥是否单独备份，以及丢失后的不可恢复性。

### 8.13 Hooks、插件和技能

Grok 支持命令和 HTTP hooks，但企业服务不应直接开放“配置一个宿主机命令并在 PreToolUse 执行”。

建议：

- 先建立内部类型化 DomainEventBus。
- 企业插件只能订阅允许的事件和调用明确 capability。
- 高风险 PreToolUse 策略组件异常时 fail-closed。
- 通知、指标等被动订阅异常可以 fail-open，但必须记录健康状态。
- 插件发布必须有版本、校验和、来源、权限声明、审核和回滚。
- 技能共享池使用草稿、审核、发布、版本和分发，不直接让项目 Agent 扫描任意用户目录。
- Task 记录实际使用的 skill version 和 plugin version。

### 8.14 外部渠道

每条渠道消息进入 Agent 前必须完成：

1. 渠道独立签名校验。
2. 时间戳窗口。
3. nonce 或消息 ID 重放缓存。
4. 来源和速率限制。
5. 渠道账号到企业用户或待审批访客的映射。
6. Agent 和 scope 服务端解析。
7. 使用 channel message ID 生成幂等键。
8. 创建 CHANNEL Task。
9. 记录响应投递结果和失败重试。

渠道 payload 中的 agent_id、user_id、project_id 和 workspace_dir 都只能作为不可信提示，不能直接进入 RuntimeContext。

## 9. 建议数据库对象

建议由独立 EnterpriseTaskStore 管理，不把任务表继续塞入 EnterpriseIdentityStore：

| 表 | 关键约束 |
| --- | --- |
| enterprise_agent_definitions | agent_id 唯一；scope_type/scope_id 不可为空 |
| enterprise_agent_versions | UNIQUE(agent_id, version)；发布后不可修改 |
| enterprise_agent_bindings | PERSONAL 用户或 PROJECT 项目绑定唯一 |
| enterprise_tasks | task_id 主键；所有者、scope、Agent 版本必填 |
| enterprise_task_runs | UNIQUE(task_id, attempt) |
| enterprise_task_events | UNIQUE(run_id, sequence)；append-only |
| enterprise_task_idempotency | UNIQUE(source_type, source_id, idempotency_key) |
| enterprise_approvals | approval_id 主键；tool_input_hash 和 policy_version 必填 |
| enterprise_usage_ledger | task/run/user/project/agent/provider 归因 |
| enterprise_audit_events | append-only；包含 actor、target、before/after hash、request_id |
| enterprise_delegations | parent/child、能力、数据范围、预算和过期时间 |

建议索引：

- tasks(owner_user_id, created_at DESC)
- tasks(project_id, status, created_at DESC)
- tasks(agent_id, status, created_at DESC)
- runs(task_id, attempt DESC)
- events(run_id, sequence)
- events(task_id, occurred_at)
- approvals(approver_scope, status, created_at)
- usage(user_id, occurred_at)
- usage(project_id, occurred_at)
- audit(actor_id, occurred_at)
- audit(target_type, target_id, occurred_at)

## 10. 建议 API

### 10.1 Task

- POST /api/tasks
- GET /api/tasks
- GET /api/tasks/{taskId}
- GET /api/tasks/{taskId}/runs
- GET /api/tasks/{taskId}/events
- GET /api/tasks/{taskId}/result
- POST /api/tasks/{taskId}/cancel
- POST /api/tasks/{taskId}/retry
- GET /api/tasks/{taskId}/stream

### 10.2 Approval

- GET /api/approvals
- GET /api/approvals/{approvalId}
- POST /api/approvals/{approvalId}/allow
- POST /api/approvals/{approvalId}/deny

### 10.3 Project Agent

- POST /api/projects/{projectId}/agents
- POST /api/projects/{projectId}/agents/{agentId}/versions
- POST /api/projects/{projectId}/agents/{agentId}/publish
- POST /api/projects/{projectId}/agents/{agentId}/rollback
- GET /api/projects/{projectId}/agents/{agentId}/versions

所有 API 都应先从认证 Principal 解析资源范围，再查询实体。不能先按 ID 读取完整对象，再在 Controller 最后过滤。

## 11. 代码改动落点

### 11.1 建议新增

| 建议路径 | 责任 |
| --- | --- |
| melon-app/.../task/EnterpriseTaskService.java | 创建、准入、执行、取消、重试和恢复 |
| melon-app/.../task/EnterpriseTaskStore.java | Task、Run、Event、幂等和查询 |
| melon-app/.../task/TaskEventPublisher.java | 类型化事件追加与投影 |
| melon-app/.../task/TaskAuthorizationService.java | Task 列表、详情、结果和事件可见性 |
| melon-app/.../task/TaskAdmissionService.java | 配额、并发和预算预留 |
| melon-app/.../task/TaskRecoveryService.java | 启动时处理中断 Run |
| melon-app/.../task/TaskController.java | 正式版本化 Task API |
| melon-app/.../service/UsageLedgerService.java | 用户、项目、Agent 和 Provider 用量归因 |
| melon-app/.../service/EnterpriseAuditService.java | 管理操作和任务审计 |
| melon-app/.../agent/AgentDefinitionService.java | Agent 草稿、版本、发布和绑定 |

以上路径中的省略部分建议统一为 <code>src/main/java/com/melon/app</code>。具体包名可按现有模块约定调整。

### 11.2 建议修改

| 当前文件 | 建议改动 |
| --- | --- |
| EnterpriseAgentAccessService.java | 增加 PROJECT/SERVICE scope 解析；继续禁止客户端决定所有者；项目 Agent 只接受 TaskAuthorizationService 已授权上下文 |
| EnterpriseIdentityStore.java | 保持身份、RBAC、项目成员；不要继续承载高频 TaskEvent |
| AgentRunner.java | 接收 TaskExecutionContext；映射 AgentScope 生命周期事件；传播取消、usage、approval 和 trace |
| ChatManager.java | Chat 关联 task_id/run_id；不再充当 Task 主存储 |
| MelonPawEnvelopeMapper.java | SSE 事件加入 task_id、run_id、sequence 和 schema_version |
| CronExecutor.java | 通过 TaskService 创建 AUTOMATION Task；幂等和执行者身份由 TaskService 统一 |
| ChannelManager.java | 通过 TaskService 创建 CHANNEL Task；保存渠道幂等键和投递状态 |
| ApprovalService.java | 使用 approval_id + task/run/tool_call 关联；增加过期、参数哈希、审批人和幂等 |
| AuditLogService.java | 扩展为统一企业审计，或由 EnterpriseAuditService 替代并保留兼容适配 |
| InboxStore.java | 只做事件通知投影 |
| HistoryStore.java | 作为可重建历史索引；增加网络文件系统策略和统一 SQLite 配置 |
| McpClientManager.java | 增加启动/工具超时、结果上限、liveness、退避、熔断、取消和错误分类 |
| McpClientRegistry.java | PROJECT Agent 使用项目凭据域；PERSONAL Agent 继续个人隔离 |
| McpAccessPolicyService.java | 规则加入 scope、project、agent_version 和策略版本；高风险默认 ASK/DENY |
| MultiAgentManager.java | Agent 定义与 session state 分离；按 agent_version 构建；为 Subagent 保留受控入口 |
| MelonAgentFactory.java | 注入 TaskExecutionContext 中的有效能力、scope 和预算；不能从工具参数恢复所有权 |
| WorkspaceManager.java | 支持 PERSONAL/PROJECT/SERVICE 受控根；保持直接子目录和符号链接约束 |
| StateStoreConfig.java | 明确单节点 JSON 状态的原子写、备份和未来外部 Store 切换条件 |
| ConfigManager.java | 敏感字段改为 SecretRef/密文；Agent 发布后生成版本快照 |
| TaskCenter/index.tsx | 改用 Task API，展示非对话任务、来源、项目、审批、用量、重试和取消 |
| Inbox 页面 | 审批和任务通知跳转到统一 Task/Approval 详情 |
| Chat 页面 | 展示关联 Task 状态，但不承担企业任务管理功能 |

## 12. 分阶段实施路线

### Phase 0：生产安全基线

完成现有 P0 中与任何共享 Agent 无关但必须先关闭的风险：

- 初始化令牌部署验收。
- 敏感配置加密和轮换。
- 管理操作审计。
- 外部渠道签名、重放保护和限流。
- Docker 停止、网络和跨用户隔离验收。
- SQLite 本地文件系统预检与一致备份。

退出条件：个人 Agent 多用户并发运行时，不存在已知的跨用户数据、密钥和容器边界漏洞。

### Phase 1：统一企业任务底座

当前进度：PERSONAL 与 PROJECT 范围的 Chat、Cron、Heartbeat、Channel、Approval、Usage、Inbox 和审批 Audit 已接入；失败任务重试会复用原 Task 并创建递增 attempt 的新 Run。

- 实现 Task、Run、Event 和幂等。
- Chat、Cron、Heartbeat 和 Channel 全部接入。
- TaskCenter 使用正式 Task API。
- Approval、Inbox、Usage 和 Audit 关联统一 task_id/run_id。
- 加入取消、重试和启动恢复。

退出条件：任意执行都能从 task_id 查询完整状态、发起人、Agent、审批、用量、结果和审计。

### Phase 2：项目 Agent

当前进度：Agent Definition、配置 Version、项目唯一 Binding、PROJECT Workspace、项目成员执行授权、项目任务结果/审批和管理页面已落地；项目文档、技能与 MCP 使用既有管理链路并按项目 Agent 隔离，运行中禁止修改。项目预算/并发，以及技能文件、MCP 配置、项目文档正文与 Agent Version 的一致快照和回滚仍未完成。

- Agent Definition、Version、Binding 和发布流程。
- PROJECT scope Workspace、知识和凭据。
- 项目成员执行授权。
- 项目任务结果和审批。
- 项目预算和并发。

退出条件：项目成员只能在授权项目中执行已发布 Agent，不能读取其他项目或个人状态。

### Phase 3：企业运营

- OpenTelemetry 指标、Trace 和告警。
- 用户、项目、Agent 和 Provider 配额。
- 自动备份、恢复演练和数据生命周期。
- 角色管理页面。
- Agent/技能/MCP 版本和变更审计。

退出条件：运营人员能定位任务失败、成本异常、MCP 故障、审批积压和沙箱回收失败。

### Phase 4：受控 Subagent

- 仅 depth=1。
- 能力交集。
- Delegation 记录。
- 子任务预算、并发、超时和取消。
- 审批传播。
- 父子任务 UI。
- 越权和循环委派测试。

退出条件：子 Agent 无法扩大父任务权限，所有子任务都能独立归因、取消、审计和恢复。

### Phase 5：高级记忆和自动化

- 企业/项目/个人/会话记忆命名空间。
- FTS + 向量 + 时间衰减 + MMR。
- Embedding 数据出境策略。
- 自动化频率和结果去重。
- 记忆删除、离职交接和项目归档。

## 13. 企业多用户验收矩阵

### 13.1 身份和所有权

- 用户 A 不能通过 task_id、run_id、session_id、approval_id 读取用户 B 数据。
- 用户 A 不能通过 X-Agent-Id、请求体、Channel metadata 或工具参数切换到用户 B。
- SYSTEM_ADMIN 没有 data:breakglass 时不能读取他人 PERSONAL 内容。
- AGENTS_MANAGE 允许管理共享 Agent，不允许进入 PERSONAL Workspace。
- 项目成员退出项目后，新请求立即失效；历史结果按保留策略处理。

### 13.2 任务

- Chat、Cron、Channel 和 Project Task 使用同一状态机。
- 重复 Webhook 只创建一个 Task。
- 重试创建新 Run，不覆盖原失败证据。
- 终态不可回退到 RUNNING。
- 同一 session 不允许两个活动写 Run。
- Task 删除或归档不删除审计和法定保留数据。

### 13.3 审批

- 审批人无项目权限时无法审批。
- 发起人在禁止自批策略下无法批准自己的高风险操作。
- 参数变化后旧审批不能继续执行。
- 审批超时后 Agent 不会继续运行。
- 重复 allow/deny 请求幂等。
- 父任务审批不能批准超出范围的子任务。

### 13.4 配额

- 一个用户耗尽并发不会阻塞所有其他用户。
- Cron 队列不能挤占全部交互名额。
- Project 预算超限只影响该项目。
- Provider 限流触发退避，不产生无限重试。
- 子任务用量计入根任务、用户、项目和目标 Agent。

### 13.5 MCP

- MCP 进程崩溃后状态可见，并按上限重启。
- 超时工具被取消，不长期占用线程或容器。
- 20 KiB 以上结果不直接进入模型上下文。
- PERSONAL OAuth 不会被 PROJECT Agent 使用。
- HTTP MCP 不能访问 localhost、内网和云元数据。
- MCP Server 返回伪造 readOnly 元数据时仍按企业风险策略审批。

### 13.6 Workspace 和沙箱

- PERSONAL、PROJECT、SERVICE 挂载互不重叠。
- 符号链接不能跳出授权根。
- 取消 sleep 等长命令能在 SLA 内回收进程。
- 用户 A 无法读取用户 B 的容器、快照、临时文件和 tool_results。
- bridge 网络不能访问企业内网和云元数据。
- Docker 不可用时启动预检明确失败，不静默退回宿主机执行。

### 13.7 记忆

- 用户 A 的查询不会命中用户 B 的记忆。
- 项目 A 的向量检索不会泄漏项目 B 的命中数量。
- 用户退出项目后无法继续搜索项目记忆。
- 删除文档后全文和向量索引同步删除。
- 自动总结不保存密钥、审批参数和绝对路径。
- Embedding Provider 禁用时能够降级为全文检索。

### 13.8 审计和可观测性

- 管理写操作都包含 actor、before/after、request_id 和结果。
- Telemetry 默认不包含提示词、响应、路径、命令和工具参数。
- 未知 Telemetry 字段被拒绝或丢弃。
- user_id/task_id 不导致 Prometheus 高基数失控。
- 任意失败任务可以通过 request_id、task_id、run_id 和 trace_id 串起完整链路。
- 审计导出和保留期可验证。

### 13.9 备份和恢复

- 备份包含 enterprise.db、AgentStateStore、Workspace、Task 结果和必要索引。
- 备份不包含已消费 bootstrap token。
- 密钥材料按设计单独备份或明确不可恢复。
- 恢复后 task_id、session_id 和 Agent 绑定不串用户。
- 数据库 quick_check 通过。
- 未完成 Run 被恢复或明确标记 INTERRUPTED，不伪装成成功。

### 13.10 负载

- 并发用户数、平均任务时长、模型延迟和工具调用比例有明确压测模型。
- SQLite 写锁等待和 busy 次数可观测。
- TaskEvent 写入不会阻塞 SSE 文本流。
- 大 MCP 输出、批量审批和大量 Cron 同时发生时系统仍可取消和管理。
- 达到容量上限时返回可预测的 QUEUED 或 admission_denied，不出现随机超时。

## 14. 从 Grok 借鉴代码时的许可和维护要求

grok-build 第一方代码是 Apache-2.0，但部分工具实现来源于 OpenAI Codex 和 SST OpenCode。

若复制或翻译具体实现：

1. 固定来源 commit 和 SOURCE_REV。
2. 保留 LICENSE、NOTICE 和原版权信息。
3. 在修改文件中标明已经修改。
4. 对第三方移植内容继续保留对应 OpenAI/SST 许可声明。
5. 优先提炼协议、算法和测试用例，不直接复制大块运行时代码。
6. 不依赖 Grok 的内部 API 稳定性；公开仓库只是周期同步快照且不接受外部 PR。

## 15. Grok Build 源码导航

本地参考仓库根目录为 <code>/Users/melon/IdeaWorkSpace/Github/grok-build</code>。建议按问题阅读，不要从根目录顺序通读 135 万行 Rust。

| 主题 | Grok Build 路径 | 重点 |
| --- | --- | --- |
| 项目定位 | README.md | 公开仓库边界、crate 布局、构建方式和 monorepo 同步属性 |
| Session | crates/codegen/xai-grok-pager/docs/user-guide/17-sessions.md | 权威事件流、摘要、信号、恢复、压缩和 rewind 的数据分层 |
| 后台任务 | crates/codegen/xai-grok-pager/docs/user-guide/20-background-tasks.md | task_id、wait_any/wait_all、kill、scheduler 和状态展示 |
| 权限管线 | crates/codegen/xai-grok-pager/docs/user-guide/22-permissions-and-safety.md | hook、deny/ask/allow、记忆授权、默认策略和沙箱组合顺序 |
| Hooks | crates/codegen/xai-grok-pager/docs/user-guide/10-hooks.md | Session、Tool、Permission、Subagent、Compact 生命周期事件 |
| Subagent | crates/codegen/xai-grok-pager/docs/user-guide/16-subagents.md | capability_mode、worktree 隔离、resume_from 和 depth=1 |
| Subagent 恢复校验 | crates/codegen/xai-grok-subagent-resolution/src/resume.rs | 恢复时类型和 persona 身份不允许改变 |
| 任务事件 | crates/codegen/xai-file-utils/src/events/types.rs | Turn、Phase、Tool、Permission、Cancel、Goal 等版本化事件 |
| 生命周期扩展 | crates/codegen/xai-agent-lifecycle/src | Host 无关的 session/turn contributor 边界 |
| 工具协议 | crates/common/xai-tool-protocol/src | 握手、能力、通知、取消、错误码和 wire schema |
| 工具运行时 | crates/common/xai-tool-runtime/src | Tool dispatch、streaming、notification 和错误转换 |
| MCP 入口 | crates/codegen/xai-grok-mcp/src/lib.rs | MCP 依赖隔离和模块职责 |
| MCP 连接 | crates/codegen/xai-grok-mcp/src/servers.rs | 初始化、工具超时、错误分类、刷新和调用 |
| MCP 存活 | crates/codegen/xai-grok-mcp/src/liveness.rs | Transport 关闭检测、任务取消和 watcher 生命周期 |
| MCP 凭据 | crates/codegen/xai-grok-mcp/src/credentials.rs | 文件锁、原子写和 0600；注意它不是加密方案 |
| MCP 调度恢复 | crates/codegen/xai-grok-shell/src/session/mcp_dispatcher.rs | 状态合并、去抖、重启和陈旧事件抑制 |
| Memory 文档 | crates/codegen/xai-grok-pager/docs/user-guide/13-memory.md | 全局、Workspace、Session 层级和配置语义 |
| Memory 检索 | crates/codegen/xai-grok-memory/src/search.rs | FTS5、向量、时间衰减、来源权重、MMR 和降级 |
| Memory 索引 | crates/codegen/xai-grok-memory/src/index.rs | SQLite FTS/vec 索引和重建 |
| 会话压缩 | crates/common/xai-grok-compaction/src | 压缩、失败分类、重试和历史组装 |
| SQLite | crates/codegen/xai-sqlite-journal/src/lib.rs | WAL、NFS 检测、TRUNCATE 和 per-host DB |
| 日志脱敏 | crates/codegen/xai-grok-secrets/src/sanitizer.rs | API Key、JWT、Bearer、私钥、URL 和用户目录脱敏 |
| Telemetry | crates/codegen/xai-grok-pager/docs/user-guide/24-monitoring-usage.md | 指标、事件、内容门控、schema 和导出前校验 |
| Sandbox | crates/codegen/xai-grok-pager/docs/user-guide/18-sandbox.md | Profile、文件系统、网络和平台边界 |
| 第三方来源 | crates/codegen/xai-grok-tools/THIRD_PARTY_NOTICES.md | Codex/OpenCode 工具来源和许可义务 |

阅读顺序建议：

1. 先读 Session、Background Tasks 和 Task Event。
2. 再读 Permissions、MCP 和 Telemetry。
3. 项目 Agent 数据模型完成后再读 Subagent。
4. 需要优化记忆时再读 Memory Search。
5. 仅在实现具体故障处理时进入对应 Rust 源码和测试。

## 16. 最终优先级清单

### 必须先做

- [ ] 敏感配置加密与轮换。
- [ ] Docker 停止、受控出口和跨用户隔离验收。
- [ ] 渠道签名、重放保护和幂等。
- [x] Task、Run、Event 统一数据模型。
- [x] Approval 与 Task/Run/ToolCall 强关联。
- [ ] 管理操作统一审计。
- [ ] SQLite 本地文件系统和一致备份策略。

### 项目 Agent 开放前

- [x] Agent Definition、配置 Version、Binding 和发布流程。
- [x] PROJECT scope Workspace、知识、MCP 隔离和成员授权。
- [x] 项目 Task 所有权、结果可见性和审批。
- [ ] 用户/项目/Agent 分层并发和预算。
- [ ] 技能、MCP、项目文档与 Agent Version 的一致快照、审计和回滚。
- [ ] 项目凭据加密、轮换和恢复。
- [x] TaskCenter 正式支持非对话任务。
- [x] Task 启动恢复和取消。
- [x] Task 失败重试创建新 Run。

### Subagent 开放前

- [ ] depth=1。
- [ ] capability intersection。
- [ ] root_task_id、parent_task_id、delegation_id。
- [ ] 子任务预算、并发、超时和取消传播。
- [ ] 审批传播和正确审批人路由。
- [ ] 父子任务 UI 和完整审计。
- [ ] 越权委派、循环委派、跨项目访问和重启恢复测试。

### 可后续增强

- [x] MCP liveness、有界退避、失败熔断和结果溢出落盘。
- [ ] OpenTelemetry 指标、Trace 和隐私门控。
- [ ] FTS + 向量 + 时间衰减 + MMR 记忆检索。
- [ ] 外部身份源、灾备演练和 WORM 审计导出。

## 17. 最终建议

对 MelonPaw-Company 来说，Grok Build 最值得借鉴的是“Agent 执行过程如何被建模、限制、观察和恢复”，而不是它的编程 Agent 产品形态。

最合理的实施顺序是：

1. 先完成现有生产安全 P0。
2. 把 Task、Run、Event 建成企业执行主干。
3. 让 Chat、Cron、Channel、Approval、Usage 和 Audit 全部挂到这条主干。
4. 再开放项目 Agent。
5. 在项目 Agent 的所有权、额度和审批已经稳定后，才开放受控 Subagent。

如果缺少统一 Task 主干，直接开放项目 Agent 或 Subagent，会同时放大当前任务归属、审批路由、配额、取消、审计和恢复的缺口。反过来，只要 Task 主干建立正确，Grok Build 中 MCP、Telemetry、Memory 和 Subagent 的优秀设计都可以逐项吸收，而不需要破坏现有 Java/AgentScope 架构。
