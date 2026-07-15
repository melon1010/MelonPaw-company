# MelonPaw-Company

MelonPaw-Company 是面向单个企业私有部署的 AI Agent 协作与任务执行平台。后端基于 Java 17、Spring Boot WebFlux 和 AgentScope Java 2.0.0 GA，前端采用 React、TypeScript 与 Vite。

企业角色、个人资源和管理边界见 [企业能力与权限矩阵](docs/ENTERPRISE_CAPABILITY_MATRIX.md)；尚未完成的生产能力及验收标准见 [企业化剩余工作](docs/ENTERPRISE_REMAINING_WORK.md)。

项目由 QwenPaw 的交互基础演进而来，但作为独立项目维护，不是 QwenPaw 或 AgentScope 的官方发行版。上游来源和再分发边界见 [docs/UPSTREAM.md](docs/UPSTREAM.md)。

## 产品边界

- 一个部署实例服务一家企业，不提供 SaaS 多租户、企业切换、订阅或计费能力。
- 只交付响应式 Web 控制台，并兼容移动端浏览器；不包含桌面客户端、CLI 和 Coding 模式。
- 支持直接运行 JAR 或使用常规进程服务部署；当前不提供 Kubernetes 部署方案。
- 企业身份、角色、项目关系和个人资源映射保存在内嵌 SQLite 中，不要求安装 PostgreSQL。
- 渠道只保留控制台、钉钉、飞书、QQ、微信、企业微信、元宝、小艺和 OneBot；渠道配置归属具体用户。
- 自定义跨 Agent 调用和 AgentScope 官方 Subagent 当前均不对 Web 用户开放。企业级委派、审批和审计闭环完成前不会启用。

## 当前能力

- 企业管理员、运营人员和普通员工的身份认证与权限控制。
- 普通用户独立的个人 Agent、技能中心、MD 工作区、渠道配置和会话数据。
- 项目成员关系及项目文档工作区，项目资源按成员角色授权。
- 基于 SSE 的流式对话，工具调用和执行结果沿用 `tool/result` 事件展示。
- Agent、模型、工具、MCP、审计、备份和系统设置等管理能力按权限展示。
- 工具审批、文件路径约束、技能扫描、密钥掩码和操作审计等安全边界。

项目共享 Agent 的执行入口仍保持关闭。它只有在任务所有权、委派范围、额度、审批人和审计链完整后才应开放。

## AgentScope 2.0 对齐

项目固定使用 AgentScope Java `2.0.0` GA，优先组合官方能力，不维护重复实现：

| 能力 | 当前做法 |
| --- | --- |
| 模型接入 | 使用五个官方 provider extension，通过 `ModelRegistry` 和 `ModelCreationContext` 动态创建模型；DeepSeek 复用 OpenAI 兼容扩展 |
| 会话上下文 | 使用 `RuntimeContext(userId, sessionId)` 传递用户和会话身份 |
| 状态持久化 | 全应用注入统一的 `AgentStateStore`，每个 Agent 通过命名空间包装器隔离状态，默认底层使用官方 `JsonFileAgentStateStore` |
| 工作区 | 使用 Harness Workspace，`AGENTS.md` 和 `MEMORY.md` 由 Harness 管理，其他上下文文件通过 `additionalContextFile` 注入 |
| 文件与命令工具 | 使用 Harness 官方文件工具和官方 `execute`；生产默认采用 `DockerFilesystemSpec`、`IsolationScope.USER`，只挂载已授权的个人工作区 |
| 技能 | 使用 Harness 动态技能与官方 `SkillManageTool`；创建前要求审批，审批后经官方安全扫描进入用户正式技能目录 |
| 工具审批 | 使用 `PermissionContextState`/`PermissionEngine` 规则和 `RequireUserConfirmEvent`，再接入企业审批记录 |
| Subagent | 构建 Agent 时显式禁用，等待企业级委派、RBAC、审批、额度和审计能力完成 |

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
| Node.js | 18 或更高 |
| Docker | 运行 Agent 文件/命令工具时必需；Web 管理页本身可在无 Docker 时启动 |

## 构建

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

## 启动

先构建一次企业沙箱镜像。镜像内以非 root 用户运行，并包含技能脚本常用的 Bash、Git、curl、Python 和 Node.js：

```powershell
.\scripts\build-sandbox.ps1
```

Linux 可执行等价命令：

```bash
docker build -t melonpaw-company-sandbox:latest docker/sandbox
```

默认使用 Docker `bridge` 网络，使 `execute_shell_command` 能获取公开链接和技能依赖。生产环境应通过 `MELON_SANDBOX_NETWORK` 指定受限出口网络；禁止外网时可设为 `none`。每个执行上下文采用 AgentScope `USER` 隔离，只把受控工作区根目录下当前用户的工作区挂载到容器。

Windows 可使用仓库内脚本，默认监听 `127.0.0.1:8088`：

```powershell
.\scripts\start-company.ps1
```

也可直接启动 JAR。`application.yaml` 的原始默认端口是 `8089`，建议部署时显式指定端口：

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

首次打开 Web 控制台时创建初始管理员。默认数据目录为 `${MELON_HOME}`；未设置时使用用户目录下的 `.melonAI`。企业数据库默认位于：

```text
${MELON_HOME}/enterprise.db
```

当系统没有任何用户且未显式设置 `MELON_BOOTSTRAP_TOKEN` 时，后端会生成 256 位初始化令牌并写入 `${MELON_HOME}/bootstrap.token`；登录页会要求输入该令牌。首个管理员创建成功后文件会被删除，初始化接口随即永久关闭。也可以在启动前由运维系统注入 `MELON_BOOTSTRAP_TOKEN`，此时不会生成令牌文件。

这是应用内嵌的 SQLite 数据库，无需安装或启动 PostgreSQL。可通过 `MELON_ENTERPRISE_DATABASE_PATH` 覆盖数据库文件位置。备份时至少应同时保存 `enterprise.db`、`config.yaml` 和 `workspaces/`。

OneBot 使用反向 WebSocket。个人渠道页会显示带当前 Agent 标识的连接 URL；OneBot 客户端必须把该渠道配置的 `access_token` 作为 `Authorization: Bearer <token>` 或 `X-Access-Token` 请求头发送。服务端按 Agent 独立校验 token，不接受仅靠 `X-Agent-Id` 切换工作区。

生产环境应由反向代理终止 HTTPS，并限制应用端口只对受信网络开放。

## 模型配置

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

## 开发与验证

后端开发服务：

```bash
mvn -pl melon-app spring-boot:run
```

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

## 文档

- [AgentScope 2.0 对齐说明](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [上游来源与独立性说明](docs/UPSTREAM.md)
- [开源发布检查清单](docs/OPEN_SOURCE_CHECKLIST.md)
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)

## 许可证

本项目使用 [Apache License 2.0](LICENSE)。公开源码仓库或二进制发布前，仍需核对 `NOTICE`、上游版本、第三方资源许可证和前端素材的再分发条件。
