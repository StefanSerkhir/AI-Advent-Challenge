# Приватный LLM-сервис: systemd + Nginx + Ollama

Этот профиль превращает локальную Ollama в сетевой приватный AI-сервис, не
открывая нативный порт модели. Основной поддерживаемый вариант — Linux-сервер с
systemd, приватным адресом Tailscale/RFC1918 и Nginx. Workbench остаётся web-чатом,
а HTTP-клиенты получают защищённый OpenAI-compatible
`POST /v1/chat/completions`.

Если нужен только личный web-чат с телефона, без сетевого raw API, отдельного
Basic Auth и ручного управления сертификатом, используйте более узкий профиль
[Tailscale Serve](TAILSCALE_CHAT.md). Он оставляет Workbench/Ollama на loopback и
использует Tailscale identity; описанный ниже Nginx-профиль остаётся вариантом
для OpenAI-compatible API, server-side rate limit и независимых credentials.

```text
клиент приватной сети
  │ HTTPS + Basic Auth, 4 r/s, burst 8
  ▼
Nginx на приватном IP:8443
  ├── /                    ──► 127.0.0.1:8080  Workbench UI
  ├── /api/*               ──► 127.0.0.1:8080  Ktor REST/SSE
  ├── /v1/chat/completions ──► 127.0.0.1:11434 Ollama OpenAI API
  └── /private/ollama/*    ──► allowlist диагностических Ollama API
```

Оба upstream остаются loopback-only. Renderer не принимает `0.0.0.0`, loopback
или публичный адрес для Nginx: только RFC1918 или Tailscale/CGNAT. Пароль не
хранится в репозитории или env-примере: Nginx читает внешний `htpasswd`.

## Threat model

Профиль защищает от прямого доступа к Ollama/Ktor из LAN/Internet, запросов без
учётных данных, перехвата трафика, случайного публичного bind, простого burst-DoS
и передачи Basic Auth в upstream. TLS обязателен даже внутри VPN. Ktor продолжает
проверять loopback Host, JSON и `X-Workbench-Request`; gateway нормализует только
Host/Origin/fetch-metadata после собственной TLS/auth границы и не включает CORS.

Профиль не защищает от root на сервере, компрометации клиентского устройства,
утечки пароля самим пользователем, вредоносного prompt или длительной нагрузки от
уже авторизованного клиента в пределах лимита. Basic Auth не имеет per-user
квот; для разных trust domains нужны отдельные gateway instances/credentials.
Nginx access log содержит адрес, путь и статус, но не тело и не пароль.

## Состав артефактов

- `deploy/private-llm/systemd/llm-workbench.service` — Workbench из
  `/opt/llm-workbench`, состояние в `/var/lib/llm-workbench`, bind остаётся
  `127.0.0.1:8080`;
- `deploy/private-llm/systemd/ollama.service.d/10-private-loopback.conf` —
  `OLLAMA_HOST=127.0.0.1:11434` и воспроизводимый runtime context `4096`;
- `deploy/private-llm/nginx/private-llm.conf.template` — TLS/auth/rate limit,
  OpenAI route, web UI и streaming без buffering;
- `deploy/private-llm/render_nginx_config.py` — fail-closed renderer;
- `deploy/private-llm/verify_private_llm.py` — реальная проверка сети, API,
  concurrency, rate limit и context boundary;
- `frontend/scripts/record-private-llm-service-demo.mjs` — запись реального UI и
  итогов verifier в `video/private-llm-service-demo.webm`.

## Учётные данные и проверка Basic Auth

Логин и пароль не выдаются Ollama, Workbench или репозиторием. Их создаёт
администратор непосредственно на целевом Linux-сервере:

1. Username выбирается администратором; `llm-user` ниже — только пример.
2. `htpasswd` дважды запрашивает пароль в терминале и с `-B` записывает его
   bcrypt-хеш. Открытый пароль в файл, Git или env не попадает.
3. Файл по умолчанию находится на сервере по адресу
   `/etc/llm-workbench/gateway.htpasswd`. Переменная `PRIVATE_LLM_HTPASSWD` в
   `/etc/llm-workbench/private-llm.env` содержит только этот абсолютный путь.
4. Renderer подставляет путь в директиву Nginx
   `auth_basic_user_file`; файл не копируется внутрь конфигурации Nginx.

При запросе браузер или HTTP-клиент отправляет заголовок Basic Auth. Его payload
— это base64-представление `username:password`, а не шифрование, поэтому
использовать gateway без HTTPS нельзя. Nginx выполняет проверку до обращения к
Workbench или Ollama: находит username в `htpasswd`, сверяет введённый пароль с
сохранённым bcrypt-хешем и возвращает `401 Unauthorized`, если заголовка нет или
проверка не прошла. При успехе запрос проксируется, но внешний `Authorization`
удаляется и не передаётся loopback-сервисам.

Первичное создание файла выполняется интерактивно:

```bash
sudo htpasswd -cB /etc/llm-workbench/gateway.htpasswd llm-user
```

Ключ `-c` означает «создать новый файл» и применяется только при первой
настройке. Повторный запуск с `-c` перезапишет файл и удалит остальных
пользователей. Для смены пароля существующего username или добавления нового
пользователя `-c` не нужен; команда сама различает эти случаи:

```bash
# сменить пароль существующего llm-user
sudo htpasswd -B /etc/llm-workbench/gateway.htpasswd llm-user

# добавить ещё одного пользователя
sudo htpasswd -B /etc/llm-workbench/gateway.htpasswd another-user

# удалить пользователя
sudo htpasswd -D /etc/llm-workbench/gateway.htpasswd another-user
```

Не используйте `htpasswd -b`: переданный аргументом пароль может попасть в
shell history и список процессов. После изменений файл должен оставаться
доступным только root и системной группе Nginx. На Debian/Ubuntu эта группа
обычно называется `www-data`, на RHEL-подобных системах — `nginx`; каталог
`/etc/llm-workbench` должен разрешать этой группе проход к файлу.

## Установка на Linux-сервер

Требования: JRE 21+, Ollama, Nginx, Python 3.11+, `apache2-utils` (`htpasswd`),
TLS-сертификат для приватного DNS-имени и уже настроенная приватная сеть.
Для Tailscale используйте Tailscale IP `100.64.0.0/10`; firewall должен разрешать
gateway-порт только на интерфейсе Tailscale. Репозиторий не меняет firewall и не
устанавливает сервисы автоматически.

Соберите distribution на машине разработки:

```bash
./gradlew installDist
sudo useradd --system --home /var/lib/llm-workbench --shell /usr/sbin/nologin llm-workbench
sudo install -d -o llm-workbench -g llm-workbench /var/lib/llm-workbench
sudo test ! -e /opt/llm-workbench
sudo cp -a build/install/AIAdventChallenge /opt/llm-workbench
sudo install -d -m 0750 /etc/llm-workbench /etc/llm-workbench/tls
```

Команды выше рассчитаны на первую установку и не перезаписывают существующий
`/opt/llm-workbench`. Для обновлений используйте versioned release-каталоги и
атомарный symlink; рабочее состояние находится отдельно в `/var/lib`.

Создайте внешние конфигурации без реальных секретов в Git:

```bash
sudo cp deploy/private-llm/workbench.env.example /etc/llm-workbench/workbench.env
sudo cp deploy/private-llm/private-llm.env.example /etc/llm-workbench/private-llm.env
sudo chmod 0640 /etc/llm-workbench/*.env

# Debian/Ubuntu: www-data; RHEL-подобные системы: nginx
NGINX_GROUP=www-data
getent group "$NGINX_GROUP"
sudo chgrp "$NGINX_GROUP" /etc/llm-workbench
sudo chmod 0750 /etc/llm-workbench
sudo htpasswd -cB /etc/llm-workbench/gateway.htpasswd llm-user
sudo chown root:"$NGINX_GROUP" /etc/llm-workbench/gateway.htpasswd
sudo chmod 0640 /etc/llm-workbench/gateway.htpasswd
```

Команда `htpasswd` попросит ввести и повторить новый пароль; `llm-user` станет
логином. Значение `NGINX_GROUP` должно совпадать с группой worker-процессов вашей
установки Nginx. Реальный файл создаётся только этой командой на сервере: в
репозитории есть лишь путь-пример, но нет файла с учётными данными.

Отредактируйте `PRIVATE_LLM_LISTEN`, приватное DNS-имя и абсолютные TLS paths.
Сертификат можно получить через `tailscale cert <private-name>` или внутренний CA;
CA должен быть доверен клиентом. Не используйте self-signed сертификат с
отключённой проверкой TLS.

Сначала проверьте занятость портов. Не останавливайте неизвестный процесс:

```bash
sudo lsof -nP -iTCP:11434 -sTCP:LISTEN
sudo lsof -nP -iTCP:8080 -sTCP:LISTEN
sudo lsof -nP -iTCP:8443 -sTCP:LISTEN
```

Установите drop-in и unit, затем сгенерируйте Nginx site:

```bash
sudo install -d /etc/systemd/system/ollama.service.d
sudo cp deploy/private-llm/systemd/ollama.service.d/10-private-loopback.conf \
  /etc/systemd/system/ollama.service.d/
sudo cp deploy/private-llm/systemd/llm-workbench.service /etc/systemd/system/

sudo python3 deploy/private-llm/render_nginx_config.py \
  --environment /etc/llm-workbench/private-llm.env \
  --output /etc/nginx/conf.d/private-llm.conf
sudo nginx -t
sudo systemctl daemon-reload
```

Загрузите модель до сетевого запуска и запустите сервисы:

```bash
sudo systemctl restart ollama
ollama pull qwen3:14b
sudo systemctl enable --now llm-workbench nginx
```

Проверка bind должна показывать Ollama/Workbench только на loopback, а Nginx —
только на выбранном приватном IP:

```bash
sudo ss -ltnp | grep -E ':(11434|8080|8443)\b'
systemctl --no-pager --full status ollama llm-workbench nginx
journalctl -u ollama -u llm-workbench -u nginx --since -10m --no-pager
```

Остановка и повторный запуск:

```bash
sudo systemctl stop nginx llm-workbench ollama
sudo systemctl start ollama llm-workbench nginx
```

## HTTP и web-чат

Не записывайте пароль в shell history; пример ниже ожидает уже экспортированные
переменные. Для внутреннего CA добавьте `--cacert "$PRIVATE_LLM_CA_FILE"`:

```bash
export PRIVATE_LLM_BASE_URL=https://llm.private.example:8443
export PRIVATE_LLM_USERNAME=llm-user
read -rsp 'Private LLM password: ' PRIVATE_LLM_PASSWORD; export PRIVATE_LLM_PASSWORD; echo

curl --fail --silent --show-error \
  --user "$PRIVATE_LLM_USERNAME:$PRIVATE_LLM_PASSWORD" \
  "$PRIVATE_LLM_BASE_URL/private/health"

curl --fail --silent --show-error \
  --user "$PRIVATE_LLM_USERNAME:$PRIVATE_LLM_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3:14b","stream":false,"reasoning_effort":"none","max_tokens":64,"messages":[{"role":"user","content":"Ответь кратко: сервис работает?"}]}' \
  "$PRIVATE_LLM_BASE_URL/v1/chat/completions"
```

Откройте тот же URL в браузере, введите Basic Auth и используйте обычный
Workbench chat. Browser storage пароль не получает: challenge обрабатывает сам
браузер/Nginx. Streaming для `/v1/chat/completions` и `/api/events` идёт с
`proxy_buffering off`, `proxy_request_buffering off`, `gzip off` и timeout 10 мин.

## Воспроизводимая реальная проверка

Запускайте с отдельного устройства, контейнера или network namespace. Если тест
идёт на том же сервере через его приватный адрес, явно оставьте значение
`same-host-private-address`: это ограниченное доказательство маршрута, не проверка
другого устройства.

```bash
export PRIVATE_LLM_BASE_URL=https://llm.private.example:8443
export PRIVATE_LLM_USERNAME=llm-user
read -rsp 'Private LLM password: ' PRIVATE_LLM_PASSWORD; export PRIVATE_LLM_PASSWORD; echo
export PRIVATE_LLM_NETWORK_EVIDENCE=separate-device
# export PRIVATE_LLM_CA_FILE=/path/to/private-ca.pem

python3 deploy/private-llm/verify_private_llm.py
```

Verifier требует HTTPS и DNS/IP, который разрешается только в RFC1918,
Tailscale/CGNAT или ULA. Он выполняет:

1. `401` без Basic Auth;
2. непустой non-streaming ответ именно `qwen3:14b`;
3. streaming до `[DONE]`;
4. warm-up и минимум 4 параллельных уникально маркированных запроса;
5. success/error, min/max, mean, p50/p95 latency;
6. burst из 16 health-запросов с ожидаемым `429`, затем `200` после 3 с;
7. версии Ollama, model digest/quantization и context metadata;
8. `/api/ps` runtime context и безопасные below/above probes;
9. gateway body limit: oversized body должен получить `413` до inference.

JSON-отчёт атомарно пишется в
`build/reports/private-llm-service.json` и не содержит password/Authorization.
Параллельные ответы считаются корректными только если они непусты, содержат свой
маркер и не содержат маркер другого запроса.

## Rate limit и четыре разных лимита

Gateway policy — `4r/s` на client IP с `burst=8` и `nodelay`; при переполнении
Nginx явно возвращает HTTP `429`. Значения отражаются в безопасном response header
`X-Private-LLM-Rate-Limit`. Через 3 секунды verifier ожидает восстановление.

Не смешивайте четыре независимые величины:

| Величина | Источник в профиле | Проверка |
| --- | --- | --- |
| Model context metadata | `/api/show`, ожидаемо 40960 для текущего Qwen3 | `metadataContextLength` |
| Фактический runtime context | `OLLAMA_CONTEXT_LENGTH=4096`, после load виден в `/api/ps` | `runtimeContextLength` |
| Максимальный HTTP body | Nginx `client_max_body_size 256k` | header + реальный HTTP 413 |
| Максимум output | поле запроса `max_tokens` | context probes используют 32 |

Below probe посылает около 75% выбранного безопасного runtime limit, above —
около 120%. Это оценка входа, не точный tokenizer. Отчёт сохраняет фактический
`usage.prompt_tokens`, статусы, наличие двух sentinels и дословное наблюдение.
Если Ollama принимает over-limit запрос, verifier сравнивает provider
`usage.prompt_tokens` с оценкой. При заметно меньшем usage отчёт фиксирует
`provider usage suggests input truncation`; без такого доказательства он честно
пишет, что детерминированного признака обрезки нет.

## Наблюдаемые результаты 8 октября 2026 года

Контрольный прогон выполнен на той же macOS-машине через её адрес
`172.20.10.2:18443`; это честно классифицировано как
`same-host-private-address`, а не проверка с другого устройства. Временный Nginx
1.31.6 был запущен из распакованного Homebrew bottle без установки сервиса;
строгая TLS-проверка использовала короткоживущий CA. Ollama и Workbench оставались
на `127.0.0.1:11434` и `127.0.0.1:8080`. Linux systemd unit на этом стенде не
запускался.

| Проверка | Наблюдение |
| --- | --- |
| Auth | без credentials `401`; с credentials health `200` |
| Ollama/model | Ollama `0.35.1`; `qwen3:14b`, 14.8B, Q4_K_M |
| Non-streaming API | `200`, 47 символов, 1 397.643 мс, prompt/completion 36/14 |
| Streaming API | завершён `[DONE]`, 41 символ, 1 096.573 мс |
| Параллельные запросы | 4/4 успешны, 0 ошибок и смешанных маркеров |
| Parallel latency | min 1 617.602; max/p95 6 090.597; p50 3 173.801; mean 3 890.961 мс |
| Rate burst | 16 запросов: 9×`200`, 7×`429`; после 3 с снова `200` |
| Gateway body | 270 151 байт при лимите `256k` → `413` |
| Context metadata/runtime | 40960 / 4096 tokens |
| Below probe | оценка 3072, provider input 3116, `200`, output упёрся в `max_tokens=32` |
| Above probe | оценка 4916, `200`, provider input 2050: usage свидетельствует об обрезке |

Отдельный `runLocalLlmDemo` на той же реальной модели дал 3/3 непустых streaming-
ответа за 10 434, 4 987 и 36 731 мс. Fake/integration gateway test отдельно
проверяет механику, но не засчитывается как эти результаты. Платные/облачные API
не вызывались.

## Видео

После успешного verifier запишите новый файл, не перезаписывая старые ролики:

```bash
npm --prefix frontend run record:private-llm-service-demo
ffprobe -v error -show_entries format=duration,size \
  -show_entries stream=codec_name,width,height \
  -of default=noprint_wrappers=1 video/private-llm-service-demo.webm
```

Сценарий сначала повторяет реальную сетевую проверку, затем записывает 1440×900:
приватный HTTPS URL, успешный web-чат с настоящей Ollama и сводку HTTP,
parallel latency, `429`, версии/context. Username/password в кадр и отчёт не
попадают. Существующий файл защищён; осознанная перезапись требует
`PRIVATE_LLM_DEMO_OVERWRITE=1`.

Контрольный файл `video/private-llm-service-demo.webm`: 2 774 305 байт,
42.24 с, 1440×900, WebM CodecID `V_VP8`. Размер, длительность и разрешение
проверены Chromium, контейнер и CodecID — отдельно по содержимому WebM.

Для короткоживущего лабораторного сертификата Chromium можно доверить ровно один
SPKI через `PRIVATE_LLM_CHROMIUM_SPKI_PIN` (base64 SHA-256 публичного ключа).
Это не отключает проверку TLS глобально и не требуется для сертификата,
доверенного ОС. Python verifier всё равно должен получить и проверить CA через
`PRIVATE_LLM_CA_FILE`.

## Безмодельные проверки и известные ограничения

```bash
./gradlew checkPrivateLlmDeployment
sudo nginx -t
```

Первая команда проверяет renderer, запрет public/wildcard bind, обязательные
TLS/auth/429/streaming директивы, percentile и раздельный разбор metadata/runtime
context без запуска Ollama. `nginx -t` возможен только после установки Nginx и
рендера site на целевой Linux-машине.

Реальные latency зависят от CPU/GPU, KV cache и cold start; 4 параллельных HTTP
запроса не означают четыре независимых GPU execution slots. Nginx rate limit
per-IP объединяет клиентов за одним NAT. Runtime жив только пока работают
systemd-сервисы. Этот профиль не выдаёт публичный multi-tenant API и не открывает
произвольные Ollama endpoints.
