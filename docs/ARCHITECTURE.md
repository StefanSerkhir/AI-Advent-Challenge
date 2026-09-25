# Архитектура LLM Workbench

Этот документ — подробная карта проекта для разработки и сопровождения. Краткие обязательные правила для Codex находятся в корневом [AGENTS.md](../AGENTS.md), пользовательский запуск и описание функций — в [README.md](../README.md), точный HTTP-контракт — в [WEB_API.md](WEB_API.md).

## Назначение и границы

LLM Workbench — локальное web-приложение для диалогов с LLM, сравнения способов ответа и демонстрации управления токенами и контекстом. Один JVM-процесс:

- читает локальную конфигурацию и постоянное состояние;
- вызывает OpenAI или DeepSeek;
- управляет операциями, историей, памятью, метриками и стоимостью;
- отдаёт REST/SSE API и статическую production-сборку React.

Frontend не вызывает провайдеров напрямую. В проекте нет базы данных, учётных записей и отдельного Node.js backend. Node.js нужен для разработки и сборки frontend, но не для запуска готового distribution.

```mermaid
flowchart LR
    UI[React UI] -->|REST commands| API[Ktor REST API]
    API --> Controller[WorkbenchController]
    Controller --> Runners[Mode runners / agents]
    Runners --> Client[LlmClient]
    Client --> Providers[OpenAI / DeepSeek]
    Controller --> Stores[Local .env and versioned JSON stores]
    Controller -->|StateFlow snapshots| SSE[Ktor SSE]
    SSE --> UI
```

## Каталоги и ответственность

| Путь | Ответственность |
| --- | --- |
| `src/main/kotlin/llm` | Общий `LlmClient`, сообщения, completion events/options/usage, каталог моделей и фабрика провайдеров |
| `src/main/kotlin/llm/openai` | OpenAI-compatible transport и OpenAI-адаптер |
| `src/main/kotlin/llm/deepseek` | DeepSeek-адаптер |
| `src/main/kotlin/network` | Настройка общего Ktor `HttpClient`, таймаутов и retry |
| `src/main/kotlin/config` | Чтение и атомарное сохранение локального `.env` |
| `src/main/kotlin/tokens` | Оценка токенов, профили окон/цен, подготовка контекста, overflow и агрегаты |
| `src/main/kotlin/agent` | Обычный агент, старые контекстные стратегии, отдельный агент слоёв памяти и JSON stores |
| `src/main/kotlin/app` | Настройки, orchestration режимов, runners, `WorkbenchController` и UI-neutral state |
| `src/main/kotlin/web` | Явные DTO, валидация команд, REST/SSE, локальная защита и static resources |
| `src/main/kotlin/mcp` | Локальный MCP stdio-сервер, lifecycle-aware шлюз `tools/list`/`tools/call` и CLI-проверка |
| `frontend/src/api` | Зеркало wire-контракта и fetch-клиент |
| `frontend/src/state` | SSE-синхронизация, REST-команды и клиентская блокировка действий |
| `frontend/src/components` | Настройки, память, результаты и Markdown presentation |
| `src/test` | Unit/integration тесты и production-like deterministic web fixture |
| `frontend/tests` | Playwright-проверки настоящего Ktor API и React UI без платных запросов |
| `legacy-desktop` | Изолированное прежнее Compose-приложение |

## Запуск приложения

Production entry point — `src/main/kotlin/web/WebMain.kt` (`org.example.web.WebMainKt`). Он:

1. определяет loopback-порт из `WEB_PORT`, по умолчанию `8080`;
2. загружает `.env` через `LocalConfigStore` и нормализует настройки через `AppBootstrap`;
3. создаёт один общий HTTP-клиент;
4. собирает `WorkbenchController` с production stores и фабрикой `LlmClient`;
5. запускает Netty только на `127.0.0.1`;
6. при shutdown отменяет работу контроллера, закрывает HTTP-клиент и сервер.

`./gradlew runWeb` сначала собирает frontend: Gradle-задача `processResources` зависит от `frontendBuild`, а `frontend/dist` копируется в classpath `web`. `./gradlew :run` и `./llm` ведут к тому же web runtime.

Для разработки UI backend запускается с `WEB_DEV_PORT=5173`, а Vite отдельно через `npm --prefix frontend run dev`. Vite проксирует `/api`; произвольный CORS не включён.

`runWebFixture` использует `src/test/kotlin/web/WebFixture.kt`. Его fake clients детерминированы и доступны только в test source set, поэтому не могут случайно попасть в production distribution. MCP scheduler в fixture также настоящий, но получает путь во временном каталоге теста.

Отдельная задача `./gradlew runMcpDemo` использует тот же `LocalMcpGateway`, что и
web runtime. Шлюз создаёт дочерний JVM-процесс `McpDemoServerKt` с тем же runtime
classpath и соединяет его официальными `StdioClientTransport`/`StdioServerTransport`
Kotlin MCP SDK. Сервер объявляет безопасные локальные инструменты `ping`, `echo`,
`tracker_get_issue`, `scheduler_create`, `scheduler_list`, `scheduler_cancel` и
`scheduler_get_summary`; stdout зарезервирован только для JSON-RPC. CLI выполняет
handshake, `tools/list`, реальный `tools/call` для `DEMO-101`, создание, чтение
сводки и отмену расписания, затем закрывает client, transport и process. Demo
использует отдельный временный scheduler store. Mock Tracker не читает `.env`, не
использует сеть и не требует ключей.

`WorkbenchController` активирует `LocalMcpGateway` сразу при создании web runtime,
поэтому восстановленные расписания работают до первого пользовательского запроса.
Шлюз кэширует каталог на время соединения и сериализует protocol operations.
Транспортная ошибка или отмена закрывает соединение и дочерний процесс; фоновый
monitor подключает новый процесс, который восстанавливается из JSON. При штатном
`WorkbenchController.shutdown` monitor, scheduler/gateway и дочерний процесс
закрываются. DeepSeek и остальные response modes не получают tool definitions.
OpenAI transport сериализует обязательный `type: "function"` явно и для tool
definition, и для assistant tool call, не полагаясь на пропускаемые
сериализатором значения по умолчанию.

### Планировщик MCP

`mcp/Scheduler.kt` разделяет модели, `SchedulerStore`, `SchedulerService`,
`ScheduledTaskExecutor` и `TrackerSource`. Production service использует
`java.time.Clock`, `SupervisorJob`, `Mutex`, conflated wake channel и один
последовательный due-loop. Множество `inFlight` дополнительно защищает ручной
`runDue(now)` от параллельного запуска одного расписания. Создание/отмена и каждый
результат сначала проходят atomic store commit и только затем становятся видимыми
MCP-клиенту.

Поддерживаются `once` и `fixed_interval`, задачи `reminder` и
`tracker_snapshot`. Периодическая ошибка сохраняется как результат, но расписание
остаётся активным. После долгого простоя due-loop выполняет один запуск и считает
новый `nextRunAt` от текущего времени; missed intervals не проигрываются серией.
Одноразовая задача после успеха становится `COMPLETED`, после ошибки — `FAILED`.
Локальный executor не вызывает LLM.

Агрегаты хранят общие success/error/snapshot counters, последнее состояние Tracker
и числа изменений `status`/`nextAction`. Подробная история ограничена 100 последними
запусками на расписание, но общие счётчики при отсечении не сбрасываются.

```mermaid
flowchart LR
    Agent[OpenAI Простой агент] -->|tools/call| Gateway[LocalMcpGateway]
    Gateway -->|stdio JSON-RPC| MCP[MCP child process]
    MCP --> Service[SchedulerService]
    Service --> Store[.llm-scheduler-state.json]
    Service --> Tracker[Local TrackerSource]
    Gateway -->|scheduler_list monitor| Controller[WorkbenchController]
    Controller -->|changed StateFlow snapshot| SSE[Ktor SSE]
    SSE --> Panel[Панель Фоновые задачи]
```

Monitor запрашивает snapshot через настоящий `scheduler_list`, но публикует новый
`WorkbenchState` только при структурном изменении. Поэтому heartbeat/ожидание
следующего времени не создают частые SSE-события. Это локальная фоновая работа
только во время жизни JVM, не системный daemon и не облачный сервис.

## Доменная orchestration

### Настройки и режимы

`AppSettings` хранит выбранный провайдер, модель, `ResponseMode`, лимиты ответа, историю, overflow policy, `ContextStrategy` и `recentMessagesLimit`. `LocalConfig` отображает эти значения в `.env`.

Текущие wire-id режимов определены в `ResponseMode.cliValue`:

| Режим UI | Enum / wire-id | Исполнитель |
| --- | --- | --- |
| Сравнение двух ответов | `COMPARE` / `compare` | `PromptRunner`, обе `ResponseVariant` |
| С ограничениями | `CONTROLLED` / `controlled` | `PromptRunner`, constrained prompt/options |
| Простой агент | `UNRESTRICTED` / `unrestricted` | `PromptRunner` или отдельный `AssistantAgent` для `MEMORY_LAYERS` |
| 4 способа рассуждения | `REASONING` / `reasoning` | `ReasoningRunner` |
| Сравнение температуры | `TEMPERATURE` / `temperature` | `TemperatureRunner` |
| Сравнение моделей | `MODEL_COMPARISON` / `models` | `ModelComparisonRunner` |
| Токены и контекст | `TOKENS_CONTEXT` / `tokens` | `TokenContextDemoRunner` |

«Простой агент» намеренно остаётся `UNRESTRICTED`/`unrestricted`. Стратегия памяти не является отдельным response mode.

### Центральный контроллер

`WorkbenchController` — граница между web/API и исполняющей логикой. Он владеет:

- текущим `WorkbenchState` в `MutableStateFlow`;
- API-ключами и выбранными моделями;
- единственной активной worker coroutine;
- `PromptRunner` для обычных вариантов и трёх старых context strategies;
- отдельными `AssistantMemoryManager` и `AssistantAgent`;
- отдельным `AssistantInvariantManager` и версионированной коллекцией обязательных правил;
- отдельным `TaskStateManager` и состоянием задачи Простого агента;
- специализированными runners экспериментов;
- монотонными номерами exchange/revision и streaming snapshots;
- read-only monitor снимков фоновых задач из MCP scheduler.

Все команды и worker transitions синхронизированы на контроллере. Одновременно разрешена одна операция. Настройки и мутации состояния во время неё отклоняются. `WorkbenchState` — серверный источник истины; React получает первоначальный снимок через REST, а последующие полные снимки через SSE.

При `UNRESTRICTED + MEMORY_LAYERS` контроллер направляет запрос прямо в `AssistantAgent`. Любой другой вариант `UNRESTRICTED` идёт через `PromptRunner` → `LlmAgent` → `ContextManager`. Эта развилка — главная граница, предотвращающая смешивание memory layers со старыми хранилищами.

Обе ветки вызывают общий bounded tool-calling executor. Для OpenAI он преобразует
каталог MCP в Chat Completions `tools`, собирает streaming `tool_calls`, проверяет
имя по каталогу, выполняет `tools/call` и добавляет assistant tool-call и tool-result
только во временный контекст текущего выполнения. MCP-результат имеет роль `tool`
и считается недоверенными данными, а не system instruction. После не более чем
трёх вызовов модель должна вернуть финальный текст; именно он продолжает стримиться
в output. Usage суммируется по всем LLM-шагам, а стоимость вычисляется для каждого
шага отдельно и затем складывается, чтобы high-context тариф одного запроса не
применялся ошибочно к агрегату нескольких запросов.

```mermaid
sequenceDiagram
    participant UI as React UI
    participant A as LlmAgent / AssistantAgent
    participant L as OpenAI-compatible LLM
    participant G as LocalMcpGateway
    participant S as MCP child process
    A->>G: tools/list (уже активный gateway)
    G->>S: initialize + tools/list over stdio
    A->>L: messages + discovered tool definitions
    L-->>A: assistant tool_calls
    A->>G: tools/call tracker_get_issue
    G->>S: tools/call over stdio
    S-->>A: structured untrusted result
    A->>L: temporary assistant tool-call + tool result
    L-->>A: streamed final answer + usage
    A-->>UI: final answer + MCP diagnostics in StateDto/SSE
```

При успешном цикле старый `LlmAgent` и `AssistantAgent` фиксируют только исходный
user prompt и финальный assistant answer. Tool messages и сырой результат отдельно
не попадают в `.llm-history.json`, `.llm-context-state.json` или `SHORT_TERM`.
Tool error отображается в diagnostics и передаётся модели для безопасного ответа,
но такой обмен не коммитится. Ошибка модели, неизвестный инструмент, превышение
лимита, отмена и незавершённый поток также оставляют persistent state неизменным.
В ветке `MEMORY_LAYERS` invariant structured output и task-state postflight
применяются к финальному ответу после MCP-цикла.

### Обычный агент и история

`PromptRunner` владеет двумя `LlmAgent`: для `unrestricted` и `controlled`. Compare использует оба, одиночные режимы — соответствующий вариант. `ConversationHistoryStore` сохраняет обычную завершённую историю и token metrics в `.llm-history.json`.

Для unrestricted legacy history из старых форматов намеренно не повышается до состояния новых context strategies. Состояние `SLIDING_WINDOW`, `STICKY_FACTS` и `BRANCHING` хранится отдельно через `ContextStateStore` в `.llm-context-state.json`.

## Контекст Простого агента

### Старые стратегии в `ContextManager`

`ContextSessionState` содержит независимые поля для всех трёх старых стратегий. Их wire/state semantics нельзя незаметно переиспользовать для слоёв памяти.

| Стратегия | Активный контекст | Постоянное состояние |
| --- | --- | --- |
| `SLIDING_WINDOW` | system + последние N обычных сообщений с текущим user message | `slidingMessages` |
| `STICKY_FACTS` | system + извлечённые facts + последние N raw messages | `stickyFacts`, `stickyMessages`, `factUsage` |
| `BRANCHING` | system + immutable checkpoint prefix + suffix активной ветки + текущий user message | `branching.checkpoint`, `branches`, `activeBranchId` |

`STICKY_FACTS` делает отдельный LLM-вызов `FactExtractor`. Его usage является фактической стоимостью и сохраняется сразу, но кандидат facts/raw messages фиксируется только после успешного основного ответа.

`BRANCHING` начинает с `root`. Создание checkpoint фиксирует префикс и создаёт ветки A/B. Только эта стратегия имеет checkpoint/branch API и элементы интерфейса. Проверка должна существовать и в UI, и на backend.

`ContextManager.prepare` и `commit` явно отвергают `MEMORY_LAYERS`: она обслуживается другой подсистемой.

### Слои памяти

`MEMORY_LAYERS` состоит из собственных моделей и сервисов в `AssistantMemoryManager.kt` и отдельного request pipeline в `AssistantAgent.kt`.

`AssistantProfile` — отдельная доменная модель одного активного профиля, а не набор
`LONG_TERM`-записей. Она содержит собственную версию, обращение, сведения о
пользователе, стиль, формат и ограничения. Пустой профиль нейтрален; обновление
возможно только явной командой и проходит нормализацию, ограничения длины,
проверку управляющих символов и проверку известных API-ключей.

| Слой | Наполнение | Очистка | Назначение |
| --- | --- | --- | --- |
| `SHORT_TERM` | Только атомарно сохранённые успешные пары user/assistant | «Новый диалог» или явная очистка | Недавний диалог; ограничивается `recentMessagesLimit` |
| `WORKING` | Явное добавление пользователем | «Завершить задачу» или явная очистка | Цели, ограничения и промежуточные данные текущей задачи |
| `LONG_TERM` | Явное добавление пользователем | Только явная очистка/удаление | Устойчивые предпочтения, решения и знания между задачами и перезапусками |

Все три слоя могут участвовать одновременно. У каждого есть независимый persisted enable flag: выключение меняет следующий запрос, не удаляя записи. Явное добавление в `SHORT_TERM` запрещено; редактирование и удаление доступны, причём удаление одной short-term записи удаляет её полную пару.

Сборка запроса выполняется так:

1. системная инструкция `ASSISTANT_SYSTEM_INSTRUCTIONS`; непустая коллекция
   инвариантов, профиль и активная незавершённая задача добавляются в неё как
   отдельные детерминированные JSON-блоки с экранированными значениями;
2. включённый непустой `LONG_TERM`;
3. включённый непустой `WORKING`;
4. включённый непустой `SHORT_TERM`;
5. текущий prompt пользователя.

Порядок приоритетов зафиксирован в system instructions: безопасность и системные
правила → инварианты → явные требования текущего prompt → task state → профиль →
остальные слои. Поэтому
текущий запрос может временно переопределить стиль/формат, не изменяя профиль.
Profile и memory blocks помечаются как недоверенные пользовательские данные.
Переключатели слоёв не влияют на профиль. `AssistantAgent` затем передаёт сообщения
в общий `ContextPreparer`, который применяет budget и overflow policy. При
`DROP_OLDEST` диагностика short-term корректируется до записей, реально оставшихся
в активном запросе.

Успешный жизненный цикл ответа:

```mermaid
sequenceDiagram
    participant C as WorkbenchController
    participant A as AssistantAgent
    participant M as AssistantMemoryManager
    participant L as LlmClient
    C->>A: respond(prompt, settings)
    A->>M: prepare(enabled layers, short-term limit)
    A->>L: stream(active messages)
    L-->>A: buffered deltas + CompletionFinished + usage
    A->>A: validate invariant receipt, usage and metrics/cost
    alt receipt accepted or no invariants
        A->>M: commitShortTermPair(user, assistant)
        M->>M: atomic persistent save
    else receipt rejected
        A->>A: replace raw output with safe blocked response
    end
    A-->>C: response + actual memory diagnostics
    C-->>C: publish final StateFlow snapshot
```

Пара не считается завершённой до успешного сохранения. Ошибка провайдера, отмена, отсутствие финального streaming event, неверный usage или ошибка persistence не должны оставлять половину пары.

`AssistantMemoryDiagnostics` записывается вместе с конкретным output и содержит
`profileApplied`, `profileVersion`, безопасное число заполненных полей, а для
каждого слоя — `enabled`, `usedCount` и `usedEntryIds`. Полные поля профиля не
дублируются. Это диагностика фактически подготовленного/сокращённого запроса, а не
текущего состояния sidebar после ответа.

### Инварианты ассистента

`AssistantInvariantManager.kt` — отдельная доменная подсистема только для
`UNRESTRICTED + MEMORY_LAYERS`. Она не входит в `AssistantMemoryState`, профиль,
FSM или старый `ContextManager`. `AssistantInvariantState` содержит собственную
монотонную версию и явно созданные записи со стабильным ID, категорией
`ARCHITECTURE`, `TECH_DECISION`, `STACK`, `BUSINESS_RULE` или `OTHER`, текстом и
timestamps. Добавление, редактирование и удаление являются единственными способами
изменить коллекцию; диалог и ответы модели её не пополняют.

Непустой snapshot включается в первое system message как экранированный JSON-блок
`ASSISTANT INVARIANTS` до профиля и task state. Контракт требует до ответа
проверить весь запрос и все предлагаемые действия по всем правилам. Инструкции
«игнорировать», сделать исключение или изменить приоритет не действуют. Значения
правил являются конфигурационными данными и не могут менять системную безопасность,
формат блока или порядок приоритетов. При конфликте модель отказывает только в
несовместимой части, называет ID и категории, объясняет прямую причину и предлагает
совместимую альтернативу. Отдельного классифицирующего LLM-вызова и production
keyword-логики нет.

Для каждого такого вызова `AssistantAgent` обрамляет текущий prompt как
экранированные недоверенные JSON-данные и требует один структурированный ответ:
preflight-поля с версией snapshot, полным упорядоченным списком проверенных ID,
конфликтующими ID и решением `COMPATIBLE`/`CONFLICT`, пользовательское поле
`answer` и вложенный postflight audit готового текста. `OpenAiLlmClient` передаёт
схему нативно через `response_format=json_schema`; совместимый transport без такой
возможности сохраняет prompt-контракт и тот же строгий локальный parser. Пока поток
не завершён, сырой текст не публикуется. Backend сверяет схему, версию и ID и только
затем публикует `answer`. Отсутствующий, повреждённый или несогласованный объект
приводит к fail-closed
ответу: сырой output не раскрывается, пара не фиксируется в `SHORT_TERM`, а
диагностика получает `responseBlocked=true`. Семантическую классификацию по
ключевым словам backend не выполняет; дополнительного LLM-вызова нет.

System message и текущий prompt являются обязательным контекстом для
`ContextPreparer`: `DROP_OLDEST` их не удаляет, а если они сами не помещаются,
существующий preflight локально отклоняет запрос. `AssistantInvariantDiagnostics`
сохраняется с конкретным output и содержит `applied`, версию snapshot, количество,
ID применённых правил и `responseBlocked`. При пустом списке блок отсутствует,
`applied=false`, а обычный streaming pipeline остаётся прежним.

### Машина состояния задачи

`TaskStateManager.kt` — независимая доменная подсистема только для
`UNRESTRICTED + MEMORY_LAYERS`. `AgentTaskState` содержит стабильный ID, собственную
версию, цель, `phase`, текущий шаг, ожидаемое действие, флаг паузы и timestamps.
Отдельные поля фиксируют timestamps утверждения плана и завершения реализации,
а также `validationStatus` (`NOT_RUN`, `FAILED`, `PASSED`) и подробности проверки.
Пауза ортогональна этапу. Единственная доменная таблица `TASK_TRANSITION_RULES`
задаёт линейный граф `PLANNING → EXECUTION → VALIDATION → DONE`; `DONE` терминален.
Переходы выполняют только смысловые команды `approvePlan`,
`completeImplementation` и успешный `recordValidation`. Неуспешный результат
обновляет причину и следующее действие, но оставляет `VALIDATION`. Start, update,
pause, resume и reset также явные; ни одна команда не выводится из текста модели.
Во время паузы запрещены переходы и обновление прогресса.

Активная незавершённая задача включается в первое system message как компактный
JSON-блок `TASK STATE DATA`; рядом backend добавляет доверенную фазовую инструкцию,
запрещающую изображать реализацию в `PLANNING` или завершение до успешной
валидации. Пользовательские goal/progress помечены как недоверенный контекст, а
вычисленные backend phase/evidence/actions не могут быть переопределены prompt.
Пока задача активна, `AssistantAgent` буферизует ответ и выполняет узкую
детерминированную постпроверку явных lifecycle-утверждений. Если текст заявляет о
результате более поздней фазы, backend не публикует сырой ответ, не фиксирует пару
в `SHORT_TERM`, возвращает безопасное объяснение текущей фазы и выставляет
`TaskStateDiagnostics.responseBlocked=true`. Учёт фактически потраченных токенов
сохраняется, FSM не меняется. Отрицания вроде «задача не завершена» не считаются
утверждением о завершении.
При паузе controller/API отклоняет
запуск до создания exchange и LLM-клиента; `AssistantAgent` повторяет защиту до
расчёта метрик. `DONE` не считается активным контекстом. Output diagnostics хранит
только факт применения, ID, версию, этап и признак блокировки — без полных текстов
задачи.

## Постоянное состояние

Все локальные runtime-файлы относятся к текущему рабочему каталогу запуска и исключены из Git.

| Файл | Владелец | Содержимое |
| --- | --- | --- |
| `.env` | `LocalConfigStore` | Провайдер, модель, API-ключи и настройки UI/ответа |
| `.llm-history.json` | `JsonConversationHistoryStore` | Обычные завершённые exchanges и token metrics |
| `.llm-context-state.json` | `JsonContextStateStore` | Состояния `SLIDING_WINDOW`, `STICKY_FACTS`, `BRANCHING` |
| `.llm-assistant-memory.json` | `JsonAssistantMemoryStore` | v2: профиль и три слоя, записи, роли/пары, timestamps и enable flags; v1 читается с явной миграцией в пустой профиль |
| `.llm-assistant-invariants.json` | `JsonAssistantInvariantStore` | v1: версия коллекции и отдельные обязательные правила с ID, категориями и timestamps |
| `.llm-task-state.json` | `JsonTaskStateStore` | v2: задача FSM, transition timestamps и результат валидации; v1 мигрирует по сохранённой фазе |
| `.llm-scheduler-state.json` | `JsonSchedulerStore` в MCP-процессе | v1: расписания, агрегированные counters и до 100 последних результатов каждого расписания |

JSON stores используют UTF-8, номер версии, temporary file и atomic replace с безопасным fallback, если файловая система не поддерживает atomic move. Повреждённый или неподдерживаемый документ не должен частично загружаться: runtime начинает с пустого состояния и публикует предупреждение.
Для инвариантов commit store предшествует изменению in-memory state и публикации
`WorkbenchState`; ошибка записи сохраняет прежнюю коллекцию и обе версии. Тексты
проверяются на известные API-ключи, а новый ключ — на присутствие в инвариантах.

Очистки намеренно разделены:

- `DELETE /api/history` очищает обычную историю и три старые context strategies;
- memory endpoint очищает ровно выбранный слой;
- «Новый диалог» очищает только `SHORT_TERM`;
- «Завершить задачу» очищает только `WORKING`;
- обе команды сохраняют профиль; очистка профиля — явное сохранение пяти пустых полей;
- ни одна из этих очисток не меняет Task State Machine; для неё существует отдельный reset;
- инварианты не меняются ни одной очисткой памяти, истории, task state или карточек;
- очистка карточек результатов не затрагивает историю и память.

## HTTP API и согласованность

`WebServer.kt` содержит маршруты и транспортную защиту; `WorkbenchApi.kt` — API-level orchestration/валидацию; `ApiDtos.kt` — сериализуемый контракт. Frontend обязан зеркалить контракт в `frontend/src/api/types.ts` и `client.ts`.

Ключевые правила:

- каждый state snapshot имеет монотонный `revision`;
- `settingsVersion` растёт при настройках, ключе, мутациях памяти, FSM и инвариантов;
- изменяющая команда, зависящая от настроек, передаёт `expectedSettingsVersion`;
- stale версия получает `409 stale_settings`, после чего клиент обновляет state;
- `POST /api/operations` принимает новый `requestId`; повтор идентичной команды не запускает второй платный вызов;
- одновременно исполняется одна операция, конфликт получает `409 busy`;
- операция принадлежит controller scope, а не HTTP/SSE-соединению, поэтому refresh не отменяет генерацию;
- SSE передаёт полные snapshots, а не патчи; новый подписчик сразу получает актуальное состояние.

Маршруты и error codes перечислены в [WEB_API.md](WEB_API.md). При добавлении поля обновляются Kotlin DTO, mapper, TypeScript type, клиент/состояние, API-тест и browser-тест.

## Frontend

`App.tsx` собирает layout, `useWorkbench` держит последний принятый state и действия,
`Sidebar` показывает только настройки активного режима, `Results` отображает
streaming/final outputs, метрики и диагностику инвариантов, `MemoryLayers`
управляет тремя слоями, а `AssistantInvariants` — отдельной коллекцией правил.

`useWorkbench` принимает snapshot, только если его `revision` не старее текущего. SSE является основным каналом состояния; REST-ответ после команды помогает быстро синхронизироваться. На неопределённой сетевой ошибке start command сохраняется с исходным `requestId`, чтобы проверка отправки не создала повторный платный запрос. `TaskStatePanel` показывает FSM только рядом со слоями памяти, строит доступность по серверному `availableActions`, называет переходы по смыслу, требует текст результата проверки, скрывает недопустимые переходы и оставляет resume доступным во время паузы.

`BackgroundTasks` — read-only панель общего workspace. Создание остаётся
разговорным MCP-сценарием; browser storage и отдельный REST mutation не
используются. Панель отображает `StateDto.backgroundTasks` из REST/SSE.

Карточка output показывает `mcpCalls` отдельным блоком «MCP-инструменты»: имя,
безопасные аргументы, `success`/`error` и безопасный результат. Данные приходят в
том же полном SSE snapshot; отдельного endpoint и клиентского источника истины нет.

Условный UI должен следовать доменной доступности:

- branch controls — только `strategy === "BRANCHING"`;
- memory controls — только `strategy === "MEMORY_LAYERS"` в режиме `unrestricted`;
- task-state controls — только там же; paused task блокирует composer, но не resume;
- invariant controls — только там же; активная операция блокирует их мутации;
- настройки, не используемые текущим режимом, скрываются;
- во время активной операции мутации заблокированы и frontend, и backend.

LLM-текст рендерится как Markdown без raw HTML. KaTeX работает без trusted commands; CSP запрещает внешние изображения и произвольные подключения.

## Токены, overflow и стоимость

`tokens/TokenAccounting.kt` централизует:

- приблизительный `TokenEstimator` для preflight;
- `ModelContextProfiles` с окнами, max output, ценами и источниками;
- `ContextPreparer` с `REJECT` и `DROP_OLDEST`;
- проверку фактического provider usage;
- `TokenCostCalculator` и cumulative totals.

Preflight estimate не подменяет фактический `prompt_tokens`/`completion_tokens`. После финального события источник истины — usage провайдера; если usage отсутствует, actual metrics и стоимость остаются неизвестными. `DROP_OLDEST` удаляет из активного запроса старые полные пары, но не переписывает постоянную историю. `REJECT` завершается локально до сетевого вызова.

При изменении модели или цены обновляйте профиль и его тесты, дату/источник и пользовательскую документацию. Финансовые значения требуют повторной сверки с текущей официальной страницей провайдера.

## Безопасность

Приложение рассчитано на локальное использование, но браузерный endpoint всё равно защищён:

- Netty слушает только `127.0.0.1`;
- Host проверяется по точному loopback allowlist против DNS rebinding;
- Origin, если присутствует, должен быть origin приложения или явно настроенного Vite;
- cross-site fetch запрещён;
- mutation требует JSON и `X-Workbench-Request: 1`;
- ответы получают `no-store`, CSP, `nosniff` и `no-referrer`;
- сериализуемые строки проходят redaction известных ключей;
- исключения API не возвращают request bodies, секреты или сырые provider errors.

API-ключ сохраняется только в локальном `.env`; API возвращает лишь `hasKey`. Новые persistent данные должны проходить проверку, исключающую известный ключ, и не должны сохранять streaming drafts.

## Тестовая архитектура

### JVM

- тесты `llm` проверяют adapter payload/streaming/usage;
- тесты `agent` проверяют transactional history, старые strategies, memory layers и stores;
- `TaskStateManagerTest` проверяет граф переходов, pause/resume, persistence,
  секреты и отсутствие неявных изменений при сбое/отмене stream;
- `AssistantInvariantManagerTest` проверяет CRUD/version, v1 persistence,
  fail-closed load, секреты, порядок контекста, диагностику и конфликтные fake-ответы;
- тесты `app` проверяют runners, controller concurrency, persistence и demos;
- `SchedulerServiceTest` проверяет once/interval, idempotency, строгую валидацию,
  cancel, due/parallel execution, success/error reschedule, restart/overdue,
  отсутствие catch-up storm, versioned atomic JSON, history cap и Tracker aggregation;
- `McpGatewayTest` проверяет настоящий `tools/list`/`tools/call` для всех scheduler tools;
- `WorkbenchApiTest` проверяет маршруты, DTO, конфликты, безопасность и состояния;
- тесты `tokens` фиксируют estimation, budgets, overflow и pricing math.

Основная команда: `./gradlew test`.

### Frontend и браузер

- `npm --prefix frontend run typecheck` — TypeScript contract;
- `npm --prefix frontend run build` — typecheck + production Vite bundle;
- `npm --prefix frontend run test:e2e` — Playwright против настоящего Ktor `runWebFixture`.

Browser tests проверяют режимы, memory layers, streaming, refresh, две вкладки, offline/reconnect, идемпотентный retry, отмену, Markdown и узкий экран. Fixture использует временные persistence paths и фиктивные ключи; внешние платные API не вызываются.

### Минимальная матрица для изменений

| Изменение | Минимально ожидаемые проверки |
| --- | --- |
| Чистая Kotlin-логика | целевой unit test + `./gradlew test` |
| DTO/маршрут/controller | unit/API tests + TypeScript typecheck |
| React/CSS | typecheck + целевой Playwright test |
| Сквозной режим/стратегия | полный Gradle test, typecheck, build и Playwright |
| Persistence schema | round-trip, corrupt/unsupported version, write failure/atomicity, backward compatibility |
| Streaming/cancellation | success, provider error, cancelled stream и отсутствие частичного commit |

## Как расширять систему

### Новый провайдер или модель

1. Добавить/обновить `LlmKind`, `LlmModels` и provider adapter.
2. Проверить mapping options, streaming final event и usage details.
3. Добавить профиль контекста/цены только из подтверждённого источника.
4. Обновить bootstrap/config, DTO catalog, UI и adapter tests.

### Новый response mode

1. Добавить стабильный wire-id в `ResponseMode`.
2. Определить отдельный runner или явную ветку существующего runner.
3. Обновить progress call count, `RequestResult`, output mapping и UI visibility.
4. Добавить backend и browser coverage для success/error/partial results.

Не создавайте новый response mode, если поведение является стратегией контекста существующего «Простого агента».

### Новая context strategy

Сначала определите, является ли она состоянием старого `ContextManager` или действительно отдельной подсистемой. Не переиспользуйте чужие поля persistence ради удобства. Зафиксируйте:

- состав и точный порядок сообщений;
- границу prepare/commit;
- поведение при error/cancel/overflow;
- очистку, переключение и restart semantics;
- diagnostics конкретного ответа;
- wire value и обратную совместимость;
- backend guards и условное отображение UI.

После изменения обновите этот документ, [README.md](../README.md), [WEB_API.md](WEB_API.md) и сквозные тесты.
