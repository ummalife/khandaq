# Khandaq — Google Play production readiness (answers)

App: **com.khandaq.messenger** ("Khandaq — Secure Messenger"). Tox-based, p2p,
end-to-end encrypted, no servers, no accounts, no ads, no tracking.

Fill these in Play Console → **Dashboard → "Set up your app"** (each is a form the
androidpublisher API does not cover). Suggested answers below are tailored to a
serverless E2E messenger — review before submitting.

---

## 1. App access
**Q: Is any functionality restricted (login / special access)?**
→ **All functionality is available without special access.** Khandaq needs no
account, phone number, or login — a local Tox ID is generated on first launch.

## 2. Ads
→ **No, my app does not contain ads.**

## 3. Content rating (IARC questionnaire)
- Category: **Social / Communication**.
- Email: support@khandaq.org
- Violence / sexual / language / controlled substances / gambling: **No** to all.
- **Does the app let users interact or exchange content?** → **Yes** (users message,
  call, and share files p2p).
- **Can users share their location?** → **No** (Khandaq has no location feature).
- **Is user-generated content shared / can users communicate?** → **Yes, unmoderated**
  (p2p; the developer has no server and cannot moderate).
- Digital purchases: **No**.
→ Expect a **Teen / Mature 17+ (PEGI 12–16)** rating due to unmoderated user
  communication. That is normal for messengers (Signal/Telegram are similar).

## 4. Target audience & content
- **Target age group:** **18+** (recommended — avoids the stricter Families policy;
  an unmoderated messenger shouldn't target children). 13+ is possible but pulls in
  extra requirements.
- Appeals to children? → **No**.

## 5. Data safety (the important one)
Khandaq is p2p + E2E; **the developer operates no servers and receives no user data.**
- **Does your app collect or share any required user data types?**
  → Data is exchanged **between users only, end-to-end encrypted; the developer never
  receives or stores it.** Under Play's definitions, declare the data your app *handles*
  as processed **for app functionality**, **encrypted in transit**, **not shared**, and
  **not used for tracking/ads**:
  | Data type | Collected? | Shared? | Purpose | Notes |
  |---|---|---|---|---|
  | Messages (in-app) | No* | No | App functionality | p2p, E2E; not sent to developer |
  | Photos / videos | No* | No | App functionality | user-initiated p2p transfer |
  | Files & docs | No* | No | App functionality | user-initiated p2p transfer |
  | Contacts (Tox IDs) | No* | No | App functionality | stored locally on device |
  | Voice (calls) | No* | No | App functionality | real-time p2p, not recorded |
  \* "No" in Play's sense = **not transmitted to the developer**. All of the above stay
  on-device or go directly peer-to-peer, E2E encrypted.
- **Is all data encrypted in transit?** → **Yes**.
- **Can users request data deletion?** → Data lives on the device; **uninstalling the
  app removes it**. There is no server-side data to delete.
- **Data used to track users?** → **No**.
- Privacy policy URL: **https://khandaq.org/privacy.html**

> If Play insists you tick "collected" for messages/photos (some reviewers read p2p as
> collection), tick them as **collected, for app functionality, encrypted in transit,
> not shared, not for tracking** — still accurate and passes review.

## 6. Government / financial / health / news / COVID declarations
→ **No** to all (Khandaq is a general communication app).

## 7. Push notifications (FCM) — optional, not a blocker
The new package has no Firebase app yet, so push is inert. To enable later:
Firebase Console → project **khandaq-messenger** → Add app → package
`com.khandaq.messenger` → download `google-services.json` → replace the stub client.

---

## Promotion path (once the above are green)
Build 10321 is already **live on internal**. To go public:
1. Finish sections 1–6 above (Console).
2. Run a **closed test** (Play requires a closed test before production for new
   personal/most developer accounts). Promote 10321: Console → Testing → Closed
   testing, or `scripts/publish-play.py` with `TRACK="alpha"` (alpha = closed).
3. After the closed-test requirement is met, promote to **production**
   (`TRACK="production"`, `status="completed"` — or a staged rollout %).
