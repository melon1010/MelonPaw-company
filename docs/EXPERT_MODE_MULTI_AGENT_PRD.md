# 专家模式与多 Agent 编排 PRD

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 产品 | MelonPaw-Company |
| 状态 | Draft |
| 版本 | 1.1 |
| 日期 | 2026-07-21 |

## 2. 背景与产品判断

MelonPaw-Company 是单企业私有部署的 AI Agent 平台，当前已经具备：

- 每个用户一个 PERSONAL Agent，以及独立个人工作区、记忆、技能和会话。
- 按项目成员授权的 PROJECT Agent 和项目工作区。
- 受企业权限管理的 SERVICE Agent。
- AgentScope Harness Workspace、Memory、Skill、Permission 和原生 Subagent。
- 统一的 Task/Run/Event、审批、配额、取消和重试能力。
- `/mission` 下固定的 `researcher`、`executor`、`reviewer` 单层委派。

本需求包含两个相互独立但可以组合的能力：

1. **专家模式**：用户创建多个长期存在、彼此隔离的个人专家。
2. **平台级多 Agent 编排**：为现有 PERSONAL、PROJECT、SERVICE、EXPERT 等 Agent 场景提供统一的五种协作模式。

五种模式不是专家模式的附属功能。专家团只是多 Agent 编排的一种使用方式；现有 `/mission`、项目任务和受管 Agent 也可以逐步复用同一编排底座。

## 3. 产品目标

1. 用户可以创建多个仅本人可用的 Expert，每个 Expert 有独立目录、入口文件、记忆、技能和会话。
2. 平台提供串行、并行、移交、群聊、Magentic 五种统一编排模式。
3. 五种模式复用现有 Task/Run/Event、AgentScope Subagent、审批、配额和取消能力，不分别建设五套 Runtime。
4. 每次多 Agent 运行必须有唯一所有者、根 Agent、资源作用域和授权工作区。
5. Agent 之间只传递受控上下文和产物，不通过互相挂载目录完成协作。
6. 现有 PERSONAL、PROJECT 和 `/mission` 行为保持兼容，并按阶段迁移到统一编排层。

## 4. 明确非目标

- 专家市场、公开专家、跨用户分享或企业批量分发。
- PERSONAL Agent 自动识别意图并静默切换专家。
- 个人 Expert 自动读取 PERSONAL 或 PROJECT 工作区。
- 个人 Expert 直接加入项目任务并携带个人记忆。
- 跨用户、跨项目或跨安全作用域的 Agent 自由调用。
- 专家独立渠道、Cron、Heartbeat 和 MCP 配置。
- 多层 Agent 嵌套、运行时创建新 Agent 或无限轮协作。
- 可视化 DAG 编辑器、脚本工作流编辑器和低代码流程平台。
- 替换 AgentScope，或重新实现文件、技能、状态和权限系统。

## 5. 核心模型

### 5.1 Agent 作用域

| 作用域 | 所有者 | 工作区 | 可参与的编排 |
| --- | --- | --- | --- |
| PERSONAL | 当前用户 | 个人工作区 | 当前用户发起的个人编排 |
| EXPERT | 当前用户 | Expert 独立工作区 | 单 Expert 或个人 Expert Team |
| PROJECT | 项目 | 项目工作区 | 项目成员有权发起的项目编排 |
| SERVICE | 企业受管资源 | 受管 Agent 工作区 | 按 `agents:manage/use` 授权的编排 |

每次编排只能绑定一个根作用域。个人 Expert 定义不能被带入 PROJECT 编排；项目 Agent 也不能读取用户个人 Expert 的记忆和技能。

### 5.2 编排运行

平台增加统一的 OrchestrationRun 概念：

- 根 Task 表示一次用户请求。
- MemberRun 表示一个参与 Agent 的受限执行。
- 所有成员继承根 Task 的所有者、作用域、配额、超时、取消和审批边界。
- 编排模式只决定成员如何启动、交接、讨论和汇总。
- 结果继续进入现有任务中心和事件流，不新建平行任务系统。

### 5.3 参与者类型

统一编排可以使用以下参与者：

- 当前已有的固定 Subagent 角色，如 `researcher`、`executor`、`reviewer`。
- 与根资源同一作用域内注册的受管 Agent 定义。
- 个人 Expert Team 中由同一用户拥有的 Expert 定义快照。

参与者只能获得根任务授权能力的子集，不能因为被委派而提升权限。

## 6. 专家模式需求

### EXP-01 Expert 资源

- 新增 `EXPERT` Agent 作用域，并记录 `owner_user_id`。
- 用户可创建、编辑、停用和删除多个本人 Expert。
- 普通用户使用现有权限：
  - `agents:use`：运行本人 Expert。
  - `workspace:personal`：创建、编辑、删除 Expert 及管理 Expert 文件。
  - `skills:personal`：管理 Expert 技能。
- `agents:manage` 和 `SYSTEM_ADMIN` 默认只能查看运营元数据，不能读取 Expert 内容。
- 跨用户读取必须使用 `data:breakglass` 并记录审计。

### EXP-02 独立工作区

Expert 目录必须与 PERSONAL 和 PROJECT 目录并列隔离：

```text
<workspaceRoot>/
|-- users/{userId}/
|-- experts/{userId}/{expertId}/
|-- projects/{projectId}/
`-- orchestration-runs/{scope}/{ownerOrScopeId}/{runId}/
```

Expert 工作区包含：

```text
EXPERT.md
AGENTS.md
PROFILE.md
MEMORY.md
agent.json
skills/
sessions/
memory/
media/
tool_results/
history.db
```

PERSONAL Agent、其他 Expert 和 PROJECT Agent 均不能挂载或读取该目录。

### EXP-03 `EXPERT.md` 入口

`EXPERT.md` 是 Expert 的必需上下文文件，至少描述：

- 专家定位与目标。
- 适用和不适用条件。
- 输入材料要求。
- 标准执行流程。
- 技能使用规则。
- 需要询问、确认或停止的条件。
- 输出格式和交付前自检。

Markdown 只规定专业行为。所有权、工具权限、目录、审批、配额和模型限制必须由运行时强制执行。

### EXP-04 记忆、会话和技能

- 每个 Expert 使用独立 `PROFILE.md`、`MEMORY.md`、`memory/`、`history.db` 和 session。
- Expert 不继承 PERSONAL、其他 Expert 或 PROJECT 的长期记忆。
- Expert 只发现自身 `skills/` 下的技能。
- 技能从本人可用技能源复制或安装，不使用指向其他工作区的链接。
- 技能安装和修改继续经过现有安全扫描与确认。

### EXP-05 使用入口

- 聊天作用域增加“我的专家”分组。
- 切换 Expert 时切换到该 Expert 的会话，不能在同一会话中途改变 Agent 身份。
- 用户手动选择 Expert；首期不增加自动路由。
- Expert Team 从专家中心发起，个人 Expert 不必依赖 Expert Team 才能使用。

## 7. 平台级多 Agent 编排需求

本 PRD 采用 Sequential、Concurrent、Handoff、Group Chat、Magentic 五种编排定义。五种模式由统一编排服务执行，可被现有 Agent 和 Expert Team 共同使用。

### ORC-01 串行（Sequential）

适用：后一步依赖前一步结果的固定流程。

规则：

1. 按配置顺序逐个启动成员。
2. 前一成员的结构化摘要和产物引用作为下一成员输入。
3. 前一成员失败或等待审批时，后续成员不得启动。
4. 最后一成员生成最终结果。
5. 模型不能临时跳过、重排或增加成员。

示例：研究 -> 撰写 -> 审校；项目需求分析 -> 实施建议 -> 风险复核。

### ORC-02 并行（Concurrent）

适用：相互独立的子任务、多视角分析或缩短执行时间。

规则：

1. 多个成员接收同一目标和各自职责后并行启动。
2. 并发量受现有企业、用户、项目、Agent 配额约束。
3. 使用统一的 `wait_all` 或明确的汇合条件等待分支。
4. 汇总者只读取成员结果和产物，不读取成员私有工作区。
5. 必需分支失败时根任务失败，但保留其他分支结果。
6. 等待超时不能重复派发同一任务。

示例：多个研究角色并行查证；项目 Agent 并行做技术、成本和风险分析。

### ORC-03 移交（Handoff）

适用：当前 Agent 判断另一个 Agent 更适合继续处理，并把任务控制权交给对方。

规则：

1. 任一时刻只有一个活动成员。
2. 只能移交给编排定义中允许的目标。
3. 接收者获得根任务上下文摘要、移交原因和产物引用。
4. 接收者不获得移交者的工作区、私有记忆和无关会话。
5. 运行时限制移交次数并检测循环。
6. 移交改变当前执行者，但不改变根任务所有者和安全作用域。

示例：服务分诊 Agent 移交给专业处理 Agent；项目分析 Agent 移交给项目审核 Agent。

### ORC-04 群聊（Group Chat）

适用：评审、讨论、辩论和多轮改进。

规则：

1. 使用一个主持者选择下一发言成员并判断结束。
2. 任一时刻只有一个成员发言。
3. 各 Agent 保持独立 session，只同步受控的团队讨论记录或压缩摘要。
4. 共享讨论不能自动写入成员长期记忆。
5. 平台统一限制最大轮次、上下文和 Token。
6. 主持者输出最终结论、分歧和未解决问题。

示例：项目方案评审；研究、写作和审校角色多轮改进文档。

### ORC-05 Magentic（管理者动态协作）

适用：任务开放、子任务和执行顺序无法预先确定的复杂目标。

规则：

1. 管理者先生成结构化计划并通过事件展示。
2. 管理者只能选择已授权、已配置的参与者。
3. 管理者可以组合串行步骤和独立的并行批次。
4. 每轮记录已完成、待完成、阻塞、证据和下一步。
5. 连续无进展时受限重规划。
6. 平台限制最大协调轮次、停滞次数和重规划次数。
7. 成员保持单层委派，不能继续派生。
8. 管理者最终汇总已验证内容、失败项、假设和产物。

现有 `/mission` 可逐步演进为 Magentic 的受限入口，但在统一计划、进度、停滞和权限机制完成前继续保留当前固定角色行为。

## 8. 通用隔离与安全要求

### SEC-01 单一根作用域

每次 OrchestrationRun 必须固定：

- 发起用户。
- 根 Agent。
- 作用域类型和作用域 ID。
- 授权工作区。
- 模型、配置和技能版本。

运行中不能通过提示词或成员输出改变这些字段。

### SEC-02 独立运行目录

- 每次编排使用独立运行目录。
- MemberRun 使用独立可写目录，避免并行写冲突。
- 需要读取根工作区时，只提供明确授权的输入快照或只读内容。
- 只有最终汇总或明确发布步骤可以把产物写回根工作区。
- Agent 之间通过结构化消息和产物引用协作，不互相扫描目录或聊天历史。

### SEC-03 能力交集

MemberRun 的有效能力为：

```text
发起人权限
∩ 根 Agent 能力
∩ 当前作用域策略
∩ 参与者能力
∩ 当前步骤允许能力
```

移交、群聊和 Magentic 重规划均不能扩大该集合。

### SEC-04 审批、配额和审计

- 高风险工具审批关联根 Task、MemberRun、实际 Agent、用户和会话。
- 取消根任务必须取消全部未完成成员。
- 所有成员计入现有并发、Token、次数和时限配额。
- 记录成员启动、结果、失败、移交、发言选择、计划修改、审批和取消事件。
- 管理员可查看运行指标，但默认不能读取个人正文。

### SEC-05 单层限制

- 参与者不能继续派生 Agent。
- Expert Team 不能嵌套 Expert Team。
- PROJECT 编排只能使用项目拥有或项目运行时定义的参与者。
- PERSONAL/EXPERT 编排不能读取 PROJECT 资源，PROJECT 编排不能读取个人 Expert 资源。

## 9. 产品入口

### 9.1 专家中心

提供两个简洁列表：

- 我的专家：名称、说明、状态、技能数量、最近使用时间。
- 专家团：名称、模式、成员、最近运行状态。

Expert Team 是个人专家使用平台编排能力的配置入口，不代表编排能力只服务专家。

### 9.2 现有 Agent

- `/mission` 继续作为现有 Agent 的复杂任务入口。
- 串行和并行能力成熟后，`/mission` 优先使用统一编排服务。
- PROJECT 和 SERVICE 的编排入口沿用任务/Agent 页面，不增加新的“专家”概念。
- 普通聊天不显示五种模式选择器，避免简单任务界面复杂化。

### 9.3 运行展示

复用现有任务中心和 SSE 事件，展示：

- 模式、根 Agent 和作用域。
- 当前成员及各成员状态。
- 计划、移交、发言、汇总和审批事件。
- 最终结果、产物、失败原因和取消状态。

## 10. 分阶段交付

### M1：专家模式与统一编排基础

- `EXPERT` 作用域、所有权、独立工作区、`EXPERT.md`、记忆、技能和会话。
- OrchestrationRun、MemberRun、结构化交接和独立运行目录。
- 权限交集、审批传播、配额、取消、事件和版本快照。

### M2：串行与并行

- 为现有 `/mission` 和 Expert Team 提供串行、并行策略。
- 完成依赖、汇合、失败、幂等和产物发布。
- 验证 PERSONAL、PROJECT、SERVICE、EXPERT 各自在单一作用域内运行。

### M3：移交与群聊

- 允许目标列表、移交循环保护和单活动成员控制。
- 群聊主持、轮次限制、共享摘要和最终结论。

### M4：Magentic

- 计划、动态选人、进度记录、停滞检测和受限重规划。
- 复用已完成的串行、并行、移交和汇总能力。
- 不通过单段系统提示词绕过 M1-M3 控制流。

## 11. 关键验收标准

### 11.1 Expert

- PERSONAL、Expert A、Expert B、PROJECT 四个工作区互相越权读取均被拒绝。
- Expert 的会话、记忆、技能和状态不与其他 Agent 串用。
- 管理员无 `data:breakglass` 时不能读取他人 Expert 内容。
- 被运行任务或 Expert Team 引用的 Expert 不能直接删除。

### 11.2 编排

- 串行严格按顺序执行，前一步失败时后续不启动。
- 并行成员存在真实重叠执行，等待超时不会重复派发。
- Handoff 任一时刻只有一个活动成员，未授权目标和循环被拒绝。
- Group Chat 保持 Agent session 隔离，并在轮次限制内结束。
- Magentic 只能选择已授权成员，并在停滞或上限时正常停止。

### 11.3 安全与回归

- 任一模式都不能跨用户、跨项目或跨工作区读取数据。
- 委派前后工具能力不提升。
- 根任务取消可以终止全部成员和后台执行。
- 审批、用量和事件始终关联正确的用户、作用域、Agent 和 Run。
- 现有 PERSONAL、PROJECT、固定 `/mission`、技能、文件、审批和任务测试通过。

## 12. 实施约束

- 优先使用 AgentScope Java 2.0 官方 Subagent、TaskRepository、Workspace、Memory、Skill 和 Permission 能力。
- 企业层只实现所有权、作用域、编排控制流、审批传播、配额、审计和展示。
- 不重新引入自制 `spawn/send/list/task` 工具协议。
- 官方能力存在缺口时，先形成技术设计、风险说明和契约测试，再增加最小适配层。

## 13. 参考资料

- [Microsoft Agent Framework：Workflows and orchestration patterns](https://learn.microsoft.com/en-us/agent-framework/journey/workflows)
- [Microsoft Agent Framework：Handoff orchestration](https://learn.microsoft.com/en-us/agent-framework/workflows/orchestrations/handoff)
- [Microsoft Agent Framework：Group Chat orchestration](https://learn.microsoft.com/en-us/agent-framework/workflows/orchestrations/group-chat)
- [Microsoft Agent Framework：Magentic orchestration](https://learn.microsoft.com/en-us/agent-framework/workflows/orchestrations/magentic)
- [Anthropic：Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)
- [本项目 AgentScope Java 2.0 对齐说明](AGENTSCOPE_2_ALIGNMENT.md)
- [本项目企业能力与权限矩阵](ENTERPRISE_CAPABILITY_MATRIX.md)

