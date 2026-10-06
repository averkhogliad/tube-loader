# Повтор сетевых сбоев: остаёмся на `:common:retry`

## Status

`accepted` (по итогам гриля `retry-развитие`, 2026-10-06; влитие спеки `docs/features/common-retry/spec.md`
и этого ADR — тикет #65, родитель #64).

## Context

Адаптеры источников ретраят сетевые сбои (обрыв, таймаут, 5xx, 429) через собственный движок
`:common:retry` — пять исходников в `:common:retry/src/main/kotlin/io/averkhogliad/tubeloader/retry/`,
портирующих контракты `kotlin-retry 2.0.2` на наш шов (`kotlin.Result`, без generic по типу
ошибки, `+`-композиция `Stop`/`maxOf`, `withinBudget(cumulativeDelay)` без дефолта бюджета,
`MAX_BACKOFF_STEP = 30` от переполнения shift). Адаптеры собирают политику из фабрик
(`stopAtAttempts + continueIf + exponentialBackoff`) и сейчас ретраят по маркеру
`HttpStatusException : IOException` (память
`Rutube_retry_-_4xx_filtered_by_value_before_retry_-_5xx_via_marker-0ed5834c83bb.md`).

Пользователь поднял вопрос: «сравни и рассмотри возможность и необходимость перейти на
kmp-resilient — стандарт де-факто в отрасли», а потом уточнил рамку: «не “стандарт де-факто”, а
функциональное сравнение; либо обоснованно пишем своё, либо отказываемся в пользу библиотеки».
Гриль прошёл 4 раунда по 3 вопроса, исследовано четыре публичных альтернативы:

| Кандидат | Coroutines | `kotlin.Result` | `cumulativeDelay`-бюджет | Чистая политика | `retryOnResult` | Отдельный артефакт | Лидер ниши |
|---|---|---|---|---|---|---|---|
| `:common:retry` (текущий) | ✅ | ✅ | ✅ | ✅ `RetryPolicy` + `Stage` | ❌ план | n/a (наш) | n/a |
| `kotlin-retry 2.0.2` (michaelbull, 378★, ISC) | ✅ | ❌ свой `Ok/Err` | ✅ встроен | ✅ | ✅ через `RetryOn.returned` | ✅ `kotlin-retry` | 378★ Kotlin coroutines retry |
| `kmp-resilient 2.0.1` (santimattius, 149★, Apache-2.0) | ✅ | ❌ throws last error | ❌ нет | ❌ `BackoffStrategy` sealed | ⚠️ `shouldRetryResult` (только Ktor-плагин) | ❌ тянет весь `resilient-jvm` | KMP-only resilience |
| Arrow Resilience 2.2.3 (Apache-2.0) | ✅ | ❌ throws / `Either` | ❌ нет | ❌ `Schedule` stateful | ❌ через `retryOrElseEither` | ✅ `arrow-resilience-core` | часть Arrow-экосистемы |
| Kresil (`kresil/kresil`, Apache-2.0) | ✅ | ❌ throws | ❌ нет | ❌ stateful | ✅ `retryOnResult` | ❌ **артефакт не опубликован на Maven Central** | 9★, 0 форков |

Премисса «kmp-resilient — стандарт де-факто» не подтверждается цифрами: 149★ < `kotlin-retry` 378★
< resilience4j 10 778★ (Java) < Polly 14 244★ (.NET). Внешних prod-юзеров вне автора нет
(GitHub Code Search: README/awesome, не вызовы). Корректная формулировка — «ближайший к нашему
профилю без собственной разработки».

Sandbox-пробы (артефакты в `.tasks/probe-arrow-resilience/`):
- Arrow Resilience 2.2.3 — 5 проб T1–T6 скомпилированы и прошли через JUnit Platform 1.11.4;
  `Schedule` stateful (Output несёт state между вызовами), `Either → kotlin.Result` — 5 строк
  обёртки на каждый вызов, `cumulativeDelay` бюджета нет; тянет `arrow-core`/`arrow-fx-coroutines`/
  `arrow-atomic`/`arrow-exception-utils` — это вход в Arrow-экосистему, не «+1 библиотека».
- kmp-resilient 2.0.1 — `BackoffStrategy` остаётся `sealed`, retry не отдельный артефакт, throws
  last error, бюджета по `cumulativeDelay` нет (проверено в `DefaultRetryPolicy.kt`-исходниках
  2.0.1, не в обзоре).

## Decision

**Остаёмся на `:common:retry`** как порте контрактов `kotlin-retry` на наши швы. Без зависимостей
от публичных библиотек. Переносим в движок три идеи из рассмотренных альтернатив:

1. **`retryOnResult` через `judging: (T) -> Boolean`** на вызове `retry`, вместе с `onRetry`
   в `RetryContext`. Источники: Polly
   `ShouldHandle(args.Outcome)`, Failsafe `.handleResult`/`.handleResultIf`, Guava `retryIfResult`,
   resilience4j `retryOnResultPredicate` + `failAfterMaxAttempts`, Kresil `retryOnResult`,
   kmp-resilient `shouldRetryResult`, tenacity `retry_if_result`, kotlin-retry `RetryOn.returned`.
   Закрывает долг по синтетическим маркерам (`HttpStatusException : IOException` в адаптере уходит).
2. **`randomizationFactor: Double = 0.0`** в `exponentialBackoff` плюс источник случайности
   параметром `random: () -> Double`. Источники: Kresil
   `exponentialDelay(..., randomizationFactor)`, kmp-resilient `ExponentialBackoff(..., jitter)`,
   Arrow `.jittered(min, max, random)`, Polly `jitter`, tenacity `wait_random`. Закрывает долг по
   джиттеру для будущего параллелизма; seeded `Random` делает кейсы детерминированными.
3. **`onRetry: ((FailedAttempt) -> Unit)?`** в `RetryContext` — том же шве, что `judging`.
   Источники: Kresil `retry.onRetry`, Failsafe `FailsafeListener`, resilience4j `RetryRegistry`,
   kmp-resilient `policy.events`. Закрывает долг по наблюдаемости без новых зависимостей.

DSL — **Compose-форма** (issue #63, форма выбрана и опробована): `RetryPolicy` — обычный
`interface` (не `fun interface`) без generic по типу ошибки, `+`-оператор как `then`, приватный
`Combined`, `Stage` как receiver фабрик. Убирает 23 явных `<Throwable>` из цепочки. Форма `fun
interface` с `companion object` не компилируется: `companion object` требует конструктора у
интерфейса, а `fun interface` его не даёт; ковариантный generic-вариант с companion компилируется,
но падает в рантайме `ClassCastException`.

TOML — **плоский** под-блок `[download.http-tool]`: `connect-timeout-ms`, `read-timeout-ms`,
`max-attempts`, `base-delay-ms`, `randomization-factor`, `retriable-statuses`. Никакого вложенного
`[download.http-tool.retry]` — одна вложенность.

DSL-блок `retryConfig { … }` (Kresil/kmp-resilient) — **отвергнут**: ломает Compose-форму #63
(`+`-оператор как точка сборки).

**Уточнения 16.10.2026 (решения пользователя перед прогоном #64).**

- **`judging` и `onRetry` идут одним швом:** драйвер принимает `RetryContext(judging, onRetry)`.
  Две идеи добавляются одновременно, и раздельные параметры вывели бы сигнатуру за лимит ktlint
  (≤5 параметров). `onRetry` закрывается в тикете #66 вместе с `judging`.
- **Источник случайности в `exponentialBackoff` — параметр.** При `randomizationFactor > 0`
  пауза считается как `base * (1 - factor + 2 * factor * random())`, где `random: () -> Double`
  по умолчанию `Math::random`. Тесты подставляют `Random(seed)`, поэтому окно джиттера
  проверяется детерминированно, а не флакает.
- **`perAttemptTimeout` (#68) отложен.** Ограничивать по времени пока нечего: production-реализации
  `HttpTool`/`MediaTool` в репозитории нет, есть только порты ядра и тестовые фейки. Шов получает
  таймаут вместе с первым реальным клиентом; параметры `connect-timeout-ms`/`read-timeout-ms`
  читаются в `HttpToolConfig` уже сейчас (#67).

## Consequences

**Пять тикетов в трекере** (по состоянию на 2026-10-06):

Родитель #64, подзадачи:

1. #65 — спека, ADR-0004 и архив не-покрытого (эта выгрузка).
2. #66 — `retryOnResult` через `judging(T) → Boolean` в драйвере `:common:retry`, плюс
   `onRetry` в том же `RetryContext`. Правки
   `core/.../adapters/rutube/RutubeSourceAdapter.kt` (убрать `HttpStatusException`-маркер).
   ~60 LoC.
3. #69 — Compose-DSL из issue #63: влитие выбранной формы (`interface RetryPolicy` +
   `companion object : RetryPolicy` + `then`/`Combined`/`Stage`). ~40 LoC, 23 аннотации `<Throwable>`
   уходят. Форма `fun interface` была отвергнута пробой: `companion object` требует конструктора
   у интерфейса, а `fun interface` его не даёт («Interface 'interface RetryPolicy : Any' does not
   have constructors»).
4. #67 — `[download.http-tool]` плоский блок + `HttpToolConfig` в `AppConfig` + интеграция
   в адаптер. ~50 LoC + 30 LoC тестов. Заблокирован тикетом #66.
5. #68 — `perAttemptTimeout` через `withTimeout` в `HttpTool.open` (долг из памяти
   `Retry_engine_in_common_retry-37360798096e.md`). Заблокирован тикетом #67 (нужны
   `connectTimeout`/`readTimeout`). **Отложен 16.10.2026:** production-реализации `HttpTool` в
   репозитории нет, ограничивать по времени нечего — задача ждёт первого реального клиента.

**Локальный долг.** `MAX_ATTEMPTS = 5` и `RETRY_BASE_PAUSE = 250ms` — дефолты из кода адаптера,
**не из грил-заметок**. Провенанс невосстановим: `git ls-files | grep grill` пусто. Согласование
дефолтов — отдельная задача, когда retry-параметры станут настраиваемыми через TOML (тикет 3).

**Что остаётся не покрыто `:common:retry`.** См. `docs/archive/retry/not-covered.md`: per-attempt
timeout (отдельный шов `HttpTool`), decorrelated jitter (избыточно для текущего профиля),
`Retry-After` от сервера (порт `HttpTool` не отдаёт), wall-clock-бюджет (Failsafe/Polly — у нас
budget по паузам, другое требование). Полный список и обоснование — в архивном файле.

**Несовместимо с этим под-деревом решений.**
- Введение зависимости на `kotlin-retry 2.0.2` (требует ADR на смену контракта `SourceAdapter`
  на их `Result`, плюс конвертация на шве).
- Введение зависимости на `kmp-resilient 2.0.1` (см. таблицу — три из шести целевых свойств
  платные обёртками).
- Введение зависимости на Arrow Resilience 2.2.3 (stateful-движок ломает чистую политику; пять
  транзитивных `arrow-*` модулей).
- Введение зависимости на Kresil (артефакт не опубликован на Maven Central; незрелая ниша,
  9★, 0 форков).

**Швы, которые это решение сохраняет.**
- `SourceAdapter` контракт — `suspend fun → DownloadResult`, `kotlin.Result` внутри.
- `HttpTool.open(url, headers)` — без таймаута на одну попытку (см. тикет 4).
- Конфиг-каскад `AppConfig → httpTool` (см. тикет 3).
