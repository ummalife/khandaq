# Design — authenticated authorship for NGC history sync

**Status:** proposal, awaiting sign-off. Nothing here is implemented.
**Closes:** external audit #2, finding 1 (the half that needs a protocol version).
**Author:** written 11 Aug 2026 as the follow-up promised in `SECURITY-REVIEW2-RESPONSE.md`. Lives at the repo root, not under `docs/`, because `docs/**` is gitignored here.

---

## 1. The defect, stated precisely

A history-sync packet carries the *alleged original author* as 32 raw bytes that nobody signed.

Wire layout today (`0x02` = text history, `0x03` = file history), from
`HelperGroup.handle_incoming_sync_group_message`:

| offset | size | field |
|---|---|---|
| 0 | 6 | magic `66 77 88 11 34 35` |
| 6 | 1 | version, always `0x01` |
| 7 | 1 | pkt id (`0x02`) |
| 8 | 4 | message id |
| 12 | 32 | **alleged original author pubkey** |
| 44 | 4 | timestamp (low 4 bytes of an 8-byte BE value) |
| 48 | 25 | alleged author display name |
| 73 | … | UTF-8 text |

The transport authenticates the **syncing** peer — `tox_group_peer_get_public_key__wrapper(group_number, peer_id)`, which we already read — but offsets 12 and 48 are attacker-chosen. Any group member can therefore manufacture a message that displays as another member's, with a chosen timestamp inside the sync window.

**What is already mitigated** (shipped): unauthenticated sync packets cannot write the persistent peer table; synced rows are never re-served, so a forgery cannot launder through an honest client; packets claiming to originate from us are rejected; private member-to-member messages are not served through history sync at all; and per-group row/byte/notification budgets bound the flooding half of the finding.

**What is not**: the attribution itself.

---

## 2. Why the obvious fix does not exist

The instinct is "sign it with the Tox identity key". That key is **Curve25519**, an encryption key. `crypto_sign` needs **Ed25519**. libsodium converts Ed25519 → Curve25519 and deliberately not the reverse, so there is no way to produce a signature that verifies against a peer's Tox public key.

Deriving a fresh Ed25519 key from the Tox secret is easy; **publishing it credibly is the hard part**. Announcing "my signing key is X" inside an unsigned packet is circular — that announcement is exactly as forgeable as the field we are trying to protect.

## 3. The idea that breaks the circle

**toxcore already authenticates the sender of a live group packet.** Only *relayed* history is unauthenticated. So the live channel is a trustworthy carrier for a key announcement, and history verified against a key learned that way inherits that trust.

---

## 4. Design

### 4.1 History-signing key (HSK)

Each profile holds an Ed25519 keypair, generated once and stored in the encrypted profile database beside the other secrets. It is *not* derived from the Tox secret key — deriving it buys nothing (the binding comes from the announcement, not the derivation) and would couple two key lifetimes for no reason. It is regenerated when the Tox identity changes.

### 4.2 Announcement — new packet `version=0x02, pktid=0x50`

```
magic(6) | 0x02 | 0x50 | hsk_pub(32) | valid_from_ts(8, BE) | sig(64)
```

`sig` is over `"KQ-HSK-ANNOUNCE-1" || tox_pubkey(32) || hsk_pub(32) || valid_from_ts(8)`, made with the announced key itself. The self-signature proves possession; the **binding to the Tox identity comes from the authenticated transport**, not from the signature.

Sent on joining a group, on key change, and periodically (a peer that joins later must be able to learn it — see §6, open question 1).

Receivers store `(group_id, tox_pubkey, hsk_pub, first_seen_ts, last_seen_ts)`. **First announcement wins**: a later announcement with a different `hsk_pub` for the same `tox_pubkey` is recorded but does not replace the first one automatically, because silently accepting a replacement re-opens the impersonation door for anyone who can get a packet in first after a peer goes offline. Replacement requires the peer to be currently connected *and* the old key to be absent for longer than a grace period.

### 4.3 Signed history — new packets `version=0x02, pktid=0x02 / 0x03`

Identical layout to today's, plus a 64-byte signature appended after the text, with the text length made explicit so the signature cannot be confused with content:

```
magic(6) | 0x02 | 0x02 | msg_id(4) | author_pub(32) | ts(8, BE) | name(25) | text_len(4, BE) | text | sig(64)
```

Two changes beyond the signature, both worth making while the version is being bumped: the timestamp becomes the **full 8 bytes** (today only the low 4 are transmitted — a latent 2038-class truncation), and the text is explicitly length-prefixed.

`sig` is over:

```
"KQ-HISTSYNC-1" || group_id(32) || author_pub(32) || msg_id(4) || ts(8) || sha256(text)(32)
```

All fields fixed-width, domain-separated by the prefix, so no two distinct messages share a signed pre-image.

### 4.4 Verification on receive

1. Unknown version → ignored by every shipped client (see §5). Unknown `hsk_pub` for the claimed author → store the row **unverified**, never notify.
2. Signature fails → drop the packet. Not "store unverified" — a *present but wrong* signature is an attack, not an old client.
3. Signature verifies → store as **verified**, normal handling.

### 4.5 Transition and display

A new `GroupMessage.author_verified` column: `VERIFIED`, `UNVERIFIED_LEGACY` (no signature, sender predates the rollout), `UNVERIFIED_NO_KEY` (signed variant but we never learned the author's key).

Anything not `VERIFIED` renders with a marker on the bubble and is excluded from notification. Old Tox conference bubbles already carry an orange/green synced-vs-direct dot (`ConferenceMessageListHolder_text_incoming_not_read`), so there is precedent for the affordance; the NGC group holder has no such indicator today and needs one.

**Note:** adding this marker to NGC bubbles is useful on its own and does not need the protocol change. It is the one piece of this document that could ship first — and it has: see §8 step 2. What is shipped is the `UNVERIFIED_LEGACY` case only, since without signatures every synced row is that. `VERIFIED` / `UNVERIFIED_NO_KEY` arrive with the protocol.

---

## 5. Backward compatibility — verified on all three platforms

This was step 1 of §8 and it is **done**. Every shipped parser gates on the version byte and drops anything it does not recognise:

| platform | check | file |
|---|---|---|
| Android | every branch requires `data[6] == 0x1`; unknown version falls to the `else` and is ignored | `MainActivity.android_tox_callback_group_custom_packet_cb_method` |
| iOS — history sync | `if (bytes[6] != kOCTNgcHistLayer) return;`, `kOCTNgcHistLayer = 0x01` | `OCTNgcGroupHistSync.m:143` |
| iOS — edit/delete/reaction | `b[6] != 0x01 → return NO` | `OCTSubmanagerGroupsImpl.m:2534, 2708` |
| desktop | `data[6] != NGC_VERSION → return`, `NGC_VERSION = 0x01` | `core/core.cpp:1880` |

So a shipped client receiving `version=0x02` does nothing at all — no mis-parse, no crash, no half-message. The version byte is a usable upgrade lever, and the design can rely on it.

### 5.1 Packet-id registry (so the next feature does not collide)

Ids observed in use today, under `version=0x01`:

| id | meaning |
|---|---|
| `0x01` | history-sync request |
| `0x02` | history-sync text |
| `0x03` | history-sync file |
| `0x11` | group file, single-packet |
| `0x12` | chunked file BEGIN |
| `0x13` | chunked file CHUNK |
| `0x14` | chunked file REQUEST |
| `0x21` | live audio |
| `0x31` | live video |
| `0x41` / `0x42` / `0x43` | message edit / delete / reaction |

`0x50` is free, which is why §4.2 uses it. **Anything new must be added to this table in the same commit that introduces it** — the ids are spread across three codebases and there is no other place they are written down.

That makes the rollout safe in both directions:

- **New → old:** the old client ignores the signed packet. It loses that history item, exactly as if the syncing peer had not sent it. Nothing is corrupted.
- **Old → new:** the new client receives `version=0x01`, marks the row `UNVERIFIED_LEGACY`, and displays it with the marker.

Dual-emitting `0x01` and `0x02` for the same message would keep old clients fed, but it also lets an attacker drop the signed copy and keep the unsigned one — the classic downgrade. **Recommendation: do not dual-emit.** Signed clients emit only `0x02`; old clients simply receive less history during the transition, and the transition ends when the fleet has turned over.

---

## 6. Decisions (answered by the owner, 11 Aug 2026)

1. **Late joiners — DECIDED: periodic re-announce, plus on join.** No new packet type; the announcement (§4.2) is simply re-sent on a long timer and whenever we join a group. Cost is a little bandwidth in large groups, which is the cheapest of the three options and the only one that needs no new attack surface. Implementation note: the timer must be jittered per client, or every member of a large group re-announces in lockstep.
2. **Key rotation / reinstall — DECIDED: immediate replacement, but only while the peer is connected in the group.** The binding argument is the same one that makes the whole scheme work: a live packet is authenticated by the transport, so a currently-connected peer announcing a new key has already proved it is that Tox identity. An announcement from a peer NOT currently in the group's peer list does not replace anything. Every replacement is logged. This keeps reinstall from degrading the UX while giving an attacker no window in which the real owner is absent.
3. **Quarantine display before the protocol — DONE.** Shipped ahead of the protocol; see §8 step 2.
4. **Platform order — DECIDED: desktop first.** It is a history-sync **consumer only** (`core.cpp:1600` — it never emits a request), so it needs verification but not signing: the smallest change, and it proves the verification path before either mobile client starts emitting signatures. Then Android, then iOS. The version gate is settled for all three (§5).

---

## 7. What this does not fix

A group member can still forge history **as themselves** with a false timestamp, and can still withhold history. Neither is impersonation, and neither is in scope for this finding.

## 8. Rough sequencing

1. ~~Confirm the version gate on iOS and desktop parsers (§5).~~ **Done — all three gate on the version byte and ignore unknown values.**
2. ~~Unverified-display marker on NGC bubbles (§4.5).~~ **Shipped for incoming text rows** (`GroupMessageListHolder_text_incoming_not_read.mark_unverified_sender`): a `was_synced` row now gets the orange marker in the status slot plus a localised content description. Regression-checked on device (no crash, no marker on non-synced rows); the positive case is not yet seen on screen because it needs a synced row, which needs a second peer.
3. HSK generation + storage + announcement, all three platforms, with verification disabled.
4. Emit signed history, still accepting `0x01`. **Половина шага 4 сделана (13 авг 2026): сборщики пакетов есть и покрыты round-trip тестами против парсеров** (`NgcHistSigParser.buildAnnouncement` / `buildSignedText`). Сборка — это не отправка: проводка в реальный emit меняет исходящий трафик и намеренно не сделана. Сборщик отвергает ровно то, что отверг бы парсер (включая тело выше потолка 37000), чтобы клиент не мог выпустить пакет, который его же пиры обязаны отбросить. **Мутация «писать длину little-endian» красит 3 round-trip теста** — это и есть защита от расхождения сборщика и разборщика, которое иначе всплыло бы только в поле, между двумя клиентами.

**Прогресс по шагу 3 (десктоп, 13 авг 2026):** проверочная сторона реализована и сверена с эталоном — `src/core/ngchistsig.{h,cpp}` (построение обоих pre-image + `verifySignature` поверх libsodium `crypto_sign_verify_detached`), тест `test/core/ngchistsig_test.cpp`, зарегистрирован как `auto_test(core ngchistsig)`. **16/16 зелёные, включая все 8 замороженных векторов** — то есть C++ строит те же байты, что и `ngc_histsync_vectors.py`. Подписывания здесь нет намеренно: десктоп — только потребитель истории. Проверено мутацией: замена big-endian на little-endian в `appendBigEndian64` красит 5 векторов.

**Прогресс по шагу 3 (Android, 13 авг 2026):** тот же pre-image построен на Java — `NgcHistSig.java` + `NgcHistSigTest` (12 тестов), **все 8 векторов совпали**, то есть Android, десктоп и эталон дают одинаковые байты. Подписи/проверки здесь пока нет намеренно: `java.security.Signature` даёт Ed25519 только с API 33, а minSdk 21 — значит проверка пойдёт через нативный JNI (libsodium) отдельным шагом. Класс пока никем не вызывается. **Java-специфика, вынесенная в тесты:** метод принимает уже закодированный UTF-8 `byte[]`, а не `String` — строки в Java UTF-16, и это самое вероятное место расхождения; максимальный u64 приходит как `-1L`, поэтому сдвиг логический (`>>>`), а не арифметический.

**Прогресс по шагу 3 (iOS, 13 авг 2026):** `OCTNgcHistSig.{h,m}` в objcTox рядом с `OCTNgcGroupHistSync.m`, SHA-256 через CommonCrypto (тот же `CC_SHA256`, что уже используется для HMAC пуша), без зависимостей от остального пода. Функция принимает `NSData` с уже закодированным UTF-8, а не `NSString` — `NSString` внутри UTF-16, и это самое вероятное расхождение iOS. **Все 8 векторов совпали**, проверено отдельным clang-harness'ом; мутация big-endian → little-endian красит 5 из 8. Подписи/проверки пока нет: `crypto_sign` приходит через под toxcore, это следующий шаг. Класс пока никем не вызывается, podspec подхватывает его автоматически (`Classes/**/*.{m,h}`).

**Итог шага 3 по pre-image: Android, iOS, десктоп и Python-эталон дают одинаковые байты на всех восьми векторах.** **Проверка подписи: десктоп и iOS готовы, Android — нет, и причина конкретная.**

- Десктоп: `crypto_sign_verify_detached` через уже линкуемый libsodium.
- iOS: то же самое — `objcTox → toxcore → libsodium`, `#import <sodium.h>` резолвится в реальной сборке пода (проверено `xcodebuild`), логика проверена harness'ом: настоящая подпись проходит, подделанное сообщение и чужой подписант — нет, всё некорректное падает закрыто.
- **Android — блокер, требующий решения:** Ed25519 там нет ни в одной форме. `java.security.Signature` даёт его только с **API 33**, а приложение шипится с **minSdk 21**; в зависимостях нет ни BouncyCastle, ни Tink, ни lazysodium; нативный JNI `crypto_sign` не экспортирует. Варианты: (а) добавить JNI-обёртку над libsodium, который toxcore и так линкует, — но `.so` собирается CI из свежего клона upstream, значит нужен патч-скрипт в `deps.sh` и прогон `android-native-so.yml`; (б) добавить Java-зависимость с Ed25519 — но это новый supply-chain вход, который придётся пинить в `witness.gradle` (то самое, что чинилось по находке #2). **Вариант (а) предпочтительнее: libsodium уже в бинарнике, новых зависимостей нет.**

**СДЕЛАНО (13 авг 2026), и оказалось проще ожидаемого.** Патч-скрипт не понадобился: JNI-исходник лежит **в нашем же репозитории** — `khandaq-android-trifa/jni-c-toxcore/jni-c-toxcore.c`, `deps.sh:554` просто копирует его в сборку (`cp -av /root/work/jni-c-toxcore`). И `sodium.h` там уже подключён (строка 65), то есть `crypto_sign_verify_detached` был доступен всё это время — не хватало только моста в Java.

Добавлено: `Java_..._MainActivity_khandaq_1ed25519_1verify` в JNI + `public static native int khandaq_ed25519_verify(...)` в `MainActivity`. Ничего больше: ни работы с ключами, ни разбора пакетов, ни подписывания. Длины проверяются через `GetArrayLength` **до** обращения к буферам — `messageLength` приходит из Java и не может описывать чужой массив; освобождение через `JNI_ABORT`, потому что ничего не менялось.

**R8 проверен:** в `proguard-rules.pro:21` уже стоит `-keepclasseswithmembernames … native <methods>`, значит имя нативного метода не переименуется и `UnsatisfiedLinkError` только-в-релизе не будет.

**Осталось по Android ровно одно и оно требует CI:** новая функция появится в `libjni-c-toxcore.so` только после прогона `android-native-so.yml` (4 ABI). До этого вызов дал бы `UnsatisfiedLinkError` — поэтому её пока никто не вызывает.
5. Flip display: unsigned → `UNVERIFIED_LEGACY` marker.
6. When the fleet has turned over, stop accepting `0x01` history entirely.

Steps 3–6 are a release each. This is not a one-batch change, which is why it was not attempted as one.

---

## 9. Замороженные тестовые векторы (сделано 11 авг 2026)

Подпись — это Ed25519 из libsodium, она между платформами не разойдётся. Разойдётся **байтовая
строка, которую каждая платформа решит подписать**: порядок полей, ширина целых, порядок байт,
хэшируется текст или вставляется сырым. Это ровно тот класс ошибки, при котором схема подписи
проваливается в проде, а все локальные тесты зелёные.

Поэтому pre-image заморожен: **`ngc_histsync_vectors.py`** в корне репозитория. Каждая реализация
обязана воспроизвести эти SHA-256 **до** того, как ей позволено что-либо подписывать или проверять.
Тот же приём уже применялся к HMAC push-релея, где все четыре реализации оказались совпадающими.

```
vector                  len  sha256(pre-image)
ascii-basic             121  33599061b75b2c487120a845450367ee880c931d6c00095960f8c3828f3457ed
empty-text              121  d008aea0521ebdc8ac4488263746b0c63c7819b37aa966edcdf89ff7121b711f
utf8-multibyte          121  752e856237a501d9bb3c278d97b445b2d428375f54edf0692ffbb80818dafd49
ts-above-32-bit         121  b16b06ed10ce4bec25661e486350f2b8b4018014b9ddcb8cbcc35ca51779507d
ts-max-u64              121  e867afdab7e3f201da8b48259530733400dce9caf5d1306e22af0989e69db055
zero-author-key         121  0c0a9029409cf4a617254a0f5bc2fa78119ca3f4766ce77f377ade1f38defa99
announce-basic           89  70b75055b020a79fbc70fe27fb7d48adebf585dcf6fd79f9a1f58d195d11a88b
announce-zero-ts         89  b25c730609d8e16646630ff6f8dff11046dfaa4d8f617bda18c948fa843de278
```

Векторы **не случайные** — каждый изолирует конкретную ошибку:

| вектор | что ловит |
|---|---|
| `empty-text` | реализацию, которая не хэширует пустое тело или подставляет null |
| `utf8-multibyte` | платформу, которая хэширует UTF-16 (нативные строки Java/Swift) вместо UTF-8 |
| `ts-above-32-bit` | **усечение до 32 бит** — текущий формат передаёт только младшие 4 байта таймстампа, а подписывается все 8; ошибка проявится только здесь |
| `ts-max-u64` | реализацию со **знаковым** 64-битным таймстампом — она завернётся в отрицательное |
| `zero-author-key` | обработку all-zero ключа как «поле отсутствует» |

Длина pre-image фиксирована (121 байт для истории, 89 для объявления) независимо от длины
сообщения — **текст хэшируется, а не встраивается**. Проверка: `python3 ngc_histsync_vectors.py --check`.

**Если формат когда-нибудь меняется — эти дайджесты перегенерируются тем же коммитом, что меняет
формат, и вместе с ними поднимается версия протокола.** Молча разошедшийся вектор означает, что
одна из трёх платформ уже подписывает не то.
