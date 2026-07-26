# MelonPaw-Company

> **Phát hành đầu tiên trên toàn cầu · “Claw” doanh nghiệp Java**

MelonPaw-Company là nền tảng cộng tác và thực thi tác vụ AI Agent để triển khai riêng trong một doanh nghiệp. Nền tảng dùng Java 17, Spring Boot WebFlux, AgentScope Java 2.0.0 GA và console React, TypeScript, Vite. Nền tảng cung cấp workspace, bộ nhớ, Skills, MCP, thực thi công cụ, tự động hóa và các kênh nội địa. “Phát hành đầu tiên trên toàn cầu” là định vị phát hành của dự án, không phải xếp hạng hay chứng nhận của bên thứ ba.

“Claw” là cách gọi một dạng sản phẩm phổ biến trong cộng đồng Agent Trung Quốc. Dự án tham khảo Hermes Agent và các Agent mã nguồn mở khác, nhưng không phải bản port Java chính thức, fork, dependency, mục tiêu tương thích hay sản phẩm được ủy quyền.

## Ngôn ngữ README

File mặc định là tiếng Trung giản thể. Cả bảy file đều mô tả cùng một sản phẩm, ranh giới quyền, triển khai và bảo mật bằng các ngôn ngữ khác nhau.

| Ngôn ngữ | README |
| --- | --- |
| 简体中文 (mặc định) | [README.md](README.md) |
| English | [README.en.md](README.en.md) |
| 日本語 | [README.ja.md](README.ja.md) |
| Русский | [README.ru.md](README.ru.md) |
| Português (Brasil) | [README.pt-BR.md](README.pt-BR.md) |
| Bahasa Indonesia | [README.id.md](README.id.md) |
| Tiếng Việt | [README.vi.md](README.vi.md) |

## Phạm vi sản phẩm

- Một bản triển khai phục vụ một doanh nghiệp; đây không phải SaaS đa tenant, thuê bao hay tính phí.
- Sản phẩm cung cấp console Web responsive hỗ trợ trình duyệt di động; không có desktop client, CLI hay chế độ Coding.
- Có thể chạy dưới dạng JAR hoặc tiến trình thông thường. Hiện chưa cung cấp Kubernetes.
- Danh tính, vai trò, thành viên nhóm và tài nguyên cá nhân dùng SQLite tích hợp; không cần PostgreSQL.
- Hỗ trợ DingTalk, Feishu, QQ, WeChat và WeCom; thông tin kênh thuộc người cấu hình.
- Model cục bộ dùng Ollama do môi trường cung cấp; ứng dụng không cài, cập nhật hay tích hợp Ollama/llama.cpp.
- `/mission` dùng Subagent cố định `researcher`, `executor`, `reviewer`; ủy quyền động, lồng nhau và giữa nhóm bị tắt.

## Năng lực

- Xác thực doanh nghiệp, RBAC, Agent cá nhân và Agent nhóm.
- Workspace cá nhân, Markdown, Skills, MCP, kênh, hội thoại và bộ nhớ.
- Agent nhóm chia sẻ file, Skills, MCP, hội thoại, bộ nhớ và kết quả với thành viên được cấp quyền.
- Hội thoại streaming SSE, gọi công cụ và sự kiện `tool/result`.
- Quản trị Agent, model, công cụ, MCP, audit, backup và hệ thống theo quyền.
- Phê duyệt theo rủi ro, giới hạn đường dẫn, quét Skill, che giấu secret và audit log.
- Trung tâm tác vụ Agent, Goal, Mission với quota, kết quả, hủy và thử lại thống nhất.
- Giới hạn concurrency LLM, QPM và backoff 429 theo Agent; không thay thế quota task/token.
- Bộ nhớ file AgentScope và chế độ tắt bộ nhớ. ReMeLight và ADBPG chưa nối vào Java runtime, không hiển thị trong cấu hình.
- Kiểm tra OAuth 2.0 Client Credentials cho MCP từ xa; Client Secret được mã hóa khi lưu.

## Hỗ trợ ngôn ngữ

Console mặc định dùng tiếng Trung giản thể (`zh`). Người dùng đổi tùy chọn đã lưu từ menu ngôn ngữ. Bảy ngôn ngữ là Trung giản thể, Anh, Nhật, Nga, Bồ Đào Nha Brazil, Indonesia và Việt.

Bản dịch bao gồm UI React, design system, ngày tháng và thông báo chung. Phản hồi model, đầu ra công cụ, kết quả MCP, file và nội dung Skill không tự động dịch. Ngôn ngữ Agent là thiết lập riêng cho `SOUL.md`, `AGENTS.md`, `PROFILE.md`, `MEMORY.md`, `BOOTSTRAP.md`, `HEARTBEAT.md`; ngôn ngữ Skill theo metadata của Skill. Tag được chuẩn hóa theo BCP 47, ví dụ `zh-CN` thành `zh`, `pt-PT` thành `pt-BR`.

## Quyền, chia sẻ và cô lập

Ủy quyền được kiểm tra qua RBAC doanh nghiệp, chủ sở hữu Agent, ranh giới file workspace/Docker và luật rủi ro AgentScope Permission. Ẩn menu hoặc gửi `X-Agent-Id` không tạo quyền.

Không gian nhóm dùng scope nội bộ `PROJECT`. “Chia sẻ” nghĩa là dùng chung một Agent nhóm, không sao chép dữ liệu cá nhân vào nhóm.

| Tài nguyên | Agent cá nhân | Agent nhóm |
| --- | --- | --- |
| Workspace, file, kết quả | Chỉ chủ sở hữu | Thành viên được cấp quyền |
| Hội thoại và lịch sử | Chỉ chủ sở hữu | Theo vai trò và `agents:use` |
| Bộ nhớ dài hạn | Chỉ Agent của chủ sở hữu | Dùng chung trong Agent, tách khỏi cá nhân |
| Skills và MCP | Chủ sở hữu quản lý | Dùng chung; bảo trì cần quyền |
| Tác vụ và kết quả | Chỉ chủ sở hữu | Scope `PROJECT`, kiểm tra khi đọc/hủy/thử lại |
| Kênh và tự động hóa cá nhân | Chỉ chủ sở hữu | Không tự động sao chép |
| Ghi nhớ phê duyệt | `agentId + ownerUserId + fingerprint` | Gắn với người đã phê duyệt |

Vai trò nhóm là `OWNER`, `MANAGER`, `MEMBER`, `VIEWER`. Thành viên không tự có `agents:use`. `VIEWER` đọc kết quả được cấp quyền nhưng không bắt đầu/tiếp tục hội thoại. `SYSTEM_ADMIN` cũng không tự động đọc dữ liệu cá nhân; cần `data:breakglass` có audit.

Chính sách `RISK_BASED` cho phép đọc thường, hỏi khi Shell ghi/xóa/cài/đổi quyền, upload, deploy hoặc MCP rủi ro cao, và từ chối credential, thoát workspace hay công cụ bị từ chối. Chỉ phê duyệt rõ ràng mới được ghi nhớ; từ chối, hủy, timeout và từ chối vĩnh viễn không ghi nhớ hoặc chuyển sang người khác.

Mã quyền doanh nghiệp là rõ ràng: `agents:use` chạy Agent được cấp quyền; `workspace:personal`, `skills:personal`, `mcp:personal`, `channels:personal`, `automations:personal` quản lý tài nguyên của mình; `agents:manage` quản lý Agent không cá nhân/Agent nhóm; `approvals:decide` quyết định phê duyệt được phép; `audit:read` đọc audit; `system:manage` quản lý hạ tầng chung; `data:breakglass` cho phép truy cập khẩn cấp có audit. Không mã nào vượt qua quyền sở hữu, thành viên nhóm hay ranh giới workspace của Agent.

Trong Docker, tool file/command và tiến trình MCP stdio chỉ thấy thư mục con của Agent được cấp quyền. Root workspace, Agent cá nhân của người khác và Agent nhóm khác không được mount. Sandbox giới hạn tiến trình, mạng và filesystem; RBAC doanh nghiệp và AgentScope Permission vẫn được kiểm tra trước khi chạy.

## Xem trước file

Preview hỗ trợ ảnh (`avif`, `bmp`, `gif`, `jpeg`, `jpg`, `png`, `svg`, `webp`), video (`m4v`, `mov`, `mp4`, `ogg`, `webm`), âm thanh (`aac`, `flac`, `m4a`, `mp3`, `oga`, `wav`), PDF, DOCX, XLSX và định dạng văn bản/mã phổ biến. Văn bản tối đa 1 MB, DOCX/XLSX tối đa 20 MB; định dạng Office phức tạp không đảm bảo giống hoàn toàn.

## Tương thích AgentScope 2.0

Dự án ưu tiên provider extension/`ModelRegistry`, `RuntimeContext`, `AgentStateStore`, Harness Workspace, tool file/execute chính thức, `DockerFilesystemSpec`, `IsolationScope.USER`, Skill động, `SkillManageTool`, `PermissionContextState`, `PermissionEngine`, `RequireUserConfirmEvent` và factory Native Subagent cố định. Worker dùng tool hạn chế và context độc lập; ủy quyền động, lồng nhau, giữa các nhóm bị tắt. Xem [docs/AGENTSCOPE_2_ALIGNMENT.md](docs/AGENTSCOPE_2_ALIGNMENT.md).

## Cấu trúc dự án

```text
melon-plugin-api/   SPI plugin bên thứ ba
melon-core/         runtime Agent, cấu hình, model, bảo mật, middleware
melon-tools/        mở rộng nhỏ như công cụ tác vụ định kỳ
melon-channels/     adapter kênh, queue, kiểm soát truy cập
melon-app/          Spring Boot Web, xác thực, dịch vụ doanh nghiệp, API
console/            console Web React
docs/               kiến trúc, nguồn và tài liệu phát hành
```

Maven gộp năm module Java và đóng gói `console/dist` vào JAR. Không có module CLI, Coding hay desktop độc lập.

## Yêu cầu

JDK 17+, Maven 3.8+, Node.js 20.19+ (khuyến nghị 22 LTS) và Docker cho tool file/command production. Phát triển tin cậy có thể dùng profile `local` để chạy trên host.

## Build và chạy

```bash
cd console && npm ci && npm run build
cd .. && mvn clean package
docker build -t melonpaw-company-sandbox:latest docker/sandbox
MELON_EXECUTION_SANDBOX=docker MELON_SANDBOX_IMAGE=melonpaw-company-sandbox:latest \
  java -jar melon-app/target/melonpaw-company-web.jar
```

Cổng HTTP mặc định là `8088`. Trong production, Docker cô lập các tool file/lệnh của Agent và MCP stdio, chỉ mount workspace của Agent hiện được cấp quyền. Profile chạy, ánh xạ path workspace, import MCP cục bộ đáng tin cậy, gVisor và Compose được mô tả trong [tài liệu triển khai](docs/DEPLOYMENT.md).

Khi chưa có user, có thể tạo administrator đầu tiên trực tiếp mà không cần bootstrap token. Nếu endpoint khởi tạo được công khai, hãy đặt `MELON_BOOTSTRAP_TOKEN` trước khi khởi động hoặc dùng `MELON_GENERATE_BOOTSTRAP_TOKEN=true` để tạo `${MELON_HOME}/bootstrap.token`. Trường token chỉ xuất hiện khi một trong hai cơ chế bảo vệ được bật. Sau khi tạo administrator đầu tiên, file token đã tạo sẽ bị xóa và bootstrap được đóng vĩnh viễn. SQLite ở `${MELON_HOME}/enterprise.db`; backup gồm DB, `config.yaml`, `workspaces`, nhưng không để lộ `master.key` và `bootstrap.token`.

## Model và secret

ID model có dạng `<provider>:<model>`, ví dụ `dashscope:qwen-plus` hoặc `deepseek:deepseek-chat`. Cấu hình qua console hoặc `DASHSCOPE_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`. Ollama do môi trường cài/chạy; console chỉ đọc `/api/tags`, pull/delete và lưu `ollama:<model>`.

OAuth provider chưa hỗ trợ; dùng API key. MCP từ xa chỉ hỗ trợ OAuth 2.0 Client Credentials, không hỗ trợ Authorization Code, PKCE, Dynamic Client Registration. Skill ngoài chỉ nhận ZIP HTTPS có `SKILL.md`; path cục bộ tùy ý và trang marketplace bị từ chối. Secret model, kênh, MCP dùng AES-256-GCM; có thể inject `MELON_MASTER_KEY`, nếu không tạo `${MELON_HOME}/master.key`.

## Phát triển và kiểm tra

```bash
mvn -pl melon-app spring-boot:run -Dspring-boot.run.profiles=local
cd console && npm ci && npm run dev
npm run test:run
npm run build
```

Vite chạy ở `http://localhost:5173`; đặt `VITE_API_BASE_URL` khi backend khác địa chỉ. `local` dùng `cmd.exe` trên Windows và `sh` trên macOS/Linux, chỉ dành cho phát triển tin cậy. Đây là điểm vào kiểm tra, không phải tuyên bố mọi thay đổi đã đạt.

## Triển khai và bảo mật

Kết thúc HTTPS tại reverse proxy và giới hạn cổng ứng dụng. Shell, tự động hóa trình duyệt, MCP, plugin, import Skill và ghi file phải bị giới hạn bởi vai trò, chủ Agent, workspace, sandbox và phê duyệt. AgentScope Permission không thay thế RBAC doanh nghiệp. Trước production, kiểm tra path escape, bypass quyền/phê duyệt, hủy tiến trình, khôi phục backup và rollback nâng cấp.

## Tài liệu và giấy phép

- [Tương thích AgentScope 2.0](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [Ma trận năng lực và quyền doanh nghiệp](docs/ENTERPRISE_CAPABILITY_MATRIX.md)
- [Nguồn và tính độc lập](docs/UPSTREAM.md)
- [Checklist phát hành open source](docs/OPEN_SOURCE_CHECKLIST.md)
- [Hướng dẫn đóng góp](CONTRIBUTING.md)
- [Chính sách bảo mật](SECURITY.md)

Giấy phép dự kiến là [Apache License 2.0](LICENSE). Lịch sử Git vẫn chứa tài liệu bên thứ ba bị hạn chế, nên repository chưa tự động là bản phát hành công khai hợp lệ. Hoàn tất checklist và kiểm tra `NOTICE`, ghi nhận upstream và điều kiện tái phân phối trước khi phát hành mã hoặc binary.
