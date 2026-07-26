# MelonPaw-Company

> **全世界初公開 · Java エンタープライズ「ロブスター」**

MelonPaw-Company は、1 企業向けにプライベート導入できる AI Agent 協働・タスク実行プラットフォームです。Java 17、Spring Boot WebFlux、AgentScope Java 2.0.0 GA、React/TypeScript/Vite で構成され、ワークスペース、メモリ、Skills、MCP、ツール実行、自動化、国内チャネルを提供します。「全世界初公開」はプロジェクトの公開上の位置付けであり、第三者の順位や認証を意味しません。

「ロブスター」は中国の Agent コミュニティで使われる製品形態の呼称です。Hermes Agent などを設計上の参考にしていますが、公式 Java 移植版、コードフォーク、依存関係、互換実装、公式認定製品ではありません。

## README 言語

既定の入口は簡体中文です。7 ファイルは同じ製品、権限、導入、セキュリティ境界をそれぞれの言語で記載しています。

| 言語 | README |
| --- | --- |
| 简体中文（既定） | [README.md](README.md) |
| English | [README.en.md](README.en.md) |
| 日本語 | [README.ja.md](README.ja.md) |
| Русский | [README.ru.md](README.ru.md) |
| Português (Brasil) | [README.pt-BR.md](README.pt-BR.md) |
| Bahasa Indonesia | [README.id.md](README.id.md) |
| Tiếng Việt | [README.vi.md](README.vi.md) |

## 製品の範囲

- 1 つの導入環境は 1 企業を提供します。SaaS のマルチテナント、購読、課金製品ではありません。
- 提供するクライアントはレスポンシブ Web コンソールです。デスクトップ、CLI、Coding モードはありません。
- JAR または通常プロセスで実行できます。Kubernetes 導入は現在提供していません。
- 企業 ID、ロール、チーム関係、個人リソースの対応は組み込み SQLite に保存し、PostgreSQL は不要です。
- 対応する国内チャネルは DingTalk、Feishu、QQ、WeChat、WeCom です。チャネル資格情報は設定したユーザーに属します。
- ローカルモデルは導入環境が提供する Ollama を使用します。アプリは Ollama や llama.cpp をインストール、更新、内蔵しません。
- `/mission` の固定 Native Subagent は `researcher`、`executor`、`reviewer` です。動的、入れ子、チーム間委任は無効です。

## 機能

- 企業認証、RBAC、個人 Agent、チーム Agent。
- 個人ワークスペース、Markdown、Skills、MCP、チャネル、会話、メモリ。
- チームメンバーとチームワークスペース。チーム Agent のファイル、Skills、MCP、会話、メモリ、結果を許可されたメンバーで共有します。
- SSE ストリーミング会話、ツール呼び出し、`tool/result` イベント。
- Agent、モデル、ツール、MCP、監査、バックアップ、システム管理を権限に従って提供します。
- リスクベースの承認、パス制約、Skill スキャン、秘密情報のマスク、監査ログ。
- Agent、Goal、Mission のタスクセンターと共通のクォータ、結果、取消、再試行。
- Agent 単位の LLM 同時実行数、QPM、429 バックオフ。企業のタスク/トークンクォータの代替ではありません。
- AgentScope ファイルメモリとメモリ無効モード。ReMeLight と ADBPG は Java ランタイムに接続されず、設定画面にも公開しません。
- リモート MCP の OAuth 2.0 Client Credentials 検証。Client Secret は暗号化して保存し、平文では返しません。

## 言語サポート

コンソールの既定言語は簡体中文（`zh`）です。言語メニューからユーザーごとの設定を変更できます。提供する 7 言語は簡体中文、英語、日本語、ロシア語、ブラジルポルトガル語、インドネシア語、ベトナム語です。

コンソール翻訳は React UI、デザインシステム、日付、共通メッセージを対象とします。モデル応答、ツール出力、MCP 結果、アップロードファイル、Skill 本文は自動翻訳しません。Agent 言語は `SOUL.md`、`AGENTS.md`、`PROFILE.md`、`MEMORY.md`、`BOOTSTRAP.md`、`HEARTBEAT.md` などのペルソナファイルを選ぶ別設定です。Skill の言語は各 Skill のメタデータに従います。言語タグは BCP 47 として正規化します（`zh-CN` は `zh`、`pt-PT` は `pt-BR`）。

## 権限、共有、分離

認可は企業 RBAC、Agent 所有権、ワークスペース/Docker のファイル境界、AgentScope Permission のリスク規則の 4 層で検査します。画面のメニューやクライアント指定の `X-Agent-Id` は認可になりません。

チーム空間は内部で安定した `PROJECT` scope を使用します。「共有」とは同じチーム Agent のリソースを使うことであり、個人データをチームへコピーすることではありません。

| リソース | 個人 Agent | チーム Agent |
| --- | --- | --- |
| ワークスペース、ファイル、生成結果 | 所有者のみ | 許可されたメンバーで共有 |
| 会話と履歴 | 所有者のみ | チームロールと `agents:use` に従う |
| 長期メモリ | 所有者の Agent のみ | チーム Agent で共有、個人メモリとは分離 |
| Skills、MCP | 所有者が管理・利用 | チーム Agent で共有、管理権限が必要 |
| タスクと結果 | 所有者のみ | `PROJECT` scope に保存し、読取・取消・再試行ごとに検査 |
| 個人チャネル、自動化 | 所有者のみ | 暗黙にコピーしない |
| 承認の記憶 | `agentId + ownerUserId + 操作指紋` に拘束 | 承認したメンバーに拘束 |

チームロールは `OWNER`、`MANAGER`、`MEMBER`、`VIEWER` です。チームメンバーであることは企業の `agents:use` を付与しません。`VIEWER` は許可された結果を読めますが会話を開始・継続できません。`SYSTEM_ADMIN` も個人 Agent を通常閲覧できず、監査対象の `data:breakglass` が必要です。

既定の `RISK_BASED` は副作用を区別します。通常の読取は `ALLOW`、Shell 書込み・削除・インストール・権限変更・アップロード・デプロイ・高リスク MCP は `ASK`、資格情報の読取・ワークスペース脱出・明示拒否は `DENY` です。明示的な許可だけを記憶し、拒否、取消、タイムアウト、永久拒否は記憶しません。あるメンバーの記憶済み許可は別メンバーへ移りません。

企業の権限コードは明示的です。`agents:use` は許可された Agent の実行、`workspace:personal`、`skills:personal`、`mcp:personal`、`channels:personal`、`automations:personal` は本人のリソース、`agents:manage` は非個人/チーム Agent、`approvals:decide` は許可された承認、`audit:read` は監査の読取、`system:manage` は全体インフラ、`data:breakglass` は監査付き緊急アクセスを表します。いずれも所有権、チームメンバーシップ、Agent ワークスペース境界を迂回しません。

Docker モードではファイル/コマンドツールと stdio MCP は許可された Agent のサブディレクトリだけを見ます。ワークスペースルート、他ユーザーの個人 Agent、他チームの Agent はマウントしません。プロセス、ネットワーク、ファイルシステムは sandbox で制限しますが、起動前に企業 RBAC と AgentScope Permission を検査します。

## ファイルプレビュー

分割プレビューは画像（`avif`、`bmp`、`gif`、`jpeg`、`jpg`、`png`、`svg`、`webp`）、動画（`m4v`、`mov`、`mp4`、`ogg`、`webm`）、音声（`aac`、`flac`、`m4a`、`mp3`、`oga`、`wav`）、PDF、DOCX、XLSX、一般的なテキスト/コード形式を表示します。テキストは 1 MB、DOCX/XLSX は 20 MB までです。複雑な Office 形式の完全な再現は保証しません。

## AgentScope 2.0 対応

公式能力として provider extension/`ModelRegistry`、`RuntimeContext`、`AgentStateStore`、Harness Workspace、公式ファイル/execute ツール、`DockerFilesystemSpec`、`IsolationScope.USER`、動的 Skill、`SkillManageTool`、`PermissionContextState`、`PermissionEngine`、`RequireUserConfirmEvent`、固定 Native Subagent Factory を優先します。Worker は制限されたツールと独立コンテキストを使用し、動的・入れ子・チーム間委任を無効にします。詳細は [docs/AGENTSCOPE_2_ALIGNMENT.md](docs/AGENTSCOPE_2_ALIGNMENT.md) を参照してください。

## プロジェクト構成

```text
melon-plugin-api/   サードパーティプラグイン SPI
melon-core/         Agent ランタイム、設定、モデル、セキュリティ、ミドルウェア
melon-tools/        定時タスクなどの小規模拡張ツール
melon-channels/     チャネルアダプター、キュー、アクセス制御
melon-app/          Spring Boot Web、認証、企業サービス、API
console/            React Web コンソール
docs/               アーキテクチャ、上流、公開文書
```

Maven は 5 Java モジュールを集約し、`console/dist` を JAR に含めます。独立 CLI、Coding、デスクトップモジュールはありません。

## 必要環境

JDK 17 以上、Maven 3.8 以上、Node.js 20.19 以上（22 LTS 推奨）、本番のファイル/コマンドツール用 Docker。信頼できる開発では `local` profile でホスト実行できます。

## ビルドと起動

```bash
cd console && npm ci && npm run build
cd .. && mvn clean package
docker build -t melonpaw-company-sandbox:latest docker/sandbox
MELON_EXECUTION_SANDBOX=docker MELON_SANDBOX_IMAGE=melonpaw-company-sandbox:latest \
  java -jar melon-app/target/melonpaw-company-web.jar
```

既定の HTTP ポートは `8088` です。本番実行では Agent のファイル／コマンドツールと stdio MCP を Docker で分離し、現在許可された Agent workspace だけをマウントします。実行 profile、workspace のパス投影、信頼済みローカル MCP の取り込み、gVisor、Compose の設定は [デプロイ文書](docs/DEPLOYMENT.md) を参照してください。

ユーザーが存在しない場合、初回管理者は bootstrap token なしで直接作成できます。初期化エンドポイントを外部公開する場合は、起動前に `MELON_BOOTSTRAP_TOKEN` を設定するか、`MELON_GENERATE_BOOTSTRAP_TOKEN=true` を指定して `${MELON_HOME}/bootstrap.token` にトークンを生成してください。いずれかの保護を有効にした場合のみ、画面にトークン入力欄が表示されます。最初の管理者作成後、生成されたファイルは削除され、bootstrap は永久に閉じます。SQLite は `${MELON_HOME}/enterprise.db` にあり、バックアップには DB、`config.yaml`、`workspaces` を含めますが `master.key` と `bootstrap.token` は公開しません。

## モデルと秘密情報

モデル ID は `<provider>:<model>` 形式（例 `dashscope:qwen-plus`、`deepseek:deepseek-chat`）です。コンソールまたは `DASHSCOPE_API_KEY`、`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`GEMINI_API_KEY`、`DEEPSEEK_API_KEY` で設定できます。Ollama は導入環境がインストール・起動し、コンソールは `/api/tags` の検出、pull/delete、`ollama:<model>` の保存だけを行います。

Provider OAuth は未対応で、API Key を使用します。リモート MCP は OAuth 2.0 Client Credentials のみで、Authorization Code、PKCE、Dynamic Client Registration は未実装です。外部 Skill は `SKILL.md` を含む HTTPS ZIP のみ受け付け、任意のローカルパスとマーケット詳細ページは拒否します。モデル、チャネル、MCP の資格情報は AES-256-GCM で保存し、`MELON_MASTER_KEY` を注入できます。未設定時は `${MELON_HOME}/master.key` を生成します。

## 開発と検証

```bash
mvn -pl melon-app spring-boot:run -Dspring-boot.run.profiles=local
cd console && npm ci && npm run dev
npm run test:run
npm run build
```

Vite は `http://localhost:5173` で起動します。バックエンドが別の場合は `VITE_API_BASE_URL` を設定します。`local` は Windows の `cmd.exe`、macOS/Linux の `sh` を使うホスト実行であり、信頼できる開発専用です。上記は検証入口であり、作業ツリーの全変更が常に合格済みという意味ではありません。

## 導入とセキュリティ

HTTPS を終端し、アプリポートを制限してください。Shell、ブラウザ自動化、MCP、プラグイン、Skill インポート、ファイル書込みは企業ロール、Agent 所有権、ワークスペース、サンドボックス、承認で制限します。AgentScope Permission は企業 RBAC の代替ではありません。本番前にパス脱出、越権、承認バイパス、プロセス取消、バックアップ復元、アップグレードロールバックを検証してください。

## 文書とライセンス

- [AgentScope 2.0 対応](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [企業能力・権限マトリクス](docs/ENTERPRISE_CAPABILITY_MATRIX.md)
- [上流と独立性](docs/UPSTREAM.md)
- [オープンソース公開チェックリスト](docs/OPEN_SOURCE_CHECKLIST.md)
- [Contribution](CONTRIBUTING.md)
- [セキュリティポリシー](SECURITY.md)

コードの予定ライセンスは [Apache License 2.0](LICENSE) です。Git 履歴には制限付き第三者素材が残っているため、現時点で自動的に適法な公開物とはみなせません。公開前にチェックリストを完了し、`NOTICE`、上流帰属、第三者再配布条件を確認してください。
