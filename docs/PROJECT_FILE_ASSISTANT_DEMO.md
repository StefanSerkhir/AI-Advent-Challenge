# Ассистент файлов проекта

## Назначение

Файловый ассистент работает внутри существующего режима **OpenAI → Простой
агент** (`ResponseMode.UNRESTRICTED`, wire-id `unrestricted`). Пользователь задаёт
цель уровня проекта, а модель сама находит и читает связанные файлы. Новый mode,
REST endpoint, DTO или persistent store не добавляются: каталог приходит из
настоящего MCP `tools/list`, вызовы маршрутизируются discovered owner server
`workspace`, а UI использует существующий `outputs[].mcpCalls`.

Ollama, DeepSeek, `MODEL_COMPARISON`, остальные modes и
`UNRESTRICTED + MEMORY_LAYERS + assistantRagEnabled=true` файловые tools не
получают. Общий executor по-прежнему разрешает максимум три `tools/call`.

## Каталог tools

### `project_search`

Аргумент: обязательный непустой literal `query` до 200 символов. Инструмент
сканирует только разрешённые UTF-8 файлы, сортирует relative POSIX paths и
возвращает bounded `matches[]` с `path`, `line`, `snippet`. Поля
`candidateFiles`, `scannedFiles`, `matchedFiles`, `totalMatches`,
`returnedMatches` и `truncated` не позволяют выдать усечённый результат за «все
использования».

### `project_read_files`

Аргумент `paths` содержит 1–5 уникальных relative paths. Один вызов читает сразу
2–3 связанных файла, поэтому обычный сценарий укладывается в общий MCP budget.
Каждый результат содержит `path`, полный bounded `content`, `byteCount` и
SHA-256 текущей версии; дополнительно возвращается общий byte count. Порядок
стабилен по path.

### `project_write_file`

Аргументы: `path`, полный replacement `content`, `mode=preview|apply`,
`createOnly` и необязательный `expectedSha256`.

- существующий файл требует `createOnly=false` и точный hash из
  `project_read_files`;
- новый файл требует `createOnly=true`, отсутствующий `expectedSha256` и уже
  существующий безопасный parent directory;
- `preview` проходит полную валидацию и возвращает diff без записи;
- `apply` повторно проверяет current hash/отсутствие цели, пишет temporary sibling
  и делает atomic move с fallback;
- одинаковый content возвращает `noOp=true` без записи;
- stale hash или неожиданно существующая create-only цель отклоняются.

Результат содержит relative path, mode, `applied`, `created`, `wouldCreate`,
`noOp`, previous/new SHA-256, byte count, bounded unified `diff` и
`diffTruncated`.

## File policy

Единственный root задаётся родительским runtime через существующий
`LLM_MCP_REPOSITORY_ROOT`. Клиент не может выбрать другой root. Разрешены только
обычные UTF-8 text files с allowlisted расширениями исходников/документации.

Отклоняются:

- absolute paths, backslash paths, пустые/`.`/`..` компоненты и выход после
  normalize/real-path проверки;
- symlink-файл и любой symlink-компонент;
- `.git`, `.env*`, `.idea`, `.gradle`, `build`, `dist`, `node_modules`, `video`,
  `.llm-*`, `.llm-mcp-output`;
- имена, похожие на secrets, credentials, tokens и private keys;
- invalid UTF-8, NUL/binary content, oversized query/path/file/content/result;
- запись в `AGENTS.md`, `.github`, Gradle и package/lock configuration.

Ошибки не содержат absolute root, environment или содержимое запрещённого файла.

## Request-local данные и diagnostics

File content, snippets, replacement content и полный bounded diff считаются
недоверенными данными. `executeWithMcpTools` передаёт полный результат только
модели в request-local `tool` message с маркером `UNTRUSTED MCP TOOL DATA`.
Промежуточные assistant/tool messages не входят в history, SHORT_TERM, логи или
persistent state.

Отдельная diagnostic projection публикуется через существующие StateDto/SSE/UI:

- search: query, counts, truncation, path/line;
- read: paths, byte counts, SHA-256;
- write: path, mode, content byte count/hash, expected/current hashes,
  applied/status/no-op/truncation.

Она не содержит `snippet`, file/write `content` или поле `diff` и никогда не
подменяет полный tool result для модели.

## Воспроизводимые сценарии

`runWebFixture` создаёт во временном Git repository три связанных файла:

- `src/main/kotlin/fixture/FixtureProjectApi.kt` — Kotlin GET route;
- `frontend/src/api/fixtureProjectApi.ts` — TypeScript GET client;
- `docs/fixture-project-api.md` — намеренно устаревшее описание POST.

### 1. Анализ без записи

> Найди все места использования API FixtureProjectApi в проекте, прочитай связанные файлы и объясни путь вызова от Kotlin до TypeScript. Ничего не изменяй.

Ожидается `project_search → project_read_files`, три прочитанных path, grounded
путь Kotlin → TypeScript, сообщение «Ничего не изменено» и отсутствие write-call.

### 2. Обновление документации

> Проверь, соответствует ли документация FixtureProjectApi фактической реализации. Если она устарела, обнови её и покажи diff.

Ожидается `project_search → project_read_files → project_write_file`, hash из
прочитанной Markdown-версии, `mode=apply`, реальная замена POST на GET во
временном repository и ответ с relative changed path и unified diff.

## Проверки и видео

Автоматические проверки используют fake fixture LLM, настоящий
`LocalMcpGateway`, отдельный stdio workspace process и настоящий
`tools/list`/`tools/call`; OpenAI/Ollama не вызываются:

```bash
./gradlew :test --tests org.example.mcp.RepositoryFileWorkspaceTest \
  --tests org.example.mcp.McpGatewayTest \
  --tests org.example.agent.LlmAgentTest
npm --prefix frontend run test:e2e -- --grep "project file assistant"
```

Полный релевантный набор:

```bash
./gradlew test
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

Запись:

```bash
npm --prefix frontend run record:project-file-assistant-demo
```

Скрипт сначала выполняет
`lsof -nP -iTCP@127.0.0.1:18080 -sTCP:LISTEN`, не останавливает найденный процесс,
сам запускает `runWebFixture`, показывает выбор OpenAI/Простого агента и оба
сценария, проверяет tool sequence/result до сохранения ролика и завершает fixture.
Файл `video/project-file-assistant-demo.webm` имеет 1440×900. Существующий файл
защищён; осознанная перезапись требует:

```bash
PROJECT_FILE_ASSISTANT_DEMO_OVERWRITE=1 \
  npm --prefix frontend run record:project-file-assistant-demo
```

После записи Chromium читает metadata и проверяет ненулевые size/duration,
разрешение 1440×900 и наличие video stream. Это fixture/fake demo, не проверка
реального OpenAI, платного cloud API или real-local Ollama inference.
