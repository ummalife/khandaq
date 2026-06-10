# Khandaq Changelog

История изменений для https://khandaq.org/changelog — генерируется из git и релизных записей.

## Как помечать коммиты

В **subject** коммита (первая строка):

```
[critical] Fix unlock crash after password on Android
[important] Add strict push URL whitelist
[medium] Limit desktop markdown parsing to 16k
[low] Update deploy script comments
```

Альтернатива в **body** коммита:

```
severity: critical
```

Допустимые уровни: `critical`, `important`, `medium`, `low`.

Коммиты **без метки** не попадают в дневной лог (только в полный список на GitHub).

## Релизные записи (крупные изменения)

Для security audit, релизов версий — файл `changelog/releases/YYYY-MM-DD-vX.Y.Z.json`:

```json
{
  "version": "0.2.5",
  "date": "2026-06-10",
  "title": "Security audit remediation",
  "commits": ["48cf534", "d3b7c7d"],
  "changes": [
    {
      "severity": "critical",
      "title": "Краткий заголовок",
      "description": "Что исправлено и почему.",
      "platforms": ["android", "ios"]
    }
  ]
}
```

## Генерация

```bash
python3 scripts/generate-changelog.py
# → web/changelog.json
```

Запускается автоматически в `scripts/deploy-site.sh` перед заливкой на сервер.

## Workflow

1. Фикс → коммит с `[severity]` в subject
2. Крупный релиз → добавить/обновить `changelog/releases/*.json`
3. `python3 scripts/generate-changelog.py`
4. `bash scripts/deploy-site.sh` (или push в master — CI проверит актуальность JSON)
