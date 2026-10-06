# Локальная LLM: Ollama + Qwen3 14B

Демонстрация выполняет inference локально на Mac и не использует платный или
облачный LLM API. Штатная конфигурация проекта: Ollama, `qwen3:14b`,
`http://127.0.0.1:11434`; Workbench обращается только к фиксированному endpoint
`/v1/chat/completions` без `Authorization`.

## Требования

- Apple Silicon; проверенный стенд — MacBook M1 Pro с 32 ГБ unified memory;
- не менее 15 ГБ свободного места для Ollama, модели и временных файлов;
- JDK 21+, Node.js 22.12+ и Chromium для Playwright-видеозаписи.

Qwen3 14B в официальной Q4_K_M-сборке занимает около 9,3 ГБ, оставляя на машине
с 32 ГБ достаточный запас для runtime, KV cache, JVM и браузера. Резервная
`qwen3:8b` нужна только при фактической ошибке запуска 14B; проверенный прогон ниже
выполнен на 14B без fallback.

## Установка, запуск и загрузка модели

```bash
brew install ollama
OLLAMA_HOST=127.0.0.1:11434 OLLAMA_FLASH_ATTENTION=1 OLLAMA_KV_CACHE_TYPE=q8_0 ollama serve
```

Оставьте процесс запущенным и в другом терминале выполните:

```bash
ollama pull qwen3:14b
ollama list
ollama show qwen3:14b
```

Перед отдельным запуском проверяйте владельца порта и не останавливайте чужой
процесс:

```bash
lsof -nP -iTCP:11434 -sTCP:LISTEN
```

## CLI и native HTTP smoke-check

```bash
ollama run qwen3:14b --think=false --hidethinking \
  'Ответь одним коротким предложением: что такое локальная LLM?'
```

```bash
curl --fail --silent --show-error http://127.0.0.1:11434/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3:14b","stream":false,"think":false,"messages":[{"role":"user","content":"Сколько будет 7 * 8? Ответь кратко."}]}'
```

## Воспроизводимая проверка проекта

```bash
./gradlew runLocalLlmDemo
```

Команда последовательно отправляет три streaming-запроса через фиксированный
OpenAI-compatible endpoint, печатает сложность, модель, длительность и ответ.
Она завершается с ненулевым кодом, если сервис/модель недоступны, HTTP/SSE поток
оборвался, имя модели не совпало или ответ пуст.

Локальный адаптер передаёт `reasoning_effort=none`: иначе включённое по умолчанию
thinking Qwen3 может израсходовать весь `max_tokens` до появления видимого ответа.
Это не меняет поведение OpenAI и DeepSeek.

Проверенные запросы:

1. определение локальной LLM одним предложением;
2. `3 × 12 − 17` с вычислением и итогом;
3. Kotlin `uniqueSorted`, сложность и два граничных примера.

## Запуск LLM Workbench

Без изменения пользовательского `.env`:

```bash
WEB_PORT=18081 \
llm_kind=Ollama \
llm_model=qwen3:14b \
response_mode=unrestricted \
history_enabled=false \
context_strategy=SLIDING_WINDOW \
assistant_rag_enabled=false \
stop_sequence=off \
max_tokens=900 \
./gradlew runWeb
```

Откройте `http://127.0.0.1:18081`. В начале должны быть видны
**Ollama (локально)** и `qwen3:14b`; форма API-ключа отсутствует. MCP tools для
этого провайдера не экспонируются, сравнение GPT-5.6 по-прежнему переключает
провайдер на OpenAI. Для локальной модели облачная API-стоимость отсутствует;
token usage отображается по данным Ollama, а неизвестный ценовой профиль не
подменяется облачным тарифом.

## Запись и проверка видео

При работающем web runtime на порту `18081`:

```bash
npm --prefix frontend run record:local-llm-demo
# Скрипт сам проверяет ненулевой размер, длительность и разрешение через Chromium.
# При наличии ffprobe можно выполнить дополнительную независимую проверку:
ffprobe -v error -show_entries format=duration,size \
  -show_entries stream=codec_name,width,height \
  -of default=noprint_wrappers=1 video/local-llm-demo.webm
```

Скрипт использует настоящий Ollama/Qwen, timeout 10 минут на запрос и пишет
1440×900 WebM. Существующий файл защищён; осознанная перезапись:

```bash
LOCAL_LLM_DEMO_OVERWRITE=1 npm --prefix frontend run record:local-llm-demo
```

Готовый файл: [`video/local-llm-demo.webm`](../video/local-llm-demo.webm).

## Результаты

Контрольный прогон выполнен 5 октября 2026 года на `arm64` MacBook M1 Pro с
32 ГБ памяти, Ollama 0.35.1 и `qwen3:14b`. `ollama show` подтвердил 14,8 млрд
параметров, Q4_K_M, размер 9,3 ГБ и context length 40960; fallback не применялся.

- CLI: русское определение локальной LLM получено за 10,87 с с учётом cold start.
- Native `POST /api/chat`: ответ `56.`, `done=true`, 0,631 с.
- `runLocalLlmDemo`, простой запрос: 4,887 с, непустое определение одним
  предложением.
- `runLocalLlmDemo`, структурированный запрос: 4,624 с, вычисление
  `3 × 12 = 36`, `36 − 17 = 19` и правильный итог.
- `runLocalLlmDemo`, кодовый запрос: 35,202 с, функция на Kotlin через
  `toSet().sorted()`, оценка `O(n + m log m)` и граничные примеры.

Все три ответа получены реальным streaming-вызовом локальной модели. Обычные
unit/API/browser-тесты отдельно используют только MockEngine, fake client и
fixture и не засчитываются как этот inference.

Итоговое видео `video/local-llm-demo.webm` записано через production web runtime
с настоящей Ollama. Проверенные Chromium metadata: 87,24 с, 1440×900,
6 571 725 байт.

## Остановка

Остановите web runtime и запущенный вручную `ollama serve` через `Ctrl+C` в их
терминалах. Если Ollama запущена как Homebrew service, используйте
`brew services stop ollama`.

## Ограничения

- первая генерация после загрузки модели медленнее из-за cold start;
- скорость и формулировки зависят от версии Ollama, температуры и нагрузки;
- обычный test suite использует MockEngine/fake/fixture и не загружает реальную
  модель; real inference запускается только явной demo-командой и видеоскриптом;
- произвольный base URL намеренно не поддерживается: это сохраняет loopback-only
  границу и не создаёт SSRF-настройку.
