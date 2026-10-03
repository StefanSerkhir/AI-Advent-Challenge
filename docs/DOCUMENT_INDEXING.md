# Локальная индексация документов

Подсистема `org.example.indexing` строит локальный текстовый индекс репозитория независимо от web runtime, response modes, истории, MCP и memory layers. Один нормализованный corpus manifest проходит через две стратегии chunking, после чего chunks получают embeddings и сохраняются в отдельных versioned JSON-файлах.

## Безопасная воспроизводимая проверка

```bash
./gradlew runDocumentIndexFixture
```

Fixture расположен только в test source set. Он использует `DeterministicFakeEmbeddingClient`, не читает API-ключ, не выполняет сетевые или платные вызовы, строит оба индекса во временном каталоге, загружает их обратно и проверяет metadata, размерность, общий manifest и JSON/Markdown-отчёт. Временный каталог удаляется после проверки. Значения Hit@3 и MRR в таком прогоне проверяют механику evaluation, но не качество реальной embedding-модели.

Контрольный fixture-прогон текущего репозитория:

- документов: `74`;
- символов: `816813`;
- слов: `74240`;
- приблизительных страниц: `453.79`;
- fixed chunks: `867`;
- structured chunks: `1508`.

Страница оценивается по явной стабильной формуле `characterCount / 1800`. Минимально допустимый corpus равен 20 таким страницам; меньший объём завершает запуск ошибкой до embeddings и создания output-каталога.

## Production-запуск

Реальная индексация использует существующий `openai_api_key` из `.env` в корне corpus и может быть платной. Codex и автоматические тесты эту команду не запускают. Пользователь запускает её явно:

```bash
./gradlew buildDocumentIndexes --args="--root . --output .llm-document-index --strategy both --fixed-chunk-size 1200 --overlap 200 --embedding-model text-embedding-3-small --batch-size 64"
```

Параметры:

| Аргумент | Default | Значение |
| --- | --- | --- |
| `--root` | `.` | Корень corpus и место чтения существующего `.env` |
| `--output` | `.llm-document-index` | Абсолютный путь или путь относительно root |
| `--strategy` | `both` | `fixed`, `structured` или `both` |
| `--fixed-chunk-size` | `1200` | Максимальный размер fixed chunk в UTF-16 offsets без разрыва surrogate pair |
| `--overlap` | `200` | Перекрытие fixed chunks |
| `--embedding-model` | `text-embedding-3-small` | OpenAI embedding model |
| `--batch-size` | `64` | Число текстов в одном embedding batch |

CLI вызывает `POST /v1/embeddings` через общий Ktor `HttpClient`, но использует собственный ограниченный retry для `408`, `409`, `429`, `5xx` и временных сетевых ошибок. Ответ восстанавливается по `data[].index`; количество vectors, единая размерность и конечность каждого `Float` проверяются до сохранения. Cancellation остаётся кооперативной. Provider errors очищаются от настроенного ключа, bearer tokens и строк вида `sk-...`.

## Corpus

`RepositoryCorpusCollector` детерминированно сортирует относительные пути и включает:

- `README.md`;
- `docs/**/*.md` и `docs/**/*.pdf`;
- `src/main/kotlin/**/*.kt`;
- `frontend/src/**/*.ts` и `frontend/src/**/*.tsx`.

Исключаются `.git`, build/output/test-report каталоги, `node_modules`, `legacy-desktop`, `.env`, `.llm-*`, symlinks, бинарные данные вне PDF и файлы вне allowlist. UTF-8 декодируется строго, BOM удаляется, CRLF/CR переводятся в LF, trailing whitespace строк удаляется, Unicode нормализуется в NFC. Content hash считается от нормализованного текста. В index и report сохраняются только относительные пути.

### PDF

Текстовые PDF внутри `docs/` извлекаются Apache PDFBox 3.0.8. Чтобы добавить документ, поместите его, например, в `docs/knowledge/manual.pdf`, сначала выполните `./gradlew runDocumentIndexFixture`, затем обычную production-команду. В manifest появится относительный source `docs/knowledge/manual.pdf` с kind `pdf`.

Extractor использует temporary-file cache PDFBox и применяет ограничения до сохранения индекса:

- размер файла — не более 50 MiB;
- число страниц — не более 1000;
- извлечённый текст — не более 10 000 000 символов;
- symlink и зашифрованный/password-protected PDF отклоняются;
- повреждённый PDF завершает pipeline понятной ошибкой;
- PDF без извлекаемого text layer не индексируется и требует предварительного OCR.

Каждая непустая текстовая страница получает стабильный заголовок `# PDF page N`. Благодаря этому structure-aware chunks сохраняют `PDF page N` в поле `section`; fixed chunking использует тот же нормализованный текст. Изображения, графика, координаты layout, вложения и JavaScript не извлекаются и не исполняются. Текущий репозиторий не содержит постоянного PDF: safe fixture не добавляет искусственный PDF, а тесты создают их только во временном каталоге и удаляют после проверки.

## Chunking

Обе реализации получают один и тот же `List<NormalizedDocument>` и никогда не объединяют разные файлы.

`FixedSizeChunkingStrategy` использует целевой размер 1200 и overlap 200. Граница сдвигается назад сначала к переводу строки, затем к whitespace; fallback остаётся Unicode-safe и не разрывает surrogate pair. Пустые chunks отбрасываются.

`StructureAwareChunkingStrategy` сохраняет Markdown heading path и номер PDF-страницы в `section`, а Kotlin/TypeScript/TSX делит по крупным top-level declarations. Обычный текст делится по абзацам. Блок длиннее 1600 символов дополнительно режется ограниченными частями с небольшим overlap. Preamble остаётся отдельной секцией своего файла.

Стабильный `chunkId` имеет префикс стратегии и SHA-256 от стратегии, относительного source, section, ordinal, offsets, content hash документа и hash текста chunk. Неизменившийся документ при одинаковых параметрах получает те же ID; смена границ chunk не переиспользует прежний ID.

## Формат и файлы

Default output исключён из Git через `.gitignore`:

- `.llm-document-index/fixed.json`;
- `.llm-document-index/structured.json`;
- `.llm-document-index/comparison.json`;
- `.llm-document-index/comparison.md`.

Каждый индекс имеет `formatVersion: 1`, параметры стратегии, provider/model/dimensions, полный corpus manifest и chunks с текстом, vector, source, title, section, strategy, ordinal, offsets, content hash и отдельным обязательным metadata-блоком `source`, `title`, `section`, `chunk_id`.

JSON пишется в UTF-8 temporary file, затем заменяет target через atomic move с fallback для файловых систем без `ATOMIC_MOVE`. Ошибка replacement сохраняет прежний target и удаляет temporary file. Загрузка сначала декодирует и валидирует весь документ; отсутствующая/неизвестная версия, повреждённый JSON, абсолютный source, повторный ID, неверные offsets/hash/metadata или несовпадающая размерность отклоняют индекс целиком.

## Сравнение и evaluation

JSON и Markdown содержат число документов/chunks, min/avg/median/p95/max размера, суммарные символы, приблизительные tokens (`ceil(characters / 4)`), заполненность section, пустые/oversized chunks, batch calls, vector dimensions, размер index-файла и длительности только локальных этапов. Время provider API не смешивается с ними.

Встроенные шесть запросов проверяют запуск приложения, HTTP-настройки, OpenAI streaming, Ktor routes, memory layers и локальную конфигурацию. Query embeddings создаёт тот же `EmbeddingClient`, что и chunks. Cosine ranking вычисляет Hit@3, MRR и долю запросов, для которых ожидаемый source или section найден в индексе. Production и fake-отчёты явно различаются флагом и пояснением.

## Retrieval для RAG

Web-режим `rag` не строит второй индекс. `DocumentRetriever` загружает
`.llm-document-index/structured.json`
существующим `JsonDocumentIndexStore`, затем:

1. проверяет versioned документ целиком и требует strategy `structured`;
2. создаёт `EmbeddingClient` с точными `provider/model` из index descriptor;
3. получает один embedding переданного retrieval query и проверяет его dimension;
4. вызывает общий `search`, использующий `cosineSimilarity`;
5. возвращает до переданного limit без merge: score descending, стабильный
   tie-break по `chunkId`; меньший индекс корректно даёт меньше результатов.

Raw RAG передаёт исходный вопрос и limit `rag_result_limit`. Enhanced RAG сначала
получает request-local rewrite исходного вопроса, запрашивает
`rag_candidate_limit`, затем чистая функция, не имеющая доступа к index store,
оставляет `score >= rag_min_similarity`, снова сортирует score/`chunkId`, берёт
`rag_result_limit` и перенумеровывает rank/citations. Универсально правильного
threshold нет: его нужно калибровать для конкретных corpus и embedding model.

Тот же `DocumentRetriever` используется опциональным RAG-чатом Простого агента
при `unrestricted + MEMORY_LAYERS`. В отличие от независимого comparison runner,
он получает bounded contextual query из текущего вопроса, active task,
включённого WORKING и хвоста завершённого SHORT_TERM, затем применяет общий
candidate/threshold/top-K filter. Chunks остаются request-local и входят в
generation как недоверенный блок перед единственным текущим prompt.

Полный текст каждого chunk используется только при построении request-local RAG
prompt и локальной postflight-проверке. Модель возвращает ответ с `[Sx]` и
дословные цитаты; backend требует цитату для каждого использованного source,
нормализует только whitespace, проверяет точное вхождение и присоединяет source,
section и chunk_id из retrieval result. REST/SSE diagnostic содержит rank, score,
metadata и только проверенные короткие quotes; vectors и полный chunk text не
сериализуются и не логируются. Если файл
отсутствует, повреждён, имеет неизвестную версию, пуст или несовместим с embedding
client, RAG LLM-вызов не начинается и пользователь получает команду:

```bash
./gradlew buildDocumentIndexes --args="--root . --output .llm-document-index --strategy structured --embedding-model text-embedding-3-small --batch-size 64"
```

Baseline и ранее завершённая RAG-ветка уже могут быть готовы к этому моменту и
остаются в своих карточках. Если enhanced-фильтр вернул ноль chunks, generation
не вызывается и завершённая карточка возвращает «Не знаю» с machine-readable
`abstained=true`, reason `below_threshold`, candidate count, threshold,
`filtered=0` и пустыми sources/quotes.

## Набор из 10 RAG-вопросов и production evaluation

`org.example.rag.RAG_EVALUATION_CASES` — отдельный dataset; он не смешивается с
шестью `EvaluationQuery`, которые сравнивают fixed/structured chunking. Каждый из
десяти cases имеет стабильный ID, вопрос, проверяемое expectation, expected sources
и при необходимости section hint. Ожидания сверены с текущими README,
ARCHITECTURE, WEB_API и этим документом; исправлений относительно предложенного
набора не потребовалось.

Production evaluation запускается только явно:

```bash
./gradlew runRagEvaluation
```

Команда использует существующие `.env` и `structured.json`, выполняет до 40
generation calls (baseline, raw, rewrite и, если фильтр не пуст, enhanced для
каждого case) и до 20 query embedding calls и атомарно сохраняет:

- `.llm-rag-evaluation/comparison.json` формата v3 (v1/v2 читаются с миграцией);
- `.llm-rag-evaluation/comparison.md`.

Для каждого case сохраняются expectation, expected sources, три ответа, raw и
enhanced queries/counts/filter params/source metadata, verified quotes,
`sourcesPresent`, `quotesPresent`, `citationsValid`, `quotesExact`,
`expectedSourceFound`, usage и elapsed time. Автоматически считаются только
структурно проверяемые metrics. Поля ручной оценки correctness/completeness/
groundedness/support отдельно для raw и enhanced принимают `0..2` и остаются `pending`, пока
эксперт не заполнит их; fake embeddings не выдаются за фактическое качество.
Подробнее см. [RAG_EVALUATION.md](RAG_EVALUATION.md).
