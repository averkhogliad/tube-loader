# Повтор сетевых сбоев и retry-параметры Tubeloader

## Problem Statement

Скачивание через адаптер источника регулярно упирается в сетевые сбои и временные ответы сервера
(5xx, 429): обрыв соединения, таймаут чтения, перегрузка upstream. Сегодня каждое такое событие
сразу превращается в `DownloadResult.Failed(NetworkTransient)`, и пользователь вынужден перезапускать
загрузку вручную. Без автоматического повтора Tubeloader выглядит хрупким относительно любой
нестабильной ссылки, при том что большинство этих сбоев проходит за секунды без вмешательства
человека.

Повтор должен жить там же, где живут остальные правила адаптера: в коде адаптера, с его протокольными
знаниями (какой статус считать временным, какие заголовки ретраить). Сам механизм повтора — общий:
он не должен зависеть от Rutube или от любого будущего источника, и не должен требовать менять
контракт `SourceAdapter` (`suspend fun → DownloadResult`, `kotlin.Result` на шве). Цифры повтора
— параметризуемые: пользовательский конфиг через TOML, без перекомпиляции.

Что должно остаться в проекте как общее правило:
- `kotlin.Result` — единственный способ отдать исход из retry-движка наружу;
- бюджет по **прошедшему времени** (wall-clock), а не по сумме пауз;
- политика — чистая функция, складываемая через `+`-композицию (Compose-DSL);
- предикат повтора видит метаданные попытки (номер, накопленная пауза, прошедшее время);
- отмена (`CancellationException`) перебрасывается, не ретраится.

## Solution

Остаётся собственный движок `:common:retry` — порт контрактов `kotlin-retry` на наши швы. В него
добавляются три идеи, перенятые из рассмотренных публичных библиотек без зависимости:

1. `retryOnResult` через `judging: (T) -> Boolean` на вызове `retry`. Источники: Polly
   `ShouldHandle(args.Outcome)`, Failsafe `.handleResult`/`.handleResultIf`, Guava `retryIfResult`,
   resilience4j `retryOnResultPredicate` + `failAfterMaxAttempts`, Kresil `retryOnResult`,
   kmp-resilient `shouldRetryResult`, tenacity `retry_if_result`, kotlin-retry `RetryOn.returned`.
   Закрывает запрет на синтетические маркеры: `HttpStatusException` в адаптере уходит.
2. `randomizationFactor: Double = 0.0` в `exponentialBackoff`. Источники: Kresil
   `exponentialDelay(..., randomizationFactor)`, kmp-resilient `ExponentialBackoff(..., jitter)`,
   Arrow `.jittered(min, max, random)`, Polly `jitter`, tenacity `wait_random`. Закрывает долг
   по джиттеру — профилактика retry storm, когда ретраи появятся в параллели.
3. `onRetry: ((FailedAttempt) -> Unit)?` callback в `retry(policy, …)`. Источники: Kresil
   `retry.onRetry { … }`, Failsafe `FailsafeListener`, resilience4j `RetryRegistry`,
   kmp-resilient `policy.events`. Закрывает долг по наблюдаемости — сейчас нет ни логов, ни
   метрик ретраев.

DSL — Compose-форма, выбранная в issue #63 и влитая задачей #69: `RetryPolicy` без
generic по типу ошибки, `+`-оператор как `then`, приватный `Combined`, `Stage` как receiver фабрик.
Это убирает 29 явных `<Throwable>` из цепочки без потери типовой безопасности на вызове.

Параметры повтора — через плоский под-блок `[download.http-tool]` в TOML: `connect-timeout-ms`,
`read-timeout-ms`, `max-attempts`, `base-delay-ms`, `randomization-factor`, `retriable-statuses`.
Без вложенного `[download.http-tool.retry]`: одна вложенность (`download` → `http-tool`)
сохраняется.

## User Stories

1. Как пользователь, я хочу, чтобы обрыв соединения во время скачивания повторялся автоматически,
   чтобы не перезапускать загрузку вручную при временной потере сети.
2. Как пользователь, я хочу, чтобы временный ответ сервера (HTTP 503) приводил к повтору, чтобы
   upstream-нагрузка не превращалась в ручной перезапуск.
3. Как пользователь, я хочу, чтобы повтор не трогал постоянные ошибки (HTTP 404, 403, 410), чтобы
   бесполезный повтор не увеличивал время ожидания.
4. Как пользователь, я хочу, чтобы между попытками была растущая пауза (экспонента с потолком),
   чтобы не долбить упавший сервер с фиксированным интервалом.
5. Как пользователь, я хочу, чтобы в конфиге можно было включить лёгкий джиттер паузы, чтобы
   параллельные повторы не выстраивались в синхронную волну.
6. Как пользователь, я хочу, чтобы количество попыток и стартовая пауза задавались через TOML,
   без перекомпиляции проекта.
7. Как пользователь, я хочу, чтобы набор «ретраябельных» статусов задавался в конфиге, чтобы
   развёртывание могло реагировать на изменения upstream без правки кода.
8. Как разработчик, я хочу выразить политику повтора как композицию маленьких фабрик
   (`stopAtAttempts` + `continueIf` + `exponentialBackoff`), чтобы каждый кусок был независимо
   читаем.
9. Как разработчик, я хочу писать цепочку политики без аннотаций `<Throwable>` в каждом звене,
   чтобы рефакторинг типа ошибки не требовал править 29 точек.
10. Как разработчик, я хочу иметь `judging: (T) -> Boolean` на вызове `retry`, чтобы протокольное
    знание «какой статус плохой» принадлежало адаптеру, а не движку.
11. Как разработчик, я хочу, чтобы движок не требовал от меня создавать синтетические исключения
    для «плохого значения», чтобы `Result.failure(HttpStatusException(...))` остался анти-паттерном
    Polly-цитаты, а не нашим.
12. Как разработчик, я хочу, чтобы движок отдавал `kotlin.Result`, а не свой или бросал исключение,
    чтобы контракт `SourceAdapter` (`DownloadResult` через `Result`) не переучивался.
13. Как разработчик, я хочу, чтобы отмена перебрасывалась как `CancellationException`, а не
    ретраилась, чтобы отмена пользователя не превращалась в повтор «ещё разок».
14. Как разработчик, я хочу, чтобы между попытками срабатывал `ensureActive()`, чтобы отмена во
    время паузы не пережила её в следующую попытку.
15. Как разработчик, я хочу, чтобы в `FailedAttempt` был `previousDelay`, `cumulativeDelay` и `elapsed`,
    чтобы предикат мог видеть, сколько уже отспит и сколько всего прошло, и решать «стоп по бюджету».
    Предикат получает `FailedAttempt` как ресивер — вместе с ошибкой ему доступны номер попытки
    и остальные метаданные, без второго параметра в сигнатуре.
16. Как разработчик, я хочу задавать бюджет прошедшего времени вызывающим кодом явно, без дефолта,
    чтобы политика не подменялась скрытым потолком, провенанс которого невосстановим.
17. Как разработчик, я хочу, чтобы `exponentialBackoff` имел защиту `MAX_BACKOFF_STEP`, чтобы длинная
    экспонента не превращалась в отрицательную паузу из-за переполнения shift.
18. Как разработчик, я хочу иметь callback `onRetry`, чтобы лог/метрика ретрая не требовали
    отдельного канала наблюдения (например, `SharedFlow`) и не тянули новых зависимостей.
19. Как разработчик, я хочу видеть поведение движка в тестах без сети и без времени
    (`kotlinx.coroutines.test.runTest`), чтобы retry-логика была проверена детерминированно.
20. Как разработчик, я хочу, чтобы движок жил в общем модуле `:common:retry` сиблингом
    `:common:config`, а не внутри `:common` (тот — `java-platform` без Kotlin-кода).

## Implementation Decisions

**Модуль.** `:common:retry` (Kotlin/JVM, `java-test-fixtures`). Сиблинг `:common:config`, не внутри
`:common` (память «Retry engine in `:common:retry`»: `:common` — `java-platform`, Kotlin-код в
нём не заводится). Гейт Kover 80/75 распространяется автоматически.

**Контракт наружу — `kotlin.Result`.** Драйвер — `suspend fun <T> retry(policy, context, timeSource, block): Result<T>`,
где `context: RetryContext<T>` несёт `judging`, `onRetry` и `onExhausted` (объединение продиктовано
лимитом ktlint на сигнатуру), а `timeSource: TimeSource = TimeSource.Monotonic` задаёт, от чего
меряется `FailedAttempt.elapsed`; тесты подставляют `TestTimeSource` и двигают время вручную.
`Ok(value)` при `judging(value) == false` — неудачная попытка, идёт в
тот же цикл. `CancellationException` перебрасывается, `ensureActive()` после `delay`. Контракт
`SourceAdapter` сохраняет `kotlin.Result`/`DownloadResult.Failed` без смены шва.

**Политика — чистая функция, Compose-DSL.** `RetryPolicy` без generic по типу ошибки (issue #63).
`+`-оператор как `then`, приватный `Combined`, `Stage` как receiver фабрик. `FailedAttempt(failure,
number, previousDelay, cumulativeDelay, elapsed)` предикат `continueIf` получает **как ресивер**
(`continueIf { number < 3 && failure is IOException }`), поэтому ошибка и метаданные попытки
читаются в одном условии — преимущество, которое ни одна из рассмотренных публичных альтернатив
не даёт без обёртки.

**Три новые оси (без зависимостей).**
- `judging: (T) -> Boolean` на вызове `retry` (по умолчанию `{ true }`), поле `RetryContext`.
  Закрывает долг «синтетические маркеры».
- `randomizationFactor: Double = 0.0` в `exponentialBackoff`. При `0.1` пауза — `random(base*0.9,
  base*1.1)` вокруг формулы. Источник случайности — параметр `random: () -> Double`
  (по умолчанию `Math::random`); тесты подставляют `Random(seed)`, поэтому окно проверяется
  детерминированно. Декоррелированный jitter (AWS) не нужен сейчас: сегменты качаются
  последовательно (память «Retry libraries survey — kotlin-retry closest»).
- `onRetry: ((FailedAttempt) -> Unit)?` — поле `RetryContext` (по умолчанию `null`). Перед каждой
  паузой. `SharedFlow`/`RetrySnapshot`/телеметрия отложены — на этом этапе достаточно callback'а
  без новых зависимостей.
- `onExhausted: (T) -> Result<T>` — поле `RetryContext` (по умолчанию `{ Result.failure(
  RetryExhausted(it)) }`). Решает, чем становится значение, отвергнутое `judging`, когда попытки
  кончились: обёрткой `RetryExhausted` (дефолт, прежнее поведение) или самим значением
  (`{ Result.success(it) }`). Источники: kmp-resilient (`RetryableResultException` наружу не
  выходит — отдаётся последнее значение), resilience4j (`failAfterMaxAttempts = false` по
  умолчанию), Failsafe (last result as is). На исчерпании по исключению не зовётся.

**TOML — плоский под-блок.** `[download.http-tool]`: `connect-timeout-ms`, `read-timeout-ms`,
`max-attempts`, `base-delay-ms`, `randomization-factor`, `retriable-statuses`. Конфиг резолвится
в `HttpToolConfig` (`AppConfig.httpTool`) его собственным `fromConfig` через `Config.getTableOrNull("download.http-tool")`.
Никакого вложенного `[download.http-tool.retry]` — одна вложенность (`download` → `http-tool`).

**Что не делается в этой миграции.** Decorator-style API (`executeSupplier`/`decorateSupplier`),
DSL-блок `retryConfig { … }`, `exceptionHandler`, decorrelated jitter,
`SharedFlow<ResilientEvent>`, `RetrySnapshot` и телеметрия-флоу — см.
`docs/archive/retry/not-covered.md`.

**Связь с другими решениями.** ADR-0004 (это решение). Инварианты — `docs/standards/architecture.md`.
Стандарт ошибок — `docs/standards/errors.md`: наружу выходит `DownloadResult.Failed(error)`,
`Result`-фасад внутренний. Стандарт тестов — `docs/standards/testing.md`. Стандарт линта —
`docs/standards/build.md`: сигнатуры ≤5 параметров (порог ktlint). Форма принята: `judging`,
`onRetry` и `onExhausted` объединяются через `RetryContext`, драйвер вызывается как
`retry(policy, context, block)`.

## Testing Decisions

**Что делает хороший тест.** Тест проверяет **наблюдаемое** поведение драйвера: какие попытки были
сделаны, какие паузы между ними, какой исход вернулся, что произошло с `onRetry` и `judging`. Тест
**не** проверяет внутренние поля `RetryPolicy` или приватный `Combined` — это шов реализации.

**Швы, на которых тесты:**
1. `:common:retry` — unit-тесты на драйвер и фабрики (`RetryTest`, `PoliciesTest`).
2. `:common:retry` — фейки политик рядом с тестами (`RetryFixtures`): `java-test-fixtures` подключён,
   но каталога `src/testFixtures` в модуле нет, `RecordingPolicy` живёт в `src/test/`.
3. `core/.../adapters/rutube` — контрактные тесты на retry-логику адаптера (`RutubeRetryTest`):
   `judging` видит `HttpBody.status`, исчерпание → `DownloadResult.Failed(NetworkTransient)`,
   нетранзиентный статус не ретраится, транзиентный (408/429/502/503/504) ретраится,
   `CancellationException` не проглатывается.
4. `core/.../config` — unit-тесты на чтение `[download.http-tool]` (`HttpToolConfigTest`):
   дефолты, отсутствующий блок, невалидные значения.

**Prior art в проекте:**
- `RetryTest`, `PoliciesTest`, `RetryFixtures` в `:common:retry` — kotest 6 FreeSpec,
  `kotlinx-coroutines-test`, `TestScope`.
- `RutubeRetryTest` в `core/src/test/kotlin/io/averkhogliad/tubeloader/adapters/rutube/`.
- `core/testFixtures/.../port/FakeHttpTool` — фейк для HTTP-уровня.
- `core/testFixtures/.../adapter/FakeSourceAdapter` — фейк для `SourceAdapter`-уровня.

**Конкретные кейсы, которые должны быть покрыты (минимум):**
- успех первой попытки — `onRetry` не зовётся, `judging` уже увидел значение;
- `judging(value) == false` после `Ok` — попытка считается неуспешной, идёт в цикл;
- исчерпание по `judging` — исход задаёт `RetryContext.onExhausted`: по умолчанию `Result.failure(
  RetryExhausted(value))`, с `{ Result.success(it) }` — само значение;
- исчерпание по исключению — `onExhausted` не зовётся, наружу идёт исходное исключение;
- `judging` отвергает `null` — заворачивается именно `null`, а не теряется;
- `CancellationException` из блока перебрасывается, не считается `IOException`;
- `withinBudget` — стоп, когда прошедшее время вместе с предстоящей паузой превышает бюджет;
- `randomizationFactor = 0.0` — детерминированная последовательность; `> 0.0` с seeded
  `Random` — паузы в окне `[base*0.9, base*1.1]`;
- `+`-композиция: `Stop` с любой стороны — стоп; `maxOf` пауз;
- отмена во время `delay` — следующая попытка не стартует (кейс закрыт на уровне адаптера:
  `RutubeRetryTest` «breaks the retry wait as soon as the task is cancelled»).

## Out of Scope

- **Зависимость от публичной retry-библиотеки.** `kotlin-retry 2.0.2`, `kmp-resilient 2.0.1`,
  Arrow Resilience 2.2.3, Kresil рассмотрены, отвергнуты функционально (память
  `kmp-resilient_as_retry_replacement_1.5.0_-_2.0.1-48700a979f05.md`,
  `Retry_libraries_survey_-_kotlin-retry_closest-877ba70d281e.md`). Подробности —
  `docs/archive/retry/not-covered.md`.
- **`Retry-After` от сервера.** Порт `HttpTool.open` его не отдаёт. Правка порта — отдельный
  тикет/ADR. В этом — экспонента как дешёвая замена.
- **`perAttemptTimeout` через `withTimeout` (задача #68).** Таймаут одной попытки — отдельный шов
  `HttpTool`, не retry-движка. Реализация **отложена** вместе с первой production-реализацией
  `HttpTool`/`MediaTool`: в репозитории есть только порт и тестовый фейк, ограничивать по времени
  нечего. Параметры `connect-timeout-ms`/`read-timeout-ms` при этом читаются в `HttpToolConfig`
  (задача #67) — их ждёт первый реальный клиент.
- **Decorrelated jitter (AWS).** `randomizationFactor` ±N% достаточно для текущего профиля
  (сегменты последовательные).
- **DSL-блок `retryConfig { … }`.** Ломает Compose-форму #63. Отдельный гриль при появлении
  use-case.
- **`SharedFlow<ResilientEvent>`, `RetrySnapshot`, телеметрия-флоу.** Не нужно сейчас; `onRetry`
  callback закрывает минимум.
- **`Retry.onAttempt`, `Retry.onSuccess`.** Понадобятся — расширение `onRetry` отдельным тикетом.
- **`MAX_ATTEMPTS = 5`, `RETRY_BASE_PAUSE = 250ms` как константы адаптера.** Удалены задачей #67;
  значения приходят из `[download.http-tool]`, дефолты живут в `HttpToolConfig`. Согласование
  дефолтов между источниками — отдельный тикет.
- **Долг по другим адаптерам.** yt-dlp (Delegate) — отдельная спека.

## Further Notes

**Связанные документы.**
- `docs/adr/0004-retry-engine-stays.md` — обоснование выбора.
- `docs/archive/retry/not-covered.md` — что не покрыто `:common:retry` и почему (долг, кейсы из
  рассмотренных библиотек, наши преимущества, пробелы отрасли).
- `docs/standards/architecture.md` — инвариант «повторы сетевых сбоев — механизм ядра, не
  адаптера» (`docs/standards/architecture.md:12` по памяти).
- `docs/standards/errors.md` — внутренний `Result`-контракт vs внешний `DownloadResult.Failed`.
- `docs/standards/build.md` — Kover-гейт 80/75 на `:common:retry`.
- `docs/standards/testing.md` — `*Test.kt` + `Gen.kt` + `testFixtures`, правила стиля.

**Открытые тикеты (по состоянию на 2026-10-07).**
- Issue #63: Compose-DSL — форма влита задачей #69 (коммит `3ed5437`).
- Issues #64–#69: родитель и пять подзадач этого решения. В работе одним PR: #65, #69, #66, #67;
  #68 отложен.

**Соответствие подзадач Weeek и тикетов GitHub.** 166 = #65, 168 = #69, 167 = #66,
169 = #67, 170 = #68 (отложена).

## Tickets

GitHub: родитель #64, подзадачи #65–#69 (нативная связь sub-issue).

| GitHub | Что | Заблокирован |
|---|---|---|
| #65 | спека, ADR-0004 и архив не-покрытого | — |
| #66 | суд по значению (`retryOnResult`) без синтетических исключений | — |
| #69 | Compose-DSL политики без аннотаций `<Throwable>` | — |
| #67 | параметры повтора в TOML (`[download.http-tool]`) | #66 |
| #68 | таймаут одной попытки в `HttpTool` — **отложен** вместе с первой production-реализацией `HttpTool`/`MediaTool` | #67 |

Weeek (бизнес-представление, доска проекта 2, колонка 4): родитель **165** «Устойчивое скачивание
при временных сбоях сети»; этапы **166** (описание), **167** (повтор при временных сбоях),
**168** (читаемые правила), **169** (настройка из конфигурации), **170** (ожидание одной попытки).
Ключ коммитов и заголовка PR — `[165]` (только Weeek-id; GitHub-номер в квадратные скобки не
подставлять).
