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

Сначала явно постройте production structure-aware индекс. Обе команды могут быть
платными и не запускаются автоматическими тестами или Codex без отдельного прямого
разрешения:

```bash
./gradlew buildDocumentIndexes --args="--root . --output .llm-document-index --strategy structured --embedding-model text-embedding-3-small --batch-size 64"
./gradlew runRagEvaluation
```

Для 10 cases evaluation выполняет до 40 generation calls: 10 baseline, 10 raw,
10 rewrite и до 10 enhanced. Enhanced generation пропускается при нуле sources.
Retrieval добавляет до 20 query embedding calls: по исходному и переписанному
запросу для каждого успешно дошедшего до retrieval case. Значения `rag_candidate_limit`,
`rag_result_limit` и `rag_min_similarity` читаются из локальных настроек; модель и
max tokens можно переопределить CLI-параметрами:

```bash
./gradlew runRagEvaluation --args="--root . --output .llm-rag-evaluation --model gpt-4.1-mini --max-tokens 600"
```

## Report v2

Результат атомарно записывается в исключённый из Git каталог:

- `.llm-rag-evaluation/comparison.json` — machine-readable format v2;
- `.llm-rag-evaluation/comparison.md` — читаемое side-by-side сравнение.

Для raw и enhanced отдельно сохраняются ответ/ошибка, usage, стоимость и elapsed,
retrieval query, candidate/result limits, threshold, counts, metadata реально
использованных источников, expected-source flag и проверка citations. Vectors,
полные chunk texts и rewrite prompt в report не записываются. Старый v1 читается
с явной миграцией: прежняя RAG-ветка становится raw, а enhanced помечается как
отсутствующая в исходном отчёте; следующее сохранение создаёт v2.

Markdown содержит общую raw/enhanced таблицу и отдельные ответы/источники. При
нуле результатов enhanced generation пропускается, но report сохраняет rewrite
usage, candidate count, threshold и `filteredCount=0`.

Автоматика оценивает только наблюдаемые retrieval/source/citation/usage свойства.
Она не делает keyword-based вывод о содержательном качестве. Для raw и enhanced
есть отдельные nullable поля ручной оценки `0..2`:

- `correctness` — фактическая правильность;
- `completeness` — покрытие expectation;
- `groundedness` — опора на показанные sources/citations.

Similarity threshold не универсален: одинаковое значение по-разному ведёт себя
на разных embedding models и corpus. Улучшение качества можно подтвердить только
production evaluation с ручной оценкой, а не фактом применения rewrite/фильтра.

## Безопасные проверки

`RagEvaluationTest` использует deterministic fake generation/query embeddings,
проверяет десять cases, round-trip v2 и чтение v1. Browser fixture показывает три
карточки, rewrite query, `10 → N`, threshold, citations и изолированный сбой
enhanced-ветки.

Для ручного просмотра структуры отчёта без сети и стоимости:

```bash
./gradlew runRagEvaluationFixture
```

Fake-report доказывает механику pipeline и формата. Он не доказывает качество
production embeddings, rewrite или финальных ответов.
