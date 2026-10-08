# Полностью локальный RAG: Ollama + Qwen3

Этот сценарий использует существующий Week 6 document index и RAG pipeline без
API-ключей и облачных fallback:

`repository → structure-aware chunks → Ollama embeddings → versioned JSON →
Ollama query embedding → JVM cosine search → Ollama rewrite/generation → local
citation/quote postflight`.

Штатные модели: `qwen3-embedding:0.6b` для индекса и retrieval,
`qwen3:14b` для generation. Оба endpoints фиксированы на `127.0.0.1:11434`.

## Подготовка

```bash
lsof -nP -iTCP:11434 -sTCP:LISTEN
ollama pull qwen3:14b
ollama pull qwen3-embedding:0.6b
ollama list
ollama show qwen3:14b
ollama show qwen3-embedding:0.6b
```

Не запускайте второй Ollama server, если порт уже занят известным процессом.
`qwen3:14b` занимает около 9,3 ГБ, embedding-модель — около 639 МБ.

## Локальный индекс

```bash
./gradlew buildLocalDocumentIndex
```

Команда эквивалентна:

```bash
./gradlew buildDocumentIndexes --args="--root . --output .llm-document-index --strategy structured --embedding-provider ollama --embedding-model qwen3-embedding:0.6b --batch-size 32"
```

Она не читает `openai_api_key`. В `.llm-document-index/structured.json` должны
быть `provider=ollama`, `model=qwen3-embedding:0.6b` и фактическая, а не
захардкоженная размерность vector. Файл остаётся runtime-артефактом вне Git.

## Production web и два smoke-вопроса

Без изменения `.env`:

```bash
WEB_PORT=18082 \
llm_kind=Ollama \
llm_model=qwen3:14b \
response_mode=rag \
history_enabled=false \
max_tokens=600 \
./gradlew runWeb
```

Откройте `http://127.0.0.1:18082` и отправьте два вопроса из evaluation dataset:

1. `Где находится production entry point web-приложения?`
2. `Какие ограничения безопасности применяются к save_to_file?`

Каждый exchange должен завершиться карточками baseline, raw RAG и enhanced RAG.
У RAG-карточек проверьте:

- embedding `ollama/qwen3-embedding:0.6b` и manifest hash;
- generation `ollama/qwen3:14b`;
- query embedding/search/retrieval latency и candidate/result counts;
- status evidence `verified`, дословные quotes и repository sources;
- стоимость `н/д`.

Недоступность Ollama является видимой ошибкой; облачного fallback нет. Полные
chunks и vectors не выходят в REST/SSE, browser storage или логи.

## Evaluation качества, скорости и стабильности

```bash
./gradlew runRagEvaluation --args="--max-tokens 600 --repetitions 3 --timeout-seconds 180"
```

Результаты записываются атомарно:

- `.llm-rag-evaluation/comparison.json` — machine-readable v4;
- `.llm-rag-evaluation/comparison.md` — ответы, sources и aggregate.

Десять основных cases сравнивают baseline/raw/enhanced. Три representative cases
имеют отдельный warm-up и три измеряемых повтора. Один retrieval context
замораживается для всех generation providers внутри case/repetition. Отчёт
содержит p50/p95/range latency, success/error/timeout, verified citation rate и
стабильность chunk IDs. Correctness, completeness, groundedness и support остаются
явными ручными оценками 0–2, а не выводятся по keywords.

OpenAI comparison по умолчанию записывается как `skipped`. Платная ветка допустима
только при настроенном ключе и явном `--allow-cloud`; отсутствие ключа не ломает
локальный прогон.

## Запись настоящего видео

При работающем production runtime на порту `18082` и уже созданном evaluation
report:

```bash
npm --prefix frontend run record:local-rag-demo
```

Скрипт выполняет оба настоящих вопроса, валидирует через `/api/state` локальные
provider/model, verified evidence, exact quotes и `estimatedCostUsd=null`, а затем
показывает report. Он пишет и через Chromium проверяет 1440×900 WebM:

[`video/local-rag-demo.webm`](../video/local-rag-demo.webm)

Существующий файл защищён. Осознанная перезапись:

```bash
LOCAL_RAG_DEMO_OVERWRITE=1 npm --prefix frontend run record:local-rag-demo
```

При наличии `ffprobe` можно независимо проверить metadata:

```bash
ffprobe -v error -show_entries format=duration,size \
  -show_entries stream=codec_name,width,height \
  -of default=noprint_wrappers=1 video/local-rag-demo.webm
```

## Проверенный прогон 6 октября 2026

На `arm64` MacBook M1 Pro с 32 ГБ unified memory подтверждены установленная
`qwen3:14b` и `qwen3-embedding:0.6b` (embedding length 1024), настоящий batch
`POST /api/embed` без Authorization и structure-aware индекс: 81 документ,
1578 chunks, 1024 измерения, manifest
`02d61b1aff2aa1f36b7539c32e78f71f926d3a642dbb0d1724b776d5293a9c9c`.

Полный `runRagEvaluation` завершился за 22 мин 7 с и атомарно записал report v4:

- 10/10 baseline completions, 6/10 raw и 6/10 enhanced веток прошли строгий
  citation/quote postflight; остальные RAG-ветки были честно сохранены как ошибки
  неточной цитаты/формата, а не засчитаны успешными;
- expected source найден в 7/10 raw и 7/10 enhanced cases;
- stability-серия: 9 измеряемых local runs, 5 successful, 4 postflight errors,
  0 timeouts; среди успешных verified citation/exact-quote rate — 100%;
- retrieved chunk-ID stability — 100%; latency p50 — 18,380 с, p95 — 31,892 с,
  диапазон — 8,494–31,892 с;
- local cost во всех provider runs и основных RAG-ветках — `null`/`н/д`;
- OpenAI `gpt-4.1-mini` — `skipped`, потому что платный cloud-прогон явно не
  разрешался; облачных вызовов не было;
- ручные correctness/completeness/groundedness/support оставлены `pending`, без
  выдуманных оценок.

Production web затем реально обработал два указанных выше вопроса. Скрипт
подтвердил у raw и enhanced веток `ollama/qwen3-embedding:0.6b`,
`ollama/qwen3:14b`, непустые ответы, verified citations, exact quotes, sources и
нулевую облачную стоимость. Во время evaluation наблюдались только loopback
соединения JVM ↔ `127.0.0.1:11434`; внешний LLM/embedding endpoint не использовался.

Настоящее видео `video/local-rag-demo.webm` проверено Chromium: 229,52 с,
1440×900, 16 109 060 байт. `ffprobe` на контрольной машине отсутствовал, поэтому
использован предусмотренный сценарием Chromium metadata check.

Fake/unit/browser-fixture проверки не считаются результатом real-local inference.

Отдельный воспроизводимый staged search temperature/max tokens/context/prompt и
финальный repeated A/B приведены в
[LOCAL_LLM_OPTIMIZATION.md](LOCAL_LLM_OPTIMIZATION.md).

## Граница жизненного цикла

Система полностью локальна, но не автономна от процессов: embeddings и generation
работают только пока жив Ollama, а HTTP UI/evaluation — пока жив соответствующий
JVM runtime. Остановка процесса не переносит выполнение в облако.
