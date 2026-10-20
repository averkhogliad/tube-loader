# 0009: Use-case слой над CoreFacade

## Status

Принято. Применяется с M1 (пакет `core.usecase` в `:core`); вынос в модуль `:usecases`
и rename `CoreFacade` — на M2, по триггеру «первый фронтенд».

## Context

`CoreFacade` (`core/.../facade/CoreFacade.kt`) — headless-оркестрация: роутинг URL по
адаптерам (`findByUrl`/`findById`), `loadMeta`, `enqueue` (`registry.allocate` +
`queue.submit` + `job.invokeOnCompletion`), `runDownload` (staging `.part-<id>`,
статус-переходы `LoadingMeta→Downloading→Finalizing→Completed/Failed`, `ATOMIC_MOVE`,
cleanup по cancel/failure). `TaskRegistry` приватный, поверхности «наблюдать все
загрузки» нет. `cancel()` живёт на `DownloadHandle`, не на фасаде.

GUI и TUI (M2) требуют прикладной логики, которой в ядре нет: трекинг всех загрузок
(агрегация `Flow<List<DownloadState>>`), координация flow
`resolve→load→pick quality→enqueue`, маппинг ошибок для вида. По принципу
«следить за всеми загрузками — не задача ядра» `CoreFacade` не должен расти этой
поверхностью.

Три варианта: (а) расширить `CoreFacade` observe-all; (б) растворить фасад в
use-cases (замена); (в) use-case слой над `CoreFacade` (обёртка).

## Decision

Use-case слой **поверх** `CoreFacade`, обёртка. В M1 — пакет `core.usecase` в
`:core`; в M2 — отдельный модуль `:usecases` + rename `CoreFacade`, одним
рефакторингом. Пять use-cases: `ResolveUrl`, `LoadMedia`, `EnqueueDownload`,
`CancelDownload`, `ObserveDownloads`.

`CoreFacade` остаётся lean: observe-all в него не идёт. Трекинг (`ObserveDownloads`)
владеет map `taskId → DownloadHandle`, подписывается на `handle.state` каждого и
агрегирует в `Flow<List<DownloadState>>`. `CancelDownload` = lookup handle +
`handle.cancel()`. Flow `resolve→load→pick→enqueue` координирует use-case слой; вид
отдаёт выбор качества и target path.

`core.usecase` — **временная резиденция**, не часть ядра: семантически это app-слой
над ядром. `:core` не получает UI-фреймворк-зависимостей через этот пакет (только
`:core` + kotlinx.coroutines). Это та же конвенция, что для `adapters.rutube` и
`core.media`: модуль не заводим заранее, пакет может; вынос в модуль — отдельным
тикетом по триггеру.

Триггер выноса в `:usecases` — первый фронтенд (GUI, M2): фронтенд зависит от
`:usecases`, не от `:core`. До этого use-cases зовут только `tools/smoke` и тесты,
которые и так видят `:core`.

## Considered Options

- **(а) Расширить `CoreFacade` observe-all.** Отвергнуто: трекинг — app-специфичная
  логика, не ядра; фасад теряет фокус и становится UI-state-хранилищем.
- **(б) Растворить `CoreFacade` в use-cases (замена).** Отвергнуто: общая
  оркестрация (`queue` + `registry` + staging + атомарный move + маппинг ошибок)
  spans use-cases; каждый потянет shared-зависимости — либо дублирование, либо
  вытаскиваешь общую внутреннюю деталь, она же фасад под другим именем. Замена не
  убирает фасад, она прячет.
- **(в) Use-case слой над фасадом.** Выбрано: фасад сохраняет оркестрацию, use-cases
  держат app-логику и UI-состояние.

## Consequences

**Положительные.**
- `CoreFacade` остаётся чистой оркестрацией ядра; граница «ядро не знает про все
  загрузки» сохранена.
- MVC-подобный разрез: model (`:core`) / controller (`:usecases`) / view (GUI, TUI).
  Use-case слой фронтенд-агностичен, валидируется headless в M1 через `tools/smoke`.
- Rename `CoreFacade` приезжает в M2 вместе с выносом модуля — фасад перестаёт быть
  внешней точкой входа, имя отражает «внутренняя оркестрация», одним рефакторингом.

**Отрицательные.**
- Временная резиденция `core.usecase` в `:core` размывает границу, если вынос
  откладывать. Митигация: триггер «первый фронтенд» зафиксирован, пакет имени
  `core.usecase` сигнализирует изоляцию.
- 4 из 5 use-cases (`ResolveUrl`, `LoadMedia`, `EnqueueDownload`, `CancelDownload`)
  — тонкие делегирования `CoreFacade`/`handle`. Слой ради бизнес-логики тут нет;
  обоснование — сокрытие `:core` от фронтендов (актуально с M2), не логика. До M2
  это видно как pass-through; реальную логику несёт `ObserveDownloads`.
- Rename + вынос модуля в M2 трясут тесты ядра (26 классов) и контракты.
