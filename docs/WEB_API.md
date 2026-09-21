# Локальный API LLM Workbench

Обычный origin: `http://127.0.0.1:8080`. Сериализуемые DTO:
`src/main/kotlin/web/ApiDtos.kt`; TypeScript: `frontend/src/api/types.ts`.
Ключи не входят в состояние. Все ошибки JSON имеют `{ "code": "...", "message": "..." }`.

| Метод | Путь | Тело / результат |
| --- | --- | --- |
| GET | `/api/state` | Полный `StateDto`: настройки, каталог, история, память, `assistantInvariants`, `taskState`, результаты, операция, уведомление |
| GET | `/api/settings` | Тот же полный снимок; ключи представлены только `hasKey` |
| PUT | `/api/settings` | `{ expectedSettingsVersion, settings: SettingsDto }` → снимок после сохранения |
| PUT | `/api/key` | `{ expectedSettingsVersion, provider, key }` → снимок без ключа |
| POST | `/api/operations` | `{ requestId, expectedSettingsVersion, prompt, demo: null \| "reasoning" \| "temperature" }` → `202 { operationId }` |
| POST | `/api/context/checkpoint` | `{ expectedSettingsVersion }` → создаёт immutable checkpoint и ветки A/B |
| POST | `/api/context/branch` | `{ expectedSettingsVersion, branchId }` → переключает активную ветку |
| POST | `/api/operations/{id}/cancel` | `{}` → текущий снимок; окончание отмены приходит по SSE |
| GET | `/api/history` | Controlled/compare history и состояние стратегии: facts, checkpoint, branch ID/сообщения, activeBranchId |
| GET | `/api/assistant/memory` | Три секции памяти `MEMORY_LAYERS`, записи, счётчики и флаги включения |
| GET | `/api/assistant/profile` | Активный `AssistantProfileDto`: `version`, `preferredName`, `about`, `responseStyle`, `responseFormat`, `constraints`, `configuredFieldCount` |
| PUT | `/api/assistant/profile` | `{ expectedSettingsVersion, profile: { preferredName, about, responseStyle, responseFormat, constraints } }` → полный снимок после атомарного сохранения |
| GET | `/api/assistant/invariants` | `AssistantInvariantStateDto`: версия коллекции и полный список правил |
| POST | `/api/assistant/invariants` | `{ expectedSettingsVersion, category, text }` → добавить правило и вернуть полный снимок |
| PUT | `/api/assistant/invariants` | `{ expectedSettingsVersion, id, category, text }` → изменить правило с сохранением ID/created timestamp и вернуть полный снимок |
| DELETE | `/api/assistant/invariants` | `{ expectedSettingsVersion, id }` → удалить правило и вернуть полный снимок |
| POST / PUT / DELETE | `/api/assistant/memory` | Добавить / изменить / удалить запись с `{ expectedSettingsVersion, layer, ... }` |
| POST | `/api/assistant/memory/clear` | `{ expectedSettingsVersion, layer }` → очистить ровно один слой |
| PUT | `/api/assistant/memory/enabled` | `{ expectedSettingsVersion, layer, enabled }` → включить слой без удаления |
| POST | `/api/assistant/dialogue/new` | Очистить только `SHORT_TERM` |
| POST | `/api/assistant/task/complete` | Очистить только `WORKING` |
| GET | `/api/assistant/task-state` | `{ task: TaskStateDto \| null }` для отдельной FSM |
| POST | `/api/assistant/task-state/start` | `{ expectedSettingsVersion, goal, currentStep, expectedAction }` → новая задача в `PLANNING` |
| PUT | `/api/assistant/task-state/progress` | `{ expectedSettingsVersion, currentStep, expectedAction }` → обновить ход задачи без смены этапа |
| POST | `/api/assistant/task-state/approve-plan` | `{ expectedSettingsVersion }` → явно утверждает план и переводит `PLANNING → EXECUTION` |
| POST | `/api/assistant/task-state/complete-implementation` | `{ expectedSettingsVersion }` → фиксирует завершение реализации и переводит `EXECUTION → VALIDATION` |
| POST | `/api/assistant/task-state/validation` | `{ expectedSettingsVersion, successful, details, expectedAction? }`; успех переводит `VALIDATION → DONE`, неуспех сохраняет причину/следующее действие в `VALIDATION` |
| POST | `/api/assistant/task-state/pause` | `{ expectedSettingsVersion }` → пауза без смены этапа |
| POST | `/api/assistant/task-state/resume` | `{ expectedSettingsVersion }` → продолжение на том же этапе |
| POST | `/api/assistant/task-state/reset` | `{ expectedSettingsVersion }` → удалить только состояние FSM |
| DELETE | `/api/history` | `{}` → очищает обычную историю и состояния трёх прежних стратегий; слои памяти не затрагивает |
| DELETE | `/api/results` | `{}` → снимок без карточек; история сохранена |
| DELETE | `/api/notice` | `{}` → снимок без уведомления |
| GET | `/api/events` | SSE `event: state`, `id: revision`, `data: StateDto`; heartbeat каждые 15 секунд |

Изменяющие запросы требуют `Content-Type: application/json` и
`X-Workbench-Request: 1`. Host проверяется по точному списку loopback-адресов с
настроенным портом. Origin, если есть, должен принадлежать приложению. В режиме
разработки явно допускается порт Vite через `WEB_DEV_PORT`; запросы идут через
Vite proxy, CORS не включается. Запросы `Sec-Fetch-Site: cross-site` запрещены.

## Согласованность

`revision` монотонно растёт при любом изменении снимка; `settingsVersion` — при
сохранении настроек/ключа, профиля, каждой мутации памяти, FSM и инвариантов. Клиент передаёт последнюю версию и при
`409 stale_settings` получает свежий снимок, после чего пользователь повторяет
действие. Команды проверяются и выполняются под общим монитором контроллера.
Рабочая корутина публикует состояние под тем же монитором; история принадлежит
рабочей корутине до освобождения активной операции.

Одновременно работает один эксперимент. Другой запуск, изменение настроек/ключа,
checkpoint/branch-команды, мутации FSM/инвариантов и очистки возвращают `409 busy`. Отмена привязана к номеру операции, поэтому
запоздалая отмена из вкладки не остановит следующий запрос.

`requestId` — новый UUID для каждого намеренного запуска. Повтор **той же** команды
с тем же ID возвращает исходный `operationId`, даже после завершения или отмены.
Повтор ID с другим телом возвращает `409 duplicate_id`. Запомненные ID живут до
остановки сервера, включая команды, чьи карточки были очищены. После неопределённой
сетевой ошибки frontend предлагает «Проверить отправку» с исходным ID и телом,
а не создаёт ещё один платный LLM-запрос.

Операция запускается в области корутин контроллера, независимой от HTTP/SSE-сессии.
Новый подписчик сразу получает полный актуальный снимок; `Last-Event-ID` не нужен
для воспроизведения промежуточных событий. Медленный подписчик может пропустить
снимок этапа, но следующий снимок содержит весь накопленный текст. LLM-фрагменты
собираются на сервере и публикуются обновлёнными снимками примерно
раз в 50 мс; активная карточка имеет `outputs[].streaming = true`. Финальные
метрики и история появляются только после успешного завершения потока. Финальный
обмен фиксируется в версионированном `.llm-context-state.json` и
`.llm-history.json`, затем публикуется в памяти и API; при сбое второго шага
context-state откатывается. При ошибке или отмене последний накопленный черновик остаётся в
карточке, но в историю диалога не записывается. Ошибка записи переводит операцию
в `failed`, не добавляя обмен ни в память, ни в файл. `DELETE /api/history`
аналогично очищает память только после успешной записи пустого снимка; при ошибке
возвращает `500 persistence`.

В `settings` стратегия передаётся как `SLIDING_WINDOW`, `STICKY_FACTS`,
`BRANCHING` или `MEMORY_LAYERS`, а N — как положительный `recentMessagesLimit`
(не меньше 2 для полного short-term обмена). Неизвестные значения
и неположительный N дают `400 validation`. Sticky Facts публикует отдельный этап
«Обновление facts» и учитывает usage этого вызова в `context.factUsage`.

Все поля профиля необязательны и нормализуются обрезкой внешних пробелов и
переводов строк. `preferredName` — одна строка до 120 символов; `about` и
`constraints` — до 4000, `responseStyle` и `responseFormat` — до 1000 символов.
Недопустимые управляющие символы и любое значение с настроенным API-ключом дают
`400 validation`. PUT запрещён при активной операции (`409 busy`) и при устаревшем
`expectedSettingsVersion` (`409 stale_settings`). Пустой профиль не включается в
LLM-контекст. Непустой применяется только в `UNRESTRICTED + MEMORY_LAYERS`, даже
если все слои выключены; `StateDto.assistantProfile` и SSE являются источником
истины для редактора. Диагностика output содержит только факт применения, версию
и число заполненных полей.

Task State Machine доступна только при `unrestricted + MEMORY_LAYERS`. Поля `goal`,
`currentStep` и `expectedAction` обязательны после нормализации; пределы — 8000,
4000 и 4000 символов; `details` проверки — до 4000. Новая задача начинается в
`PLANNING`. Серверная таблица переходов разрешает только явное утверждение плана
`PLANNING → EXECUTION`, фиксацию завершения реализации `EXECUTION → VALIDATION` и
успешный результат проверки `VALIDATION → DONE`. Неуспешная проверка требует
`details` и непустой `expectedAction`, сохраняет `validationStatus=FAILED` и
остаётся в `VALIDATION`. DTO задачи также содержит transition timestamps,
`validationStatus`, `validationDetails` и вычисленный backend список
`availableActions`; frontend не вычисляет граф самостоятельно. Pause/resume
сохраняют все поля. На паузе разрешены только resume/reset. Повторная пауза/resume,
обновление прогресса на паузе, пропуск/возврат этапа и команда из `DONE` дают
`409 invalid_task_transition` с текущей фазой и ближайшим действием, без записи,
изменения `revision`/`settingsVersion` и публикации snapshot.
Текст ответа модели не меняет FSM. System context ограничивает ответ текущей фазой:
в `PLANNING` нельзя изображать реализацию, а до зафиксированной успешной проверки —
объявлять задачу завершённой. Дополнительно ответ активной задачи буферизуется:
backend блокирует явное утверждение о результате более поздней фазы до публикации,
не записывает пару в `SHORT_TERM` и возвращает безопасный текст с текущей фазой и
ближайшим разрешённым действием. Токены вызова учитываются, FSM не меняется.
Приостановленная задача блокирует обычный `POST /api/operations` с кодом
`409 task_paused` до вызова LLM. Активная незавершённая задача попадает в system
context, а output diagnostics содержит только `applied`, `taskId`, `stateVersion`,
`phase` и `responseBlocked`. Известный API-ключ в любом текстовом поле даёт
`400 validation`.

Инварианты доступны для мутаций только при `unrestricted + MEMORY_LAYERS`.
Категория — одно из `ARCHITECTURE`, `TECH_DECISION`, `STACK`, `BUSINESS_RULE`,
`OTHER`; текст после нормализации непустой, не длиннее 16 384 символов и не
содержит недопустимых управляющих символов или настроенного API-ключа. Версия
`AssistantInvariantStateDto.version` увеличивается после каждой успешно записанной
мутации. Ошибка persistence возвращает `500 persistence`, не меняя коллекцию,
`settingsVersion` или опубликованный snapshot. Новый API-ключ отклоняется, если
его значение уже встречается в инварианте. `StateDto`/SSE возвращают тексты правил
для редактора, а output diagnostics — только `applied`, `stateVersion`,
`appliedCount`, `appliedInvariantIds` и `responseBlocked`.

В pipeline `unrestricted + MEMORY_LAYERS` непустые правила входят в первое system
message перед task/profile/memory context и имеют приоритет над текущим prompt.
При конфликте контракт требует объяснимый отказ с ID/категорией и совместимой
альтернативой, сохраняя выполнение совместимой части. Ответ с инвариантами
буферизуется до завершения и принимается только как структурированный объект с
preflight-полями, пользовательским `answer` и вложенным postflight-аудитом. Для
OpenAI форма закрепляется нативным `response_format=json_schema`. Объект содержит
версию snapshot, все проверенные ID, конфликтующие ID, согласованное решение и
результат проверки готового ответа. При невалидном объекте сырой output блокируется,
`responseBlocked=true`, а пара не
попадает в `SHORT_TERM`. Пустая коллекция не создаёт invariant block, даёт
`applied=false` и сохраняет обычный streaming. Дополнительного LLM-вызова и
production keyword-классификатора нет.

## Коды ошибок

- `400 validation`, `400 invalid_json`: некорректные параметры / JSON / отсутствие ключа.
- `403 forbidden`: неподходящий Host, Origin, fetch metadata или формат команды.
- `404 not_found`: неизвестная операция.
- `409 busy`, `409 stale_settings`, `409 duplicate_id`, `409 invalid_task_transition`,
  `409 task_paused`: конфликт команд или состояния задачи.
- `500 persistence`, `500 internal`: ошибка сохранения / выполнения команды,
  без содержимого запросов и исключений.

Ошибки длительного LLM-вызова передаются в `exchange.status`, `exchange.error` или
`outputs[].error`, а не превращают уже принятый POST в запоздалую HTTP-ошибку.
