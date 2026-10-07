# RAG evaluation: raw против enhanced

Evaluation сравнивает три независимые карточки одной выбранной generation model:

1. `baseline` — исходный вопрос без индекса;
2. `rag` — retrieval по исходному вопросу, первые `rag_result_limit` результатов,
   без rewrite и threshold;
3. `rag_enhanced` — отдельный query rewrite, до `rag_candidate_limit` кандидатов,
   фильтр `score >= rag_min_similarity`, итоговый top `rag_result_limit` и новая
   нумерация `[S1]…[Sn]`.

Финальные ответы используют одинаковые generation options. Rewrite получает
только исходный вопрос, ограничен 128 output tokens и не получает историю,
память, MCP или retrieved chunks. Его usage, elapsed time и стоимость учитываются
в enhanced-диагностике и общих метриках ветки.

## Контрольный набор

Канонический `RAG_EVALUATION_CASES` находится в
`src/main/kotlin/rag/RagEvaluation.kt` и содержит десять стабильных вопросов:

1. production web start;
2. deterministic web fixture;
3. production entry point;
4. порядок `MEMORY_LAYERS`;
5. доступность checkpoint/branches;
6. ограничения MCP;
7. allowlist corpus;
8. fixture индексации против production;
9. идемпотентность `requestId`;
10. безопасность `save_to_file`.

## Production-запуск и стоимость

Сначала явно постройте полностью локальный structure-aware индекс. Эти команды
не требуют API-ключей; автоматические тесты их не запускают, потому что они
загружают настоящий Ollama runtime и модели:

```bash
ollama pull qwen3:14b
ollama pull qwen3-embedding:0.6b
./gradlew buildLocalDocumentIndex
./gradlew runRagEvaluation
```

Для 10 cases evaluation выполняет до 40 generation calls: 10 baseline, 10 raw,
10 rewrite и до 10 enhanced через `qwen3:14b`. Enhanced generation пропускается при нуле sources.
Retrieval добавляет до 20 query embedding calls: по исходному и переписанному
запросу для каждого успешно дошедшего до retrieval case через descriptor
`ollama/qwen3-embedding:0.6b`. Значения `rag_candidate_limit`,
`rag_result_limit` и `rag_min_similarity` читаются из локальных настроек; модель и
max tokens можно переопределить CLI-параметрами:

```bash
./gradlew runRagEvaluation --args="--root . --output .llm-rag-evaluation --model qwen3:14b --max-tokens 600 --repetitions 3 --timeout-seconds 180"
```

Дополнительно representative cases `production-web-start`, `memory-layers-order`
и `request-id-idempotency` проходят один warm-up и не менее трёх измеряемых
повторов. Внутри каждого повтора retrieval выполняется один раз, а всем generation
providers передаются одинаковые frozen chunks, prompt и max tokens. OpenAI можно
включить только осознанно:

```bash
./gradlew runRagEvaluation --args="--allow-cloud --cloud-model gpt-4.1-mini"
```

Даже при наличии ключа cloud-вызов без `--allow-cloud` не выполняется и получает
статус `skipped`. Без ключа `--allow-cloud` также даёт `skipped`, а не ошибку.

## Report v4

Результат атомарно записывается в исключённый из Git каталог:

- `.llm-rag-evaluation/comparison.json` — machine-readable format v4;
- `.llm-rag-evaluation/comparison.md` — читаемое side-by-side сравнение.

Для raw и enhanced отдельно сохраняются ответ/ошибка, usage, стоимость и elapsed,
retrieval query, candidate/result limits, threshold, counts, retrieved metadata,
проверенные короткие цитаты и отдельные `sourcesPresent`, `quotesPresent`,
`citationsValid`, `quotesExact`, `expectedSourceFound`, abstention fields. Vectors,
полные chunk texts и rewrite prompt в report не записываются. Старые v1/v2
и v3 читаются с явной миграцией: v1 превращает прежнюю RAG-ветку в raw; v1/v2 не
получают выдуманных historical quotes, поэтому quote flags остаются false.
Следующее сохранение создаёт v4.

`providerComparison` сохраняет provider/model, фактически полученный answer,
success/error/timeout, отдельные embedding/retrieval/generation timings,
end-to-end latency, usage/cost, sources/chunk IDs, expected-source hit,
citations/quotes, abstention и nullable manual assessment только при реально
полученном ответе. Aggregate содержит completion/citation rates, error/timeout
counts, стабильность chunk IDs, p50/p95 и min/max latency. Для Ollama стоимость
остаётся `null`/`н/д`.

Markdown содержит общую raw/enhanced таблицу и отдельные ответы/источники. При
нуле результатов enhanced generation пропускается, но report сохраняет rewrite
usage, candidate count, threshold и `filteredCount=0`.

Автоматика оценивает только наблюдаемые source/quote/citation/exact-substring/usage свойства.
Она не делает keyword-based вывод о содержательном качестве. Для raw и enhanced
есть отдельные nullable поля ручной оценки `0..2`:

- `correctness` — фактическая правильность;
- `completeness` — покрытие expectation;
- `groundedness` — опора на показанные sources/citations.
- `support` — подтверждается ли смысл ответа приведёнными цитатами.

Similarity threshold не универсален: одинаковое значение по-разному ведёт себя
на разных embedding models и corpus. Улучшение качества можно подтвердить только
production evaluation с ручной оценкой, а не фактом применения rewrite/фильтра.

## Безопасные проверки

`RagEvaluationTest` использует deterministic fake generation/query embeddings,
проверяет десять cases, frozen provider context, stability aggregate, round-trip
v4 и миграцию v1/v2/v3. Browser fixture показывает
три карточки, rewrite query, `10 → N`, threshold, verified evidence, изолированный
сбой enhanced-ветки и нормальный abstention без источников/цитат.

Для ручного просмотра структуры отчёта без сети и стоимости:

```bash
./gradlew runRagEvaluationFixture
```

Fake-report доказывает механику pipeline и формата. Он не доказывает качество
production embeddings, rewrite или финальных ответов.
