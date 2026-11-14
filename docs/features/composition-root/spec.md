# Композиционный корень `:app`

## Problem Statement

Сборка графа приложения живёт private-функцией в smoke (`buildComposition`): конфиг → reader →
Ktor-клиент → `KtorHttpTool` → `ManagedBinary` ×2 → `FfmpegMediaTool` → адаптеры → очередь →
`CoreFacade` → use-cases → остановка. Первый GUI и будущий TUI скопируют этот граф целиком — прямое
дублирование проводки, ровно то, чего оба фронтенда не должны делать. Кроме копии, фронтенды
вынуждены видеть `:core`, `:adapters` и `:infra` напрямую, хотя их публичный мир — use-cases и
жизненный цикл.

## Solution

Модуль `:app` — единственный композиционный корень: собирает весь граф и отдаёт фронтенду готовый
`TubeloaderApp`. Публичная поверхность: `sources: List<Source>`, `resolveMedia: ResolveMedia`,
`downloads: DownloadSession`, `config: AppConfig`, `prepareTools(onNote)` — подготовка внешних
инструментов с пер-инструментным статусом, `shutdown(timeout)` — штатная остановка очереди,
`close()` — освобождение клиента, медиа-инструмента и скоупа.

Сборка — `TubeloaderApp.build(reader: ConfigReader, paths: ToolPaths, overrides: AppOverrides)`:
reader приходит снаружи, поэтому источник конфига (файл/TOML, код, in-memory) остаётся изолирован
(config-wiring); `overrides` — значения фронтенда поверх конфига (параллельность, target dir).
Вся проводка — Ktor → `KtorHttpTool`, `ManagedBinary` ×2, `FfmpegMediaTool`, адаптеры, очередь,
фасад — внутри модуля; фронтенды не видят `:core`/`:adapters`/`:infra`.

`tools:smoke` пересаживается на `:app` первым потребителем: `buildComposition` и `SmokeTools`
умирают, dashboard, observer и CLI остаются в smoke; подготовка инструментов идёт через
`prepareTools(onNote)`.

## User Stories

1. Как разработчик GUI, я хочу получить собранный граф одной фабрикой, чтобы не знать про порты,
   адаптеры и инфру и не копировать проводку.
2. Как разработчик TUI, я хочу тот же граф, чтобы второй фронтенд не дублировал smoke-сборку.
3. Как разработчик фронтенда, я хочу видеть только `:app` и `:usecases`, чтобы зависимость от
   внутренностей ядра была структурно невозможна.
4. Как разработчик smoke, я хочу остаться на том же графе, чтобы smoke проверял production-путь,
   а не собственный дубль.
5. Как пользователь, я хочу видеть статус подготовки инструментов (ffmpeg, yt-dlp) в конкретном
   фронтенде, чтобы понимать, почему загрузка ещё не началась.

## Implementation Decisions

- **Модуль `:app`** — библиотека, не entry point; зависит от `:core`, `:usecases`, `:adapters`,
  `:infra`, `:common:*`; фронтенды зависят от `:app` + `:usecases`. Пакет —
  `io.averkhogliad.tubeloader.app`.
- **Фабрика графа:** `TubeloaderApp.build(reader: ConfigReader, paths: ToolPaths, overrides:
  AppOverrides = AppOverrides())`. `reader` — source-agnostic (config-wiring); `ToolPaths` несут
  home-каталог приложения; `overrides` — `maxParallelDownloads` и `defaultTargetDir` поверх
  конфига.
- **Публичная поверхность:** `sources`, `resolveMedia`, `downloads` (`DownloadSession`), `config`
  (снимок `AppConfig`), `prepareTools(onNote)` — мемоизированная, пер-инструментный статус
  (ffmpeg, yt-dlp), колбек последним; `shutdown(timeout)` делегирует `DownloadQueue.shutdown`;
  `close()` освобождает клиент, медиа-инструмент и скоуп.
- **Логирование:** `:app` логирует через фасад slf4j (когда появится фича logging); backend и его
  конфиг — забота фронтенда. До логирования в `:app` — без логов.
- **Smoke-миграция в том же модульном шаге:** `buildComposition`/`SmokeTools` удаляются; dashboard,
  `DownloadStateObserver`, CLI-парсинг остаются в smoke; `prepareTools` отдаёт ноты через колбек.
  `LoggingHttpTool` в `:app` не переезжает — шпион умирает в фиче failure-reason; smoke временно
  теряет http-ноты, осознанно.
- **Версии и зависимости** — `gradle/libs.versions.toml`; Kover-гейт применяется автоматически.
- **Доки — по факту кода:** инвариант архитектуры (фронтенды → `:app`/`:usecases`; единственный
  композитор — `:app`), `tech-stack` (строки модулей `:app` и `:usecases`), спека usecases
  (ссылка на композиционный корень).

## Testing Decisions

- **Граф проверяется через поведение:** фабрика собирается на реальных портах с подменой внешнего
  мира — smoke-прогон остаётся сквозной проверкой; focused-тесты — на `AppOverrides` (поверх
  конфига) и на `prepareTools` (мемоизация, пер-инструментный статус, колбек).
- **Фейки — существующие:** `FakeSourceAdapter`/`FakeHttpTool`/`FakeMediaTool` из
  `core/testFixtures`; новых фейков не заводится.
- **Счётчик тестов** из `TEST-*.xml` не уменьшается; `./gradlew check --rerun-tasks` зелёный.

## Out of Scope

- Слой презентации GUI/TUI (экраны, состояние вида, навигация) — спеки фронтендов.
- Backend логирования и его конфиг — фронтенд (ADR-0013).
- Entry point и упаковка приложения (jpackage/distribution) — отдельный ADR в конце M1.
- Rename `CoreFacade` — отложен (usecases spec).
- Политика удаления терминальных загрузок из списка — M2 с видом.

## Further Notes

- ADR-0014 — решение «композиционный корень `:app`»: единственное место проводки, фронтенды —
  тонкие клиенты.
- Тикет `#140` (конфиг smoke из TOML) поглощён `:app`-шагом: проводка конфига переезжает в
  композиционный корень; закрыт как superseded.
- Smoke остаётся первым потребителем и проверкой графа до появления GUI.

## Tickets

- #145 — feat(app): композиционный корень TubeloaderApp (blocked by #144, #139)
