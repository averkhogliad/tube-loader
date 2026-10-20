# Use-case слой над CoreFacade

Спека прикладного слоя над ядром — тонкие оркестраторы для GUI/TUI. Решение зафиксировано в
`docs/adr/0009-usecase-layer-over-core-facade.md`. Контракт ядра и `CoreFacade` —
`docs/features/core/spec.md`; термины `DownloadHandle`/`DownloadState`/`DownloadQueue` —
`CONTEXT.md`.

## Problem Statement

GUI и TUI (M2) требуют прикладной логики, которой в headless-ядре нет: трекинг всех загрузок
(агрегация состояний в список для вида), координация flow `resolve→load→pick quality→enqueue`,
отмена по `taskId`. `CoreFacade` — оркестрация ядра (роутинг, очередь, staging); он не должен
расти UI-поверхностью («следить за всеми загрузками — не задача ядра», ADR-0009). Без слоя
фронтенды зависели бы от `:core` напрямую и дублировали трекинг.

## Solution

Use-case слой в пакете `core.usecase` (внутри `:core` в M1, ADR-0009), обёртка над `CoreFacade`.
Пять use-cases: `ResolveUrl`, `LoadMedia`, `EnqueueDownload`, `CancelDownload`,
`ObserveDownloads`. `ObserveDownloads` владеет map `taskId → DownloadHandle` и агрегирует
`Flow<List<DownloadState>>`. `CoreFacade` остаётся lean — observe-all в него не идёт. Вынос в
модуль `:usecases` + rename `CoreFacade` — M2, по триггеру «первый фронтенд».

## User Stories

1. Как фронтенд, я хочу разрешить URL в `MediaRef` и список источников, чтобы показать
   пользователю, что URL распознан и из какого источника.
2. Как фронтенд, я хочу загрузить метаданные по `MediaRef`, чтобы показать заголовок, автора,
   длительность, обложку и качества для выбора.
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
10. Как разработчик, я хочу, чтобы трекинг (`ObserveDownloads`) тестировался детерминированно
    на фейках ядра, без реальной сети и процессов.
11. Как разработчик, я хочу, чтобы тонкие use-cases (`ResolveUrl`, `LoadMedia`) проверялись
    через существующий шов `CoreFacade` (`FacadeWorld`), чтобы не плодить новые фейки.
12. Как мейнтейнер, я хочу, чтобы слой жил в `:core` как пакет `core.usecase` только временно, с
    задокументированным триггером выноса в модуль, чтобы «временно» не стало «навсегда»
    (ADR-0009).

## Implementation Decisions

**Место и границы.** Пакет `io.averkhogliad.tubeloader.core.usecase` внутри `:core` (M1).
Временная резиденция, не часть ядра: семантически app-слой над `CoreFacade` (ADR-0009).
Зависимости — только `:core` (домен + `CoreFacade`) и `kotlinx.coroutines`; UI-фреймворков нет.
Вынос в модуль `:usecases` + rename `CoreFacade` — M2, одним рефакторингом, по триггеру «первый
фронтенд». До этого слой зовут `tools/smoke` и тесты, которые и так видят `:core`.

**`CoreFacade` не трогается.** observe-all в фасад не добавляется. Трекинг живёт в use-cases.
Контракт `CoreFacade` (`availableSources`, `findByUrl`, `findById`, `loadMeta`, `enqueue`,
`DownloadHandle.cancel`/`state`) — как есть.

**`ResolveUrl`.** Обёртка над `CoreFacade.availableSources` + `findByUrl(input)` /
`findById(sourceId, mediaId)`. Возвращает `ResolveResult` (`Resolved(MediaRef)` / `NotFound` /
`Unsupported`) и/или список `Source` для выбора при `findById`. Без состояния.

**`LoadMedia`.** Обёртка над `CoreFacade.loadMeta(ref)` → `LoadMetaResult` (`Found(MediaMeta)` /
`NotFound` / `Failed`). Без состояния.

**`EnqueueDownload`.** Обёртка над `CoreFacade.enqueue(ref, quality, targetPath)` →
`DownloadHandle`. Регистрирует хэндл в трекере слоя (map `taskId → DownloadHandle`). `quality` и
`targetPath` — входные параметры от вида; слой не выбирает их.

**`CancelDownload`.** Lookup хэндла по `taskId` в трекере слоя → `handle.cancel()`. Если `taskId`
неизвестен — no-op или исход «не найдено» (деталь реализации, не контракта ядра).

**`ObserveDownloads` — реальная логика слоя.** Владеет map `taskId → DownloadHandle`.
Подписывается на `handle.state: Flow<DownloadState>` каждого хэндла; агрегирует в
`Flow<List<DownloadState>>`. Порядок — по `startedAt` (момент постановки, `DownloadState`).
Терминальные задачи (`Completed`/`Cancelled`/`Failed`) остаются в списке до явного удаления
(политика удаления — M2 с видом; в M1 список только растёт, удаление out of scope). Aggregation
через `kotlinx.coroutines.flow` (combine/merge поверх StateFlow-хэндлов).

**Shutdown.** Слой делегирует штатную остановку на `CoreFacade`/`DownloadQueue.shutdown` (ADR-0003,
конфайнмент). Слой не владеет очередью.

**Нет новой domain-модели.** Слой оперирует существующими типами ядра (`MediaRef`, `MediaMeta`,
`Quality`, `DownloadHandle`, `DownloadState`, `TaskId`, `ResolveResult`, `LoadMetaResult`).
Новых sealed-исходов и полей в `Progress`/`DownloadError` — нет.

## Testing Decisions

**Хороший тест** проверяет внешнее поведение use-case через `CoreFacade` (реальный, на фейках
адаптеров) — исходы делегирования и агрегацию состояний, не внутренний map. Реальная сеть и
процессы — никогда.

**Высший шов — `CoreFacade` + `FacadeWorld`.** Переиспользуется существующий харнес
`core/test/.../facade/CoreFacadeFixtures.kt` (`FacadeWorld`, `FakeSourceAdapter`, `resolve`,
`enqueue`, `observedStatuses`, `state`). Новые фейки ядра не заводятся.

**Тонкие use-cases** (`ResolveUrl`, `LoadMedia`):Focused-тест на делегирование — use-case зовёт
фасад и возвращает маппинг; поведение равно `CoreFacadeFind`/`CoreFacadeEnqueue`. Prior art:
`CoreFacadeFindTest`, `CoreFacadeEnqueueTest`.

**`ObserveDownloads` — детерминированный тест агрегации.** В `FacadeWorld` ставятся N загрузок
через `FakeSourceAdapter` (с управляемыми переходами статусов через `CompletableDeferred`, как в
`CoreFacadeEnqueueTest`); собирается `Flow<List<DownloadState>>`; asserts: порядок по `startedAt`,
обновления при переходах, терминальные задачи остаются. Cancel-кейс: `CancelDownload` → хэндл
переходит в `Cancelling`/`Cancelled`, список отражает. Для изоляции агрегации допускается фейк
хэндла с `MutableStateFlow<DownloadState>` (без `CoreFacade`), но предпочтён сквозной путь через
`FacadeWorld`.

**`CancelDownload`.** Lookup + `cancel()`: тестируется через `CoreFacadeCancelTest`-образец —
ставится задача, отменяется по `taskId` через use-case, наблюдается переход в `Cancelled`.

**Prior art.** `CoreFacadeEnqueueTest`, `CoreFacadeCancelTest`, `CoreFacadeFindTest`,
`CoreFacadeShutdownTest`, `FacadeWorld` харнес. Стандарт тестов — `docs/standards/testing.md`;
моки — `docs/standards/testing.md` (query-методы заглушаются, command-методы верифицируются с
`capture`; здесь use-cases — command/query над фасадом, `CoreFacade` реальный на фейках, моки не
нужны).

## Out of Scope

- Модуль `:usecases` и rename `CoreFacade` — M2, ADR-0009.
- Per-frontend UI-state (выделение в списке, диалоги, навигация, пикер качества/каталога) — слой
  вида, M2 (спеки GUI/TUI).
- Политика удаления завершённых задач из списка — M2, с видом.
- Persistence списка загрузок между сессиями (job journal) — `core/spec.md` вне M1.
- TUI (mosaic) — M2.
- Сокрытие `:core` от фронтендов (фронтенд → `:usecases` → `:core`) — актуально с M2 после выноса
  модуля; в M1 `tools/smoke` и тесты видят `:core` напрямую.

## Further Notes

- ADR-0009 — решение «use-case слой над `CoreFacade`, обёртка, вынос в M2».
- `docs/features/core/spec.md` — контракт `CoreFacade`, capability-модель, staging.
- `CONTEXT.md` — `Core`, `DownloadHandle`, `DownloadState`, `DownloadQueue`, `DownloadStatus`.
- 4 из 5 use-cases — тонкие делегирования; реальную логику несёт `ObserveDownloads`. Слой ради
  сокрытия `:core` от фронтендов актуален с M2; в M1 pass-through обоснован подготовкой API и
  валидацией через `tools/smoke` (ADR-0009, Consequences).

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
