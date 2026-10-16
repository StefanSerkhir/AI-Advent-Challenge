# AI-сводка разработки

## Решаемая проблема

Перед стендапом или отчётом разработчику приходится вручную сопоставлять недавние
коммиты, затронутые файлы и незакоммиченные изменения. Команда `/digest` собирает
этот ограниченный снимок из текущего Git-репозитория и формирует русскоязычную
сводку с проверяемыми ссылками на источники.

Команда доступна только в существующем режиме **«Простой агент»**
(`ResponseMode.UNRESTRICTED`, wire-id `unrestricted`):

- `/digest` — последние 24 часа;
- `/digest <hours>` — целое число от 1 до 168;
- `/digest help` — локальная справка без MCP и LLM;
- некорректный аргумент — локальный usage без MCP и LLM.

AI-выполнение доступно только с OpenAI, потому что локальные MCP tools по
продуктовому контракту экспонируются только этой ветке. DeepSeek и Ollama получают
локальное объяснение и не запускают Git или модель. Отсутствующий OpenAI API-ключ
блокирует `/digest` и `/digest <hours>`, но не `/digest help` и локальный usage.

## Детерминированные Git-данные

`ProjectDigestRunner` находит `workspace / git_activity_report` только в
фактическом `tools/list` и выполняет ровно один backend-controlled `tools/call`.
MCP server читает только заранее настроенный `LLM_MCP_REPOSITORY_ROOT`; путь от
пользователя не принимается. Clock также задаётся сервером: production использует
UTC system clock, а fixture передаёт фиксированный instant дочернему stdio-процессу.

Versioned report schema v1 содержит:

- `generatedAt`, `since`, branch/detached state и короткий HEAD;
- до 20 недавних коммитов по умолчанию, максимум 50;
- до 50 записей файлов по умолчанию, максимум 100;
- раздельный `scope=committed|working_tree`, статус и bounded numstat;
- staged/unstaged/untracked counts и признак dirty working tree;
- стабильные request-local IDs `C1...` и `F1...`;
- явный `truncated`, если применён лимит или ограничен вывод Git-команды.

Git запускается через `ProcessBuilder` с отдельными аргументами. Обычная ветка,
detached HEAD, пустой репозиторий, отсутствие коммитов за период и dirty working
tree имеют отдельные безопасные результаты.

## Где используется AI

После строгого разбора MCP JSON runner формирует отдельный request-local prompt.
Commit subjects, branch и paths помещаются в блок **untrusted data**; инструкции
из них запрещено выполнять. OpenAI получает только обычные messages и
`CompletionOptions`: tool definitions специализированному вызову не передаются.

Модель превращает факты в пять секций: «Коротко», «Что изменилось», «Риски и
проверки», «Следующие шаги» и «Источники». Backend postflight запрещает неизвестные
`C...`/`F...` IDs, требует citations у фактических выводов в двух основных
секциях, проверяет обязательную секцию источников и точное соответствие каждой
строки фактическому short SHA/subject либо path/status. Невалидный ответ не
публикуется как успешный.

Команда полностью request-local. Успех, MCP/LLM/postflight error, cancellation и
незавершённый stream не записывают `/digest` или ответ в обычную history,
`SHORT_TERM`, `WORKING`, `LONG_TERM`, профиль, инварианты или task FSM.

## Почему нет raw file contents

Для стендапа достаточно metadata. `git_activity_report` не вызывает `git show -p`,
не читает файлы и не возвращает patch, diff или snippets. `.env*`, `.llm-*`, Git,
IDE/build/dependency/video/runtime paths, secret/key/token/credential paths и
похожие на токены значения исключаются или редактируются до prompt.

Полный bounded MCP report существует только внутри backend request. REST/SSE
`ProjectDigestDiagnostics` содержит период, branch/HEAD, counts, working-tree
агрегаты, `truncated` и source IDs. `mcpCalls[].result` также является safe
projection без commit subjects и paths. Пользователь видит необходимые bounded
source metadata только в прошедшей postflight AI-сводке.

## Production-запуск

Сначала запустите обычное приложение из корня анализируемого репозитория:

```bash
./gradlew runWeb
```

Выберите OpenAI → «Простой агент», сохраните OpenAI API-ключ и отправьте
`/digest 24`. Это реальный платный OpenAI-вызов; автоматически и при записи
fixture-видео он не выполняется. Production `LocalMcpGateway` передаёт текущий
настроенный repository root дочернему workspace server.

## Fixture-запуск

Безопасный сценарий без сети и платных API:

```bash
./gradlew runWebFixture -PwebPort=18080
```

Fixture создаёт временный настоящий Git-репозиторий: два коммита с фиксированными
датами, unstaged изменение и untracked файл. Дочерний workspace MCP server и
`git_activity_report` настоящие; LLM — test-only deterministic fake с заранее
grounded ответом. Временный репозиторий удаляется при остановке fixture.

## Security model и ограничения

- Путь репозитория не входит в tool input или wire diagnostics.
- Параметры ограничены: `sinceHours=1..168`, `maxCommits=1..50`,
  `maxFiles=1..100`; неизвестные поля отклоняются.
- Все коллекции, строки, subjects, paths и process output имеют жёсткие лимиты.
- Git metadata считается недоверенным вводом для модели.
- Сводка не доказывает семантическую цель изменений по имени файла и не доказывает
  прохождение тестов; модель обязана явно обозначать неопределённость.
- Работающий Git CLI обязателен. Репозитории с очень большой активностью дают
  `truncated=true`; пользователь должен расширить анализ вручную, а не считать
  отчёт полным.
- MCP/LLM выполняются только пока жив локальный JVM runtime.

## Сценарий видеодемонстрации

```bash
npm --prefix frontend run record:project-digest-demo
```

Скрипт проверяет порт `18080` через `lsof`, сам запускает только
`runWebFixture`, записывает 1440×900 и проверяет ненулевые size/duration,
разрешение и video stream через Chromium metadata. Существующий файл не
перезаписывается без `PROJECT_DIGEST_DEMO_OVERWRITE=1`.

Короткий ролик показывает:

1. подсказку и ввод `/digest 24`;
2. появление/streaming карточки «AI-СВОДКА РАЗРАБОТКИ»;
3. отдельный блок «Детерминированные Git-факты» с периодом, branch/HEAD, counts и
   working tree;
4. раскрытый provenance `workspace / git_activity_report`;
5. отдельный AI-ответ и секцию источников `[C...]`/`[F...]`.

Результат: `video/project-digest-demo.webm`. Inference в ролике — **fake fixture**,
а не OpenAI и не локальная Ollama.

## Различие видов проверок

- **Fixture/fake**: все unit/API/browser-тесты и видео; настоящий временный Git и
  MCP stdio, deterministic fake LLM, без сети.
- **Real-local Ollama**: для `/digest` не поддерживается и этой демонстрацией не
  запускается; продуктовый контракт не выдаёт Ollama MCP tools.
- **Платный OpenAI**: production-вариант команды; запускается только вручную при
  явно настроенном ключе и не входит в автоматические проверки или видео.
