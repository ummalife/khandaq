# Внутренний аудит Khandaq — 22.08.2026

Коммит `0a0d5559`. Восемь направлений, каждая находка отдельно проверялась на опровержение.

Найдено 43, вынесено на состязательную проверку 24, выжило **20**, опровергнуто 4.

---

# Аудит Khandaq-rc, коммит 0a0d5559 — итоговый отчёт

## Вывод

Проверено семь направлений (Android, iOS, протокол NGC, push-реле, цепочка поставки, веб/деплой, десктоп). Подтверждено 19 дефектов: 1 высокий, 14 средних, 4 низких; ещё 4 кандидата опровергнуты в состязательной проверке и в отчёт не включены. Один высокий дефект в релизном конвейере сайта сводит на нет всю схему подписи десктопных артефактов; остальное — обходы отдельных барьеров, ложноотрицательные гейты и расхождения инвентаря с фактически поставляемым кодом. Репозиторий не изменялся (`git status` чист).

Две ветви аудита независимо пришли к одному и тому же дефекту в `verify-desktop-signatures.py` — он приведён в отчёте один раз (M-04).

---

## HIGH

### H-01. Деплой подписывает релизным ключом байты, скачанные с боевого веб-сервера
**Файл:** `scripts/deploy-site.sh:170-189` (scp — строка 185), в паре с `scripts/sign-desktop-artifacts.sh:100-105`

Блок «Fetch already-published artifacts that are not staged locally» тянет по scp с боевого хоста любой файл `khandaq-*`, которого нет локально, и кладёт его в `web/downloads/`; сразу после этого (`deploy-site.sh:196`) вызывается подписывающий скрипт, который пропускает только файлы с уже сходящейся подписью, а несовпадение трактует как «файл изменился» и переподписывает (`ssh-keygen -Y sign`, строка 105). `.gitignore:74-78` игнорирует `*.exe/*.zip/*.tar.gz`, поэтому на чистом checkout источником байтов для Windows/macOS/Linux-артефактов является именно тот сервер, от компрометации которого подпись и должна защищать. Единственное событие, обязанное остановить всё — расхождение файла с закоммиченным `.sig`, — используется как триггер переподписи.

**Сценарий отказа.** Атакующий с записью в `/var/www/khandaq-site/downloads` (деплой сам делает `chown -R www-data`, строка 323) подменяет `khandaq-windows-installer.exe`. Следующий обычный деплой с чистого checkout: файла нет локально → scp с сервера → verify падает → троян подписывается релизным Ed25519-ключом и выкладывается. На **первом** деплое публикация самопротиворечива: пересчёт `SHA256SUMS.txt` идёт раньше скачивания (строки 133-159), поэтому суммы остаются от подлинного файла и `verify-desktop-signatures.py` это поймает. На **втором** деплое из того же рабочего дерева троян уже лежит локально, попадает в `shasum -a 256 khandaq-*`, суммы и `release-manifest.json` пересчитываются по нему, подпись уже сходится — все три утверждения о байтах сходятся, проверка зелёная.

**Правка.** (1) Не подписывать байты, пришедшие из канала распространения: подписывать только то, что лежит в `$ROOT/dist` после локальной сборки, либо помечать скачанное как непригодное к подписи. (2) В `sign-desktop-artifacts.sh` при наличии `.sig`, который НЕ сходится, завершаться с ошибкой (это инцидент, а не повод переподписать). (3) Недостающие артефакты брать из GitHub Releases с проверкой по закоммиченному `.sig` до публикации.

---

## MEDIUM

### M-01. Приватная NGC-группа: любой друг, знающий chat_id, получает приглашение автоматически
**Файл:** `khandaq-android-trifa/.../HelperGroup.java:8284` (`handle_group_invite_request`), приглашение на 8306

Обработчик lossless-пакета 184 принимает от любого принятого друга 32-байтовый chat_id, проверяет только членство (`tox_group_by_groupid__wrapper >= 0`) и rate-limit 60 с (8298-8305), после чего безусловно вызывает `tox_group_invite_friend`. Ни `privacy_state`, ни роли отправителя, ни подтверждения пользователем. В toxcore гейтов тоже нет (`tox.c:4488`, `group_chats.c:7848`, `8095` — только валидность друга). Приём приглашения у атакующего полностью автоматический (`MainActivity.java:9147`). При этом `GroupInfoActivity.java:973/993` отдают chat_id приватной группы в буфер обмена и в «поделиться» без всякой проверки privacy_state.

**Сценарий отказа.** Мэллори — принятый друг Алисы, но не участник приватной группы G. Получив утёкший chat_id (Алиса нажала «скопировать ID»), она вставляет его в «Присоединиться к публичной группе». `tox_group_join` локально создаёт объект группы, пиров 0 → `finish_public_group_join` рассылает пакет 184 всем онлайн-друзьям (ветка при peers<=1, `HelperGroup.java:9369` — уточнение: после join группа локально помечена GI_PUBLIC, `group_chats.c:7523`, поэтому срабатывает if-, а не else-ветка). Клиент Алисы молча приглашает Мэллори в G, и та читает всю дальнейшую переписку. Ограничения: история не утекает (`MainActivity.java:9241` гейтит sync на PUBLIC), вход оставляет системное сообщение (`HelperGroup.java:4202`), а приватная группа с паролем неуязвима (пароль опционален, `AddPrivateGroupActivity.java:135-141`).

**Правка.** В `handle_group_invite_request` перед приглашением читать `tox_group_get_privacy_state(group_num)`; для `TOX_GROUP_PRIVACY_STATE_PRIVATE` не приглашать автоматически — либо отбрасывать, либо ставить в очередь с явным подтверждением пользователя. В `GroupInfoActivity` предупреждать при копировании/шаринге chat_id приватной группы.

### M-02. Диалог согласия перед запуском полученного файла обходится регистром расширения
**Файл:** `khandaq-desktop/src/widget/tool/messageboxmanager.cpp:141`

`dangerousExtensions.contains(file.suffix())` — у `QStringList::contains` по умолчанию `Qt::CaseSensitive`, а `QFileInfo::suffix()` регистр не нормализует. Список только в нижнем регистре; в нём же опечатка `"src"` вместо `"scr"` и отсутствует `"cmd"`. Имя файла целиком выбирает отправитель: `corefile.cpp:356` чистит только `<>:"/\|?*`, `widget.cpp:128-135` собирает путь из `baseName() + '.' + completeSuffix()`. Несработавшая проверка означает не отсутствие предупреждения, а сразу запуск: строка 158 безусловно вызывает `QDesktopServices::openUrl` (на Windows — ShellExecuteW). Рядом, в том же клике, `imageviewerwidget.cpp:60` делает `suffix().toLower()` — то есть это несогласованность, а не стиль проекта.

**Сценарий отказа.** Контакт присылает `Schet_2026.PDF.EXE` (или `update.Cmd`, или `payload.scr`). Пользователь принимает передачу и жмёт «Открыть»: `suffix()` == `"EXE"`, `contains()` == false, диалог «Executable file… Are you sure» не показывается, файл запускается. Windows по умолчанию прячет известные расширения, а записанный Qt файл не получает Mark-of-the-Web — других предупреждений тоже нет. На Linux эффект нулевой (`chmod +x` находится внутри того же `if`), на macOS недостижим.

**Правка.** `dangerousExtensions.contains(file.suffix(), Qt::CaseInsensitive)` (список держать в нижнем регистре); исправить `"src"` → `"scr"`, добавить `"cmd"`, `"url"`, `"inf"`, `"msu"`; обрезать хвостовые точки/пробелы у suffix перед сравнением.

### M-03. iOS: «Автозагрузка вложений: Никогда» обходится именем файла `*.file.m4a`
**Файл:** `khandaq-ios/Antidote/AutomationCoordinator.swift:70`

«Голосовое сообщение» определяется исключительно по имени: `VoiceMessageHelper.isVoiceMessagePath` (строка 27) делает `path.lowercased().contains(".file.m4a")` — подстрока, не суффикс. При `isVoiceNote == true` весь `switch userDefaults.autodownloadImages` (74-85), включая ветку `.Never`, пропускается. Дефолт — `.Never`, и комментарий в `UserDefaultsManager.swift:185` объясняет почему. Проверки MIME/UTI/содержимого нет ни здесь, ни в `OCTSubmanagerFilesImpl.acceptFileTransfer:` (330-390); имя приходит сырым из пакета (`OCTTox.m` fileReceiveCallback). На Android тот же форс-приём стоит ПОСЛЕ глобального гейта (`HelperFiletransfer.java:169`), то есть iOS — единственная платформа, где политика обходится.

**Сценарий отказа.** Принятый контакт шлёт файл 19 МБ с именем `photo.file.m4a.jpg` и произвольным содержимым. У жертвы дефолт «Никогда» и сотовая сеть: `isVoiceNote` = true, проверка настройки пропущена, `acceptFileTransfer` вызывается автоматически, файл молча качается по сотовой и ложится в каталог загрузок профиля. Повтор расходует трафик и место. Потолок — 20 МБ на файл, файл остаётся в песочнице (`UIFileSharingEnabled` не выставлен), в Фотоплёнку не попадает.

**Правка.** Требовать для авто-приёма голосового: расширение ровно `m4a` (а не подстроку), размер в пределах голосовой заметки (сотни КБ), и сохранить действие ветки `.UsingWiFi` для голосовых. После загрузки валидировать контейнер через AVAsset и удалять, если это не аудио.

### M-04. `verify-desktop-signatures.py` берёт корень доверия с того же сервера, который проверяет
**Файл:** `scripts/verify-desktop-signatures.py:81` (использование — 144-147 и self-test 160-172)

Докстринг скрипта и шапка `sign-desktop-artifacts.sh:84-86` заявляют кросс-канальность: «ключ опубликован в git, это другой канал, чем веб-сервер». Код делает обратное: `allowed_signers` скачивается по `{base}/downloads/allowed_signers` и именно он передаётся в `ssh-keygen -Y verify -f`. `SHA256SUMS.txt` и `release-manifest.json` берутся оттуда же. Закоммиченная копия `web/downloads/allowed_signers` этим скриптом не читается (её использует только подписывающий скрипт). `verify-site-deploy.py:323-327` проверяет только HTTP-код 200 на этот файл.

**Сценарий отказа.** Воспроизведено: собран «скомпрометированный сайт», подменённый артефакт подписан посторонним ed25519-ключом с `-n khandaq-release`, публичная половина вписана в `allowed_signers`, пересчитаны `SHA256SUMS.txt` и манифест. Прогон `verify-desktop-signatures.py --base file://…` печатает «ок: совпадает с SHA256SUMS.txt», «ок: совпадает с release-manifest.json», «ок: Good "khandaq-release" signature», проходит встроенный тест на переворот бита (он сверяется тем же подменённым ключом) и завершается «ВСЁ ЧИСТО», код возврата 0. Пользовательский путь при этом защищён: `web/index.html:207-209` предписывает брать ключ с raw.githubusercontent.com. Ущерб — молчание единственной пострелизной проверки ровно в том сценарии, ради которого она написана (в связке с H-01 — молчание перед тем, как подмена получит легитимную подпись).

**Правка.** Верифицировать против `ROOT/web/downloads/allowed_signers`, а скачанную копию использовать только для побайтового сравнения, падая при расхождении. Ту же сверку (allowed_signers и `KHANDAQ-RELEASE-SIGNING.pub`) добавить в `verify-site-deploy.py` вместо проверки кода 200.

### M-05. Релизная сборка Android берёт `libjni-c-toxcore.so` из «последнего успешного» прогона другого workflow
**Файл:** `.github/workflows/release-provenance.yml:82-83` (загрузка — 91, run-id в summary — 99)

Шаг выбирает источник самой большой бинарной части APK так: `gh run list --workflow=android-native-so.yml --status=success --limit 1`. Нет ни `--branch`, ни сверки headSha с релизным коммитом, ни пина sha256. Единственная последующая проверка — `scripts/check-jni-symbols.py:134` — смотрит только наличие трёх экспортируемых имён `Java_com_zoffcc_..._khandaq_*`, то есть пропустит любую библиотеку. Run-id уходит только в `$GITHUB_STEP_SUMMARY`, а не в `TOOLCHAIN.txt`, который является subject аттестации; `SHA256SUMS` покрывают только `*.apk` и `TOOLCHAIN.txt`.

**Сценарий отказа.** Не гипотетический: последний успешный прогон `android-native-so` — 32540755348 с ветки `release/0.2.40` (sha 06d1a2d6), а лог релизного прогона 32595153201 (тег v0.2.42, sha 0a0d5559) прямо пишет «taking the native library from android-native-so run 32540755348». Подписанный и аттестованный APK собран с .so, произведённой на другом ref. Достижимый вред без атакующего: владелец диспатчит `android-native-so.yml` с экспериментальной ветки (`workflow_dispatch`), потом режет тег — релиз молча забирает WIP-библиотеку, и по аттестованному коммиту тот же APK не восстанавливается. (Эскалации привилегий здесь нет: write-доступ в репозитории есть только у владельца, ruleset требует 0 аппрувов.)

**Правка.** Выбирать прогон по тому же коммиту: `gh run list --branch "$GITHUB_REF_NAME" --json databaseId,headSha` со строгой сверкой headSha с `github.sha`, либо передавать run_id явным input и требовать подтверждения. Пинить sha256 каждой ABI-слайс .so в репозитории и писать run-id + хэши в `TOOLCHAIN.txt`.

### M-06. Гейт «реле должно стартовать чисто» ищет `[ERROR]`, а реле логирует ERROR без скобок
**Файл:** `scripts/deploy-push-relay.sh:136`

`errs=$(docker compose logs ... | grep -F '[ERROR]')` ищет литерал в скобках, тогда как формат логгера реле — `"%(asctime)s %(levelname)s %(message)s"` (`app.py:21`), без скобок. Скобочный формат есть только у gunicorn, чей инцидент этот гейт и породил. Все стоп-условия, перечисленные в комментарии (строки 130-133), печатаются через `log.error(...)` приложения (`app.py:640, 662, 688, 699, 710`) и под фильтр не попадают. Проверено запуском реального `app.py` под закреплённым gunicorn 26.1.0 (`requirements.txt:55`): `basicConfig` не подавляется, root-логгер gunicorn не перенастраивает (dictConfig только при заданном logconfig*), обе ERROR-строки вышли без скобок, `grep -F '[ERROR]'` вернул exit=1.

**Сценарий отказа.** Повтор инцидента K-05: `/opt/khandaq-push/secrets/firebase-sa.json` с правами 0600 root:root при контейнере под uid 10001. Реле пишет `... ERROR fcm: ... is NOT READABLE by uid 10001 — every send will fail while the relay otherwise looks healthy`, гейт печатает «no errors in the startup log», проверка `auth_mode` (она про режим) проходит, `/health` отдаёт `{"status":"ok"}` — деплой объявлен успешным поверх полного отказа доставки пушей. В прогоне `/health/detail` при этом содержал `"fcm_service_account":"unreadable"`, `"fcm_configured":false`, `"enforce_overdue":true` — их скрипт не смотрит. То же для `PUSH_AUTH_BREAK_GLASS` (прод в soft) и `enforce_overdue`.

**Правка.** Привести формат логгера к скобочному (`app.py:21`) либо заменить фильтр на `grep -E '\[ERROR\]|[[:space:]]ERROR[[:space:]]'`. Добавить в гейт явную проверку `/health/detail → fcm_service_account == "ok"` и `enforce_overdue == false`, не зависящую от формата логов, и один раз уронить проверку намеренно.

### M-07. Счётчики capability пишутся в таблицу auth-adoption и вдвое занижают `window_signed_pct`
**Файл:** `infra/push/relay/app.py:1374-1383` (безусловный `_record_auth_outcome("cap_ok")` — 1383)

Исходы проверки capability пишутся тем же `_record_auth_outcome`, что и HMAC-аутентификация, в ту же таблицу `authoutcome` (единственную, создаётся на 291). `_auth_adoption_summary` читает её без фильтра (584-586) и берёт знаменатель как `w_total = sum(window.values())` (601-602), а `today_unsigned` — как «всё, что не ok» (606). Асимметрия односторонняя и доказывает непреднамеренность: сводка capability свои строки фильтрует явно (`WHERE ... outcome LIKE 'cap_%'`, строка 1264), обратного фильтра нет. `today_outcomes`/`window_outcomes` перечисляют только ключи `AUTH_OUTCOMES` (521), поэтому отчёт внутренне противоречив: сумма бакетов не сходится с signed+unsigned.

**Сценарий отказа.** После раскатки 0.2.42 / iOS 142986 клиенты и подписывают, и регистрируют capability (`KhandaqPush.swift:252,311`, `OCTSubmanagerChatsImpl.m:428`, `KhandaqPush.java:117`), поэтому каждый подписанный wake даёт две строки — `ok` и `cap_ok`. Потолок `window_signed_pct` — 50 % при нулевых бакетах `missing/badmac/stale`. Критерий включения enforce из `PUSH-AUTH-SECRET-PROVISIONING.md:94` («при 100 % и badmac=0») становится математически недостижимым, то есть процедура закрытия KQ-01/KQ-02 не завершается. То же число попадает в релизное свидетельство (`scripts/record-push-release-evidence.py:94`).

**Правка.** Отделить пространства имён: колонка `kind` или отдельная таблица для `cap_*`. Минимально — фильтровать знаменатель по `AUTH_OUTCOMES` перед вычислением `w_total`/`today_unsigned`. Тест: N подписанных wake с валидной capability → `window_signed_pct == 100.0`.

### M-08. iOS: у приёма истории (0x02/0x03) нет ни строкового, ни байтового бюджета
**Файл:** `khandaq-ios/local_pod_repo/objcTox/.../OCTNgcGroupHistSync.m:752` (текст), `:844`/`:857` (файл)

После проверок времени и анти-даунгрейда вызывается `insertSyncedMessageBlock` без счётчика принятых строк/байт; в файловой ветке — `writeToFile` (до ~36 КиБ на пакет) и `insertSyncedFileBlock`. Ограничен только размер одного пакета (`kOCTNgcHistMaxFilePayload = 36701`). Дедупликация контентная и обходится: текстовый ключ включает сам текст, файловый — `msgIdHashHex` из пакета плюс (имя, размер). Анти-даунгрейд не спасает: для необъявленного pubkey `OCTNgcHistoryDowngradeDecide` возвращает `AcceptLegacy`. Ретеншена/обрезки в Realm нет (`addObject` в `OCTRealmManager.m:1644/1702`, `removeMessages*` только по действию пользователя). На Android тот же путь ограничен `NgcHistorySyncBudget` (5000 строк / 8 МиБ на группу за запуск: `HelperGroup.java:5078-5097, 5213, 11101-11119, 11232`). `SECURITY-REVIEW2-RESPONSE.md:118` объявляет лимиты сделанными без оговорки о платформе; `OCTNgcSignedHistory.m:34` сам пишет «bounded on Android by NgcHistorySyncBudget».

**Сценарий отказа.** Сомайнер публичной NGC-группы шлёт private custom packet 0x02 с валидным заголовком, свежим timestamp, уникальным message_id и телом ~36 КБ, повторяя с новым текстом; каждый пакет — новая строка `OCTMessageAbstract` в Realm, верхней границы нет. Пакетом 0x03 с варьируемым именем/размером пишется по файлу на пакет. Уведомлений при этом нет (`NotificationCoordinator.swift:261/293` глушит `groupHistorySync`), то есть рост тихий. Скорость ограничена фрагментацией NGC (1372 байта на чанк) и тем, что на iOS tox крутится только на переднем плане, — исчерпание диска требует часов, немедленный эффект — мусор в чате и файлы-сироты.

**Правка.** Портировать `NgcHistorySyncBudget` в objcTox как per-group состояние на `OCTNgcGroupHistSync` (rows, bytes-per-session), сеять счётчик строк из Realm по `chatUniqueIdentifier AND groupHistorySync == YES`, проверять перед `insertSyncedMessageBlock`/`writeToFile` и списывать после фактической записи — как `HelperGroup.java:5097/5213` и `11119/11232`, с теми же константами.

### M-09. iOS: приём HSK-объявления пишет в Realm на каждый пакет (на Android этот путь дросселирован)
**Файл:** `khandaq-ios/local_pod_repo/objcTox/.../OCTNgcHskDirectory.m:52-54`, запись — `OCTNgcHskAnnounce.m:282-287`

При совпадении уже известного ключа `decideWithExisting:` безусловно возвращает `Refresh`, и приёмник вызывает `setValueBlock` → `-[OCTRealmManager setNgcValue:forKey:]` (`OCTRealmManager.m:176`): `dispatch_sync` на очередь Realm, полная write-транзакция и read-back для верификации. Пакет объявления — ровно 112 байт, шлётся broadcast'ом и может повторяться. В перечислении `OCTNgcHskDecision` состояния `UpToDate` нет вовсе, тогда как на Android ровно эта проблема закрыта `UP_TO_DATE` с `REFRESH_MIN_INTERVAL_MS = 5 мин` (`NgcHskDirectory.java:40, 116-121`, коммит a72f9daf — тронуты только три java-файла, парного iOS-изменения нет). В toxcore лимита на group custom packets нет (rate limit только для sync-request, `group_chats.c:1963`).

**Сценарий отказа.** Участник публичной группы шлёт корректное объявление 200 раз в секунду. У каждого iOS-участника это 200 write-транзакций Realm в секунду с read-back. Уточнение к масштабу: колбэк идёт не на потоке toxcore — `OCTTox.m:4519-4537` делает `dispatch_async` на главную очередь, поэтому блокируется main queue (фриз UI, рост очереди отложенных блоков), а не tox-цикл. Плюс износ флеша. Безопасность не затронута: bump `lastSeenMs` лишь усложняет подмену ключа.

**Правка.** Добавить `OCTNgcHskDecisionUpToDate` и `+refreshMinIntervalMs` (5 мин, как на Android): при совпадении ключа возвращать `UpToDate`, пока `nowMs - existing.lastSeenMs < refreshMinIntervalMs` (с тем же guard на обратный ход часов); в `OCTNgcHskAnnounce.m` добавить пустую ветку. Запас до `replaceGraceMs` = 24 ч — три порядка, анти-подстановочное правило не затрагивается.

### M-10. Анти-даунгрейд для файловой истории (0x03) не детектирует даунгрейд, а запрещает её
**Файл:** `khandaq-android-trifa/.../HelperGroup.java:10920-10935`; симметрично `OCTNgcGroupHistSync.m:794-801`

`handle_incoming_sync_group_file` вызывает `NgcHistoryDowngradePolicy.decide(file_author_hsk, false, now)` с жёстко зашитым `verdictMatchesThisRow = false` — и это факт, а не упрощение: подписанной формы файловой записи не существует (`NgcHistSigParser.java:27-28` знает только `PKT_HSK_ANNOUNCE=0x50` и `PKT_SIGNED_TEXT=0x02`). Значит для любого автора со свежим HSK в директории (<24 ч, `KEY_STALE_MS = REPLACE_GRACE_MS`) `decide` всегда возвращает `REJECT_DOWNGRADE`, и файл выбрасывается. Эмиссия объявлений в этих сборках уже живая (`HelperGroup.java:3712, 7601, 9075`; `OCTSubmanagerGroupsImpl.m:2344/2425/5235`), то есть директория наполняется за минуты. Нейтральный исход, написанный ровно для этой ситуации (`TRIFA_SYNC_TYPE_NGC_PEERS_UNATTRIBUTED`, строки 10936-10941), недостижим — REJECT срабатывает раньше и даже до вычисления `file_syncer_is_author`.

**Сценарий отказа.** Алиса и Боб в публичной группе на 0.2.42 / iOS 142986, оба обменялись HSK-объявлениями. Боб офлайн; Алиса публикует фото (изображения ужимаются под `TOX_MAX_NGC_FILESIZE = 36701`, `HelperGroup.java:7199-7363`, поэтому идут через историю 0x03). Боб возвращается, запрашивает историю, получает 0x03 с author=pubkey Алисы, находит её свежий HSK → `REJECT_DOWNGRADE`. Фото не появится никогда: переотдача идёт тем же путём, `if (gm.was_synced) continue` (10163) запрещает вторые руки, chunked-resend требует уже существующей строки. В логе одна строка, в UI ничего. Границы: механизм и так best-effort — окно отдачи 130 минут, файлы >36701 байт через 0x03 не синхронизировались никогда, живая доставка не затронута; при гонке (анонс ещё не обработан) файл проходит по `ACCEPT_LEGACY`.

**Правка.** Для 0x03 не применять `REJECT_DOWNGRADE`, а сводить к существующей нейтральной ветке: сохранять строку как `NGC_PEERS_UNATTRIBUTED`, когда синкер не является заявленным автором; REJECT оставить только если синкер заявляет чужое авторство при свежем ключе — либо ввести отдельный вердикт `REJECT_ONLY_IF_SIGNABLE`. На iOS симметрично использовать `OCTNgcHistoryDowngradeRendersAsClaimedAuthor`. Корневое решение — подписанная форма файловой записи (version 0x02, pktid 0x03), как предполагает §4.3 дизайна.

### M-11. `Core::sendGroupFileFromPath` вызывает toxcore из GUI-потока без `coreLoopLock`
**Файл:** `khandaq-desktop/src/core/core.cpp:2299` (пакеты — 2341 и 2369), вызов — `groupchatform.cpp:225`

Все публичные методы `Core`, трогающие `Tox*`, начинаются с `QMutexLocker ml{&coreLoopLock};`; `Core::process()` (`core.cpp:379-383`) держит тот же мьютекс на время `tox_iterate()`. `sendGroupFileFromPath` — единственное исключение: публичный (`core.h:145`), лок не берёт и в цикле вызывает `tox_group_send_custom_packet()`. Перебраны все `Core::*` с `tox_` без лока и без `ASSERT_CORE_THREAD` — остальные достижимы только из колбэков toxcore или из методов, уже взявших лок. Внутренней синхронизации в toxcore нет: `tox.c:899-912` создаёт мьютекс только при `experimental_thread_safety`, а этот флаг не выставляется нигде (`toxoptions.cpp:86-157`), то есть `tox_lock()` — no-op. Core живёт в отдельном потоке (`core.cpp:165, 285, 331`), таймер `process()` крутится непрерывно.

**Сценарий отказа.** Пользователь перетаскивает в чат NGC-группы файл 5 МБ (> `NGC_SINGLE_PKT_MAX_FILESIZE = 36701`, `khandaqlimits.h:10`) — `groupchatform.cpp:225` в GUI-потоке вызывает `sendGroupFileFromPath`, тот шлёт BEGIN и ~140 чанков подряд без лока, пока Core-поток каждые 20-50 мс входит в `tox_iterate()` под локом. Два потока одновременно мутируют одни структуры: порча кучи/падение либо тихо испорченные исходящие пакеты. Для 200 МБ — 5400 конкурентных вызовов. Триггер локальный (данные с диска, а не из сети), поэтому эксплуатируемость извне отсутствует.

**Правка.** Взять `QMutexLocker ml{&coreLoopLock};` первой строкой метода — рекурсии бояться не нужно, `coreLoopLock` объявлен как `CompatibleRecursiveMutex` (`core.h:311`), так что вложенные вызовы из `sendGroupFile` не заблокируются. Отдельно вынести чтение файла и рассылку чанков из GUI-потока.

### M-12. App Group `group.org.khandaq.messenger` не объявлен ни в одном entitlements — расширение «Поделиться» не работает
**Файл:** `khandaq-ios/shareextension/shareextension.entitlements` (пустой `<dict/>`), `Antidote/Antidote.entitlements`

`ShareInboxStorage.containerURL()` (`ShareInboxStorage.swift:141-143`) целиком построен на `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)`; ключа `com.apple.security.application-groups` нет ни в одном entitlements, а `grep` по всему монорепо даёт единственное вхождение идентификатора — `ShareInboxStorage.swift:26` (то есть нет ни инъекции скриптом, ни xcconfig, ни `post_install`). В `project.pbxproj` App Groups не включён и в SystemCapabilities. Сильнее: сам provisioning profile capability не содержит (`security cms -D` по `embedded.mobileprovision` → только aps-environment, application-identifier, keychain-access-groups, get-task-allow, team-identifier), и в App Store-экспорте (`build/export/DistributionSummary.plist` для `shareextension.appex`) её тоже нет — просто дописать ключ нельзя, подпись упадёт.

**Сценарий отказа.** Пользователь на реальном устройстве жмёт «Поделиться» → Khandaq: `ShareViewController.finalizeShare` (166-170) → `savePendingShare` → `manifestURL()` == nil → false → `finishWithFailure()` → системный алерт «Could not share to Khandaq». Симметрично `ActiveSessionCoordinator.processPendingShareIfNeeded()` (478-483) всегда получает nil, deep link `khandaq://share` мёртв. Прогноз (не проверено запуском): на симуляторе API отдаёт путь независимо от entitlements — этим объясняется, как дефект доехал до TestFlight.

**Правка.** Включить App Group в обоих App ID в портале Apple и перевыпустить профили, затем добавить `com.apple.security.application-groups = [group.org.khandaq.messenger]` в `Antidote.entitlements` и `shareextension.entitlements`. В `savePendingShare`/`storeFile*` различать «нет контейнера» и «ошибка записи», чтобы такая поломка падала громко, а не деградировала в тихий `finishWithFailure`.

### M-13. SBOM не описывает нативный стек Android APK и подменяет его десктопными версиями
**Файл:** `scripts/generate-sbom.py:141-157` (`desktop_components()`, источник — `DESKTOP` на строке 50)

SBOM объявлен как «one CycloneDX SBOM for the whole product», `metadata.component` = приложение `khandaq`, но группа `generic` берётся исключительно из `khandaq-desktop/buildscripts/bundled-deps.json`, а поле `platforms` при этом отбрасывается — purl получается плоский. APK же несёт `app/nativelibs/*/libjni-c-toxcore.so` (`app/build.gradle:68`), собранную `circle_scripts/deps.sh` с другими версиями: libvpx v1.8.0 (deps.sh:33), ffmpeg n6.0 (18), x264 31e19f92, opus v1.3.1, libsodium 1.0.18. Проверено по байтам: в `nativelibs/arm64-v8a/libjni-c-toxcore.so` строки `WebM Project VP8 Decoder v1.8.0`. Ни один из этих компонентов в SBOM не попадает (maven=338, gem=134, cocoapods=26, generic=23, pypi=19), `check-bundled-deps-eol.py` читает только десктопный инвентарь (`INVENTORY`, строка 41), а `check-vulnerable-deps.py:157-160` намеренно исключает `pkg:generic/` из запроса в OSV.

**Сценарий отказа.** Выходит advisory на libvpx ветки 1.8.x. Еженедельный `dependencies-sbom` зелёный: в SBOM для vpx стоит 1.14.1, и generic-purl всё равно не сканируется. Читатель SBOM (сегодня — внутренний, следующий аудитор; релизу Android SBOM не прикладывается) делает вывод, что 1.8.0 нигде не поставляется, тогда как каждый APK содержит её внутри `libjni-c-toxcore.so`, декодирующей видео из недоверенных NGC-пакетов. Смягчение: конкретно по libvpx разбор был сделан вручную и зафиксирован в дереве (deps.sh:20-33 про CVE-2024-5197 и CVE-2023-5217, патч разведён по 4 ABI-сайтам, `patches/khandaq-libvpx-cve-2024-5197.patch`) — то есть отсутствует именно автоматика на будущее.

**Правка.** Добавить в `generate-sbom.py` источник для нативного стека мобильных клиентов (разбор `_*_VERSION_`/`_*_COMMIT_` из `circle_scripts/deps.sh` и `build-ios-native-deps.sh`), выдавать компоненты с квалификатором платформы и переносить `platforms` из `bundled-deps.json` в properties. Расширить `check-bundled-deps-eol.py` на этот инвентарь либо снять исключение `pkg:generic/` из OSV-скана.

### M-14. `bundled-deps.json` объявляет macOS, но описывает только Windows-кросс-сборку; Linux не описан вовсе
**Файл:** `khandaq-desktop/buildscripts/bundled-deps.json:147` (и все записи с `platforms: ["windows","macos"]`)

Инвентарь заявляет для openssl, ffmpeg, vpx, sodium, sqlcipher, opus, qrencode, libexif, openal, x264 платформу macOS и версии из `buildscripts/download/download_*.sh`. Реальная сборка macOS этих скриптов не запускает: `scripts/build-macos.sh:44` делает `brew bundle --file osx/Brewfile` (версии не фиксирует), а из пришпиленных скриптов гоняет только toxcore/toxext. `.ci-scripts/build-osx-deps.sh` вызывается только из `khandaq-desktop/.github/workflows/build-test-deploy.yaml:518` — этот workflow лежит не в корне монорепо и GitHub его не запускает. Linux собирается из apt Ubuntu 24.04 (`Dockerfile.khandaq_ubuntu2404`), а `bundle-linux-portable.sh` копирует эти `.so` в тарбол — в инвентаре нет ни одной linux-записи рантайма.

**Сценарий отказа.** Проверено по фактически отгружаемым артефактам, которые лежат в репозитории (`web/downloads/khandaq-macos.zip`, 41.9 МБ, с `.sig` и строкой в `SHA256SUMS.txt`; `khandaq-linux-x86_64-portable.tar.gz`, 121 МБ). Версии в бинарях: macOS — Qt 5.15.19, OpenSSL 3.6.2 и 4.0.0, libvpx 1.16.0, sqlcipher 4.16.0, ffmpeg 8.x, libsodium 1.0.20; Linux — Qt 5.15.13, libssl.so.3, libvpx.so.9, libavcodec.so.60. Инвентарь при этом утверждает Qt 5.12.12 и OpenSSL 1.1.1w с waiver до 2026-11-19. Запуск `check-bundled-deps-eol.py --release` даёт EXIT=0 — гейт зелёный ровно для версий, которых в артефактах нет. При advisory на любую brew/apt-библиотеку ни один гейт не покраснеет; отчёт о составе релиза ложен в обе стороны. Прямой эксплуатируемости нет: фактически отгружаются более свежие библиотеки, чем waived.

**Правка.** Либо перевести macOS-сборку на пришпиленные `download_*/build_*` (тогда `platforms: ["macos"]` станет правдой), либо сузить `platforms` до `["windows"]` и завести отдельные источники истины: `Brewfile.lock.json` для macOS и версии apt-пакетов образа для Linux, с генерацией SBOM из фактического артефакта (`otool -L` для .app, `dpkg-query`/`ldd` для тарбола).

---

## LOW

### L-01. `PUSH_CAP_ENFORCE=always` не отказывает устройствам без зарегистрированной capability
**Файл:** `infra/push/relay/app.py:1376`

`_cap_required` для режима `always` возвращает True («every wake needs one», 1173-1176), но вызывающий код короткозамыкает раньше: `if cap_state != "none" and cap_state != "ok" and _cap_required(cap_state)`. Для токена без capability `_cap_state` возвращает `"none"` (1141-1143), и ветка `always` недостижима. Сообщение SystemExit при старте (`app.py:169`) описывает режим как «refuse every wake without one» — конфигурация обещает то, чего не делает. Воспроизведено: `relay(enforce="1", cap_grace="0", cap_enforce="always")`, три подписанных wake на незарегистрированные токены (без cap, с пустым cap, с чужим cap) — все три 200 и все три дошли до `_send_wake`. Тестов с `cap_enforce="always"` в `test_app.py` нет ни одного.

Почему low: прод стоит в `auto` (`docker-compose.yml:58`, `docs/PUSH-COMPATIBILITY.md`), `always` нигде не выставлен и ни одним ранбуком как аварийный рычаг не предписан; при нынешнем soft-режиме неподписанные wake и так обслуживаются (это учтённый KQ-01/02), так что экспозиция не меняется. Режим не полностью инертен — он реально ужесточает `missing_grace/bad_grace` и `store_error`.

**Побочно, там же:** для `store_error/missing_grace/bad_grace` в режиме `always` `_record_auth_outcome` вызывается дважды (1375 и 1377), из-за чего `capabilities.grace_still_used` на `/health/detail` завышается вдвое.

**Правка.** `if cap_state != "ok" and _cap_required(cap_state):`, а решение по `"none"` принимать внутри `_cap_required` (в `auto` — False, в `always` — True). Убрать двойную запись исхода и добавить тест на `always` + незарегистрированный токен.

### L-02. `--rollback` возвращает только контент сайта; CSP-хеши в nginx остаются от новой версии
**Файл:** `scripts/deploy-site.sh:34-43` (ветка отката) в паре с областью снимка `208-219`

Снимок делается только с `$REMOTE_SITE_DIR` (`cp -al`, строка 212), откат восстанавливает только его и сразу уходит в verify. Ни `/etc/nginx/snippets/khandaq-security-headers.conf` (заливается на 263), ни `sites-enabled/khandaq.org` не откатываются и не сохраняются: единственная резервная копия nginx-конфига делается внутри `if [[ -d /var/www/element ]]` (199-204), то есть привязана к легаси-каталогу, к сайту отношения не имеющему. CSP хеш-based, поэтому пара «страницы + сниппет» согласована только целиком.

**Сценарий отказа.** Правится инлайновый `<script>` в `web/index.html` (например, `var release` на строке 363 — сверено: хеш меняется с `sha256-G2qaNc5N…` на `sha256-pvLtwjHB…`). Деплой уносит новый сниппет; релиз оказывается плохим, запускается `--rollback`. Возвращается старый `index.html` при CSP с новым хешем → браузер блокирует скрипт, который проставляет href у четырёх кнопок скачивания и ссылки на SHA256SUMS. Собственная проверка отката падает громко (`verify-site-deploy.py:262-263`, «браузер заблокирует — `<script>` на строке 361»), rollback возвращает ненулевой код. Ущерб ограничен: `index.html:164-165` и `/downloads/` статически ведут на GitHub Releases и живой листинг, ослабления безопасности нет (политика становится строже), лечение — редеплой старого коммита. На существующей истории сценарий не воспроизводится ни одной парой коммитов, станет достижим на первом релизе десктопа, меняющем `var release`.

**Правка.** Класть в снимок nginx-часть: копировать сниппет и site-конфиг в `$SNAP_ROOT/$STAMP/_nginx/` безусловно (вынести `cp` из-под проверки на `/var/www/element`), в `--rollback` восстанавливать их вместе с контентом и делать `nginx -t && systemctl reload nginx` перед внешней проверкой.

### L-03. Кнопка «Поделиться диагностикой сети» не работает никогда: FileProvider с несуществующим authority
**Файл:** `khandaq-android-trifa/.../NetworkDiagnosticsLog.java:91-92`

`FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", out)`; authority `com.khandaq.messenger.fileprovider` не объявлен — в манифесте только `.std_fileprovider`, `.ext2_provider`, `.ext1_fileprovider` (`AndroidManifest.xml:583, 593, 603). Сверено по собранным merged-манифестам релиза: 6 authority, нужного среди них нет; `applicationId` без суффикса (`build.gradle:288`), значит и в debug то же. `getUriForFile` бросает `IllegalArgumentException`, локальный `catch (Exception)` (строка 100) её глотает, метод возвращает null, а `NetworkDiagnosticsActivity.java:37-43` при null не делает ничего — ветки else нет. Вторая независимая причина: файл пишется в `getCacheDir()/network_diag`, а в `res/xml/stdfilepaths.xml` объявлены только `external-files-path` (`/vfs_export/`, `/tmpdir/`) — даже с верным authority вызов упал бы с «Failed to find configured root».

**Сценарий отказа.** Пользователь по просьбе поддержки открывает Настройки → Диагностика сети (путь достижим: `pref_general.xml:342` → `SettingsActivity.java:254`) и жмёт «Поделиться». Ничего не происходит — ни чузера, ни тоста, ни сообщения об ошибке. Отказ стопроцентный. Ущерб ограничен тем, что содержимое диагностики уже на экране и выделяется (`textIsSelectable="true"`), плюс пишется в logcat.

**Правка.** Использовать `KhandaqProviders.STD_FILE_PROVIDER` вместо конкатенации, а файл писать в `MainActivity.SD_CARD_TMP_DIR` (покрыт `<external-files-path path="/tmpdir/">`), либо добавить `<cache-path path="network_diag/" name="netdiag"/>` в `stdfilepaths.xml`. Показывать тост, когда `createShareIntent` вернул null.

### L-04. Сборка Android из чистого клона падает: `google-services.json.example` содержит старый package_name
**Файл:** `khandaq-android-trifa/android-refimpl-app/app/google-services.json.example:12`

`app/build.gradle:25-31` копирует `.example` в `google-services.json`, если файла нет; `**/google-services.json` в `.gitignore:138` и гитом не отслеживается, то есть в чистом клоне фолбэк срабатывает всегда. Единственный client-блок в примере содержит `"package_name": "org.khandaq.messenger"`, тогда как `applicationId` = `com.khandaq.messenger` (`build.gradle:288`, коммит 90d91731 поправил боевой файл, но не пример). Плагин `com.google.gms:google-services:4.4.2` сравнивает именно `applicationId` (проверено по разобранному jar: `GoogleServicesTask.getApplicationId().set(GeneratesApk.getApplicationId())`), фолбэка на namespace нет и падает с `No matching client found for package name 'com.khandaq.messenger'`.

**Сценарий отказа.** `git clone` → `bash scripts/build-android-trifa.sh` (README.md:79 и docs/BUILDING.md:40 называют его единственным путём, и он идёт прямо в `./gradlew assembleRelease`) → таск `processReleaseGoogleServices` падает, сообщение уводит в сторону Firebase, а не устаревшего примера. CI не задет: все три воркфлоу подставляют правильный `com.khandaq.messenger` через `scripts/ci-google-services.sh`, так что релизная и провенанс-сборка зелёные. Ущерб — сломанный документированный локальный путь и невозможность независимой проверки воспроизводимости «из коробки».

**Правка.** Поменять `package_name` в примере на `com.khandaq.messenger`. Надёжнее — убрать gradle-фолбэк и вызывать `scripts/ci-google-services.sh` из `scripts/build-android-trifa.sh`, чтобы заглушку во всех путях писал один источник.

---

## Опровергнуто

4 кандидата не выдержали проверки и в отчёт не вошли: HMAC-«оракул подписи» на легаси-URL реле (секрет по построению публичен — лежит открытым текстом в Info.plist собранного 142986 и в BuildConfig, так что оракул не даёт информации); «CI-APK уходит с пустым HMAC-ключом» (в собранном 0.2.42 AAB секрет присутствует, а CI-артефакт вообще не публикуется — Android раздаётся только через Play); «Linux-сборки везут уязвимый OpenSSL 3.0.13» (баннер апстрима, реальный пакет — 3.0.13-0ubuntu3.9 от 07.04.2026, все названные CVE закрыты, и TLS-путь в клиенте отсутствует); «Firebase API key в истории не отозван» (тот же ключ извлекается из любого APK из Play, ротация ничего не меняет и сломает установленные версии).

---

## Что осталось непроверенным и почему

**Ничего не собиралось и не запускалось из клиентов.** Gradle-сборка Android не выполнялась (в том числе из-за L-04), Xcode-таргеты и `AntidoteTests`/UI-тесты не гонялись, Qt/ctest в окружении нет — все выводы по клиентам сделаны чтением кода плюс разбором уже собранных артефактов, лежащих в дереве (merged-манифест релиза, `build/export/Khandaq.ipa` и `DistributionSummary.plist`, `nativelibs/*.so`, `khandaq-macos.zip`, linux-тарбол, `.deb`, APK 0.2.20 из загрузок). Динамической проверки на устройстве/эмуляторе не было; прогноз про поведение App Group на симуляторе (M-12) не подтверждён запуском.

**Реле проверено локально, но не в бою.** Штатный набор тестов прогнан целиком (119 passed, 1 skipped), поверх написаны три собственные пробы в скретчпаде — они и подтвердили L-01, M-07, M-06. Реального прогона против живого реле и FCM не было (send везде застабан), поведение под настоящим `gunicorn -w 2` с конкурентной записью в SQLite оценено по коду, а не измерено.

**Веб-хост недоступен.** `verify-site-deploy.py` и `verify-desktop-signatures.py` против `https://khandaq.org` не запускались, поэтому фактические заголовки, TLS-конфигурация (её в репозитории нет вовсе — живёт только на хосте), сроки сертификатов и содержимое `/var/www/khandaq-site` не проверены. H-01 и M-04 воспроизведены на локальных фикстурах, а не на проде.

**Нативный и медийный код почти не покрыт.** `jni-c-toxcore.c` просмотрен только в части колбэков lossless-пакетов; toxcore/webrtc/vpx как таковые не аудировались. Аудио/видео-стек (CallingActivity, ConfGroupAudioService, `OCTSubmanagerCallsImpl`, `OCTAudioEngine/OCTVideoEngine`, CallKit/PushKit, NGC live 0x21/0x31) — вне охвата. Чанкованная передача файлов (`NgcGroupFileTransfer`, ~2300 строк + 58 КБ ObjC) проверена только по границам заголовков. На десктопе не смотрел `src/video`, `src/platform`, `src/persistence/db` (SQLCipher-схемы и апгрейды), chatlog-рендеринг.

**Настройки вне дерева.** Фактические Workflow permissions по умолчанию, полный состав ruleset, состояние Firebase-консоли (ограничения ключа, включённые API) и реальные права `/opt/khandaq-push/secrets/firebase-sa.json` на push-хосте не проверялись — они не в репозитории.

**Сознательно не сообщены как находки (недостаточно доказательств ущерба):** `exiftransform.cpp:47` читает `exif_get_short(exifEntry->data)` без проверки `size >= 2` — libexif не вендорится, поведение подтвердить не удалось; `ipc.cpp:138-165` допускает name/data ровно по 16/128 байт без NUL (чтение за полем, но в пределах той же структуры shared memory и только для локального пользователя); обход 20-секундного кулдауна истории циклом leave/join (перезаход минтит новый групповой pubkey) — стоимость атаки сопоставима с выигрышем; `announceForNewPeer` с force=YES даёт квадратичную амплификацию broadcast'ов по числу участников, но пакет 112 байт; инъекция через имена файлов в цикле fetch `deploy-site.sh:172-185` (`for name in $REMOTE_FILES` без кавычек) — локальный OpenSSH 10.2 использует SFTP, подтверждённого исполнения команд нет.

Заявленное закрытым (K-01..K-05, W-01..W-03, S-01, M-01, KQ-01..KQ-10) не переоткрывалось; область KQ-01/02 (soft-режим реле, вшитый секрет) намеренно не трогалась, кроме мест, где она служит контекстом (M-06, M-07, L-01).

---

# Что осталось непроверенным

## Пропущенное в аудите Khandaq (критика полноты)

### A. Подсистемы, до которых не дошёл никто

1. **`/Users/lucyok/Khandaq-rc/khandaq-android/` — второй Android-клиент (545 файлов, aTox/Kotlin), не смотрел ни один из восьми.** Android-аудитор покрыл только `khandaq-android-trifa/`. README называет его «Legacy Android reference», но он не мёртв: `scripts/sync-all-bootstrap-nodes.py:17` активно переписывает его `atox/src/main/res/raw/nodes.json`. Свой манифест (`khandaq-android/atox/src/main/AndroidManifest.xml`) с экспортированной `MainActivity` (`tox:` deeplink + `SEND text/plain`), свой FileProvider, свои `.github/workflows/{ci,detekt}.yaml`, свой Bazel/WORKSPACE. Ни разрешения, ни deeplink, ни хранение ключей здесь никем не проверялись, при этом APK этой линии публиковался (`docs/SECURITY_AUDIT_RETEST.md:5`).

2. **`infra/bootstrap/` — вся подсистема bootstrap-нод (12 файлов) вне охвата.** `install.sh`, `deploy-node.sh`, `Dockerfile`, `nginx-bootstrap.conf`, `khandaq-bootstrap.service`, `docker-compose.yml`, `.env.example`. Relay-аудитор явно ограничился push, web-аудитор — статикой сайта, supplychain — воркфлоу. Систем-юнит bootstrap-демона не проверялся на песочницу (для реле это делали), firewall-документ `FIREWALL.md` никто не сверял с compose-портами.

3. **Фактические списки bootstrap-нод, которые шипятся клиентам, — DHT-якоря доверия — не смотрел никто.** `khandaq-android-trifa/.../trifa/BootstrapNodeEntryDB.java` (101 захардкоженная нода с pubkey), `khandaq-desktop/res/nodes.json` (21), `khandaq-ios/local_pod_repo/objcTox/Classes/Public/Manager/nodes.json` (22), `khandaq-android/atox/src/main/res/raw/nodes.json` (24). Конкретика: **в aTox-списке до сих пор лежат три ноды `bootstrap1..3.khandaq.org`** с maintainer=Khandaq, хотя `config/khandaq_bootstrap_nodes.json` помечает их `status=retired` (сейчас имена не резолвятся — но это ровно та комбинация «клиент доверяет имени, инфраструктуры за ним нет», из которой делают eclipse). В desktop/iOS их уже нет — расхождение никем не замечено.

4. **`scripts/sync-all-bootstrap-nodes.py` / `sync-android-bootstrap-nodes.py` / `generate-bootstrap-registry.py` / `deploy-bootstrap-registry.sh` / `audit-bootstrap-nodes.sh` — тракт обновления якорей доверия.** Ни один из них не вызывается ни одним workflow (проверено перебором всех `scripts/*.py` против `.github/workflows/`), то есть изменения списка нод не проходят ни одного гейта, и никто не читал код, который эти списки переписывает.

5. **Вендоренный C-код toxcore — тот, что реально парсит сеть — не аудировал никто в этом раунде.** `khandaq-desktop/buildscripts/toxcore/amalgamation/toxcore_amalgamation.c` — 89 697 строк, содержит собственные правки Khandaq (комментарии `KHANDAQ (audit F2)` / `(audit A29)` на строках 78940/78962/78968), плюс `buildscripts/toxcore/toxav/toxav.c:3317+`. `khandaq-ios/local_pod_repo/toxcore/toxcore/toxav/{rtp.m,toxav_ngc_video.m}` — отдельная копия того же кода под iOS с собственными KHANDAQ-правками. Desktop-аудитор: «не оценивал upstream-код qTox», secrets: «не анализировал вендоренные деревья», android: «toxcore/webrtc/vpx не смотрел». В итоге три расходящиеся копии одного C-парсера, и синхронность правок между ними никем не сверена (protocol-аудитор сверял только Java/ObjC/C++ верхний слой).

6. **`khandaq-android-trifa/patches/` — python-скрипты, модифицирующие shipped C на этапе сборки.** `apply_khandaq_toxav_ngc_chroma.py`, `apply_khandaq_media_resend.py`, `apply_khandaq_patch.py`, `apply_khandaq_201_instrument.py`, `khandaq-libvpx-cve-2024-5197.patch`, `khandaq-ngc-tcp-announce.patch`. Их читал только `android-native-so.yml` на предмет «есть ли вызов в deps.sh». Сами патчи (текстовая подстановка в C, поведение при неудачном матче) не прочитал никто.

7. **`khandaq-android-trifa/android-refimpl-app/native-audio-jni/` — 879 C-файлов**, `jni-c-toxcore/jni-c-toxcore.c` — 10 771 строк, из которых прочитаны только колбэки lossless-пакетов. Плюс `coffeecatch.c/coffeejni.c` (перехват SIGSEGV в native — сам по себе класс проблем: продолжение работы после повреждения памяти).

8. **Аудио/видео на всех трёх платформах — сквозная дыра.** Android: `CallingActivity.java` (4023 строки), `ConfGroupAudioService`, `CameraWrapper.java`. iOS: `OCTSubmanagerCallsImpl`, `OCTAudioEngine/OCTVideoEngine`, CallKit/PushKit. Desktop: `src/video` (18 файлов), `audio/`. Каждый из трёх аудиторов честно вынес это в «не покрыто» — то есть RTP/VP8-путь от недоверенного пира не смотрел вообще никто, при том что прошлые раунды находили там переполнения (F2/A29 — именно chroma-буферы).

9. **`khandaq-desktop/src/persistence/` (40 файлов) — хранение на диске.** `db/rawdatabase.cpp:84-85` прямым текстом: «If empty, the database will be opened unencrypted» — то есть история чатов на десктопе не шифруется, если у профиля нет пароля; `credentialstore.cpp` на Linux не работает вовсе (это secrets-аудитор отметил, но последствие — что тогда происходит с ключом БД — никто не проследил). Плюс `globalsettingsupgrader.cpp`/`personalsettingsupgrader.cpp`/`settingsserializer.cpp`/`toxsave.cpp`/`profilelocker.cpp` — миграции и парсинг локальных файлов профиля не читал никто.

10. **`khandaq-desktop/src/chatlog/` (35 файлов) и `src/widget/` (151 файл) — рендер недоверенного текста.** `src/chatlog/content/text.cpp:374` подаёт текст сообщения в `QTextDocument::setHtml()`. Экранирование делается выше (`chatmessage.cpp:62`), но связка «HTML-документ + внешние/локальные ресурсы + вложенные таблицы» не проверялась никем; desktop-аудитор прямо вынес chatlog за скобки.

11. **Приём файлов 1:1 на Android — `HelperFiletransfer.java` (3202 строки).** Автоприём включается по типу и размеру (`check_auto_accept_incoming_filetransfer`, `PREF__auto_accept_image/_video`, `AUTO_ACCEPT_FT_MAX_*`), путь строится как `VFS_PREFIX + VFS_FILE_DIR + "/" + friend_pubkey_str` (:313). Аудитор смотрел только групповой NGC-транспорт (`NgcGroupFileTransfer`), а этот путь — где недоверенное имя файла и авто-запись на диск без действия пользователя — не смотрел.

12. **`MaintenanceActivity.java` (1725 строк) + `IOBrowser` — экспорт/импорт БД и профиля на SD-карту** (`SD_CARD_ENC_CHATS_EXPORT_DIR`, `SD_CARD_FILES_EXPORT_DIR`, `PREF__DB_secrect_key`), сброс списка bootstrap-нод, переключатель Orbot. Есть даже device-тесты `PlaintextExportGateDeviceTest`/`StartExportImportTest`, то есть «гейт экспорта в открытом виде» существует как понятие — но ни код, ни гейт никто не проверил (BackupHelper `.kbk` — это другой путь).

13. **`infra/monitoring/` + `scripts/deploy-monitoring.sh` + `setup-uptime-kuma.{sh,py}`** — стек мониторинга (Uptime Kuma, `louislam/uptime-kuma:1` — плавающий тег, без digest, в отличие от всех остальных образов). Не смотрел никто.

14. **Крупные Java-файлы trifa вне списка охвата.** Всего 169 417 строк в 301 файле; аудитор перечислил ~30. Не открывались: `HelperGeneric.java` (6975), `GroupMessageListActivity.java` (5586), `TrifaToxService.java` (2966), `SettingsActivity.java`, `QrScanActivity.java` (QR → добавление контакта/ноды), `ToxVars.java`. То же на iOS: ~200 контроллеров чата и весь `local_pod_repo/objcTox` за пределами 15 названных файлов.

### B. Принято на веру / заявленный охват не соответствует реальности

15. **Рабочее дерево ГРЯЗНОЕ, и это не заметил никто.** `git status`: изменены `infra/push/relay/app.py` (**+223 строки**), `scripts/record-push-release-evidence.py` (196), `SECURITY.md`, `docs/PUSH-COMPATIBILITY.md`, `web/privacy.html`, `.gitignore`; удалён `docs/push-release-evidence.json`; не отслеживаются `docs/release-evidence/` и `scripts/check-policy-claims.py`. Relay-аудитор написал «Репозиторий не изменён (git status пуст)» — это фактически неверно. Новый код реле (`_challenge_hash_key`, `_kth`, `_chal_count`, `_challenge_gate`, `_challenge_summary`, таблицы `pushchalrate`/`chalstat`, запись файла ключа `challenge-th.key` с O_EXCL) **не покрыт ни одним тестом**: `test_app.py` не изменялся, ссылок на новые символы в нём ноль. «119 passed» не говорит об этом коде ничего. Это самый свежий и самый непроверенный код в репозитории, и он вне коммита 0a0d5559, то есть вне CodeQL/Semgrep/ревью.

16. **`scripts/check-policy-claims.py` — новый неотслеживаемый гейт**, не вызывается ни одним workflow и не прочитан ни одним аудитором.

17. **Тесты клиентов не гоняет ни CI, ни аудиторы.** В CI: `:app:testDebugUnitTest` (Android JVM) и pytest реле — всё. `ios-build.yml` делает только `xcodebuild build` для симулятора — **137 тест-файлов iOS** (`AntidoteTests/`, `objcTox/Tests/`, включая `OCTNgcSignedHistoryDualIntegrationTests.m`) не исполняются нигде. `windows-build.yaml` кросс-компилирует десктоп без `ctest` — **35 тестов десктопа** (`test/core/ngchistsig_test.cpp`, `test/persistence/dbschema_test.cpp`, `test/model/exiftransform_test.cpp`, `test/net/bsu_test.cpp`) не запускались ни разу. Вывод protocol-аудитора о межплатформенной эквивалентности подписи истории держится исключительно на чтении — при том что тест, который это доказал бы, лежит в дереве и не запускается. 50 androidTest-файлов (`NgcHistSigNativeTest`, `NgcHskStoreDeviceTest`, `NgcHistoryDowngradeDeviceTest`) тоже не гоняет никто — а именно они проверяют JNI, историю которого `release-provenance.yml:62-72` описывает как «худший дефект проекта».

18. **Гейты, существующие, но никем не запущенные в этом раунде:** `check-ios-downgrade-policy.py`, `check-ios-rtp-reassembly.py`, `check-push-ca-anchors.py`, `changelog-content-diff.py`. Гейты, не привязанные ни к одному workflow: `verify-desktop-signatures.py`, `generate-release-manifest.py`, `generate-bootstrap-registry.py`, `lock-relay-requirements.py`, `check-policy-claims.py`, оба `sync-*-bootstrap-nodes.py`. Supplychain отчитался «16 офлайн-гейтов зелёные», но вопрос «а какие гейты не подключены к CI вообще» не задал никто.

19. **Провенанс нативной библиотеки в релизном APK.** `.github/workflows/release-provenance.yml:76-96`: `.so` берётся через `gh run list --workflow=android-native-so.yml --status=success --limit 1` — **последний успешный прогон, без привязки к коммиту релиза, без сверки digest артефакта и без проверки, с какими входными параметрами он собран**. А у этого workflow есть вход `instrument: true` (`android-native-so.yml:14-18`), добавляющий logcat-инструментацию в шипающийся `.so`. То есть релиз может унаследовать инструментированную либо собранную из другого дерева библиотеку, и `check-jni-symbols.py` этого не поймает (он проверяет наличие символов, а не происхождение). Supplychain читал этот файл и эту связку не разобрал.

20. **`khandaq-desktop/buildscripts/download/download_toxcore.sh` затирает патченое дерево.** Скрипт клонирует `zoff99/c-toxcore` на пин `2e7a0675…` и делает `cp -R "$TEMPDIR/src"/. .` поверх вендоренного каталога. Расхождение ref проверяется, но при `FORCE_TOXCORE_DOWNLOAD=1` (или на дереве без `CMakeLists.txt`) правки `KHANDAQ (audit F2/A29)` в `toxav/toxav.c` молча заменяются на upstream. Гейта, проверяющего сохранность этих патчей в вендоренном дереве, нет (грепом по `scripts/` и `.github/` — единственное упоминание в `android-native-so.yml`, и только про Android-ветку).

21. **`khandaq-android-trifa/android-refimpl-app/gradle/wrapper/gradle-wrapper.jar` и `sorma2/test/sqlite-jdbc-3.53.1.0.jar` — закоммиченные бинарники, никем не проверенные.** `gradle/wrapper-validation-action` в воркфлоу отсутствует (грепом — ноль), при том что этот jar исполняется в каждом CI-джобе, включая релизный.

22. **Опубликованные артефакты проверены на четверть.** `web/downloads/` содержит реально раздаваемые файлы (untracked, 388 МБ): secrets-аудитор распаковал только `.deb`; `khandaq-windows-installer.exe`, `khandaq-windows-x86_64.zip`, `khandaq-macos.zip` не открывал никто. Отдельно: `khandaq-messenger_amd64.deb` и `khandaq-messenger_0.2.6_amd64.deb` побайтно идентичны и это **0.2.6**, тогда как релиз — 0.2.42; соответствие «что на сайте» ↔ «что в манифесте/VERSION» никем не сверялось (web-аудитор не имел доступа к хосту, а локальную копию не сопоставил).

23. **`config/khandaq_push.json` и `config/khandaq_bootstrap_nodes.json` — чистая документация, которую никто не сверил с кодом.** Грепом: их не читает ни один клиент и ни один скрипт (только README/SECURITY.md/docs). При этом конфиг объявляет `allowed_push_url_prefixes` с `https://ntfy.sh/` и `https://gotify1.unifiedpush.org/UP?token=` и `https://tox.zoff.xyz/…`. Валидаторы (`PushUrlValidator.java`, `OCTPushUrlValidator.m`) читались отдельно, но вопрос «совпадает ли декларируемая политика с зашитой» и «что означает префикс `https://ntfy.sh/` без пути» не задал никто.

### C. Классы уязвимостей, которые не искал никто

24. **Утечка мимо прокси/Tor.** `PREF__orbot_enabled` (`MainActivity.java:1072-1074, 3108-3174`) прокидывается только в нативную инициализацию Tox (SOCKS для DHT). А `KhandaqPushCapability.java:360-363` открывает `new URL(url).openConnection()` — `HttpURLConnection` без аргумента `Proxy`, то есть регистрация capability и обращения к `push.khandaq.org` идут по прямому маршруту в обход Orbot. Пользователь, включивший Tor именно чтобы скрыть IP, раскрывает его реле. Ни один из восьми не проверял, охватывает ли прокси весь исходящий трафик; тот же вопрос открыт для десктопа (настройки прокси qTox vs `BootstrapNodeUpdater`/QNetworkAccessManager).

25. **Защита данных на iOS в покое и в бэкапе.** Во всём `khandaq-ios/Antidote` и `local_pod_repo/objcTox` — **ноль** упоминаний `NSFileProtection*`, `FileProtectionType`, `isExcludedFromBackup`/`NSURLIsExcludedFromBackupKey`. Realm шифруется (это проверено), но класс защиты файлов, попадание базы/tox-save/`ShareInboxStorage` в iCloud- и iTunes-бэкап, и доступность контейнера расширению до первой разблокировки — не смотрел никто. Android-аналог (`allowBackup=false`) проверен, iOS-аналога проверки нет.

26. **Ресурсное истощение и амплификация на клиентах.** Protocol-аудитор сам вынес два кандидата (обход кулдауна истории через leave/join; `announceForNewPeer(force=YES)` → квадратичный broadcast) и не стал их считать находками, а никто другой к вопросу DoS не подходил: бюджеты памяти/диска при приёме NGC-файлов, лимиты на число одновременных трансферов, поведение при 1000 пиров в группе — вне охвата всех восьми.

27. **Динамической проверки не было ни одной.** Ни один аудитор не собрал ни один клиент и не запустил его: Android — не собирался (Gradle не запускался), iOS — не собирался, desktop — «Qt5/ctest в этом окружении нет», реле — только застабанный pytest, сайт — «доступа к боевому хосту не было». Все восемь отчётов — статическое чтение плюс python-гейты. Соответственно любое утверждение вида «граница сходится», «выхода за буфер нет», «гейт закрывает» не проверено исполнением, а прошлый опыт этого проекта (`release-provenance.yml:66-74`: три JNI-функции месяцами отсутствовали в шипающемся `.so`, и «ничего не падало») — ровно про то, что чтение кода этот класс дефектов не ловит.