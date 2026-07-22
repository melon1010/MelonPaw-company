# 开源发布检查清单

这份清单用于 MelonPaw-Company 公开发布前的核对。当前 `origin` 为 `https://github.com/melon1010/MelonPaw-company.git`；清单未完成前不得把仓库或构建产物视为可合规再分发版本。

## 必须确认

- [ ] 许可证选择已确认。当前仓库使用 Apache-2.0；如改用 MIT/GPL/私有许可，需要同步修改 `LICENSE`、`NOTICE`、`pom.xml` 和 README。
- [ ] 确认 `https://github.com/melon1010/MelonPaw-company` 是最终公开地址，再向 `pom.xml` 添加准确的项目 URL、SCM 和 issue 地址。
- [ ] `NOTICE` 中的版权主体已确认。
- [ ] README 中不存在夸大能力；兼容桩、未实现功能、实验能力要明确说明。
- [ ] 仓库中没有真实 API Key、token、账号密码、备份包、日志或用户数据。
- [ ] 内置第三方素材、脚本、schema、图标、前端资源的许可证允许再分发。
- [ ] 清理 Git 历史中的受限 Anthropic 技能材料。当前工作树已移除原 `builtin-skills/docx`、`pdf`、`pptx`、`xlsx`，但这些文件从初始提交 `33a9bd89f9b0d10f3c739d0d8a4e5f6ce6693e1c` 起存在于历史中；公开发布前必须重写历史或从干净快照创建新仓库，并通知已有克隆失效。
- [ ] 对剩余 `melon-app/src/main/resources/builtin-skills/**` 逐项记录来源、许可证和改动；没有明确再分发授权的内容不得发布。
- [ ] AgentScope Java 版本及 core、harness、provider extensions 的许可证和依赖清单已核对。
- [ ] 已按 [AgentScope 2.0 对齐说明](AGENTSCOPE_2_ALIGNMENT.md) 确认没有重新引入自制文件工具、`materialize_skill`、自制多 Agent 工具、CLI、Coding 或桌面桥接代码。
- [ ] 安全风险已评估，尤其是文件工具、Shell 工具、浏览器自动化、插件/技能导入、备份恢复。
- [ ] 默认配置不应在公网环境下无认证暴露管理接口。

## Java 规范建议

参考标准：

- Google Java Style Guide：命名、导入、格式和结构规则。
- Spring Framework Code Style：Spring 项目常见组织方式和可读性习惯。
- Alibaba Java Coding Guidelines：中文团队常用的工程规约、安全和异常处理建议。

当前仓库更适合采用“增量收敛”策略：

- 新代码严格按 `CONTRIBUTING.md` 和 `.editorconfig` 提交。
- 老代码不要为了格式化产生超大 diff，结合功能改动逐步整理。
- 大规模格式化应单独提交，避免和行为变更混在一起。
- 对安全边界、公共 API、插件 SPI 的改动优先补测试。

## 推荐发布流程

1. 先完成受限技能材料的历史清理，再完成 `SECURITY.md` 与 [AgentScope 2.0 对齐说明](AGENTSCOPE_2_ALIGNMENT.md) 中的安全和回归检查。
2. 确定代码托管平台后开启受保护分支，要求 CI 通过后才能合并。
3. 在选定的托管平台启用安全公告和依赖更新能力。
4. 跑一次依赖许可证扫描和 secret 扫描。
5. 确认上游归属与品牌资源后，发布第一个非 SNAPSHOT 版本。
