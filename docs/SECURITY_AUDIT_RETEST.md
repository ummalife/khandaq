# Khandaq — чеклист повторной проверки (post v0.2.6)

Дата фиксов: 2026-06-11  
Базовый коммит: tag `v0.2.6` на `master`  
APK: https://khandaq.org/downloads/khandaq-android.apk (v0.2.6, build 10298)

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
| iOS | TestFlight build **142824** — https://testflight.apple.com/join/4ppS8ZN5 |
| Desktop macOS | https://khandaq.org/downloads/khandaq-macos.zip |
| Desktop Windows | https://khandaq.org/downloads/khandaq-windows-installer.exe |
| Desktop Linux | https://khandaq.org/downloads/khandaq-linux-x86_64-portable.tar.gz |
| Push relay | https://push.khandaq.org/health → `auth_required` field |

## Desktop Windows — CVE-зависимости и проверка происхождения сборки

Финальный незакрытый пункт отчёта (Windows-бинарь со встроенными библиотеками,
содержавшими CVE). **Закрыто и верифицировано хешом 1:1.**

Пропатченные зависимости в сборке:

- **libvpx 1.14.0** (было 1.11.0) — закрывает **CVE-2023-5217** (heap overflow в VP8)
- **OpenSSL 1.1.1w**
- **FFmpeg 4.4.5**

Происхождение отгруженного бинаря (chain of custody):

- CI-ран **`27848909085`** «Windows build (security D-7/8/9)» — `success`, 2026-06-19,
  кросс-сборка в `Dockerfile.windows_builder` с пропатченными либами.
- Артефакт рана `install-prefix/khandaq-x86_64-Release.zip`:
  - SHA256 `f3b62ffa8d5ac77d24d1517d1a7a1e8e0c4aa4c3cfb4917340e6495d89a4c1f6`
- Отгружено в релизе **v0.2.8** как `khandaq-windows-x86_64.zip` — **тот же SHA256** в трёх местах:
  - GitHub release: `f3b62ffa…`
  - зеркало khandaq.org/downloads: `f3b62ffa…`
  - `SHA256SUMS.txt`: `f3b62ffa…`
- **Вывод:** скачиваемый пользователями Windows-бинарь побайтово совпадает с выходом
  зелёного CI-рана — отгружена именно пропатченная сборка (проверено `gh run download`
  + `shasum -a 256`, 2026-06-20).

## Не закрыто полностью (известно)

- Подпись bootstrap JSON (Ed25519) — только host/key validation
- `PUSH_RELAY_AUTH_SECRET` — опционален до настройки на сервере + rebuild клиентов
- iOS `aps-environment`, NotificationService, Windows credentialstore — backlog

## Контакт для отчёта

При нахождении регрессии: номер пункта из таблицы + шаги + logcat/crash report + версия/build.
