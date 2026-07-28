# 运行与容器部署

## 运行模式

| Spring Profile | Java 位置 | Agent Shell / StdIO MCP | 数据目录 | 适用场景 |
| --- | --- | --- | --- | --- |
| `local` | 宿主机 | 宿主机 | `~/.melonPaw-CompanyAI` | 仅限可信开发环境 |
| `docker` | 宿主机 | 独立 Docker 沙箱 | `~/.melonPaw-CompanyAI` | 本机开发和调试，默认模式 |
| `container` | Docker 容器 | 通过 Docker socket 启动同级沙箱 | `/data` | Docker Compose 部署 |

公共参数位于 `melon-app/src/main/resources/application.yaml`，三个 Profile 只覆盖与运行边界有关的字段。环境变量始终拥有最高优先级。

## 宿主机直接执行工具

该模式没有 Docker 文件隔离，只能用于完全可信的开发机器：

```bash
SPRING_PROFILES_ACTIVE=local java -jar melon-app/target/melonpaw-company-web.jar
```

IDEA 中将 Active profiles 设置为 `local` 即可。不要在服务器或包含多用户数据的环境使用该模式。

## 宿主机使用 Docker 沙箱

先构建项目标准沙箱镜像：

```bash
docker build -t melonpaw-company-sandbox:latest docker/sandbox
```

`docker` 是默认 Profile，可以直接启动，也可以显式指定：

```bash
SPRING_PROFILES_ACTIVE=docker java -jar melon-app/target/melonpaw-company-web.jar
```

Java、配置和数据库仍在宿主机；只有 Agent Shell 和 StdIO MCP 进入独立容器。Docker 或 gVisor 配置错误时不会回退到本机执行。

## Docker Compose

Java 镜像不内置 Maven，也不会在 Docker 构建时下载 Java 依赖。先在宿主机或 CI 生成 JAR：

```bash
mvn -pl melon-app -am -DskipTests package
```

然后复制环境模板，将其中两个宿主目录改为真实绝对路径并启动：

```bash
cp .env.example .env
docker compose up --build -d
```

如果 Java 源码发生变化，重新执行 Maven 打包命令后再构建 `app` 镜像。首次部署也可以合并执行：

```bash
mvn -pl melon-app -am -DskipTests package && docker compose up --build -d
```

默认入口：

- 控制台：`http://localhost:5173`
- Java API：`http://localhost:8088`

Compose 会组装 Java 运行镜像，并构建前端 Nginx 和标准工具沙箱镜像。前端通过同源 `/api` 反向代理访问 Java，SSE 响应关闭代理缓冲。

### 工作区路径映射

Java 容器看到的工作区为 `/data/workspaces`，Docker daemon 挂载沙箱时需要宿主机真实路径。因此 `.env` 中的 `MELON_HOST_DATA_DIR` 必须是绝对路径，Compose 会同时设置：

```text
MELON_WORKSPACE_ROOT=/data/workspaces
MELON_SANDBOX_HOST_WORKSPACE_ROOT=<宿主数据目录>/workspaces
```

每个 Agent 仍只挂载自己的工作区，不会因为 Java 容器化而扩大访问范围。

### 本地 MCP 源码

`MELON_HOST_MCP_IMPORT_DIR` 以只读方式挂载到 Java 容器的 `/mcp-imports`。如果页面填写的是宿主机绝对路径，需要同步配置前缀映射，例如：

```dotenv
MELON_HOST_MCP_IMPORT_DIR=/Users/me/IdeaWorkSpace/Github
MELON_MCP_PATH_MAPPINGS={"/Users/me/IdeaWorkSpace/Github":"/mcp-imports"}
```

Java 验证源码后按内容哈希复制到所属 Agent 工作区，MCP 沙箱不会直接挂载原始宿主目录。

### 远程 MCP 出站与网络层 egress

远程(HTTP/SSE)MCP 与 OAuth token 请求在应用层有 SSRF 防线（`MELON_MCP_EGRESS_*`）：

- link-local（含 `169.254.169.254` 云元数据）、any-local、多播始终拦截，任何开关都不放行；
- loopback 与私网默认拒绝，可用 `MELON_MCP_EGRESS_ALLOWLIST` 按主机精确放行，或按档打开
  `MELON_MCP_EGRESS_ALLOW_LOOPBACK` / `MELON_MCP_EGRESS_ALLOW_PRIVATE`；
- 保存配置、建立连接和每次请求前都会重新做 DNS 校验。

**应用层校验不等于网络隔离。** 当前 MCP/OAuth 使用带 `GuardedDns` 的 OkHttp，校验通过的地址会
直接交给 OkHttp 建立连接，代码层的 DNS rebinding 竞态已关闭。要求严格隔离的部署仍必须在网络层
再加一道出站约束，例如在 Linux 宿主机上通过 `DOCKER-USER` 链限制 `app` 容器的目的地址：

```bash
# 以 compose 网络网段为源(按实际网段调整)，禁止访问云元数据与内网段
iptables -I DOCKER-USER -s 172.20.0.0/16 -d 169.254.169.254 -j DROP
iptables -I DOCKER-USER -s 172.20.0.0/16 -d 10.0.0.0/8 -j DROP
iptables -I DOCKER-USER -s 172.20.0.0/16 -d 172.16.0.0/12 -j DROP
iptables -I DOCKER-USER -s 172.20.0.0/16 -d 192.168.0.0/16 -j DROP
```

注意事项：

- 给 compose 网络固定子网（`networks.default.ipam`）后上述规则才可稳定引用；
- `MELON_MCP_EGRESS_ALLOWLIST` 放行的内网主机需要在防火墙侧同步放行，两层清单保持一致；
- Docker Desktop（macOS/Windows）中 iptables 规则位于虚拟机内部，生产建议使用 Linux 宿主机
  或云厂商安全组/出站防火墙实现同等约束；
- Agent 工具沙箱的出口用 `MELON_SANDBOX_NETWORK` 单独控制，与本节的 Java 服务出站是两条链路。

### WebFlux 阻塞线程池

Java API 会通过 Spring WebFlux 官方阻塞执行配置，把控制器调用以及 SQLite、工作区文件和同步 SDK
调用调度到有界的 `melon-web-blocking` 线程池，避免阻塞 Netty event loop。带请求体的接口会在参数解析
完成后再切换到该线程池。默认值适合本地和中小规模部署：

```dotenv
MELON_WEB_BLOCKING_CORE_THREADS=16
MELON_WEB_BLOCKING_MAX_THREADS=16
MELON_WEB_BLOCKING_QUEUE_CAPACITY=256
```

默认核心线程数与最大线程数相同，因为标准 `ThreadPoolExecutor` 会先填满队列再扩容。生产环境应结合
CPU、SQLite 锁竞争和请求耗时压测后调整；若降低核心线程数，需同时评估排队延迟。不要使用无界队列；队列饱和时服务返回
`503 runtime_overloaded`，由调用方退避重试。

## 生产注意事项

1. 固定镜像版本或 digest，不要长期使用 `latest`。
2. 将 `MELON_HOST_MCP_IMPORT_DIR` 收窄到专用可信目录。
3. Docker socket 等价于较高的宿主机控制权限；生产环境应使用独立 Docker daemon、rootless Docker 或受控 socket proxy。
4. 设置 `MELON_BOOTSTRAP_TOKEN` 和 `MELON_MASTER_KEY`，不要把真实密钥提交到 `.env`。
5. 通过 `MELON_SANDBOX_NETWORK` 使用受限出口网络；需要 gVisor 时设置 `MELON_SANDBOX_RUNTIME=runsc`。
6. 远程 MCP 的应用层 SSRF 校验之外，按「远程 MCP 出站与网络层 egress」一节叠加网络层出站限制；
   应用层防护、Docker 沙箱隔离和网络层出口控制是三层不同的能力，不能互相替代。
