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

复制环境模板并将其中两个宿主目录改为真实绝对路径：

```bash
cp .env.example .env
docker compose up --build -d
```

默认入口：

- 控制台：`http://localhost:5173`
- Java API：`http://localhost:8088`

Compose 会构建三个镜像：Java、前端 Nginx 和标准工具沙箱。前端通过同源 `/api` 反向代理访问 Java，SSE 响应关闭代理缓冲。

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

## 生产注意事项

1. 固定镜像版本或 digest，不要长期使用 `latest`。
2. 将 `MELON_HOST_MCP_IMPORT_DIR` 收窄到专用可信目录。
3. Docker socket 等价于较高的宿主机控制权限；生产环境应使用独立 Docker daemon、rootless Docker 或受控 socket proxy。
4. 设置 `MELON_BOOTSTRAP_TOKEN` 和 `MELON_MASTER_KEY`，不要把真实密钥提交到 `.env`。
5. 通过 `MELON_SANDBOX_NETWORK` 使用受限出口网络；需要 gVisor 时设置 `MELON_SANDBOX_RUNTIME=runsc`。

