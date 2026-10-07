# Контракт порта HTTP-чтения (HttpTool)

## Problem Statement

`HttpTool` сейчас — порт в `core/port/HttpTool.kt` без отдельной спеки: контракт `open`
существует только как заголовочный комментарий и через два места использования (Rutube-адаптер и
`FakeHttpTool` в testFixtures). Из-за этого одинаково трудно (а) добавить следующий источник, который
захочет того же набора операций, и (б) написать production-реализацию порта, не переизобретая
контракт заново. Текущие пробелы задокументированы в `ADR-0004`: `Content-Encoding`-декодирование
лежит на адаптере неявно, форма возврата body толкает адаптер к ручному закрытию стримов, headers
ответа и `Retry-After` не отдаются наружу намеренно, но без спеки это «намеренно» не видно.

## Solution

Спека фиксирует контракт `HttpTool` как «порт, отдающий значение»: `Result<HttpResponse>`, где
`HttpResponse` несёт `status` + `contentLength` + `headers` и ровно две формы чтения body — `content()` как
стрим и `content(block)` как блок. Декодирование transport encoding — ответственность реализации
порта, адаптер получает уже распакованный поток. Дефолтные request headers (`User-Agent`,
`Accept`, `Accept-Encoding: identity`) выставляет порт; адаптер может переопределить. Заголовки
ответа доступны через `headers`; `contentType`, редиректы, подтипы `HttpResponse` и прокси —
сознательно вне контракта и попадут туда отдельными тикетами.

Реализация самого `HttpTool` в эту спеку не входит — `ADR-0004` откладывает её до первого реального
HTTP-клиента. Спека фиксирует **что** должны соблюдать существующая и будущая реализации, не
**как** они это делают.

## User Stories

1. Как разработчик адаптера, я хочу вызвать `open(url, headers): Result<HttpResponse>`, чтобы
   получить тело ответа или упасть на транспортном сбое, без выбора HTTP-клиента в адаптере.
2. Как разработчик адаптера, я хочу прочитать `status` ответа, чтобы отличить «ресурс не найден»
   от «транспорт упал» и решить, ретраить или отдавать `DownloadResult.NotFound`.
3. Как разработчик адаптера, я хочу получить распакованный body (`Content-Encoding` уже снят
   портом), чтобы парсер JSON не падал на gzip-байтах.
4. Как разработчик адаптера, я хочу вызвать `response.content { ... }` и не думать про закрытие
   стрима, чтобы не утекали дескрипторы и не плодились «забыл close()» баги.
5. Как разработчик адаптера, я хочу получить стрим через `response.content()` для редких сценариев
   (например, копирование в `OutputStream` через `transferTo`), чтобы не потерять эту возможность
   совсем.
6. Как разработчик адаптера, я хочу, чтобы `HttpTool` ставил дефолтные `User-Agent`/`Accept`/
   `Accept-Encoding: identity`, чтобы хостинги не отбивали запросы 403-ей из-за пустого UA.
7. Как разработчик адаптера, я хочу, чтобы отмена корутины, в которой вызван `open`, прерывала
   сетевую операцию, чтобы дауншифтить таймауты через `withTimeout` снаружи без побочных
   эффектов.
8. Как разработчик ядра, я хочу переиспользовать `FakeHttpTool` во всех адаптерных сьютах, чтобы
   тесты HTTP-уровня оставались детерминированными и без сети.

## Implementation Decisions

**Порт.** `HttpTool : Closeable`. Закрытие — операция над владельцем порта (приложением), не над
адаптером: адаптер берёт порт параметром и использует на каждой операции, закрывает его тот, кто
его создал. Закрытие идемпотентно.

**Сигнатура `open`.** `suspend fun open(url: String, headers: Map<String, String> = emptyMap()):
Result<HttpResponse>`. `Result.failure` — для транспортных сбоев: DNS, TCP, TLS, таймаут.
`Result.success` — для всего, что сервер ответил, включая 4xx и 5xx; адаптер смотрит в `status` и
решает сам, что значит «успех для адаптера». Эта граница — единственный шов между «порт не
справился» и «порт справился, сервер сказал своё слово», и retry-движок опирается на неё в
`judging`.

Оборванное **тело** в этот набор не входит: `content(block)` отдаёт адаптеру `InputStream`, поэтому
обрыв посреди чтения бросает исключение наружу из блока, а не приходит значением. Обёртка вокруг
чтения — дело вызывающего: адаптер ловит `IOException` и переводит его в свой исход
(`RutubeSourceAdapter.loadMeta`).

**`HttpResponse`.** Интерфейс с четырьмя членами:

- `val status: Int` — HTTP-статус ответа; для перенаправлений (3xx) — статус финального ответа,
  на который реализация порта уже перешла (см. «Out of contract by design»).
- `val contentLength: Long?` — длина тела, если сервер её передал; `null` для chunked-ответа
  или `Transfer-Encoding` без `Content-Length`.
- `val headers: Headers` — заголовки ответа, по одному значению на имя; имя хранится как его
  написал сервер. Поиск по имени **без учёта регистра**: на HTTP/2 сервер отдаёт имена в нижнем
  регистре, поэтому `headers["Retry-After"]` обязан находиться. `Retry-After` достижим отсюда
  как обычный заголовок ответа.
- `fun content(): InputStream` — отдаёт распакованный поток. Вызывающий закрывает его; для
  большинства сценариев рекомендуется форма с блоком.
- `suspend fun <R> content(block: (InputStream) -> R): R` — читает тело в блоке; порт закрывает
  поток в `finally` независимо от исхода блока. Это основной путь для адаптеров: парсеров JSON,
  скачивания в файл через `transferTo`, подсчёта байт. Реализация Ktor буферизует тело ответа
  целиком (`client.request(url) {}`), поэтому блок отдаёт уже прочитанные байты: `open` для
  тела в 1.5 s возвращается через ~1540 ms.

Обе формы отдают **один и тот же распакованный** поток: реализация порта снимает `Content-Encoding`
до возврата. `content()` и `content(block)` — два вида read одной и той же сущности, не «сжатый
против несжатого».

**Default request headers.** `HttpTool.open` выставляет по дефолту, если адаптер не передал свой
эквивалент (проверка — case-insensitive по имени):

- `User-Agent: Tubeloader/0` (заглушка до появления версионирования приложения).
- `Accept: */*`.
- `Accept-Encoding: identity` — гарантирует, что сервер не сожмёт body; адаптер может явно
  переопределить.

Спека не фиксирует формат версии `User-Agent` и `Accept-Language`: первое — отдельный тикет
(версионирование), второе — забота конкретного адаптера (зависит от источника).

**Cancellation.** Отмена корутины, в которой вызван `open`, прерывает сетевую операцию. Это
**контракт**: реализация, которая игнорирует cancellation, нарушает спеку. Конкретный таймаут
(`perAttemptTimeout` через `withTimeout`) — **вне контракта порта**: его ставит адаптер в своей
retry-обёртке; порт ограничивать попытку не обязан и retry-движок тоже. Тикет #68 в `ADR-0004`.

**`HttpToolConfig`.** Конфиг остаётся как в `core/.../config/HttpToolConfig.kt`:
`connectTimeout`, **`requestTimeout`** (вместо бывшего `readTimeout` — см. ADR-0006 про
отсутствие split readTimeout в Ktor), `perAttemptTimeout`, `retryMaxAttempts`, `retryBaseDelay`,
`retryRandomizationFactor`, `retryRetriableStatuses`. Дефолты живут в `HttpToolConfig` и
нигде больше — кроме `perAttemptTimeout`, единственного поля без дефолта: его обязан задать
конфиг (ключ `per-attempt-timeout-ms`), читает его адаптер. `connectTimeout`/`requestTimeout`
применяются вызывающим, который собирает `HttpClient`
(`HttpClient { install(HttpTimeout) { ... } }`), а не кодом ядра. Тесты обвязки порта — в
`core/src/test/.../http/KtorHttpToolTest.kt` (7 кейсов, см. ADR-0006).

**`FakeHttpTool`.** Живёт в `core/src/testFixtures/.../port/FakeHttpTool.kt` — это контракт для
тестов адаптеров. Переписан под `interface HttpResponse` и две формы `content()` вместе с #73:
стабы отдают готовый `HttpResponse`, заголовки ответа задаются через `Headers`.

## Out of contract by design

Эти вещи **сознательно не входят** в текущий контракт `HttpTool` и `HttpResponse`. Если они
понадобятся адаптеру — это новый тикет и отдельное расширение порта, не правка этой спеки.

| Что | Почему | Где зафиксировано |
|---|---|---|
| `Content-Type` / `charset` | Не используется в текущем парсинге; XML/JSON определяются адаптером по URL и телу. | здесь |
| Автоматическое следование редиректам | Реализация порта может следовать 3xx прозрачно (текущий дефолт `HttpClient.followRedirects`), но финальный URL наружу не отдаётся. Адаптер шлёт уже резолв-нный URL. | здесь (Q9 грила) |
| Подтипы `HttpResponse` (range, streaming, gzip-aware) | Текущий сценарий один: прочитать всё тело и закрыть. Range/streaming — отдельный тикет вместе с первым адаптером, которому это нужно. | здесь |
| Проксирование, базовая аутентификация, cookies | Не используются текущим Rutube-адаптером; добавление — отдельный тикет с решением о форме (headers в `open()` или новый параметр). | здесь |

`Retry-After` из этой таблицы ушёл вместе с #75: заголовки ответа теперь в контракте (см.
`HttpResponse.headers` выше), и заголовок сервера достижим как обычный заголовок ответа. Потребителя
у него нет: retry-движок по-прежнему судит по `status`, а не по header (`ADR-0004`).

## Тестовая стратегия

**Шов ядра (порты).** Контрактные тесты `HttpTool` живут в `core/src/testFixtures` как
`FakeHttpTool`. Тесты ядра, которые про сеть, идут через `FakeHttpTool` — настоящая сеть в
unit-тестах ядра запрещена (`docs/features/core/spec.md`).

**Тесты адаптеров.** Адаптеры гоняют `FakeHttpTool` с golden-фикстурами
(`docs/features/rutube-adapter/spec.md` — 264-я строка и далее); минимально один кейс с эталонным
ответом `FakeHttpTool` per test class.

**Переходный кейс.** `FakeHttpTool` переписан под `interface HttpResponse` и две формы
`content()` вместе с #73; extension `bytes()` убран, тесты адаптеров работают через форму с блоком.

## Tickets

Раскладываются в трекере `averkhogliad/tube-loader` через
`scripts/tracker/tracker.mjs` (см. `docs/agents/issue-tracker.md`).

- **#73 — контракт порта по ADR-0005: `open` возвращает `Result<HttpResponse>`, `HttpResponse` —
  `interface` с двумя формами `content()`, `FakeHttpTool` переписан под неё.** Шов для тестов. Без
  него контракт нельзя применить к существующему `FakeHttpTool`, а production-реализация писалась
  бы не под зафиксированную форму. Вместе с ним: адаптер переходит на `Result` и форму с блоком,
  extension `bytes()` уходит.
- **#75 — заголовок ответа `Retry-After` через расширение `HttpResponse.headers`.** Расширение
  порта: `HttpResponse.headers: Headers` с поиском по имени без учёта регистра,
  `KtorHttpResponse` пробрасывает заголовки ответа, `FakeHttpTool` умеет задать их у стаба.
  Потребителя в тикете нет: движок повторов судит по `status`, а не по header. Заблокирован по
  #73 (форма `HttpResponse`) и по #74 (headers пробрасывает реализация порта).
- **#74 — production-реализация `HttpTool` поверх Ktor Client 3.x + CIO** (ADR-0006). Зависимости

  `ktor-client-core`, `ktor-client-cio` в `:core/main`; `ktor-client-mock` в `:core/test`.
  `KtorHttpTool(client)` + `HttpTools.create(client)`; `HttpResponse` = `interface` с двумя
  `content()`; default headers и `expectSuccess = false` / `followRedirects = false`.
  Переименование `HttpToolConfig.readTimeout` → `HttpToolConfig.requestTimeout: Duration`
  (правка `HttpToolConfig.kt`, `HttpToolConfigTest.kt` и `AppConfig.kt` — плоский TOML-блок
  `[download.http-tool]` уже существует после #67). Тест: `KtorHttpToolTest`, 7 кейсов на
  `MockEngine` (happy / non-2xx / transport fail / content closes stream / оба gzip-сценария /
  `Retry-After` из headers).
  ~200 LoC + ~80 LoC тестов. Заблокирован по #73 (форма `HttpResponse`).
- **#68 — `perAttemptTimeout` одной попытки** (долг из ADR-0004). Решён 07.10.2026: таймаут попытки —
  ответственность **адаптера**, который оборачивает `HttpTool.open` в `withTimeout` внутри своей
  retry-обёртки; порт таймаут попытки не ставит. `connectTimeout` и `requestTimeout` —
  ответственность HTTP-клиента (`HttpTimeout` в `HttpClient`, который собирает вызывающий).
  Значение — из обязательного ключа `per-attempt-timeout-ms` (`HttpToolConfig.perAttemptTimeout`).
- **#77 — production-сборка `HttpClient`** — **закрыт 07.10.2026 как `wontfix`**: сборка клиента
  (timeouts из `HttpToolConfig` + `install(ContentEncoding) { gzip() }`) делается после выбора DI
  и реализации самого приложения. Реализация порта клиент не строит: `KtorHttpTool` берёт его
  параметром и lifecycle не владеет.
  Сейчас `HttpClient` не создаёт никто, кроме тестового хелпера `KtorHttpToolTest`, поэтому
  `Content-Encoding` в проде не снимается.
