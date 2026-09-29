# Сценарий видео: первый RAG-сценарий

Готовый план записи на 3–5 минут. Видео в этом окружении не записывалось; ниже —
сценарий и безопасные команды для самостоятельной записи без платного API.

## Подготовка

```bash
./gradlew runRagEvaluationFixture
./gradlew runWebFixture
```

Открыть `http://127.0.0.1:18080`. Fixture создаёт временный валидный
`structured.json`, использует deterministic fake embeddings/generation и удаляет
каталог после остановки. Production API, сеть и реальные ключи не используются.

## Тайминг и действия

### 0:00–0:35 — новый режим

1. Показать селектор «Режим ответа».
2. Выбрать **«RAG: с источниками / без RAG»**.
3. Обратить внимание: виден лимит токенов, но отсутствуют history, checkpoint,
   branches и memory-layer controls — контекст эксперимента независимый.

### 0:35–1:25 — один контрольный вопрос

Отправить:

> Как запустить production web-приложение?

Во время выполнения показать две loading-карточки. После завершения сравнить
**«БЕЗ RAG»** и **«С RAG»**: generation model одна, но baseline не получал chunks,
а вторая ветка использовала локальный индекс.

### 1:25–2:20 — provenance и citation

1. В RAG-ответе выделить ссылку `[S1]`.
2. Ниже раскрыть/показать блок **«Использованные источники»**.
3. Пройти по полям rank, относительный путь, section, `chunkId`, similarity score.
4. Коротко отметить, что UI/API не получает vectors и полный chunk context.

### 2:20–3:10 — dataset и report

Открыть `src/main/kotlin/rag/RagEvaluation.kt`, показать ровно десять
`RAG_EVALUATION_CASES`, затем открыть созданный fake-report
`.llm-rag-evaluation/comparison.md` и `docs/RAG_EVALUATION.md`.
Показать production-команду, не запуская её без осознанного решения о стоимости:

```bash
./gradlew runRagEvaluation
```

Объяснить, что команда делает минимум 20 generation calls и 10 embedding queries,
а versioned report появляется в `.llm-rag-evaluation/comparison.json` и `.md`.
Автоматические source/citation/usage flags не заменяют ручную rubric 0–2.

### 3:10–4:20 — ключевые классы и тесты

Показать по одному экрану:

- `indexing/DocumentRetriever.kt` — validated load, compatible model/dimension,
  общий cosine top-5 и стабильный tie-break;
- `app/RagComparisonRunner.kt` — baseline first, untrusted context, citation check;
- `app/WorkbenchController.kt` и `web/ApiDtos.kt` — SSE source of truth и
  metadata-only diagnostics;
- `DocumentRetrieverTest`, `RagComparisonRunnerTest`, `WorkbenchApiTest` и
  `frontend/tests/workbench.spec.ts` — ranking, isolation, injection, errors,
  cancellation, wire и две карточки.

### 4:20–4:45 — завершение

Сформулировать границу проверки: демонстрация подтверждает весь технический flow
`question → fake embedding → retrieval → context → fake LLM` без сети. Она не
подтверждает качество реальной embedding/generation model. Для такого вывода нужен
явный production evaluation и ручная оценка отчёта.
