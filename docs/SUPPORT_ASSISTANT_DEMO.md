# Демонстрация ассистента поддержки

## Назначение

Ассистент поддержки — точная команда существующего режима «Простой агент»:

```text
/support TCK-1001 Почему не работает авторизация?
```

Нового response mode и REST endpoint нет. Запуск идёт через идемпотентный
`POST /api/operations`, а результат и прогресс публикуются обычным `StateDto`/SSE.

## Архитектура сценария

1. `SupportAssistantRunner` находит инструмент в настоящем объединённом
   `tools/list` и один раз вызывает `support / support_get_ticket_context`.
2. Отдельный MCP stdio-процесс загружает упакованные `support/users.json` и
   `support/tickets.json` формата v1, строго валидирует весь набор и связь
   пользователь–тикет, затем возвращает только allowlist-поля.
3. Ограниченный retrieval query объединяет вопрос, категорию, тему, код ошибки,
   версию и последнее безопасное событие. `DocumentRetriever` ищет только в
   `docs/support/**/*.md|pdf`.
4. Общий similarity filter применяет candidate/result limits и threshold. При
   нуле chunks generation не выполняется.
5. OpenAI получает system instructions, отдельные недоверенные RAG evidence и MCP
   ticket data, а также текущий вопрос. Метки `[Sx]` относятся только к документам.
6. Backend проверяет citations и точные короткие цитаты и только затем публикует
   ответ. История, memory layers, профиль, инварианты и FSM не меняются.

Команда распознаётся регистрозависимо только в начале всего prompt.
`/supportive`, `/Support` и обычный текст с `/support` идут в прежний агент.
DeepSeek и Ollama получают локальное объяснение без model/MCP-вызова: support
pipeline требует связку OpenAI + unrestricted, где по инвариантам доступны tools.

## Безопасная fixture-демонстрация

В одном терминале запустите production-like fixture:

```bash
./gradlew runWebFixture -PwebPort=18080
```

В другом запишите ролик:

```bash
npm --prefix frontend run record:support-assistant-demo
```

Скрипт выбирает OpenAI и `unrestricted`, показывает подсказку, выполняет
`/support`, затем основной запрос. Перед сохранением он проверяет через
`/api/state` завершённый exchange, `pipeline=support_assistant`, verified evidence,
ровно один успешный `support/support_get_ticket_context`, TCK-1001 и рекомендации
по синхронизации времени. На экране последовательно показываются ответ, MCP,
evidence и sources.

Результат: `video/support-assistant-demo.webm`, 1440×900. Существующий файл
защищён; осознанная перезапись требует:

```bash
SUPPORT_ASSISTANT_DEMO_OVERWRITE=1 npm --prefix frontend run record:support-assistant-demo
```

Скрипт проверяет ненулевой размер, положительную длительность и разрешение через
Chromium. В fixture используются deterministic fake embeddings и fake LLM, без
сети, настоящего OpenAI и Ollama. При этом Ktor API, structure-aware index,
`DocumentRetriever`, similarity filter, `LocalMcpGateway`, дочерний MCP stdio-
процесс, `tools/list`, `tools/call`, orchestration и evidence postflight являются
настоящими локальными компонентами.

## Production и ограничения

Перед production-вопросом нужен актуальный structure-aware индекс:

```bash
./gradlew buildLocalDocumentIndex
./gradlew runWeb
```

Production support generation использует выбранную OpenAI-модель и может быть
платной. JSON содержит только вымышленные данные; email, пароль, токены и ключи
отсутствуют. MCP/RAG данные считаются недоверенными, инструкции внутри description
или events не исполняются. Реальная Ollama остаётся доступной для других режимов,
но support MCP pipeline ей намеренно недоступен.
