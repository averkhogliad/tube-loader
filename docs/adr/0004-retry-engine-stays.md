# Повтор сетевых сбоев: остаёмся на `:common:retry`

## Status

`accepted` (по итогам гриля `retry-развитие`, 2026-10-06; влитие спеки `docs/features/common-retry/spec.md`
и этого ADR — тикет #65, родитель #64).

## Context

Адаптеры источников ретраят сетевые сбои (обрыв, таймаут, 5xx, 429) через собственный движок
`:common:retry` — семь исходников в `:common:retry/src/main/kotlin/io/averkhogliad/tubeloader/retry/`,
портирующих контракты `kotlin-retry 2.0.2` на наш шов (`kotlin.Result`, без generic по типу
ошибки, `+`-композиция `Stop`/`maxOf`, `withinBudget(elapsed)` без дефолта бюджета,
`MAX_BACKOFF_STEP = 30` от переполнения shift). Адаптеры собирают политику из фабрик
(`stopAtAttempts + continueIf + exponentialBackoff`) и ретраили по маркеру
`HttpStatusException : IOException` (память
`Rutube_retry_-_4xx_filtered_by_value_before_retry_-_5xx_via_marker-0ed5834c83bb.md`); маркер убран
задачей #66.

Пользователь поднял вопрос: «сравни и рассмотри возможность и необходимость перейти на
kmp-resilient — стандарт де-факто в отрасли», а потом уточнил рамку: «не “стандарт де-факто”, а
функциональное сравнение; либо обоснованно пишем своё, либо отказываемся в пользу библиотеки».
Гриль прошёл 4 раунда по 3 вопроса, исследовано четыре публичных альтернативы:

| Кандидат | Coroutines | `kotlin.Result` | Бюджет по времени | Чистая политика | `retryOnResult` | Отдельный артефакт | Лидер ниши |
|---|---|---|---|---|---|---|---|
| `:common:retry` (текущий) | ✅ | ✅ | ✅ | ✅ `RetryPolicy` + `Stage` | ✅ `judging` + `onExhausted` | n/a (наш) | n/a |
| `kotlin-retry 2.0.2` (michaelbull, 378★, ISC) | ✅ | ❌ свой `Ok/Err` | ⚠️ по паузам (`cumulativeDelay`) | ✅ | ✅ через `RetryOn.returned` | ✅ `kotlin-retry` | 378★ Kotlin coroutines retry |
| `kmp-resilient 2.0.1` (santimattius, 149★, Apache-2.0) | ✅ | ❌ throws last error | ✅ `ResilientDeadline` (вне политики) | ❌ `BackoffStrategy` sealed | ⚠️ `shouldRetryResult` (только Ktor-плагин) | ❌ тянет весь `resilient-jvm` | KMP-only resilience |
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
  2.0.1, не в обзоре); deadline у него есть, но живёт в `CoroutineContext`, а не в политике.

## Decision

**Остаёмся на `:common:retry`** как порте контрактов `kotlin-retry` на наши швы. Без зависимостей
от публичных библиотек. Переносим в движок три идеи из рассмотренных альтернатив:

1. **`retryOnResult` через `judging: (T) -> Boolean`** на вызове `retry`, вместе с `onRetry`
   и `onExhausted` в `RetryContext`. Источники: Polly
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
4. **`onExhausted: (T) -> Result<T>`** в `RetryContext` — чем становится значение, отвергнутое
   `judging`, когда попытки кончились; дефолт заворачивает его в `RetryExhausted`. Источники:
   kmp-resilient (последнее значение возвращается, `RetryableResultException` наружу не выходит),
   resilience4j (`failAfterMaxAttempts = false` по умолчанию), Failsafe (last result as is).

DSL — **Compose-форма** (issue #63, форма выбрана и опробована): `RetryPolicy` — обычный
`interface` (не `fun interface`) без generic по типу ошибки, `+`-оператор как `then`, приватный
`Combined`, `Stage` как receiver фабрик, предикат `continueIf` — лямбда с ресивером
`FailedAttempt.() -> Boolean`. Убирает 29 явных `<Throwable>` из цепочки. Форма `fun
interface` с `companion object` не компилируется: `companion object` требует конструктора у
интерфейса, а `fun interface` его не даёт; ковариантный generic-вариант с companion компилируется,
но падает в рантайме `ClassCastException`.

TOML — **плоский** под-блок `[download.http-tool]`: `connect-timeout-ms`, `request-timeout-ms`,
`per-attempt-timeout-ms`, `max-attempts`, `base-delay-ms`, `randomization-factor`,
`retriable-statuses`. Никакого вложенного
`[download.http-tool.retry]` — одна вложенность. `per-attempt-timeout-ms` обязателен: это
единственная настройка без дефолта, потому что таймаут попытки — решение конфигурации, а не
вкусовая константа.

DSL-блок `retryConfig { … }` (Kresil/kmp-resilient) — **отвергнут**: ломает Compose-форму #63
(`+`-оператор как точка сборки).

**Уточнения 07.10.2026 (решения пользователя перед прогоном #64).**

- **`judging`, `onRetry` и `onExhausted` идут одним швом:** драйвер принимает
  `RetryContext(judging, onRetry, onExhausted)`.
  Идеи добавляются одновременно, и раздельные параметры вывели бы сигнатуру за лимит ktlint
  (≤5 параметров). `onRetry` закрывается в тикете #66 вместе с `judging`.
- **Исход по значению выбирает вызывающий.** Обёртка в `RetryExhausted` была единственным
  исходом, а библиотеки отрасли отдают отклонённое значение как есть; теперь это лямбда
  `onExhausted`, дефолт сохраняет прежнее поведение. На исчерпании по исключению она не зовётся.
- **Источник случайности в `exponentialBackoff` — параметр.** При `randomizationFactor > 0`
  пауза считается как `base * (1 - factor + 2 * factor * random())`, где `random: () -> Double`
  по умолчанию `Math::random`. Тесты подставляют `Random(seed)`, поэтому окно джиттера
  проверяется детерминированно, а не флакает.
- **Бюджет считается по источнику времени, который передаёт вызывающий.** Сигнатура —
  `retry(policy, context, timeSource = TimeSource.Monotonic, block)`; `FailedAttempt.elapsed`
  меряется от старта retry. Тесты подставляют `TestTimeSource` и двигают время вручную, поэтому
  бюджет проверяется без ожидания реального времени.
- **`perAttemptTimeout` (#68) решён 07.10.2026 — ответственность адаптера.** Три таймаута
  разведены: `connectTimeout` и `requestTimeout` — HTTP-клиента (`HttpTimeout` в том
  `HttpClient`, который собирает вызывающий), `perAttemptTimeout` — адаптера, который оборачивает
  `HttpTool.open` в `withTimeoutOrNull` внутри своей retry-обёртки. Порт таймаут попытки не ставит и
  retry-движок его не ставит. Значение приходит из обязательного ключа `per-attempt-timeout-ms`
  (`per-attempt-timeout` — только у kmp-resilient среди исследованных движков; у Failsafe/Polly
  это отдельный `Timeout`). Был отложен 07.10.2026 как «нечего ограничивать» — обоснование снято:
  production-реализация `HttpTool` влита (PR #76), ключ в дефолт-конфиге уже стоит.
  **Реализован** (тикет #68, `ADR-0007`): `openWithRetry` оборачивает попытку в
  `withTimeoutOrNull(settings.perAttemptTimeout)`; `null` за собственный бюджет адаптер сам отдаёт
  политике маркером `AttemptTimedOut`, а `policyOf` принимает его рядом с
  `IOException` и `RetryExhausted`; на исчерпании попыток истёкшие таймауты дают `NetworkTransient`,
  а дедлайн самого вызывающего `withTimeoutOrNull` пропускает наверх отменой — драйвер её
  перебрасывает, как и любую отмену.

## Consequences

**Пять тикетов в трекере** (по состоянию на 2026-10-06):

Родитель #64, подзадачи:

1. #65 — спека, ADR-0004 и архив не-покрытого (эта выгрузка).
2. #66 — `retryOnResult` через `judging(T) → Boolean` в драйвере `:common:retry`, плюс
   `onRetry` в том же `RetryContext`. Правки
   `core/.../adapters/rutube/RutubeSourceAdapter.kt` (убрать `HttpStatusException`-маркер).
   ~60 LoC.
3. #69 — Compose-DSL из issue #63: влитие выбранной формы (`interface RetryPolicy` +
   `companion object : RetryPolicy` + `then`/`Combined`/`Stage`). ~40 LoC, 29 аннотаций `<Throwable>`
   уходят — счёт по всем `*.kt` на базовом коммите `a27badd`. Форма `fun interface` была отвергнута пробой: `companion object` требует конструктора
   у интерфейса, а `fun interface` его не даёт («Interface 'interface RetryPolicy : Any' does not
   have constructors»).
4. #67 — `[download.http-tool]` плоский блок + `HttpToolConfig` в `AppConfig` + интеграция
   в адаптер. ~50 LoC + 30 LoC тестов. Заблокирован тикетом #66.
5. #68 — `perAttemptTimeout` через `withTimeoutOrNull` вокруг `HttpTool.open` в retry-обёртке
   адаптера (долг из памяти `Retry_engine_in_common_retry-37360798096e.md`). **Разблокирован
   07.10.2026:** production-реализация `HttpTool` влита (PR #76), блокировка «нужны
   `connectTimeout`/`requestTimeout`» снята — ключ `per-attempt-timeout-ms` добавлен в
   `[download.http-tool]`.

**Локальный долг.** Константы `MAX_ATTEMPTS = 5` и `RETRY_BASE_PAUSE = 250ms` были дефолтами из
кода адаптера, **не из грил-заметок**: провенанс невосстановим, `git ls-files | grep grill` пусто.
Тикет #67 их удалил — значения приходят из `[download.http-tool]`, дефолты живут в `HttpToolConfig`.
Согласование дефолтов между источниками — отдельная задача.

**Что остаётся не покрыто `:common:retry`.** См. `docs/archive/retry/not-covered.md`: decorrelated
jitter (избыточно для текущего профиля), `Retry-After` от сервера (порт `HttpTool` не отдаёт).
Бюджет по паузам (Failsafe/Polly — wall-clock) движок не берёт: бюджет считается по прошедшему
времени. Полный список и обоснование — в архивном
файле.

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
