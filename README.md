# MelonPaw-Company

> **全网首发 · Java 企业级“龙虾”**

MelonPaw-Company 是面向单个企业私有部署的 AI Agent 协作与任务执行平台。项目发布定位是“全网首发的 Java 企业级龙虾”：使用 Java 17、Spring Boot WebFlux 和 AgentScope Java 2.0.0 GA 构建可长期运行、具备工作区、记忆、技能、MCP、工具执行、自动化和国内渠道接入能力的 AI 助理，前端采用 React、TypeScript 与 Vite。“全网首发”是本项目的发布定位，不代表第三方排名或官方认证。

这里的“龙虾”指中文 Agent 社区对这类可自主使用工具、持续积累上下文并完成任务的 AI 助理产品形态的通俗称呼，不表示兼容或从属于某个同名产品。项目参考 Hermes Agent 等开源 Agent 在持续工作区、Skills、MCP、Memory、ReAct 工具循环和任务执行方面的产品思路，但不是 Hermes Agent 的 Java 移植版，也没有使用其名称、代码或官方身份。

企业角色、个人资源和管理边界见 [企业能力与权限矩阵](docs/ENTERPRISE_CAPABILITY_MATRIX.md)；尚未完成的生产能力及验收标准见 [企业化剩余工作](docs/ENTERPRISE_REMAINING_WORK.md)。

项目由 QwenPaw 的交互基础演进而来，Agent 运行时使用 AgentScope Java 2.0 官方能力，但作为独立项目维护，不是 QwenPaw、AgentScope、Hermes Agent 或其他参考项目的官方发行版。上游来源和再分发边界见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。

## README 语言（默认入口）

| 语言 | README |
| --- | --- |
| 简体中文（默认） | [README.md](README.md) |
| English | [README.en.md](README.en.md) |
| 日本語 | [README.ja.md](README.ja.md) |
| Русский | [README.ru.md](README.ru.md) |
| Português (Brasil) | [README.pt-BR.md](README.pt-BR.md) |
| Bahasa Indonesia | [README.id.md](README.id.md) |
| Tiếng Việt | [README.vi.md](README.vi.md) |

## 产品边界

- 一个部署实例服务一家企业，不提供 SaaS 多租户、企业切换、订阅或计费能力。
- 只交付响应式 Web 控制台，并兼容移动端浏览器；不包含桌面客户端、CLI 和 Coding 模式。
- 支持直接运行 JAR 或使用常规进程服务部署；当前不提供 Kubernetes 部署方案。
- 企业身份、角色、团队关系和个人资源映射保存在内嵌 SQLite 中，不要求安装 PostgreSQL。
- 渠道只保留控制台以及钉钉、飞书、QQ、微信、企业微信五个国内平台；渠道配置归属具体用户。
- 本地模型只接入部署环境独立运行的 Ollama；应用不内嵌、不安装也不更新 Ollama 或 llama.cpp。
- `/mission` 可使用固定的 `researcher`、`executor`、`reviewer` 原生 Subagent；动态 Subagent、跨项目委派、嵌套委派和能力提升保持关闭。

## 当前能力

- 企业管理员、运营人员和普通员工的身份认证与权限控制。
- 普通用户独立的个人 Agent、技能中心、MD 工作区、渠道配置和会话数据。
- 团队成员关系及团队文档工作区，团队资源按成员角色授权；内部数据模型继续使用稳定的 `PROJECT` scope 标识。
- 基于 SSE 的流式对话，工具调用和执行结果沿用 `tool/result` 事件展示。
- Agent、模型、工具、MCP、审计、备份和系统设置等管理能力按权限展示。
- 工具审批、文件路径约束、技能扫描、密钥掩码和操作审计等安全边界。
- 独立任务中心可直接创建 Agent、Goal、Mission 任务，不要求先进入聊天；任务沿用统一的配额、结果、取消和重试链路。
- 团队 Agent 的技能、MCP、文档、会话、记忆、文件和任务结果在团队内共享，并与个人 Agent、其他团队 Agent 完全分开；个人 Agent 与团队 Agent 可在内容标题栏切换。
- Agent 级 LLM 并发、QPM 和 429 暂停控制覆盖主对话、压缩、Rubric 与 Subagent 模型调用；它不替代企业 `/quotas` 的任务和 Token 配额。
- 长期记忆当前支持 AgentScope 文件记忆和完全关闭两种模式；ReMeLight、ADBPG 未接入 Java 运行时，控制台不提供这些选项。
- 远程 MCP 可显式配置 OAuth 2.0 Client Credentials；服务端会先向 Token Endpoint 验证凭据，成功后才替换旧配置，Client Secret 加密落盘且不回显。

团队 Agent 只向有执行权限的团队成员开放，且任务记录团队范围、执行用户和 Agent 版本。当前 Subagent 只在同一 Agent 工作区内单层运行，不是任意 Agent 之间的互调。

## 语言支持

控制台默认使用简体中文（`zh`）。语言偏好按用户保存，登录后从服务端读取；用户也可以通过右上角语言切换入口修改。当前支持的控制台界面语言如下：

| 标识 | 界面语言 | 覆盖范围 |
| --- | --- | --- |
| `zh` | 简体中文 | 默认语言，完整控制台界面 |
| `en` | English | 控制台界面 |
| `ja` | 日本語 | 控制台界面 |
| `ru` | Русский | 控制台界面 |
| `pt-BR` | Português (Brasil) | 控制台界面 |
| `id` | Bahasa Indonesia | 控制台界面 |
| `vi` | Tiếng Việt | 控制台界面 |

这里的“界面语言”只描述 React 控制台、Ant Design 组件、日期和通用提示语的翻译，不等同于模型能力或用户输入语言。模型仍可根据对话内容使用其他语言回复；工具输出、MCP 返回值、用户上传文件和技能正文不会被界面切换自动翻译。

智能体配置中的“智能体语言”是另一项设置，用于选择 `SOUL.md`、`AGENTS.md`、`PROFILE.md`、`MEMORY.md`、`BOOTSTRAP.md`、`HEARTBEAT.md` 等内置人设文件的语言版本。切换它可能覆盖这些内置 MD 文件，已经自定义的内容应先备份。当前内置技能的可用语言以技能自身的 `available_languages` 元数据为准，并不保证覆盖上述全部 7 种界面语言。

语言标签会按 BCP 47 规则规范化，例如 `zh-CN` 归一为 `zh`、`pt-PT` 归一为 `pt-BR`；不支持的标签会被拒绝或回退到简体中文。浏览器的其他语言不会自动新增翻译包，也不会改变系统默认语言。

## 权限、共享与隔离边界

权限判断采用四层边界：企业 RBAC 决定用户能否进入接口，Agent 所有权决定数据范围，工作区与 Docker 沙箱限制文件和进程可见范围，AgentScope Permission 加风险分类决定具体工具调用是直接允许、询问还是拒绝。前端隐藏菜单不是授权依据，客户端传入的 `X-Agent-Id` 也不能绕过服务端校验。

### 个人空间与团队空间

团队空间在代码和数据库中使用 `PROJECT` scope。团队内“共享”是指共享同一个团队 Agent 的资源，不是把成员的个人数据复制进团队空间。

| 资源 | 个人 Agent | 团队 Agent |
| --- | --- | --- |
| 工作区、文件和生成结果 | 只属于本人 | 同一团队 Agent 的授权成员共享 |
| 对话列表与历史 | 只属于本人 | `OWNER`、`MANAGER`、`MEMBER` 共享查看和继续；不同对话仍由 `sessionId` 区分 |
| 会话运行状态 | 按本人和会话隔离 | 同一会话同一时间只允许一个执行者；成员交接时只复制对话上下文和摘要 |
| 长期记忆 | 本人 Agent 独立记忆 | 团队 Agent 共用工作区记忆，与成员个人记忆隔离 |
| Skills | 本人维护和使用 | 团队 Agent 共用；由 `OWNER`、`MANAGER` 或具备 `agents:manage` 的运营人员维护 |
| MCP 配置与凭据 | 本人 Agent 独立配置 | 团队 Agent 共用；按团队成员角色管理，调用仍重新检查用户、渠道和工具策略 |
| 任务与结果 | 本人可见和管理 | 记录 `PROJECT` scope，团队成员按角色读取，取消和重试继续校验任务管理权限 |
| 渠道账号和个人自动化 | 属于本人，不与其他用户共享 | 不自动吸收成员的个人渠道、二维码、访问名单或个人定时任务 |
| 工具授权记录 | 只对作出允许决定的本人有效 | 仍按执行用户隔离；成员 A 的记住授权不会传给成员 B |

团队成员继续别人创建的对话时，系统会继承可见对话上下文和摘要，但会重置 Permission、Tool、Task、Plan 等运行状态。团队对话的创建者、参与执行者、当前执行者和最新状态执行者会记录在会话元数据中。任一有会话访问权的成员可以停止正在运行的共享会话，但中断会精确作用于实际执行者；旧执行回调不能结束后来启动的新一轮对话。

上述对话能力还要求企业角色具备 `agents:use`；团队角色本身不会补发企业权限。`VIEWER` 可以查看被授权的团队资料和任务结果，但不能启动或继续团队 Agent 对话。团队成员资格失效后，不能再通过旧会话 ID、请求头或历史链接访问团队 Agent。

### 企业权限码

| 权限码 | 授权范围 | 不会自动获得 |
| --- | --- | --- |
| `agents:use` | 使用本人 Agent 和被分配的团队 Agent，发起对话和任务 | 个人资源维护、Agent 配置管理 |
| `workspace:personal` | 本人工作区、Markdown、文件和记忆 | 他人个人空间、团队配置管理 |
| `skills:personal` | 本人技能 | 团队技能管理、全局技能池管理 |
| `mcp:personal` | 本人 MCP | 团队 MCP、MCP Server 和全局 OAuth 管理 |
| `channels:personal` | 本人渠道连接、二维码和访问控制 | 他人渠道、系统渠道密钥 |
| `automations:personal` | 本人定时任务和 Heartbeat | 他人自动化、系统调度管理 |
| `agents:manage` | 非个人 Agent、团队 Agent 资源和运行配置 | 他人的 PERSONAL Agent 内容 |
| `approvals:decide` | 审批被授权范围内的高风险工具调用 | 系统配置、他人数据读取 |
| `audit:read` | 只读查看用量和审计记录 | 对话与资源写入 |
| `system:manage` | 模型、插件、备份、全局安全和宿主环境管理 | 默认读取他人 PERSONAL Agent 内容 |
| `data:breakglass` | 经审计的跨用户个人数据应急访问 | 常态化的数据访问权 |

`SYSTEM_ADMIN` 也不会因为管理员身份自动读取他人的个人对话、记忆、技能、MCP 或渠道配置；这类访问必须显式具备 `data:breakglass` 并进入审计。完整角色矩阵、团队角色和接口归属以 [企业能力与权限矩阵](docs/ENTERPRISE_CAPABILITY_MATRIX.md) 为准。

### 工具执行与授权记忆

默认 `RISK_BASED` 模式按实际工具参数判断风险，而不是看到 `execute_shell_command` 就一律弹框。

| 结果 | 典型情况 | 行为 |
| --- | --- | --- |
| `ALLOW` | 工作区内的普通读取、搜索、低风险系统工具、普通无写入 Shell、只读或未声明高风险副作用的 MCP | 在当前 Agent 工作区和沙箱内直接执行 |
| `ASK` | Shell 写文件或删除文件、安装软件、修改权限、发布/上传/部署等外部副作用、高风险 MCP、删除技能 | 在输入框上方等待当前执行用户选择“拒绝”或“允许本次” |
| `DENY` | 读取受保护凭据、逃逸工作区、显式 MCP 拒绝策略、未开放的 Subagent 或无效调用 | 服务端拒绝，不提供绕过入口 |

只有用户明确允许时才可能保存授权；拒绝、取消、超时和永久拒绝都不会被记住。用户可以选择仅允许本次，或对可泛化的相同操作保存 `exact`/`similar` 授权。保存的授权绑定 `agentId + ownerUserId + 操作指纹`：个人空间不会跨用户，团队空间也不会把一个成员的授权传播给其他成员。组织 `STRICT` 策略和明确要求 `ask`/`deny` 的 MCP 策略不会被历史记住授权绕过。

## 文件分屏预览

聊天中的文件卡片默认在对话区域旁打开分屏预览，预览窗口右上角提供下载入口。当前支持范围如下：

| 类型       | 支持格式                                                                                                                                                                                                                                                | 说明                                                                                                   |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 图片       | `avif`、`bmp`、`gif`、`jpeg`、`jpg`、`png`、`svg`、`webp`                                                                                                                                                                                               | 使用浏览器原生图片渲染                                                                                 |
| 视频       | `m4v`、`mov`、`mp4`、`ogg`、`webm`                                                                                                                                                                                                                      | 使用浏览器原生播放器，实际编码支持取决于浏览器                                                         |
| 音频       | `aac`、`flac`、`m4a`、`mp3`、`oga`、`wav`                                                                                                                                                                                                               | 使用浏览器原生播放器，实际编码支持取决于浏览器                                                         |
| PDF        | `pdf`                                                                                                                                                                                                                                                   | 使用浏览器内嵌 PDF 阅读器                                                                              |
| Word       | `docx`                                                                                                                                                                                                                                                  | 客户端只读渲染常见分页、表格、图片、页眉和页脚；不支持旧版 `doc`                                       |
| Excel      | `xlsx`                                                                                                                                                                                                                                                  | 支持多工作表切换和只读单元格网格；不支持旧版 `xls`，复杂样式、公式显示、图表和合并单元格不保证完整还原 |
| 文本与代码 | `bash`、`c`、`conf`、`cpp`、`css`、`csv`、`env`、`go`、`h`、`hpp`、`html`、`ini`、`java`、`js`、`json`、`jsx`、`less`、`log`、`md`、`properties`、`py`、`rb`、`rs`、`scss`、`sh`、`sql`、`toml`、`ts`、`tsx`、`txt`、`vue`、`xml`、`yaml`、`yml`、`zsh` | 文本内容直接读取并显示                                                                                 |

文本文件的内联预览上限为 1 MB，DOCX 和 XLSX 的内联预览上限为 20 MB。为了保持页面响应速度，每个 Excel 工作表最多显示前 500 行和 100 列；原文件不会被修改，仍可通过下载入口获取完整内容。其他格式显示文件信息和下载入口。

## AgentScope 2.0 对齐

项目固定使用 AgentScope Java `2.0.0` GA，优先组合官方能力，不维护重复实现：

| 能力 | 当前做法 |
| --- | --- |
| 模型接入 | 使用五个官方 provider extension，通过 `ModelRegistry` 和 `ModelCreationContext` 动态创建模型；DeepSeek 复用 OpenAI 兼容扩展 |
| 会话上下文 | 使用 `RuntimeContext(userId, sessionId)` 传递用户和会话身份 |
| 状态持久化 | 全应用注入统一的 `AgentStateStore`，每个 Agent 通过命名空间包装器隔离状态，默认底层使用官方 `JsonFileAgentStateStore` |
| 工作区 | 使用 Harness Workspace，`AGENTS.md` 和 `MEMORY.md` 由 Harness 管理，其他上下文文件通过 `additionalContextFile` 注入 |
| 文件与命令工具 | 使用 Harness 官方文件工具和官方 `execute`；生产默认采用 `DockerFilesystemSpec`、`IsolationScope.USER`，只挂载当前已授权 Agent 的工作区 |
| 技能 | 使用 Harness 动态技能与官方 `SkillManageTool`；创建前要求审批，审批后经官方安全扫描进入用户正式技能目录 |
| 工具审批 | 使用 `PermissionContextState`/`PermissionEngine` 规则和 `RequireUserConfirmEvent`，再接入企业审批记录 |
| Subagent | 注册固定角色的原生 Subagent Factory，Worker 使用受限工具集和独立上下文；禁用动态、嵌套及跨项目委派 |

详细设计、代码落点及后续约束见 [docs/AGENTSCOPE_2_ALIGNMENT.md](docs/AGENTSCOPE_2_ALIGNMENT.md)。

## 项目结构

Maven 聚合工程只包含五个子模块：

```text
MelonPaw-Company/
|-- melon-plugin-api/   第三方插件 SPI
|-- melon-core/         Agent 运行时、配置、模型接入、安全与中间件
|-- melon-tools/        定时任务等少量业务扩展工具
|-- melon-channels/     渠道适配、消息队列和渠道访问控制
|-- melon-app/          Spring Boot Web 应用、认证、企业服务和 REST/SSE 接口
|-- console/            React Web 控制台，不是 Maven 子模块
|-- docs/               架构、上游和发布说明
`-- pom.xml              Maven 父工程
```

依赖方向如下：

```text
melon-plugin-api -> melon-core -> melon-tools
                              -> melon-channels
                              -> melon-app

melon-app 聚合其他四个 Java 模块，并打包 console/dist。
```

仓库不再包含独立 CLI、Coding 或桌面运行模块。

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 17 或更高 |
| Maven | 3.8 或更高 |
| Node.js | 20.19 或更高；推荐 22 LTS |
| Docker | 生产默认运行 Agent 文件/命令工具时必需；受信开发环境可用 `local` profile 在宿主机执行 |

## 构建与启动

先构建 Web 控制台：

```bash
cd console
npm ci
npm run build
cd ..
```

再构建 Java 应用：

```bash
mvn clean package
```

生成的可执行文件为 `melon-app/target/melonpaw-company-web.jar`。Maven 会把已有的 `console/dist` 打入 JAR，因此正式打包前必须先完成前端构建。

### 启动说明

先构建一次企业沙箱镜像。镜像内以非 root 用户运行，并包含技能脚本常用的 Bash、Git、curl、Python 和 Node.js：

```powershell
.\scripts\build-sandbox.ps1
```

Linux 和 macOS 可执行等价命令：

```bash
docker build -t melonpaw-company-sandbox:latest docker/sandbox
```

默认使用 Docker `bridge` 网络，使 `execute_shell_command` 能获取公开链接和技能依赖。生产环境应通过 `MELON_SANDBOX_NETWORK` 指定受限出口网络；禁止外网时可设为 `none`。每个执行上下文采用 AgentScope `USER` 隔离，只把受控工作区根目录下当前授权 Agent 的工作区挂载到容器，个人与团队 Agent 使用不同目录。

stdio MCP 默认跟随 `MELON_EXECUTION_SANDBOX`：Docker 模式下 MCP 进程也在独立的受限容器中运行，并且只挂载所属 Agent 的工作区。可用 `MELON_MCP_STDIO_MODE=local|docker|disabled` 单独覆盖；`local` 仅用于可信开发环境。MCP 容器默认复用沙箱镜像与网络，也可分别设置 `MELON_MCP_SANDBOX_IMAGE`、`MELON_MCP_SANDBOX_NETWORK`。

当 Java 服务本身运行在容器中并通过宿主 Docker daemon 创建执行沙箱时，必须同时配置应用容器内和 Docker 宿主机上的工作区根路径。例如应用看到 `/data/workspaces`，宿主机实际目录是 `/srv/melon/workspaces`：

```bash
MELON_WORKSPACE_ROOT=/data/workspaces
MELON_SANDBOX_HOST_WORKSPACE_ROOT=/srv/melon/workspaces
```

`MELON_WORKSPACE_ROOT` 可以单独用于指定应用工作区；配置 `MELON_SANDBOX_HOST_WORKSPACE_ROOT` 做容器路径映射时，两个变量必须同时使用绝对路径。应用只会映射其下的具体 Agent 子目录，不会挂载整个工作区根目录。

`web_search` 和 `web_fetch` 由 Java 服务直接执行只读公网访问，不经过 Shell。`web_fetch` 会拒绝本机、私网、云元数据地址、非标准端口和跨域重定向，并限制下载及上下文大小。macOS TUN 代理常用 `198.18.0.0/15` 假 IP DNS，运行时会自动兼容域名解析；需要强制关闭时设置 `MELON_WEB_ALLOW_FAKE_IP_DNS=false`。

Windows 可使用仓库内脚本，默认监听 `127.0.0.1:8088`：

```powershell
.\scripts\start-company.ps1
```

也可直接启动 JAR。`application.yaml` 默认监听 `127.0.0.1:8088`，生产部署仍建议显式指定地址和端口：

```powershell
$env:SERVER_ADDRESS = "127.0.0.1"
$env:SERVER_PORT = "8088"
$env:MELON_EXECUTION_SANDBOX = "docker"
$env:MELON_SANDBOX_IMAGE = "melonpaw-company-sandbox:latest"
java -jar .\melon-app\target\melonpaw-company-web.jar
```

```bash
SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8088 \
  MELON_EXECUTION_SANDBOX=docker \
  MELON_SANDBOX_IMAGE=melonpaw-company-sandbox:latest \
  java -jar melon-app/target/melonpaw-company-web.jar
```

首次打开 Web 控制台时创建初始管理员。默认数据目录为 `${MELON_HOME}`；未设置时使用用户目录下的 `.melonPaw-CompanyAI`。企业数据库默认位于：

```text
${MELON_HOME}/enterprise.db
```

当系统没有任何用户且未显式设置 `MELON_BOOTSTRAP_TOKEN` 时，后端会生成 256 位初始化令牌并写入 `${MELON_HOME}/bootstrap.token`；登录页会要求输入该令牌。首个管理员创建成功后文件会被删除，初始化接口随即永久关闭。也可以在启动前由运维系统注入 `MELON_BOOTSTRAP_TOKEN`，此时不会生成令牌文件。

`local` profile 默认不生成初始化令牌，首次打开控制台即可直接创建管理员。需要在本地复现生产保护时，可显式设置 `MELON_BOOTSTRAP_TOKEN`，或将 `MELON_GENERATE_BOOTSTRAP_TOKEN` 设为 `true`。

这是应用内嵌的 SQLite 数据库，无需安装或启动 PostgreSQL。可通过 `MELON_ENTERPRISE_DATABASE_PATH` 覆盖数据库文件位置。备份时至少应同时保存 `enterprise.db`、`config.yaml` 和 `workspaces/`。

生产环境应由反向代理终止 HTTPS，并限制应用端口只对受信网络开放。

## 模型与密钥

至少配置一个模型供应商。模型 ID 使用 `<provider>:<model>` 格式，例如 `dashscope:qwen-plus` 或 `deepseek:deepseek-chat`。

可在 Web 管理界面中维护供应商配置，也可通过环境变量注入密钥：

```bash
export DASHSCOPE_API_KEY=sk-xxxx
export OPENAI_API_KEY=sk-xxxx
export ANTHROPIC_API_KEY=sk-xxxx
export GEMINI_API_KEY=xxxx
export DEEPSEEK_API_KEY=sk-xxxx
```

API Key 和渠道密钥不得提交到仓库、工作区文档或日志中。Ollama 为本地模型接入，通常不需要 API Key。

本地模型管理页会探测配置端口上的 Ollama，读取 `/api/tags` 的真实模型列表，通过 `/api/pull` 拉取或取消模型，通过 `/api/delete` 删除未被 Agent 使用的模型，并把所选 `ollama:<model>` 保存到当前 Agent。Ollama 必须由部署环境提前安装并启动；页面不负责安装、更新或停止外部服务，也不支持 Hugging Face/ModelScope 文件下载或 GGUF 运行时管理。默认连接 `http://127.0.0.1:11434`，可在页面修改端口。外部 Ollama 的完整端到端验收仍取决于部署环境。

模型供应商 OAuth 当前不受支持，接口会明确返回 `501`；请使用 API Key。远程 MCP 的 OAuth 是独立能力，当前只支持 OAuth 2.0 Client Credentials：需要显式填写 Token Endpoint、Client ID、Client Secret 和可选 Scope。交互式 Authorization Code、PKCE 和 Dynamic Client Registration 尚未实现，控制台不会伪造授权弹窗或返回假成功。

外部技能 URL 导入只接受可直接下载的 HTTPS ZIP，ZIP 内必须包含 `SKILL.md`。Skills.sh、ClawHub、SkillsMP、LobeHub、ModelScope 等详情页不是 ZIP 下载地址，不会显示为受支持来源。插件同样只接受浏览器上传的 ZIP/文件夹、已安装目录中的 `plugin://` 项，或经过公网地址校验的 HTTP(S) ZIP；服务端任意本地路径安装被禁止。

模型、渠道和 MCP 凭据使用 AES-256-GCM 加密落盘。未设置 `MELON_MASTER_KEY` 时，系统会自动生成
`${MELON_HOME}/master.key` 并在支持 POSIX 权限的平台设为 `0600`；生产环境也可以注入 Base64 或
64 位十六进制的 256-bit `MELON_MASTER_KEY`。轮换时使用新 `MELON_MASTER_KEY` 启动，并通过
`MELON_MASTER_KEY_PREVIOUS`（多个旧密钥以逗号分隔）提供解密来源，随后调用
`POST /api/config/security/secrets/rotate` 重写全部已知密文。普通备份始终排除 `master.key` 和
`bootstrap.token`，跨机器恢复前必须单独恢复原主密钥；主密钥丢失后密文不可恢复。

## 开发与验证

后端开发服务：

```bash
mvn -pl melon-app spring-boot:run -Dspring-boot.run.profiles=local
```

在 IntelliJ IDEA 的 Spring Boot Run/Debug Configuration 中把 Active profiles
设置为 `local`，会加载 `application-local.yaml` 并使用 AgentScope
`LocalFilesystemSpec` 的跨平台适配层。Windows 使用 `cmd.exe`，Linux 和 macOS
使用 `sh`；文件工具和命令工具共享当前授权 Agent 的工作区，并统一使用 UTF-8 输出。
该模式允许在宿主机执行命令，仅用于可信的本地开发；
不启用 `local` profile 时，`application.yaml` 默认使用 Docker 沙箱。

前端开发服务：

```bash
cd console
npm ci
npm run dev
```

Vite 默认运行在 `http://localhost:5173`。后端地址与端口不一致时，通过 `VITE_API_BASE_URL` 显式配置。

常用检查：

```bash
mvn -pl melon-app -am test
cd console
npm run test:run
npm run build
```

上述命令是项目的验证入口，不代表当前工作区在每次修改后都已自动通过。提交前应在本机重新执行并记录结果。

## 部署与安全

- 不要把未启用认证和 HTTPS 的管理接口暴露到公网。
- Shell、浏览器自动化、MCP、插件、技能导入和文件写入属于敏感能力，必须受角色、工作区和审批策略约束。
- AgentScope 的工具权限系统不替代企业 RBAC；HTTP 接口和资源所有权仍由企业层校验。
- 导入第三方插件、技能包、备份包或工作区压缩包前必须验证来源和内容。
- 正式上线前应覆盖越权访问、路径逃逸、审批绕过、进程终止、备份恢复和升级回滚测试。

## 文档与许可证

- [AgentScope 2.0 对齐说明](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [上游来源与独立性说明](docs/UPSTREAM.md)
- [开源发布检查清单](docs/OPEN_SOURCE_CHECKLIST.md)
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)

本项目代码拟使用 [Apache License 2.0](LICENSE)，但当前 Git 历史仍包含已从工作树删除、禁止第三方分发的 Anthropic 技能材料，因此当前仓库不能直接视为合规的开源发布物。公开源码或二进制发布前必须按 [开源发布检查清单](docs/OPEN_SOURCE_CHECKLIST.md) 清理历史，并核对 `NOTICE`、上游版本、剩余第三方资源许可证和前端素材的再分发条件。
