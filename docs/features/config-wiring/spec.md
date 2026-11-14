# Конфиг: источник настроек изолирован от ядра

## Problem Statement

Типизированные настройки ядра читают конфиг сами. `HttpToolConfig` и `YtDlpConfig` несут почти
одинаковые приватные парсеры — «число по ключу», «длительность по ключу», «фактор», «список»,
«путь» — и читают TOML-подобный AST (`Map<String, Any>` из табличного lookup). Формат источника
протёк в ядро: сменить источник (файл, код, in-memory) без правки ядра нельзя. Копии парсеров
разошлись: тексты ошибок разные, обе молча усекают `Long` в `Int`.

Половина проводки мертва: `HttpToolConfig.fromConfig` и `AppConfig.fromConfig` зовутся только
тестами; smoke собирает `AppConfig` и `HttpToolConfig` руками, а его `config.toml` содержит те же
значения — две истины, TOML декоративный; `default-target-dir` и `max-parallel-downloads` из файла
перекрыты CLI-дефолтами. Единственный живой читатель — `YtDlpConfig.fromConfig`.

## Solution

Шов чтения типизированных значений — `ConfigReader` в `common:config`: `wholeNumber`,
`durationMillis`, `factor`, `path`, `numberList`, `text` по ключу. Отсутствующий ключ — `null`;
нечитаемое значение — громкий `IllegalArgumentException` с именем ключа и значением. Интерфейс
source-agnostic: ядро не знает ни формата, ни файла.

Источники — адаптеры в `common:config`: `TomlConfigReader` поверх существующего `Config` и TOML,
`MemoryConfigReader` — источник «код» (значения в памяти) для тестов и композиции. Парсинг-хелперы
существуют один раз, в TOML-адаптере; словарь сообщений единый; `Long` вне `Int`-диапазона —
отказ, не усечение.

Типизированные конфиги ядра (`HttpToolConfig`, `YtDlpConfig`, `AppConfig`) получают
`fromReader(reader)`: объявляют ключи и семантические проверки, AST и формат не видят. Ядро
работает с финальными типизированными значениями; загрузка из файла — отдельно, в композиции.

Smoke грузит файл → reader → конфиги: `[download.http-tool]` заменяет hardcoded-значения,
`AppConfig.fromReader` оживает, `defaultTargetDir` наконец потребляется. Приоритет источников —
CLI > TOML > default: флаги перекрывают файл, константы — последний backstop. Сборка retry-политики
(`policyOf` ×2) не трогается.

## User Stories

1. Как разработчик ядра, я хочу читать настройки через source-agnostic reader, чтобы формат файла
   не протекал в ядро.
2. Как разработчик, я хочу один парсер с одним словарём ошибок, чтобы баг парсинга чинился в
   одном месте, а не в копиях.
3. Как оператор smoke, я хочу переопределить любую настройку из CLI поверх TOML-файла, чтобы
   прогон не требовал правки файла.
4. Как разработчик GUI (M1), я хочу подставить любой источник — файл или код — не правя ядро.
5. Как ревьюер, я хочу отсутствия молчаливого усечения чисел: значение вне диапазона — отказ,
   не тихая подмена.

## Implementation Decisions

- **`ConfigReader`** — интерфейс в `common:config`; доступ по dotted-ключу; missing → `null`;
  синтаксическая ошибка — `IllegalArgumentException` с ключом и значением; целые — строгий
  `Int`-диапазон (`Long` вне диапазона — отказ).
- **Источники — адаптеры там же:** `TomlConfigReader` поверх `Config`/TOML; `MemoryConfigReader`
  для кода и тестов. Ядро видит только интерфейс; TOML, файлы и ktoml остаются в `common:config`.
- **Типизированные конфиги остаются в `core.config`** (в т.ч. `AppConfig` — доменный,
  package-structure spec): `fromConfig(config)` заменяется на `fromReader(reader)`; синтаксис —
  в reader'е, семантика (позитивность, границы, обязательность per-attempt) — в `init` конфигов.
- **Smoke:** `loadConfig` → `TomlConfigReader`; `HttpToolConfig.fromReader` вместо hardcoded
  `perAttemptTimeout` 60 s; `AppConfig.fromReader` вместо ручной сборки; приоритет CLI > TOML >
  default для `-out`/`-parallel` и остальных; `defaultTargetDir` потребляется.
- **`policyOf` ×2 не трогается:** knob-сборка в 4 строки на адаптер, классификация — у адаптера
  (ADR-0004); общий хелпер не окупает API.
- **Доки — по факту кода:** core spec (парсинг и контракт конфига), smoke spec (приоритет
  источников), `tech-stack` (роль `common:config`: `ConfigReader`, формат изолирован от ядра).

## Testing Decisions

- **Контракт reader'а — один набор на два адаптера:** `TomlConfigReader` и `MemoryConfigReader`
  прогоняются одинаковыми кейсами — числа, длительности, факторы, пути, списки; missing → `null`;
  синтаксические ошибки с ключом; отказ на `Long` вне `Int`-диапазона.
- **Конфиги ядра:** прежние тесты переписываются на `fromReader` + `MemoryConfigReader`;
  поведение сохраняется; усечение — новый кейс на отказ.
- **Smoke:** тест приоритета CLI > TOML > default; тест чтения `[download.http-tool]` из
  ресурсного `config.toml`; `-out`/`-parallel` перекрывают файл.
- **Критерий:** `./gradlew check --rerun-tasks` зелёный; счётчик тестов из `TEST-*.xml` не
  уменьшается.

## Out of Scope

- `policyOf` ×2 (knob-сборка retry) — осознанно не трогается: ADR-0004 оставляет классификацию
  адаптеру, экономия строк не окупает новый API общего модуля.
- Схема пользовательского конфига GUI, выбор его формата и расположения — фича GUI (M1).
- Горячая перезагрузка конфига — не требуется.
- Пересмотр ключей и их имён — формы `[download.http-tool]`/`[download.youtube]` (ADR-0004,
  youtube spec) сохраняются.

## Further Notes

- `common:config` остаётся микрофреймворком: `ConfigReader` — generic typed lookup, не доменные
  настройки; доменные остаются в `core.config` (package-structure spec).
- Критерий дизайна: «источник настроек — файл и его формат, код или др — изолирован от ядра»:
  ядро видит только `ConfigReader` и финальные типизированные значения.

## Tickets

- #139 — refactor(config): ConfigReader вместо TOML AST в ядре
- #140 — refactor(smoke): конфиг из TOML с CLI-переопределением (заблокирован #139)
