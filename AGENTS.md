# AGENTS.md

Руководство для контрибьюторов (людей и агентов) репозитория Tubeloader.

## О проекте

Что это, цели, не-цели и текущее состояние — `README.md`.

## Стандарты

Правила написания тестов и тестовый стек — `docs/standards/testing.md`; прогон сборки и тестов —
`docs/standards/build.md`; передача ошибок между швами — `docs/standards/errors.md`; порядок
параметров (колбеки последними), sealed-исходы — `docs/standards/code.md`; архитектурные
инварианты — `docs/standards/architecture.md`; правила описания исследований —
`docs/standards/research.md`; оси ревью и цикл правок — `docs/standards/review.md`; согласование
со спекой и тикетом — `docs/standards/implementation.md`; зоны ответственности CI-скриптов —
`docs/standards/ci-scripts.md`; фича-специфичные швы — Testing Decisions спеки фичи
(`docs/features/<feature>/spec.md`).
Стек и роли зависимостей — `docs/tech-stack.md`; версии — `gradle/libs.versions.toml`.

## Agent skills

### Issue tracker

Задачи — issue в GitHub-репозитории `averkhogliad/tube-loader`. Скрипт —
`scripts/tracker/tracker.mjs` (REST + PAT), вместо `gh`.
См. `docs/agents/issue-tracker.md`.

### Triage labels

Словарь меток и их назначение — `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` и `docs/adr/` в корне. См. `docs/agents/domain.md`.

### Specs

Спека — `docs/features/<feature>/spec.md`, перечисляет свои тикеты в `## Tickets`. См. `docs/agents/specs.md`.

## Заметки

- Референсы снесённого прототипа — `docs/archive/prototype/` (не собираются, не точки входа).
- Переводы строк нормализует `.gitattributes`, а не личная настройка `core.autocrlf`.
