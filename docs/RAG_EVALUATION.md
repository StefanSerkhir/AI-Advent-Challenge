# RAG evaluation

Первый RAG-сценарий сравнивает два независимых вызова одной выбранной generation
model: исходный вопрос без дополнительного контекста и тот же вопрос с top-5 из
`.llm-document-index/structured.json`. Одинаковы model, max output и generation
options; отличается только RAG system/user context. История, memory layers и MCP
не участвуют и не изменяются.

## Контрольный набор

Канонический dataset находится в `src/main/kotlin/rag/RagEvaluation.kt` как
`RAG_EVALUATION_CASES`. Он содержит ровно десять стабильных cases:

1. production web start — `README.md`;
2. deterministic web fixture — `README.md`;
3. production entry point `org.example.web.WebMainKt` — `README.md`, `docs/ARCHITECTURE.md`;
4. порядок `MEMORY_LAYERS` и приоритеты — `docs/ARCHITECTURE.md`;
5. доступность checkpoint/branches только для `BRANCHING` — `docs/ARCHITECTURE.md`, `docs/WEB_API.md`;
6. ограничения MCP: OpenAI + unrestricted, `tools/list`, максимум три call, transient messages — `README.md`, `docs/ARCHITECTURE.md`;
7. corpus allowlist и исключения — `docs/DOCUMENT_INDEXING.md`;
8. fixture индексации против production — `docs/DOCUMENT_INDEXING.md`;
9. `requestId`/`operationId`/`duplicate_id` — `docs/WEB_API.md`;
10. безопасность `save_to_file` — `README.md`, `docs/ARCHITECTURE.md`.

Формулировки expectations сверены с текущим кодом и документацией. Предложенные
пути и факты остаются актуальными; корректировать dataset из-за противоречий не
потребовалось.

## Production-запуск

Сначала явно постройте production structure-aware индекс. Обе команды могут быть
платными и не запускаются автоматическими тестами или Codex без отдельного прямого
подтверждения пользователя:

```bash
./gradlew buildDocumentIndexes --args="--root . --output .llm-document-index --strategy structured --embedding-model text-embedding-3-small --batch-size 64"
./gradlew runRagEvaluation
```

Второй вызов использует generation provider/model из `.env`, OpenAI key для query
embeddings и выполняет минимум 20 generation calls плюс 10 embedding queries.
Необязательные параметры:

```bash
./gradlew runRagEvaluation --args="--root . --output .llm-rag-evaluation --model gpt-4.1-mini --max-tokens 600"
```

Результат записывается атомарно и исключён из Git:

- `.llm-rag-evaluation/comparison.json` — versioned machine-readable report v1;
- `.llm-rag-evaluation/comparison.md` — читаемое сравнение.

## Что фиксирует report

Для каждого вопроса сохраняются expectation и expected sources, baseline/RAG
answers, top-5 metadata, признак найденного expected source, проверка допустимости
citations, фактический provider usage и elapsed time обеих веток. Vectors и полный
retrieved context в report не попадают.

Автоматика честно проверяет только наблюдаемые retrieval/source/citation/usage
свойства. Она не использует keyword matching или fake embeddings как оценку
содержательного качества. `manualAssessment` содержит три nullable поля с
допустимым диапазоном `0..2`:

- `correctness`: фактическая правильность;
- `completeness`: покрытие обязательного expectation;
- `groundedness`: опора утверждений на показанные sources/citations.

Сразу после запуска они имеют значение `null`/`pending`; эксперт заполняет их и
итоговый comment вручную. Если вместо production embeddings использовался fixture,
такой отчёт доказывает только механику pipeline, а не реальное качество retrieval.

## Безопасные проверки

`RagEvaluationTest` прогоняет все десять cases с deterministic fake generation и
query embeddings во временном каталоге, проверяет JSON/Markdown round-trip и не
использует сеть или `.env`. Browser fixture отдельно демонстрирует произвольный
вопрос, streaming, две карточки, `[S1]`, top-5 и partial result.

Для ручного просмотра структуры отчёта без сети и стоимости:

```bash
./gradlew runRagEvaluationFixture
```

Команда test source set создаёт те же два файла в исключённом из Git
`.llm-rag-evaluation/` и явно помечает их как проверку механики на fake clients.
