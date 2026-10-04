# Оценка CI-флоу

Статус: **исследование**, не решение. Отчёт описывает факты и развилки, не фиксирует выбор.
Тикет: GitHub issue #16 (`wayfinder:research`), Weeek-карточка 93.

Все утверждения ниже проверены на состоянии репозитория `6257b9e` (2026-10-13) живыми
запросами к API, прогонами в песочнице вне репозитория и чтением исходников плагинов.
Где механизм не проверен прогоном, это указано явно.

## 1. Тело тикета устарело

Тело issue #16 описывает GitVerse. Фактическое состояние — GitHub.

| Утверждение в теле issue | Факт на 2026-10-13 | Источник |
|---|---|---|
| «Текущий CI на GitVerse: два воркфлоу — только уведомления» | Три воркфлоу на GitHub; `check` тоже активен | `GET /repos/averkhogliad/tube-loader/actions/workflows` |
| «Ни один воркфлоу не запускает `gradlew check`» | `check` запускает `./gradlew check` | `.github/workflows/check.yml:33` |
| «Ветка `main` не защищена (`protected: false`)» | `protected: true`; рулсет активен | `GET /repos/.../branches/main`, `GET /repos/.../rulesets/24324963` |
| «Готовый `check.yml` лежит неприменённым» | Применён, 7 прогонов, все success | `GET /repos/.../actions/workflows/{id}/runs` |
| «Ограничения GitVerse Actions: setup-java@v4, кеш, лимит 30 мин» | Раннер `ubuntu-latest` (`:18`), `actions/setup-java@v4` (`:24`), `gradle/actions/setup-gradle@v4` (`:30`), timeout 20 мин (`:19`) | `check.yml:18-30` |
| «Альтернативы: внешний CI на legacy remote» | Альтернатива реализована: primary CI на GitHub, GitVerse — только зеркало | `git remote -v` |

GitVerse-специфичные пункты закрыты вместе с переездом и не требуют исследования. Из шести
пунктов живыми остаются три: **push-триггеры**, **Kover-гейт**, **линт**.

## 2. Текущее состояние CI

### 2.1 Воркфлоу

`GET /repos/averkhogliad/tube-loader/actions/workflows` — три, все `active`:

- `check` — `.github/workflows/check.yml`
- `Tracker: PR merge to Weeek` — `.github/workflows/tracker-pr.yml`
- `Tracker: commits to Weeek` — `.github/workflows/tracker-push.yml`

`check.yml` целиком (33 строки, прочитан с диска):

```yaml
on:
  pull_request:
    branches: [main]
  workflow_dispatch:

concurrency:
  group: check-${{ github.ref }}
  cancel-in-progress: true

jobs:
  check:
    name: check
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v4   # temurin, java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew check
```

**Триггера `push` нет вообще.** Событие `pull_request` использует типы по умолчанию
(`opened`, `synchronize`, `reopened`), то есть прогон запускается на создание PR и на каждый
коммит в его ветку.

### 2.2 Прогоны

`GET /repos/.../actions/workflows/{check}/runs` — 7 прогонов, все `success`:

| run id | событие | старт (UTC) | длительность job | шаг `Run gradle check` |
|---|---|---|---|---|
| 37201813142 | pull_request | 2026-10-04 12:21:20 | 60 с | 46 с |
| 37200522767 | pull_request | 2026-10-04 11:58:38 | 61 с | 51 с |
| 37200358826 | pull_request | 2026-10-04 11:55:29 | 39 с | 27 с |
| 37197955881 | pull_request | 2026-10-04 11:12:39 | 58 с | 49 с |
| 37196777520 | pull_request | 2026-10-04 10:52:20 | 46 с | 34 с |
| 36987049253 | pull_request | 2026-10-02 08:57:59 | 49 с | 38 с |
| 36907907245 | workflow_dispatch | 2026-10-01 18:35:01 | 144 с | 97 с |

Первый прогон (2026-10-01, `workflow_dispatch`) — 97 с: холодный кеш. Дальше `setup-gradle`
переиспользует кеш, шаг сборки укладывается в 38–51 с. Шаг «Set up Gradle» — 3–8 с.

### 2.3 Защита `main`

`GET /repos/averkhogliad/tube-loader/rulesets/24324963`:

- `name` = `main: pull request required`, `target` = `branch`, `enforcement` = `active`
- правила: `deletion`, `non_fast_forward`, `pull_request`, `required_status_checks`
- `pull_request`: `required_approving_review_count` = 0, `allowed_merge_methods` = `["squash"]`
- `required_status_checks`: контекст **`check`** (integration_id 15368 — GitHub Actions),
  `strict_required_status_checks_policy` = `true`
- `bypass_actors` = `[]` — обхода нет ни у кого, включая владельца

`GET /repos/.../branches/main` → `protected: true`. То есть мерж в `main` блокируется, пока
контекст `check` не зелёный, ветка должна быть поверх свежего `main`, и только squash.

### 2.4 Стоимость

Репозиторий публичный (`GET /repos/...` → `private: false`, `visibility: public`). Минуты
GitHub Actions для публичных репозиториев не тарифицируются. Ограничение, которое реально
действует, — `timeout-minutes: 20`, то есть потолок на один прогон, а не квота.

## 3. Пункт «push-триггеры»

### 3.1 Механика

Сейчас прогон идёт только по `pull_request`. Формулировка пользователя (29.09.2026, из
`docs/research/gradle-check.md`): «нужен прогон тестов только для PR… обычный пуш напрямую
минуя PR не должен стартовать тесты».

Варианты добавления `push`:

| Форма | Поведение | Дубль на один SHA |
|---|---|---|
| без фильтра `push` | прогон на каждую ветку | **да**: `push` + `pull_request/synchronize` на коммит ветки PR |
| `push: branches: [main]` | прогон после squash-мержа | **нет**: у merge-коммита свой SHA |

Механизм дубля: при `push` без фильтра на коммит `abc123` в ветке открытого PR стартуют два
прогона — от события `push` (ref `refs/heads/feature/...`) и от `pull_request/synchronize`
(ref `refs/pull/N/merge`). `concurrency.group` у них разный (`check-${{ github.ref }}`), так что
`cancel-in-progress` один из них не погасит — оба доработают.

### 3.2 Дубли сообщений в Weeek

Дублей комментариев при добавлении `push: branches: [main]` не будет. Проверено в коде:

- `scripts/weeek/weeek.sh:187` — маркер push-уведомления: полный sha коммита в тексте ссылки;
- `scripts/weeek/weeek.sh:35` — маркер PR-уведомления: `pr:<PR number>`;
- `has_marker` ищет маркер по телам комментариев (`scripts/weeek/weeek.sh:237`);
- если запрос дедупа не удался, публикация **не выполняется** (`weeek.sh:195,197`), чтобы сбой не
  плодил дубли.

Маркеры не пересекаются: sha и `pr:N` — разные строки. Повторный прогон по тому же коммиту
(например, повторный `synchronize` без нового коммита) отсекается по `sha`.

**Отдельно:** `tracker-push.yml` слушает `push` в **любой** ветке, поэтому коммиты
feature-ветки уходят на доску ещё до мержа. На один мерж придут два комментария — «PR merge»
(`pr:N`) и «Commits to branch main» (`sha:`) — это два разных уведомления о двух разных фактах,
а не дубль одного. Согласованное поведение, менять не обязательно.

## 4. Пункт «Kover-гейт»

### 4.1 Текущее состояние покрытия

Свежие числа получены в этой сессии: `./gradlew :core:koverXmlReport :common:config:koverXmlReport
--rerun-tasks`, `BUILD SUCCESSFUL in 32s`, оба `koverXmlReport` отработали.

| модуль | знаменатель | строки | ветки |
|---|---|---|---|
| `:core` | **текущий total** | 93.96% (467/497) | 82.05% (128/156) |
| `:core` | **без testFixtures** | 96.94% (222/229) | 86.61% (97/112) |
| `:core` | (исключаемая часть — testFixtures) | 91.42% (245/268) | 70.45% (31/44) |
| `:common:config` | **текущий total** | 96.12% (99/103) | 89.71% (61/68) |
| `:common:config` | **без testFixtures** | 96.63% (86/89) | 90.91% (60/66) |
| `:common:config` | (исключаемая часть — testFixtures) | 92.86% (13/14) | 50.00% (1/2) |

Сейчас гейта нет: `kover` подключён в обоих модулях как плагин, но блока `kover { … }` с
правилами нигде нет (проверено поиском по всем `*.kts`).

### 4.2 `testFixtures` попадают в знаменатель

Проба в песочнице: модуль с `src/main` (один класс) и `src/testFixtures` (второй класс), из
тестов покрыт только main. Отчёт `koverXmlReport` содержит **оба** файла:

```
sourcefiles: Calc.kt, FakeCalc.kt
TOTAL LINE covered 2 missed 14 = 12.50%
TOTAL BRANCH covered 1 missed 15 = 6.25%
```

То есть фикстуры по умолчанию уменьшают покрытие модуля. Для `:core` это 3 пп по строкам и
4.5 пп по веткам.

### 4.3 Как исключить `testFixtures` — механизм найден пробой

Работает **только** объявление `sources` напрямую на `currentProject`:

```kotlin
kover {
    currentProject {
        sources {
            excludedSourceSets.add("testFixtures")
        }
    }
}
```

Проба: `sourcefiles: Calc.kt` — `FakeCalc.kt` исключён, `TOTAL LINE 2/10`, `TOTAL BRANCH 1/10`.

Два неверных варианта, которые тоже компилируются и молча не срабатывают:

- `currentProject { providedVariant("jvm") { sources { … } } }` — собирается, прогон зелёный,
  но `FakeCalc.kt` остаётся в отчёте. Механизм: `variantConfig()` (исходники
  `FinalizeKover.kt:180-186`) читает конфиг из `currentProject.customVariants`, а для
  отсутствующего имени создаёт конфиг через `deriveFrom(currentProject)` — то есть `sources`
  всё равно наследуются от `currentProject`. Вложенный `providedVariant` в JVM-модуле ни на что
  не влияет.
- `currentProject { totalVariant { … } }` — **падает**: `totalVariant` кладёт конфиг в map под
  пустым ключом, а финализация запрещает такой вариант:
  `It is unacceptable to configure provided variant '', since there is no such variant in the project. Acceptable variants: [jvm]`
  (`FinalizeKover.kt:71-75`).

Имя `testFixtures` совпадает с именем компиляции, которое заводит Gradle при
`java-test-fixtures`; фильтр применяется к имени компиляции
(`JvmVariantArtifacts.kt:52-54`, проверка — `:57-72`).

### 4.4 Правило на новый модуль без правки — работает

Правило объявляется один раз в корневом `build.gradle.kts`:

```kotlin
subprojects {
    plugins.withId("org.jetbrains.kotlinx.kover") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
            currentProject { sources { excludedSourceSets.add("testFixtures") } }
            reports {
                total {
                    verify {
                        rule("line")   { minBound(80) }
                        rule("branch") { minBound(75, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH) }
                    }
                }
            }
        }
    }
}
```

Проба из двух модулей, **без** локального блока `kover {}` ни в одном:

- `:app` (в `src/main` непокрытая ветка) → `BUILD FAILED`, сообщение:
  `Rule 'line' violated: lines covered percentage is 20.000000, but expected minimum is 80`
  `Rule 'branch' violated: branches covered percentage is 10.000000, but expected minimum is 75`
- `:good` (100% покрытие) → `BUILD SUCCESSFUL`, `> Task :good:koverVerify`, отчёт
  `LINE covered 2 missed 0`, `BRANCH covered 2 missed 0`.

Новый модуль с плагином Kover получает гейт автоматически, отдельной правки не требует.

**Ограничение:** модуль, забывший подключить плагин Kover, останется без гейта — правило
навешивается по `plugins.withId`. Закрытие этой дыры требует отдельной проверки «во всех
модулях есть плагин» и выходит за рамки гейта.

### 4.5 Порог на модуль, а не на весь билд — работает

Проба с двумя разными порогами: `:app` (90/90) и `:good` (50). `:app:check` упал на пороге 90,
`:good:check` прошёл на пороге 50. Каждый модуль применяет **своё** правило. Гейт на модуль
защищает слабый модуль от маскировки сильным соседом.

### 4.6 Гейт срабатывает внутри `gradle check`

Проба: `./gradlew check` в песочнице → в графе задач есть `> Task :app:koverVerify FAILED`, и
именно он валит `check`. Отдельного шага в воркфлоу не требуется — `check` уже вызывается
как `./gradlew check` в `check.yml:33`. Проба подтвердила сообщение
`Execution failed for task ':app:koverVerify'`.

### 4.7 Тип единиц покрытия

По умолчанию `minBound(80)` — это **строки**; для веток нужен второй вызов
`minBound(75, CoverageUnit.BRANCH)`. Оба правила можно объявить в одном `verify` (проверено
пробой: в сообщении об ошибке фигурируют обе строки, `line` и `branch`).

### 4.8 Взаимодействие с configuration cache

`gradle.properties` содержит `org.gradle.configuration-cache=true`. В песочнице (тот же флаг)
все прогоны с гейтом завершались сообщением `Configuration cache entry stored.` — правило не
ломает кеш. `org.gradle.configuration-cache.problems=warn` означает, что проблемы кеша не валят
сборку, поэтому «зелёный прогон» сам по себе не доказывает корректность правила — доказательством
служит срабатывание гейта (п. 4.4).

## 5. Пункт «линт»

### 5.1 Что включает каждый инструмент

- **ktlint** — формат-линтер. Только стиль: отступы, пробелы, раскладка импортов, длина строки,
  официальный стиль Kotlin. Логику и потенциальные баги не ищет.
- **detekt** — статический анализатор. Сложность, потенциальные баги, code smells, правила для
  корутин, нейминг, магические числа. Около двухсот правил, включаются через `detekt.yml`.
- **spotless** — не линтер, а форматтер-обёртка: приводит к формату Kotlin (через ktlint или
  ktfmt), а также `.kts`, `.yml`, `.md`. По Kotlin дублирует ktlint; сверх него покрывает
  не-Kotlin файлы, которых в репозитории почти нет.

### 5.2 Совместимость со стеком

Стек: Gradle 9.8.0 (`gradle/wrapper/gradle-wrapper.properties`), Kotlin 2.4.20
(`gradle/libs.versions.toml`), JDK 21 (`jvmToolchain(21)` в обоих модулях).

Последние версии по данным Maven Central и Gradle Plugin Portal:

| инструмент | последняя стабильная | собрана против | вердикт |
|---|---|---|---|
| detekt | 1.23.8 | Gradle 8.12.1, Kotlin 2.0.21, JDK 21 | **работает** (см. 5.3) |
| detekt | 2.0.0-alpha.6 | Gradle 9.6.1, Kotlin 2.4.10, JDK 25 | alpha, не проверялась |
| detekt-parser (внутри 1.23.8) | тянет `kotlin-compiler-embeddable:2.0.21` | — | не помешал |
| ktlint-gradle (плагин) | 14.2.0 | минимум Gradle 7.4 | **работает** (см. 5.4) |
| ktlint (CLI) | 1.8.0 | — | **работает** |
| spotless-plugin-gradle | 8.10.3 | — | не проверялась, не рекомендуется |

Конфликт «последняя стабильная против совместимой версии» для detekt оказался несущественным:
стабильный detekt 1.23.8 разбирает Kotlin 2.4.20 без падений.

### 5.3 detekt 1.23.8 — прогон по реальному коду

Песочница: Gradle 9.8.0, Kotlin 2.4.20, JDK 21, `io.gitlab.arturbosch.detekt` 1.23.8, в
исходники положены все 27 `.kt` файлов `core/src/main` и `common/config/src/main` из репозитория.

Результат — **6 замечаний**, падение от правил, а не от парсера:

```
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':detekt'.
> Analysis failed with 6 weighted issues.
```

Находки:

| файл:строка | правило | текст |
|---|---|---|
| `config/MergedConfig.kt:31:33` | `ComplexCondition` | условие сложнее порога 4 |
| `core/CoreFacade.kt:19:7` | `TooManyFunctions` | 13 функций при пороге 11 |
| `core/CoreFacade.kt:132:18` | `TooGenericExceptionCaught` | ловится слишком общий `Exception` |
| `core/CoreFacade.kt:132:18` | `SwallowedException` | исключение проглатывается |
| `config/FileConfigSource.kt:15:27` | `UseCheckOrError` | `throw IllegalStateException` вместо `check()`/`error()` |
| `core/AppConfig.kt:29:21` | `ReturnCount` | 3 `return` при лимите 2 |

Detekt по умолчанию **входит в `gradle check`** — проба подтвердила: при `./gradlew check` в
графе присутствует `> Task :detekt`. То есть подключение detekt само по себе меняет гейт.

### 5.4 ktlint 14.2.0 — прогон по реальному коду

Та же песочница, плагин `org.jlleitschuh.gradle.ktlint` 14.2.0, ktlint 1.8.0, те же 27 файлов.

Результат: **118 находок** в main-сорсетах (`ktlintMainSourceSetCheck.txt` — 118 строк) плюс
8 в kotlin-скриптах (`ktlintKotlinScriptCheck.txt`). Разбивка из отчёта:

```
ktlintKotlinScriptCheck.txt: 8 findings
ktlintMainSourceSetCheck.txt: 118 findings
```

Характер находок — форматирование: `Newline expected after opening parenthesis`,
`Parameter should start on a newline`, `A multiline expression should start on a new line`,
`Class body should not start with blank line`, `Backing property is only allowed when the
matching property or function is public`.

Причина такого числа — отсутствие `.editorconfig` в репозитории (файл отсутствует) и
неприменявшийся стиль: код писался без автоформата.

ktlint-задачи также видны в графе `check` (`ktlintMainSourceSetCheck`, `ktlintKotlinScriptCheck`).

### 5.5 Baseline обязателен

Без baseline первый же прогон валит `check` на существующем коде: detekt — 6 замечаний,
ktlint — 118. Подключение «как есть» выглядит как поломка CI на ровном месте.

Оба инструмента умеют baseline, проверено прогонами:

- **detekt**: задача `detektBaseline` создаёт `detekt-baseline.xml` (12 строк, 6 записей);
  при последующем `./gradlew detekt` и `./gradlew check` → `BUILD SUCCESSFUL`.
- **ktlint**: задача `ktlintGenerateBaseline` создаёт `ktlint-baseline.xml` (157 строк,
  путь в baseline относительный — `src/main/kotlin/...`); при последующем
  `ktlintMainSourceSetCheck` → `BUILD SUCCESSFUL`.

Механика: baseline фиксирует текущие находки, гейт ловит только новые. Пути в baseline
относительны корню модуля, поэтому файл переносим между машинами (проверено: baseline,
сгенерированный в песочнице, корректно подавил находки при повторной проверке).

### 5.6 Ловушка пробы, которую стоит знать

Baseline, сгенерированный при `srcDir`-редиректе на внешний каталог, содержит пути
`../../../../../../../Projects/...` и при обычной проверке (исходники внутри модуля) **не
подавляет ничего** — прогон снова красный. В реальном репозитории этой ловушки нет: исходники
лежат внутри модуля, пути относительные. Упоминается, чтобы результат «baseline не работает»
не был воспроизведён по ошибке.

## 6. Альтернативы

**GitHub Actions — primary, GitVerse — зеркало.** Реализовано: `git remote -v` показывает
`origin` = GitHub, `legacy` = GitVerse. CI живёт только на GitHub; GitVerse-слой (`.gitverse/`)
удалён 02.10.2026, снапшот в `docs/archive/gitverse/`.

Альтернативы внешнему CI (`Jenkins`, сторонние SaaS) в тикете не заявлены, а потребности,
которые бы их оправдывали, здесь нет: прогон укладывается в 40–100 с, минуты для публичного
репозитория бесплатны, потолок в 20 минут не приближается. Смысла во внешнем CI нет.

## 7. Что осталось нереализованным

| Что | Цена | Риск |
|---|---|---|
| `push: branches: [main]` в `check.yml` | 1 строка | нет; дублей прогонов и сообщений нет (п. 3) |
| Правило Kover 80/75 + исключение `testFixtures` | блок `subprojects` в корневом `build.gradle.kts` | механизм проверен; см. 4.3 про неверные формы |
| detekt 1.23.8 + baseline | плагин + `detekt-baseline.xml` (6 записей) | detekt входит в `check` автоматически |
| ktlint 14.2.0 + baseline | плагин + `ktlint-baseline.xml` (~157 строк) | 118 находок на легаси → baseline обязателен |

## 8. Открытые решения (не решены этим отчётом)

1. **Порог Kover**: рекомендовано 80% строк / 75% веток. Запас: `:core` без фикстур —
   96.94% / 86.61%, `:common:config` — 96.63% / 90.91%. Вариант 85/75 тоже проходит.
   При исключённых `testFixtures` порог **не** на грани (запас ≥11 пп), в отличие от порога
   по текущему total, где ветки `:core` — 82.05%, вплотную к 80.
2. **Нужен ли линт** и в каком составе: ktlint + detekt (рекомендация) либо только один.
   spotless отклонён: дублирует ktlint, а не-Kotlin файлов в репозитории почти нет.
3. **Тикеты на внедрение** — заводить по итогам отчёта или отдельным решением.

## 9. Источники

- `.github/workflows/check.yml`, `tracker-push.yml`, `tracker-pr.yml` — прочитаны с диска.
- `GET /repos/averkhogliad/tube-loader` — видимость, дефолтная ветка.
- `GET /repos/averkhogliad/tube-loader/branches/main` — `protected: true`.
- `GET /repos/averkhogliad/tube-loader/rulesets/24324963` — правила, статус-чеки, bypass.
- `GET /repos/averkhogliad/tube-loader/actions/workflows` — три воркфлоу, состояния.
- `GET /repos/averkhogliad/tube-loader/actions/workflows/{id}/runs` — 7 прогонов.
- `GET /repos/averkhogliad/tube-loader/actions/runs/{id}/jobs` — тайминги шагов.
- Прогоны в песочнице `%TEMP%\tb-ci-sandbox` и `%TEMP%\tb-lint-sandbox` (вне репозитория,
  Gradle 9.8.0, JDK 21, Kotlin 2.4.20) — механизм гейта, исключение фикстур, линтеры, baseline.
- Исходники `kover-gradle-plugin-0.9.11-sources.jar` — `FinalizeKover.kt`,
  `VariantsImpl.kt`, `JvmVariantArtifacts.kt`, `KoverProjectExtension.kt`.
- `./gradlew :core:koverXmlReport :common:config:koverXmlReport --rerun-tasks` — свежие числа
  покрытия (эта сессия).
- `scripts/weeek/weeek.sh` — маркеры дедупа комментариев (`sha:`, `pr:N`).
- `docs/research/gradle-check.md` — исходная формулировка требования пользователя.

## 10. Воспроизведение

Песочницы не входят в репозиторий и создаются заново. Ключевые шаги:

1. Скопировать `gradlew`, `gradlew.bat`, `gradle/wrapper/*` и `gradle/libs.versions.toml`
   в пустой каталог вне репозитория.
2. Создать модуль с `java-test-fixtures`, классом в `src/main` и классом в `src/testFixtures`,
   покрыть из теста только main.
3. Прогнать `:module:koverXmlReport --rerun-tasks` — убедиться, что оба файла в отчёте.
4. Добавить `currentProject { sources { excludedSourceSets.add("testFixtures") } }` — убедиться,
   что файл фикстур исчез из отчёта.
5. Добавить правило `verify` и прогнать `check` — убедиться, что `:module:koverVerify` валит
   сборку с сообщением о пороге.

Прогон покрытия самого репозитория:
`./gradlew :core:koverXmlReport :common:config:koverXmlReport --rerun-tasks`
