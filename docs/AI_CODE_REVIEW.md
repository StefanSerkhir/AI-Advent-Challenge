# Автоматическое AI-ревью pull request

## Назначение

AI reviewer — отдельный CLI `org.example.review`, не связанный с web UI,
`ResponseMode`, историей, memory layers, MCP или HTTP API. Он читает pull request
как данные, извлекает релевантную документацию и production-код из существующего
structure-aware индекса, получает structured review от GPT-5.6, локально
проверяет каждую location/evidence и публикует одно обновляемое Markdown-сообщение.

## GitHub Actions

Workflow находится в `.github/workflows/ai-code-review.yml` и реагирует на
`pull_request` типов `opened`, `reopened`, `synchronize`, `ready_for_review`.
Draft PR игнорируются. `concurrency` использует номер PR и отменяет устаревший
запуск после нового push.

Repository settings:

- secret `OPENAI_API_KEY` — обязателен для embeddings и GPT-5.6 review;
- variable `AI_REVIEW_MODEL` — необязательна, default `gpt-5.6-sol`; допустимы
  только `gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-5.6-sol`;
- стандартный `GITHUB_TOKEN` создаётся Actions автоматически; PAT не нужен.

Workflow permissions минимальны:

```yaml
permissions:
  contents: read
  pull-requests: write
```

Checkout, JDK setup и artifact upload закреплены полными commit SHA; комментарии
рядом с `uses` фиксируют соответствующую release-версию. Step Summary показывает
commit, eligibility и outcome, но не diff, chunks, vectors или provider response.

## Модель доверия base/head

`pull_request_target` не используется. Такой trigger мог бы дать secret и
write-token коду workflow из привилегированного base context и слишком легко
привести к исполнению содержимого недоверенного head.

Безопасный путь текущего workflow:

1. До любых платных вызовов проверяются draft, fork и наличие secret.
2. `actions/checkout` получает точный `github.event.pull_request.base.sha`.
3. `fetch-depth: 0` делает same-repository head доступным только как Git objects.
4. Проверяются точный `HEAD == base SHA` и наличие head commit.
5. Gradle Wrapper, Kotlin reviewer и все build scripts запускаются только из base.
6. Head не checkout-ится и не исполняется; `git diff`/`git cat-file` только читают
   metadata, paths и patches. External diff и textconv отключены.
7. Индекс строится только по base checkout. Новый код PR поступает модели через
   bounded unified diff.

PR title/body, commit data, filenames, diff, code comments, strings и RAG chunks
считаются недоверенными. Stable system prompt расположен раньше динамических
данных и запрещает выполнять найденные в них инструкции, раскрывать secret или
менять output contract.

Fork PR не получает Actions secrets и write-token. Workflow завершает такой run
нейтрально до checkout/reviewer и пишет в Summary, что OpenAI calls равны нулю.
Ограничение не обходится через `pull_request_target`.

## Diff collection и coverage

CLI требует полные 40- или 64-символьные hex base/head SHA. Changed paths берутся
через `git diff --name-status -z --find-renames --find-copies`, поэтому пробелы не
ломают field parsing. Поддерживаются added, modified, deleted, renamed, copied,
type-changed и binary files. Каждый путь валидируется как относительный и не может
содержать `..`, backslash или control characters.
До collection CLI дополнительно требует, чтобы фактический `HEAD` точно совпадал
с base SHA, а working tree, включая untracked files, был чистым. Это не позволяет
локальному head/dirty-файлу незаметно попасть в base RAG corpus.

Для каждого changed file читается полный unified patch с 80 context lines.
Parser учитывает hunk headers: `+` получает новый line number, context увеличивает
номер без пометки, `-` не считается changed line. Deleted и binary files могут
получать только file-level finding.

Default model-input limit — 120000 символов diff. В prompt входят только целые
file patches в детерминированном порядке. Binary, no-text и patches, не помещённые
в remaining budget, явно перечисляются в `skippedFiles` с причиной; весь changed
file inventory всё равно передаётся. Review получает `partial=true`. Никакой файл
не исчезает молча. Полный diff не печатается в CI logs и не входит в artifact.

## Repository RAG

Reviewer не создаёт второй индексный формат. `DocumentIndexPipeline` строит один
`structured.json` формата v1 по доверенному base corpus:

- `README.md`, `docs/**/*.md|pdf`;
- `src/main/kotlin/**/*.kt`, `frontend/src/**/*.ts|tsx`.

OpenAI key читается непосредственно из process environment; `.env` не создаётся.
Один bounded query содержит title/description, paths, change kinds, identifiers и
ограниченные added-line excerpts. `DocumentRetriever` выполняет два поиска:

1. документация получает IDs `D1…`;
2. production-код получает IDs `C1…`.

Результаты дедуплицируются по `chunkId`, ограничиваются общим context budget и
сохраняют source, section, chunkId, domain, rank и score. Text используется только
в request-local prompt и exact-quote validation. Vectors, полный индекс и полный
chunk text не попадают в comment, `review.json` или CI logs.

## GPT-5.6 и локальная валидация

Production defaults:

- model `gpt-5.6-sol`;
- `reasoning_effort=medium`;
- non-streaming Chat Completions;
- strict JSON Schema;
- максимум 8000 completion tokens.

Контракт содержит summary, verdict (`PASS`, `COMMENT`, `REQUEST_CHANGES`),
partial, reviewed/skipped files, findings и used RAG IDs. Finding содержит
последовательный `F-001` ID, severity `P0..P3`, category, confidence, relative
path, nullable changed line, title, problem, impact, recommendation и evidence.

Backend fail-closed проверяет:

- точное совпадение partial/reviewed/skipped с локальным diff budget;
- severity/category через enum decoding;
- path в changed/reviewed files;
- line в локальном changed-line set;
- RAG source ID в request-local context;
- обязательную evidence для architecture finding;
- точное вхождение каждой непустой quote после нормализации whitespace;
- согласованность `usedRagSourceIds`;
- отсутствие известных keys, bearer tokens и локальных absolute paths.

При invalid structured output выполняется максимум одна repair-попытка с тем же
schema. Вторая ошибка завершает run без публикации сырого/непроверенного текста.

## Comment и artifacts

Комментарий начинается с `<!-- ai-code-review:v1 -->`. Publisher ищет marker
только среди комментариев `github-actions[bot]` на всех страницах issue comments,
создаёт comment при первом запуске и PATCH-обновляет его далее; случайные bot-дубликаты marker удаляются. Внутри: analysed commit, summary,
verdict/coverage, findings по severity/path, location, problem/impact/fix,
проверенная RAG evidence, reviewed/skipped files и безопасная диагностика.

Если findings нет, review явно сообщает, что доказуемые проблемы не найдены, но
не гарантирует корректность кода. Markdown не содержит full diff/chunks/vectors.
`review.md` и `review.json` атомарно записываются до публикации. Workflow загружает
их как 14-day artifact и при success, и при publication failure.

## Локальный запуск

Полностью offline fixture:

```bash
./gradlew runAiCodeReviewFixture
```

Он создаёт временный Git repository, synthetic PR с text/binary changes, строит
индекс fake embeddings, использует fake LLM и fake publisher и возвращает минимум
bug, architecture finding и recommendation с documentation/code evidence.
Внешняя сеть, GitHub credentials, OpenAI и Ollama не используются.

Production dry-run использует OpenAI, но не GitHub publication:

```bash
export OPENAI_API_KEY=...
./gradlew runAiCodeReview --args="--root . --base-sha <40-char-base> --head-sha <40-char-head> --pr-number 123 --repository owner/repo --title 'PR title' --output build/ai-code-review --model gpt-5.6-sol --dry-run"
```

Для production publication дополнительно задайте `GITHUB_TOKEN` и удалите
`--dry-run`. В Actions metadata безопаснее читается через `--event-file
$GITHUB_EVENT_PATH`; CLI сверяет номер и оба SHA с явными аргументами.

Основные параметры: `--root`, `--base-sha`, `--head-sha`, `--pr-number`,
`--output`, `--model`, `--diff-limit`, `--max-tokens`, `--dry-run`, `--event-file`.
`--fixture` распознаётся production parser, но направляет к test-only задаче,
чтобы fake clients не попадали в production artifact.

## Демо-видео

```bash
npm --prefix frontend run record:ai-code-review-demo
```

Script сам запускает offline fixture, открывает локальный `demo.html` и записывает
synthetic PR/files, diff, отдельные documentation/code sources и итоговый review
с bug/architecture/recommendation. На экране явно указано, что это fake fixture.
Результат: `video/ai-code-review-demo.webm`, 1440×900. Нулевой размер, отсутствие
video stream, неверная duration/resolution завершают script ошибкой. Существующий
файл защищён; для осознанной перезаписи нужен
`AI_CODE_REVIEW_DEMO_OVERWRITE=1`.

## Стоимость, ограничения и false positives

Каждый eligible run заново создаёт embeddings base corpus, выполняет два query
embedding calls и один review completion; invalid output может добавить ровно
один completion. Большой corpus увеличивает стоимость embeddings. `synchronize`
отменяет устаревший run через concurrency, но уже завершённые provider calls могут
быть тарифицированы. GitHub artifact не содержит index directory.

Лимит diff может сделать review partial. Binary content не анализируется. RAG —
поиск архитектурного контекста, не доказательство семантики; model findings могут
быть false positive. Локальная валидация гарантирует provenance/location/schema,
но не истинность вывода. Review не заменяет compiler, tests, security scanners и
human approval.

## Troubleshooting

- `OPENAI_API_KEY is not available`: создайте Actions secret или export для
  локального production run; не записывайте key в `.env` для reviewer.
- `head Git object` отсутствует: убедитесь, что это same-repository PR и checkout
  использует `fetch-depth: 0`; fork намеренно должен быть skipped.
- `RAG ... не нашёл ... контекст`: base corpus должен содержать хотя бы один
  documentation и production-code chunk.
- `Model returned invalid structured output twice`: artifacts не публикуются;
  проверьте `review.json` отсутствует/старый, Step Summary и model compatibility.
- GitHub publication HTTP error: `review.md`/`review.json` должны остаться в
  artifact; проверьте `pull-requests: write` и branch/repository policy.
- Видео уже существует: задайте `AI_CODE_REVIEW_DEMO_OVERWRITE=1` только если
  действительно хотите его заменить.
