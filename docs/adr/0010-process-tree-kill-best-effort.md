# 0010: Process tree kill — best-effort, без Job Object

## Status

Принято. Применяется к `FfmpegProcessRunner` (`core.media`) и `YtDlpProcessRunner`
(`adapters.youtube`) с M1.

## Context

Оба CLI-раннера гасят процесс инструмента по `CancellationException` корутины. Раньше
спека ffmpeg и ADR-0008 обещали `ProcessHandle.destroyTree()`; спека youtube-адаптера —
`ProcessHandle.destroyTree()` и, на Windows, Job Object `KILL_ON_JOB_CLOSE`.

Код обоих раннеров делает вручную то, что ближе к `destroyTree()`: снимок
`process.descendants()`, `destroy()` каждого ребёнка, `destroy()` родителя, ожидание в
grace-окне, затем `destroyForcibly()`. Job Object не реализован.

Остаточный риск: ребёнок, порождённый инструментом **после** снимка `descendants()`
(например, ffmpeg, который yt-dlp запускает для мёрджа ближе к концу скачивания), может
пережить отмену. `ProcessHandle.destroyTree()` (Java 9) страдает тем же — он тоже
снимочный, не атомарный. Единственный race-free механизм на Windows — Job Object
`KILL_ON_JOB_CLOSE`, но у JDK нет его API, а обёртка требует нативной библиотеки или JNI.
Спеки ffmpeg и youtube запрещают новые библиотечные зависимости для инструментов.

## Decision

Механизм tree-kill обоих раннеров: по `CancellationException` —
`process.descendants().forEach { it.destroy() }`, `process.destroy()`, ожидание
`onExit()` в grace-окне (`FfmpegProcessRunner.GRACE_TIMEOUT_MILLIS` /
`YtDlpProcessRunner.GRACE_TIMEOUT_MILLIS`), затем `destroyForcibly()`, если процесс не
вышел. Всё под `NonCancellable`, чтобы отмена доводила kill до конца.

Job Object `KILL_ON_JOB_CLOSE` **не реализуется**: потребует нативной зависимости/JNI,
запрещённой спеками; JDK не предоставляет API. Остаточный риск ребёнка-после-снимка
принят как компромисс за отсутствие нативной зависимости.

Поведение, закреплённое на уровне спек: отмена гасит дерево процессов запущенного
инструмента. Детали механизма и остаточный риск — здесь.

## Considered Options

- **`ProcessHandle.destroyTree()` как есть.** Отвергнуто как имплементация: ручной
  descendants+grace+forcibly даёт graceful-фазу, которой у `destroyTree()` нет;
  race-свободности не даёт ни то, ни другое.
- **Job Object через нативную библиотеку.** Отвергнуто: новые библиотечные зависимости
  для инструментов запрещены спеками; JNI-обёртка — объём вне рамок.
- **Переделка youtube-адаптера под `Native`: yt-dlp качает потоки, мёрджит
  `FfmpegMediaTool`.** Отвергнуто: ffmpeg-ребёнок переезжает из дерева yt-dlp в дерево
  `FfmpegProcessRunner` с тем же остаточным риском; плюс теряем мёрдж yt-dlp и меняем
  capability-модель.

## Consequences

**Положительные.**
- Один механизм для обоих раннеров; graceful-фаза перед жёстким kill.
- Нет нативных зависимостей; нижняя планка — Java 9 (`ProcessHandle.descendants`).

**Отрицательные.**
- Остаточный риск: ребёнок, порождённый после снимка `descendants()`, переживает
  отмену. На практике окно race — grace-таймаут; убитый родитель новых детей не
  порождает. Принято.
- На Windows хэндл переживающего ребёнка может держать staging-каталог; чистка
  staging должна быть устойчива к `IOException` (реализационная мера раннера).

## References

- `FfmpegProcessRunner.kt`, `YtDlpProcessRunner.kt` — реализация.
- `docs/features/media-ffmpeg/spec.md`, `docs/features/youtube-adapter/spec.md` —
  поведение.
- ADR-0008 — ffmpeg CLI-процесс.
- `docs/standards/architecture.md` — «внешние CLI-инструменты запускаются в отдельном
  процессе».
