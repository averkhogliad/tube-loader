# Use-case слой над CoreFacade

Спека прикладного слоя над ядром — тонкие оркестраторы для GUI/TUI. Решение зафиксировано в
`docs/adr/0009-usecase-layer-over-core-facade.md`; композиционный корень фронтендов —
`docs/features/composition-root/spec.md` (ADR-0014). Контракт ядра и `CoreFacade` —
`docs/features/core/spec.md`; термины `DownloadHandle`/`DownloadState`/`DownloadQueue`/`Download
Session` — `CONTEXT.md`.

## Problem Statement

GUI (M1) и TUI (M2) требуют прикладной логики, которой в headless-ядре нет: трекинг всех загрузок
(агрегация состояний в список для вида), координация flow `resolve→load→pick quality→enqueue`,
отмена по `taskId`. `CoreFacade` — оркестрация ядра (роутинг, очередь, staging); он не должен
расти UI-поверхностью («следить за всеми загрузками — не задача ядра», ADR-0009). Без слоя
фронтенды зависели бы от `:core` напрямую и дублировали трекинг.

## Solution

Use-case слой — отдельный модуль `:usecases` над `:core`: `ResolveMedia` и `DownloadSession`.
`ResolveMedia` заменяет пару `ResolveUrl` + `LoadMedia`: разрешение медиаэлемента — одна операция
ядра, отдающая и ссылку, и метаданные, поэтому слой не разводит её на два вызова.
`DownloadSession` — набор загрузок этого запуска: владеет трекером `taskId → DownloadHandle` как
внутренностью, ставит и отменяет загрузки, агрегирует `Flow<List<DownloadState>>`. `CoreFacade`
остаётся lean — observe-all в него не идёт.

Вынос из `:core` выполнен как подготовка фронтендов: триггер ADR-0009 — «первый фронтенд» (GUI —
M1 по ADR-0002 и tech-stack). Модуль зависит только от `:core` и `kotlinx.coroutines`; фронтенды
и `tools:smoke` зависят от `:usecases`, а не от `:core`. Rename `CoreFacade` отложен: имя
перестаёт быть внешней точкой входа не раньше первой фронтенд-фичи; отдельный тикет при нужде.

## User Stories

1. Как фронтенд, я хочу разрешить URL и получить ссылку на медиаэлемент вместе с метаданными,
   чтобы показать пользователю, что URL распознан, из какого источника и что за видео.
2. Как фронтенд, я хочу назвать код источника и идентификатор видео вместо URL, чтобы начать
   работу, когда ссылки под рукой нет.
3. Как фронтенд, я хочу поставить загрузку (ref, качество, target path) и получить хэндл, чтобы
   отслеживать её прогресс.
4. Как фронтенд, я хочу отменить загрузку по `taskId`, чтобы реализовать кнопку отмены без
   знания о `DownloadHandle` напрямую.
5. Как фронтенд, я хочу наблюдать список всех активных и завершённых загрузок как один
   `Flow<List<DownloadState>>`, чтобы рендерить список в виде.
6. Как фронтенд, я хочу, чтобы завершённые/отменённые/упавшие задачи оставались в списке до
   явного удаления, чтобы пользователь видел результат.
7. Как фронтенд, я хочу, чтобы слой не зависел от UI-фреймворка (Compose/mosaic), чтобы быть
   переиспользуемым между GUI и TUI.
8. Как фронтенд, я хочу, чтобы качество и target path были входными параметрами, а не решением
   слоя, чтобы UI-специфичный выбор (диалог, пикер) остался в виде.
9. Как разработчик, я хочу, чтобы `CoreFacade` не менялся ради этого слоя, чтобы ядро осталось
   стабильным и покрытым существующими тестами.
10. Как разработчик, я хочу, чтобы трекинг (`DownloadSession`) тестировался детерминированно
    на фейках ядра, без реальной сети и процессов.
11. Как разработчик, я хочу, чтобы тонкий use-case (`ResolveMedia`) проверялся
    через существующий шов `CoreFacade` (`FacadeWorld`), чтобы не плодить новые фейки.
12. Как мейнтейнер, я хочу, чтобы слой жил в отдельном модуле `:usecases`, чтобы фронтенды не
    зависели от `:core` и его внутренностей.

## Implementation Decisions

**Место и границы.** Модуль `:usecases`, пакет `io.averkhogliad.tubeloader.usecase`. Зависимости —
`:core` (домен + `CoreFacade`) и `kotlinx.coroutines`; адаптеров, инфры и UI-фреймворков нет.
Контракт ядра (`CoreFacade`, порты, доменные типы) объявляет `:core`; слой — прикладной, не
часть ядра. Фронтенды и `tools:smoke` зависят от `:usecases`; вынос в модуль больше не отложен.

**`CoreFacade` не растёт.** observe-all в фасад не добавляется. Трекинг живёт в use-cases.
Контракт `CoreFacade` (`availableSources`, `findByUrl`, `findById(code, mediaId)`, `enqueue`,
`DownloadHandle.cancel`/`state`) — как есть, кроме смены формы разрешения (ADR-0011): вместо
`findByUrl`/`findById`/`loadMeta` фасад отдаёт `MediaResult` одной операцией.

**`ResolveMedia`.** Обёртка над `CoreFacade.availableSources` + `findByUrl(input)` /
`findById(code, mediaId)`. Возвращает `MediaResult` (`Found(ref, meta)` / `NotFound` /
`Unsupported` / `Failed(error)`) — ссылку на медиаэлемент вместе с метаданными, — и/или список
`Source` для выбора источника по коду. Без состояния. Код источника приходит от вызывающего:
неизвестный код — ошибка вызывающей стороны, а не исход с пустым результатом.

**`DownloadSession` — реальная логика слоя.** Один модуль на набор загрузок этого запуска:
владеет трекером `taskId → DownloadHandle` как внутренностью (отдельного типа-трекера нет).
Операции: `enqueue(ref, quality, targetPath)` — обёртка над `CoreFacade.enqueue`, регистрирует
хэндл; `cancel(taskId)` — lookup хэндла и `handle.cancel()`, неизвестный `taskId` — `false`;
`states()` — агрегирует `Flow<List<DownloadState>>` по `handle.state` каждого хэндла, порядок по
`startedAt` (момент постановки). Терминальные задачи (`Completed`/`Cancelled`/`Failed`) остаются
в списке до явного удаления (политика удаления — M2 с видом; в M1 список только растёт, удаление
out of scope). `quality` и `targetPath` — входные параметры от вида; слой не выбирает их.

**Shutdown.** Слой не владеет очередью и не заводит модуль остановки: композиция передаёт
`DownloadQueue.shutdown` лямбдой (ADR-0003, конфайнмент). `UseCaseShutdown` удалён как
pass-through; в новом layout остановку фронтенду отдаёт композиционный корень (`:app`).

**Нет новой domain-модели.** Слой оперирует типами ядра (`MediaRef`, `MediaMeta`,
`Quality`, `DownloadHandle`, `DownloadState`, `TaskId`, `MediaResult`): `MediaResult` — тип ядра
(ADR-0011), не модель слоя. Новых sealed-исходов и полей в `Progress`/`DownloadError` — нет.

## Testing Decisions

**Хороший тест** проверяет внешнее поведение use-case через `CoreFacade` (реальный, на фейках
адаптеров) — исходы делегирования и агрегацию состояний, не внутренний map. Реальная сеть и
процессы — никогда.

**Высший шов — `CoreFacade` + `FacadeWorld`.** Переиспользуется существующий харнес
`core/testFixtures` (`FacadeWorld`, `FakeSourceAdapter`, `resolve`, `enqueue`,
`observedStatuses`, `state`) через testFixtures-зависимость; новые фейки ядра не заводятся.

**Тонкий use-case** (`ResolveMedia`): focused-тест на делегирование — use-case зовёт фасад и
возвращает маппинг; поведение равно `CoreFacadeFind`/`CoreFacadeEnqueue`. Prior art:
`CoreFacadeFindTest`, `CoreFacadeEnqueueTest`.

**`DownloadSession` — детерминированный suite агрегации.** В `FacadeWorld` ставятся N загрузок
через `FakeSourceAdapter` (с управляемыми переходами статусов через `CompletableDeferred`, как в
`CoreFacadeEnqueueTest`); собирается `Flow<List<DownloadState>>`; asserts: порядок по `startedAt`,
обновления при переходах, терминальные задачи остаются. Cancel-кейс: `cancel(taskId)` → хэндл
переходит в `Cancelling`/`Cancelled`, список отражает; неизвестный `taskId` — `false`. Для
изоляции агрегации допускается фейк хэндла с `MutableStateFlow<DownloadState>` (без
`CoreFacade`), но предпочтён сквозной путь через `FacadeWorld`.

**Тесты переезжают вместе с исходниками** в `:usecases:test`; `:core` больше не держит тестов
слоя. Kover-гейт применяется к `:usecases` автоматически.

**Prior art.** `CoreFacadeEnqueueTest`, `CoreFacadeCancelTest`, `CoreFacadeFindTest`,
`CoreFacadeShutdownTest`, `FacadeWorld` харнес. Стандарт тестов — `docs/standards/testing.md`;
моки — `docs/standards/testing.md` (query-методы заглушаются, command-методы верифицируются с
`capture`; здесь use-cases — command/query над фасадом, `CoreFacade` реальный на фейках, моки не
нужны).

## Out of Scope

- Rename `CoreFacade` — отложен: косметика, трогает тесты ядра; отдельный тикет при появлении
  первого фронтенд-потребителя имени.
- Per-frontend UI-state (выделение в списке, диалоги, навигация, пикер качества/каталога) — слой
  вида, спеки фронтендов.
- Политика удаления завершённых задач из списка — M2, с видом.
- Persistence списка загрузок между сессиями (job journal) — `core/spec.md` вне M1.
- TUI (mosaic) — M2.
- Сборка графа приложения — `:app`, `docs/features/composition-root/spec.md` (ADR-0014).

## Further Notes

- ADR-0009 — решение «use-case слой над `CoreFacade`»; Status актуализирован выносом: триггер
  «первый фронтенд» считается наступившим в подготовке фронтендов, слой вынесен, rename отложен.
- ADR-0011 — решение «код источника и контракт адаптера»: `ResolveMedia` снимает пару
  `ResolveUrl` + `LoadMedia`, источник адресуется кодом.
- `docs/features/core/spec.md` — контракт `CoreFacade`, capability-модель, staging.
- `CONTEXT.md` — `Core`, `DownloadHandle`, `DownloadState`, `DownloadQueue`, `DownloadStatus`,
  `Download Session`.
- `ResolveMedia` — тонкое делегирование; реальную логику несёт `DownloadSession`. Слой ради
  сокрытия `:core` от фронтендов теперь структурная: фронтенд зависит от `:usecases` и `:app`,
  а не от внутренностей ядра.

## Tickets

Тикеты живут в GitHub Issues: <https://github.com/averkhogliad/tube-loader/issues>. Числа ниже —
номера issue (проставляются после создания).

- 00 — usecases: package `core.usecase` + `ResolveUrl` (over `findByUrl`/`findById`/`availableSources`) — #97
- 01 — usecases: `LoadMedia` (over `loadMeta`) (blocked by #97) — #98
- 02 — usecases: `EnqueueDownload` + handle tracker (blocked by #97) — #99
- 03 — usecases: `CancelDownload` (lookup handle, cancel) (blocked by #99) — #100
- 04 — usecases: `ObserveDownloads` (map `taskId→handle`, aggregate `Flow<List<DownloadState>>`) (blocked by #99) — #101
- 05 — usecases: tests + shutdown delegation (blocked by #98, #99, #100, #101) — #102
- 06 — usecases: smoke over use-cases layer (blocked by #99, #100, #101, #102) — #104
- 07 — usecases: `ResolveMedia` вместо `ResolveUrl` + `LoadMedia` (blocked by #111) — #111
- 08 — usecases: `DownloadSession` вместо `EnqueueDownload`/`CancelDownload`/`ObserveDownloads`/`DownloadTracker`, удаление `UseCaseShutdown` — #133
- 09 — usecases: вынос `:usecases` из `:core` (blocked by #133) — #144
