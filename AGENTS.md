# Инструкции для работы с LLM Workbench

## Перед изменениями

- Прочитайте [README.md](README.md) и [архитектурную карту](docs/ARCHITECTURE.md).
- Для изменений HTTP-контракта дополнительно прочитайте [docs/WEB_API.md](docs/WEB_API.md).
- Сначала проверьте `git status --short`. В рабочем дереве могут находиться изменения пользователя: не удаляйте и не перезаписывайте их.
- Сверяйте документацию с кодом. Если поведение, команда, маршрут, DTO или постоянный формат меняются, обновите соответствующий документ в том же изменении.

## Технологии и точки входа

- Backend: Kotlin/JVM 21, Ktor, coroutines, kotlinx.serialization; исходники находятся в `src/main/kotlin`.
- Frontend: React, TypeScript, Mantine, Vite; исходники находятся в `frontend/src`.
- Production entry point: `org.example.web.WebMainKt`.
- Детерминированный web fixture: `org.example.web.WebFixtureKt`; он находится в test source set и не попадает в production-сборку.
- Legacy Compose UI изолирован в `legacy-desktop`; не переносите из него зависимости в web runtime.

## Обязательные продуктовые инварианты

- Режим «Простой агент» — это `ResponseMode.UNRESTRICTED` с wire-id `unrestricted`. Не создавайте для него отдельный `ResponseMode.ASSISTANT` и не меняйте wire-id.
- `ContextStrategy.MEMORY_LAYERS` — одна стратегия с тремя совместно работающими слоями `SHORT_TERM`, `WORKING`, `LONG_TERM`, а не три стратегии.
- Память `MEMORY_LAYERS` использует только `AssistantAgent`, `AssistantMemoryManager`, его модели и `AssistantMemoryStore`. Не используйте `slidingMessages`, `stickyFacts`, `branching` или `.llm-history.json` как скрытое хранилище слоёв.
- Поведение и форматы состояния `SLIDING_WINDOW`, `STICKY_FACTS` и `BRANCHING` должны сохраняться, если задача явно не требует их изменить.
- Checkpoint, активная ветка и элементы управления ветками доступны только при `ContextStrategy.BRANCHING`. Они не должны отображаться или вызываться при `MEMORY_LAYERS`.
- В `MEMORY_LAYERS` порядок контекста: system instructions → `LONG_TERM` → `WORKING` → `SHORT_TERM` → текущий user prompt. Пустые или выключенные слои не отправляются, текущий prompt не дублируется.
- `SHORT_TERM` пополняется только полной парой user/assistant после успешного завершения потока и сохранения. Ошибка, отмена и незавершённый stream не меняют память. «Новый диалог» очищает только `SHORT_TERM`; «Завершить задачу» — только `WORKING`; `LONG_TERM` переживает оба действия и перезапуск.
- Флаги слоёв исключают данные из следующего запроса, но не удаляют их. Диагностика ответа должна отражать реально использованные слои, количества и ID записей.
- Постоянные JSON-файлы версионируются и заменяются атомарно. Не меняйте существующий формат без явной миграции и тестов обратной совместимости.
- API-ключи не возвращаются клиенту, не попадают в логи, ошибки, fixture, browser storage, persistent memory или Git.

## Правила изменения сквозного функционала

Изменение настройки, режима, стратегии или поля результата обычно проходит через всю цепочку:

1. доменная модель и бизнес-логика Kotlin;
2. `WorkbenchController` и `WorkbenchState`;
3. DTO/валидация в `web/ApiDtos.kt` и `web/WorkbenchApi.kt`;
4. маршрут в `web/WebServer.kt`, если нужен новый endpoint;
5. зеркальные TypeScript-типы и клиент в `frontend/src/api`;
6. состояние/действия в `frontend/src/state/useWorkbench.ts`;
7. компоненты UI;
8. unit/API/browser-тесты и документация.

Не передавайте доменные Kotlin-объекты напрямую по wire: API использует явные DTO. Для изменяющих команд сохраняйте optimistic concurrency через `expectedSettingsVersion`; запуск должен оставаться идемпотентным по `requestId`. SSE-снимок сервера — источник истины для UI.

## Безопасная разработка и проверки

- Реальные платные API не должны использоваться unit-, API- или browser-тестами. Для автоматических и ручных безопасных проверок используйте детерминированные fake clients и `runWebFixture`. Платный вызов допустим только по прямому запросу пользователя.
- Не запускайте production-приложение, если порт уже занят: сначала определите процесс и убедитесь, что это не пользовательский экземпляр.
- Не редактируйте `.env`, `.llm-history.json`, `.llm-context-state.json` и `.llm-assistant-memory.json` без прямой необходимости задачи. Никогда не показывайте содержимое ключей.
- Не добавляйте production-зависимость ради тестовой утилиты.
- Тестируйте пропорционально изменению. Полная локальная проверка:

```bash
./gradlew test
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

- Playwright сам запускает `runWebFixture` на порту `18080`. Если Chromium ещё не установлен: `cd frontend && npx playwright install chromium`.
- Для узкого изменения сначала можно запустить целевой Kotlin-тест или `npm --prefix frontend run test:e2e -- --grep "..."`, но перед передачей рискованного сквозного изменения выполните полный релевантный набор.

## Команды

```bash
./gradlew runWeb                         # production UI + реальный выбранный API, 127.0.0.1:8080
./gradlew runWebFixture                  # локальный deterministic fixture без платного API
WEB_DEV_PORT=5173 ./gradlew runWeb       # backend для Vite dev server
npm --prefix frontend ci
npm --prefix frontend run dev
./gradlew installDist
./gradlew :legacy-desktop:run
```

Для JDK требуется версия 21+, для Node.js — 22.12+. Gradle Wrapper и `frontend/package-lock.json` являются источниками воспроизводимой установки.

## Завершение задачи

- Просмотрите `git diff --check` и `git diff` только по затронутым файлам.
- Сообщите, какие файлы изменены и какие проверки фактически выполнены.
- Не заявляйте об успешной проверке реального API, если использовался fixture или fake client.
