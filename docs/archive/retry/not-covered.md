# Архив: что не покрыто `:common:retry` и почему

Это архивная запись по итогам гриля `retry-развитие` от 2026-10-06. Главное решение — в
`docs/adr/0004-retry-engine-stays.md` и `docs/features/common-retry/spec.md`. Здесь — что
осталось **за бортом**: долг движка, кейсы из рассмотренных библиотек, не перенесённые в наш
код, наши преимущества и пробелы отрасли (если есть).

Источники фактов: README/CHANGELOG `kmp-resilient 2.0.1`, `arrow-kt.io/learn/resilience/retry-and-repeat/`,
`github.com/kresil/kresil` (README — артефакт не опубликован, sandbox-проба невозможна без
локальной сборки), Kotlin/Apache 2.0-ветки `kotlin-retry 2.0.2`, sandbox-пробы Arrow Resilience 2.2.3
и `kmp-resilient 2.0.1` в `.tasks/probe-arrow-resilience/` и `.tasks/probe-kmp-resilient/`.

## 1. Долг `:common:retry` — что не закрыто в движке

| Что | Где закрыто | У нас | Что мешает |
|---|---|---|---|
| `perAttemptTimeout` (таймаут одной попытки) | `kmp-resilient 2.0+` через `RetryPolicyConfig.perAttemptTimeout`; Failsafe/Polly через отдельный `Timeout` | Нет. Обходится `withTimeout` вокруг блока в адаптере | Не шов движка — обёртка живёт в адаптере (#68); ключ `per-attempt-timeout-ms` обязателен |
| Decorrelated jitter (AWS-формула) | kmp-resilient `DecorrelatedJitterBackoff`, Polly `MedianFirstJitterBackoff`, tenacity `wait_random_jitter` | Нет. Только ±N% `randomizationFactor` (идея 2 ADR-0004) | Сегменты качаются последовательно (память `Retry_libraries_survey_-_kotlin-retry_closest-877ba70d281e.md`), декорелированный jitter избыточен |
| `Retry-After` от сервера | resilience4j через `RetryAfter` response handler, Failsafe/Polly в `Handle`/`WaitAndRetry` | Нет. Экспонента как дешёвая замена | Заголовки отдаются портом с #75 (`HttpResponse.headers`), но движок судит по `status`; ждать паузу из header — отдельный тикет |
| Бюджет по паузам (`cumulativeDelay`) | `kotlin-retry` | Нет. Бюджет считается по прошедшему времени (`withinBudget`), как у Failsafe/Polly/tenacity | Сумма пауз не ограничивает реальное время: зависший запрос не двигает `cumulativeDelay` |
| Обёртка исходного значения (`RetryableResultException`, `MaxRetriesExceededException`) | kmp-resilient, resilience4j | Частично: дефолт — `RetryExhausted`, но исход задаёт `onExhausted` | Отрасль по умолчанию отдаёт последнее значение; у нас это доступно лямбдой, а дефолт сохраняет прежнее поведение |
| `RetryRegistry`/`Retry.ofDefaults`/`Retry()` без аргументов | resilience4j, Kresil, kmp-resilient | Нет — адаптер сам собирает политику | Не нужно скрывать явную сборку |
| `MAX_ATTEMPTS = 5`, `RETRY_BASE_PAUSE = 250ms` — провенанс невосстановим | n/a (наши) | Дефолты в `HttpToolConfig`, грил-заметок в git нет | Согласование дефолтов — отдельная задача |
| `Retry.onAttempt`, `Retry.onSuccess` (расширение `onRetry`) | Kresil `retry.onEvent`, Failsafe `FailsafeListener.onComplete` | Нет — только `onRetry` (идея 3 ADR-0004) и `onExhausted` (исход по значению) | Понадобятся — отдельный тикет, через те же `+`-точки, что и `onRetry` |
| `SharedFlow<ResilientEvent>` + `RetrySnapshot` + OTel/Micrometer export | kmp-resilient 2.0+ | Нет | Не нужно сейчас; `onRetry` callback закрывает минимум наблюдаемости |
| DSL-блок `retryConfig { … }` | Kresil, kmp-resilient | Нет — Compose-DSL (issue #63) | Ломает Compose-форму (`+`-оператор как точка сборки). Отдельный гриль при появлении use-case |

## 2. Кейсы из рассмотренных библиотек — не вошедшие в решение

Группировка: источник → кейс → почему не нужен / не сейчас.

### 2.1 Суд «ретраить или нет»

| Источник | Кейс | Почему не нужен |
|---|---|---|
| Polly `ShouldHandle(args.Outcome)` | Единый предикат на исключение ИЛИ значение | У нас раздельное: `retryIf` для исключений в политике, `retryOnResult` через `judging(T)` на вызове. Единый предикат семантически другой — смешивает протокольный уровень (значение) с транспортным (исключение) |
| Failsafe `.handleResultIf`, `.handleResult` | `Predicate<V>`, фильтрация значения | У нас через `judging`. Имя `retryOnResult` отраслевое (Polly/Failsafe/Guava) — берём; брать отдельный `.handleResultIf`-DSL нет смысла, форма сигнатуры уже зафиксирована |
| resilience4j `retryOnResultPredicate` + `failAfterMaxAttempts` | Условие «вернуть последнее значение» + «бросить на исчерпание» | У нас исчерпание по значению = `Result.failure(...)` — `DownloadResult.Failed(NetworkTransient)` в адаптере. «Вернуть последнее значение» ломает `kotlin.Result`-контракт |
| Kresil `retryIf { it is NetworkError }` | Предикат на исключение | У нас `continueIf { failure is IOException }` — эквивалент. Имя разное, форма та же |
| Failsafe `.abortIf`, `.abortOn`, `.abortWhen` | Форсированный стоп по условию | У нас через `+`-композицию со `StopRetrying`. `abortIf`-DSL — лишний уровень |
| kmp-resilient `onRetry(attempt, error)` listener-только-не-предикат | Видно метаданные только в `onRetry`, не в условии | У нас метаданные (`previousDelay`, `cumulativeDelay`) видны в предикате через `FailedAttempt` — функциональное преимущество, которое мы уже имеем |

### 2.2 Задержка между попытками

| Источник | Кейс | Почему не нужен |
|---|---|---|
| kmp-resilient `ExponentialBackoff(initialDelay, maxDelay, factor, jitter)` | Экспонента + jitter в одной фабрике | У нас `randomizationFactor` (идея 2) добавится отдельно. Формула (`base * 2^(n-1)`) та же |
| Arrow `.jittered(min, max, random)` | Декоратор-джиттер поверх произвольного `Schedule` | У нас `randomizationFactor` в самой фабрике `exponentialBackoff` — на выходе не `Schedule`, а `RetryPolicy`, композиция другая |
| Kresil `customDelay { attempt, context -> ... }` | Своя задержка через DSL | У нас выражается через `RetryPolicy { RetryAfter(customDelay(...)) }` — параллельный шов не нужен |
| Polly `WaitAndRetry` со списком длительностей (`WaitAndRetryPolicies.Constantize<...>`) | Передача массива пауз | У нас фабрика считает по формуле — не нужен массив |
| tenacity `wait_random` (random-uniform) | Чисто-случайная пауза | У нас `randomizationFactor` вокруг формулы экспоненты — uniform за рамками нашего профиля |

### 2.3 Контракт наружу

| Источник | Кейс | Почему не нужен |
|---|---|---|
| Arrow `Raise<E>.retry { ... }` / `either { retry(...) { ... } }` | Контракт через `Either<E, A>` или `Raise<E>` | У нас `kotlin.Result`. `Raise`/`Either` — это ADR на смену контракта `SourceAdapter` |
| Arrow `retryOrElseEither { value, error -> fallback }` | Recovery-функция при исчерпании | У нас recovery принадлежит адаптеру (`DownloadResult.Failed(error)` → `core.DownloadHandle`). Разделение — ответственность адаптера, не движка |
| resilience4j/Kresil `executeSupplier { ... }`, `decorateSupplier { ... }`, `decorateCheckedSupplier` | Тройка форм вызова | У нас `suspend fun retry(policy, ...)` — одна форма достаточна |
| Kresil `exceptionHandler { e -> ... }` | Handler исключений в конфиге | Смешивает потоки отмены с транспортной обработкой (память `Rutube_retry_-_4xx_filtered_by_value_before_retry_-_5xx_via_marker-0ed5834c83bb.md`): порядок `catch`-блоков важен, отмена — отдельно |

### 2.4 DSL и форма

| Источник | Kейс | Почему не нужен |
|---|---|---|
| Kresil `retryConfig { maxAttempts = N; retryIf { ... } }` | DSL-блок настроек | Compose-DSL (issue #63). DSL-блок ломает `+`-оператор как точку сборки |
| kmp-resilient `resilient { timeout{}; retry{}; circuitBreaker{} }` | Пайплайн-билдер | У нас нет CB/rate limiter/timeout-узла. Pipeline — будущее, не сейчас |
| Arrow `Schedule.andThen(other)` | Последовательная композиция («после K попыток с одной политикой — другая») | У нас не выражается через `+`-композицию. Use-case отсутствует — отдельный тикет при появлении |
| Arrow `Schedule.zipLeft`, `Schedule.zipRight`, `Schedule.collect` | Композиция stateful-расписаний | `Schedule` stateful — ломает чистую политику (`interface RetryPolicy`) |
| Arrow `forever().fold({ ... }, { ... })` | Ручная реализация бюджета через state | У нас бюджет встроен в `RetryPolicy` через `withinBudget` — без state |
| kmp-resilient `policy.events: SharedFlow<ResilientEvent>` | Reactive-типироузинг через `SharedFlow` | Нам не нужна `SharedFlow` для `onRetry`-callback'а. На 5–10 вызовов ретрая на одну загрузку callback достаточно |

### 2.5 Контекст и lifecycle

| Источник | Кейс | Почему не нужен |
|---|---|---|
| kmp-resilient `ResilientDeadline` (coroutine-context budget) | Wall-clock бюджет через coroutine context | У нас `withinBudget(elapsed)` в политике, без coroutine context — другая форма, не другой спрос |
| kmp-resilient `policy.cancelListeners()` | Управление listener-lifecycle | `onRetry` callback не владеет state'ом — lifecycle не нужен |
| resilience4j `RetryRegistry` (центральный registry политик) | Именованные политики | У нас каждая политика собирается в адаптере — registry не нужен |
| Failsafe `Failsafe.shutdown()` | Lifecycle-метод | Наш движок stateless — нет lifecycle |

## 3. Что НЕ покрыто ни одной из рассмотренных библиотек — наши преимущества

Свойства, которые ни одна публичная альтернатива не закрывает без обёртки:

1. **`kotlin.Result` + бюджет по прошедшему времени одновременно**. `kotlin-retry` держит
   `Ok/Err` и бюджет по `cumulativeDelay`; kmp-resilient/Arrow/Kresil — ни того, ни другого в
   паре с `kotlin.Result`. `kotlin.Result` на шве при wall-clock-бюджете — это наш движок.
2. **Предикат видит метаданные попытки** (`previousDelay`, `cumulativeDelay`). kmp-resilient
   прячет в `onRetry` listener (уведомление, не условие). Arrow показывает через stateful
   `Schedule` (но ломает чистую политику). Kresil показывает только в `customDelay` (delay,
   не условие).
3. **`RetryPolicy` + `Stage` + `+`-композиция**. Чистая функция как политика. У Arrow/Kresil
   политика stateful; у kmp-resilient `BackoffStrategy` sealed. Compose-DSL (`+`) уникален.
4. **`MAX_BACKOFF_STEP = 30` защита от переполнения shift**. Длинная экспонента с наивным
   `base * 2^(n-1)` даёт отрицательную паузу после ~30 итераций. Ни одна рассмотренная
   публичная библиотека не вытаскивает это в видимое свойство.
5. **`withinBudget(elapsed)` без дефолта бюджета**. У `kotlin-retry` бюджет по
   `cumulativeDelay` есть; в пайплайне по умолчанию не выключен — политика неявно ограничена.
   У нас бюджет задаёт вызывающий явно, политика не подменяется скрытым потолком.

## 4. Что НЕ покрыто нами и не закрыто никем — пробелы отрасли

По функциональному сравнению (см. `docs/adr/0004-retry-engine-stays.md`, таблица `Context`)
**явные пробелы отрасли не выявлены** — все рассмотренные случаи покрыты хотя бы одной из
библиотек (не всегда `kotlin.Result`-совместимой), а наши преимущества из раздела 3 не покрыты
ни одной. Уникальных пробелов, которые мы видим, а отрасль нет, в квизоне гриля нет.

Где отрасль «не договорилась» (не пробел, а открытый вопрос):
- **Нет** идиоматичного API для «retry по значению в `kotlin.Result`-мире». Failsafe/Polly/
  Guava — JVM-стиль через исключения и value-предикаты. Arrow/Kresil/kmp-resilient — через
  throws или `Either`. «`Result` на шве + значение-как-неуспех» — наш подход, отраслевого
  аналога нет.
- **Нет** открытого API для пользовательских backoff-стратегий в стиле `sealed interface`. Все
  рассмотренные библиотеки с `sealed` (kmp-resilient, Arrow) держат набор закрытым; «свою
  формулу» пользователь пишет только через фабрику-обёртку (как у нас через
  `RetryPolicy { RetryAfter(...) }`).
- **Нет** балансировки между «декоратор» (resilience4j/Kresil) и «чистая функция» (мы/
  kotlin-retry). Отрасль расколота. Это стилистический разрыв, не пробел.

## 5. Перепроверить позже

Список — что пересмотреть, если изменятся условия:

- **Если** появится второй сетевой транспорт (не HTTP) или публичный протокол с retry-семантикой
  — пересмотреть `[download.http-tool]`-под-блок vs `[download.retry]`-под-блок vs
  per-adapter-конфиг.
- **Если** появится use-case «после K попыток с одной политикой переключиться на другую» —
  Arrow `andThen`-семантика. Решение — отдельный гриль.
- **Если** retry появятся в параллели (одна загрузка, несколько сегментов, не последовательных)
  — decorrelated jitter (AWS) вместо `±N%`. Решение — отдельный тикет.
- **Если** появится `Retry-After` в порте `HttpTool` — правка порта + адаптер использует
  header, а не экспоненту. Порт заголовки уже отдаёт (#75); ждёт только потребителя.
- **Если** понадобится ограничить не только прошедшее время retry, но и длительность одной
  попытки — `perAttemptTimeout` (тикет #68). Wall-clock-бюджет к этому моменту уже есть.
- **Если** дойдёт до «+1 адаптер с другим стеком» — замерить вклад своего движка в CI-бюджет
(7 файлов, ~150 LoC, 44 теста). Цифры памяти
  `Kover_coverage_in_core_and_common_config` показывают 44 теста на `:common:config` после
  интеграции — наш retry тоже масштабируется в этот диапазон.
