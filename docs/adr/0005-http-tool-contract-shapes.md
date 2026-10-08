# HttpTool: Result-форма, интерфейс HttpResponse и декодирование в порте

## Status

`accepted` (по итогам грила `http-tool`, 2026-10-06; родительский тикет спеки — #73 на
контракт порта + `FakeHttpTool`; см. `docs/features/http-tool/spec.md`).

## Context

Порт `HttpTool` существует в `core/port/HttpTool.kt`, его контракт на момент грила — три строки
KDoc плюс сигнатура `suspend fun open(url, headers): HttpResponse`. Используется только в
`RutubeSourceAdapter` и через фейк `FakeHttpTool` — в его сьютах. Пробелы задокументированы в
`ADR-0004` и `docs/features/common-retry/spec.md` (строки 200–234): `Content-Encoding`-декодирование
не зафиксировано, `Retry-After` не отдаётся, `perAttemptTimeout` реализован (#68), headers ответа
сознательно вне контракта. Цель грила — зафиксировать форму контракта до того, как появится
production-реализация: следующий движок будет писаться под зафиксированный шов, а не под
«как сейчас вышло».

Грил прошёл 4 раунда по 3–7 вопросов; пройдены развилки: что порт делает с транспортными сбоями,
что остаётся в `HttpResponse`, форма отдачи body, дефолтные request headers, cancellation,
контракт на `Closeable`.

## Decision

**`HttpTool.open` возвращает `Result<HttpResponse>`.** `Result.failure` — для транспортных сбоев
(DNS/TCP/TLS/timeout/EOF); `Result.success` — для всего, что сервер ответил, включая 4xx и 5xx.
Адаптер смотрит в `status` и решает, что значит «успех» в его сценарии; retry-движок опирается на
эту границу в `judging`. Альтернативы — проброс исключений (`IOException`-семейство) и
sealed-исход — отвергнуты: первая дублирует то, что `runCatching` в адаптере уже делает; вторая
добавляет типы, которые не окупаются (один реальный класс — `NetworkTransient` — уже есть
`DownloadResult`).

**`HttpResponse` — `interface` с двумя формами `content()`.** Члены: `status`, `contentLength?`,
`fun content(): InputStream`, `suspend fun <R> content(block: (InputStream) -> R): R`. Обе формы
отдают **один и тот же распакованный** поток: реализация порта снимает `Content-Encoding` (gzip/
deflate) до возврата. Альтернативы — оставить `data class` с `val body: InputStream` — отвергнуты:
закрытие стрима ложится на каждый адаптер, что в текущем коде уже привело к одному
«close-or-leak»-расследованию в `RutubeSourceAdapter`. Форма с блоком (`consume`/`content{}`)
делает утечку by design.

**`Accept-Encoding: identity` по дефолту.** Порт гарантирует, что сервер не сжмёт body, и
декодирование не нужно адаптеру. `Content-Encoding` остаётся ответственностью порта на случай,
когда адаптер явно переопределит заголовок (`Accept-Encoding: gzip`); в этом случае порт всё равно
снимает декодирование и отдаёт распакованный поток — `Content-Encoding` порт не отдаёт наружу как
часть `HttpResponse`.

**Дефолтные request headers:** `User-Agent: Tubeloader/0`, `Accept: */*`, `Accept-Encoding:
identity`. `Accept-Language` не выставляется — язык ответа зависит от сценария адаптера. Версия в
`User-Agent` — заглушка до появления версионирования приложения (отдельный тикет).

**Cancellation — контракт.** Отмена корутины, в которой вызван `open`, прерывает сетевую
операцию. `perAttemptTimeout` через `withTimeoutOrNull` — **ответственность адаптера**, который
оборачивает попытку в своей retry-обёртке (тикет #68 в `ADR-0004`); реализация порта и retry-движок
таймаут попытки не ставят.

**`HttpTool : Closeable` остаётся.** Lifecycle порта (shutdown всего приложения, закрытие пула
соединений) отделён от lifecycle ответа (закрытие стрима после `content{}`). Альтернатива — снять
`Closeable` с порта — отвергнута: OkHttp/Java HttpClient сами держат пул, но контракт на
shutdown всё равно полезен для тестов (`FakeHttpTool.close()` сбрасывает состояние).

**`Content-Type`, редиректы, подтипы `HttpResponse` — вне контракта.** Заголовки ответа и
`Retry-After` в контракт вошли (#75): `HttpResponse.headers`, поиск по имени без учёта регистра.
См. раздел «Out of contract by design» в `docs/features/http-tool/spec.md`.

## Consequences

**Совместимо с этим ADR.**

- Расширение `HttpResponse` поля (например, `headers: Map<String, String>`) — добавляет
  новый `val`, не ломает старые `content()`/`content{}`.
- Добавление новых форм `content()` (например, `contentAsBytes(): ByteArray`) — не ломает
  текущие.

**Несовместимо с этим ADR.**

- Возврат к `data class HttpResponse` с `val body: InputStream` — закрытие стрима снова
  переедет в адаптеры, расследования утечек вернутся.
- Проброс исключений из `open` (минуя `Result`) — нарушает шов с retry-движком, который
  ожидает различения transport/application по типу исхода.

**Швы, которые это решение сохраняет.**

- `SourceAdapter` контракт: `suspend fun → DownloadResult`, `kotlin.Result` внутри.
- Retry-движок `:common:retry`: `judging(status) → Boolean` остаётся основной точкой
  классификации, порт не добавляет новых полей, которые надо опрашивать.
- Конфиг-каскад `AppConfig → httpTool` (`ADR-0004`, тикет #67).

**Что ADR не закрывает.**

- Выбор конкретного HTTP-клиента (OkHttp / Ktor Client / `java.net.http.HttpClient`) —
  задача тикета #74; грил на этапе выбора движка проведён, решение — `ADR-0006`.
- Маппинг `HttpToolConfig.connectTimeout/requestTimeout` на настройки клиента — там же.
- Детализация `User-Agent` (версионирование приложения) — отдельный тикет.
