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
`HttpResponse` несёт `status` + `contentLength` и ровно две формы чтения body — `content()` как
стрим и `content(block)` как блок. Декодирование transport encoding — ответственность реализации
порта, адаптер получает уже распакованный поток. Дефолтные request headers (`User-Agent`,
`Accept`, `Accept-Encoding: identity`) выставляет порт; адаптер может переопределить. Контракт не
расширяется в этом релизе: headers ответа, `contentType`, редиректы и `Retry-After` сознательно
вне контракта и попадут туда отдельными тикетами.

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
Result<HttpResponse>`. `Result.failure` — для транспортных сбоев: DNS, TCP, TLS, таймаут (когда
реализация появится), обрыв тела, `EOFException`. `Result.success` — для всего, что сервер
ответил, включая 4xx и 5xx; адаптер смотрит в `status` и решает сам, что значит «успех для
адаптера». Эта граница — единственный шов между «порт не справился» и «порт справился, сервер
сказал своё слово», и retry-движок опирается на неё в `judging`.

**`HttpResponse`.** Интерфейс с тремя членами:

- `val status: Int` — HTTP-статус ответа; для перенаправлений (3xx) — статус финального ответа,
  на который реализация порта уже перешла (см. «Out of contract by design»).
- `val contentLength: Long?` — длина тела, если сервер её передал; `null` для chunked-ответа
  или `Transfer-Encoding` без `Content-Length`.
- `fun content(): InputStream` — отдаёт распакованный поток. Вызывающий закрывает его; для
  большинства сценариев рекомендуется форма с блоком.
- `suspend fun <R> content(block: (InputStream) -> R): R` — читает тело в блоке; порт закрывает
  поток в `finally` независимо от исхода блока. Это основной путь для адаптеров: парсеров JSON,
  скачивания в файл через `transferTo`, подсчёта байт.

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
(`perAttemptTimeout` через `withTimeout`) — **вне контракта** и остаётся на реализации; тикет #68 в
`ADR-0004` откладывает его до первой production-реализации порта.

**`HttpToolConfig`.** Конфиг остаётся как в `core/.../config/HttpToolConfig.kt`:
`connectTimeout`, **`requestTimeout`** (вместо бывшего `readTimeout` — см. ADR-0006 про
отсутствие split readTimeout в Ktor), `retryMaxAttempts`, `retryBaseDelay`,
`retryRandomizationFactor`, `retryRetriableStatuses`. Дефолты живут в `HttpToolConfig` и
нигде больше. Применяются реализацией порта (`HttpClient { install(HttpTimeout) { ... } }`),
а не кодом ядра. Тесты обвязки порта — в `core/src/test/.../http/KtorHttpToolTest.kt`
(5 кейсов, см. ADR-0006).

**`FakeHttpTool`.** Остаётся в `core/src/testFixtures/.../port/FakeHttpTool.kt` — это контракт для
тестов адаптеров. Переписывается под `interface HttpResponse` и две формы `content()` отдельным
тикетом (см. «Tickets»); до переписки текущая форма `data class HttpResponse(status, body:
InputStream, contentLength)` сохраняется, и спекой это фиксируется как переходное состояние.

## Out of contract by design

Эти вещи **сознательно не входят** в текущий контракт `HttpTool` и `HttpResponse`. Если они
понадобятся адаптеру — это новый тикет и отдельное расширение порта, не правка этой спеки.

| Что | Почему | Где зафиксировано |
|---|---|---|
| Headers ответа (`Content-Type`, `Set-Cookie`, `Retry-After` и т.п.) | Изначально не отдавались, чтобы порт оставался минимальным. `Retry-After` — потому что retry-движок работает по `judging(status)`, а не по header. | `ADR-0004` (Q2 грила, 2026-10-06) |
| `Content-Type` / `charset` | Не используется в текущем парсинге; XML/JSON определяются адаптером по URL и телу. | здесь |
| Автоматическое следование редиректам | Реализация порта может следовать 3xx прозрачно (текущий дефолт `HttpClient.followRedirects`), но финальный URL наружу не отдаётся. Адаптер шлёт уже резолв-нный URL. | здесь (Q9 грила) |
| Подтипы `HttpResponse` (range, streaming, gzip-aware) | Текущий сценарий один: прочитать всё тело и закрыть. Range/streaming — отдельный тикет вместе с первым адаптером, которому это нужно. | здесь |
| Проксирование, базовая аутентификация, cookies | Не используются текущим Rutube-адаптером; добавление — отдельный тикет с решением о форме (headers в `open()` или новый параметр). | здесь |

## Тестовая стратегия

**Шов ядра (порты).** Контрактные тесты `HttpTool` живут в `core/src/testFixtures` как
`FakeHttpTool`. Тесты ядра, которые про сеть, идут через `FakeHttpTool` — настоящая сеть в
unit-тестах ядра запрещена (`docs/features/core/spec.md`).

**Тесты адаптеров.** Адаптеры гоняют `FakeHttpTool` с golden-фикстурами
(`docs/features/rutube-adapter/spec.md` — 264-я строка и далее); минимально один кейс с эталонным
ответом `FakeHttpTool` per test class.

**Переходный кейс (новый тикет).** Переписать `FakeHttpTool` под `interface HttpResponse` и две
формы `content()`. До переписки data class-форма остаётся, и адаптерные тесты компилируются
через extension `bytes()` поверх `body`. После переписки расширение уходит.

## Tickets

Раскладываются в трекере `averkhogliad/tube-loader` через
`scripts/tracker/tracker.mjs` (см. `docs/agents/issue-tracker.md`).

- **#73 — контракт порта по ADR-0005: `open` возвращает `Result<HttpResponse>`, `HttpResponse` —
  `interface` с двумя формами `content()`, `FakeHttpTool` переписан под неё.** Шов для тестов. Без
  него контракт нельзя применить к существующему `FakeHttpTool`, а production-реализация писалась
  бы не под зафиксированную форму. Вместе с ним: адаптер переходит на `Result` и форму с блоком,
  extension `bytes()` уходит.
- **#75 — заголовок ответа `Retry-After` через расширение `HttpResponse.headers`.** Расширение
  порта; до этого — заглушка (см. Раздел «Out of contract»). Потребителя в тикете нет: движок
  повторов судит по `status`, а не по header. Заблокирован по #73 (форма `HttpResponse`) и по #74
  (headers пробрасывает реализация порта).
- **#74 — production-реализация `HttpTool` поверх Ktor Client 3.x + CIO** (ADR-0006). Зависимости

  `ktor-client-core`, `ktor-client-cio` в `:core/main`; `ktor-client-mock` в `:core/test`.
  `KtorHttpTool(client, config)` + `HttpTools.create(...)`; `HttpResponse` = `interface` с двумя
  `content()`; default headers и `expectSuccess = false` / `followRedirects = false`.
  Переименование `HttpToolConfig.readTimeout` → `HttpToolConfig.requestTimeout: Duration`
  (правка `HttpToolConfig.kt`, `HttpToolConfigTest.kt` и `AppConfig.kt` — плоский TOML-блок
  `[download.http-tool]` уже существует после #67). Тест: `KtorHttpToolTest`, 5 кейсов на
  `MockEngine` (happy / non-2xx / transport fail / consume closes stream / оба gzip-сценария).
  ~200 LoC + ~80 LoC тестов. Заблокирован по #73 (форма `HttpResponse`).
- **#68 — `perAttemptTimeout` одной попытки** (долг из ADR-0004). Отложен: тикет остаётся
  открытым до первой production-реализации (#74). За ним — развилка, не закрытая ни спекой, ни
  ADR-0005/0006: спека http-tool говорит «`withTimeout` живёт снаружи порта, в retry-обёртке
  адаптера», ADR-0005 — «`perAttemptTimeout` остаётся ответственностью реализации порта», а
  ADR-0006 уже даёт Ktor `requestTimeoutMillis`, ограничивающий попытку внутри клиента. Форма
  решается отдельным шагом перед реализацией.
