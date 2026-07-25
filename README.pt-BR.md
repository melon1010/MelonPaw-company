# MelonPaw-Company

> **Primeiro lançamento mundial · “Claw” empresarial em Java**

MelonPaw-Company é uma plataforma de colaboração e execução de tarefas com AI Agents para implantação privada em uma empresa. É construída com Java 17, Spring Boot WebFlux e AgentScope Java 2.0.0 GA, com console React, TypeScript e Vite. Fornece workspaces, memória, Skills, MCP, execução de ferramentas, automação e canais nacionais. “Primeiro lançamento mundial” é o posicionamento de publicação do projeto, não um ranking ou certificação de terceiros.

“Claw” descreve uma forma de produto comum na comunidade chinesa de Agents. O projeto referencia ideias de Hermes Agent e de outros Agents open source, mas não é a versão Java oficial, fork, dependência, alvo de compatibilidade ou produto autorizado do Hermes Agent.

## Idiomas do README

O arquivo de entrada padrão é o chinês simplificado. Os sete arquivos descrevem o mesmo produto, limites de permissão, implantação e segurança em idiomas diferentes.

| Idioma | README |
| --- | --- |
| 简体中文 (padrão) | [README.md](README.md) |
| English | [README.en.md](README.en.md) |
| 日本語 | [README.ja.md](README.ja.md) |
| Русский | [README.ru.md](README.ru.md) |
| Português (Brasil) | [README.pt-BR.md](README.pt-BR.md) |
| Bahasa Indonesia | [README.id.md](README.id.md) |
| Tiếng Việt | [README.vi.md](README.vi.md) |

## Limites do produto

- Uma implantação atende uma empresa; não é um produto SaaS multi-tenant, de assinatura ou cobrança.
- O cliente entregue é um console Web responsivo com suporte a navegador móvel; não há cliente desktop, CLI ou modo Coding.
- Pode ser executado como JAR ou processo comum. A implantação Kubernetes ainda não é fornecida.
- Identidades, papéis, membros da equipe e mapeamentos de recursos pessoais usam SQLite embutido; PostgreSQL não é necessário.
- Os canais nacionais são DingTalk, Feishu, QQ, WeChat e WeCom. As credenciais do canal pertencem ao usuário que o configurou.
- Modelos locais usam Ollama fornecido pelo ambiente. A aplicação não instala, atualiza nem incorpora Ollama ou llama.cpp.
- `/mission` expõe os Subagents nativos fixos `researcher`, `executor` e `reviewer`; delegação dinâmica, aninhada e entre equipes permanece desativada.

## Recursos

- Autenticação empresarial, RBAC, Agents pessoais e Agents de equipe.
- Workspaces pessoais, Markdown, Skills, MCP, canais, conversas e memória.
- Membros e workspaces de equipe; um Agent de equipe compartilha arquivos, Skills, MCP, conversas, memória e resultados com membros autorizados.
- Conversas SSE, chamadas de ferramentas e eventos `tool/result`.
- Administração de Agents, modelos, ferramentas, MCP, auditoria, backups e sistema conforme a permissão.
- Aprovações baseadas em risco, restrições de caminho, verificação de Skills, mascaramento de segredos e auditoria.
- Central de tarefas para Agent, Goal e Mission com quota, resultado, cancelamento e retry comuns.
- Concorrência LLM, QPM e backoff de 429 por Agent; isso não substitui quotas empresariais de tarefa/token.
- Memória de arquivos do AgentScope e modo de memória desativada. ReMeLight e ADBPG não estão conectados ao runtime Java e não são expostos nas configurações.
- Validação OAuth 2.0 Client Credentials para MCP remoto; Client Secret é criptografado em repouso.

## Suporte de idiomas

O console usa chinês simplificado (`zh`) por padrão. O usuário pode alterar a preferência salva pelo menu de idioma. Os sete idiomas são chinês simplificado, inglês, japonês, russo, português do Brasil, indonésio e vietnamita.

A tradução cobre UI React, componentes do design system, datas e mensagens comuns. Respostas do modelo, saída de ferramentas, resultados MCP, arquivos enviados e texto das Skills não são traduzidos. O idioma do Agent é uma configuração separada para arquivos de persona como `SOUL.md`, `AGENTS.md`, `PROFILE.md`, `MEMORY.md`, `BOOTSTRAP.md` e `HEARTBEAT.md`; os idiomas de cada Skill vêm de seus metadados. As tags são normalizadas como BCP 47 (`zh-CN` para `zh`, `pt-PT` para `pt-BR`).

## Permissões, compartilhamento e isolamento

A autorização é verificada em quatro camadas: RBAC empresarial, proprietário do Agent, limites de arquivos do workspace/Docker e regras de risco do AgentScope Permission. Menus ocultos e `X-Agent-Id` enviado pelo cliente não concedem autorização.

O espaço de equipe usa internamente o scope estável `PROJECT`. “Compartilhado” significa usar um mesmo Agent de equipe, não copiar dados pessoais para a equipe.

| Recurso | Agent pessoal | Agent de equipe |
| --- | --- | --- |
| Workspace, arquivos e resultados | Apenas o proprietário | Membros autorizados do Agent |
| Conversas e histórico | Apenas o proprietário | Conforme papel e `agents:use` |
| Memória de longo prazo | Apenas o Agent do proprietário | Compartilhada pelo Agent e separada da memória pessoal |
| Skills e MCP | Proprietário mantém e usa | Compartilhados; manutenção exige permissão |
| Tarefas e resultados | Apenas o proprietário | Scope `PROJECT`, verificado em leitura, cancelamento e retry |
| Canais e automação pessoais | Apenas o proprietário | Nunca copiados implicitamente |
| Memória de aprovação | `agentId + ownerUserId + fingerprint` | Vinculada ao membro que aprovou |

Os papéis são `OWNER`, `MANAGER`, `MEMBER` e `VIEWER`. Ser membro não concede `agents:use`. `VIEWER` pode ler resultados autorizados, mas não inicia nem continua conversas. Mesmo `SYSTEM_ADMIN` não lê normalmente dados pessoais; é necessário `data:breakglass` auditado.

A política padrão `RISK_BASED` permite leituras comuns, pergunta para escrita Shell, exclusão, instalação, mudança de permissão, upload, deploy e MCP de alto risco, e nega credenciais, escape do workspace e ferramentas explicitamente negadas. Somente aprovação explícita pode ser lembrada. Recusa, cancelamento, timeout e recusa permanente nunca são lembrados nem transferidos entre membros.

Os códigos de permissão empresarial são explícitos: `agents:use` executa Agents permitidos; `workspace:personal`, `skills:personal`, `mcp:personal`, `channels:personal` e `automations:personal` administram recursos próprios; `agents:manage` administra Agents não pessoais/de equipe; `approvals:decide` decide aprovações autorizadas; `audit:read` lê auditoria; `system:manage` administra a infraestrutura global; `data:breakglass` permite acesso emergencial auditado. Nenhum código ignora propriedade, membro da equipe ou limite do workspace do Agent.

No modo Docker, ferramentas de arquivo/comando e o processo MCP stdio veem apenas o subdiretório do Agent autorizado. A raiz do workspace, o Agent pessoal de outro usuário e o Agent de outra equipe não são montados. O sandbox limita processo, rede e filesystem, mas RBAC empresarial e AgentScope Permission continuam sendo verificados antes da execução.

## Pré-visualização de arquivos

O preview dividido suporta imagens (`avif`, `bmp`, `gif`, `jpeg`, `jpg`, `png`, `svg`, `webp`), vídeo (`m4v`, `mov`, `mp4`, `ogg`, `webm`), áudio (`aac`, `flac`, `m4a`, `mp3`, `oga`, `wav`), PDF, DOCX, XLSX e formatos comuns de texto/código. Texto é limitado a 1 MB e DOCX/XLSX a 20 MB; formatação Office complexa não tem reprodução garantida.

## Alinhamento com AgentScope 2.0

O projeto prioriza capacidades oficiais: provider extensions/`ModelRegistry`, `RuntimeContext`, `AgentStateStore`, Harness Workspace, ferramentas oficiais de arquivo/execute, `DockerFilesystemSpec`, `IsolationScope.USER`, Skills dinâmicas, `SkillManageTool`, `PermissionContextState`, `PermissionEngine`, `RequireUserConfirmEvent` e fábricas fixas de Subagents nativos. Workers usam ferramentas restritas e contexto independente; delegação dinâmica, aninhada e entre equipes é desativada. Veja [docs/AGENTSCOPE_2_ALIGNMENT.md](docs/AGENTSCOPE_2_ALIGNMENT.md).

## Estrutura do projeto

```text
melon-plugin-api/   SPI de plugins de terceiros
melon-core/         runtime Agent, configuração, modelos, segurança e middleware
melon-tools/        pequenas extensões, como ferramentas de tarefas agendadas
melon-channels/     adaptadores de canais, filas e controle de acesso
melon-app/          Spring Boot Web, autenticação, serviços empresariais e APIs
console/            console Web React
docs/               arquitetura, origem e publicação
```

O Maven agrega os cinco módulos Java e inclui `console/dist` no JAR. Não há módulo CLI, Coding ou desktop separado.

## Requisitos

JDK 17+, Maven 3.8+, Node.js 20.19+ (22 LTS recomendado) e Docker para ferramentas de arquivos/comandos em produção. Em desenvolvimento confiável, o perfil `local` executa no host.

## Build e execução

```bash
cd console && npm ci && npm run build
cd .. && mvn clean package
docker build -t melonpaw-company-sandbox:latest docker/sandbox
MELON_EXECUTION_SANDBOX=docker MELON_SANDBOX_IMAGE=melonpaw-company-sandbox:latest \
  java -jar melon-app/target/melonpaw-company-web.jar
```

A porta HTTP padrão é `8088`. Configure `MELON_WORKSPACE_ROOT` para o workspace da aplicação e, quando o Java estiver em container, `MELON_SANDBOX_HOST_WORKSPACE_ROOT` para o caminho no host. Apenas o subdiretório do Agent autorizado é montado; a raiz inteira nunca é montada. O MCP stdio segue Docker por padrão e pode ser controlado por `MELON_MCP_STDIO_MODE=local|docker|disabled`. `MELON_SANDBOX_RUNTIME=runsc` aplica gVisor ao Agent Shell e ao MCP stdio; runtime indisponível falha sem fallback.

Projetos MCP stdio locais devem estar em `MELON_MCP_IMPORT_ROOTS`. Eles são validados e copiados por hash para `.melon/mcp-sources` do Agent proprietário antes de usar caminhos `/workspace/...`; o diretório original nunca é montado no container MCP. Escapes por symlink, entradas entre roots e importações grandes demais são rejeitados. Dependências Node/Python são preparadas e armazenadas no sandbox. Essa variável pertence ao ambiente de inicialização do processo Java e exige reinício após alterações; a propriedade Spring equivalente é `melon.execution.mcp.import.roots`. Se o Java rodar em Docker, monte os roots confiáveis como read-only no container Java; o MCP continua recebendo somente o workspace do Agent. Quando o caminho salvo no console for diferente do caminho read-only visível ao Java, defina `MELON_MCP_PATH_MAPPINGS` como um mapa JSON de prefixos, por exemplo `{ "/home/mineru/Github": "/mcp-imports" }`. O prefixo mais longo vence, o JSON MCP persistido não é alterado e todo destino mapeado ainda deve existir dentro de `MELON_MCP_IMPORT_ROOTS`.

Quando ainda não há usuários, o primeiro administrador pode ser criado diretamente sem bootstrap token. Se o endpoint de inicialização estiver exposto publicamente, defina `MELON_BOOTSTRAP_TOKEN` antes de iniciar ou use `MELON_GENERATE_BOOTSTRAP_TOKEN=true` para gerar `${MELON_HOME}/bootstrap.token`. O campo de token aparece somente quando uma dessas proteções está ativa. Após criar o primeiro administrador, o arquivo gerado é removido e o bootstrap é encerrado permanentemente. O SQLite fica em `${MELON_HOME}/enterprise.db`; backups incluem DB, `config.yaml` e `workspaces`, mas não devem expor `master.key` ou `bootstrap.token`.

## Modelos e segredos

Use o formato `<provider>:<model>`, por exemplo `dashscope:qwen-plus` ou `deepseek:deepseek-chat`. Configure pelo console ou pelas variáveis `DASHSCOPE_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` e `DEEPSEEK_API_KEY`. Ollama deve ser instalado e iniciado pelo ambiente; o console apenas lê `/api/tags`, faz pull/delete e salva `ollama:<model>`.

OAuth de provider não é suportado; use API keys. MCP remoto suporta apenas OAuth 2.0 Client Credentials, não Authorization Code, PKCE ou Dynamic Client Registration. Skills externas aceitam apenas ZIP HTTPS com `SKILL.md`; caminhos locais arbitrários e páginas de marketplace são rejeitados. Segredos de modelos, canais e MCP usam AES-256-GCM; `MELON_MASTER_KEY` pode ser injetada, caso contrário `${MELON_HOME}/master.key` é gerada.

## Desenvolvimento e verificação

```bash
mvn -pl melon-app spring-boot:run -Dspring-boot.run.profiles=local
cd console && npm ci && npm run dev
npm run test:run
npm run build
```

O Vite usa `http://localhost:5173`; defina `VITE_API_BASE_URL` se o backend estiver em outro endereço. `local` usa `cmd.exe` no Windows e `sh` no macOS/Linux e é somente para desenvolvimento confiável. Os comandos são entradas de verificação, não uma afirmação de que toda alteração atual já passou.

## Implantação e segurança

Termine HTTPS em um reverse proxy e restrinja a porta da aplicação. Shell, automação de navegador, MCP, plugins, importação de Skills e escrita de arquivos devem ser limitados por papel, proprietário do Agent, workspace, sandbox e aprovação. AgentScope Permission não substitui RBAC empresarial. Antes da produção, verifique escape de caminho, bypass de autorização/aprovação, cancelamento de processos, restauração de backup e rollback.

## Documentação e licença

- [Alinhamento AgentScope 2.0](docs/AGENTSCOPE_2_ALIGNMENT.md)
- [Matriz de capacidades e permissões](docs/ENTERPRISE_CAPABILITY_MATRIX.md)
- [Origem e independência](docs/UPSTREAM.md)
- [Checklist de publicação open source](docs/OPEN_SOURCE_CHECKLIST.md)
- [Guia de contribuição](CONTRIBUTING.md)
- [Política de segurança](SECURITY.md)

A licença pretendida é [Apache License 2.0](LICENSE). O histórico Git ainda contém materiais de terceiros com restrições, portanto o repositório não é automaticamente uma publicação pública compatível. Conclua o checklist e verifique `NOTICE`, atribuições e termos de redistribuição antes de publicar código ou binários.
