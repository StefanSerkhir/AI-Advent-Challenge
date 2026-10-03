# RAG-чат с памятью: fixture-видео и production-сценарии

Этот сценарий относится только к комбинации **«Простой агент»**
(`unrestricted`) + **«Слои памяти»** (`MEMORY_LAYERS`). Переключатель
**«RAG-чат с источниками»** выключен по умолчанию. Независимый режим
`RAG_COMPARISON` по-прежнему выполняет baseline/raw/enhanced без истории и памяти.

## Готовое видео

Файл: [`video/rag-memory-chat-demo.webm`](../video/rag-memory-chat-demo.webm).

Параметры проверенной записи: 120,72 секунды, 1440×900, WebM. На видео нет
реальных ключей или платных вызовов: используется `runWebFixture`, deterministic
fake embeddings/generation и временное состояние.

Показаны:

1. выбор «Простой агент» → «Слои памяти» и включение RAG-чата;
2. candidate/result/threshold settings и маленький SHORT_TERM limit;
3. task goal, WORKING-ограничение и согласованный термин;
4. серия коротких follow-up-команд, отвлекающий вопрос и итог;
5. `[S1]`, backend-verified exact quote, source metadata и contextual query;
6. «Новый диалог»: SHORT_TERM очищен, task goal и WORKING сохранены;
7. продолжение короткой командой и завершённый abstention с
   «Источники: не найдены».

## Воспроизводимая запись fixture

Сначала убедитесь, что TCP-порт 18080 свободен. В первом терминале:

```bash
./gradlew runWebFixture -PwebPort=18080
```

Во втором терминале:

```bash
npm --prefix frontend run record:rag-memory-demo
```

Команда создаёт ровно `video/rag-memory-chat-demo.webm`. Существующий файл не
перезаписывается без явного `RAG_DEMO_OVERWRITE=1`. Playwright выполняет сценарий
через настоящий Ktor/React UI; fixture не читает `.env`, не вызывает сеть и
удаляет временные memory/task/index stores при остановке.

## Контракт, наблюдаемый в карточке

Contextual retrieval query ограничен 4000 символами и строится в порядке:
текущий вопрос → active task goal/current step/expected action → включённый
WORKING → хвост успешно завершённого SHORT_TERM. После retrieval применяются
`ragCandidateLimit`, `ragMinSimilarity` и `ragResultLimit`.

Generation сохраняет порядок system/invariants/profile/task → LONG_TERM →
WORKING → SHORT_TERM → один текущий user message. Request-local evidence находится
внутри последнего сообщения как недоверенный блок перед prompt. Модель возвращает
«Ответ», «Цитаты» и «Источники»; backend разрешает в последней секции только
метки `[Sx]`, проверяет exact quotes и сам присоединяет source/section/chunkId/rank.
Vectors и полный chunk text не попадают в REST/SSE.

SHORT_TERM коммитится только после полного успеха retrieval, generation,
invariant/task postflight, evidence postflight и atomic persistence. Ошибка или
отмена на любом этапе не создаёт половину пары. Ноль релевантных chunks является
успешным abstention, а не технической ошибкой.

## Два production-сценария с реальным OpenAI API

Opt-in runner находится в
`frontend/scripts/verify-rag-memory-real.mjs`. Он выполняет через UI ровно два
сценария по 10 пользовательских ходов (20 UI submissions). Каждый ход также
создаёт один платный query-embedding call; MCP в RAG-чате отключён. Runner на
каждом ходе проверяет retrieval diagnostics, sources block, verified evidence
либо ожидаемый abstention, неизменность active goal и точное присутствие WORKING-
ограничений/терминов в persistent state и contextual query.

Сценарий 1: production runbook, ограничение JDK 21, термин `release gate`, короткие
follow-up, отвлекающий вопрос о PDF и финальное «Продолжай и собери итог».

Сценарий 2: document indexing/evidence, запрет vectors/full chunks в REST/SSE,
термин `grounded result`, уточнение ограничения, «Новый диалог» после пятого хода
и продолжение короткой командой. Runner проверяет, что task goal и WORKING
пережили очистку SHORT_TERM.

Это намеренно не автоматический тест. Сначала запустите production runtime и
проверьте, что порт 8080 не занят чужим процессом:

```bash
./gradlew runWeb
```

В другом терминале требуется двойное явное подтверждение расходов и временных
persistent mutations:

```bash
ALLOW_PAID_OPENAI_RAG_SCENARIOS=1 \
ALLOW_PERSISTENT_RAG_SCENARIO_MUTATIONS=1 \
npm --prefix frontend run verify:rag-memory-real
```

Runner читает только безопасный `hasKey` из API и никогда не печатает ключ. Он
откажется запускаться, если SHORT_TERM, WORKING или task state уже непусты, чтобы
не удалять пользовательские данные. После успеха или ошибки он очищает только
созданные им SHORT_TERM/WORKING/task state и восстанавливает исходные settings.
Fixture-видео доказывает механику UI/postflight; только этот opt-in production
runner проверяет реальный OpenAI generation/embeddings и расходует API-кредит.
