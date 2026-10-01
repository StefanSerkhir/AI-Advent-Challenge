# Сценарий видео: query rewrite и фильтрация RAG

Воспроизводимый сценарий записи на 5–7 минут. В этом окружении MP4 не записан:
доступного средства безопасной записи экрана нет. Все действия ниже используют
fixture без реальных ключей, сети и платного API.

## Подготовка

```bash
./gradlew runRagEvaluationFixture
./gradlew runWebFixture
```

Вторая команда остаётся запущенной. Откройте `http://127.0.0.1:18080`. Fixture
создаёт временный `structured.json` из десяти chunks, использует deterministic
fake rewrite/embeddings/generation и удаляет runtime-каталог после остановки.
Первой командой уже создан безопасный `.llm-rag-evaluation/comparison.md`.

## Тайминг записи

### 0:00–0:45 — цель и границы

Показать режим **«RAG: с источниками / без RAG»** и проговорить три независимые
ветки: baseline, raw RAG и enhanced RAG. Подчеркнуть, что fixture проверяет
механику, а не production-качество.

### 0:45–1:35 — настройки top-K и threshold

В sidebar показать:

- «Кандидатов до фильтрации» — `10`;
- «Источников после фильтрации» — `5`;
- «Минимальная similarity» — `0.20`.

Кратко объяснить диапазоны `1..50`, `1..20`, условие result ≤ candidate и
threshold `-1.0..1.0`. Порог не является универсальным: его калибруют для
конкретных embedding model и corpus. Переключить другой режим и показать, что эти
контролы скрыты, затем вернуться в `rag`.

### 1:35–2:45 — три карточки на одном вопросе

Отправить:

> Как запустить production web-приложение?

Во время streaming показать три заранее созданные карточки. После завершения:

1. **БЕЗ RAG** получил только исходный вопрос;
2. **RAG БЕЗ ФИЛЬТРА/REWRITE** использовал исходный retrieval query и top-5;
3. **УЛУЧШЕННЫЙ RAG** выполнил отдельный rewrite и фильтрацию.

Отметить, что generation model/options одинаковы, а история, memory layers и MCP
не участвуют.

### 2:45–3:45 — query, counts и citations

В diagnostics raw-карточки показать исходный query и надпись «rewrite и threshold
не применялись». В enhanced-карточке показать rewritten query, `10 → N`, threshold,
число отброшенных результатов и rewrite elapsed/usage/cost.

Раскрыть реально сохранённые sources: `[S1]…[Sn]`, rank, path, section, chunkId и
similarity. Сопоставить citations ответа с новой нумерацией. Уточнить, что REST/SSE
не содержит vectors или полный chunk text.

### 3:45–4:30 — изоляция ошибки

Отправить:

> Проверь partial result [[rewrite-error]]

Показать, что baseline и raw RAG остаются готовыми, а ошибка находится только в
enhanced-карточке. Для демонстрации нулевого результата можно временно поставить
threshold `1.00`: enhanced generation будет пропущен и появится сообщение
«При заданном пороге релевантный контекст не найден».

### 4:30–5:35 — evaluation-report

Открыть `.llm-rag-evaluation/comparison.md`. Показать side-by-side таблицу raw и
enhanced: queries, candidates → kept, threshold, expected-source/citation flags,
usage и elapsed. Ниже показать отдельные ответы и две ручные rubric 0–2.

Указать верхнюю границу production-сценария по числу вызовов: для десяти cases —
до 40 generation calls и до 20 query embedding calls; нулевой результат фильтра
пропускает enhanced generation. Production-команду только
показать, но не запускать без осознанного разрешения:

```bash
./gradlew runRagEvaluation
```

### 5:35–6:30 — код и тесты

Показать по одному экрану:

- `indexing/DocumentRetriever.kt` и `RagRelevanceFilter.kt` — top-K, threshold,
  стабильный tie-break и rerank;
- `app/RagComparisonRunner.kt` — три ветки, `RagQueryRewriter`, untrusted context
  и postflight citations;
- `rag/RagEvaluation.kt` — report v2 и миграция v1;
- `web/ApiDtos.kt`, `frontend/src/components/Sidebar.tsx` и `Results.tsx` — явный
  metadata-only wire contract и UI;
- `DocumentRetrieverTest`, `RagRelevanceFilterTest`, `RagComparisonRunnerTest`,
  `RagEvaluationTest`, `WorkbenchApiTest` и Playwright — fakes, isolation,
  cancellation, zero result и отсутствие утечки chunks/vectors.

### 6:30–6:50 — честный вывод

Завершить формулировкой: fixture подтверждает полный технический flow и
детерминированное отбрасывание кандидатов, но не подтверждает улучшение качества.
Для вывода о качестве нужны production embeddings/generation и ручное сравнение
correctness/completeness/groundedness.
