# Ассистент разработчика: проверка и демо

Ассистент разработчика — специальная request-local команда существующего режима
«Простой агент» (`ResponseMode.UNRESTRICTED`, wire-id `unrestricted`). Он не
добавляет новый режим, endpoint, store или формат постоянного состояния.

## Команды и данные

- `/help` — локальная памятка без LLM, embeddings и MCP-вызовов;
- `/help <вопрос>` — ответ по `README.md` и `docs/**/*.md|pdf` из существующего
  `.llm-document-index/structured.json`;
- `workspace / git_current_branch` — один read-only MCP-вызов для текущей ветки,
  если выбран OpenAI, которому уже разрешены локальные tools.

Parser регистрозависим и требует команду в начале всего prompt. `/helpful`,
`/Help` и фраза с `/help` в середине остаются обычными сообщениями.

Документальные chunks и Git JSON передаются раздельно как недоверенные данные.
Ветка не записывает запрос или ответ в history, `SHORT_TERM`, `WORKING`,
`LONG_TERM`, профиль, инварианты или FSM. Секция источников и дословные цитаты
проверяются backend тем же validator, что используется остальными RAG-сценариями.
UI показывает `ragDiagnostics.pipeline=developer_help`, реальные source metadata,
evidence и существующий `mcpCalls` без специального frontend store.

## Production-подготовка

Индекс строится общим полностью локальным Ollama pipeline:

```bash
ollama pull qwen3-embedding:0.6b
./gradlew buildLocalDocumentIndex
./gradlew runWeb
```

Если индекс отсутствует, повреждён или несовместим, generation не начинается, а
ответ содержит точную команду `./gradlew buildLocalDocumentIndex`. Запуск реальной
Ollama-модели и облачный OpenAI не входят в автоматические тесты или запись демо.

## Безопасная fixture-проверка

`runWebFixture` использует fake LLM/embeddings, но настоящий Ktor API,
`DocumentRetriever`, индекс, MCP stdio gateway/server, `tools/list`, `tools/call`
и временный Git-репозиторий на ветке `fixture-main`:

```bash
./gradlew runWebFixture
```

В браузере выберите OpenAI → «Простой агент» и отправьте:

> /help Объясни архитектуру проекта, как запустить web-интерфейс и на какой Git-ветке я сейчас нахожусь?

Ожидаются архитектура Kotlin/Ktor + React, команда запуска, `fixture-main`, блок
«Документация репозитория» с проверенными sources/evidence и MCP-вызов
`workspace / git_current_branch`. История и `SHORT_TERM` остаются пустыми.

## Автоматические проверки и запись

```bash
./gradlew test
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

Для ролика сначала отдельно запустите fixture на порту 18080, затем:

```bash
npm --prefix frontend run record:developer-assistant-demo
```

Скрипт записывает `video/developer-assistant-demo.webm` в разрешении 1440×900,
показывает подсказку `/help`, локальную памятку, основной вопрос, ответ, Git branch,
MCP/evidence/sources и второй вопрос о production entry point. Существующий файл
не перезаписывается; намеренная повторная запись требует
`DEVELOPER_ASSISTANT_DEMO_OVERWRITE=1`.

Fixture/fake проверка не является real-local Ollama inference и не является
облачной проверкой. Для передачи результата размер, длительность, разрешение и
ненулевой video stream дополнительно проверяются `ffprobe`.
