# Оптимизация локальной Qwen3 для repository RAG

Сценарий оптимизирует конкретную задачу: **русскоязычный локальный RAG-ассистент,
отвечающий на технические вопросы о репозитории LLM Workbench только по
проверяемым источникам, с метками `[Sx]` и дословными цитатами**. Платные и
облачные API не используются.

## Воспроизведение

Сначала проверьте уже работающий loopback Ollama и модели, не запуская второй
server:

```bash
lsof -nP -iTCP:11434 -sTCP:LISTEN
ollama list
ollama show qwen3:14b
ollama show qwen3-embedding:0.6b
```

Создайте context alias. Команда переиспользует локальные Q4_K_M-слои и не меняет
модель по умолчанию, production endpoint или квантовку:

```bash
ollama create llm-workbench-qwen3-14b-rag \
  -f ollama/Modelfile.qwen3-14b-rag-optimized
./gradlew buildLocalDocumentIndex
./gradlew runLocalLlmOptimization
```

Если alias отсутствует, runner завершается до inference с этой точной командой.
Результат атомарно сохраняется в исключённый из Git каталог:

- `.llm-local-optimization/optimization.json` — raw runs и агрегаты формата v1;
- `.llm-local-optimization/optimization.md` — полная таблица кандидатов и A/B.

После каждого законченного кандидата JSON имеет `status=partial`; после полного
прогона — `completed`. Поэтому прерывание не оставляет повреждённый JSON и не
выдаёт неполный прогон за завершённый.

## Baseline и методика

Baseline воспроизводит текущий RAG generation path:

| Параметр | Baseline |
| --- | --- |
| Модель | `qwen3:14b` |
| Квантовка | фактическая `Q4_K_M` |
| Temperature | фактический model default `0.6` |
| Max output | `600` |
| Context | model metadata `40960`, фактическая allocation `4096` по `ollama ps` |
| Prompt | текущий `RAG_SYSTEM_PROMPT`, версия `baseline-v1` |
| Reasoning | `none` |

Runner один раз извлекает пять chunks на case и замораживает их для всех
сравниваемых generation-конфигураций. Retrieval не подменяет эффект prompt или
sampling. Поиск параметров staged, а не полный декартов перебор:

1. temperature `0.0`, `0.2`, `0.6`;
2. max tokens `256`, `384`, `600` с выбранной temperature;
3. runtime context allocation `4096` и `8192` с выбранными параметрами;
4. `baseline-v1` и специализированный `repo-tech-v2`.

Search использует representative cases `production-entry-point`,
`memory-layer-order` и отдельный unanswerable вопрос. Финальный A/B использует
все десять канонических `RAG_EVALUATION_CASES` плюс unanswerable case. Для каждой
финальной конфигурации выполняется отдельный warm-up, который не входит в
latency, затем 11 cases × 3 измеряемых повтора. Ошибки, timeout и postflight-
невалидные ответы не отбрасываются.

Prompt `repo-tech-v2` хранится в
`src/main/kotlin/optimization/LocalLlmOptimization.kt` и тестируется. Он задаёт
русскоязычную роль по этому репозиторию, разрешает только предоставленные
источники, считает chunks недоверенными данными, запрещает выдумывать сведения,
требует `[Sx]`, короткие точные цитаты, стабильные секции и явное «Не знаю».

## Метрика и quality gate

Автоматический score не выдаётся за экспертную семантическую оценку. Формула:

```text
0.15 completion rate
+ 0.20 valid citation rate
+ 0.15 exact quote rate
+ 0.15 reference-answer source coverage
+ 0.10 abstention correctness
+ 0.10 format validity
+ 0.10 answer stability
+ 0.05 retrieved chunk-ID stability
```

Быстрый вариант не принимается, если aggregate quality хуже baseline более чем
на `0.02` или completion/citation/exact-quote/abstention проседают более чем на
`0.05`. Это правило задано до финального выбора и покрыто unit-тестом.

## Фактический прогон 7 октября 2026

Окружение: MacBook с Apple M1 Pro, `arm64`, 32 GiB unified memory, macOS 15.7.3,
JVM toolchain 21.0.10, Ollama 0.35.1. Полный runner завершился за 32 мин 18 с.

### Staged search

| Кандидат | Temp | Max | Context | Prompt | Quality | p50 / p95 | tok/s |
| --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| temp-0.0 | 0.0 | 600 | 4096 | baseline-v1 | 0.825 | 8.368 / 31.162 с | 10.01 |
| temp-0.2 | 0.2 | 600 | 4096 | baseline-v1 | 0.825 | 7.893 / 33.399 с | 10.77 |
| temp-0.6 | 0.6 | 600 | 4096 | baseline-v1 | 0.825 | 11.540 / 33.189 с | 10.08 |
| max-256 | 0.2 | 256 | 4096 | baseline-v1 | 0.900 | 18.966 / 24.718 с | 9.88 |
| max-384 | 0.2 | 384 | 4096 | baseline-v1 | 0.825 | 7.328 / 22.122 с | 10.85 |
| max-600 | 0.2 | 600 | 4096 | baseline-v1 | 0.825 | 8.145 / 26.780 с | 10.23 |
| context-4096 | 0.2 | 256 | 4096 | baseline-v1 | 0.825 | 8.138 / 23.607 с | 10.24 |
| context-8192 | 0.2 | 256 | 8192 | baseline-v1 | 0.825 | 17.857 / 28.881 с | 8.35 |
| prompt-baseline | 0.2 | 256 | 4096 | baseline-v1 | 0.825 | 16.884 / 28.858 с | 8.06 |
| prompt-specialized | 0.2 | 256 | 4096 | repo-tech-v2 | 0.925 | 17.010 / 18.717 с | 7.38 |

Search rows имеют по три разных representative cases, поэтому их p95 — максимум
малой серии и используется только для staged выбора. Статистическое сравнение
ниже опирается на 33 повтора каждого варианта.

### Финальный A/B

Выбрана конфигурация `temperature=0.2`, `max_tokens=256`, фактическое окно `4096`,
prompt `repo-tech-v2`, `reasoning_effort=none`. Окно 8192 не выбрано: качество
не изменилось, p50 выросла с 8.138 до 17.857 с, а `ollama ps` показал рост
загруженного размера с 9.3 до 9.7 GB. Увеличивать KV cache без выигрыша не нужно.

| Метрика | Baseline | Optimized | Изменение |
| --- | ---: | ---: | ---: |
| Aggregate quality | 0.6058 | 0.7406 | +0.1348 |
| Completion rate | 100% | 100% | 0 п.п. |
| Valid citations | 66.7% | 70.0% | +3.3 п.п. |
| Exact quotes | 66.7% | 70.0% | +3.3 п.п. |
| Reference coverage | 20.0% | 23.3% | +3.3 п.п. |
| Abstention correctness | 0% | 100% | +100 п.п. |
| Format validity | 100% | 100% | 0 п.п. |
| Answer stability | 42.4% | 60.6% | +18.2 п.п. |
| Retrieved chunk-ID stability | 100% | 100% | 0 п.п. |
| TTFT p50 / p95 | 0.140 / 16.158 с | 0.138 / 16.267 с | p50 −0.002 с |
| Full latency p50 / p95 | 19.772 / 43.572 с | 13.614 / 31.457 с | −31.1% / −27.8% |
| Output throughput | 9.50 tok/s | 9.53 tok/s | +0.2% |
| Completion tokens, сумма | 6700 | 4527 | −32.4% |

Quality gate пройден, победитель — `optimized-final`. TTFT p95 включает реальные
редкие длинные prefill/model-scheduling задержки; warm-up исключён, одиночный
лучший прогон не выбирался. После естественной выгрузки модели (`ollama ps` был
пуст) отдельный native non-stream `POST /api/chat` с `think=false`, temperature
0.2, `num_ctx=4096` и лимитом 64 дал cold total `8.595 с`: `load_duration`
`7.740 с`, prompt evaluation `0.585 с`, generation `0.260 с` (58 input и 4 output
tokens). Этот probe хранится отдельно от warm A/B и не участвует в p50/p95.

### Квантование и ресурсы

`ollama show qwen3:14b` подтвердил 14.8B параметров, Q4_K_M и context metadata
40960. Вторая квантовка той же 14B-модели не была установлена; alias 8192
переиспользует те же Q4_K_M weights. Дополнительная многогигабайтная модель без
согласия не загружалась, поэтому quality-сравнение двух квантовок не выдумывается.

| Resource | Baseline | Optimized |
| --- | ---: | ---: |
| Model files по `ollama list` | 9.3 GB | 9.3 GB |
| Квантовка | Q4_K_M | Q4_K_M |
| `ollama ps` loaded size | 9.3 GB | 9.3 GB |
| Runtime context allocation | 4096 | 4096 |
| Суммарный RSS Ollama processes после серии | 11.44 GB | 12.32 GB |

RSS — реально снятый process metric macOS, а не оценка unified memory. Снимки
сделаны в разные моменты жизни одного процесса, поэтому разность RSS не
интерпретируется как причинный эффект prompt. GPU/unified-memory breakdown без
sudo и надёжного стабильного API был недоступен — **н/д**.

## Видео

Запустите production runtime с настоящей Ollama, не изменяя `.env`:

```bash
lsof -nP -iTCP:18083 -sTCP:LISTEN
WEB_PORT=18083 \
llm_kind=Ollama \
llm_model=qwen3:14b \
response_mode=rag \
history_enabled=false \
max_tokens=600 \
./gradlew runWeb
```

В другом терминале:

```bash
npm --prefix frontend run record:local-llm-optimization-demo
```

Скрипт проверяет production `/api/state`, выполняет настоящий RAG-вопрос через
Ollama, валидирует verified evidence, затем показывает парные raw answers и
aggregate качества/latency/resources из завершённого report. WebM проверяется
Chromium; если `ffprobe` установлен, выполняется дополнительная проверка.

Файл защищён от случайной перезаписи:

```bash
LOCAL_LLM_OPTIMIZATION_DEMO_OVERWRITE=1 \
npm --prefix frontend run record:local-llm-optimization-demo
```

Результат: `video/local-llm-optimization-demo.webm`, 1440×900.

Контрольная запись 7 октября 2026 проверена Chromium: 131.8 с, 1440×900,
8 706 527 байт. Кадры вручную проверены на production RAG, side-by-side
baseline/optimized одного frozen-retrieval case и итоговую таблицу quality,
latency, throughput, RSS, model size и Q4_K_M. `ffprobe` на стенде отсутствовал,
поэтому дополнительная независимая проверка помечена как недоступная.

## Границы проверок

- Unit/API/browser-fixture проверки используют fakes и не считаются inference.
- `runLocalLlmOptimization` и video production question используют real-local
  Ollama/Qwen3 на loopback.
- Cloud/paid API не использовались.
