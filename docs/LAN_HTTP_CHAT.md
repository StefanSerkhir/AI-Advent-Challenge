# Чат по домашней Wi-Fi-сети через HTTP

> **Небезопасный opt-in профиль.** В этом варианте нет TLS. Basic Auth кодирует
> `username:password` в Base64, но не шифрует их: участник сети, скомпрометированный
> роутер или другая точка на маршруте может перехватить пароль и содержимое чата.
> Используйте профиль только временно в собственной доверенной и изолированной
> Wi-Fi-сети. Предпочтительный вариант — [Tailscale Serve](TAILSCALE_CHAT.md).

Профиль нужен для телефона без Tailscale и без установки CA-сертификата. Он не
меняет безопасные bind приложения: Workbench остаётся на `127.0.0.1:8080`,
Ollama — на `127.0.0.1:11434`. Отдельный Nginx слушает один точный RFC1918-адрес
компьютера и проксирует только Workbench UI/API:

```text
телефон в той же Wi-Fi-сети
  │ HTTP + Basic Auth: пароль и чат не зашифрованы
  ▼
Nginx 192.168.x.x:18080
  │ Authorization удаляется, Host/Origin нормализуются
  ▼
Workbench 127.0.0.1:8080 ──► Ollama 127.0.0.1:11434
```

Прямой Ollama/OpenAI-compatible API этим профилем не публикуется. Renderer
отклоняет wildcard, loopback, CGNAT/Tailscale, публичный IP и привилегированный
порт ниже 1024. Это уменьшает вероятность случайной публикации, но не делает
HTTP-трафик конфиденциальным.

## Запуск на macOS

Установите Nginx и убедитесь, что Workbench/Ollama уже работают только локально:

```bash
brew install nginx
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:11434 -sTCP:LISTEN
ipconfig getifaddr en0
```

Не запускайте Homebrew service с его стандартным конфигом: default server тоже
занимает `8080`. Для этого профиля используется отдельный standalone config и
prefix. Создайте внешний runtime-каталог; следующие команды используют пример
`/Users/you/Library/Application Support/LLM Workbench LAN`:

```bash
mkdir -p "/Users/you/Library/Application Support/LLM Workbench LAN/logs"
cp deploy/private-llm/lan-http.env.example \
  "/Users/you/Library/Application Support/LLM Workbench LAN/lan-http.env"
```

В `lan-http.env` укажите текущий Wi-Fi IP и внешний файл credentials:

```env
LAN_LLM_LISTEN=192.168.1.20:18080
LAN_LLM_HTPASSWD=/Users/you/Library/Application Support/LLM Workbench LAN/gateway.htpasswd
LAN_LLM_RATE=4r/s
LAN_LLM_BURST=8
LAN_LLM_MAX_BODY_SIZE=256k
```

Создайте отдельный пароль, который не используется ни в одном другом сервисе.
Homebrew Nginx на macOS проверяет пароль через системный `crypt()`, который не
поддерживает созданный `htpasswd -B` bcrypt-хеш. Поэтому этот macOS-профиль
использует совместимый salted `apr1` (`-m`); открытый пароль в файл не попадает:

```bash
htpasswd -cm "/Users/you/Library/Application Support/LLM Workbench LAN/gateway.htpasswd" llm-phone
chmod 0600 "/Users/you/Library/Application Support/LLM Workbench LAN/gateway.htpasswd"
```

`apr1` слабее bcrypt при офлайн-подборе украденного файла, что является ещё одной
причиной хранить файл с `0600`, применять уникальный пароль и включать gateway
только временно. На Linux TLS-профиль продолжает использовать `htpasswd -B`.

Сгенерируйте и проверьте конфигурацию:

```bash
python3 deploy/private-llm/render_lan_http_config.py \
  --environment "/Users/you/Library/Application Support/LLM Workbench LAN/lan-http.env" \
  --output "/Users/you/Library/Application Support/LLM Workbench LAN/nginx.conf"

/opt/homebrew/opt/nginx/bin/nginx -t \
  -p "/Users/you/Library/Application Support/LLM Workbench LAN/" \
  -c "/Users/you/Library/Application Support/LLM Workbench LAN/nginx.conf"
```

Запустите standalone Nginx, не меняя глобальный Homebrew config:

```bash
/opt/homebrew/opt/nginx/bin/nginx \
  -p "/Users/you/Library/Application Support/LLM Workbench LAN/" \
  -c "/Users/you/Library/Application Support/LLM Workbench LAN/nginx.conf"
```

macOS может запросить разрешение принимать входящие соединения для Nginx. В
firewall разрешайте только этот бинарник; роутер не должен публиковать порт
`18080` через port forwarding/UPnP.

## Подключение телефона

Телефон должен находиться в той же доверенной Wi-Fi-сети. Откройте адрес из
`LAN_LLM_LISTEN`, например:

```text
http://192.168.1.20:18080
```

Браузер покажет предупреждение «Not Secure», затем Basic Auth prompt. Введите
username `llm-phone` и созданный пароль. В UI выберите **Ollama (локально)** →
`qwen3:14b`; API-ключ не требуется. Обычный HTTP не предоставляет браузерный
`crypto.randomUUID()`, поэтому frontend создаёт UUID v4 через доступный в этом
контексте `crypto.getRandomValues()`; старый браузер без Web Crypto использует
резервный генератор только для идемпотентного `requestId`.

Проверка с другого устройства:

```bash
# без credentials ожидается 401
curl --output /dev/null --write-out '%{http_code}\n' \
  http://192.168.1.20:18080/api/state

# пароль вводится интерактивно; ожидается 200
curl --user llm-phone --output /dev/null --write-out '%{http_code}\n' \
  http://192.168.1.20:18080/api/state
```

Не передавайте пароль аргументом `curl --user user:password` или через
`htpasswd -b`: он может попасть в history или список процессов. Сам сетевой
перехват этот совет не предотвращает — для него нужен HTTPS/Tailscale.

## Остановка и смена сети

Останавливайте LAN gateway сразу после использования:

```bash
/opt/homebrew/opt/nginx/bin/nginx \
  -p "/Users/you/Library/Application Support/LLM Workbench LAN/" \
  -c "/Users/you/Library/Application Support/LLM Workbench LAN/nginx.conf" \
  -s quit
```

При смене Wi-Fi адрес компьютера может измениться. Nginx fail-closed не запустится
на отсутствующем IP: обновите `LAN_LLM_LISTEN`, заново выполните renderer и
`nginx -t`. Не используйте этот профиль в гостевой, гостиничной, офисной или
общественной сети и не заменяйте точный IP на `0.0.0.0`.

## Что именно защищено

- Basic Auth ограничивает случайный доступ, но не защищает credentials от
  перехвата и повторного использования.
- Nginx удаляет `Authorization` перед Workbench, не пишет access log и применяет
  `4r/s`, burst `8`, body limit `256k`.
- Gateway нормализует Host/Origin после Basic Auth, удаляет недоверенные
  Tailscale identity headers и сохраняет браузерный `Sec-Fetch-Site`; мутации
  по-прежнему требуют штатный CSRF-заголовок Workbench.
- Ollama и Ktor не открываются напрямую в Wi-Fi-сеть.
- Конфигурация не добавляется в автозапуск намеренно: небезопасный listener
  должен включаться только на время использования.
