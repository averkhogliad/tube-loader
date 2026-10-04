# Воркфлоу `gradle check` для PR

Статус: **реализовано** — `.github/workflows/check.yml`, коммит `875d36c` (ticket #29). Ниже сохранён
исходный план от 29.09.2026. Отличия факта от плана: провайдер GitHub, а не GitVerse; в `on`
добавлен `push: branches: [main]` (пункт 4 исходного разбора снят пользователем); `.editorconfig`
в репозитории есть; Kover-гейт 80/75 и линтеры подключены в корневом `build.gradle.kts`.

## Требование пользователя

> нужен прогон тестов только для PR (создание и любой пуш в рамках этого PR), идеальным было бы и перед слиянием проверить. обычный пуш напрямую минуя PR не должен стартовать тесты

Разбор на проверяемое поведение:

1. Создание PR → прогон тестов.
2. Пуш в ветку открытого PR → прогон тестов.
3. Гейт перед слиянием → защита ветки + обязательный статус-чек.
4. Прямой пуш (в `main` или в feature-ветку) **без PR** → тесты не запускаются.

## Подготовленный воркфлоу

Исходный план называл `.gitverse/workflows/check.yml`; реализация живёт в `.github/workflows/check.yml`.

```yaml
name: 'Check: тесты Gradle'

on:
  pull_request:
    types: [opened, synchronize, reopened]
    branches: [main]
  workflow_dispatch:

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      - name: Настроить JDK 21
        # существование setup-java в реестре GitVerse НЕ подтверждено — см. «Не проверено»
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Кеш Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('gradle/wrapper/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}

      - name: Сделать gradlew исполняемым
        run: chmod +x gradlew

      - name: Прогнать тесты
        run: ./gradlew check
```

Почему так:

- **Триггера `push` нет вообще** — это и даёт «прямой пуш не запускает тесты».
- `types` перечислены явно: `opened` = создание PR, `synchronize` = новый пуш в ветку PR.
  Доки GitVerse перечисляют ровно эти значения как поддерживаемые.
- `reopened` добавлен, чтобы переоткрытый PR тоже проверялся; убрать при желании.
- `workflow_dispatch` — ручной прогон для отладки. Появляется в UI только когда файл есть в `main`.
- `chmod +x gradlew` обязателен: в git-индексе `gradlew` записан как `100644`
  (`git ls-files -s gradlew` → `100644 23d15a9…`), на Linux это `Permission denied`.
- Кеш Gradle: wrapper 9.8.0 тянет ~130 МБ дистрибутив; без кеша это каждый прогон заново.
  Кеш не входит в лимит артефактов (доки GitVerse).

## «Проверить перед слиянием» — делается не воркфлоу

Проверено анонимным запросом: `GET https://gitverse.ru/api/repos/averkhogliad/tube-loader/branches/main/protection` → `200`

```json
{"protected": false, "required_approvals": 0,
 "enable_status_check": false, "status_check_contexts": []}
```

Механизм гейта:

1. Воркфлоу отрабатывает хотя бы раз → в репозитории появляется контекст чека.
2. Настройки репозитория → защита ветки `main` → включить `enable_status_check`
   и выбрать контекст чека в `status_check_contexts`.
3. Кнопка слияния блокируется до зелёного прогона. Заодно доступен `required_approvals`.

Настройки сейчас все выключены; в UI их не трогали.

Неизвестно (не проверялось): есть ли у GitVerse «branch up to date» — обязательность
перезапуска чека, когда `main` ушёл вперёд. И появляется ли контекст в списке до
первого прогона.

## План работ

1. **Локальный прогон** — убедиться, что задача `check` существует и зелёная.
   Без этого падение CI не отличить от проблем воркфлоу.
   ```powershell
   .\gradlew.bat check
   .\gradlew.bat tasks --all | Select-String check    # задача из базового плагина
   ```
2. **Создать** `.gitverse/workflows/check.yml` (текст выше).
3. **Проба на раннере** (как делали для трекера): временный шаг печатает контекст и наличие java,
   затем удаляется:
   ```yaml
   - name: Проба окружения
     run: |
       for t in java javac; do printf '%s: ' "$t"; command -v "$t" || echo missing; done
       java -version 2>&1 | head -3 || true
       echo "--- event ---"
       echo "event_name=${{ gitverse.event_name }}"
       echo "action=${{ gitverse.event.action }}"
       echo "pr=${{ gitverse.event.pull_request.number }}"
   ```
   Нужна, потому что JDK на образе и фактическое срабатывание `opened`/`synchronize`
   нигде не подтверждены.
4. **Живая проверка** по шагам: ветка → PR (`opened`) → второй коммит в ту же ветку (`synchronize`).
5. **Включить защиту ветки** `main` (пользователь, в UI) — после первого зелёного прогона.

## Альтернативный конфиг (если решат проверять и `main`)

Добавить пуш в `main`. Без фильтра по веткам будет по два прогона на каждый коммит в ветке PR
(`push` + `synchronize`), поэтому фильтр обязателен:

```yaml
on:
  push:
    branches: [main]
  pull_request:
    types: [opened, synchronize, reopened]
```

Плюс: видно, что `main` зелёный после слияния. Минус: расход минут на каждый мерж.

## Инфраструктурные факты (проверено)

| Факт | Значение | Чем проверено |
| --- | --- | --- |
| JDK в проекте | `jvmToolchain(21)` в `core` и `common/config` | `build.gradle.kts` модулей |
| Gradle wrapper | `gradle-9.8.0-bin.zip` | `gradle/wrapper/gradle-wrapper.properties` |
| `gradlew` в индексе | `100644`, без exec-бита | `git ls-files -s gradlew` |
| `gradle.properties` | `configuration-cache=true`, `warning-mode=all`, `kotlin.code.style=official` | файл в корне |
| Модули | `:common`, `:common:config`, `:core` | `settings.gradle.kts` |
| `check` | не переопределён; `test` настроен на `useJUnitPlatform()` | `build.gradle.kts` модулей |
| JDK локально | `JAVA_HOME=C:\Program Files\Java\jdk-21`, java 21.0.8 LTS | `java -version` |
| Типы `pull_request` | `opened`, `edited`, `closed`, `reopened`, `synchronize`; остальные не поддерживаются | доки GitVerse, раздел `on.pull_request.types` |
| Фильтры веток | `branches` / `branches-ignore` есть | доки GitVerse |
| Инструменты раннера | bash 5.2.21, curl, git, jq, node 24.14.0, python3 | прогон probe 29.09.2026 |
| Лимиты job | 30 минут; 1000 минут/мес для публичных репозиториев | доки GitVerse |
| Текущие воркфлоу | `.gitverse/workflows/`: `tracker-pr.yml`, `tracker-push.yml` | `list_dir` |

## Не проверено (решается пробным прогоном)

1. **JDK на раннере.** В доке про Java нет ни слова; в примерах GitVerse только `setup-node`
   и `setup-python`. `actions/setup-java@v4` в реестре GitVerse не подтверждён ни одним примером.
   Если экшена нет — вариант: `apt-get install -y temurin-21-jdk` (тоже не проверено) или
   self-hosted раннер.
2. **Срабатывание `opened` / `synchronize`.** `pull_request` в доке помечен как «частично
   поддерживается», а у нас был ровно один прогон этого события — `closed` (PR #7).
3. **Семантика `branches: [main]`** — фильтр по базе PR или по голове. В доке сказано просто
   «фильтрует запросы на слияние по веткам». Если по голове, то PR `feature/*` → `main`
   не запустится; тогда фильтр убрать.
4. **Время прогона** — уложится ли в 30 минут холодная сборка с нулевым кешем.
5. **Кеш Gradle** — работает ли `actions/cache@v4` на гитверсовых раннерах так же, как на GitHub.

## Взаимодействие с существующими воркфлоу

`tracker-push.yml` уже срабатывает на `push` в любой ветке (проверено) и пишет в Weeek.
Кеш `~/.gradle` и `check.yml` с ним не пересекаются: у трекера нет Gradle-шагов.
Конфликта по времени нет — это разные job'ы разных воркфлоу.

## Состояние репозитория на момент записи плана

- Ветка `ci/weeek-tracker-verify`, upstream `origin/ci/weeek-tracker-verify` — `[gone]` (удалён после мержа PR #7).
- Последний коммит: `7c5cb22 ci: link branch and author profiles in tracker comments`
  (ранее `56378e9` — хеш изменился, коммит переписан).
- Локальная `main` отстаёт от `origin/main` на squash-мерж `a8551f9` (PR #7).
- Рабочее дерево чистое.
- **Не решено:** куда класть коммит с `check.yml` — новая ветка от `origin/main`
  или заново запушить `ci/weeek-tracker-verify`.
