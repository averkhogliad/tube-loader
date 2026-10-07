# Технический стек

Карта стека по ролям: что в проекте и зачем. Версии — только `gradle/libs.versions.toml`;
этот файл версий не держит и не дублирует. Решение о стеке — `docs/adr/0001-kotlin-jvm-compose-desktop.md`.

## Модули

| Модуль | Роль |
| --- | --- |
| `common` | Java platform (`java-platform`): ограничений и потребителей пока нет |
| `common:config` | Интерфейсы конфигурации, разбор TOML, merge и каскад источников; переиспользуется ядром |
| `common:retry` | Движок повторов: политика решает, повторять ли упавшую попытку и сколько ждать. Источник-нейтрален, ядро и адаптеры переиспользуют |
| `core` | Headless-ядро: роутинг URL, оркестрация загрузок, нормализация прогресса и ошибок, staging |
| Фронтенды (план) | Compose Desktop GUI (M1), mosaic TUI (M2) — тонкие клиенты: команды вниз, события вверх |

## Подпакеты `core`

Слоистая граница — инвариант `docs/standards/architecture.md`; здесь — только раскладка пакетов
внутри ядра. Тестовые сорсеты (`testFixtures`, `test`) зеркалят main.

| Пакет | Содержимое |
| --- | --- |
| `core.adapter` | Контракт расширения: `SourceAdapter`, `FindResult`, `LoadMetaResult`, `DownloadCapability`, `DownloadResult` |
| `core.config` | Типизированные настройки ядра: `AppConfig`, `HttpToolConfig` |
| `core.domain` | Доменные типы: `MediaMeta`, `MediaRef`, `Progress`, `Quality`, `TrackKind`, `Source`, `SourceId`, `SourceProgress`, `TaskId`, `DownloadError` |
| `core.download` | Жизненный цикл и исполнение: `DownloadHandle`, `DownloadQueue`, `DownloadState`, `DownloadStatus`, `TaskIdGenerator`, `TaskRegistry` |
| `core.facade` | Единственная точка входа команд: `CoreFacade` |
| `core.port` | Порты ядра: `MediaTool`, `HttpTool`, `HttpResponse` (закрываемый ответ, несёт `status`) |
| `adapters.rutube` | Первый `SourceAdapter`: разбор URL, `playOptions`, HLS-скачивание, финализация через `MediaTool` |

## Рантайм

| Технология | Роль |
| --- | --- |
| Kotlin/JVM | Единый язык ядра и фронтендов |
| kotlinx.coroutines | Конкурентность ядра; модель конкурентности — конфайнмент на одном воркере (ADR-0003) |
| kotlinx.serialization | Разбор JSON-ответов источника в адаптере (`RutubeSourceAdapter`) |
| Ktor Client (engine CIO) | Транспорт порта `HttpTool`: реализация `core.http` (ADR-0006) |

## Конфигурация

| Технология | Роль |
| --- | --- |
| ktoml | Чтение TOML-конфигурации приложения: `ktoml-core` и `ktoml-file` |

## GUI

| Технология | Роль |
| --- | --- |
| Compose Desktop (план) | Рендеринг M1; слой презентации отделён от рендеринга (ADR-0002), ручной флоу состояний. В сборке зависимостей нет: фронтенда пока нет |

## Тесты

| Технология | Роль | Стандарт |
| --- | --- | --- |
| kotest | Runner (JUnit6), property-тесты: `Arb`/`Exhaustive`, сэмпл — `gen.next()` | `docs/standards/testing.md` |
| mockk | Моки: подключён к `:core`, в тестах пока не применён; query-методы заглушаются, command-методы верифицируются с `capture` | `docs/standards/testing.md` |
| JUnit Platform launcher | Тестовая платформа Gradle | — |
| Kover | Покрытие в `:core`, `:common:config` и `:common:retry`; гейт 80% строк / 75% ветвей, `testFixtures` исключены | — |

## Линт

| Технология | Роль |
| --- | --- |
| ktlint | Стиль кода; настройки в `.editorconfig`, `max_line_length = 120` |
| detekt | Статический анализ; per-module baseline `detekt-baseline.xml` рядом с модулем |

## Внешние инструменты

| Инструмент | Роль |
| --- | --- |
| FFmpeg | Муксинг/remux — за портом `MediaTool`, в ядре не упоминается |
| yt-dlp | Адаптер `Delegate` (план): внешний процесс, дерево процессов гасится целиком при отмене |
