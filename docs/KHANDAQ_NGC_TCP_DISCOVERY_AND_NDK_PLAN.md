# Khandaq — план: pure-TCP discovery для NGC + NDK-пайплайн (п.4)

**Дата:** 2026-06-16
**Статус:** план (нативка в этой итерации не собиралась/не доставлялась)
**Связано с:** `KHANDAQ_GROUP_MEDIA_STABILITY_REPORT.md` (п.11, п.12), память `ngc-public-group-join-requires-udp`

---

## 0. Зачем

Вступление в публичную NGC-группу **по chat-id** держится на onion-DHT announce-lookup, который работает только при UDP (`toxcore/Messenger.c:3154` — `do_dht()`/`networking_poll()` выполняются лишь при `!udp_disabled`). На сетях, режущих UDP (мобильные операторы, CGNAT, отдельные страны), присоединяющийся **навсегда залипает в CONNECTING**. Подтверждено вживую: телефон входит в группу через `relay=2 direct=0` (UDP проходит → discovery → транспорт по релею), а Samsung A50 в другой стране — нет; помог только friend-assisted invite.

Прикладной слой уже смягчён (п.1 — подсказка пользователю, п.2 — share-инвайт с Tox ID, friend-assisted invite). **Настоящее решение — нативное**, и оно упирается в две вещи, которых в репозитории нет:

1. **Нет способа собрать `libjni-c-toxcore.so`** — он лежит готовым бинарным блобом (~33 МБ, статически слинкованный toxcore, путь сборки `/root/work//arm64_build///c-toxcore`). NDK-пайплайна нет. Значит **никакой** нативный патч (ни этот, ни `Messenger.c self_announce_group` из п.12) доставить нельзя.
2. **Нет pure-TCP discovery в toxcore** — announce-lookup ходит по onion, чьи path-nodes набираются из UDP-DHT (`onion_client.c` ~`randfriends_nodes`).

Порядок обязателен: **сначала пайплайн (A), потом нативный discovery (B)** — без A любую правку B нельзя проверить на устройстве.

---

## A. NDK build pipeline (предпосылка для всего нативного)

**Цель:** воспроизводимо собирать `libjni-c-toxcore.so` (arm64-v8a, armeabi-v7a, x86_64) из исходников в репозитории и класть в `app/src/main/jniLibs/<abi>/`.

**Что есть сейчас:**
- Исходники JNI: `khandaq-android-trifa/jni-c-toxcore/jni-c-toxcore.c` (+ `coffeecatch`, topic-patch).
- Эталон toxcore C, совпадающий по символам с поставляемым `.so`: `khandaq-desktop/buildscripts/toxcore/toxcore/*.c`.
- Зависимости toxcore: libsodium, libopus, libvpx (для AV).

**Шаги:**
1. Зафиксировать дерево toxcore для Android в репо (vendored), а не «совпадающее по символам» — чтобы исходник = бинарь. Кандидат: подмодуль/копия `zoff99/c-toxcore` той же ревизии, что в текущем `.so` (проверить версию: `strings libjni-c-toxcore.so | grep -i "0.2."`).
2. Добавить `app/jnilib/CMakeLists.txt` (или Android.mk) с целями: `sodium`, `opus`, `vpx`, `toxcore`, и итоговую shared `jni-c-toxcore`, линкующую их статически (как в текущем блобе).
3. Прокинуть `externalNativeBuild { cmake { ... } }` в `app/build.gradle` под `abiFilters`. Закрепить версию NDK (в проекте уже встречается `20.1.5948944`; перейти на актуальную LTS, проверить совместимость кода).
4. Кросс-сборка зависимостей: скрипт `scripts/build-android-native-deps.sh` (sodium/opus/vpx под каждую ABI через NDK toolchain) с кешем артефактов.
5. CI-цель + локальный `scripts/build-jnilibs.sh`: на выходе `.so` под 3 ABI + SHA256 в лог.
6. **Acceptance:** `./gradlew :app:assembleDebug` собирает `.so` из исходников; приложение стартует, tox инициализируется, существующие группы/звонки работают (smoke на эмуляторе и реальном устройстве); diff символов нового `.so` vs старого не ломает JNI-сигнатуры.

**Риски:** размер/время сборки; рассинхрон версий toxcore между платформами (clients 0.2.19 / bootstrap 0.2.20 — заодно унифицировать); AV-кодеки (vpx) — самая капризная зависимость.

---

## B. Pure-TCP NGC discovery в toxcore

**Цель:** дать присоединяющемуся узлу найти публичную группу и её пиров **без рабочего UDP**, используя только TCP-релеи.

**Где корень (эталонные пути `khandaq-desktop/buildscripts/toxcore/toxcore/`):**
- `Messenger.c:3154` — `do_dht()`/`networking_poll()` под `!udp_disabled`. При pure-TCP DHT не крутится.
- `onion_client.c` (~`populate_path_nodes` / `randfriends_nodes`) — onion path-nodes берутся из close-list DHT, который при pure-TCP пуст → announce-lookup некуда маршрутизировать.
- `group_chats.c` — `gc_add_peers_from_announces` (вход по announce, DHT-путь) vs ветка `HS_INVITE_REQUEST` (friend-invite, уже работает по TCP).
- `Messenger.c:~3042 self_announce_group` — в эталоне TCP-релеи уже пакуются в announce (`tcp_copy_connected_relays`); проверить, есть ли это в поставляемом `.so` (см. п.12 — патч мог быть не доставлен).

**Возможные подходы (от меньшего к большему):**
1. **TCP-узлы как onion path-nodes.** Источник path-nodes для onion-lookup дополнить подключёнными TCP-релеями (по аналогии с тем, как они уже шарятся в группе). Минимально-инвазивно, не трогает DHT-семантику.
2. **Announce/lookup поверх TCP-релея.** Маршрутизировать onion announce + lookup через TCP-соединения, когда UDP недоступен (релей как точка рандеву). Сложнее, ближе к ядру onion.
3. **Khandaq-релей как «group rendezvous».** Опциональный сервис, где founder публикует свои TCP-релеи/announce, а joiner забирает их по chat-id. Уводит от чистого Tox-P2P (вопрос приватности/централизации) — только как запасной вариант, не дефолт.

**Рекомендация:** начать с (1) — наименьший риск, прямое попадание в причину. Сопроводить честной правкой `jni-c-toxcore.c:699`: вместо безусловного `udp_enabled = true` уважать флаг, но при pure-TCP включать новый источник path-nodes.

**Acceptance (только после A):**
- Эмулятор с принудительно отключённым UDP (SOCKS5-прокси → `udp_disabled=true`, `Messenger.c:4196`) **входит** в публичную группу по chat-id, где пиры онлайн: `group_conn=1 tox_peers>1 relay>0`.
- Регресс: на UDP-сети поведение не хуже текущего (discovery 30–90с).
- Кросс-сеть: UDP-founder ↔ TCP-only-joiner соединяются и обмениваются сообщениями/медиа.

---

## Промежуточная проверка без полного решения (можно сделать сейчас)

SOCKS5-прокси на хосте + указать его в настройках прокси приложения → toxcore уйдёт в `udp_disabled=true` (`Messenger.c:4196-4202`). Ожидаемо: эмулятор **не сможет** войти в публичную группу по chat-id (воспроизведение сети A50), но friend-invite сработает. Это валидирует B-acceptance ещё до правок нативки.

---

## Итог приоритетов

1. **A (NDK-пайплайн)** — без него мёртвы и этот план, и патч п.12. Наивысший приоритет инфраструктуры.
2. **B(1)** — TCP path-nodes для onion-lookup; правка `jni:699` на честную.
3. Прикладные смягчения (п.1/п.2) уже в деле и закрывают боль пользователей на время A+B.
