# MelonPaw-Company

> **Rilis pertama di dunia · “Claw” enterprise berbasis Java**

MelonPaw-Company adalah platform kolaborasi dan eksekusi tugas AI Agent untuk deployment privat dalam satu perusahaan. Platform ini dibangun dengan Java 17, Spring Boot WebFlux, AgentScope Java 2.0.0 GA, serta konsol React, TypeScript, dan Vite. Platform menyediakan workspace, memori, Skills, MCP, eksekusi tool, otomatisasi, dan kanal domestik. “Rilis pertama di dunia” adalah posisi publikasi proyek, bukan peringkat atau sertifikasi pihak ketiga.

Istilah “Claw” menggambarkan bentuk produk yang umum di komunitas Agent Tiongkok. Proyek ini mengambil gagasan dari Hermes Agent dan Agent open source lain, tetapi bukan port Java resmi, fork kode, dependency, target kompatibilitas, atau produk resmi Hermes Agent.

## Bahasa README

File masuk default adalah Mandarin Sederhana. Ketujuh file menjelaskan produk, batas izin, deployment, dan keamanan yang sama dalam bahasa berbeda.

| Bahasa | README |
| --- | --- |
| 简体中文 (default) | [README.md](README.md) |
| English | [README.en.md](README.en.md) |
| 日本語 | [README.ja.md](README.ja.md) |
| Русский | [README.ru.md](README.ru.md) |
| Português (Brasil) | [README.pt-BR.md](README.pt-BR.md) |
| Bahasa Indonesia | [README.id.md](README.id.md) |
| Tiếng Việt | [README.vi.md](README.vi.md) |

## Batas produk

- Satu deployment melayani satu perusahaan; ini bukan SaaS multi-tenant, subscription, atau billing.
- Klien yang diberikan adalah konsol Web responsif dengan dukungan browser seluler; tidak ada desktop client, CLI, atau mode Coding.
- Aplikasi dapat dijalankan sebagai JAR atau proses biasa. Deployment Kubernetes belum disediakan.
- Identitas, peran, keanggotaan tim, dan pemetaan resource pribadi disimpan di SQLite tertanam; PostgreSQL tidak diperlukan.
- Kanal domestik yang didukung adalah DingTalk, Feishu, QQ, WeChat, dan WeCom. Kredensial kanal menjadi milik pengguna yang mengaturnya.
- Model lokal memakai Ollama dari environment deployment. Aplikasi tidak menginstal, memperbarui, atau menyertakan Ollama maupun llama.cpp.
- `/mission` memakai Native Subagent tetap `researcher`, `executor`, dan `reviewer`; delegasi dinamis, bersarang, dan lintas tim tetap dinonaktifkan.

## Kemampuan

- Autentikasi enterprise, RBAC, Agent pribadi, dan Agent tim.
- Workspace pribadi, Markdown, Skills, MCP, kanal, percakapan, dan memori.
- Keanggotaan dan workspace tim; Agent tim membagikan file, Skills, MCP, percakapan, memori, dan hasil kepada anggota yang berwenang.
- Percakapan streaming SSE, pemanggilan tool, dan event `tool/result`.
- Administrasi Agent, model, tool, MCP, audit, backup, dan sistem sesuai izin.
- Persetujuan berbasis risiko, pembatasan path, pemindaian Skill, masking secret, dan audit log.
- Pusat tugas Agent, Goal, dan Mission dengan quota, hasil, pembatalan, dan retry bersama.
- Batas concurrency LLM, QPM, dan backoff 429 per Agent; ini tidak menggantikan quota task/token enterprise.
- Memori file AgentScope dan mode memori dinonaktifkan. ReMeLight dan ADBPG belum terhubung ke runtime Java dan tidak ditampilkan di konfigurasi.
- Validasi OAuth 2.0 Client Credentials untuk MCP remote; Client Secret disimpan terenkripsi dan tidak dikembalikan dalam teks biasa.

## Dukungan bahasa

Bahasa konsol default adalah Mandarin Sederhana (`zh`). Pengguna dapat mengubah preferensi tersimpan dari menu bahasa. Tujuh bahasa yang tersedia adalah Mandarin Sederhana, Inggris, Jepang, Rusia, Portugis Brasil, Indonesia, dan Vietnam.

Terjemahan konsol mencakup UI React, komponen design system, tanggal, dan pesan umum. Respons model, output tool, hasil MCP, file upload, dan isi Skill tidak diterjemahkan otomatis. Bahasa Agent adalah pengaturan terpisah untuk file persona seperti `SOUL.md`, `AGENTS.md`, `PROFILE.md`, `MEMORY.md`, `BOOTSTRAP.md`, dan `HEARTBEAT.md`; bahasa Skill mengikuti metadata Skill. Tag bahasa dinormalisasi sebagai BCP 47, misalnya `zh-CN` menjadi `zh` dan `pt-PT` menjadi `pt-BR`.

## Izin, berbagi, dan isolasi

Otorisasi diperiksa dalam empat lapisan: RBAC enterprise, pemilik Agent, batas file workspace/Docker, dan aturan risiko AgentScope Permission. Menu yang disembunyikan dan `X-Agent-Id` dari client bukan otorisasi.

Ruang tim memakai scope internal stabil `PROJECT`. “Dibagikan” berarti anggota memakai Agent tim yang sama, bukan menyalin data pribadi ke tim.

| Resource | Agent pribadi | Agent tim |
| --- | --- | --- |
| Workspace, file, hasil | Pemilik saja | Anggota berwenang Agent tersebut |
| Percakapan dan riwayat | Pemilik saja | Sesuai peran tim dan `agents:use` |
| Memori jangka panjang | Agent pemilik saja | Dibagikan dalam Agent dan terpisah dari memori pribadi |
| Skills dan MCP | Dikelola pemilik | Dibagikan; pemeliharaan perlu izin terkait |
| Tugas dan hasil | Pemilik saja | Scope `PROJECT`, dicek saat baca, batal, dan retry |
| Kanal dan otomatisasi pribadi | Pemilik saja | Tidak pernah disalin secara implisit |
| Memori persetujuan | Terikat `agentId + ownerUserId + fingerprint` | Tetap terikat anggota yang menyetujui |

Peran tim adalah `OWNER`, `MANAGER`, `MEMBER`, dan `VIEWER`. Keanggotaan tidak memberikan `agents:use`. `VIEWER` dapat membaca hasil yang diizinkan tetapi tidak dapat memulai atau melanjutkan percakapan. Bahkan `SYSTEM_ADMIN` tidak otomatis membaca data pribadi; diperlukan `data:breakglass` yang diaudit.

Kebijakan default `RISK_BASED` mengizinkan baca biasa, meminta konfirmasi untuk penulisan Shell, penghapusan, instalasi, perubahan izin, upload, deployment, dan MCP berisiko tinggi, serta menolak akses credential, escape workspace, dan tool yang ditolak eksplisit. Hanya persetujuan eksplisit yang boleh diingat. Penolakan, pembatalan, timeout, dan penolakan permanen tidak pernah diingat atau dipindahkan ke anggota lain.

Kode izin enterprise bersifat eksplisit: `agents:use` menjalankan Agent yang diizinkan; `workspace:personal`, `skills:personal`, `mcp:personal`, `channels:personal`, dan `automations:personal` mengelola resource sendiri; `agents:manage` mengelola Agent non-pribadi/tim; `approvals:decide` memutuskan persetujuan yang berwenang; `audit:read` membaca audit; `system:manage` mengelola infrastruktur global; `data:breakglass` memberi akses darurat yang diaudit. Tidak ada kode yang melewati kepemilikan, keanggotaan tim, atau batas workspace Agent.

Dalam mode Docker, tool file/command dan proses MCP stdio hanya melihat subdirektori Agent yang berwenang. Root workspace, Agent pribadi pengguna lain, dan Agent tim lain tidak di-mount. Sandbox membatasi proses, jaringan, dan filesystem, sementara RBAC enterprise dan AgentScope Permission tetap diperiksa sebelum proses berjalan.

## Preview file

Preview terpisah mendukung gambar (`avif`, `bmp`, `gif`, `jpeg`, `jpg`, `png`, `svg`, `webp`), video (`m4v`, `mov`, `mp4`, `ogg`, `webm`), audio (`aac`, `flac`, `m4a`, `mp3`, `oga`, `wav`), PDF, DOCX, XLSX, dan format teks/kode umum. Teks dibatasi 1 MB dan DOCX/XLSX 20 MB; format Office kompleks tidak dijamin sama persis.

## Keselarasan AgentScope 2.0

Proyek memprioritaskan kemampuan resmi: provider extension/`ModelRegistry`, `RuntimeContext`, `AgentStateStore`, Harness Workspace, tool file/execute resmi, `DockerFilesystemSpec`, `IsolationScope.USER`, Skill dinamis, `SkillManageTool`, `PermissionContextState`, `PermissionEngine`, `RequireUserConfirmEvent`, dan factory Native Subagent tetap. Worker memakai tool terbatas dan konteks mandiri; delegasi dinamis, bersarang, dan lintas tim dinonaktifkan. Lihat [docs/AGENTSCOPE_2_ALIGNMENT.md](docs/AGENTSCOPE_2_ALIGNMENT.md).

## Struktur proyek

```text
melon-plugin-api/   SPI plugin pihak ketiga
melon-core/         runtime Agent, konfigurasi, model, keamanan, middleware
melon-tools/        ekstensi kecil seperti tool tugas terjadwal
melon-channels/     adapter kanal, queue, kontrol akses
melon-app/          Spring Boot Web, autentikasi, layanan enterprise, API
console/            konsol Web React
docs/               arsitektur, sumber, dokumentasi rilis
```

Maven menggabungkan lima modul Java dan memasukkan `console/dist` ke JAR. Tidak ada modul CLI, Coding, atau desktop mandiri.

## Persyaratan

JDK 17+, Maven 3.8+, Node.js 20.19+ (22 LTS disarankan), dan Docker untuk tool file/command production. Pengembangan tepercaya dapat memakai profile `local` untuk menjalankan tool di host.

## Build dan menjalankan

```bash
cd console && npm ci && npm run build
cd .. && mvn clean package
docker build -t melonpaw-company-sandbox:latest docker/sandbox
MELON_EXECUTION_SANDBOX=docker MELON_SANDBOX_IMAGE=melonpaw-company-sandbox:latest \
  java -jar melon-app/target/melonpaw-company-web.jar
```

Port HTTP default adalah `8088`. Atur `MELON_WORKSPACE_ROOT` untuk workspace aplikasi dan `MELON_SANDBOX_HOST_WORKSPACE_ROOT` untuk path host jika Java berjalan di container. Hanya subdirektori Agent yang berwenang yang di-mount; root workspace tidak pernah di-mount seluruhnya. MCP stdio mengikuti Docker secara default dan dapat diatur dengan `MELON_MCP_STDIO_MODE=local|docker|disabled`.

Dalam mode terlindungi tanpa user dan tanpa `MELON_BOOTSTRAP_TOKEN`, token dibuat di `${MELON_HOME}/bootstrap.token`. Setelah admin pertama dibuat, token dihapus dan bootstrap ditutup permanen. Profile `local` tidak membuat token kecuali `MELON_GENERATE_BOOTSTRAP_TOKEN=true`. SQLite berada di `${MELON_HOME}/enterprise.db`; backup mencakup DB, `config.yaml`, dan `workspaces`, tetapi tidak boleh mengekspos `master.key` atau `bootstrap.token`.

## Model dan secret

Gunakan ID model `<provider>:<model>`, misalnya `dashscope:qwen-plus` atau `deepseek:deepseek-chat`. Konfigurasi melalui konsol atau `DASHSCOPE_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, dan `DEEPSEEK_API_KEY`. Ollama harus dipasang dan dijalankan oleh environment; konsol hanya membaca `/api/tags`, pull/delete, dan menyimpan `ollama:<model>`.

OAuth provider belum didukung; gunakan API key. MCP remote hanya mendukung OAuth 2.0 Client Credentials, bukan Authorization Code, PKCE, atau Dynamic Client Registration. Skill eksternal hanya menerima ZIP HTTPS yang berisi `SKILL.md`; path lokal arbitrer dan halaman detail marketplace ditolak. Secret model, kanal, dan MCP memakai AES-256-GCM; `MELON_MASTER_KEY` dapat disuntikkan, jika tidak `${MELON_HOME}/master.key` dibuat.

## Pengembangan dan verifikasi

```bash
mvn -pl melon-app spring-boot:run -Dspring-boot.run.profiles=local
cd console && npm ci && npm run dev
npm run test:run
npm run build
```

Vite berjalan di `http://localhost:5173`; set `VITE_API_BASE_URL` jika backend memakai alamat lain. Profile `local` memakai `cmd.exe` di Windows dan `sh` di macOS/Linux, hanya untuk pengembangan tepercaya. Perintah ini adalah pintu verifikasi, bukan klaim bahwa semua perubahan di working tree sudah lulus.

## Deployment dan keamanan

Hentikan HTTPS di reverse proxy dan batasi port aplikasi. Shell, otomasi browser, MCP, plugin, import Skill, dan penulisan file harus dibatasi oleh peran, pemilik Agent, workspace, sandbox, dan persetujuan. AgentScope Permission tidak menggantikan RBAC enterprise. Sebelum production, uji path escape, bypass otorisasi/persetujuan, pembatalan proses, pemulihan backup, dan rollback upgrade.

## Dokumentasi dan lisensi

- [Keselarasan AgentScope 2.0](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [Matriks kemampuan dan izin enterprise](docs/ENTERPRISE_CAPABILITY_MATRIX.md)
- [Sumber dan independensi](docs/UPSTREAM.md)
- [Checklist rilis open source](docs/OPEN_SOURCE_CHECKLIST.md)
- [Panduan kontribusi](CONTRIBUTING.md)
- [Kebijakan keamanan](SECURITY.md)

Lisensi kode yang direncanakan adalah [Apache License 2.0](LICENSE). Riwayat Git masih berisi materi pihak ketiga dengan pembatasan, sehingga repository belum otomatis menjadi rilis publik yang patuh. Selesaikan checklist dan verifikasi `NOTICE`, atribusi upstream, serta syarat redistribusi sebelum mempublikasikan kode atau binary.
