# Личный чат с локальной LLM через Tailscale Serve

Этот профиль открывает существующий Workbench web-чат телефону или другому
личному устройству без публикации портов в Wi-Fi-сеть, отдельного пароля Basic
Auth и ручной установки TLS-сертификата. Tailscale автоматически выдаёт HTTPS-
сертификат для имени `device.tailnet.ts.net`, а доступ ограничивается tailnet и
его ACL/grants.

```text
телефон с Tailscale
  │ HTTPS, Tailscale identity и ACL
  ▼
Tailscale Serve:443
  │ HTTP только внутри компьютера
  ▼
Workbench 127.0.0.1:8080 ──► Ollama 127.0.0.1:11434
```

Этот путь публикует только Workbench UI, REST и SSE. Он не публикует прямой
OpenAI-compatible `/v1/chat/completions`; для такого API используйте
[Nginx-профиль](PRIVATE_LLM_SERVICE.md). Не используйте `tailscale funnel`:
Funnel делает endpoint публичным, а для личного чата нужен только Serve.
Временный вариант без Tailscale и без TLS для собственной Wi-Fi-сети описан
отдельно в [LAN_HTTP_CHAT.md](LAN_HTTP_CHAT.md); он не обеспечивает
конфиденциальность пароля или чата.

## Модель доверия

Workbench продолжает слушать только `127.0.0.1`. Доступ через Tailscale выключен
по умолчанию и включается явной переменной `WEB_TAILSCALE_HOST` с полным именем
узла вида `device.tailnet.ts.net`.

Для каждого tailnet-запроса backend требует одновременно:

- точное совпадение `Host`, `Origin` и `X-Forwarded-Host` с настроенным именем;
- `X-Forwarded-Proto: https`;
- непустой подтверждённый `Tailscale-User-Login`;
- прежние JSON/`X-Workbench-Request` и `Sec-Fetch-Site` проверки.

Tailscale Serve удаляет identity headers, присланные клиентом, и добавляет
собственные. Поэтому логином является Tailscale identity пользователя, а
отдельный пароль Workbench не нужен. Любой пользователь, которому ACL/grants или
device sharing разрешают доступ к узлу, получает доступ и к чату; проверьте
состав tailnet перед запуском. Tagged devices не получают `Tailscale-User-Login`
и этим профилем отклоняются.

## Первая настройка на macOS и телефоне

1. Установите Tailscale на Mac из официального standalone package или Mac App
   Store, пройдите системное добавление VPN и войдите в свой tailnet.
2. Установите приложение Tailscale на iOS/Android, войдите в тот же tailnet и
   включите VPN-подключение.
3. Убедитесь на Mac, что узел подключён и получил полное MagicDNS-имя:

```bash
tailscale status
tailscale ip -4
tailscale status --json |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))'
```

Если команда не выводит имя `*.ts.net`, завершите Tailscale onboarding и
включите MagicDNS/HTTPS в tailnet. При первом `tailscale serve` Tailscale может
открыть web-страницу согласия для включения HTTPS.

## Запуск чата

Сначала проверьте, что не запускаете второй экземпляр и что локальная модель
установлена:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:11434 -sTCP:LISTEN
ollama show qwen3:14b
```

Ollama должна слушать только `127.0.0.1:11434`. Получите имя Tailscale и
запустите production Workbench:

```bash
export WEB_TAILSCALE_HOST="$(
  tailscale status --json |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))'
)"
./gradlew runWeb
```

Оставьте процесс Workbench работающим. В другом терминале создайте приватный
HTTPS reverse proxy на его loopback-порт:

```bash
tailscale serve --bg --https=443 --yes http://127.0.0.1:8080
tailscale serve status
```

Команда `serve status` покажет URL вида
`https://device.tailnet-name.ts.net`. На телефоне включите Tailscale и откройте
этот URL в браузере. В Workbench выберите **Ollama (локально)** → `qwen3:14b`;
поле API-ключа не требуется. Телефон является только UI-клиентом: inference,
модель и состояние остаются на Mac.

## Проверка и остановка

С другого устройства в том же tailnet можно проверить UI и API:

```bash
curl --fail --show-error --silent "https://$WEB_TAILSCALE_HOST/" >/dev/null
curl --fail --show-error --silent "https://$WEB_TAILSCALE_HOST/api/state" >/dev/null
```

Запрос через обычный Wi-Fi IP `192.168.x.x:8080` не должен подключаться, потому
что Workbench не меняет loopback bind. Запрос к tailnet URL без активного
Tailscale/разрешения ACL также не должен проходить.

Остановка Workbench — `Ctrl+C` в его терминале. Фоновая конфигурация Serve живёт
отдельно; удалить HTTPS endpoint можно командой:

```bash
tailscale serve --https=443 off
tailscale serve status
```

Если компьютер спит, выключен, вышел из Tailscale или Workbench/Ollama
остановлены, чат с телефона недоступен. Смена Wi-Fi не мешает соединению, пока
оба устройства имеют доступ к Tailscale.

## Ограничения

- Это личный приватный UI, не публичный и не multi-tenant сервис.
- Tailscale identity заменяет второй пароль, но не отменяет необходимость
  защищать Tailscale-аккаунт MFA и ограничивать ACL/grants.
- Serve не добавляет `Tailscale-User-Login` для tagged devices; они fail-closed
  получают `403`.
- Автоматический TLS принадлежит Tailscale Serve. Между Serve и Workbench трафик
  идёт по HTTP только через loopback того же компьютера.
- Прямой gateway API, Basic Auth, Nginx rate limit и отдельный verifier остаются
  в [PRIVATE_LLM_SERVICE.md](PRIVATE_LLM_SERVICE.md).
