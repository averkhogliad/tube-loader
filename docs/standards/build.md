# Сборка: стандарт репо

Источник истины по прогону сборки и тестов. Команда одна на всех: локальный прогон и CI зовут
одну и ту же задачу, иначе «зелено у меня» перестаёт что-либо значить.

## Каноничная команда

```
./gradlew check --rerun-tasks
```

В PowerShell — `.\gradlew check --rerun-tasks`.

- `check` — задача базового плагина: тесты, линт, гейт покрытия. Отдельные задачи (`test`,
  `detekt`, `koverVerify`) для проверки не запускаются: они либо дублируют `check`, либо
  пропускают его часть.
- `--rerun-tasks` обязателен. Kotlin-демон отдаёт устаревшие классы из `build/`, и без флага
  прогон показывает результат предыдущей правки. Свежесть сборки проверяется по слову
  `fallback` в выводе: `Compilation in Kotlin daemon has failed … Using fallback strategy`
  означает, что демон исключён из процесса и результат прогона недостоверен.
- Стратегия компиляции задана в `gradle.properties` (`kotlin.compiler.execution.strategy`),
  поэтому в команде флага `-D…` нет.

## Читать результат по XML, а не по панели

Счётчики тестов берутся из `build/test-results/test/TEST-*.xml` — сумма `tests`, `failures`,
`errors`, `skipped` по всем `testsuite`. Панель прогона может показать `PASSED` для кейса,
который прогон не исполнял так, как ожидается. Базовые счётчики репозитория: `:core` — 162,
`:common:retry` — 20, `:common:config` — 44.

## Gotcha: битый `build/test-results/test/binary/*.bin`

Симптом — падение `:core:test` до запуска тестов:

```
> java.io.EOFException
```

Причина: Gradle читает бинарные результаты прошлого прогона в
`build/test-results/test/binary/` (`Test.getPreviousFailedTestClasses`), чтобы переупорядочить
классы. Усечённый или оборванный `.bin` (например, после жёсткой остановки задачи) разбирается
как `UncheckedIOException: java.io.EOFException`. Тесты тут ни при чём.

Лечение — удалить каталог и прогнать задачу заново:

```
Remove-Item -Recurse -Force core/build/test-results
./gradlew check --rerun-tasks
```

Проверено пробой: запись восьми байт в `core/build/test-results/test/binary/results-generic.bin`
даёт на `:core:test --rerun-tasks` ровно `> java.io.EOFException` (в `--stacktrace` —
`Caused by: java.io.UncheckedIOException: java.io.EOFException`, кадр
`org.gradle.api.tasks.testing.Test.getPreviousFailedTestClasses(Test.java:752)`); удаление
каталога и повторный прогон — `BUILD SUCCESSFUL`, 226 тестов, 0 failures.
