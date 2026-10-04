# Issue tracker: GitHub Issues

Задачи живут issue в GitHub-репозитории `averkhogliad/tube-loader`. Спека — отдельно, в `docs/`.
Общение с трекером идёт скриптом `scripts/tracker/tracker.mjs`, а не `gh`: на этой машине `gh`
нет, и он не нужен — скрипт ходит в REST по PAT из переменной окружения.

## Команды

```
node scripts/tracker/tracker.mjs check
node scripts/tracker/tracker.mjs issue create --title T --body-file F --label ready-for-agent
node scripts/tracker/tracker.mjs issue view 15 --comments
node scripts/tracker/tracker.mjs issue list --state open --label ready-for-agent
node scripts/tracker/tracker.mjs issue edit 15 --add-label needs-info --remove-label ready-for-agent
node scripts/tracker/tracker.mjs issue comment 15 --body-file F
node scripts/tracker/tracker.mjs issue close 15 --comment-file F --reason completed
node scripts/tracker/tracker.mjs label list
node scripts/tracker/tracker.mjs block 17 --by 15
node scripts/tracker/tracker.mjs sub add 20 --to 19
node scripts/tracker/tracker.mjs claim 15
node scripts/tracker/tracker.mjs resolve 15 --body-file F
node scripts/tracker/tracker.mjs frontier --label ready-for-agent
```

`--body` и `--comment` принимают одну строку; для markdown использовать `--body-file`.
Многострочный markdown через аргумент шелла ломается на кавычках чаще, чем файл.

## Словарь меток

Набор меток репозитория и их смысл — [triage-labels.md](triage-labels.md). Скрипт словаря не
знает: `--label` уходит в трекер как есть. Опечатка создаст новую метку.

`issue create --label` и `issue edit --add-label` **задают набор меток целиком**, а не дополняют
его: PATCH уходит с `labels: [...]`, и всё, чего в списке нет, снимается. Перечислять все нужные
метки в одном флаге через запятую: `--add-label ready-for-agent,wayfinder:task`. Второй
`--add-label` в той же команде теряется — парсер оставляет последнее значение флага. Для снятия
одной метки при сохранении остальных есть `--remove-label` (отдельный DELETE на метку), но он не
спасает от потери набора, если рядом указан `--add-label`.

## PR как поверхность запросов

**PRs as a request surface: no.** _(Поставить `yes`, если репозиторий считает внешние PR заявками
на фичу; `/triage` читает этот флаг.)_

При `yes` PR проходят те же роли и состояния, что issue. Скрипт их не покрывает: PR-операции
делаются через REST напрямую. Нумерация у issue и PR общая — голый `#42` может быть любым,
разрешать по флагу.

## Тело тикета

```
**Spec:** docs/features/<feature>/spec.md

## What to build
## Acceptance criteria
## Blocked by
```

Строку `**Spec:**` дописывает `/to-tickets`. Спека без тикетов и тикеты без спеки — норма
(`tech-debt/01` и `tech-debt/03` спеки не имеют).

## Статусы

Триаж-роли и строки меток, которыми они выражены, — [triage-labels.md](triage-labels.md).

Дефолтной метки у скрипта нет. Её ставит тот, кто создаёт тикет: `/to-tickets` — `ready-for-agent`.
Закрытие — не метка, а `state: closed` самого issue.

## Когда навык говорит «опубликовать в трекер»

`issue create` с телом в файле и меткой. Закрывать сразу — через `issue close`.

## Когда навык говорит «получить тикет»

`issue view N --comments`. Пользователь обычно передаёт номер или URL.

## Wayfinding

Используется `/wayfinder`. **Map** — issue с меткой `wayfinder:map`, **child** — issue с меткой
своего типа, связанный с картой как sub-issue.

- **Map**: тело — Notes / Decisions-so-far / Not yet specified / Out of scope.
- **Child ticket**: метка типа (`wayfinder:research` / `prototype` / `grilling` / `task`).
- **Blocking**: нативная зависимость, `block 17 --by 15`. Тикет разблокирован, когда все блокеры
  закрыты.
- **Frontier**: `frontier --map 19` — открытые дети карты, незаблокированные и незанятые.
  Без `--map` брать `--label`.
- **Claim**: `claim N` — назначить себя, до начала работы.
- **Resolve**: `resolve N --body-file F` — ответ комментарием и закрытие, затем указатель на
  контекст в Decisions-so-far карты.

## Чего трекер не умеет

REST не удаляет issue. Ошибочный тикет закрывается, а не стирается. Удаление репозитория требует
scope `delete_repo`, которого у PAT нет.
