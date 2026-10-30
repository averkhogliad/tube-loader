# Youtube Source Adapter

Спека второго `SourceAdapter` — YouTube через yt-dlp в режиме `Delegate`. Первый адаптер
(Rutube, `Native`) — `docs/features/rutube-adapter/spec.md`; контракт `SourceAdapter` и
capability-модель — `docs/features/core/spec.md`. Детали интеграции с yt-dlp (CLI, JSON-протокол,
прогресс, маппинг ошибок, гигиена процессов) — исследование `docs/research/report-1.md` §5;
архитектура следует коду, не репорту.

## Problem Statement

Пользователь хочет скачивать видео с YouTube. Ядро роутит URL по адаптерам; адаптер должен
разобрать URL в `mediaId`, отдать метаданные и скачать файл. У YouTube нет стабильного
публичного API; единственный устойчивый к антиботу экстрактор — yt-dlp. M1 требует YouTube
end-to-end headless, рядом с Rutube, без установки Python/Node пользователем.

## Solution

`SourceAdapter`-реализация `YoutubeSourceAdapter` в пакете `adapters.youtube` (внутри `:core`),
`capability = Delegate`. Разбор URL — нативный, offline, без yt-dlp. Метаданные и скачивание
делегируются CLI-бинарнику yt-dlp через процесс-раннер. yt-dlp сам скачивает и мёрджит
video+audio через bundled ffmpeg (`--ffmpeg-location`); адаптер **не зовёт** `MediaTool` — в этом
суть `Delegate`. Финальный файл пишется в `targetPath` (part-путь ядра), инвариант
`CoreFacade` (staging + `ATOMIC_MOVE`) сохранён, как у Rutube.

## User Stories

1. Как пользователь, я хочу вставить YouTube-URL и получить видео в целевом каталоге в
   финальном контейнере mp4, чтобы не собирать потоки руками.
2. Как пользователь, я хочу, чтобы YouTube-URL распознавался без обращения к сети, чтобы
   роутинг был мгновенным и offline.
3. Как пользователь, я хочу увидеть метаданные (заголовок, автор, длительность, обложка,
   доступные качества), чтобы выбрать качество перед скачиванием.
4. Как пользователь, я хочу, чтобы первая загрузка с YouTube отработала без ручной установки
   yt-dlp или Python, чтобы не настраивать окружение.
5. Как пользователь, я хочу, чтобы вторая и последующие загрузки не скачивали yt-dlp заново,
   чтобы первая не была медленнее остальных.
6. Как пользователь, я хочу видеть прогресс скачивания (байты/байты), чтобы понимать, что
   процесс идёт, а не завис.
7. Как пользователь, я хочу, чтобы отмена задачи прерывала идущий yt-dlp и его ffmpeg-детей,
   чтобы не получить «отменённый, но пишущий файл» артефакт.
8. Как пользователь, я хочу, чтобы yt-dlp не оставлял осиротевших дочерних процессов после
   отмены или падения, чтобы система оставалась чистой.
9. Как пользователь, я хочу осмысленную ошибку, когда видео недоступно (приватное, удалено),
   чтобы понять причину, а не видеть «что-то сломалось».
10. Как пользователь, я хочу осмысленную ошибку, когда YouTube требует подтверждение «не бот»,
    чтобы понять, что нужен сценарий с cookies/прокси (за пределами M1), а не пытаться снова.
11. Как пользователь, я хочу, чтобы временный сетевой сбой повторялся, а не падал сразу.
12. Как пользователь, я хочу, чтобы качество выбиралось в тех же терминах, что и для Rutube,
    чтобы UX качества был единый для всех источников.
13. Как разработчик, я хочу подменять yt-dlp-раннер фейком в тестах ядра и адаптера, чтобы
    тесты оставались детерминированными и офлайн.
14. Как разработчик, я хочу, чтобы contract-тест на golden-фикстурах проверял адаптер через
    тот же `SourceAdapterContract`, что и Rutube, чтобы контракт был единым для всех адаптеров.
15. Как разработчик, я хочу, чтобы маппинг ошибок yt-dlp шёл по stderr/JSON, а не по
    exit-кодам, чтобы классификация была устойчивой (exit-кодам yt-dlp не доверяем, §5.6).
16. Как разработчик, я хочу, чтобы `--no-config` был обязателен в каждом вызове yt-dlp, чтобы
    лежащий в CWD `yt-dlp.conf` не инжектил опции вплоть до RCE (§5.4).
17. Как разработчик, я хочу, чтобы адаптер жил в пакете `adapters.youtube` внутри `:core`, не
    плодя отдельный Gradle-модуль ради второго адаптера (та же конвенция, что для
    `adapters.rutube` и `core.media`).
18. Как разработчик, я хочу, чтобы реальная реализация `SourceAdapter` для YouTube
    соответствовала контракту `SourceAdapterContract`, чтобы адаптер можно было подменить без
    правок ядра.
19. Как разработчик, я хочу, чтобы интеграционный прогон с реальным yt-dlp был отдельным
    тестом вне `check`, чтобы `check` оставался быстрым и офлайн.
20. Как мейнтейнер, я хочу, чтобы `Delegate` оставался ярлыком реализации, по которому ядро не
    ветвится, чтобы не плодить пути в ядре под каждый бэкенд (core/spec.md, capability-модель).

## Implementation Decisions

**Место в репозитории.** Пакет `io.averkhogliad.tubeloader.adapters.youtube` внутри `:core`.
Отдельный Gradle-модуль `:adapters:youtube` не создаётся — та же конвенция, что для
`adapters.rutube` (спека ядра, «Раскладка репозитория») и `core.media` (ADR-0008): модули под
реализации не заводятся заранее, пакет может. Переезд в отдельный модуль — отдельный тикет на
будущее, когда появится третий источник или потребует сборка графа.

**Capability.** `DownloadCapability.Delegate`. Ядро по capability не ветвится
(`core/spec.md`: «`Native` и `Delegate` для ядра неразличимы, ядро в обоих случаях зовёт
`download`»); перечисление — ярлык реализации. `displayName = "YouTube"`.

**`find` — нативный разбор URL, offline.** Без обращения к yt-dlp или сети. Распознаёт
`youtube.com/watch?v=<id>`, `youtu.be/<id>`, `youtube.com/shorts/<id>`, `youtube.com/embed/<id>`.
Исход `FindResult`: `Found(mediaId)` / `Unsupported` (чужой хост) / `NotFound` (хост YouTube, но
id пуст/отсутствует). Контракт `SourceAdapterContract` требует, чтобы `find` решал offline, не
трогая запись источника.

**`loadMeta` — yt-dlp Resolve.** `yt-dlp --no-config -J --skip-download <URL>` отдаёт JSON с
метаданными и списком форматов. Адаптер разбирает JSON в `MediaMeta` и набор `Quality`.
Маппинг defensive: неизвестные поля игнорируются (схема `-J` не версионирована, §5.5). Спека
адаптера Rutube и `core/spec.md` фиксируют форму `MediaMeta` (`id`, `title`, `author`,
`duration`, `thumbnailUrl`, `qualities`). `Quality` — `id`, `kind` (`TrackKind.Video` в M1),
`label`; M1 использует только видео-качество, audio-only отложен на M2 (модель сохранена).

**`download` — yt-dlp Delegate (R-2).** yt-dlp скачивает и мёрджит video+audio сам, через
bundled ffmpeg (`--ffmpeg-location` указывает на бинарник из `FfmpegDistribution`,
`~/.tubeloader/ffmpeg/ffmpeg(.exe)`, ADR-0008). Адаптер **не зовёт** `MediaTool.mux`/`remux` —
`Delegate` означает, что инструмент делает всё. Эскиз команды (деталь реализации, не контракта):
`--no-config --newline --no-simulate --print after_move:filepath --progress-template
"dl:@P %(progress.downloaded_bytes)s %(progress.total_bytes)s …" -f <quality> -o
<internal-staging>/%(id)s.%(ext)s --ffmpeg-location <ffmpeg> <URL>`. Финальный путь файла
читается из `--print after_move:filepath`; адаптер переносит результат в `targetPath` (part-путь
ядра), чтобы `CoreFacade.runDownload` выполнил атомарный `ATOMIC_MOVE` в целевой каталог, как для
Rutube. Внутренний staging yt-dlp (его `.part`/temp) чистится адаптером по успеху/отмене/сбою.

**Прогресс.** `--progress-template` отдаёт `downloaded_bytes` и `total_bytes`. Адаптер эмиттит
`Progress.Absolute(downloaded, total)`. Если `total_bytes` неизвестен (yt-dlp не всегда знает) —
`Progress.Indeterminate` до появления total, затем `Absolute`. Контракт
`SourceAdapterContract` требует, что прогресс достигает завершения (`fraction() == 1.0`) и не
идёт назад.

**Перевод качества.** M1: инлайн-маппинг `Quality` → формат-селектор yt-dlp `-f` (лучшее
video+audio ≤ выбранного разрешения, напр. `bv*[height<=?1080]+ba/b[height<=?1080]`).
Отдельный компонент `FormatSelector` (rep ort §7) — вне scope; маппинг живёт в адаптере как
деталь реализации.

**Маппинг ошибок (§5.6, YouTube-специфика).** Exit-кодам yt-dlp не доверяем; классифицируем по
stderr/JSON:

| Признак | `DownloadError` | Поведение |
|---|---|---|
| `Video unavailable` / приватное / удалено | `NotFound` | стоп |
| HTTP 403/410, протухшие URL | `UrlExpired` | re-resolve через ретрай |
| сетевые таймауты/сбросы | `NetworkTransient` | retry c backoff |
| `Sign in to confirm you're not a bot` и аналоги | `AntiBotBlocked` | стоп; cookies/прокси — M3 |
| ошибки формата/опций | `ExtractorBroken` | баг перевода качества |
| крэш/мусорный вывод | `ExtractorBroken` | «обновите компонент» |

**Расширение `DownloadError`.** Добавляется `data object AntiBotBlocked : DownloadError`. Спека
ядра оставила расширение таксономии открытым («таксономия ошибок расширенно»); antibot —
реальный исход YouTube, не сводящийся к существующим классам. `Config`-ошибки перевода качества
пока маппятся в `ExtractorBroken`.

**`YtDlpProcessRunner` — внутренний шов.** Зеркало `FfmpegProcessRunner` (`core.media`):
инжектируемый `start`, tree-kill по `CancellationException` (механизм и остаточный
риск — ADR-0010). Отличие от `FfmpegProcessRunner`: стримит stdout построчно (progress-линии
+ финальный `filepath`) и классифицирует ошибки из stderr/JSON, а не только по exit-коду. Тесты
передают фейк-раннер. Консолидация с `FfmpegProcessRunner` в общий `ProcessRunner` (rep ort §9.1)
— вне scope.

**Гигиена процессов (§5.4, YouTube-специфика).** `--no-config` обязательно в каждом вызове
(security: CWD `yt-dlp.conf` → инъекция опций до RCE). CWD — контролируемый каталог приложения.
Окружение: whitelist переменных, не наследовать `PYTHONPATH`/`PYTHONHOME`, форсировать
`PYTHONUTF8=1`. Параллельный дрен stdout и stderr (анти-дедлок на заполнении буфера), лимит объёма
вывода. Таймаут на resolve-вызовы.

**`YtDlpConfig`.** Новый объект в `core.config` (рядом с `HttpToolConfig`, `MediaToolConfig`):
`binaryPath: Path?` (optional escape hatch для CI/тестов с уже установленным yt-dlp),
`paths: ToolPaths` (общий компонент путей инструментов — даёт путь к бинарнику ffmpeg и home-каталог),
`workDirectory: Path`, `resolveTimeout`, `concurrentFragments: Int`. Ключи под `[download.youtube]`.
Версия/URL/SHA-256 бинарника — `private const` реализации, не пользовательский конфиг (политика
дистрибутива — ADR по упаковке, M2). Никаких новых библиотечных зависимостей:
`java.lang.ProcessBuilder`, `ProcessHandle`, `kotlinx.coroutines`, `kotlinx.serialization`
(уже в `:core` для JSON).

**Пути инструментов.** Где лежит ffmpeg, адаптер не вычисляет и ничего о медиа-пакете не знает:
пути резолвит общий `ToolPaths`, преднастраиваемый при сборке графа объектов, и оба потребителя
(медиа-инструмент и YouTube-адаптер) получают его параметром конструктора.

**Загрузка бинарника ffmpeg.** Владельцев загрузки два: `MediaTool` запрашивает бинарник сам, а
делегат передаёт путь к нему инструменту, который стартует раньше, чем до `MediaTool` дойдёт первый
вызов. Поэтому корень сборки зовёт `ensureManagedFfmpeg` один раз за прогон, до первого
скачивания: бинарник на диске есть всегда, когда адаптер отдаёт путь. Политика ADR-0008 не
меняется: то же место, тот же источник, те же правила контрольной суммы. Шов остаётся узким и
общим — зеркалить дистрибуцию в `tools/smoke` было бы дублированием.

**Зависимости адаптера.** Конструктор принимает `YtDlpProcessRunner` (или фабрику) и
`() -> YtDlpConfig`. `HttpTool` и `MediaTool` адаптеру не нужны — yt-dlp сам делает сеть и
муксинг. Шов DI узкий, как у `FfmpegMediaTool` (ADR-0008: «конструктор принимает конфиг, и всё»).

## Testing Decisions

**Хороший тест** проверяет внешнее поведение через интерфейс `SourceAdapter` на golden-фикстурах,
offline. Детали реализации (команда yt-dlp, парсинг JSON) не тестируются напрямую — только через
исходы `find`/`loadMeta`/`download`, прогресс и байты файла. Реальный yt-dlp — только в IT вне
`check`.

**Contract--suite.** Переиспользуется `SourceAdapterContract` из `core/testFixtures/.../contract`.
`YoutubeAdapterTest` подключает адаптер фабрикой, как `RutubeAdapterTest`:

```kotlin
class YoutubeAdapterTest : FreeSpec({
    include(sourceAdapterContract("youtube", youtubeFixtures()) { YoutubeAdapter(fakeRunner) })
})
```

Фейк `YtDlpProcessRunner` отвечает из записи: mediaId → JSON (meta), mediaId+quality →
progress-линии + байты выходного файла (download). `SourceAdapterFixtures`
(`FindCase`/`MetaCase`/`DownloadCase`) переиспользуются; `RecordedResponses` (URL→HTTP-body) для
YouTube не используется — запись живёт в YouTube-fixture-хелпере. Харнес `SourceAdapterContract`
не меняется.

**Golden-фикстуры** лежат на classpath под `golden/youtube/` (образец — `golden/rutube/`):
записанные JSON-ответы yt-dlp и эталонные `MediaMeta`/`Quality`/исходы. Обновление фикстуры — правка
файла, не тестового кода.

**Таксономия ошибок.** На каждый класс `DownloadError` (включая новый `AntiBotBlocked`) —
минимум один кейс с эталонным stderr/JSON yt-dlp и ожидаемым `DownloadResult.Failed`.

**Прогресс.** Golden progress-линии → ожидаемая последовательность `Progress`, достигающая
завершения (`fraction() == 1.0`), монотонная, последний `Progress.Absolute(fileSize, fileSize)` —
по утверждению contract-	suite.

**Шов раннера.** Фейк `YtDlpProcessRunner` — injectable `start`, как у `FfmpegProcessRunner`
(`core.media`): контрольный поток раннера наблюдаем без запуска процесса. Cancel-кейс: фейк
имитирует живого ребёнка, проверяется tree-kill.

**IT.** `YoutubeSourceAdapterIT`, `@Tag("integration")`, исключён из `check` через
`excludeTags("integration")` + `excludeTestsMatching("*SourceAdapterIT")` в
`core/build.gradle.kts` (образец — `RutubeSourceAdapterIT`). Реальный yt-dlp; бинарные фикстуры в
репозитории не появляются — используется публичное видео или skip при отсутствии бинарника.

**Prior art.** `RutubeSourceAdapterTest` (contract на golden), `FfmpegMediaTool` (CLI-процесс,
tagged IT вне `check`), `FfmpegProcessRunner` (injectable start, tree-kill). Стандарт тестов —
`docs/standards/testing.md`.

## Out of Scope

- Дистрибуция и обновление yt-dlp-бинарника (`ComponentUpdater`, manifest, mirror, SHA-256,
  атомарный swap) — ADR по упаковке, M2. Спека адаптера предполагает, что бинарник доступен
  (через `YtDlpConfig.binaryPath` или политику дистрибутива); готовность в контракте не описывается.
- `MetadataCache`, батчинг URL, префетч при старте.
- `FormatSelector` как отдельный компонент (§7) — M1 использует инлайн-маппинг.
- Консолидация `ProcessRunner` (общий для ffmpeg и yt-dlp, §9.1).
- PO Token sidecar (bgutil/Node/Docker) — отложено до фактической необходимости, не закладывать
  в M1.
- Плейлисты/каналы, субтитры/главы, приватные видео, cookies/прокси, аккаунты и синк.
- Generic-source (yt-dlp generic-экстрактор для «прочих сайтов»).
- Rutube-via-yt-dlp fallback (Rutube остаётся Native).
- Скачивание только аудио (второй маршрут по `TrackKind.Audio`) — M2; модель сохранена.
- Resolve-only capability (L2, `--print` без скачивания).
- Tuning `--concurrent-fragments` и прочих yt-dlp-опций сверх дефолта конфига.

## Further Notes

- Исследование: `docs/research/report-1.md` §5 (формат интеграции, режимы вызова, эскиз команды,
  гигиена, маппинг ошибок, перевод качества). Репорт — источник YouTube-специфики; архитектура
  следует коду, не репорту.
- Связанные ADR: ADR-0008 (ffmpeg CLI-процесс + гибридная дистрибуция; `--ffmpeg-location`
  переиспользует bundled ffmpeg), ADR-0003 (конфайнмент очереди), ADR-0009 (use-case слой над
  `CoreFacade` — адаптеру ортогонален).
- Контракт и capability-модель: `docs/features/core/spec.md`. Native-коллега:
  `docs/features/rutube-adapter/spec.md`.
- Открыто без тикета: судьба capability-модели (`core/spec.md` — «свести ли перечисление к
  „скачивает сам / только метаданные“»); `Delegate` остаётся ярлыком, не блокирует адаптер.
- Security: `--no-config` обязательно (RCE через CWD `yt-dlp.conf`, §5.4/§13 репорта).

## Tickets

Тикеты живут в GitHub Issues: <https://github.com/averkhogliad/tube-loader/issues>. Числа ниже —
номера issue (проставляются после создания).

- 00 — Youtube adapter: URL parsing and empty package (`adapters.youtube`, `find`, `Delegate`) — #89
- 01 — Youtube adapter: loadMeta via yt-dlp `-J --skip-download` (blocked by #89) — #90
- 02 — Youtube adapter: `YtDlpProcessRunner` seam (blocked by #89) — #91
- 03 — Youtube adapter: `YtDlpConfig` (blocked by #89) — #92
- 04 — Youtube adapter: delegate download via yt-dlp R-2 (blocked by #90, #91, #92) — #93
- 05 — Youtube adapter: error taxonomy mapping + `AntiBotBlocked` (blocked by #90, #93) — #94
- 06 — Youtube adapter: contract tests on golden fixtures (blocked by #90, #93, #94) — #95
- 07 — Youtube adapter: IT with real yt-dlp (blocked by #95) — #96
- 08 — Youtube adapter: smoke end-to-end wiring (blocked by #92, #93, #94, #95, #96) — #103
