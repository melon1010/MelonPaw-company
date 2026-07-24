# 上游来源与独立性说明

MelonPaw-Company 是独立维护的企业私有部署项目。当前代码与前端交互基础包含从 QwenPaw 演进而来的内容：

- 上游项目：QwenPaw
- 上游地址：https://github.com/agentscope-ai/QwenPaw
- 上游许可证：Apache License 2.0

本项目继续使用 Apache License 2.0，并保留上游许可证与归属信息。MelonPaw-Company 的名称、企业功能、Java 后端实现及后续维护由本项目贡献者独立负责；本项目不是 QwenPaw 或 AgentScope 的官方发行版，也不主张其商标或官方身份。

README 中“Java 企业级龙虾”和“参考 Hermes Agent 等开源 Agent”的表述是产品形态与设计理念说明，不表示 Hermes Agent 是本仓库的代码上游、运行依赖或兼容目标。本项目不是 Hermes Agent 的官方 Java 版本，也不得使用这些表述暗示官方授权或技术认证。“全网首发”属于项目发布定位，不是第三方排名结论。

当前仓库没有保存最初导入时对应的 QwenPaw tag/commit，不能据现有 Git 历史可靠推断，禁止编造版本号。可确认的本地导入基线是 MelonPaw-Company 初始提交 `33a9bd89f9b0d10f3c739d0d8a4e5f6ce6693e1c`。公开发布前，维护者必须从原始导入记录确定上游 commit；无法确定时，应以文件级来源清单和干净重建仓库完成溯源。

主要演进目录包括 `console/`（Web 交互基础及企业页面重构）、`melon-core/`（Java Agent 运行时）、`melon-app/`（认证、企业数据、任务和 HTTP API）、`melon-channels/`、`melon-tools/` 与 `melon-plugin-api/`。任何公开源码仓库或二进制发布都必须同时携带 `LICENSE`、`NOTICE` 和适用的第三方许可证。
