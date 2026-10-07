# HttpTool: Ktor Client 3.x + CIO как production-движок

## Status

`accepted` (по итогам грила `http-tool/engine`, 2026-10-06; продолжает `ADR-0005`; тикет — #74
на production-реализацию).

## Context

`ADR-0005` зафиксировал форму контракта `HttpTool` (`Result<HttpResponse>`, `interface`
`HttpResponse` с двумя `content()`) и отложил выбор движка до грила «на этапе выбора движка»
(`ADR-0005` → «Что ADR не закрывает»). В репозитории нет production-реализации: тесты адаптеров
идут через `FakeHttpTool` (`core/testFixtures`), `RutubeSourceAdapter` принимает `HttpTool`
параметром и в проде использует ту же фабрику, что и приложение.

Текущий стек (факт `libs.versions.toml`, 2026-10-06): Kotlin/JVM 2.4.20, toolchain 21,
`kotlinx-coroutines-core` 1.11.0, `kotlinx-serialization-json` 1.11.0. `:core` зависит только от
этих двух артефактов и наших `:common:config`/`:common:retry`. KMP не заявлен (`common` —
`java-platform`), фронтенд — Compose Desktop (JVM).

Грил прошёл 4 раунда по 3–5 вопросов; пройдены развилки: кандидат движка, пакет реализации,
форма фабрики, тестовая стратегия, поведение `Content-Encoding`-декодирования, таймауты.

## Decision

**Движок — Ktor Client 3.x с engine `ktor-client-cio`.** Зависимости в `:core`:

```
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.cio)
implementation(libs.ktor.client.encoding)
```

`ktor-client-encoding` нужен и не является транзитивным: `ktor-client-core` и `ktor-client-cio` не
содержат плагина `ContentEncoding` (проверено пробой: с ними gzip-тело приходит адаптеру сырыми
байтами). Плагин устанавливается в тот `HttpClient`, который создаёт вызывающий:
`install(ContentEncoding) { gzip() }`.

`ktor-client-okhttp` отвергнут: под ним тянется OkHttp, и тогда Ktor — лишний слой над тем, что
можно взять по честному. Альтернативы — JDK `java.net.http.HttpClient` (0 зависимостей, но
`readTimeout` тоже отсутствует, и нужен ручной coroutine-мост через `suspendCancellableCoroutine`)
и `ktor-client-okhttp` (тащит OkHttp транзитивно) — отвергнуты по тем же причинам: либо
избыточный мост, либо лишний слой.

**Пакет реализации — `core.http` в `:core`.** Новый Gradle-модуль не заводим: единственная
реализация, импорт `io.ktor.*` живёт только в этом пакете, `core.port` остаётся чистым. Если
появится вторая реализация (OkHttp / JDK / native) — модуль выделяется отдельным тикетом.

**Маппинг `HttpToolConfig` в Ktor.** Текущие 6 полей → переоформление:

| Поле | Было | Стало | Ktor-side |
|---|---|---|---|
| `connectTimeout: Duration` | ✅ | ✅ | `HttpTimeout.connectTimeoutMillis = connectTimeout.inWholeMilliseconds` |
| `readTimeout: Duration` | ✅ | **удалено** | (нет split readTimeout в Ktor) |
| `requestTimeout: Duration` | — | ✅ новое | `HttpTimeout.requestTimeoutMillis = requestTimeout.inWholeMilliseconds` |
| `retryMaxAttempts: Int`, `retryBaseDelay: Duration`, `retryRandomizationFactor: Double`, `retryRetriableStatuses: Set<Int>` | ✅ | ✅ без изменений | retry — не ответственность Ktor, остаётся в `:common:retry` (`ADR-0004`) |

Тип `kotlin.time.Duration` — те же единицы, что и Ktor `HttpTimeout`, маппинг — `Duration.inWholeMilliseconds`.
Дефолты `HttpToolConfig` сохраняются: `connectTimeout = 5.seconds`, `requestTimeout = 30.seconds`,
retry-блок — без изменений (`HttpToolConfigTest` 193 строки переписывается вместе с тикетом #74,
это `HttpToolConfig` уже зашит TODO в тикете #67).

**Default request headers.** Ktor-клиент создаётся с блоком:

```kotlin
HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = config().connectTimeout.inWholeMilliseconds
        requestTimeoutMillis = config().requestTimeout.inWholeMilliseconds
    }
    install(ContentEncoding) { gzip() }
    expectSuccess = false            // non-2xx -> Result.success со status: Int, не throw
    followRedirects = false          // финальный URL наружу не отдаём (ADR-0005, Q9 спеки)
    engine { /* пустой, дефолты CIO */ }
}
```

Headers `User-Agent: Tubeloader/0`, `Accept: */*`, `Accept-Encoding: identity` подставляются
нашей обвязкой в `KtorHttpTool.open` (не через Ktor `defaultRequest`-блок — нужно мержить с
адаптерскими headers, Ktor это делает по case-insensitive, мержим аккуратно).

**Два пути создания** (Q13):

```kotlin
// core/http/KtorHttpTool.kt
class KtorHttpTool(
    private val client: HttpClient,
) : HttpTool {
    override suspend fun open(url: String, headers: Map<String, String>): Result<HttpResponse>
    override fun close() = Unit   // клиент принадлежит вызывающему, закрывать нечего
}

object HttpTools {
    fun create(client: HttpClient): HttpTool = KtorHttpTool(client)
}
```

Прод-сценарий — конструктор; фабричный метод — для DI-графа приложения и тестов (где нужно
передать `HttpClient(MockEngine { ... })`).

**Cancellation.** Ktor нативно: `client.request(...)` — suspend, отмена корутины бросает
`CancellationException` и гасит сокет в engine. Контракт `ADR-0005` («отмена корутины прерывает
сетевую операцию») выполняется без обвязки. `HttpTimeout.requestTimeoutMillis` отменяется
отдельным `TimeoutCancellationException`; внешняя отмена приоритетна.

**Тесты.** Один класс `KtorHttpToolTest` в `core/src/test`, 7 кейсов на
`HttpClient(MockEngine { ... })` (`ktor-client-mock` в testImplementation):

1. Happy: 200 + body → `Result.success(HttpResponse(status=200, body=stream, ...))`.
2. 4xx/5xx: `expectSuccess = false` → `Result.success` со `status != 200`.
3. Transport fail: `MockEngine` бросает `IOException` → `Result.failure(IOException)`.
4. `content { block }`: блок выполнен, поток закрыт (наблюдается по каналу, который отдаёт движок).
5. **Декодирование (Q12)**: `Accept-Encoding: identity` + сервер не сжимает → отдаём как есть;
   адаптер шлёт `Accept-Encoding: gzip` + сервер отвечает `Content-Encoding: gzip` с gzip-байтами
   → `content { readBytes() }` возвращает распакованные байты.
6. Заголовок `Retry-After` достижим из `HttpResponse.headers` (#75).

Контрактные тесты адаптеров остаются на `FakeHttpTool` — `ktor-client-mock` не подменяет шов.

## Consequences

**Совместимо с этим ADR.**

- Расширение `HttpToolConfig` (новые поля таймаутов/headers) — добавляет новый параметр в
  `KtorHttpTool`-конструктор, тесты обвязки обновляются точечно.
- Замена engine на `ktor-client-okhttp` или `ktor-client-java` — переключается в одной строке
  `HttpClient(...)`, контракт `HttpTool` не меняется.
- Расширение `HttpResponse` (например, `headers: Map<String, String>`) — правка `KtorHttpResponse`,
  `HttpTool`-контракт не меняется.

**Несовместимо с этим ADR.**

- Возврат `HttpToolConfig.readTimeout` как отдельной настройки — Ktor `HttpTimeout` не даёт split
  read/connect; вводить `readTimeout` обратно — отдельный ADR на смену движка или на новый шов
  (например, `X-Tubero-Read-Timeout`).
- Снятие `expectSuccess = false` (то есть возврат к дефолту Ktor — выброс на non-2xx) — ломает
  retry-движок, который ожидает различения transport/application по типу исхода (`ADR-0005`).
- Переезд на `ktor-client-okhttp` без смены ADR — OkHttp тащится транзитивно, +700 KB, см. выше.

**Швы, которые это решение сохраняет.**

- `HttpTool` контракт (`ADR-0005`): `Result<HttpResponse>`, `interface HttpResponse` с двумя
  `content()`, default headers, cancellation.
- Retry-движок `:common:retry` (`ADR-0004`): `judging(status)` остаётся точкой классификации,
  порт не добавляет полей для опроса.
- `HttpToolConfig` (`ADR-0004`, тикет #67): дефолты живут в `HttpToolConfig`, retry-блок
  применяется адаптером поверх `HttpTool.open` через `openWithRetry` (Rutube) — поведение
  воспроизводится при замене движка.

**Зависимости `:core` после #74.**

```
implementation(libs.kotlinx.coroutines.core)
implementation(libs.kotlinx.serialization.json)
implementation(libs.ktor.client.core)
implementation(libs.ktor.client.cio)
implementation(libs.ktor.client.encoding)
implementation(project(":common:config"))
implementation(project(":common:retry"))
```

**Что ADR не закрывает.**

- Версионирование `User-Agent` (формат `Tubeloader/<version>`) — отдельный тикет.
- Авторизация / cookies / прокси — за рамками текущего релиза.
- Range/streaming-запросы — отдельный тикет, текущий `content { ... }` покрывает обычные сценарии.
