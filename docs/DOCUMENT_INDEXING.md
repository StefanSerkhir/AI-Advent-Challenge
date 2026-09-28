# Локальная индексация документов

Подсистема `org.example.indexing` строит локальный текстовый индекс репозитория независимо от web runtime, response modes, истории, MCP и memory layers. Один нормализованный corpus manifest проходит через две стратегии chunking, после чего chunks получают embeddings и сохраняются в отдельных versioned JSON-файлах.

## Безопасная воспроизводимая проверка

```bash
./gradlew runDocumentIndexFixture
```

Fixture расположен только в test source set. Он использует `DeterministicFakeEmbeddingClient`, не читает API-ключ, не выполняет сетевые или платные вызовы, строит оба индекса во временном каталоге, загружает их обратно и проверяет metadata, размерность, общий manifest и JSON/Markdown-отчёт. Временный каталог удаляется после проверки. Значения Hit@3 и MRR в таком прогоне проверяют механику evaluation, но не качество реальной embedding-модели.

Контрольный fixture-прогон текущего репозитория:

- документов: `65`;
- символов: `683757`;
- слов: `61888`;
- приблизительных страниц: `379.87`;
- fixed chunks: `727`;
- structured chunks: `1329`.

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
- `docs/**/*.md`;
- `src/main/kotlin/**/*.kt`;
- `frontend/src/**/*.ts` и `frontend/src/**/*.tsx`.

Исключаются `.git`, build/output/test-report каталоги, `node_modules`, `legacy-desktop`, `.env`, `.llm-*`, бинарные данные и файлы вне allowlist. UTF-8 декодируется строго, BOM удаляется, CRLF/CR переводятся в LF, trailing whitespace строк удаляется, Unicode нормализуется в NFC. Content hash считается от нормализованного текста. В index и report сохраняются только относительные пути.

В текущем allowlist и репозитории PDF отсутствуют, поэтому PDF-библиотека и искусственный PDF не добавлялись. PDF не индексируется автоматически: при появлении реального PDF corpus следует сначала добавить безопасный JVM extractor, лимиты размера и отдельные тесты, а затем явно расширить allowlist.

## Chunking

Обе реализации получают один и тот же `List<NormalizedDocument>` и никогда не объединяют разные файлы.

`FixedSizeChunkingStrategy` использует целевой размер 1200 и overlap 200. Граница сдвигается назад сначала к переводу строки, затем к whitespace; fallback остаётся Unicode-safe и не разрывает surrogate pair. Пустые chunks отбрасываются.

`StructureAwareChunkingStrategy` сохраняет Markdown heading path в `section`, а Kotlin/TypeScript/TSX делит по крупным top-level declarations. Обычный текст делится по абзацам. Блок длиннее 1600 символов дополнительно режется ограниченными частями с небольшим overlap. Preamble остаётся отдельной секцией своего файла.

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
