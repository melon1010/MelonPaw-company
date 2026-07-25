# MelonPaw-Company

> **First released worldwide · Enterprise Java “Claw”**

MelonPaw-Company is a privately deployable enterprise AI Agent collaboration and task-execution platform. It is built with Java 17, Spring Boot WebFlux and AgentScope Java 2.0.0 GA, with a React, TypeScript and Vite console. The project is positioned as a Java enterprise “Claw”: a long-running assistant with workspaces, memory, Skills, MCP, tools, automation and domestic channel integrations. “First released worldwide” is the project’s publishing position, not a third-party ranking or certification.

The “Claw” wording describes a product form commonly used by the Chinese Agent community. The project references product ideas from Hermes Agent and other open-source Agents, but is not Hermes Agent’s official Java version, code fork, dependency, compatibility target or officially authorized product.

## README languages

The default entry is Simplified Chinese. All seven files describe the same product, permissions, deployment and security boundaries in different languages.

| Language | README |
| --- | --- |
| Simplified Chinese (default) | [README.md](README.md) |
| English | [README.en.md](README.en.md) |
| Japanese | [README.ja.md](README.ja.md) |
| Russian | [README.ru.md](README.ru.md) |
| Portuguese (Brazil) | [README.pt-BR.md](README.pt-BR.md) |
| Indonesian | [README.id.md](README.id.md) |
| Vietnamese | [README.vi.md](README.vi.md) |

## Product boundary

- One deployment serves one enterprise; this is not a SaaS multi-tenant, subscription or billing product.
- The delivered client is a responsive web console with mobile-browser support. There is no desktop client, CLI or Coding mode.
- The application can run as a JAR or normal process. Kubernetes deployment is not currently provided.
- Enterprise identities, roles, team membership and personal-resource mappings use embedded SQLite; PostgreSQL is not required.
- Supported domestic channels are DingTalk, Feishu, QQ, WeChat and WeCom. Channel credentials belong to the configuring user.
- Local models use Ollama supplied by the deployment environment. The application does not install, update or embed Ollama or llama.cpp.
- `/mission` exposes the fixed native Subagents `researcher`, `executor` and `reviewer`; dynamic, nested and cross-team delegation remains disabled.

## Capabilities

- Enterprise authentication, RBAC, personal Agents and team Agents.
- Personal workspaces, Markdown files, Skills, MCP, channels, conversations and memory.
- Team membership and team workspaces; a team Agent shares its files, Skills, MCP, conversations, memory and results with authorized members.
- SSE streaming conversations with tool calls and `tool/result` events.
- Agent, model, tool, MCP, audit, backup and system administration according to permission.
- Risk-based tool approvals, path constraints, Skill scanning, secret masking and audit logging.
- A task center for Agent, Goal and Mission tasks with common quota, result, cancellation and retry paths.
- Agent-level LLM concurrency, QPM and 429 backoff controls; these do not replace enterprise task/token quotas.
- AgentScope file memory and a disabled-memory mode. ReMeLight and ADBPG are not connected to the Java runtime and are not exposed as configuration options.
- Remote MCP OAuth 2.0 Client Credentials validation. Client secrets are encrypted at rest and never returned in clear text.

## Language support

The default console language is Simplified Chinese (`zh`). A user preference can be changed from the language menu and is stored per user. The seven shipped console languages are:

| Code | Language | Scope |
| --- | --- | --- |
| `zh` | Simplified Chinese | Default, full console |
| `en` | English | Console |
| `ja` | Japanese | Console |
| `ru` | Russian | Console |
| `pt-BR` | Brazilian Portuguese | Console |
| `id` | Indonesian | Console |
| `vi` | Vietnamese | Console |

Console translation covers React UI, design-system components, dates and common messages. It does not translate model responses, tool output, MCP results, uploaded files or Skill bodies. Agent language is a separate setting for built-in persona files such as `SOUL.md`, `AGENTS.md`, `PROFILE.md`, `MEMORY.md`, `BOOTSTRAP.md` and `HEARTBEAT.md`; Skill language availability depends on each Skill’s metadata. Language tags are normalized as BCP 47 values, for example `zh-CN` to `zh` and `pt-PT` to `pt-BR`.

## Permissions, sharing and isolation

Authorization has four enforced layers: enterprise RBAC, Agent ownership, workspace/Docker filesystem boundaries, and AgentScope Permission risk rules. The UI menu and a client-supplied `X-Agent-Id` are never authorization.

Team space is represented internally by the stable `PROJECT` scope. “Shared” means that members use one team Agent and its resources; it does not copy personal data into the team.

| Resource | Personal Agent | Team Agent |
| --- | --- | --- |
| Workspace, files and generated results | Owner only | Shared by authorized members of that team Agent |
| Conversations and history | Owner only | Shared according to team role and `agents:use` |
| Long-term memory | Owner’s Agent only | Shared by that team Agent, separate from personal memory |
| Skills and MCP | Owner maintains and uses them | Shared by the team Agent; maintenance requires the relevant management permission |
| Tasks and results | Owner only | Stored in `PROJECT` scope and checked on every read, cancel and retry |
| Personal channels and automation | Owner only | Never implicitly copied to the team |
| Approval memory | Bound to `agentId + ownerUserId + operation fingerprint` | Still bound to the member who approved |

Team roles are `OWNER`, `MANAGER`, `MEMBER` and `VIEWER`. Team membership does not grant the enterprise `agents:use` permission. `VIEWER` can read authorized team material but cannot start or continue a conversation. Even `SYSTEM_ADMIN` cannot routinely read another user’s personal Agent data; audited `data:breakglass` access is required.

The default `RISK_BASED` policy distinguishes ordinary reads from side effects. Workspace reads and low-risk tools may be `ALLOW`; Shell writes, deletion, installation, permission changes, uploads, deployment and high-risk MCP calls may be `ASK`; credential access, workspace escape and explicitly denied tools are `DENY`. Only explicit approval may be remembered. Denial, cancellation, timeout and permanent denial are never remembered, and one member’s remembered approval never transfers to another member.

Enterprise permission codes are explicit: `agents:use` runs permitted Agents; `workspace:personal`, `skills:personal`, `mcp:personal`, `channels:personal` and `automations:personal` manage the caller’s own resources; `agents:manage` manages non-personal/team Agent resources; `approvals:decide` decides authorized approvals; `audit:read` reads audit data; `system:manage` manages global infrastructure; and `data:breakglass` enables audited emergency access. None of these codes bypasses ownership, team membership or the Agent workspace boundary.

In Docker mode, the command/file tools and stdio MCP process see only the authorized Agent subdirectory. The workspace root, another user’s personal Agent and another team’s Agent are not mounted. The sandbox limits process, network and filesystem access, but enterprise RBAC and AgentScope Permission are still checked before a process starts.

## File preview

The conversation split preview supports browser-rendered images (`avif`, `bmp`, `gif`, `jpeg`, `jpg`, `png`, `svg`, `webp`), video (`m4v`, `mov`, `mp4`, `ogg`, `webm`), audio (`aac`, `flac`, `m4a`, `mp3`, `oga`, `wav`), PDF, DOCX, XLSX and common text/code formats (`bash`, `c`, `conf`, `cpp`, `css`, `csv`, `env`, `go`, `h`, `hpp`, `html`, `ini`, `java`, `js`, `json`, `jsx`, `less`, `log`, `md`, `properties`, `py`, `rb`, `rs`, `scss`, `sh`, `sql`, `toml`, `ts`, `tsx`, `txt`, `vue`, `xml`, `yaml`, `yml`, `zsh`). Text previews are limited to 1 MB; DOCX/XLSX previews to 20 MB. Complex Office formatting is not guaranteed to be identical.

## AgentScope 2.0 alignment

The project uses official AgentScope Java 2.0 capabilities where available: provider extensions and `ModelRegistry`, `RuntimeContext`, `AgentStateStore`, Harness Workspace, official file/execute tools, `DockerFilesystemSpec`, `IsolationScope.USER`, dynamic Skills, `SkillManageTool`, `PermissionContextState`, `PermissionEngine`, `RequireUserConfirmEvent` and fixed native Subagent factories. Workers use restricted tools and independent context; dynamic, nested and cross-team delegation is disabled. See [docs/AGENTSCOPE_2_ALIGNMENT.md](docs/AGENTSCOPE_2_ALIGNMENT.md).

## Project structure

```text
melon-plugin-api/   Third-party plugin SPI
melon-core/         Agent runtime, configuration, models, security and middleware
melon-tools/        Small business extensions such as scheduled-task tools
melon-channels/     Channel adapters, queues and channel access control
melon-app/          Spring Boot web app, authentication, enterprise services and APIs
console/            React web console
docs/               Architecture, upstream and release documentation
```

The Maven build aggregates the five Java modules and packages `console/dist` into the application JAR. There is no standalone CLI, Coding or desktop module.

## Requirements

JDK 17+, Maven 3.8+, Node.js 20.19+ (22 LTS recommended), and Docker for production file/command tools. A trusted developer may use the `local` profile, which executes tools on the host.

## Build and run

```bash
cd console
npm ci
npm run build
cd ..
mvn clean package
docker build -t melonpaw-company-sandbox:latest docker/sandbox
MELON_EXECUTION_SANDBOX=docker \
MELON_SANDBOX_IMAGE=melonpaw-company-sandbox:latest \
java -jar melon-app/target/melonpaw-company-web.jar
```

The default HTTP port is `8088`. Configure `MELON_WORKSPACE_ROOT` for the application workspace and, when the Java service runs in a container, `MELON_SANDBOX_HOST_WORKSPACE_ROOT` for the host path. Only the authorized Agent subdirectory is mounted; the whole workspace root is never mounted. stdio MCP follows Docker execution by default and can be controlled with `MELON_MCP_STDIO_MODE=local|docker|disabled`. `MELON_SANDBOX_RUNTIME=runsc` applies gVisor to both Agent Shell and stdio MCP; an unavailable runtime fails closed instead of falling back.

Local stdio MCP projects must be under `MELON_MCP_IMPORT_ROOTS`. They are validated and copied by content hash into the owning Agent's `.melon/mcp-sources` before `/workspace/...` paths are passed to the container; the original host directory is never mounted into the MCP container. Symlink escapes, cross-root inputs and oversized imports are rejected. Node and Python dependencies are prepared and cached inside the sandbox. This variable belongs to the Java process startup environment and requires a restart after changes; the equivalent Spring property is `melon.execution.mcp.import.roots`. If Java itself runs in Docker, mount trusted import roots read-only into the Java container; MCP containers still receive only their Agent workspace. When a path stored by the console differs from the read-only path visible to Java, set `MELON_MCP_PATH_MAPPINGS` to a JSON prefix map such as `{ "/home/mineru/Github": "/mcp-imports" }`. The longest prefix wins, the persisted MCP JSON remains unchanged, and every mapped target must still exist under `MELON_MCP_IMPORT_ROOTS`.

When no user exists, the console allows direct creation of the first administrator without a bootstrap token. If the initialization endpoint is exposed publicly, inject `MELON_BOOTSTRAP_TOKEN` before startup, or set `MELON_GENERATE_BOOTSTRAP_TOKEN=true` to write a generated token to `${MELON_HOME}/bootstrap.token`. The token field appears only when either protection is enabled. Creating the first administrator removes the generated token file and permanently closes bootstrap. SQLite is stored at `${MELON_HOME}/enterprise.db`; backups must include the database, `config.yaml` and `workspaces`, but never expose `master.key` or `bootstrap.token`.

## Model and secret configuration

Configure a model ID as `<provider>:<model>`, for example `dashscope:qwen-plus` or `deepseek:deepseek-chat`. Supported provider keys can be maintained in the console or injected with `DASHSCOPE_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` and `DEEPSEEK_API_KEY`. Ollama must be installed and started by the deployment environment; the console only discovers `/api/tags`, pulls or deletes models and stores the selected `ollama:<model>`.

Provider OAuth is not supported; use API keys. Remote MCP supports only OAuth 2.0 Client Credentials, not Authorization Code, PKCE or Dynamic Client Registration. External Skill imports accept HTTPS ZIP files containing `SKILL.md`; arbitrary local paths and marketplace detail pages are rejected. Model, channel and MCP credentials use AES-256-GCM. `MELON_MASTER_KEY` may be injected, otherwise `${MELON_HOME}/master.key` is generated with restricted permissions.

## Development and verification

```bash
mvn -pl melon-app spring-boot:run -Dspring-boot.run.profiles=local
cd console
npm ci
npm run dev
npm run test:run
npm run build
```

Vite runs on `http://localhost:5173`; set `VITE_API_BASE_URL` when the backend uses another address. The `local` profile uses the cross-platform host filesystem adapter (`cmd.exe` on Windows, `sh` on macOS/Linux) and is for trusted development only. These commands are the verification entry points and are not a claim that every working-tree change has already passed.

## Deployment and security

Use HTTPS termination and restrict the application port. Shell, browser automation, MCP, plugins, Skill imports and file writes must be constrained by enterprise roles, Agent ownership, workspace boundaries, sandbox policy and approvals. AgentScope Permission does not replace enterprise RBAC. Verify path escape, authorization bypass, approval bypass, process cancellation, backup recovery and upgrade rollback before production.

## Documentation and license

- [AgentScope 2.0 alignment](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [Enterprise capability and permission matrix](docs/ENTERPRISE_CAPABILITY_MATRIX.md)
- [Upstream and independence statement](docs/UPSTREAM.md)
- [Open-source release checklist](docs/OPEN_SOURCE_CHECKLIST.md)
- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

The intended code license is [Apache License 2.0](LICENSE). The current Git history still contains restricted third-party material, so the repository is not automatically a compliant public release. Complete the open-source checklist and verify `NOTICE`, upstream attribution and all third-party redistribution terms before publishing source or binaries.
